package com.example.cinema.client.network;

import com.example.cinema.network.DeviceNamePayload;
import com.example.cinema.util.CinemaNames;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;

/** Sends device name changes from the projector/receiver GUIs. */
public final class DeviceNameClient {
	private DeviceNameClient() {
	}

	public static void setProjectorName(BlockPos projectorPos, String name) {
		send(projectorPos, DeviceNamePayload.Target.PROJECTOR, name);
	}

	public static void setReceiverName(BlockPos receiverPos, String name) {
		send(receiverPos, DeviceNamePayload.Target.RECEIVER, name);
	}

	public static void setLinkedScreenName(BlockPos projectorPos, String name) {
		send(projectorPos, DeviceNamePayload.Target.SCREEN, name);
	}

	private static void send(BlockPos anchorPos, DeviceNamePayload.Target target, String name) {
		ClientPlayNetworking.send(new DeviceNamePayload(anchorPos, target,
			CinemaNames.sanitize(name)));
	}
}
