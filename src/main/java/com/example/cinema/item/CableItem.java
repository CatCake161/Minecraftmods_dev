package com.example.cinema.item;

import com.example.cinema.pairing.CinemaPairing;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * Two-click pairing tool for projectors/screens and receivers/speakers.
 *
 * <p>The first right-click selects a device; the second right-click on a
 * compatible device links them. Sneak-right-click clears the links owned by
 * that device. The server owns the selection and all link state.
 */
public class CableItem extends Item {
	public CableItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		return CinemaPairing.useCable(context);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
								TooltipFlag flag) {
		tooltip.add(Component.translatable("item.cinema.cable.tooltip").withStyle(ChatFormatting.GRAY));
	}
}
