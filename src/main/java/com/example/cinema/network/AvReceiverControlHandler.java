package com.example.cinema.network;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.item.RemoteControlAccess;
import com.example.cinema.media.MediaKey;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Validates and applies receiver control payloads on the logical server.
 */
public final class AvReceiverControlHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private AvReceiverControlHandler() {
	}

	public static void handle(AvReceiverControlPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos avReceiverPos = payload.avReceiverPos();
		boolean near = player.blockPosition().distSqr(avReceiverPos)
			<= MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE;
		if (!level.isLoaded(avReceiverPos)
			|| (!near && !RemoteControlAccess.canControl(player, avReceiverPos,
				RemoteBinding.Kind.RECEIVER))) {
			error(player, "AV receiver is out of range");
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(avReceiverPos);
		if (!(blockEntity instanceof AvReceiverBlockEntity receiver)) {
			error(player, "No AV receiver at " + avReceiverPos.toShortString());
			return;
		}
		if (payload.action() == null) {
			error(player, "Invalid AV receiver action");
			return;
		}
		switch (payload.action()) {
			case SELECT_MEDIA -> selectMedia(player, receiver, payload);
			case PLAY -> {
				if (!receiver.play()) {
					error(player, "AV receiver has no media selected");
				}
			}
			case PAUSE -> receiver.pause();
			case STOP -> receiver.stop();
			case SEEK -> receiver.seek(payload.value());
			case SET_VOLUME_DB -> receiver.setVolumeDb(payload.value());
			case SET_MUTED -> receiver.setMuted(payload.value() >= 0.5);
		}
	}

	private static void selectMedia(ServerPlayer player, AvReceiverBlockEntity receiver,
									AvReceiverControlPayload payload) {
		String key = MediaKey.normalize(payload.mediaKey());
		if (key == null) {
			error(player, "Invalid media key");
			return;
		}
		if (payload.mediaSize() < 0 || payload.durationSeconds() < 0) {
			error(player, "Invalid media metadata");
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
