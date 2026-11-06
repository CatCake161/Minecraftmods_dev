package com.example.cinema.client.item;

import com.example.cinema.Cinema;
import com.example.cinema.client.gui.RemoteDeviceScreen;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.network.RemoteBindPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Client-side interaction for the cinema remote.
 *
 * <p>Normal right-click opens the nearby-device scan panel. Sneak-right-click
 * on a projector/receiver binds it for the HUD; sneak-right-click in the air
 * unbinds. Pairing is intentionally left to the device GUIs and cable.
 */
public final class RemoteControllerClient {
	private RemoteControllerClient() {
	}

	public static InteractionResult useBlock(Player player, Level level, InteractionHand hand,
											 BlockHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(Cinema.REMOTE_CONTROLLER_ITEM)) {
			return InteractionResult.PASS;
		}
		if (player.isShiftKeyDown()) {
			if (level.isClientSide) {
				RemoteBinding.Kind kind = RemoteBinding.kindAt(level, hit.getBlockPos()).orElse(null);
				if (kind == null) {
					player.displayClientMessage(
						Component.translatable("message.cinema.remote.bind_target"), true);
				} else {
					ClientPlayNetworking.send(new RemoteBindPayload(hit.getBlockPos(), kind, hand,
						RemoteBindPayload.Action.BIND));
				}
			}
			return InteractionResult.SUCCESS;
		}
		if (level.isClientSide) {
			openPanel();
		}
		return InteractionResult.SUCCESS;
	}

	public static InteractionResultHolder<ItemStack> useItem(Player player, Level level,
															 InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(Cinema.REMOTE_CONTROLLER_ITEM)) {
			return InteractionResultHolder.pass(stack);
		}
		if (player.isShiftKeyDown()) {
			if (level.isClientSide) {
				ClientPlayNetworking.send(new RemoteBindPayload(BlockPos.ZERO,
					RemoteBinding.Kind.PROJECTOR, hand, RemoteBindPayload.Action.UNBIND));
			}
			return InteractionResultHolder.success(stack);
		}
		if (level.isClientSide) {
			openPanel();
		}
		return InteractionResultHolder.success(stack);
	}

	private static void openPanel() {
		Minecraft.getInstance().setScreen(new RemoteDeviceScreen());
	}
}
