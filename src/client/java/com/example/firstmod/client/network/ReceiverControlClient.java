package com.example.firstmod.client.network;

import com.example.firstmod.client.media.MediaLibrary;
import com.example.firstmod.network.ReceiverControlPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Shared client-side sender for receiver control payloads. */
public final class ReceiverControlClient {
	private ReceiverControlClient() {
	}

	public static void sendSimple(BlockPos receiverPos, ReceiverControlPayload.Action action) {
		send(receiverPos, action, "", 0L, "", 0.0, 0.0);
	}

	public static void sendValue(BlockPos receiverPos, ReceiverControlPayload.Action action, double value) {
		send(receiverPos, action, "", 0L, "", 0.0, value);
	}

	public static void selectMedia(BlockPos receiverPos, MediaLibrary.Metadata metadata) {
		send(receiverPos, ReceiverControlPayload.Action.SELECT_MEDIA, metadata.key(),
			metadata.size(), metadata.sha256(), metadata.durationSeconds(), 0.0);
	}

	public static void send(BlockPos receiverPos, ReceiverControlPayload.Action action, String mediaKey,
							long mediaSize, String mediaSha256, double durationSeconds, double value) {
		ClientPlayNetworking.send(new ReceiverControlPayload(receiverPos, action, mediaKey,
			mediaSize, mediaSha256, durationSeconds, value));
	}
}
