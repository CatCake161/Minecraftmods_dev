package com.example.cinema.item;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/**
 * Server-side validation for operations performed through a held cinema remote.
 * The remote is a universal scanner: no binding is required.
 */
public final class RemoteControlAccess {
	public static final double MAX_REMOTE_DISTANCE = 128.0;

	private RemoteControlAccess() {
	}

	public static boolean canControl(ServerPlayer player, BlockPos pos, RemoteBinding.Kind kind) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(pos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}

	public static boolean isHoldingRemote(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).is(Cinema.REMOTE_CONTROLLER_ITEM)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A speaker can be accessed through a held remote within range.
	 */
	public static boolean canAccessLinkedSpeaker(ServerPlayer player, BlockPos speakerPos) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(speakerPos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}

	/**
	 * A projector can be accessed through a held remote within range.
	 */
	public static boolean canAccessLinkedProjector(ServerPlayer player, BlockPos projectorPos) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(projectorPos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}
}
