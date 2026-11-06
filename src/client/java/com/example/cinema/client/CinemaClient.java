package com.example.cinema.client;

import com.example.cinema.Cinema;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.client.audio.EfxReverbSpike;
import com.example.cinema.client.audio.InGameAudioSpike;
import com.example.cinema.client.audio.speakerdata.SpeakerDataAssignments;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.command.ClientHelpCommand;
import com.example.cinema.client.command.ClientReceiverCommand;
import com.example.cinema.client.command.ClientProjectorCommand;
import com.example.cinema.client.command.ClientSpeakerDataCommand;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.gui.AvReceiverConfigScreen;
import com.example.cinema.client.gui.ProjectorConfigScreen;
import com.example.cinema.client.gui.RemoteDeviceScreen;
import com.example.cinema.client.gui.RemotePairingScreen;
import com.example.cinema.client.gui.SpeakerDataConfigScreen;
import com.example.cinema.client.item.RemoteControllerClient;
import com.example.cinema.client.media.AvReceiverPlaybackManager;
import com.example.cinema.client.media.CinemaPlayer;
import com.example.cinema.client.media.CinemaPrewarm;
import com.example.cinema.client.render.ProjectorOptics;
import com.example.cinema.client.render.ScreenBlockEntityRenderer;
import com.example.cinema.client.render.ScreenGeometry;
import com.example.cinema.client.render.ScreenLimits;
import com.example.cinema.client.render.SeatEntityRenderer;
import com.example.cinema.client.render.SpeakerLocator;
import com.example.cinema.network.RemotePairingListPayload;
import com.example.cinema.network.RemoteScanListPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

