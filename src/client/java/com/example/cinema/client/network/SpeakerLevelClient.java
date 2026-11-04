package com.example.cinema.client.network;

import com.example.cinema.network.SpeakerLevelPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Sends one linked speaker's level from the receiver GUI to the server. */
public final class SpeakerLevelClient {
	private SpeakerLevelClient() {
	}

	public static void setLevel(BlockPos receiverPos, BlockPos speakerPos, double levelDb) {
		ClientPlayNetworking.send(new SpeakerLevelPayload(receiverPos, speakerPos, levelDb));
	}
}
