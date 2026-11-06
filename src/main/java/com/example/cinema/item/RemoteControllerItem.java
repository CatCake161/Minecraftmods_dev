package com.example.cinema.item;

import com.example.cinema.Cinema;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * Binds to a projector or AV receiver and opens its GUI from a distance.
 *
 * <p>Sneak-right-click a projector/receiver to bind; right-click to open the
 * bound device. Sneak-right-click in the air to unbind. The server validates
 * the binding, the device chunk and the 128-block remote range.
 */
public class RemoteControllerItem extends Item {
	public RemoteControllerItem(Properties properties) {
		super(properties);
	}

	@Override
	public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
								TooltipFlag flag) {
		tooltip.add(Component.translatable("item.cinema.remote_controller.tooltip")
			.withStyle(ChatFormatting.GRAY));
		RemoteBinding.get(stack).ifPresentOrElse(binding -> tooltip.add(
				Component.translatable("item.cinema.remote_controller.bound",
					Component.translatable(binding.kind() == RemoteBinding.Kind.PROJECTOR
						? Cinema.PROJECTOR_BLOCK.getDescriptionId()
						: Cinema.AV_RECEIVER_BLOCK.getDescriptionId()),
					Component.literal(binding.pos().toShortString()))
					.withStyle(ChatFormatting.AQUA)),
			() -> tooltip.add(Component.translatable("item.cinema.remote_controller.unbound")
				.withStyle(ChatFormatting.DARK_GRAY)));
	}
}
