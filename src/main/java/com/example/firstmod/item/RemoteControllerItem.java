package com.example.firstmod.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Remote control placeholder for opening a receiver/projector GUI from a seat.
 *
 * <p>Binding and distance checks arrive with the formal GUI/remote milestone.
 */
public class RemoteControllerItem extends Item {
	public RemoteControllerItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
								TooltipFlag flag) {
		tooltip.add(Component.translatable("item.firstmod.remote_controller.tooltip")
			.withStyle(ChatFormatting.GRAY));
	}
}