public class CinemaClient implements ClientModInitializer {
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
		SpeakerDataRegistry.load();
		SpeakerDataAssignments.load();

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			InteractionResult remoteResult =
				RemoteControllerClient.useBlock(player, world, hand, hitResult);
			if (remoteResult != InteractionResult.PASS) {
				return remoteResult;
			}
			if (!CinemaClientConfig.get().emptyHandOpensDeviceGuis()) {
				return InteractionResult.PASS;
			}
			// Held items keep their normal block interaction; only a bare hand opens a GUI.
			if (!player.getItemInHand(hand).isEmpty()) {
				return InteractionResult.PASS;
			}
			BlockEntity blockEntity = world.getBlockEntity(hitResult.getBlockPos());
			if (world.getBlockState(hitResult.getBlockPos()).is(Cinema.PROJECTOR_BLOCK)
				&& blockEntity instanceof ProjectorBlockEntity projector) {
				if (world.isClientSide) {
					Minecraft.getInstance().setScreen(new ProjectorConfigScreen(projector));
				}
				return InteractionResult.SUCCESS;
			}
			if (world.getBlockState(hitResult.getBlockPos()).is(Cinema.AV_RECEIVER_BLOCK)
				&& blockEntity instanceof AvReceiverBlockEntity receiver) {
				if (world.isClientSide) {
					Minecraft.getInstance().setScreen(new AvReceiverConfigScreen(receiver));
				}
				return InteractionResult.SUCCESS;
			}
			if (world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof SpeakerBlock) {
				if (world.isClientSide) {
					SpeakerBlockEntity speaker = findSpeaker(world, hitResult.getBlockPos());
					if (speaker != null) {
						Minecraft.getInstance().setScreen(new SpeakerDataConfigScreen(null, speaker));
						return InteractionResult.SUCCESS;
					}
				}
				return InteractionResult.PASS;
			}
			return InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register(RemoteControllerClient::useItem);
		ClientPlayNetworking.registerGlobalReceiver(RemotePairingListPayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				if (context.client().screen instanceof RemotePairingScreen screen) {
					screen.applyList(payload);
				}
			}));
		ClientPlayNetworking.registerGlobalReceiver(RemoteScanListPayload.TYPE,
			(payload, context) -> context.client().execute(() -> {
				if (context.client().screen instanceof RemoteDeviceScreen screen) {
					screen.applyList(payload);
				}
			}));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_audio_spike")
				.executes(context -> {
					InGameAudioSpike.runAsync();
					context.getSource().sendFeedback(Component.literal(
						"cinema audio spike started; see the game log for the OpenAL result"));
					return 1;
				})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_efx_spike")
				.executes(context -> {
					EfxReverbSpike.runAsync();
					context.getSource().sendFeedback(Component.literal(
						"cinema EFX spike started; results go to chat and the game log"));
					return 1;
				})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ClientProjectorCommand.register(dispatcher));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ClientHelpCommand.register(dispatcher));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ClientSpeakerDataCommand.register(dispatcher));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			ClientReceiverCommand.register(dispatcher));

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			CinemaPlayer.stopActive(client);
			AvReceiverPlaybackManager.shutdown();
			ProjectorOptics.clearCache();
			SpeakerLocator.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CinemaPlayer.stopActive(client);
			AvReceiverPlaybackManager.shutdown();
			ProjectorOptics.clearCache();
			SpeakerLocator.clear();
			spikeEnvironmentPrepared = false;
			fixedPlayerPos = null;
		});

		BlockEntityRendererRegistry.register(Cinema.screenBlockEntity, ScreenBlockEntityRenderer::new);
		EntityRendererRegistry.register(Cinema.seatEntityType, SeatEntityRenderer::new);
		WorldRenderEvents.START.register(context -> ScreenGeometry.beginFrame());
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			AvReceiverPlaybackManager.presentFrames();
			CinemaPlayer.presentActiveFrame();
		});
		ClientTickEvents.END_CLIENT_TICK.register(AvReceiverPlaybackManager::tick);
		ClientTickEvents.END_CLIENT_TICK.register(SpeakerLocator::tick);

		Cinema.LOGGER.info(
			"cinema screen: max rendered size {}x{} ({})",
			ScreenLimits.maxSize(), ScreenLimits.maxSize(), ScreenLimits.source());

		if (System.getProperty("cinema.spike.audio") != null
			|| System.getProperty("cinema.spike.video") != null
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
						Cinema.LOGGER.info("cinema spike: game window is focused");
					}
					return;
				}
				GLFW.glfwShowWindow(handle);
				GLFW.glfwFocusWindow(handle);
				GLFW.glfwRequestWindowAttention(handle);
			});
		}

		if (System.getProperty("cinema.spike.audio") != null
			|| System.getProperty("cinema.spike.video") != null
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

		if (System.getProperty("cinema.spike.audio") != null) {
			boolean autoExit = System.getProperty("cinema.spike.exit") != null && !isManualSpikeTest();
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (++autoSpikeTicks == 100) {
					Cinema.LOGGER.info("cinema audio spike: auto-running after client startup");
					InGameAudioSpike.runAsync();
				}
				if (autoExit && autoSpikeTicks == 220) {
					Cinema.LOGGER.info("cinema audio spike: stopping the client");
					client.stop();
				}
			});
		}

		if (System.getProperty("cinema.spike.video") != null) {
			boolean manual = isManualSpikeTest();
			boolean autoExit = System.getProperty("cinema.spike.exit") != null && !manual;
			String videoPath = System.getProperty("cinema.spike.video");
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.level == null || client.player == null) {
					return;
				}
				autoVideoTicks++;
				if (autoVideoTicks == 40) {
					try {
						Cinema.LOGGER.info("cinema video spike: playing {}", videoPath);
						CinemaPlayer.play(client, Path.of(videoPath));
					} catch (Throwable t) {
						Cinema.LOGGER.error("cinema video spike failed", t);
					}
				}
				if (autoVideoTicks == 100) {
					String seek = System.getProperty("cinema.spike.video.seek");
					if (seek != null) {
						try {
							double seconds = Double.parseDouble(seek);
							Cinema.LOGGER.info("cinema video spike: seeking to {} s", seconds);
							CinemaPlayer.seekActive(client, seconds);
						} catch (Throwable t) {
							Cinema.LOGGER.error("cinema video seek failed", t);
						}
					}
				}
				if (autoVideoTicks == 80 && manual) {
					client.player.displayClientMessage(Component.literal(
						"Cinema manual test: move/look freely. " +
							"Use /cinema_play, /cinema_projector_client and /cinema_help."), false);
				}
				if (autoExit && autoVideoTicks == 240) {
					Cinema.LOGGER.info("cinema video spike: stopping the client");
					CinemaPlayer.stopActive(client);
					client.stop();
				}
			});
		}
	}

	private static SpeakerBlockEntity findSpeaker(Level level, BlockPos pos) {
		if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
			return speaker;
		}
		// Floorstanding speakers are two blocks tall and only the lower half
		// owns the block entity.
		if (level.getBlockEntity(pos.below()) instanceof SpeakerBlockEntity speaker) {
			return speaker;
		}
		return null;
	}

	private static boolean isManualSpikeTest() {
		return System.getProperty("cinema.spike.manual") != null;
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
		boolean modifyWorld = CinemaClientConfig.get().spikeModifiesWorld();
		server.execute(() -> {
			ServerLevel level = server.overworld();
			int removed = 0;
			if (modifyWorld) {
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
				removed = mobs.size();
			}

			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				player.teleportTo(level, pose.x, pose.y, pose.z, yaw, pitch);
				player.setYHeadRot(yaw);
				player.setDeltaMovement(Vec3.ZERO);
				player.setNoGravity(false);
			}
			Cinema.LOGGER.info(
				"cinema spike: fixed player at {} yaw={} worldModified={} removedMobs={}",
				pose, yaw, modifyWorld, removed);
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
