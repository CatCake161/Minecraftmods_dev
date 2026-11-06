package com.example.cinema.network;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.item.RemoteControlAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and applies the per-speaker level changed from the receiver GUI. */
public final class SpeakerLevelHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private SpeakerLevelHandler() {
	}

	public static void handle(SpeakerLevelPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos receiverPos = payload.receiverPos();
		BlockPos speakerPos = payload.speakerPos();
		boolean near = player.blockPosition().distSqr(receiverPos)
			<= MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE;
		if (!level.isLoaded(receiverPos)
			|| (!near && !RemoteControlAccess.canControl(player, receiverPos,
				RemoteBinding.Kind.RECEIVER))) {
			error(player, "AV receiver is out of range");
			return;
		}
		BlockEntity receiverEntity = level.getBlockEntity(receiverPos);
		if (!(receiverEntity instanceof AvReceiverBlockEntity receiver)) {
			error(player, "No AV receiver at " + receiverPos.toShortString());
			return;
		}
		if (!receiver.speakerTargets().contains(speakerPos)) {
			error(player, "Speaker is not linked to this receiver");
			return;
		}
		BlockEntity speakerEntity = level.getBlockEntity(speakerPos);
		if (!(speakerEntity instanceof SpeakerBlockEntity speaker)
			|| !receiverPos.equals(speaker.receiverPos())) {
			error(player, "No linked speaker at " + speakerPos.toShortString());
			return;
		}
		speaker.setLevelDb(payload.levelDb());
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
