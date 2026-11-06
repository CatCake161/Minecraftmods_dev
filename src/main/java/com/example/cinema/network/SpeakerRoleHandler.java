package com.example.cinema.network;

import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.item.RemoteControlAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and applies the speaker role selected in the speaker GUI. */
public final class SpeakerRoleHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private SpeakerRoleHandler() {
	}

	public static void handle(SpeakerRolePayload payload, ServerPlayNetworking.Context context) {
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
		SpeakerRole role = SpeakerRole.byName(payload.role());
		if (!speaker.type().allows(role)) {
			error(player, "This speaker cannot use role " + role.label());
			return;
		}
		speaker.setRole(role);
		player.displayClientMessage(Component.literal(
			"Speaker role: " + role.label()), true);
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
