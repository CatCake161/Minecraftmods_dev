package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side registry of receiver playback proxies.
 *
 * <p>Receivers are discovered from loaded chunks near the player and then kept
 * tracked until their block entity or chunk disappears. M1 shares one audio
 * engine; the server rejects a second simultaneously playing receiver.
 */
public final class AvReceiverPlaybackManager {
	private static final int SCAN_RADIUS_BLOCKS = 64;
	private static final int SYNC_INTERVAL_TICKS = 10;
	private static final Map<BlockPos, AvReceiverPlayback> PLAYBACKS = new HashMap<>();
	private static final AtomicInteger TEXTURE_SEQUENCE = new AtomicInteger();
	private static CinemaAudioEngine audioEngine;
	private static ClientLevel lastLevel;
	private static int tickCounter;

	private AvReceiverPlaybackManager() {
	}

	public static void tick(Minecraft client) {
		if (client.level == null || client.player == null) {
			closeAll();
			lastLevel = null;
			return;
		}
		if (client.level != lastLevel) {
			closeAll();
			lastLevel = client.level;
			tickCounter = 0;
		}
		if (++tickCounter % SYNC_INTERVAL_TICKS != 0) {
			return;
		}
		scan(client.level, client.player.blockPosition());
	}

	public static AvReceiverPlayback playbackAt(BlockPos receiverPos) {
		return receiverPos == null ? null : PLAYBACKS.get(receiverPos);
	}

	public static void presentFrames() {
		for (AvReceiverPlayback playback : PLAYBACKS.values()) {
			playback.presentFrame();
		}
	}

	public static void shutdown() {
		closeAll();
		lastLevel = null;
	}

	static CinemaAudioEngine audioEngine() {
		if (audioEngine == null) {
			audioEngine = new CinemaAudioEngine();
			audioEngine.start();
		}
		return audioEngine;
	}

	static ResourceLocation nextTextureId() {
		return FirstMod.id("cinema_video_receiver_" + TEXTURE_SEQUENCE.incrementAndGet());
	}

	private static void scan(ClientLevel level, BlockPos center) {
		Set<BlockPos> seen = new HashSet<>();
		ChunkPos centerChunk = new ChunkPos(center);
		int radiusChunks = (int) Math.ceil(SCAN_RADIUS_BLOCKS / 16.0);
		for (int chunkX = centerChunk.x - radiusChunks; chunkX <= centerChunk.x + radiusChunks; chunkX++) {
			for (int chunkZ = centerChunk.z - radiusChunks; chunkZ <= centerChunk.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (blockEntity instanceof AvReceiverBlockEntity receiver) {
						seen.add(receiver.getBlockPos());
						sync(level, receiver);
					}
				}
			}
		}
		Iterator<Map.Entry<BlockPos, AvReceiverPlayback>> iterator = PLAYBACKS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<BlockPos, AvReceiverPlayback> entry = iterator.next();
			if (seen.contains(entry.getKey())) {
				continue;
			}
			BlockEntity blockEntity = level.getBlockEntity(entry.getKey());
			if (blockEntity instanceof AvReceiverBlockEntity receiver
				&& entry.getValue().sync(level, receiver)) {
				continue;
			}
			entry.getValue().close();
			iterator.remove();
		}
	}

	private static void sync(ClientLevel level, AvReceiverBlockEntity receiver) {
		BlockPos pos = receiver.getBlockPos();
		AvReceiverPlayback playback = PLAYBACKS.computeIfAbsent(pos, AvReceiverPlayback::new);
		if (!playback.sync(level, receiver)) {
			playback.close();
			PLAYBACKS.remove(pos);
		}
	}

	private static void closeAll() {
		for (AvReceiverPlayback playback : PLAYBACKS.values()) {
			playback.close();
		}
		PLAYBACKS.clear();
		// Keep the shared OpenAL context alive across worlds; players reset the
		// sources when they close, and recreating devices on every reconnect
		// would leak contexts.
	}
}
