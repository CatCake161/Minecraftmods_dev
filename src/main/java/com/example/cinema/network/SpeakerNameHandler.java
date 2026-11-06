package com.example.cinema.network;

import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.item.RemoteControlAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and applies a speaker name change from the speaker GUI. */
public final class SpeakerNameHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private SpeakerNameHandler() {
	}

	public static void handle(SpeakerNamePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos pos = payload.speakerPos();
		boolean near = player.blockPosition().distSqr(pos) <= MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE;
		if (!level.isLoaded(pos)
			|| (!near && !RemoteControlAccess.canAccessLinkedSpeaker(player, pos))) {
			error(player, "Speaker is out of range");
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
			error(player, "No speaker at " + pos.toShortString());
			return;
		}
		speaker.setName(payload.name());
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
