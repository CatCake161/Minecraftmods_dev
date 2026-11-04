package com.example.cinema.client.network;

import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.network.SpeakerNamePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Sends a speaker's custom name from its GUI to the server. */
public final class SpeakerNameClient {
	private SpeakerNameClient() {
	}

	public static void setName(BlockPos speakerPos, String name) {
		ClientPlayNetworking.send(new SpeakerNamePayload(speakerPos,
			SpeakerBlockEntity.sanitizeName(name)));
	}
}
