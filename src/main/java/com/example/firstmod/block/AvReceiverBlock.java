package com.example.firstmod.block;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** AV receiver chassis; media and playback state live in the block entity. */
public class AvReceiverBlock extends Block implements EntityBlock {
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

	public AvReceiverBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// The front panel faces the player who placed the receiver.
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new AvReceiverBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
																  BlockEntityType<T> type) {
		if (type != FirstMod.AV_RECEIVER_BLOCK_ENTITY) {
			return null;
		}
		return (tickLevel, pos, tickState, blockEntity) -> {
			if (blockEntity instanceof AvReceiverBlockEntity receiver) {
				AvReceiverBlockEntity.tick(tickLevel, pos, tickState, receiver);
			}
		};
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											   BlockHitResult hit) {
		if (!level.isClientSide && level.getBlockEntity(pos) instanceof AvReceiverBlockEntity receiver) {
			player.displayClientMessage(receiver.status(), false);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
