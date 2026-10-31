package com.example.firstmod.client.render;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.ScreenBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the contiguous rectangular screen containing a block.
 *
 * <p>The screen plane is vertical. Only blocks with the same facing and on the
 * same plane are considered. This is deliberately a small scan: screens are
 * expected to be at most 16x16 blocks.
 */
public record ScreenGeometry(BlockPos master, Direction facing, int width, int height,
							 Vec3 right, Vec3 up) {
	private static final int MAX = 16;

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

	public static ScreenGeometry find(Level level, BlockPos pos, BlockState state) {
		Direction facing = state.getValue(ScreenBlock.FACING);
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		int stepX = alongX ? 1 : 0;
		int stepZ = alongX ? 0 : 1;

		int minH = 0;
		int maxH = 0;
		while (maxH < MAX && isScreen(level, pos.offset(stepX * (maxH + 1), 0, stepZ * (maxH + 1)), facing)) {
			maxH++;
		}
		while (minH > -MAX && isScreen(level, pos.offset(stepX * (minH - 1), 0, stepZ * (minH - 1)), facing)) {
			minH--;
		}

		int minY = 0;
		int maxY = 0;
		while (maxY < MAX && rowIsScreen(level, pos, stepX, stepZ, minH, maxH, maxY + 1, facing)) {
			maxY++;
		}
		while (minY > -MAX && rowIsScreen(level, pos, stepX, stepZ, minH, maxH, minY - 1, facing)) {
			minY--;
		}

		int width = maxH - minH + 1;
		int height = maxY - minY + 1;
		int masterX = pos.getX() + stepX * (facing == Direction.NORTH || facing == Direction.EAST ? maxH : minH);
		int masterZ = pos.getZ() + stepZ * (facing == Direction.NORTH || facing == Direction.EAST ? maxH : minH);
		BlockPos master = new BlockPos(masterX, pos.getY() + minY, masterZ);

		Vec3 right = switch (facing) {
			case NORTH -> new Vec3(-1, 0, 0);
			case SOUTH -> new Vec3(1, 0, 0);
			case WEST -> new Vec3(0, 0, 1);
			case EAST -> new Vec3(0, 0, -1);
			default -> new Vec3(1, 0, 0);
		};
		return new ScreenGeometry(master, facing, width, height, right, new Vec3(0, 1, 0));
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
		return state.is(FirstMod.SCREEN_BLOCK) && state.getValue(ScreenBlock.FACING) == facing;
	}
}
