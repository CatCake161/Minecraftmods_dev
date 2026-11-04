package com.example.cinema.block;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.SeatBlockEntity;
import com.example.cinema.entity.SeatEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Cinema seat. The block is decorative and right-clickable; it has no audio
 * calibration or listening-position role.
 */
public class SeatBlock extends Block implements EntityBlock {
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	private static final VoxelShape SEAT_SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 6.0, 14.0);

	public SeatBlock(Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// The chair faces the direction the player was looking when placing it.
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
	}

	@Override
	protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
								  CollisionContext context) {
		// Keep the whole cube clickable (the backrest is tall), but only the
		// cushion collides so a seated player is not pushed out of the block.
		return Shapes.block();
	}

	@Override
	protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
										   CollisionContext context) {
		return SEAT_SHAPE;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
											   Player player, BlockHitResult hitResult) {
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (player.isPassenger()) {
			return InteractionResult.PASS;
		}
		List<SeatEntity> seats = level.getEntitiesOfClass(SeatEntity.class,
			new AABB(pos).inflate(0.5));
		for (SeatEntity seat : seats) {
			if (seat.blockPosition().equals(pos) && seat.isAlive()
				&& !seat.getPassengers().isEmpty()) {
				return InteractionResult.PASS;
			}
		}
		for (SeatEntity seat : seats) {
			if (seat.blockPosition().equals(pos)) {
				seat.discard();
			}
		}
		Direction facing = state.getValue(FACING);
		SeatEntity seat = new SeatEntity(Cinema.seatEntityType, level);
		seat.setPos(pos.getX() + 0.5, pos.getY() + 0.375, pos.getZ() + 0.5);
		seat.setYRot(facing.toYRot());
		seat.setYHeadRot(facing.toYRot());
		level.addFreshEntity(seat);
		player.startRiding(seat, true);
		player.setYRot(facing.toYRot());
		player.setYHeadRot(facing.toYRot());
		return InteractionResult.SUCCESS;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SeatBlockEntity(pos, state);
	}
}
