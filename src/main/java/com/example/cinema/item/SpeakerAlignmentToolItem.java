package com.example.cinema.item;

import com.example.cinema.block.FloorstandingSpeakerBlock;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Locale;

/**
 * Adjusts a speaker's acoustic aim in 10 degree steps.
 *
 * <p>Right-click advances, sneak-right-click rewinds. The block model keeps its
 * four-way placement facing; the acoustic yaw is stored in the block entity and
 * synchronized through the normal block update packet.
 */
public final class SpeakerAlignmentToolItem extends Item {
	public SpeakerAlignmentToolItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos pos = context.getClickedPos();
		BlockState clicked = level.getBlockState(pos);
		if (clicked.getBlock() instanceof FloorstandingSpeakerBlock
			&& clicked.getValue(FloorstandingSpeakerBlock.HALF) == DoubleBlockHalf.UPPER) {
			pos = pos.below();
		}
		if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) {
			return InteractionResult.PASS;
		}
		if (!level.isClientSide) {
			Player player = context.getPlayer();
			boolean reverse = player != null && player.isShiftKeyDown();
			speaker.cycleAcousticYaw(reverse);
			if (player != null) {
				player.displayClientMessage(Component.translatable(
					"message.cinema.speaker_alignment_tool.yaw",
					String.format(Locale.ROOT, "%.1f", speaker.acousticYawDegrees())), true);
			}
		}
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
