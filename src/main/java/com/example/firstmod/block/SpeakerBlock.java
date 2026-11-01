package com.example.firstmod.block;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.SpeakerBlockEntity;
import com.example.firstmod.block.entity.SpeakerRole;
import com.example.firstmod.block.entity.SpeakerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** One physical speaker. Right-click with an empty hand to cycle its valid channel roles. */
public class SpeakerBlock extends Block implements EntityBlock {
	public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

	private final SpeakerType type;

	public SpeakerBlock(SpeakerType type, Properties properties) {
		super(properties);
		this.type = type;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	public SpeakerType type() {
		return type;
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		// The driver faces the player who placed the speaker.
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new SpeakerBlockEntity(pos, state);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
											   BlockHitResult hit) {
		if (!level.isClientSide && level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
			SpeakerRole role = speaker.cycleRole();
			player.displayClientMessage(Component.translatable(
				"message.firstmod.speaker_role", speaker.type().displayName(), role.displayName()), true);
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
