package com.example.firstmod.network;

import com.example.firstmod.block.entity.ProjectorBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates the projector GUI snapshot and applies it to the block entity. */
public final class ProjectorSettingsHandler {
	private static final double MAX_CONTROL_DISTANCE = 16.0;

	private ProjectorSettingsHandler() {
	}

	public static void handle(ProjectorSettingsPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		BlockPos pos = payload.projectorPos();
		if (!level.isLoaded(pos)
			|| player.blockPosition().distSqr(pos) > MAX_CONTROL_DISTANCE * MAX_CONTROL_DISTANCE) {
			error(player, "Projector is out of range");
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
			error(player, "No projector at " + pos.toShortString());
			return;
		}
		if (!valid(payload)) {
			error(player, "Invalid projector settings");
			return;
		}
		projector.applySettings(payload.throwRatio(), payload.focusDistance(),
			payload.keystoneVerticalDegrees(), payload.keystoneHorizontalDegrees(),
			payload.brightness(), payload.ambientWash(), payload.powered(),
			payload.simFocus(), payload.simKeystone(),
			payload.simWash(), payload.simWarmup());
	}

	private static boolean valid(ProjectorSettingsPayload payload) {
		return (payload.throwRatio() == -1.0
				|| (finite(payload.throwRatio()) && inRange(payload.throwRatio(),
					ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO)))
			&& (payload.focusDistance() == -1.0
				|| (finite(payload.focusDistance()) && inRange(payload.focusDistance(),
					ProjectorBlockEntity.MIN_FOCUS_DISTANCE, ProjectorBlockEntity.MAX_FOCUS_DISTANCE)))
			&& finite(payload.keystoneVerticalDegrees()) && inRange(payload.keystoneVerticalDegrees(),
				ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES)
			&& finite(payload.keystoneHorizontalDegrees()) && inRange(payload.keystoneHorizontalDegrees(),
				ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES)
			&& finite(payload.brightness()) && inRange(payload.brightness(),
				ProjectorBlockEntity.MIN_BRIGHTNESS, ProjectorBlockEntity.MAX_BRIGHTNESS)
			&& finite(payload.ambientWash()) && inRange(payload.ambientWash(),
				ProjectorBlockEntity.MIN_AMBIENT_WASH, ProjectorBlockEntity.MAX_AMBIENT_WASH);
	}

	private static boolean finite(double value) {
		return Double.isFinite(value);
	}

	private static boolean inRange(double value, double min, double max) {
		return value >= min && value <= max;
	}

	private static void error(ServerPlayer player, String message) {
		player.displayClientMessage(Component.literal(message), false);
	}
}
