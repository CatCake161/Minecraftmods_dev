package com.example.cinema.pairing;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.ScreenBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.ProjectorBlock;
import com.example.cinema.block.ScreenBlock;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.Cinema;
import com.example.cinema.CinemaLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative two-click pairing for the cinema cable.
 *
 * <p>The first right-click selects a device. The second right-click on a
 * compatible device creates the link. Two sneak-right-clicks select two devices
 * and remove only the link between them. Screens store nothing themselves; the
 * projector stores the explicit screen link so the optics can resolve it without
 * a chunk scan.
 */
public final class CinemaPairing {
	private static final double MAX_LINK_DISTANCE = 128.0;
	private static final long SELECTION_TIMEOUT_TICKS = 20L * 60L;
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, Pending> CLEAR_PENDING = new HashMap<>();

	private CinemaPairing() {
	}

	public static InteractionResult useCable(UseOnContext context) {
		Level level = context.getLevel();
		BlockPos clicked = context.getClickedPos();
		if (!isDeviceBlock(level.getBlockState(clicked))) {
			return InteractionResult.PASS;
		}
		if (level.isClientSide) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel)
			|| !(context.getPlayer() instanceof ServerPlayer player)) {
			return InteractionResult.PASS;
		}

		Device device = deviceAt(serverLevel, clicked);
		if (device == null) {
			action(player, "message.cinema.pair.missing");
			return InteractionResult.SUCCESS;
		}
		if (player.isShiftKeyDown()) {
			PENDING.remove(player.getUUID());
			handleClearClick(player, serverLevel, device);
			return InteractionResult.SUCCESS;
		}
		CLEAR_PENDING.remove(player.getUUID());
		handleClick(player, serverLevel, device);
		return InteractionResult.SUCCESS;
	}

	public static void clear(UUID playerId) {
		PENDING.remove(playerId);
		CLEAR_PENDING.remove(playerId);
	}

	/**
	 * Links two devices for a remote-pairing request. The caller has already
	 * validated the remote binding and the player's access to the anchor device.
	 */
	public static boolean remoteLink(ServerPlayer player, ServerLevel level,
									 BlockPos anchorPos, BlockPos targetPos) {
		Device first = deviceAt(level, anchorPos);
		Device second = deviceAt(level, targetPos);
		if (first == null || second == null) {
			action(player, "message.cinema.pair.missing");
			return false;
		}
		if (!isCompatible(first.kind(), second.kind())) {
			action(player, "message.cinema.pair.incompatible",
				deviceName(first), deviceName(second), deviceName(second));
			return false;
		}
		double distance = Vec3.atCenterOf(first.pos()).distanceTo(Vec3.atCenterOf(second.pos()));
		if (distance > MAX_LINK_DISTANCE) {
			action(player, "message.cinema.pair.too_far");
			return false;
		}
		if (!link(player, level, first, second)) {
			return false;
		}
		action(player, "message.cinema.pair.linked", deviceName(first), deviceName(second));
		return true;
	}

	/** Removes the link between two devices for a remote-pairing request. */
	public static boolean remoteUnlink(ServerPlayer player, ServerLevel level,
									   BlockPos anchorPos, BlockPos targetPos) {
		Device first = deviceAt(level, anchorPos);
		Device second = deviceAt(level, targetPos);
		if (first == null || second == null) {
			action(player, "message.cinema.pair.missing");
			return false;
		}
		if (!isCompatible(first.kind(), second.kind())) {
			action(player, "message.cinema.pair.clear_incompatible");
			return false;
		}
		if (!unlink(level, first, second)) {
			action(player, "message.cinema.pair.no_pair_link",
				deviceName(first), deviceName(second));
			return false;
		}
		action(player, "message.cinema.pair.unlinked", deviceName(first), deviceName(second));
		return true;
	}

	private static void handleClick(ServerPlayer player, ServerLevel level, Device device) {
		UUID playerId = player.getUUID();
		Pending pending = PENDING.get(playerId);
		if (pending != null
			&& (!pending.dimension().equals(level.dimension())
				|| level.getGameTime() - pending.gameTime() > SELECTION_TIMEOUT_TICKS)) {
			PENDING.remove(playerId);
			pending = null;
		}

		if (pending == null) {
			select(player, level, device);
			return;
		}

		Device first = deviceAt(level, pending.pos());
		if (first == null) {
			PENDING.remove(playerId);
			select(player, level, device);
			return;
		}

		if (first.kind() == device.kind() && first.pos().equals(device.pos())) {
			PENDING.remove(playerId);
			action(player, "message.cinema.pair.cancelled");
			return;
		}

		if (!isCompatible(first.kind(), device.kind())) {
			select(player, level, device);
			action(player, "message.cinema.pair.incompatible",
				deviceName(first), deviceName(device), deviceName(device));
			return;
		}

		double distance = Vec3.atCenterOf(first.pos()).distanceTo(Vec3.atCenterOf(device.pos()));
		if (distance > MAX_LINK_DISTANCE) {
			action(player, "message.cinema.pair.too_far");
			return;
		}

		if (link(player, level, first, device)) {
			PENDING.remove(playerId);
			action(player, "message.cinema.pair.linked", deviceName(first), deviceName(device));
		}
	}

	private static void select(ServerPlayer player, ServerLevel level, Device device) {
		PENDING.put(player.getUUID(), new Pending(level.dimension(), device.pos(), level.getGameTime()));
		action(player, "message.cinema.pair.selected",
			deviceName(device), Component.literal(device.pos().toShortString()),
			partnerName(device.kind()));
	}

	private static boolean link(ServerPlayer player, ServerLevel level, Device first, Device second) {
		if (first.kind() == Kind.PROJECTOR && second.kind() == Kind.SCREEN) {
			return linkProjectorScreen(player, level, (ProjectorBlockEntity) first.entity(), second.pos());
		}
		if (first.kind() == Kind.SCREEN && second.kind() == Kind.PROJECTOR) {
			return linkProjectorScreen(player, level, (ProjectorBlockEntity) second.entity(), first.pos());
		}
		if (first.kind() == Kind.RECEIVER && second.kind() == Kind.SPEAKER) {
			return linkReceiverSpeaker((AvReceiverBlockEntity) first.entity(),
				(SpeakerBlockEntity) second.entity());
		}
		if (first.kind() == Kind.SPEAKER && second.kind() == Kind.RECEIVER) {
			return linkReceiverSpeaker((AvReceiverBlockEntity) second.entity(),
				(SpeakerBlockEntity) first.entity());
		}
		if (first.kind() == Kind.RECEIVER && second.kind() == Kind.PROJECTOR) {
			return linkReceiverProjector(level, (AvReceiverBlockEntity) first.entity(),
				(ProjectorBlockEntity) second.entity());
		}
		if (first.kind() == Kind.PROJECTOR && second.kind() == Kind.RECEIVER) {
			return linkReceiverProjector(level, (AvReceiverBlockEntity) second.entity(),
				(ProjectorBlockEntity) first.entity());
		}
		return false;
	}

	private static boolean linkProjectorScreen(ServerPlayer player, ServerLevel level,
											   ProjectorBlockEntity projector, BlockPos screenPos) {
		BlockState screenState = level.getBlockState(screenPos);
		if (!screenState.is(Cinema.SCREEN_BLOCK)) {
			action(player, "message.cinema.pair.missing");
			return false;
		}
		Direction facing = screenState.getValue(ScreenBlock.FACING);
		Vec3 normal = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
		Vec3 screenCenter = Vec3.atCenterOf(screenPos);
		Vec3 projectorCenter = Vec3.atCenterOf(projector.getBlockPos());
		if (projectorCenter.subtract(screenCenter).dot(normal) <= 0.05) {
			action(player, "message.cinema.pair.behind_screen");
			return false;
		}
		Direction projectorFacing = projector.getBlockState().getValue(ProjectorBlock.FACING);
		Vec3 forward = new Vec3(projectorFacing.getStepX(), 0.0, projectorFacing.getStepZ());
		if (screenCenter.subtract(projectorCenter).dot(forward) <= 0.05) {
			action(player, "message.cinema.pair.not_in_front");
			return false;
		}

		BlockPos previousOwner = ScreenBlockEntity.findOwner(level, screenPos);
		if (previousOwner != null && !previousOwner.equals(projector.getBlockPos())) {
			boolean stillActive = false;
			if (level.isLoaded(previousOwner)
				&& level.getBlockEntity(previousOwner) instanceof ProjectorBlockEntity old) {
				BlockPos oldScreen = old.screenPos();
				stillActive = oldScreen != null && sameScreen(level, oldScreen, screenPos);
			}
			if (stillActive) {
				action(player, "message.cinema.pair.screen_occupied");
				return false;
			}
			// The previous owner was removed or re-linked; reclaim the screen.
			ScreenBlockEntity.clearOwnerFor(level, screenPos, previousOwner);
		}

		BlockPos oldScreen = projector.screenPos();
		if (oldScreen != null && !sameScreen(level, oldScreen, screenPos)) {
			ScreenBlockEntity.clearOwnerFor(level, oldScreen, projector.getBlockPos());
		}
		projector.setScreenPos(screenPos);
		ScreenBlockEntity.applyOwnerToConnected(level, screenPos, projector.getBlockPos());
		CinemaLog.info(CinemaLog.PAIRING, "linked projector {} to screen {}",
			projector.getBlockPos().toShortString(), screenPos.toShortString());
		return true;
	}

	private static boolean linkReceiverSpeaker(AvReceiverBlockEntity receiver,
											   SpeakerBlockEntity speaker) {
		BlockPos previous = speaker.receiverPos();
		if (previous != null && !previous.equals(receiver.getBlockPos())
			&& speaker.getLevel() != null && speaker.getLevel().isLoaded(previous)
			&& speaker.getLevel().getBlockEntity(previous) instanceof AvReceiverBlockEntity old) {
			old.removeSpeaker(speaker.getBlockPos());
		}
		receiver.addSpeaker(speaker.getBlockPos());
		speaker.setReceiverPos(receiver.getBlockPos());
		CinemaLog.info(CinemaLog.PAIRING, "linked receiver {} to speaker {}",
			receiver.getBlockPos().toShortString(), speaker.getBlockPos().toShortString());
		return true;
	}

	private static boolean linkReceiverProjector(Level level, AvReceiverBlockEntity receiver,
												 ProjectorBlockEntity projector) {
		BlockPos receiverPos = receiver.getBlockPos();
		BlockPos projectorPos = projector.getBlockPos();
		BlockPos previous = projector.receiverPos();
		if (previous != null && !previous.equals(receiverPos)
			&& level.isLoaded(previous)
			&& level.getBlockEntity(previous) instanceof AvReceiverBlockEntity old) {
			old.clearProjectorTarget(projectorPos);
		}
		receiver.setVideoTarget(projectorPos);
		projector.setReceiverPos(receiverPos);
		CinemaLog.info(CinemaLog.PAIRING, "linked receiver {} to projector {}",
			receiverPos.toShortString(), projectorPos.toShortString());
		return true;
	}

	private static void handleClearClick(ServerPlayer player, ServerLevel level, Device device) {
		UUID playerId = player.getUUID();
		Pending pending = CLEAR_PENDING.get(playerId);
		if (pending != null
			&& (!pending.dimension().equals(level.dimension())
				|| level.getGameTime() - pending.gameTime() > SELECTION_TIMEOUT_TICKS)) {
			CLEAR_PENDING.remove(playerId);
			pending = null;
		}

		if (pending == null) {
			CLEAR_PENDING.put(playerId,
				new Pending(level.dimension(), device.pos(), level.getGameTime()));
			action(player, "message.cinema.pair.clear_selected", deviceName(device));
			return;
		}

		Device first = deviceAt(level, pending.pos());
		if (first == null) {
			CLEAR_PENDING.remove(playerId);
			action(player, "message.cinema.pair.missing");
			return;
		}

		if (first.kind() == device.kind() && first.pos().equals(device.pos())) {
			CLEAR_PENDING.remove(playerId);
			action(player, "message.cinema.pair.clear_cancelled");
			return;
		}

		if (!isCompatible(first.kind(), device.kind())) {
			CLEAR_PENDING.remove(playerId);
			action(player, "message.cinema.pair.clear_incompatible");
			return;
		}

		CLEAR_PENDING.remove(playerId);
		if (unlink(level, first, device)) {
			action(player, "message.cinema.pair.unlinked",
				deviceName(first), deviceName(device));
		} else {
			action(player, "message.cinema.pair.no_pair_link",
				deviceName(first), deviceName(device));
		}
	}

	private static boolean unlink(Level level, Device first, Device second) {
		if (first.kind() == Kind.PROJECTOR && second.kind() == Kind.SCREEN) {
			return unlinkProjectorScreen(level, (ProjectorBlockEntity) first.entity(), second.pos());
		}
		if (first.kind() == Kind.SCREEN && second.kind() == Kind.PROJECTOR) {
			return unlinkProjectorScreen(level, (ProjectorBlockEntity) second.entity(), first.pos());
		}
		if (first.kind() == Kind.RECEIVER && second.kind() == Kind.SPEAKER) {
			return unlinkReceiverSpeaker((AvReceiverBlockEntity) first.entity(),
				(SpeakerBlockEntity) second.entity());
		}
		if (first.kind() == Kind.SPEAKER && second.kind() == Kind.RECEIVER) {
			return unlinkReceiverSpeaker((AvReceiverBlockEntity) second.entity(),
				(SpeakerBlockEntity) first.entity());
		}
		if (first.kind() == Kind.RECEIVER && second.kind() == Kind.PROJECTOR) {
			return unlinkReceiverProjector((AvReceiverBlockEntity) first.entity(),
				(ProjectorBlockEntity) second.entity());
		}
		if (first.kind() == Kind.PROJECTOR && second.kind() == Kind.RECEIVER) {
			return unlinkReceiverProjector((AvReceiverBlockEntity) second.entity(),
				(ProjectorBlockEntity) first.entity());
		}
		return false;
	}

	private static boolean unlinkProjectorScreen(Level level, ProjectorBlockEntity projector,
												 BlockPos clickedScreen) {
		BlockPos linked = projector.screenPos();
		if (linked == null || !sameScreen(level, linked, clickedScreen)) {
			return false;
		}
		projector.clearScreen();
		ScreenBlockEntity.clearOwnerFor(level, clickedScreen, projector.getBlockPos());
		CinemaLog.info(CinemaLog.PAIRING, "unlinked projector {} from screen {}",
			projector.getBlockPos().toShortString(), clickedScreen.toShortString());
		return true;
	}

	private static boolean unlinkReceiverSpeaker(AvReceiverBlockEntity receiver,
												 SpeakerBlockEntity speaker) {
		BlockPos receiverPos = receiver.getBlockPos();
		BlockPos speakerPos = speaker.getBlockPos();
		boolean linked = false;
		if (receiver.speakerTargets().contains(speakerPos)) {
			receiver.removeSpeaker(speakerPos);
			linked = true;
		}
		if (receiverPos.equals(speaker.receiverPos())) {
			speaker.setReceiverPos(null);
			linked = true;
		}
		if (linked) {
			CinemaLog.info(CinemaLog.PAIRING, "unlinked receiver {} from speaker {}",
				receiverPos.toShortString(), speakerPos.toShortString());
		}
		return linked;
	}

	private static boolean unlinkReceiverProjector(AvReceiverBlockEntity receiver,
												   ProjectorBlockEntity projector) {
		BlockPos receiverPos = receiver.getBlockPos();
		BlockPos projectorPos = projector.getBlockPos();
		boolean linked = false;
		if (projectorPos.equals(receiver.videoTarget())) {
			receiver.clearProjectorTarget(projectorPos);
			linked = true;
		}
		if (receiverPos.equals(projector.receiverPos())) {
			projector.setReceiverPos(null);
			linked = true;
		}
		if (linked) {
			CinemaLog.info(CinemaLog.PAIRING, "unlinked receiver {} from projector {}",
				receiverPos.toShortString(), projectorPos.toShortString());
		}
		return linked;
	}

	/** True when both blocks belong to the same contiguous, same-facing screen. */
	private static boolean sameScreen(Level level, BlockPos first, BlockPos second) {
		if (first.equals(second)) {
			return true;
		}
		BlockState firstState = level.getBlockState(first);
		BlockState secondState = level.getBlockState(second);
		if (!firstState.is(Cinema.SCREEN_BLOCK) || !secondState.is(Cinema.SCREEN_BLOCK)) {
			return false;
		}
		Direction facing = firstState.getValue(ScreenBlock.FACING);
		if (secondState.getValue(ScreenBlock.FACING) != facing) {
			return false;
		}
		boolean alongX = facing.getAxis() == Direction.Axis.Z;
		if (alongX ? first.getZ() != second.getZ() : first.getX() != second.getX()) {
			return false;
		}

		int limit = 64 * 64;
		Set<BlockPos> visited = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		visited.add(first);
		queue.add(first);
		while (!queue.isEmpty() && visited.size() <= limit) {
			BlockPos current = queue.removeFirst();
			if (current.equals(second)) {
				return true;
			}
			for (BlockPos next : new BlockPos[]{
				current.offset(alongX ? 1 : 0, 0, alongX ? 0 : 1),
				current.offset(alongX ? -1 : 0, 0, alongX ? 0 : -1),
				current.above(),
				current.below()}) {
				if (visited.contains(next) || !level.isLoaded(next)) {
					continue;
				}
				BlockState state = level.getBlockState(next);
				if (state.is(Cinema.SCREEN_BLOCK)
					&& state.getValue(ScreenBlock.FACING) == facing) {
					visited.add(next);
					queue.addLast(next);
				}
			}
		}
		return false;
	}

	private static void action(ServerPlayer player, String key, Object... args) {
		player.displayClientMessage(Component.translatable(key, args), true);
	}

	private static boolean isDeviceBlock(BlockState state) {
		return state.is(Cinema.PROJECTOR_BLOCK)
			|| state.is(Cinema.SCREEN_BLOCK)
			|| state.is(Cinema.AV_RECEIVER_BLOCK)
			|| state.getBlock() instanceof SpeakerBlock;
	}

	private static Device deviceAt(Level level, BlockPos clicked) {
		BlockState state = level.getBlockState(clicked);
		if (state.is(Cinema.PROJECTOR_BLOCK)
			&& level.getBlockEntity(clicked) instanceof ProjectorBlockEntity projector) {
			return new Device(Kind.PROJECTOR, clicked, projector, state.getBlock());
		}
		if (state.is(Cinema.SCREEN_BLOCK)) {
			return new Device(Kind.SCREEN, clicked, null, state.getBlock());
		}
		if (state.is(Cinema.AV_RECEIVER_BLOCK)
			&& level.getBlockEntity(clicked) instanceof AvReceiverBlockEntity receiver) {
			return new Device(Kind.RECEIVER, clicked, receiver, state.getBlock());
		}
		if (state.getBlock() instanceof SpeakerBlock) {
			BlockPos lower = clicked;
			if (!(level.getBlockEntity(lower) instanceof SpeakerBlockEntity)) {
				BlockPos below = clicked.below();
				if (level.getBlockEntity(below) instanceof SpeakerBlockEntity) {
					lower = below;
				}
			}
			if (level.getBlockEntity(lower) instanceof SpeakerBlockEntity speaker) {
				return new Device(Kind.SPEAKER, lower, speaker,
					level.getBlockState(lower).getBlock());
			}
		}
		return null;
	}

	private static boolean isCompatible(Kind first, Kind second) {
		return (first == Kind.PROJECTOR && second == Kind.SCREEN)
			|| (first == Kind.SCREEN && second == Kind.PROJECTOR)
			|| (first == Kind.RECEIVER && second == Kind.SPEAKER)
			|| (first == Kind.SPEAKER && second == Kind.RECEIVER)
			|| (first == Kind.RECEIVER && second == Kind.PROJECTOR)
			|| (first == Kind.PROJECTOR && second == Kind.RECEIVER);
	}

	private static Component deviceName(Device device) {
		return Component.translatable(device.block().getDescriptionId());
	}

	private static Component partnerName(Kind kind) {
		return switch (kind) {
			case PROJECTOR -> Component.translatable(Cinema.SCREEN_BLOCK.getDescriptionId())
				.append(Component.literal(" / "))
				.append(Component.translatable(Cinema.AV_RECEIVER_BLOCK.getDescriptionId()));
			case SCREEN -> Component.translatable(Cinema.PROJECTOR_BLOCK.getDescriptionId());
			case RECEIVER -> Component.translatable("message.cinema.pair.speaker")
				.append(Component.literal(" / "))
				.append(Component.translatable(Cinema.PROJECTOR_BLOCK.getDescriptionId()));
			case SPEAKER -> Component.translatable(Cinema.AV_RECEIVER_BLOCK.getDescriptionId());
		};
	}

	private enum Kind {
		PROJECTOR,
		SCREEN,
		RECEIVER,
		SPEAKER
	}

	private record Device(Kind kind, BlockPos pos, BlockEntity entity, Block block) {
	}

	private record Pending(ResourceKey<Level> dimension, BlockPos pos, long gameTime) {
	}
}
