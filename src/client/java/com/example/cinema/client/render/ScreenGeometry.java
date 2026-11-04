package com.example.cinema.client.render;

import com.example.cinema.Cinema;
import com.example.cinema.block.ScreenBlock;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the contiguous rectangular screen containing a block.
 *
 * <p>The screen plane is vertical. Only blocks with the same facing and on the
 * same plane are considered. Scanning stops at the hard 64x64 limit, and the
 * resulting width/height are then clipped to the configured maximum
 * ({@link ScreenLimits#maxSize()}). Geometry is cached for one frame because
 * every screen block entity asks for the same rectangle while a frame renders.
 */
public record ScreenGeometry(BlockPos master, Direction facing, int width, int height,
							 Vec3 right, Vec3 up) {
	private static final int MAX_SCAN_PER_SIDE = ScreenLimits.HARD_MAX_SIZE - 1;
	private static final Long2ObjectOpenHashMap<ScreenGeometry> CACHE = new Long2ObjectOpenHashMap<>();

	/** Unit normal pointing out of the screen toward the audience. */
	public Vec3 normal() {
		return new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
	}

	/** Distance from the block origin to the visible screen surface. */
	public double frontOffset() {
		return facing.getStepX() > 0 || facing.getStepZ() > 0 ? 1.0 : 0.0;
	}

	/** World-space bottom-left corner of the visible screen surface. */
	public Vec3 surfaceOrigin() {
		Vec3 origin = Vec3.atLowerCornerOf(master).add(normal().scale(frontOffset()));
		if (right.x < 0.0) {
			origin = origin.add(1.0, 0.0, 0.0);
		}
		if (right.z < 0.0) {
			origin = origin.add(0.0, 0.0, 1.0);
		}
		return origin;
	}

	/** World-space center of the visible screen surface. */
	public Vec3 center() {
		return surfaceOrigin()
			.add(right.scale(width / 2.0))
			.add(up.scale(height / 2.0));
	}

	/** Surface origin relative to the master block's minimum corner. */
	public Vec3 localSurfaceOrigin() {
		return surfaceOrigin().subtract(Vec3.atLowerCornerOf(master));
	}

	/** Column of a block, 0 at the audience-left edge of the screen. */
	public int columnOf(BlockPos pos) {
		return (int) Math.round((pos.getX() - master.getX()) * right.x
			+ (pos.getZ() - master.getZ()) * right.z);
	}

	/** Row of a block, 0 at the bottom edge of the screen. */
	public int rowOf(BlockPos pos) {
		return pos.getY() - master.getY();
	}

	/** True when the block is inside the visible (possibly clipped) rectangle. */
	public boolean contains(BlockPos pos) {
		int column = columnOf(pos);
		int row = rowOf(pos);
		return column >= 0 && column < width && row >= 0 && row < height;
	}

	/** Drops the per-frame geometry cache. Called once at the start of a frame. */
	public static void beginFrame() {
		CACHE.clear();
	}

	public static ScreenGeometry find(Level level, BlockPos pos, BlockState state) {
		long key = pos.asLong();
		ScreenGeometry cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		Direction facing = state.getValue(ScreenBlock.FACING);
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		int stepX = alongX ? 1 : 0;
		int stepZ = alongX ? 0 : 1;

		int minH = 0;
		int maxH = 0;
		while (maxH < MAX_SCAN_PER_SIDE
			&& isScreen(level, pos.offset(stepX * (maxH + 1), 0, stepZ * (maxH + 1)), facing)) {
			maxH++;
		}
		while (minH > -MAX_SCAN_PER_SIDE
			&& isScreen(level, pos.offset(stepX * (minH - 1), 0, stepZ * (minH - 1)), facing)) {
			minH--;
		}

		int minY = 0;
		int maxY = 0;
		while (maxY < MAX_SCAN_PER_SIDE
			&& rowIsScreen(level, pos, stepX, stepZ, minH, maxH, maxY + 1, facing)) {
			maxY++;
		}
		while (minY > -MAX_SCAN_PER_SIDE
			&& rowIsScreen(level, pos, stepX, stepZ, minH, maxH, minY - 1, facing)) {
			minY--;
		}

		int limit = ScreenLimits.maxSize();
		int width = Math.min(maxH - minH + 1, limit);
		int height = Math.min(maxY - minY + 1, limit);
		int masterX = pos.getX()
			+ stepX * (facing == Direction.NORTH || facing == Direction.EAST ? maxH : minH);
		int masterZ = pos.getZ()
			+ stepZ * (facing == Direction.NORTH || facing == Direction.EAST ? maxH : minH);
		BlockPos master = new BlockPos(masterX, pos.getY() + minY, masterZ);

		Vec3 right = switch (facing) {
			case NORTH -> new Vec3(-1, 0, 0);
			case SOUTH -> new Vec3(1, 0, 0);
			case WEST -> new Vec3(0, 0, 1);
			case EAST -> new Vec3(0, 0, -1);
			default -> new Vec3(1, 0, 0);
		};
		ScreenGeometry geometry =
			new ScreenGeometry(master, facing, width, height, right, new Vec3(0, 1, 0));
		// Cache every block of the scanned rectangle, including columns/rows
		// beyond the configured limit, so their renderers skip without a rescan.
		for (int h = minH; h <= maxH; h++) {
			for (int y = minY; y <= maxY; y++) {
				CACHE.put(pos.offset(stepX * h, y, stepZ * h).asLong(), geometry);
			}
		}
		return geometry;
	}

	private static boolean rowIsScreen(Level level, BlockPos origin, int stepX, int stepZ,
									   int minH, int maxH, int y, Direction facing) {
		for (int h = minH; h <= maxH; h++) {
			BlockPos p = origin.offset(stepX * h, y, stepZ * h);
			if (!isScreen(level, p, facing)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isScreen(Level level, BlockPos pos, Direction facing) {
		BlockState state = level.getBlockState(pos);
		return state.is(Cinema.SCREEN_BLOCK) && state.getValue(ScreenBlock.FACING) == facing;
	}
}
