package com.example.cinema.client.audio.acoustics;

import com.example.cinema.block.SpeakerBlock;
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
	private static final double MIN_WALL_DISTANCE = 0.5;
	private static final double MIN_ROOM_DIMENSION = 1.5;
	private static final double WALL_MARGIN = 0.05;
	private static final double SKIP_OBSTACLE_EPSILON = 0.05;
	private static final int MAX_SKIP_ITERATIONS = 16;
	private static final int MATERIAL_SAMPLE_RADIUS = 2;
	private static final double SPEAKER_RADIUS = 48.0;
	private static final double FALLBACK_HALF_XZ = 6.0;
	private static final double FALLBACK_DOWN = 3.0;
	private static final double FALLBACK_UP = 4.0;

	private RoomDetector() {
	}

	public static RoomAcousticSnapshot detect(Level level, Player player, long version) {
		Vec3 eye = player.getEyePosition();
		AcousticListener listener = new AcousticListener(eye, player.getLookAngle(),
			new Vec3(0.0, 1.0, 0.0));

		SurfaceHit east = cast(level, player, eye, Direction.EAST);
		SurfaceHit west = cast(level, player, eye, Direction.WEST);
		SurfaceHit up = cast(level, player, eye, Direction.UP);
		SurfaceHit down = cast(level, player, eye, Direction.DOWN);
		SurfaceHit south = cast(level, player, eye, Direction.SOUTH);
		SurfaceHit north = cast(level, player, eye, Direction.NORTH);
		if (east == null || west == null || up == null || down == null
			|| south == null || north == null) {
			return fallback(level, player, listener, version, "missing room plane");
		}
		if (east.distance < MIN_WALL_DISTANCE || west.distance < MIN_WALL_DISTANCE
			|| up.distance < MIN_WALL_DISTANCE || down.distance < MIN_WALL_DISTANCE
			|| south.distance < MIN_WALL_DISTANCE || north.distance < MIN_WALL_DISTANCE) {
			return fallback(level, player, listener, version, "wall too close");
		}

		double westX = west.hit.getLocation().x;
		double eastX = east.hit.getLocation().x;
		double downY = down.hit.getLocation().y;
		double upY = up.hit.getLocation().y;
		double northZ = north.hit.getLocation().z;
		double southZ = south.hit.getLocation().z;
		double minX = Math.min(westX, eastX);
		double maxX = Math.max(westX, eastX);
		double minY = Math.min(downY, upY);
		double maxY = Math.max(downY, upY);
		double minZ = Math.min(northZ, southZ);
		double maxZ = Math.max(northZ, southZ);

		if (maxX - minX < MIN_ROOM_DIMENSION
			|| maxY - minY < MIN_ROOM_DIMENSION
			|| maxZ - minZ < MIN_ROOM_DIMENSION) {
			return fallback(level, player, listener, version, "room too small");
		}
		if (!contains(eye, minX, maxX, minY, maxY, minZ, maxZ)) {
			return fallback(level, player, listener, version, "listener outside box");
		}
		if (!mostlyAir(level, minX, maxX, minY, maxY, minZ, maxZ)) {
			return fallback(level, player, listener, version, "interior mostly solid");
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

		AcousticRoom room = AcousticRoom.box(
			new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ),
			surfaces, true, "");
		return new RoomAcousticSnapshot(version, level.getGameTime(), room, listener,
			scanSpeakers(level, eye));
	}

	private static RoomAcousticSnapshot fallback(Level level, Player player,
												 AcousticListener listener, long version,
												 String reason) {
		Vec3 eye = listener.eye();
		double minX = eye.x - FALLBACK_HALF_XZ;
		double maxX = eye.x + FALLBACK_HALF_XZ;
		double minZ = eye.z - FALLBACK_HALF_XZ;
		double maxZ = eye.z + FALLBACK_HALF_XZ;
		double minY = Math.max(level.getMinBuildHeight(), eye.y - FALLBACK_DOWN);
		double maxY = Math.min(level.getMaxBuildHeight(), eye.y + FALLBACK_UP);
		if (maxY <= minY) {
			maxY = minY + 1.0;
		}
		AcousticRoom room = AcousticRoom.box(
			new Vec3(minX, minY, minZ), new Vec3(maxX, maxY, maxZ),
			List.of(), false, reason);
		return new RoomAcousticSnapshot(version, level.getGameTime(), room, listener,
			scanSpeakers(level, eye));
	}

	private static boolean contains(Vec3 eye, double minX, double maxX,
									double minY, double maxY, double minZ, double maxZ) {
		return eye.x > minX + WALL_MARGIN && eye.x < maxX - WALL_MARGIN
			&& eye.y > minY + WALL_MARGIN && eye.y < maxY - WALL_MARGIN
			&& eye.z > minZ + WALL_MARGIN && eye.z < maxZ - WALL_MARGIN;
	}

	private static boolean mostlyAir(Level level, double minX, double maxX,
									 double minY, double maxY, double minZ, double maxZ) {
		int solid = 0;
		int total = 0;
		for (int ix = 0; ix < 3; ix++) {
			double x = minX + (maxX - minX) * (0.25 + 0.25 * ix);
			for (int iy = 0; iy < 3; iy++) {
				double y = minY + (maxY - minY) * (0.25 + 0.25 * iy);
				for (int iz = 0; iz < 3; iz++) {
					double z = minZ + (maxZ - minZ) * (0.25 + 0.25 * iz);
					BlockPos pos = BlockPos.containing(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (!state.getCollisionShape(level, pos).isEmpty()) {
						solid++;
					}
					total++;
				}
			}
		}
		return total == 0 || solid <= total / 2;
	}

	private static AcousticSurface surface(Level level, SurfaceHit hit,
										   AcousticSurface.Axis axis, double coordinate,
										   double normal, double minU, double maxU,
										   double minV, double maxV) {
		MaterialSample sample = sampleMaterial(level, hit.hit, axis);
		return new AcousticSurface(axis, coordinate, normal, minU, maxU, minV, maxV,
			sample.material, sample.samples);
	}

	private static MaterialSample sampleMaterial(Level level, BlockHitResult hit,
												 AcousticSurface.Axis axis) {
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

	private static SurfaceHit cast(Level level, Player player, Vec3 start,
								   Direction direction) {
		Vec3 dir = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
		Vec3 end = start.add(dir.scale(MAX_RAY_DISTANCE));
		Vec3 cursor = start;
		for (int i = 0; i < MAX_SKIP_ITERATIONS; i++) {
			BlockHitResult hit = level.clip(new ClipContext(cursor, end,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.BLOCK) {
				return null;
			}
			BlockState state = level.getBlockState(hit.getBlockPos());
			if (!AcousticMaterials.isIgnoredObstacle(state)) {
				return new SurfaceHit(hit, hit.getLocation().distanceTo(start));
			}
			cursor = hit.getLocation().add(dir.scale(skipDistance(hit, direction)));
			if (cursor.distanceTo(start) > MAX_RAY_DISTANCE) {
				return null;
			}
		}
		return null;
	}

	private static double skipDistance(BlockHitResult hit, Direction direction) {
		BlockPos pos = hit.getBlockPos();
		Vec3 location = hit.getLocation();
		double distance = switch (direction.getAxis()) {
			case X -> (direction.getStepX() > 0 ? pos.getX() + 1.0 : pos.getX()) - location.x;
			case Y -> (direction.getStepY() > 0 ? pos.getY() + 1.0 : pos.getY()) - location.y;
			case Z -> (direction.getStepZ() > 0 ? pos.getZ() + 1.0 : pos.getZ()) - location.z;
		};
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
					double centerY = pos.getY() + acousticCenterOffset(type);
					Vec3 position = new Vec3(pos.getX() + 0.5, centerY, pos.getZ() + 0.5);
					Direction facing = speaker.getBlockState().getValue(SpeakerBlock.FACING);
					speakers.add(new AcousticSpeaker(pos, speaker.role(), type, position,
						facing, speaker.levelDb(), profileId(speaker)));
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

	private static double acousticCenterOffset(SpeakerType type) {
		return switch (type) {
			case FLOORSTANDING -> 1.0;
			case BOOKSHELF -> 0.7;
			case CENTER -> 0.6;
			case SUBWOOFER -> 0.3;
		};
	}

	private record SurfaceHit(BlockHitResult hit, double distance) {
	}

	private record MaterialSample(AcousticMaterial material, int samples) {
	}
}
