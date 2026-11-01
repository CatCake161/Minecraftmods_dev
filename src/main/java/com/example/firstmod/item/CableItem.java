package com.example.firstmod.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Wiring tool placeholder for linking receivers, projectors and speakers.
 *
 * <p>The actual two-click linking behavior is added with the receiver pairing
 * step; the item is registered now so it can be placed in the creative tab and
 * used for in-game layout planning.
 */
public class CableItem extends Item {
	public CableItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
								TooltipFlag flag) {
		tooltip.add(Component.translatable("item.firstmod.cable.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
