package com.example.firstmod.client.network;

import com.example.firstmod.block.entity.SpeakerRole;
import com.example.firstmod.network.SpeakerRolePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Sends the speaker GUI's role selection to the server. */
public final class SpeakerRoleClient {
	private SpeakerRoleClient() {
	}

	public static void setRole(BlockPos speakerPos, SpeakerRole role) {
		ClientPlayNetworking.send(new SpeakerRolePayload(speakerPos, role.name()));
	}
}
