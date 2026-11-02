package com.example.firstmod.client.network;

import com.example.firstmod.network.ProjectorSettingsPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Sends the projector GUI's draft snapshot to the server. */
public final class ProjectorSettingsClient {
	private ProjectorSettingsClient() {
	}

	public static void send(BlockPos projectorPos, double throwRatio, double focusDistance,
							double keystoneVerticalDegrees, double keystoneHorizontalDegrees,
							double brightness, double ambientWash, boolean powered,
							boolean simFocus, boolean simKeystone,
							boolean simWash, boolean simWarmup) {
		ClientPlayNetworking.send(new ProjectorSettingsPayload(projectorPos, throwRatio,
			focusDistance, keystoneVerticalDegrees, keystoneHorizontalDegrees, brightness, ambientWash,
			powered, simFocus, simKeystone, simWash, simWarmup));
	}
}
