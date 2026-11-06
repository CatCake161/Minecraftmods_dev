package com.example.cinema.network;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.ScreenBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.item.RemoteControlAccess;
import com.example.cinema.pairing.CinemaPairing;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Server-side listing and execution for remote pairing.
 *
 * <p>The remote must be bound to the anchor (or the player must be within the
 * normal 16-block interaction range). The anchor-to-target distance uses the
 * same 128-block limit as the cable.
 */
public final class RemotePairingHandler {
	private static final double MAX_DISTANCE = 128.0;
	private static final int MAX_ENTRIES = 128;

	private RemotePairingHandler() {
	}

	public static void handleRequest(RemotePairingRequestPayload payload,
									 ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		if (!canUseAnchor(player, level, payload.anchorPos(), payload.anchorKind())) {
			sendList(player, level, payload.anchorPos(), payload.anchorKind());
			return;
		}
		sendList(player, level, payload.anchorPos(), payload.anchorKind());
	}

	public static void handleAction(RemotePairingActionPayload payload,
									ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		if (!canUseAnchor(player, level, payload.anchorPos(), payload.anchorKind())) {
			sendList(player, level, payload.anchorPos(), payload.anchorKind());
			return;
		}
		if (!compatible(payload.anchorKind(), payload.targetKind())) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.pairing_incompatible"), true);
			sendList(player, level, payload.anchorPos(), payload.anchorKind());
			return;
		}
		BlockPos anchor = payload.anchorPos();
		BlockPos target = payload.targetPos();
		if (!level.isLoaded(target)
			|| Vec3.atCenterOf(anchor).distanceToSqr(Vec3.atCenterOf(target))
			> MAX_DISTANCE * MAX_DISTANCE) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.pairing_out_of_range"), true);
			sendList(player, level, anchor, payload.anchorKind());
			return;
		}
		if (payload.link()) {
			CinemaPairing.remoteLink(player, level, anchor, target);
		} else {
			CinemaPairing.remoteUnlink(player, level, anchor, target);
		}
		sendList(player, level, anchor, payload.anchorKind());
	}

	private static boolean canUseAnchor(ServerPlayer player, ServerLevel level, BlockPos anchor,
										RemoteBinding.Kind kind) {
		if (!level.isLoaded(anchor)) {
			return false;
		}
		boolean near = player.blockPosition().distSqr(anchor) <= 16.0 * 16.0;
		if (!near && !RemoteControlAccess.canControl(player, anchor, kind)) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.pairing_out_of_range"), true);
			return false;
		}
		BlockEntity blockEntity = level.getBlockEntity(anchor);
		boolean valid = kind == RemoteBinding.Kind.RECEIVER
			? blockEntity instanceof AvReceiverBlockEntity
			: blockEntity instanceof ProjectorBlockEntity;
		if (!valid) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.pairing_missing"), true);
		}
		return valid;
	}

	private static boolean compatible(RemoteBinding.Kind anchorKind,
									  RemotePairingListPayload.TargetKind targetKind) {
		return switch (anchorKind) {
			case RECEIVER -> targetKind == RemotePairingListPayload.TargetKind.PROJECTOR
				|| targetKind == RemotePairingListPayload.TargetKind.SPEAKER;
			case PROJECTOR -> targetKind == RemotePairingListPayload.TargetKind.SCREEN;
		};
	}

	private static void sendList(ServerPlayer player, ServerLevel level, BlockPos anchor,
								 RemoteBinding.Kind kind) {
		ServerPlayNetworking.send(player, new RemotePairingListPayload(anchor, kind,
			list(level, anchor, kind)));
	}

	private static List<RemotePairingListPayload.Entry> list(ServerLevel level, BlockPos anchor,
															 RemoteBinding.Kind anchorKind) {
		List<RemotePairingListPayload.Entry> entries = new ArrayList<>();
		Set<BlockPos> seenScreens = new HashSet<>();
		BlockEntity anchorEntity = level.getBlockEntity(anchor);
		ChunkPos center = new ChunkPos(anchor);
		int radiusChunks = (int) Math.ceil(MAX_DISTANCE / 16.0);
		for (int chunkX = center.x - radiusChunks; chunkX <= center.x + radiusChunks; chunkX++) {
			for (int chunkZ = center.z - radiusChunks; chunkZ <= center.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					scanEntry(level, anchor, anchorKind, anchorEntity, blockEntity,
						seenScreens, entries);
					if (entries.size() >= MAX_ENTRIES) {
						break;
					}
				}
			}
		}
		entries.sort(Comparator.comparingDouble(RemotePairingListPayload.Entry::distance)
			.thenComparingInt(entry -> entry.kind().ordinal()));
		return entries;
	}

	private static void scanEntry(ServerLevel level, BlockPos anchor,
								  RemoteBinding.Kind anchorKind, BlockEntity anchorEntity,
								  BlockEntity candidate, Set<BlockPos> seenScreens,
								  List<RemotePairingListPayload.Entry> entries) {
		BlockPos pos = candidate.getBlockPos();
		double distance = Vec3.atCenterOf(anchor).distanceTo(Vec3.atCenterOf(pos));
		if (distance > MAX_DISTANCE) {
			return;
		}
		if (anchorKind == RemoteBinding.Kind.RECEIVER
			&& anchorEntity instanceof AvReceiverBlockEntity receiver) {
			if (candidate instanceof ProjectorBlockEntity projector) {
				entries.add(new RemotePairingListPayload.Entry(
					RemotePairingListPayload.TargetKind.PROJECTOR, pos, projector.name(),
					pos.equals(receiver.videoTarget()), distance));
			} else if (candidate instanceof SpeakerBlockEntity speaker) {
				entries.add(new RemotePairingListPayload.Entry(
					RemotePairingListPayload.TargetKind.SPEAKER, pos, speaker.name(),
					receiver.speakerTargets().contains(pos), distance));
			}
			return;
		}
		if (anchorKind == RemoteBinding.Kind.PROJECTOR
			&& anchorEntity instanceof ProjectorBlockEntity projector
			&& candidate instanceof ScreenBlockEntity) {
			if (seenScreens.contains(pos)) {
				return;
			}
			Set<BlockPos> group = ScreenBlockEntity.connectedBlocks(level, pos);
			seenScreens.addAll(group);
			BlockPos representative = group.stream()
				.min(Comparator.comparingInt((BlockPos p) -> p.getX())
					.thenComparingInt(BlockPos::getY)
					.thenComparingInt(BlockPos::getZ))
				.orElse(pos);
			String name = level.getBlockEntity(representative) instanceof ScreenBlockEntity screen
				? screen.name() : "";
			boolean linked = projector.screenPos() != null
				&& group.contains(projector.screenPos());
			double screenDistance = Vec3.atCenterOf(anchor)
				.distanceTo(Vec3.atCenterOf(representative));
			if (screenDistance <= MAX_DISTANCE) {
				entries.add(new RemotePairingListPayload.Entry(
					RemotePairingListPayload.TargetKind.SCREEN, representative, name,
					linked, screenDistance));
			}
		}
	}
}
