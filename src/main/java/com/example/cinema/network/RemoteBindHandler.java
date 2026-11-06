package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.item.RemoteBinding;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Applies remote bind/unbind requests on the server.
 */
public final class RemoteBindHandler {
	private static final double MAX_BIND_DISTANCE = 16.0;

	private RemoteBindHandler() {
	}

	public static void handle(RemoteBindPayload payload, ServerPlayNetworking.Context context) {
		ServerPlayer player = context.player();
		ServerLevel level = player.serverLevel();
		ItemStack stack = player.getItemInHand(payload.hand());
		if (!stack.is(Cinema.REMOTE_CONTROLLER_ITEM)) {
			error(player, "message.cinema.remote.hold_remote");
			return;
		}
		if (payload.action() == RemoteBindPayload.Action.UNBIND) {
			RemoteBinding.clear(stack);
			player.displayClientMessage(Component.translatable("message.cinema.remote.unbound"), true);
			return;
		}
		BlockPos pos = payload.pos();
		if (!level.isLoaded(pos)
			|| player.blockPosition().distSqr(pos) > MAX_BIND_DISTANCE * MAX_BIND_DISTANCE) {
			error(player, "message.cinema.remote.too_far");
			return;
		}
		RemoteBinding.Kind actual = RemoteBinding.kindAt(level, pos).orElse(null);
		if (actual == null || actual != payload.kind()) {
			error(player, "message.cinema.remote.bind_target");
			return;
		}
		RemoteBinding.set(stack, level.dimension(), pos, actual);
		player.displayClientMessage(Component.translatable("message.cinema.remote.bound",
			Component.translatable(actual == RemoteBinding.Kind.PROJECTOR
				? Cinema.PROJECTOR_BLOCK.getDescriptionId()
				: Cinema.AV_RECEIVER_BLOCK.getDescriptionId()),
			Component.literal(pos.toShortString())), true);
	}

	private static void error(ServerPlayer player, String key) {
		player.displayClientMessage(Component.translatable(key), true);
	}
}
