package com.example.cinema.network;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.item.RemoteControlAccess;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scans loaded cinema devices around the remote holder. This is for the
 * universal remote access panel; pairing stays in the receiver/projector GUIs
 * and the cable.
 */
public final class RemoteScanHandler {
	private static final double MAX_DISTANCE = 128.0;
	private static final int MAX_ENTRIES = 128;

	private RemoteScanHandler() {
	}

	public static void handle(RemoteScanRequestPayload payload,
							  ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		if (!RemoteControlAccess.isHoldingRemote(player)) {
			ServerPlayNetworking.send(player, new RemoteScanListPayload(List.of()));
			return;
		}
		List<RemoteScanListPayload.Entry> entries = new ArrayList<>();
		BlockPos center = player.blockPosition();
		ChunkPos centerChunk = new ChunkPos(center);
		int radiusChunks = (int) Math.ceil(MAX_DISTANCE / 16.0);
		for (int chunkX = centerChunk.x - radiusChunks;
			 chunkX <= centerChunk.x + radiusChunks && entries.size() < MAX_ENTRIES;
			 chunkX++) {
			for (int chunkZ = centerChunk.z - radiusChunks;
				 chunkZ <= centerChunk.z + radiusChunks && entries.size() < MAX_ENTRIES;
				 chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					addEntry(player, blockEntity, entries);
					if (entries.size() >= MAX_ENTRIES) {
						break;
					}
				}
			}
		}
		entries.sort(Comparator.comparingDouble(RemoteScanListPayload.Entry::distance)
			.thenComparingInt(entry -> entry.kind().ordinal()));
		ServerPlayNetworking.send(player, new RemoteScanListPayload(entries));
	}

	private static void addEntry(ServerPlayer player, BlockEntity blockEntity,
								 List<RemoteScanListPayload.Entry> entries) {
		BlockPos pos = blockEntity.getBlockPos();
		double distance = Vec3.atCenterOf(player.blockPosition())
			.distanceTo(Vec3.atCenterOf(pos));
		if (distance > MAX_DISTANCE) {
			return;
		}
		if (blockEntity instanceof AvReceiverBlockEntity receiver) {
			entries.add(new RemoteScanListPayload.Entry(
				RemoteScanListPayload.Kind.RECEIVER, pos, receiver.name(), distance));
		} else if (blockEntity instanceof ProjectorBlockEntity projector) {
			entries.add(new RemoteScanListPayload.Entry(
				RemoteScanListPayload.Kind.PROJECTOR, pos, projector.name(), distance));
		} else if (blockEntity instanceof SpeakerBlockEntity speaker) {
			entries.add(new RemoteScanListPayload.Entry(
				RemoteScanListPayload.Kind.SPEAKER, pos, speaker.name(), distance));
		}
	}
}
