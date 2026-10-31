package com.example.firstmod.client;

import com.example.firstmod.FirstMod;
import com.example.firstmod.client.audio.InGameAudioSpike;
import com.example.firstmod.client.render.CinemaVisualSpike;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class FirstModClient implements ClientModInitializer {
	private static int autoSpikeTicks;
	private static int autoVisualTicks;
	private static boolean spikeWindowFocused;

	@Override
	public void onInitializeClient() {
		// Client-only setup goes here (rendering, keybinds, screens).
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_audio_spike")
				.executes(context -> {
					InGameAudioSpike.runAsync();
					context.getSource().sendFeedback(Component.literal(
						"cinema audio spike started; see the game log for the OpenAL result"));
					return 1;
				})));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
			dispatcher.register(ClientCommandManager.literal("cinema_visual_spike")
				.executes(context -> {
					CinemaVisualSpike.toggle(context.getSource().getClient());
					context.getSource().sendFeedback(Component.literal(
						"cinema visual spike: " + (CinemaVisualSpike.isEnabled() ? "on" : "off")));
					return 1;
				})
				.then(ClientCommandManager.literal("screenshot").executes(context -> {
					CinemaVisualSpike.screenshot(context.getSource().getClient());
					context.getSource().sendFeedback(Component.literal("cinema visual spike: screenshot requested"));
					return 1;
				}))));

		WorldRenderEvents.AFTER_ENTITIES.register(CinemaVisualSpike::render);

		if (System.getProperty("firstmod.spike.audio") != null
			|| System.getProperty("firstmod.spike.visual") != null) {
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (spikeWindowFocused) {
					return;
				}
				spikeWindowFocused = true;
				long handle = client.getWindow().getWindow();
				GLFW.glfwShowWindow(handle);
				GLFW.glfwFocusWindow(handle);
				GLFW.glfwRequestWindowAttention(handle);
			});
		}

		// Used by the P0.5 integration test:
		//   JAVA_TOOL_OPTIONS=-Dfirstmod.spike.audio=independent
		// The probe runs after the world has been loaded for a few seconds.
		if (System.getProperty("firstmod.spike.audio") != null) {
			boolean autoExit = System.getProperty("firstmod.spike.exit") != null;
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

		if (System.getProperty("firstmod.spike.visual") != null) {
			boolean autoExit = System.getProperty("firstmod.spike.exit") != null;
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.level == null || client.player == null) {
					return;
				}
				autoVisualTicks++;
				if (autoVisualTicks == 40) {
					FirstMod.LOGGER.info("cinema visual spike: auto-enabling");
					CinemaVisualSpike.enable(client);
				}
				if (autoVisualTicks == 100) {
					FirstMod.LOGGER.info("cinema visual spike: auto-screenshot");
					CinemaVisualSpike.screenshot(client);
				}
				if (autoExit && autoVisualTicks == 140) {
					FirstMod.LOGGER.info("cinema visual spike: stopping the client");
					client.stop();
				}
			});
		}
	}
}
