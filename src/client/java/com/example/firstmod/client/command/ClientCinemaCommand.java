package com.example.firstmod.client.command;

import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import com.example.firstmod.client.media.MediaLibrary;
import com.example.firstmod.network.ReceiverControlPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;

/**
 * M1 command front-end for the receiver pipeline. Commands resolve the nearest
 * receiver on the client, then send the same C2S payload the M2 GUI will use.
 */
public final class ClientCinemaCommand {
	private static final int SEARCH_RADIUS_CHUNKS = 4;

	private ClientCinemaCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_play")
			.then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
				.executes(context -> play(context.getSource(),
					StringArgumentType.getString(context, "path")))));

		dispatcher.register(ClientCommandManager.literal("cinema_pause")
			.executes(context -> togglePause(context.getSource())));

		dispatcher.register(ClientCommandManager.literal("cinema_stop")
			.executes(context -> sendSimple(context.getSource(), ReceiverControlPayload.Action.STOP,
				"cinema: stop requested")));

		dispatcher.register(ClientCommandManager.literal("cinema_seek")
			.then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.0))
				.executes(context -> sendValue(context.getSource(), ReceiverControlPayload.Action.SEEK,
					DoubleArgumentType.getDouble(context, "seconds"),
					"cinema: seek requested"))));

		dispatcher.register(ClientCommandManager.literal("cinema_volume")
			.then(ClientCommandManager.argument("value", DoubleArgumentType.doubleArg(0.0, 1.0))
				.executes(context -> sendValue(context.getSource(), ReceiverControlPayload.Action.SET_VOLUME,
					DoubleArgumentType.getDouble(context, "value"),
					"cinema: volume requested"))));

		dispatcher.register(ClientCommandManager.literal("cinema_mute")
			.executes(context -> {
				AvReceiverBlockEntity receiver = nearestReceiver(context.getSource().getClient());
				if (receiver == null) {
					return noReceiver(context.getSource());
				}
				double value = receiver.isMuted() ? 0.0 : 1.0;
				send(context.getSource(), receiver,
					new ReceiverControlPayload(receiver.getBlockPos(), ReceiverControlPayload.Action.SET_MUTED,
						"", 0L, "", 0.0, value),
					"cinema: mute toggled");
				return 1;
			}));

		dispatcher.register(ClientCommandManager.literal("cinema_link")
			.executes(context -> sendSimple(context.getSource(),
				ReceiverControlPayload.Action.LINK_PROJECTOR,
				"cinema: projector link requested")));

		dispatcher.register(ClientCommandManager.literal("cinema_receiver")
			.executes(context -> info(context.getSource()))
			.then(ClientCommandManager.literal("info")
				.executes(context -> info(context.getSource()))));
		dispatcher.register(ClientCommandManager.literal("cinema_receiver_info")
			.executes(context -> info(context.getSource())));
	}

	private static int play(FabricClientCommandSource source, String raw) {
		Minecraft client = source.getClient();
		AvReceiverBlockEntity receiver = nearestReceiver(client);
		if (receiver == null) {
			return noReceiver(source);
		}
		Path file;
		String key;
		try {
			file = MediaLibrary.resolve(raw);
			key = MediaLibrary.keyFor(file);
		} catch (IOException e) {
			source.sendError(Component.literal("cinema play failed: " + e.getMessage()));
			return 0;
		}
		BlockPos receiverPos = receiver.getBlockPos();
		source.sendFeedback(Component.literal("cinema: preparing " + key + " ..."));
		Thread probe = new Thread(() -> {
			try {
				MediaLibrary.Metadata metadata = MediaLibrary.describe(file, key);
				client.execute(() -> {
					ClientPlayNetworking.send(new ReceiverControlPayload(receiverPos,
						ReceiverControlPayload.Action.SELECT_MEDIA, metadata.key(), metadata.size(),
						metadata.sha256(), metadata.durationSeconds(), 0.0));
					source.sendFeedback(Component.literal("cinema: play requested: " + metadata.key()));
				});
			} catch (Exception e) {
				client.execute(() -> source.sendError(Component.literal(
					"cinema play failed: " + e.getMessage())));
			}
		}, "cinema-media-probe");
		probe.setDaemon(true);
		probe.start();
		return 1;
	}

	private static int togglePause(FabricClientCommandSource source) {
		AvReceiverBlockEntity receiver = nearestReceiver(source.getClient());
		if (receiver == null) {
			return noReceiver(source);
		}
		boolean playing = receiver.state() == AvReceiverBlockEntity.State.PLAYING;
		ReceiverControlPayload.Action action = playing
			? ReceiverControlPayload.Action.PAUSE
			: ReceiverControlPayload.Action.PLAY;
		return send(source, receiver, new ReceiverControlPayload(receiver.getBlockPos(), action,
			"", 0L, "", 0.0, 0.0), playing
			? "cinema: pause requested"
			: "cinema: play requested");
	}

	private static int sendSimple(FabricClientCommandSource source,
								  ReceiverControlPayload.Action action, String message) {
		AvReceiverBlockEntity receiver = nearestReceiver(source.getClient());
		if (receiver == null) {
			return noReceiver(source);
		}
		return send(source, receiver, new ReceiverControlPayload(receiver.getBlockPos(), action,
			"", 0L, "", 0.0, 0.0), message);
	}

	private static int sendValue(FabricClientCommandSource source, ReceiverControlPayload.Action action,
								 double value, String message) {
		AvReceiverBlockEntity receiver = nearestReceiver(source.getClient());
		if (receiver == null) {
			return noReceiver(source);
		}
		return send(source, receiver, new ReceiverControlPayload(receiver.getBlockPos(), action,
			"", 0L, "", 0.0, value), message);
	}

	private static int send(FabricClientCommandSource source, AvReceiverBlockEntity receiver,
							ReceiverControlPayload payload, String message) {
		ClientPlayNetworking.send(payload);
		source.sendFeedback(Component.literal(message));
		return 1;
	}

	private static int info(FabricClientCommandSource source) {
		AvReceiverBlockEntity receiver = nearestReceiver(source.getClient());
		if (receiver == null) {
			return noReceiver(source);
		}
		source.sendFeedback(receiver.status());
		return 1;
	}

	private static AvReceiverBlockEntity nearestReceiver(Minecraft client) {
		if (client.player == null || client.level == null) {
			return null;
		}
		Vec3 origin = client.player.position();
		ChunkPos center = new ChunkPos(client.player.blockPosition());
		AvReceiverBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int chunkX = center.x - SEARCH_RADIUS_CHUNKS; chunkX <= center.x + SEARCH_RADIUS_CHUNKS; chunkX++) {
			for (int chunkZ = center.z - SEARCH_RADIUS_CHUNKS; chunkZ <= center.z + SEARCH_RADIUS_CHUNKS; chunkZ++) {
				LevelChunk chunk = client.level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof AvReceiverBlockEntity receiver)) {
						continue;
					}
					double distance = Vec3.atCenterOf(receiver.getBlockPos()).distanceToSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = receiver;
					}
				}
			}
		}
		return best;
	}

	private static int noReceiver(FabricClientCommandSource source) {
		source.sendError(Component.literal("No AV receiver within 64 blocks"));
		return 0;
	}
}
