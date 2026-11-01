package com.example.firstmod.client;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.client.audio.InGameAudioSpike;
import com.example.firstmod.client.command.CinemaHelpCommand;
import com.example.firstmod.client.command.ClientProjectorCommand;
import com.example.firstmod.client.gui.ProjectorScreen;
import com.example.firstmod.client.media.CinemaPlayer;
import com.example.firstmod.client.media.CinemaPrewarm;
import com.example.firstmod.client.render.ProjectorOptics;
import com.example.firstmod.client.render.ScreenBlockEntityRenderer;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public class FirstModClient implements ClientModInitializer {
	private static int autoSpikeTicks;
	private static int autoVideoTicks;
	private static int spikeFocusTicks;
	private static boolean spikeWindowFocused;
	private static boolean spikeEnvironmentPrepared;
	private static Vec3 fixedPlayerPos;
	private static float fixedPlayerYaw;
	private static float fixedPlayerPitch;

	@Override
	public void onInitializeClient() {
		CinemaPrewarm.start();

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (!world.getBlockState(hitResult.getBlockPos()).is(FirstMod.PROJECTOR_BLOCK)) {
				return InteractionResult.PASS;
			}
			BlockEntity blockEntity = world.getBlockEntity(hitResult.getBlockPos());
			if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
				return InteractionResult.PASS;
			}
			if (world.isClientSide) {
				Minecraft.getInstance().setScreen(new ProjectorScreen(projector));
			}
			return InteractionResult.SUCCESS;
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_audio_spike")
				.executes(context -> {
					InGameAudioSpike.runAsync();
					context.getSource().sendFeedback(Component.literal(
						"cinema audio spike started; see the game log for the OpenAL result"));
					return 1;
				})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ClientProjectorCommand.register(dispatcher));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			CinemaHelpCommand.register(dispatcher));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_play")
				.then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
					.executes(context -> {
						String raw = StringArgumentType.getString(context, "path");
						try {
							CinemaPlayer player = CinemaPlayer.play(
								context.getSource().getClient(), Path.of(raw));
							context.getSource().sendFeedback(Component.literal(
								"cinema playing: " + raw + " (" + player.width() + "x" + player.height() + ")"));
							return 1;
						} catch (Exception e) {
							context.getSource().sendError(Component.literal(
								"cinema play failed: " + e.getMessage()));
							return 0;
						}
					}))));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_stop").executes(context -> {
				CinemaPlayer.stopActive(context.getSource().getClient());
				context.getSource().sendFeedback(Component.literal("cinema stopped"));
				return 1;
			})));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_pause").executes(context -> {
				CinemaPlayer player = CinemaPlayer.active();
				if (player == null) {
					context.getSource().sendError(Component.literal("cinema is not playing"));
					return 0;
				}
				player.togglePause();
				context.getSource().sendFeedback(Component.literal(
					"cinema " + (player.isPaused() ? "paused" : "resumed")));
				return 1;
			})));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_seek")
				.then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.0))
					.executes(context -> {
						double seconds = DoubleArgumentType.getDouble(context, "seconds");
						try {
							CinemaPlayer.seekActive(context.getSource().getClient(), seconds);
							context.getSource().sendFeedback(Component.literal(
								"cinema seek: " + String.format("%.1f", seconds) + " s"));
							return 1;
						} catch (Exception e) {
							context.getSource().sendError(Component.literal(
								"cinema seek failed: " + e.getMessage()));
							return 0;
						}
					}))));

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			CinemaPlayer.stopActive(client);
			ProjectorOptics.clearCache();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CinemaPlayer.stopActive(client);
			ProjectorOptics.clearCache();
			spikeEnvironmentPrepared = false;
			fixedPlayerPos = null;
		});

		BlockEntityRendererRegistry.register(FirstMod.SCREEN_BLOCK_ENTITY, ScreenBlockEntityRenderer::new);
		WorldRenderEvents.AFTER_ENTITIES.register(context -> CinemaPlayer.presentActiveFrame());

		if (System.getProperty("firstmod.spike.audio") != null
			|| System.getProperty("firstmod.spike.video") != null
			|| isManualSpikeTest()) {
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				spikeFocusTicks++;
				if (isManualSpikeTest() && spikeWindowFocused) {
					return;
				}
				if (spikeFocusTicks > 600) {
					return;
				}
				if (spikeFocusTicks % 10 != 0) {
					return;
				}
				long handle = client.getWindow().getWindow();
				if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
					GLFW.glfwRestoreWindow(handle);
					GLFW.glfwShowWindow(handle);
				}
				if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE) {
					if (!spikeWindowFocused) {
						spikeWindowFocused = true;
						FirstMod.LOGGER.info("cinema spike: game window is focused");
					}
					return;
				}
				GLFW.glfwShowWindow(handle);
				GLFW.glfwFocusWindow(handle);
				GLFW.glfwRequestWindowAttention(handle);
			});
		}

		if (System.getProperty("firstmod.spike.audio") != null
			|| System.getProperty("firstmod.spike.video") != null
			|| isManualSpikeTest()) {
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.level == null || client.player == null) {
					return;
				}
				if (!spikeEnvironmentPrepared) {
					IntegratedServer server = client.getSingleplayerServer();
					if (server == null) {
						return;
					}
					prepareSpikeEnvironment(client, server);
				}
				if (!isManualSpikeTest()) {
					freezeSpikePlayer(client);
				}
			});
		}

		if (System.getProperty("firstmod.spike.audio") != null) {
			boolean autoExit = System.getProperty("firstmod.spike.exit") != null && !isManualSpikeTest();
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (++autoSpikeTicks == 100) {
					FirstMod.LOGGER.info("cinema audio spike: auto-running after client startup");
					InGameAudioSpike.runAsync();
				}
				if (autoExit && autoSpikeTicks == 220) {
					FirstMod.LOGGER.info("cinema audio spike: stopping the client");
					client.stop();
				}
			});
		}

		if (System.getProperty("firstmod.spike.video") != null) {
			boolean manual = isManualSpikeTest();
			boolean autoExit = System.getProperty("firstmod.spike.exit") != null && !manual;
			String videoPath = System.getProperty("firstmod.spike.video");
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.level == null || client.player == null) {
					return;
				}
				autoVideoTicks++;
				if (autoVideoTicks == 40) {
					try {
						FirstMod.LOGGER.info("cinema video spike: playing {}", videoPath);
						CinemaPlayer.play(client, Path.of(videoPath));
					} catch (Throwable t) {
						FirstMod.LOGGER.error("cinema video spike failed", t);
					}
				}
				if (autoVideoTicks == 100) {
					String seek = System.getProperty("firstmod.spike.video.seek");
					if (seek != null) {
						try {
							double seconds = Double.parseDouble(seek);
							FirstMod.LOGGER.info("cinema video spike: seeking to {} s", seconds);
							CinemaPlayer.seekActive(client, seconds);
						} catch (Throwable t) {
							FirstMod.LOGGER.error("cinema video seek failed", t);
						}
					}
				}
				if (autoVideoTicks == 80 && manual) {
					client.player.displayClientMessage(Component.literal(
						"Cinema manual test: move/look freely. " +
							"Use /cinema_play, /cinema_projector_client and /cinema_help."), false);
				}
				if (autoExit && autoVideoTicks == 240) {
					FirstMod.LOGGER.info("cinema video spike: stopping the client");
					CinemaPlayer.stopActive(client);
					client.stop();
				}
			});
		}
	}

	private static boolean isManualSpikeTest() {
		return System.getProperty("firstmod.spike.manual") != null;
	}

	private static void prepareSpikeEnvironment(Minecraft client, IntegratedServer server) {
		BlockPos spawn = client.level.getSharedSpawnPos();
		if (!client.level.isLoaded(spawn)) {
			return;
		}
		fixedPlayerPos = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		fixedPlayerYaw = 0.0f;
		fixedPlayerPitch = 0.0f;
		spikeEnvironmentPrepared = true;
		freezeSpikePlayer(client);
		client.player.setNoGravity(false);

		Vec3 pose = fixedPlayerPos;
		float yaw = fixedPlayerYaw;
		float pitch = fixedPlayerPitch;
		server.execute(() -> {
			ServerLevel level = server.overworld();
			level.setDayTime(6000L);
			GameRules rules = level.getGameRules();
			rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
			rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
			rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
			level.setWeatherParameters(6000, 0, false, false);

			AABB removalArea = new AABB(
				pose.x - 96.0, pose.y - 48.0, pose.z - 96.0,
				pose.x + 96.0, pose.y + 48.0, pose.z + 96.0);
			var mobs = level.getEntitiesOfClass(Mob.class, removalArea);
			mobs.forEach(Entity::discard);
			int removed = mobs.size();

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.teleportTo(level, pose.x, pose.y, pose.z, yaw, pitch);
				player.setYHeadRot(yaw);
				player.setDeltaMovement(Vec3.ZERO);
				player.setNoGravity(false);
			}
			FirstMod.LOGGER.info(
				"cinema spike: fixed environment at {} yaw={} time=6000 clear mobSpawning=false removedMobs={}",
				pose, yaw, removed);
		});
	}

	private static void freezeSpikePlayer(Minecraft client) {
		if (fixedPlayerPos == null || client.player == null) {
			return;
		}
		client.player.setPos(fixedPlayerPos.x, fixedPlayerPos.y, fixedPlayerPos.z);
		client.player.setYRot(fixedPlayerYaw);
		client.player.setXRot(fixedPlayerPitch);
		client.player.setYHeadRot(fixedPlayerYaw);
		client.player.setDeltaMovement(Vec3.ZERO);
	}
}
