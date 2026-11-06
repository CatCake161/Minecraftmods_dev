package com.example.cinema.client.audio.acoustics;

import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.audio.speakerdata.SpeakerDataAssignments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A1 room and speaker scanner.
 *
 * <p>Six bounded rays from the player's eyes find the nearest room planes.
 * Device blocks (speakers/seats/projectors/receivers) are skipped so a nearby
 * speaker does not become a wall. The resulting box is accepted only when it
 * contains the listener, has sensible dimensions, and its interior is mostly
 * non-solid. A failed scan produces a fixed fallback box with
 * {@code valid=false}.
 */
public final class RoomDetector {
	private static final double MAX_RAY_DISTANCE = 48.0;
	private static final double MIN_ROOM_DIMENSION = 1.5;
	private static final double WALL_MARGIN = 0.05;
	private static final double SKIP_OBSTACLE_EPSILON = 0.05;
	private static final double[] FAN_OFFSETS = {-0.16, -0.08, 0.0, 0.08, 0.16};
	private static final int MAX_SKIP_ITERATIONS = 16;
	private static final int MAX_BOUNCES = 2;
	private static final int MATERIAL_SAMPLE_RADIUS = 4;
	private static final double SPEAKER_RADIUS = 48.0;
	private static final double PLANE_STABILIZE_MARGIN = 1.5;
	/** Missing-axis estimate: 16 m total span, centered or from a known plane. */
	private static final double FALLBACK_HALF_XZ = 8.0;
	/** Reuse a flood fill for this long before checking for block changes. */
	private static final long FILL_REFRESH_TICKS = 200L;
	private static final double FILL_CONTAIN_MARGIN = 1.0;

	private RoomDetector() {
	}

	public static RoomAcousticSnapshot detect(Level level, Player player, long version,
											  RoomAcousticSnapshot previous) {
		return detect(level, player, version, previous, false);
	}

