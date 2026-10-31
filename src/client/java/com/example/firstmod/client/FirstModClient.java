package com.example.firstmod.client;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.ProjectorBlock;
import com.example.firstmod.block.ScreenBlock;
import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.client.audio.InGameAudioSpike;
import com.example.firstmod.client.command.CinemaHelpCommand;
import com.example.firstmod.client.command.ClientProjectorCommand;
import com.example.firstmod.client.media.CinemaPlayer;
import com.example.firstmod.client.media.CinemaPrewarm;
import com.example.firstmod.client.render.CinemaVisualSpike;
import com.example.firstmod.client.render.ProjectorOptics;
import com.example.firstmod.client.render.ProjectorShadow;
import com.example.firstmod.client.render.ScreenBlockEntityRenderer;
import com.example.firstmod.client.render.ScreenGeometry;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FirstModClient implements ClientModInitializer {
	private static int autoSpikeTicks;
	private static int autoVisualTicks;
	private static int autoVideoTicks;
	private static int spikeFocusTicks;
	private static boolean spikeWindowFocused;
	private static boolean spikeEnvironmentPrepared;
	private static Vec3 fixedPlayerPos;
	private static float fixedPlayerYaw;
	private static float fixedPlayerPitch;
	private static final Set<BlockPos> TEST_SCENE_BLOCKS = new HashSet<>();

	@Override
	public void onInitializeClient() {
		// Client-only setup goes here (rendering, keybinds, screens).
		CinemaPrewarm.start();
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
			dispatcher.register(ClientCommandManager.literal("cinema_test_scene")
				.executes(context -> {
					placeTestBlocks(context.getSource().getClient());
					context.getSource().sendFeedback(Component.literal(
						"cinema test scene placed in front of the current view"));
					return 1;
				})
				.then(ClientCommandManager.literal("clear")
					.executes(context -> clearTestScene(context.getSource())))
				.then(ClientCommandManager.literal("remove")
					.executes(context -> clearTestScene(context.getSource())))));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_visual_spike")
				.executes(context -> {
					CinemaVisualSpike.toggle(context.getSource().getClient());
					context.getSource().sendFeedback(Component.literal(
						CinemaVisualSpike.isEnabled()
							? "cinema visual spike: on (legacy world-space quad, independent of screens/projectors)"
							: "cinema visual spike: off"));
					return 1;
				})
				.then(ClientCommandManager.literal("on").executes(context -> {
					if (!CinemaVisualSpike.isEnabled()) {
						CinemaVisualSpike.toggle(context.getSource().getClient());
					}
					context.getSource().sendFeedback(Component.literal(
						"cinema visual spike: on (legacy world-space quad, independent of screens/projectors)"));
					return 1;
				}))
				.then(ClientCommandManager.literal("off").executes(context -> {
					CinemaVisualSpike.disable();
					context.getSource().sendFeedback(Component.literal("cinema visual spike: off"));
					return 1;
				}))
				.then(ClientCommandManager.literal("screenshot").executes(context -> {
					CinemaVisualSpike.screenshot(context.getSource().getClient());
					context.getSource().sendFeedback(Component.literal("cinema visual spike: screenshot requested"));
					return 1;
				}))));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_play")
				.then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
					.executes(context -> {
						String raw = StringArgumentType.getString(context, "path");
						try {
							CinemaPlayer player = CinemaPlayer.play(
								context.getSource().getClient(), Path.of(raw));
							CinemaVisualSpike.enableVideo(
								context.getSource().getClient(), CinemaPlayer.TEXTURE_ID);
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
				CinemaVisualSpike.disable();
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
							CinemaVisualSpike.enableVideo(
								context.getSource().getClient(), CinemaPlayer.TEXTURE_ID);
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
			ProjectorShadow.clearCache();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CinemaPlayer.stopActive(client);
			ProjectorOptics.clearCache();
			ProjectorShadow.clearCache();
			spikeEnvironmentPrepared = false;
			fixedPlayerPos = null;
			TEST_SCENE_BLOCKS.clear();
		});

		BlockEntityRendererRegistry.register(FirstMod.SCREEN_BLOCK_ENTITY, ScreenBlockEntityRenderer::new);
		WorldRenderEvents.AFTER_ENTITIES.register(context -> CinemaPlayer.presentActiveFrame());
		WorldRenderEvents.AFTER_ENTITIES.register(CinemaVisualSpike::render);

		if (System.getProperty("firstmod.spike.audio") != null
			|| System.getProperty("firstmod.spike.visual") != null
			|| System.getProperty("firstmod.spike.video") != null) {
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

		// Used by the P0.5 integration test:
		//   JAVA_TOOL_OPTIONS=-Dfirstmod.spike.audio=independent
		// The probe runs after the world has been loaded for a few seconds.
		if (System.getProperty("firstmod.spike.audio") != null
			|| System.getProperty("firstmod.spike.visual") != null
			|| System.getProperty("firstmod.spike.video") != null) {
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

		if (System.getProperty("firstmod.spike.visual") != null
			&& System.getProperty("firstmod.spike.video") == null) {
			boolean autoExit = System.getProperty("firstmod.spike.exit") != null && !isManualSpikeTest();
			boolean manual = isManualSpikeTest();
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.level == null || client.player == null) {
					return;
				}
				autoVisualTicks++;
				if (autoVisualTicks == 40) {
					FirstMod.LOGGER.info("cinema visual spike: auto-enabling");
					CinemaVisualSpike.enable(client);
				}
				if (autoVisualTicks == 100 && !manual) {
					FirstMod.LOGGER.info("cinema visual spike: auto-screenshot");
					CinemaVisualSpike.screenshot(client);
				}
				if (autoExit && autoVisualTicks == 140) {
					FirstMod.LOGGER.info("cinema visual spike: stopping the client");
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
				if (autoVideoTicks == 30 && System.getProperty("firstmod.spike.blocks") != null) {
					placeTestBlocks(client);
				}
				if (autoVideoTicks == 40) {
					try {
						FirstMod.LOGGER.info("cinema video spike: playing {}", videoPath);
						CinemaPlayer.play(client, Path.of(videoPath));
						if (System.getProperty("firstmod.spike.blocks") == null) {
							CinemaVisualSpike.enableVideo(client, CinemaPlayer.TEXTURE_ID);
						}
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
							CinemaVisualSpike.enableVideo(client, CinemaPlayer.TEXTURE_ID);
						} catch (Throwable t) {
							FirstMod.LOGGER.error("cinema video seek failed", t);
						}
					}
				}
				if (autoVideoTicks == 80 && manual) {
					client.player.displayClientMessage(Component.literal(
						"Cinema manual test: move/look freely. " +
							"Tune optics with /cinema_projector_client info|throw|focus|keystone|brightness|power. " +
							"Rebuild the scene with /cinema_test_scene."), false);
				}
				if (autoVideoTicks == 180 && !manual) {
					FirstMod.LOGGER.info("cinema video spike: auto-screenshot");
					CinemaVisualSpike.screenshot(client);
				}
				if (autoExit && autoVideoTicks == 240) {
					FirstMod.LOGGER.info("cinema video spike: stopping the client");
					CinemaPlayer.stopActive(client);
					CinemaVisualSpike.disable();
					client.stop();
				}
			});
		}
	}

	private static boolean isManualSpikeTest() {
		return System.getProperty("firstmod.spike.manual") != null;
	}

	private static void prepareSpikeEnvironment(net.minecraft.client.Minecraft client, IntegratedServer server) {
		BlockPos spawn = client.level.getSharedSpawnPos();
		if (!client.level.isLoaded(spawn)) {
			return;
		}
		fixedPlayerPos = new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
		fixedPlayerYaw = 0.0f; // south
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

	private static void freezeSpikePlayer(net.minecraft.client.Minecraft client) {
		if (fixedPlayerPos == null || client.player == null) {
			return;
		}
		client.player.setPos(fixedPlayerPos.x, fixedPlayerPos.y, fixedPlayerPos.z);
		client.player.setYRot(fixedPlayerYaw);
		client.player.setXRot(fixedPlayerPitch);
		client.player.setYHeadRot(fixedPlayerYaw);
		client.player.setDeltaMovement(Vec3.ZERO);
	}

	private static void placeTestBlocks(net.minecraft.client.Minecraft client) {
		if (client.level == null || client.player == null) {
			return;
		}
		// The block scene and the legacy world-space probe are mutually
		// confusing; a block test always turns the free-floating quad off.
		CinemaVisualSpike.disable();
		Direction forward = client.player.getDirection();
		Direction screenFacing = forward.getOpposite();
		BlockPos center = client.player.blockPosition().relative(forward, 4).above();
		BlockPos firstScreen = null;
		for (int horizontal = -2; horizontal <= 1; horizontal++) {
			for (int vertical = 0; vertical < 3; vertical++) {
				BlockPos pos = switch (screenFacing) {
					case NORTH, SOUTH -> center.offset(horizontal, vertical, 0);
					case WEST, EAST -> center.offset(0, vertical, horizontal);
					default -> center;
				};
				client.level.setBlockAndUpdate(pos, FirstMod.SCREEN_BLOCK.defaultBlockState()
					.setValue(ScreenBlock.FACING, screenFacing));
				TEST_SCENE_BLOCKS.add(pos.immutable());
				if (firstScreen == null) {
					firstScreen = pos;
				}
			}
		}
		BlockPos projectorPos = client.player.blockPosition().relative(forward, -3).above();
		client.level.setBlockAndUpdate(projectorPos, FirstMod.PROJECTOR_BLOCK.defaultBlockState()
			.setValue(ProjectorBlock.FACING, forward));
		TEST_SCENE_BLOCKS.add(projectorPos.immutable());
		FirstMod.LOGGER.info("cinema block spike: 4x3 screen={} projector={} facing={}",
			center, projectorPos, forward);

		if (firstScreen == null || !(client.level.getBlockEntity(projectorPos) instanceof ProjectorBlockEntity projector)) {
			FirstMod.LOGGER.warn("cinema block spike: projector block entity was not created");
			return;
		}

		ScreenGeometry geometry = ScreenGeometry.find(client.level, firstScreen,
			client.level.getBlockState(firstScreen));
		Vec3 lens = projector.lensPosition();
		double distance = lens.distanceTo(geometry.center());
		double throwRatio = readDoubleProperty("firstmod.spike.projector.throw",
			distance / (geometry.width() * 0.98));
		projector.setThrowRatio(throwRatio);
		double focus = readDoubleProperty("firstmod.spike.projector.focus", -1.0);
		projector.setFocusDistance(focus);
		projector.setKeystone(
			readDoubleProperty("firstmod.spike.projector.keystone.vertical", 0.0),
			readDoubleProperty("firstmod.spike.projector.keystone.horizontal", 0.0));
		if (System.getProperty("firstmod.spike.projector.off") != null) {
			projector.setPowered(false);
		}
		FirstMod.LOGGER.info("cinema projector spike: lens={} screenCenter={} distance={} throw={} focus={}",
			lens, geometry.center(), String.format("%.2f", distance),
			String.format("%.2f", throwRatio), String.format("%.2f", focus));

		clearTestCorridor(client, lens, geometry);
		boolean shadow = System.getProperty("firstmod.spike.projector.shadow") != null;
		BlockPos obstacle = shadowObstaclePos(lens, geometry);
		for (int dy = 0; dy < 2; dy++) {
			BlockPos pos = obstacle.above(dy);
			client.level.setBlockAndUpdate(pos,
				shadow ? Blocks.GLASS.defaultBlockState() : Blocks.AIR.defaultBlockState());
			if (shadow) {
				TEST_SCENE_BLOCKS.add(pos.immutable());
			}
		}
		if (shadow) {
			FirstMod.LOGGER.info("cinema projector spike: shadow obstacle at {}", obstacle);
		}
	}

	private static void clearTestCorridor(net.minecraft.client.Minecraft client, Vec3 lens,
										  ScreenGeometry geometry) {
		if (client.level == null || client.player == null) {
			return;
		}
		Vec3 midpoint = lens.add(geometry.center().subtract(lens).scale(0.5));
		BlockPos mid = BlockPos.containing(midpoint);
		int minY = client.player.blockPosition().getY();
		for (int x = mid.getX() - 7; x <= mid.getX() + 7; x++) {
			for (int z = mid.getZ() - 7; z <= mid.getZ() + 7; z++) {
				for (int y = minY; y <= minY + 4; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					var state = client.level.getBlockState(pos);
					if (state.is(FirstMod.SCREEN_BLOCK) || state.is(FirstMod.PROJECTOR_BLOCK)) {
						continue;
					}
					if (!state.isAir()) {
						client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
					}
				}
			}
		}
	}

	private static BlockPos shadowObstaclePos(Vec3 lens, ScreenGeometry geometry) {
		Vec3 midpoint = lens.add(geometry.center().subtract(lens).scale(0.9));
		int offsetX = (int) Math.round(geometry.right().x * 2.0);
		int offsetZ = (int) Math.round(geometry.right().z * 2.0);
		return BlockPos.containing(midpoint).offset(offsetX, 0, offsetZ);
	}

	private static int clearTestScene(FabricClientCommandSource source) {
		Minecraft client = source.getClient();
		if (client.level == null) {
			source.sendError(Component.literal("cinema test scene: no client level"));
			return 0;
		}
		int removed = 0;
		for (BlockPos pos : TEST_SCENE_BLOCKS) {
			var state = client.level.getBlockState(pos);
			if (state.is(FirstMod.SCREEN_BLOCK)
				|| state.is(FirstMod.PROJECTOR_BLOCK)
				|| state.is(Blocks.GLASS)
				|| state.is(Blocks.BARRIER)) {
				client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				removed++;
			}
		}
		TEST_SCENE_BLOCKS.clear();

		if (removed == 0 && client.player != null) {
			BlockPos center = client.player.blockPosition();
			for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-24, -8, -24), center.offset(24, 8, 24))) {
				var state = client.level.getBlockState(pos);
				if (state.is(FirstMod.SCREEN_BLOCK) || state.is(FirstMod.PROJECTOR_BLOCK)) {
					client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
					removed++;
				}
			}
		}

		removed += clearNearbyBarriers(client);

		ProjectorOptics.clearCache();
		ProjectorShadow.clearCache();
		if (removed == 0) {
			source.sendFeedback(Component.literal("cinema test scene: nothing to remove"));
			return 0;
		}
		source.sendFeedback(Component.literal("cinema test scene cleared (" + removed + " blocks)"));
		return 1;
	}

	private static int clearNearbyBarriers(Minecraft client) {
		if (client.player == null || client.level == null) {
			return 0;
		}
		ChunkPos center = new ChunkPos(client.player.blockPosition());
		List<BlockPos> barriers = new ArrayList<>();
		for (int chunkX = center.x - 4; chunkX <= center.x + 4; chunkX++) {
			for (int chunkZ = center.z - 4; chunkZ <= center.z + 4; chunkZ++) {
				var chunk = client.level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				chunk.findBlocks(state -> state.is(Blocks.BARRIER),
					(pos, state) -> barriers.add(pos.immutable()));
			}
		}
		int removed = 0;
		for (BlockPos pos : barriers) {
			if (client.level.getBlockState(pos).is(Blocks.BARRIER)) {
				client.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
				removed++;
			}
		}
		return removed;
	}

	private static double readDoubleProperty(String key, double fallback) {
		String value = System.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException e) {
			FirstMod.LOGGER.warn("cinema projector spike: bad {} value '{}'", key, value);
			return fallback;
		}
	}
}
