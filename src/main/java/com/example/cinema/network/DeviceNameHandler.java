package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.ScreenBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and applies projector/receiver/screen names. */
public final class DeviceNameHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private DeviceNameHandler() {
	}

	public static void handle(DeviceNamePayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos anchor = payload.anchorPos();
		if (!level.isLoaded(anchor)
			|| player.blockPosition().distSqr(anchor) > MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE) {
			error(player, "Device is out of range");
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(anchor);
		switch (payload.target()) {
			case PROJECTOR -> {
				if (blockEntity instanceof ProjectorBlockEntity projector) {
					projector.setName(payload.name());
				} else {
					error(player, "No projector at " + anchor.toShortString());
				}
			}
			case RECEIVER -> {
				if (blockEntity instanceof AvReceiverBlockEntity receiver) {
					receiver.setName(payload.name());
				} else {
					error(player, "No AV receiver at " + anchor.toShortString());
				}
			}
			case SCREEN -> {
				if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
					error(player, "No projector at " + anchor.toShortString());
					return;
				}
				BlockPos screenPos = projector.screenPos();
				if (screenPos == null || !level.isLoaded(screenPos)
					|| !level.getBlockState(screenPos).is(Cinema.SCREEN_BLOCK)) {
					error(player, "Projector has no linked screen");
					return;
				}
				ScreenBlockEntity.applyToConnected(level, screenPos, payload.name());
			}
		}
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