	public static RoomAcousticSnapshot detect(Level level, Player player, long version,
											  RoomAcousticSnapshot previous,
											  boolean forceFill) {
		Vec3 eye = player.getEyePosition();
		AcousticListener listener = new AcousticListener(eye, player.getLookAngle(),
			new Vec3(0.0, 1.0, 0.0));

		SurfaceHit east = castPlane(level, player, eye, Direction.EAST);
		SurfaceHit west = castPlane(level, player, eye, Direction.WEST);
		SurfaceHit up = castPlane(level, player, eye, Direction.UP);
		SurfaceHit down = castPlane(level, player, eye, Direction.DOWN);
		SurfaceHit south = castPlane(level, player, eye, Direction.SOUTH);
		SurfaceHit north = castPlane(level, player, eye, Direction.NORTH);
		if (east == null || west == null || up == null || down == null
			|| south == null || north == null) {
			return fallback(level, player, listener, version, "missing room plane",
				east, west, up, down, south, north, previous, forceFill);
		}

		double westX = west.planeCoordinate;
		double eastX = east.planeCoordinate;
		double downY = down.planeCoordinate;
		double upY = up.planeCoordinate;
		double northZ = north.planeCoordinate;
		double southZ = south.planeCoordinate;
		double minX = Math.min(westX, eastX);
		double maxX = Math.max(westX, eastX);
		double minY = Math.min(downY, upY);
		double maxY = Math.max(downY, upY);
		double minZ = Math.min(northZ, southZ);
		double maxZ = Math.max(northZ, southZ);

		if (previous != null && previous.room().valid()) {
			AcousticRoom old = previous.room();
			minX = stabilize(minX, old.min().x);
			maxX = stabilize(maxX, old.max().x);
			minY = stabilize(minY, old.min().y);
			maxY = stabilize(maxY, old.max().y);
			minZ = stabilize(minZ, old.min().z);
			maxZ = stabilize(maxZ, old.max().z);
		}

		if (maxX - minX < MIN_ROOM_DIMENSION
			|| maxY - minY < MIN_ROOM_DIMENSION
			|| maxZ - minZ < MIN_ROOM_DIMENSION) {
			return fallback(level, player, listener, version, "room too small",
				east, west, up, down, south, north, previous, forceFill);
		}
		if (!contains(eye, minX, maxX, minY, maxY, minZ, maxZ)) {
			return fallback(level, player, listener, version, "listener outside box",
				east, west, up, down, south, north, previous, forceFill);
		}
		if (!hasOpenInterior(level, minX, maxX, minY, maxY, minZ, maxZ)) {
			return fallback(level, player, listener, version, "interior fully solid",
				east, west, up, down, south, north, previous, forceFill);
		}

		List<AcousticSurface> surfaces = new ArrayList<>(6);
		surfaces.add(surface(level, east, AcousticSurface.Axis.X, eastX, -1.0,
			minY, maxY, minZ, maxZ));
		surfaces.add(surface(level, west, AcousticSurface.Axis.X, westX, 1.0,
			minY, maxY, minZ, maxZ));
		surfaces.add(surface(level, up, AcousticSurface.Axis.Y, upY, -1.0,
			minX, maxX, minZ, maxZ));
		surfaces.add(surface(level, down, AcousticSurface.Axis.Y, downY, 1.0,
			minX, maxX, minZ, maxZ));
		surfaces.add(surface(level, south, AcousticSurface.Axis.Z, southZ, -1.0,
			minX, maxX, minY, maxY));
		surfaces.add(surface(level, north, AcousticSurface.Axis.Z, northZ, 1.0,
			minX, maxX, minY, maxY));

		RoomFill fill = resolveFill(level, eye, minX, minY, minZ, maxX, maxY, maxZ,
			previous, level.getGameTime(), forceFill);
		AcousticRoom room = AcousticRoom.box(
			new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ),
			surfaces, true, "", fill);
		return new RoomAcousticSnapshot(version, level.getGameTime(), room, listener,
			scanSpeakers(level, eye));
	}

	private static RoomAcousticSnapshot fallback(Level level, Player player,
												 AcousticListener listener, long version,
												 String reason, SurfaceHit east, SurfaceHit west,
												 SurfaceHit up, SurfaceHit down,
												 SurfaceHit south, SurfaceHit north,
												 RoomAcousticSnapshot previous,
												 boolean forceFill) {
		Vec3 eye = listener.eye();
		double[] xSpan = resolveAxis(west, east, eye.x);
		double[] zSpan = resolveAxis(north, south, eye.z);
		double[] ySpan = resolveAxis(down, up, eye.y);
		double minX = xSpan[0];
		double maxX = xSpan[1];
		double minZ = zSpan[0];
		double maxZ = zSpan[1];
		double minY = Math.max(level.getMinBuildHeight(), ySpan[0]);
		double maxY = Math.min(level.getMaxBuildHeight(), ySpan[1]);
		if (maxY <= minY) {
			maxY = Math.min(level.getMaxBuildHeight(), minY + 1.0);
		}

		List<AcousticSurface> surfaces = new ArrayList<>(6);
		addFallbackSurface(surfaces, level, east, AcousticSurface.Axis.X, maxX, -1.0,
			minY, maxY, minZ, maxZ);
		addFallbackSurface(surfaces, level, west, AcousticSurface.Axis.X, minX, 1.0,
			minY, maxY, minZ, maxZ);
		addFallbackSurface(surfaces, level, up, AcousticSurface.Axis.Y, maxY, -1.0,
			minX, maxX, minZ, maxZ);
		addFallbackSurface(surfaces, level, down, AcousticSurface.Axis.Y, minY, 1.0,
			minX, maxX, minZ, maxZ);
		addFallbackSurface(surfaces, level, south, AcousticSurface.Axis.Z, maxZ, -1.0,
			minX, maxX, minY, maxY);
		addFallbackSurface(surfaces, level, north, AcousticSurface.Axis.Z, minZ, 1.0,
			minX, maxX, minY, maxY);

		RoomFill fill = resolveFill(level, eye, minX, minY, minZ, maxX, maxY, maxZ,
			previous, level.getGameTime(), forceFill);
		AcousticRoom room = AcousticRoom.box(
			new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ),
			surfaces, false, reason, fill);
		return new RoomAcousticSnapshot(version, level.getGameTime(), room, listener,
			scanSpeakers(level, eye));
	}

	/**
	 * Reuses the previous fill while the listener is still inside it and it is
	 * younger than {@link #FILL_REFRESH_TICKS}. An incomplete fill is reused for
	 * the same interval because rescanning a capped or unloaded region cannot
	 * improve it. A forced refresh from the debug command always rescans.
	 */
	private static RoomFill resolveFill(Level level, Vec3 eye,
										double minX, double minY, double minZ,
										double maxX, double maxY, double maxZ,
										RoomAcousticSnapshot previous, long now,
										boolean forceFill) {
		if (!forceFill && previous != null) {
			RoomFill old = previous.room().fill();
			if (old != null) {
				long age = now - old.gameTime();
				if (age >= 0L && age < FILL_REFRESH_TICKS
					&& (!old.complete()
					|| (old.contains(eye, FILL_CONTAIN_MARGIN)
					&& fillFits(old, minX, minY, minZ, maxX, maxY, maxZ)))) {
					return old;
				}
			}
		}
		return RoomFloodFill.scan(level, eye, new Vec3(minX, minY, minZ),
			new Vec3(maxX, maxY, maxZ), now);
	}

	private static boolean fillFits(RoomFill fill, double minX, double minY, double minZ,
									double maxX, double maxY, double maxZ) {
		return fill.min().x >= minX - FILL_CONTAIN_MARGIN
			&& fill.min().y >= minY - FILL_CONTAIN_MARGIN
			&& fill.min().z >= minZ - FILL_CONTAIN_MARGIN
			&& fill.max().x <= maxX + FILL_CONTAIN_MARGIN
			&& fill.max().y <= maxY + FILL_CONTAIN_MARGIN
			&& fill.max().z <= maxZ + FILL_CONTAIN_MARGIN;
	}

	/**
	 * Uses both real planes when available. If exactly one is known, keeps that
	 * real coordinate and extends 16 m across the listener; if both are missing,
	 * uses a listener-centered 16 m span.
	 */
	private static double[] resolveAxis(SurfaceHit negative, SurfaceHit positive,
										double eye) {
		double span = FALLBACK_HALF_XZ * 2.0;
		if (negative != null && positive != null) {
			return new double[]{Math.min(negative.planeCoordinate, positive.planeCoordinate),
				Math.max(negative.planeCoordinate, positive.planeCoordinate)};
		}
		if (negative != null) {
			double min = negative.planeCoordinate;
			return new double[]{min, Math.max(min + span, eye + FALLBACK_HALF_XZ)};
		}
		if (positive != null) {
			double max = positive.planeCoordinate;
			return new double[]{Math.min(max - span, eye - FALLBACK_HALF_XZ), max};
		}
		return new double[]{eye - FALLBACK_HALF_XZ, eye + FALLBACK_HALF_XZ};
	}

	private static void addFallbackSurface(List<AcousticSurface> surfaces, Level level,
										   SurfaceHit hit, AcousticSurface.Axis axis,
										   double coordinate, double normal,
										   double minU, double maxU,
										   double minV, double maxV) {
		if (hit != null) {
			surfaces.add(surface(level, hit, axis, coordinate, normal, minU, maxU,
				minV, maxV));
		} else {
			surfaces.add(new AcousticSurface(axis, coordinate, normal, minU, maxU,
				minV, maxV, AcousticMaterials.DEFAULT, 0));
		}
	}

	private static double stabilize(double current, double previous) {
		return Math.abs(current - previous) <= PLANE_STABILIZE_MARGIN ? previous : current;
	}

	private static boolean contains(Vec3 eye, double minX, double maxX,
									double minY, double maxY, double minZ, double maxZ) {
		return eye.x > minX + WALL_MARGIN && eye.x < maxX - WALL_MARGIN
			&& eye.y > minY + WALL_MARGIN && eye.y < maxY - WALL_MARGIN
			&& eye.z > minZ + WALL_MARGIN && eye.z < maxZ - WALL_MARGIN;
	}

	/**
	 * Very loose sanity check: reject only a box whose sampled interior is
	 * entirely solid. Furniture and non-rectangular corners are common, so a
	 * majority-solid rule produces too many false fallbacks near room corners.
	 */
	private static boolean hasOpenInterior(Level level, double minX, double maxX,
										   double minY, double maxY, double minZ, double maxZ) {
		int solid = 0;
		int total = 0;
		for (int ix = 1; ix <= 2; ix++) {
			double x = minX + (maxX - minX) * (ix / 3.0);
			for (int iy = 1; iy <= 2; iy++) {
				double y = minY + (maxY - minY) * (iy / 3.0);
				for (int iz = 1; iz <= 2; iz++) {
					double z = minZ + (maxZ - minZ) * (iz / 3.0);
					BlockPos pos = BlockPos.containing(x, y, z);
					if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
						solid++;
					}
					total++;
				}
			}
		}
		return total == 0 || solid < total;
	}

	private static AcousticSurface surface(Level level, SurfaceHit hit,
										   AcousticSurface.Axis axis, double coordinate,
										   double normal, double minU, double maxU,
										   double minV, double maxV) {
		MaterialSample sample = sampleMaterial(level, hit.hit, axis, minU, maxU,
			minV, maxV);
		return new AcousticSurface(axis, coordinate, normal, minU, maxU, minV, maxV,
			sample.material, sample.samples);
	}

	private static MaterialSample sampleMaterial(Level level, BlockHitResult hit,
												 AcousticSurface.Axis axis, double minU,
												 double maxU, double minV, double maxV) {
		BlockPos center = hit.getBlockPos();
		double low = 0.0;
		double mid = 0.0;
		double high = 0.0;
		double scattering = 0.0;
		int samples = 0;
		for (int du = -MATERIAL_SAMPLE_RADIUS; du <= MATERIAL_SAMPLE_RADIUS; du++) {
			for (int dv = -MATERIAL_SAMPLE_RADIUS; dv <= MATERIAL_SAMPLE_RADIUS; dv++) {
				BlockPos pos = switch (axis) {
					case X -> center.offset(0, du, dv);
					case Y -> center.offset(du, 0, dv);
					case Z -> center.offset(du, dv, 0);
				};
				if (!insidePlane(pos, axis, minU, maxU, minV, maxV)) {
					continue;
				}
				BlockState state = level.getBlockState(pos);
				if (state.isAir() || AcousticMaterials.isIgnoredObstacle(state)) {
					continue;
				}
				AcousticMaterial material = AcousticMaterials.forState(state);
				low += material.low();
				mid += material.mid();
				high += material.high();
				scattering += material.scattering();
				samples++;
			}
		}
		if (samples == 0) {
			AcousticMaterial material = AcousticMaterials.forState(level.getBlockState(center));
			return new MaterialSample(material, 1);
		}
		return new MaterialSample(AcousticMaterial.mix("mixed", low, mid, high,
			scattering, samples), samples);
	}

	private static boolean insidePlane(BlockPos pos, AcousticSurface.Axis axis,
									   double minU, double maxU, double minV, double maxV) {
		double u;
		double v;
		switch (axis) {
			case X -> {
				u = pos.getY() + 0.5;
				v = pos.getZ() + 0.5;
			}
			case Y -> {
				u = pos.getX() + 0.5;
				v = pos.getZ() + 0.5;
			}
			case Z -> {
				u = pos.getX() + 0.5;
				v = pos.getY() + 0.5;
			}
			default -> throw new IllegalStateException();
		}
		return u >= minU && u <= maxU && v >= minV && v <= maxV;
	}

	/**
	 * Casts a small fan around the axis and picks the median plane hit. This
	 * avoids a single ray slipping through a doorway or grazing a corner.
	 */
	private static SurfaceHit castPlane(Level level, Player player, Vec3 start,
										Direction axis) {
		Vec3 axisVector = new Vec3(axis.getStepX(), axis.getStepY(), axis.getStepZ());
		Vec3 u;
		Vec3 v;
		switch (axis.getAxis()) {
			case X -> {
				u = new Vec3(0.0, 1.0, 0.0);
				v = new Vec3(0.0, 0.0, 1.0);
			}
			case Y -> {
				u = new Vec3(1.0, 0.0, 0.0);
				v = new Vec3(0.0, 0.0, 1.0);
			}
			case Z -> {
				u = new Vec3(1.0, 0.0, 0.0);
				v = new Vec3(0.0, 1.0, 0.0);
			}
			default -> throw new IllegalStateException();
		}
		List<SurfaceHit> hits = new ArrayList<>(FAN_OFFSETS.length * FAN_OFFSETS.length);
		for (double uOffset : FAN_OFFSETS) {
			for (double vOffset : FAN_OFFSETS) {
				Vec3 direction = axisVector
					.add(u.scale(uOffset))
					.add(v.scale(vOffset))
					.normalize();
				SurfaceHit hit = castProbe(level, player, start, axis, direction);
				if (hit != null) {
					hits.add(hit);
				}
			}
		}
		if (hits.isEmpty()) {
			return null;
		}
		Direction expectedFace = expectedFace(axis);
		List<SurfaceHit> filtered = new ArrayList<>();
		for (SurfaceHit hit : hits) {
			if (hit.hit.getDirection() == expectedFace) {
				filtered.add(hit);
			}
		}
		List<SurfaceHit> chosen = filtered.isEmpty() ? hits : filtered;
		double median = medianCoordinate(chosen);
		SurfaceHit representative = chosen.get(0);
		double best = Double.MAX_VALUE;
		for (SurfaceHit hit : chosen) {
			double distance = Math.abs(hit.planeCoordinate - median);
			if (distance < best) {
				best = distance;
				representative = hit;
			}
		}
		return new SurfaceHit(representative.hit, representative.distance, median);
	}

	/**
	 * Casts one probe and, when it hits a non-target wall, reflects it up to
	 * {@link #MAX_BOUNCES} times looking for the target plane. This lets a
	 * corner or a partially blocked side find the room plane through a bounce.
	 */
	private static SurfaceHit castProbe(Level level, Player player, Vec3 start,
										Direction axis, Vec3 direction) {
		Vec3 cursor = start;
		Vec3 ray = direction;
		Direction expected = expectedFace(axis);
		for (int bounce = 0; bounce <= MAX_BOUNCES; bounce++) {
			SurfaceHit hit = castRay(level, player, cursor, axis, ray);
			if (hit == null) {
				return null;
			}
			if (hit.hit.getDirection() == expected) {
				return hit;
			}
			Vec3 normal = new Vec3(hit.hit.getDirection().getStepX(),
				hit.hit.getDirection().getStepY(), hit.hit.getDirection().getStepZ());
			double dot = ray.dot(normal);
			ray = ray.subtract(normal.scale(2.0 * dot)).normalize();
			cursor = hit.hit.getLocation().add(ray.scale(SKIP_OBSTACLE_EPSILON));
			if (cursor.distanceTo(start) > MAX_RAY_DISTANCE) {
				return null;
			}
		}
		return null;
	}

	private static SurfaceHit castRay(Level level, Player player, Vec3 start,
									  Direction axis, Vec3 direction) {
		Vec3 end = start.add(direction.scale(MAX_RAY_DISTANCE));
		Vec3 cursor = start;
		for (int i = 0; i < MAX_SKIP_ITERATIONS; i++) {
			BlockHitResult hit = level.clip(new ClipContext(cursor, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.BLOCK) {
				return null;
			}
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (!AcousticMaterials.isIgnoredObstacle(state)) {
				double coordinate = switch (axis.getAxis()) {
					case X -> hit.getLocation().x;
					case Y -> hit.getLocation().y;
					case Z -> hit.getLocation().z;
				};
				return new SurfaceHit(hit, hit.getLocation().distanceTo(start), coordinate);
			}
			cursor = hit.getLocation().add(direction.scale(skipDistance(hit, direction)));
			if (cursor.distanceTo(start) > MAX_RAY_DISTANCE) {
				return null;
			}
		}
		return null;
	}

	private static Direction expectedFace(Direction axis) {
		return switch (axis) {
			case EAST -> Direction.WEST;
			case WEST -> Direction.EAST;
			case UP -> Direction.DOWN;
			case DOWN -> Direction.UP;
			case SOUTH -> Direction.NORTH;
			case NORTH -> Direction.SOUTH;
		};
	}

	private static double medianCoordinate(List<SurfaceHit> hits) {
		double[] values = new double[hits.size()];
		for (int i = 0; i < hits.size(); i++) {
			values[i] = hits.get(i).planeCoordinate;
		}
		java.util.Arrays.sort(values);
		int middle = values.length / 2;
		return values.length % 2 == 0
			? (values[middle - 1] + values[middle]) * 0.5
			: values[middle];
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

	private static List<AcousticSpeaker> scanSpeakers(Level level, Vec3 listener) {
		List<AcousticSpeaker> speakers = new ArrayList<>();
		BlockPos centerPos = BlockPos.containing(listener);
		int centerChunkX = centerPos.getX() >> 4;
		int centerChunkZ = centerPos.getZ() >> 4;
		int chunkRadius = (int) Math.ceil(SPEAKER_RADIUS / 16.0);
		double radiusSqr = SPEAKER_RADIUS * SPEAKER_RADIUS;
		for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
			for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
				ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (!(chunk instanceof LevelChunk levelChunk)) {
					continue;
				}
				for (BlockEntity blockEntity : levelChunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
						continue;
					}
					BlockPos pos = speaker.getBlockPos();
					if (Vec3.atCenterOf(pos).distanceToSqr(listener) > radiusSqr) {
						continue;
					}
					SpeakerType type = speaker.type();
					Vec3 forward = speaker.acousticForward();
					Vec3 position = speaker.acousticFrontPosition();
					speakers.add(new AcousticSpeaker(pos, speaker.role(), type, position,
						forward, speaker.acousticYawDegrees(), speaker.levelDb(),
						profileId(speaker)));
				}
			}
		}
		speakers.sort(Comparator.comparingInt(speaker -> speaker.role().ordinal()));
		return speakers;
	}

	private static String profileId(SpeakerBlockEntity speaker) {
		try {
			return SpeakerDataAssignments.settingsFor(speaker).profileId();
		} catch (Throwable t) {
			return "";
		}
	}

	private record SurfaceHit(BlockHitResult hit, double distance, double planeCoordinate) {
	}

	private record MaterialSample(AcousticMaterial material, int samples) {
	}
}



