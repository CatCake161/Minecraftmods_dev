package com.example.cinema.client.audio.acoustics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Speaker-centered candidate mirror discovery.
 *
 * <p>A2b deliberately does not gate reflections on {@code room.valid()}.
 * Six axis rays from each speaker find nearby walls, pillars and partial
 * structures even in open space; room surfaces with {@code samples > 0} are
 * optional seeds. Planes are deduplicated by {@code (axis, coordinate,
 * normal)} and cached per speaker/room/direction-set version.
 *
 * <p>The direction set is intentionally a constant so it can grow to 26
 * directions or diagonals later without touching the solver.
 */
public final class MirrorPlaneDiscovery {
	public static final int DIRECTION_SET_VERSION = 1;
	public static final MirrorPlaneDiscovery INSTANCE = new MirrorPlaneDiscovery();

	private static final double MAX_RAY_DISTANCE = 48.0;
	private static final double SKIP_OBSTACLE_EPSILON = 0.05;
	private static final int MAX_SKIP_ITERATIONS = 16;
	private static final int MAX_CACHE_ENTRIES = 64;
	private static final List<Vec3> PROBE_DIRECTIONS = List.of(
		new Vec3(1.0, 0.0, 0.0),
		new Vec3(-1.0, 0.0, 0.0),
		new Vec3(0.0, 1.0, 0.0),
		new Vec3(0.0, -1.0, 0.0),
		new Vec3(0.0, 0.0, 1.0),
		new Vec3(0.0, 0.0, -1.0));

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
				cursor = hit.getLocation().add(direction.scale(skipDistance(hit, direction)));
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

	private static double skipDistance(BlockHitResult hit, Vec3 direction) {
		BlockPos pos = hit.getBlockPos();
		Vec3 location = hit.getLocation();
		double distance;
		if (Math.abs(direction.x) >= Math.abs(direction.y)
			&& Math.abs(direction.x) >= Math.abs(direction.z)) {
			distance = (direction.x > 0 ? pos.getX() + 1.0 : pos.getX()) - location.x;
		} else if (Math.abs(direction.y) >= Math.abs(direction.z)) {
			distance = (direction.y > 0 ? pos.getY() + 1.0 : pos.getY()) - location.y;
		} else {
			distance = (direction.z > 0 ? pos.getZ() + 1.0 : pos.getZ()) - location.z;
		}
		return Math.max(0.1, Math.abs(distance) + SKIP_OBSTACLE_EPSILON);
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
