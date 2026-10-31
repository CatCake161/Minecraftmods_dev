package com.example.firstmod.client.command;

import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.client.media.CinemaPlayer;
import com.example.firstmod.client.render.ProjectorOptics;
import com.example.firstmod.client.render.ProjectorShadow;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side projector tuning for manual in-game tests.
 *
 * <p>The normal {@code /cinema_projector} command edits the server-side block
 * entity. The automatic test scene, however, is placed directly in the client
 * level, so this companion command edits the nearest client-side projector and
 * invalidates the pairing/shadow caches immediately.
 */
public final class ClientProjectorCommand {
	private ClientProjectorCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_projector_client")
			.then(ClientCommandManager.literal("info")
				.executes(context -> {
					ProjectorBlockEntity projector = nearest(context.getSource());
					if (projector == null) {
						return noProjector(context.getSource());
					}
					ProjectorOptics optics = ProjectorOptics.resolve(context.getSource().getClient().level,
						projector, videoAspect());
					String paired = optics == null ? "none" : optics.screen().master().toShortString();
					context.getSource().sendFeedback(Component.literal(String.format(
						"client projector %s: throw=%.2f focus=%s keystone=%.1f/%.1f brightness=%.2f power=%s screen=%s",
						projector.getBlockPos(),
						projector.throwRatio(),
						projector.focusDistance() < 0.0
							? "auto"
							: String.format("%.2f m", projector.focusDistance()),
						projector.keystoneVerticalDegrees(),
						projector.keystoneHorizontalDegrees(),
						projector.brightness(),
						projector.isPowered() ? "on" : "off",
						paired)));
					return 1;
				}))
			.then(ClientCommandManager.literal("throw")
				.then(ClientCommandManager.literal("auto")
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						if (projector == null) {
							return noProjector(context.getSource());
						}
						ProjectorOptics optics = ProjectorOptics.resolve(context.getSource().getClient().level,
							projector, videoAspect());
						if (optics == null) {
							context.getSource().sendError(Component.literal(
								"client projector has no paired screen"));
							return 0;
						}
						double ratio = optics.lens().distanceTo(optics.screen().center())
							/ (optics.screen().width() * 0.98);
						projector.setThrowRatio(ratio);
						clearCaches();
						context.getSource().sendFeedback(Component.literal(
							String.format("client projector throw ratio = %.2f", projector.throwRatio())));
						return 1;
					}))
				.then(ClientCommandManager.argument("ratio", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO))
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						if (projector == null) {
							return noProjector(context.getSource());
						}
						projector.setThrowRatio(DoubleArgumentType.getDouble(context, "ratio"));
						clearCaches();
						context.getSource().sendFeedback(Component.literal(
							String.format("client projector throw ratio = %.2f", projector.throwRatio())));
						return 1;
					})))
			.then(ClientCommandManager.literal("focus")
				.then(ClientCommandManager.literal("auto")
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						if (projector == null) {
							return noProjector(context.getSource());
						}
						projector.setFocusDistance(-1.0);
						clearCaches();
						context.getSource().sendFeedback(Component.literal(
							"client projector focus = auto (paired screen)"));
						return 1;
					}))
				.then(ClientCommandManager.argument("meters", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_FOCUS_DISTANCE, ProjectorBlockEntity.MAX_FOCUS_DISTANCE))
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						if (projector == null) {
							return noProjector(context.getSource());
						}
						projector.setFocusDistance(DoubleArgumentType.getDouble(context, "meters"));
						clearCaches();
						context.getSource().sendFeedback(Component.literal(
							String.format("client projector focus = %.2f m", projector.focusDistance())));
						return 1;
					})))
			.then(ClientCommandManager.literal("keystone")
				.then(ClientCommandManager.argument("verticalDegrees", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES))
					.then(ClientCommandManager.argument("horizontalDegrees", DoubleArgumentType.doubleArg(
							ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES))
						.executes(context -> {
							ProjectorBlockEntity projector = nearest(context.getSource());
							if (projector == null) {
								return noProjector(context.getSource());
							}
							double vertical = DoubleArgumentType.getDouble(context, "verticalDegrees");
							double horizontal = DoubleArgumentType.getDouble(context, "horizontalDegrees");
							projector.setKeystone(vertical, horizontal);
							clearCaches();
							context.getSource().sendFeedback(Component.literal(String.format(
								"client projector keystone = %.1f° / %.1f°", vertical, horizontal)));
							return 1;
						}))))
			.then(ClientCommandManager.literal("brightness")
				.then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_BRIGHTNESS, ProjectorBlockEntity.MAX_BRIGHTNESS))
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						if (projector == null) {
							return noProjector(context.getSource());
						}
						projector.setBrightness(DoubleArgumentType.getDouble(context, "value"));
						clearCaches();
						context.getSource().sendFeedback(Component.literal(
							String.format("client projector brightness = %.2f", projector.brightness())));
						return 1;
					})))
			.then(ClientCommandManager.literal("power")
				.executes(context -> {
					ProjectorBlockEntity projector = nearest(context.getSource());
					if (projector == null) {
						return noProjector(context.getSource());
					}
					projector.setPowered(!projector.isPowered());
					clearCaches();
					context.getSource().sendFeedback(Component.literal(
						"client projector power = " + (projector.isPowered() ? "on" : "off")));
					return 1;
				})));
	}

	private static ProjectorBlockEntity nearest(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.player == null || client.level == null) {
			return null;
		}
		Vec3 origin = client.player.position();
		ChunkPos center = new ChunkPos(client.player.blockPosition());
		ProjectorBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int chunkX = center.x - 4; chunkX <= center.x + 4; chunkX++) {
			for (int chunkZ = center.z - 4; chunkZ <= center.z + 4; chunkZ++) {
				LevelChunk chunk = client.level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
						continue;
					}
					double distance = Vec3.atCenterOf(projector.getBlockPos()).distanceToSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = projector;
					}
				}
			}
		}
		return best;
	}

	private static int noProjector(FabricClientCommandSource source) {
		source.sendError(Component.literal("No cinema projector within 64 blocks"));
		return 0;
	}

	private static float videoAspect() {
		CinemaPlayer player = CinemaPlayer.active();
		return player == null ? 16.0f / 9.0f : player.aspect();
	}

	private static void clearCaches() {
		ProjectorOptics.clearCache();
		ProjectorShadow.clearCache();
	}
}
