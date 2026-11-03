package com.example.cinema.client.network;

import com.example.cinema.client.media.MediaLibrary;
import com.example.cinema.network.AvReceiverControlPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Shared client-side sender for receiver control payloads. */
public final class AvReceiverControlClient {
	private AvReceiverControlClient() {
	}

	public static void sendSimple(BlockPos avReceiverPos, AvReceiverControlPayload.Action action) {
		send(avReceiverPos, action, "", 0L, "", 0.0, 0.0);
	}

	public static void sendValue(BlockPos avReceiverPos, AvReceiverControlPayload.Action action, double value) {
		send(avReceiverPos, action, "", 0L, "", 0.0, value);
	}

	public static void selectMedia(BlockPos avReceiverPos, MediaLibrary.Metadata metadata) {
		send(avReceiverPos, AvReceiverControlPayload.Action.SELECT_MEDIA, metadata.key(),
			metadata.size(), metadata.sha256(), metadata.durationSeconds(), 0.0);
	}

	public static void send(BlockPos avReceiverPos, AvReceiverControlPayload.Action action, String mediaKey,
							long mediaSize, String mediaSha256, double durationSeconds, double value) {
		ClientPlayNetworking.send(new AvReceiverControlPayload(avReceiverPos, action, mediaKey,
			mediaSize, mediaSha256, durationSeconds, value));
	}
}
