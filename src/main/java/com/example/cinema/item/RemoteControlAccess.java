package com.example.cinema.item;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * Shared validation for operations performed through a held cinema remote.
 * The remote is a universal scanner: no binding is required. The same rules are
 * used by the client GUIs and the server packet handlers so a button is never
 * enabled when the matching request would be rejected.
 */
public final class RemoteControlAccess {
	public static final double MAX_REMOTE_DISTANCE = 128.0;
	/** Reach for opening a device by hand, without a remote. */
	public static final double MAX_DIRECT_ACCESS_DISTANCE = 16.0;

	private RemoteControlAccess() {
	}

	public static boolean canControl(Player player, BlockPos pos, RemoteBinding.Kind kind) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(pos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}

	public static boolean isHoldingRemote(Player player) {
		for (InteractionHand hand : InteractionHand.values()) {
			if (player.getItemInHand(hand).is(Cinema.REMOTE_CONTROLLER_ITEM)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A speaker can be opened by hand within 16 blocks, or through any held
	 * cinema remote within 128 blocks. This is the single rule used by the
	 * receiver GUI's Ping/Access buttons and by the speaker packet handlers.
	 */
	public static boolean canAccessSpeaker(Player player, BlockPos speakerPos) {
		return player.blockPosition().distSqr(speakerPos)
				<= MAX_DIRECT_ACCESS_DISTANCE * MAX_DIRECT_ACCESS_DISTANCE
			|| canAccessLinkedSpeaker(player, speakerPos);
	}

	/**
	 * A speaker can be accessed through a held remote within range.
	 */
	public static boolean canAccessLinkedSpeaker(Player player, BlockPos speakerPos) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(speakerPos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}

	/**
	 * A projector can be accessed through a held remote within range.
	 */
	public static boolean canAccessLinkedProjector(Player player, BlockPos projectorPos) {
		return isHoldingRemote(player)
			&& player.blockPosition().distSqr(projectorPos)
				<= MAX_REMOTE_DISTANCE * MAX_REMOTE_DISTANCE;
	}
}
