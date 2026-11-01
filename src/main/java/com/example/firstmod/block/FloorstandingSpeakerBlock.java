package com.example.firstmod.block;

import com.example.firstmod.block.entity.SpeakerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Two-block-tall floorstanding speaker. The lower half owns the block entity;
 * the upper half is a dummy extension that is placed and removed with it.
 */
public class FloorstandingSpeakerBlock extends SpeakerBlock {
	public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

	public FloorstandingSpeakerBlock(Properties properties) {
		super(SpeakerType.FLOORSTANDING, properties);
		registerDefaultState(defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(HALF);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		BlockPos pos = context.getClickedPos();
		Level level = context.getLevel();
		if (pos.getY() < level.getMaxBuildHeight() - 1
			&& level.getBlockState(pos.above()).canBeReplaced(context)) {
			return defaultBlockState()
				.setValue(FACING, context.getHorizontalDirection().getOpposite())
				.setValue(HALF, DoubleBlockHalf.LOWER);
		}
		return null;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			return null;
		}
		return super.newBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
																  BlockEntityType<T> type) {
		if (level.isClientSide || state.getValue(HALF) != DoubleBlockHalf.LOWER) {
			return null;
		}
		// Speakers placed before the block became two blocks tall load as a
		// lone lower half. Extend them upward once there is room.
		return (tickLevel, pos, tickState, blockEntity) -> {
			BlockState above = tickLevel.getBlockState(pos.above());
			if (above.is(this) && above.getValue(HALF) == DoubleBlockHalf.UPPER) {
				return;
			}
			if (above.canBeReplaced()) {
				tickLevel.setBlock(pos.above(), tickState.setValue(HALF, DoubleBlockHalf.UPPER),
					Block.UPDATE_ALL);
			}
		};
	}

	@Override
	public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (state.getValue(HALF) == DoubleBlockHalf.LOWER) {
			level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											   BlockHitResult hit) {
		if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			BlockPos lowerPos = pos.below();
			BlockState lowerState = level.getBlockState(lowerPos);
			if (lowerState.is(this) && lowerState.getValue(HALF) == DoubleBlockHalf.LOWER) {
				return super.useWithoutItem(lowerState, level, lowerPos, player, hit);
			}
			return InteractionResult.PASS;
		}
		return super.useWithoutItem(state, level, pos, player, hit);
	}

	@Override
	protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
									 LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		DoubleBlockHalf half = state.getValue(HALF);
		if (direction.getAxis() == Direction.Axis.Y) {
			if (half == DoubleBlockHalf.UPPER && direction == Direction.DOWN) {
				if (!neighborState.is(this) || neighborState.getValue(HALF) != DoubleBlockHalf.LOWER) {
					return Blocks.AIR.defaultBlockState();
				}
			} else if (half == DoubleBlockHalf.LOWER && direction == Direction.UP) {
				// A lone lower half is a legacy one-block speaker; keep it.
				// Two lower halves stacked is invalid, so remove this one.
				if (neighborState.is(this) && neighborState.getValue(HALF) == DoubleBlockHalf.LOWER) {
					return Blocks.AIR.defaultBlockState();
				}
			}
		}
		return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
	}

	@Override
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
		if (!level.isClientSide && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
			// The loot table only drops from the lower half. Breaking the
			// upper half therefore removes the lower half explicitly and
			// drops one speaker from its state in survival.
			BlockPos lowerPos = pos.below();
			BlockState lowerState = level.getBlockState(lowerPos);
			if (lowerState.is(this) && lowerState.getValue(HALF) == DoubleBlockHalf.LOWER) {
				if (!player.isCreative()) {
					BlockEntity lowerBlockEntity = level.getBlockEntity(lowerPos);
					Block.dropResources(lowerState, level, lowerPos, lowerBlockEntity, player,
						player.getMainHandItem());
				}
				level.setBlock(lowerPos, Blocks.AIR.defaultBlockState(),
					Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
				level.levelEvent(player, 2001, lowerPos, Block.getId(lowerState));
			}
		}
		return super.playerWillDestroy(level, pos, state, player);
	}
}
