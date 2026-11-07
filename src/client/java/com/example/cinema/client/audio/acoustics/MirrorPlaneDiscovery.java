package com.example.cinema.client.audio.acoustics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speaker-centered candidate mirror discovery.
 *
 * <p>A2b deliberately does not gate reflections on {@code room.valid()}.
 * Twenty-six axis/edge/corner rays from each speaker find nearby walls,
 * pillars and partial structures even in open space; room surfaces with
 * {@code samples > 0} are optional seeds. Planes are deduplicated by
 * {@code (axis, coordinate, normal)} and cached per speaker/room/direction-set
 * version.
 *
 * <p>The direction set is intentionally a constant so it can grow further
 * without touching the solver.
 */
public final class MirrorPlaneDiscovery {
	public static final int DIRECTION_SET_VERSION = 2;
	public static final MirrorPlaneDiscovery INSTANCE = new MirrorPlaneDiscovery();

	private static final double MAX_RAY_DISTANCE = 48.0;
	private static final double SKIP_OBSTACLE_EPSILON = 0.05;
	private static final int MAX_SKIP_ITERATIONS = 16;
	private static final int MAX_CACHE_ENTRIES = 64;
	private static final List<Vec3> PROBE_DIRECTIONS = createProbeDirections();

	/** 6 face axes, 12 edge diagonals and 8 corner diagonals. */
	static List<Vec3> probeDirections() {
		return PROBE_DIRECTIONS;
	}

	private static List<Vec3> createProbeDirections() {
		List<Vec3> directions = new ArrayList<>(26);
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				for (int z = -1; z <= 1; z++) {
					if (x == 0 && y == 0 && z == 0) {
						continue;
					}
					directions.add(new Vec3(x, y, z).normalize());
				}
			}
		}
		return List.copyOf(directions);
	}

	private final Map<CacheKey, List<MirrorPlane>> cache = new LinkedHashMap<>();

	public List<MirrorPlane> discover(BlockGetter level, Vec3 origin, long roomVersion,
									  List<AcousticSurface> seeds) {
		if (origin == null) {
			return List.of();
		}
		Point3 originPoint = pointOf(origin);
		CacheKey key = level == null ? null
			: new CacheKey(System.identityHashCode(level), originPoint, roomVersion,
				DIRECTION_SET_VERSION);
		if (key != null) {
			List<MirrorPlane> cached = cache.get(key);
			if (cached != null) {
				return cached;
			}
		}

		Map<String, MirrorPlane> planes = new LinkedHashMap<>();
		if (seeds != null) {
			for (AcousticSurface surface : seeds) {
				if (surface == null || surface.samples() <= 0) {
					continue;
				}
				MirrorPlane plane = new MirrorPlane(axis(surface.axis()),
					surface.coordinate(), surface.normal(), true,
					surface.minU(), surface.maxU(), surface.minV(), surface.maxV(),
					pointOf(surface.center()));
				planes.putIfAbsent(plane.key(), plane);
			}
		}
		if (level != null) {
			for (Vec3 direction : PROBE_DIRECTIONS) {
				MirrorPlane plane = castPlane(level, origin, direction);
				if (plane != null) {
					planes.putIfAbsent(plane.key(), plane);
				}
			}
		}

		List<MirrorPlane> result = List.copyOf(planes.values());
		if (key != null) {
			cache.put(key, result);
			evictCache();
		}
		return result;
	}

	public void clear() {
		cache.clear();
	}

	private void evictCache() {
		while (cache.size() > MAX_CACHE_ENTRIES) {
			Iterator<CacheKey> iterator = cache.keySet().iterator();
			if (!iterator.hasNext()) {
				return;
			}
			iterator.next();
			iterator.remove();
		}
	}

	private static MirrorPlane castPlane(BlockGetter level, Vec3 origin, Vec3 direction) {
		Vec3 end = origin.add(direction.scale(MAX_RAY_DISTANCE));
		Vec3 cursor = origin;
		for (int i = 0; i < MAX_SKIP_ITERATIONS; i++) {
			BlockHitResult hit = level.clip(new ClipContext(cursor, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.BLOCK) {
				return null;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState state = level.getBlockState(pos);
			if (AcousticMaterials.isIgnoredObstacle(state)
				|| state.getCollisionShape(level, pos).isEmpty()) {
				cursor = hit.getLocation().add(direction.scale(
					skipDistance(level, pos, state, hit.getLocation(), direction)));
				if (cursor.distanceTo(origin) > MAX_RAY_DISTANCE) {
					return null;
				}
				continue;
			}
			return planeAt(hit, origin);
		}
		return null;
	}

	private static MirrorPlane planeAt(BlockHitResult hit, Vec3 origin) {
		Direction face = hit.getDirection();
		Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
		if (normal.dot(origin.subtract(hit.getLocation())) < 0.0) {
			normal = normal.scale(-1.0);
		}
		MirrorPlane.Axis axis = switch (face.getAxis()) {
			case X -> MirrorPlane.Axis.X;
			case Y -> MirrorPlane.Axis.Y;
			case Z -> MirrorPlane.Axis.Z;
		};
		double coordinate = switch (axis) {
			case X -> hit.getLocation().x;
			case Y -> hit.getLocation().y;
			case Z -> hit.getLocation().z;
		};
		double sign = switch (axis) {
			case X -> normal.x;
			case Y -> normal.y;
			case Z -> normal.z;
		};
		return MirrorPlane.unbounded(axis, coordinate, sign,
			pointOf(hit.getLocation()));
	}

	/**
	 * Distance along the ray to leave an ignored obstacle. Uses the actual
	 * collision bounds so diagonal rays cannot stop inside the block and
	 * repeatedly hit the same obstacle.
	 */
	private static double skipDistance(BlockGetter level, BlockPos pos, BlockState state,
									   Vec3 location, Vec3 direction) {
		AABB box = state.getCollisionShape(level, pos).bounds()
			.move(pos.getX(), pos.getY(), pos.getZ());
		double distance = Double.POSITIVE_INFINITY;
		distance = exitDistance(distance, location.x, direction.x, box.minX, box.maxX);
		distance = exitDistance(distance, location.y, direction.y, box.minY, box.maxY);
		distance = exitDistance(distance, location.z, direction.z, box.minZ, box.maxZ);
		if (!Double.isFinite(distance)) {
			return SKIP_OBSTACLE_EPSILON;
		}
		return Math.max(0.1, distance + SKIP_OBSTACLE_EPSILON);
	}

	private static double exitDistance(double current, double origin, double direction,
									   double min, double max) {
		if (direction > 1.0e-9) {
			return Math.min(current, (max - origin) / direction);
		}
		if (direction < -1.0e-9) {
			return Math.min(current, (min - origin) / direction);
		}
		return current;
	}

	private static MirrorPlane.Axis axis(AcousticSurface.Axis axis) {
		return switch (axis) {
			case X -> MirrorPlane.Axis.X;
			case Y -> MirrorPlane.Axis.Y;
			case Z -> MirrorPlane.Axis.Z;
		};
	}

	private static Point3 pointOf(Vec3 point) {
		return new Point3(point.x, point.y, point.z);
	}

	private record CacheKey(int levelIdentity, Point3 origin, long roomVersion,
							int directionVersion) {
	}
}
