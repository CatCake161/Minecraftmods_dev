package com.example.firstmod.network;

import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import com.example.firstmod.media.MediaKey;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Validates and applies receiver control payloads on the logical server.
 */
public final class ReceiverControlHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private ReceiverControlHandler() {
	}

	public static void handle(ReceiverControlPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos receiverPos = payload.receiverPos();
		if (!level.isLoaded(receiverPos)
			|| player.blockPosition().distSqr(receiverPos) > MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE) {
			error(player, "AV receiver is out of range");
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(receiverPos);
		if (!(blockEntity instanceof AvReceiverBlockEntity receiver)) {
			error(player, "No AV receiver at " + receiverPos.toShortString());
			return;
		}
		if (payload.action() == null) {
			error(player, "Invalid AV receiver action");
			return;
		}
		switch (payload.action()) {
			case SELECT_MEDIA -> selectMedia(player, receiver, payload);
			case PLAY -> {
				if (AvReceiverBlockEntity.isAnotherReceiverActive(level, receiverPos)) {
					error(player, "Another AV receiver is already active; stop it first");
					return;
				}
				if (!receiver.play()) {
					error(player, "AV receiver has no media selected");
				}
			}
			case PAUSE -> receiver.pause();
			case STOP -> receiver.stop();
			case SEEK -> receiver.seek(payload.value());
			case SET_VOLUME -> receiver.setVolume(payload.value());
			case SET_MUTED -> receiver.setMuted(payload.value() >= 0.5);
			case LINK_PROJECTOR -> {
				BlockPos target = receiver.pairNearestProjector(level);
				if (target == null) {
					error(player, "No cinema projector within 48 blocks");
				} else {
					player.displayClientMessage(Component.literal(
						"AV receiver linked to projector " + target.toShortString()), false);
				}
			}
		}
	}

	private static void selectMedia(ServerPlayer player, AvReceiverBlockEntity receiver,
									ReceiverControlPayload payload) {
		String key = MediaKey.normalize(payload.mediaKey());
		if (key == null) {
			error(player, "Invalid media key");
			return;
		}
		if (payload.mediaSize() < 0 || payload.durationSeconds() < 0) {
			error(player, "Invalid media metadata");
			return;
		}
		if (AvReceiverBlockEntity.isAnotherReceiverActive(player.serverLevel(), receiver.getBlockPos())) {
			error(player, "Another AV receiver is already active; stop it first");
			return;
		}
		String sha256 = payload.mediaSha256() == null ? "" : payload.mediaSha256();
		if (!receiver.selectMedia(key, payload.mediaSize(), sha256, payload.durationSeconds())) {
			error(player, "AV receiver could not start playback");
			return;
		}
		player.displayClientMessage(Component.literal("AV receiver: playing " + key), false);
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
