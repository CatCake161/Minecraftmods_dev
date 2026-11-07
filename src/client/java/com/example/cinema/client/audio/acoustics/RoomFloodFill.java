package com.example.cinema.client.audio.acoustics;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Bounded six-neighbour flood fill used to validate the ray box and measure the
 * connected air volume.
 *
 * <p>Only air cells whose centres lie inside the detected box are visited. A
 * solid neighbour contributes one square meter of surface; a passable
 * neighbour outside the box is treated as an opening (doorway or missing
 * wall). Ignored devices such as speakers, seats, projectors and receivers are
 * traversed so they do not split the room. The fill stops at
 * {@link #MAX_CELLS}, the scan time budget, or an unloaded chunk and reports
 * {@code complete=false}; callers then keep the geometric box values.
 */
final class RoomFloodFill {
	static final int MAX_CELLS = 32 * 32 * 32;
	/** Keep a single scan under this budget so it cannot stall a frame. */
	private static final long MAX_SCAN_NANOS = 8_000_000L;
	private static final Direction[] DIRECTIONS = Direction.values();

	private RoomFloodFill() {
	}

	static RoomFill scan(Level level, Vec3 eye, Vec3 boxMin, Vec3 boxMax,
						 long gameTime) {
		int minX = Mth.floor(boxMin.x + 0.5);
		int minY = Mth.floor(boxMin.y + 0.5);
		int minZ = Mth.floor(boxMin.z + 0.5);
		int maxX = Mth.floor(boxMax.x - 0.5);
		int maxY = Mth.floor(boxMax.y - 0.5);
		int maxZ = Mth.floor(boxMax.z - 0.5);
		if (maxX < minX || maxY < minY || maxZ < minZ) {
			return null;
		}

		BlockPos start = new BlockPos(
			Mth.clamp(Mth.floor(eye.x), minX, maxX),
			Mth.clamp(Mth.floor(eye.y), minY, maxY),
			Mth.clamp(Mth.floor(eye.z), minZ, maxZ));
		if (!level.isLoaded(start) || !passable(level, start)) {
			return null;
		}

		long[] queue = new long[MAX_CELLS];
		LongOpenHashSet visited = new LongOpenHashSet(MAX_CELLS);
		int head = 0;
		int tail = 0;
		long startKey = start.asLong();
		queue[tail++] = startKey;
		visited.add(startKey);

		long deadline = System.nanoTime() + MAX_SCAN_NANOS;
		int cells = 0;
		boolean complete = true;
		boolean overflow = false;
		double area = 0.0;
		double openArea = 0.0;
		double low = 0.0;
		double mid = 0.0;
		double high = 0.0;
		int openFaces = 0;
		ReflectionSurfaceMerger merger = new ReflectionSurfaceMerger();
		int fillMinX = start.getX();
		int fillMinY = start.getY();
		int fillMinZ = start.getZ();
		int fillMaxX = start.getX();
		int fillMaxY = start.getY();
		int fillMaxZ = start.getZ();

		while (head < tail) {
			BlockPos pos = BlockPos.of(queue[head++]);
			cells++;
			fillMinX = Math.min(fillMinX, pos.getX());
			fillMinY = Math.min(fillMinY, pos.getY());
			fillMinZ = Math.min(fillMinZ, pos.getZ());
			fillMaxX = Math.max(fillMaxX, pos.getX());
			fillMaxY = Math.max(fillMaxY, pos.getY());
			fillMaxZ = Math.max(fillMaxZ, pos.getZ());

			for (Direction direction : DIRECTIONS) {
				BlockPos neighbor = pos.relative(direction);
				boolean inside = inside(neighbor, minX, maxX, minY, maxY, minZ, maxZ);
				if (!level.isLoaded(neighbor)) {
					complete = false;
					continue;
				}
				BlockState state = level.getBlockState(neighbor);
				if (passable(level, neighbor, state)) {
					if (!inside) {
						openArea += 1.0;
						area += 1.0;
						low += 1.0;
						mid += 1.0;
						high += 1.0;
						openFaces |= faceBit(direction);
						continue;
					}
					long key = neighbor.asLong();
					if (!visited.add(key)) {
						continue;
					}
					if (tail >= queue.length) {
						complete = false;
						overflow = true;
						break;
					}
					queue[tail++] = key;
				} else {
					AcousticMaterial material = AcousticMaterials.forState(state);
					area += 1.0;
					low += material.low();
					mid += material.mid();
					high += material.high();
					merger.add(direction, neighbor, material);
				}
			}
			if (overflow || cells >= MAX_CELLS) {
				complete = false;
				break;
			}
			if ((cells & 0x3F) == 0 && System.nanoTime() >= deadline) {
				complete = false;
				break;
			}
		}

		if (cells == 0 || area <= 0.0) {
			return null;
		}
		return new RoomFill(cells, area, openArea, low / area, mid / area, high / area,
			cells, complete, openFaces,
			new Vec3(fillMinX, fillMinY, fillMinZ),
			new Vec3(fillMaxX + 1.0, fillMaxY + 1.0, fillMaxZ + 1.0), gameTime,
			merger.merge());
	}

	private static boolean inside(BlockPos pos, int minX, int maxX, int minY, int maxY,
								  int minZ, int maxZ) {
		return pos.getX() >= minX && pos.getX() <= maxX
			&& pos.getY() >= minY && pos.getY() <= maxY
			&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
	}

	private static boolean passable(Level level, BlockPos pos) {
		return passable(level, pos, level.getBlockState(pos));
	}

	private static boolean passable(Level level, BlockPos pos, BlockState state) {
		if (AcousticMaterials.isIgnoredObstacle(state)) {
			return true;
		}
		if (!state.getFluidState().isEmpty()) {
			return false;
		}
		return state.getCollisionShape(level, pos).isEmpty();
	}

	private static int faceBit(Direction direction) {
		return switch (direction) {
			case WEST -> RoomFill.Face.X_NEG.bit();
			case EAST -> RoomFill.Face.X_POS.bit();
			case DOWN -> RoomFill.Face.Y_NEG.bit();
			case UP -> RoomFill.Face.Y_POS.bit();
			case NORTH -> RoomFill.Face.Z_NEG.bit();
			case SOUTH -> RoomFill.Face.Z_POS.bit();
		};
	}
}
