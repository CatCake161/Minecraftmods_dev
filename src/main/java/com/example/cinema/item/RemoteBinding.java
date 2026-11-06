package com.example.cinema.item;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Optional;

/**
 * Persistent binding stored on a cinema remote item stack.
 *
 * <p>Only projectors and AV receivers can be bound. The binding contains the
 * dimension, block position and device kind; it is validated again on the
 * server before every remote operation.
 */
public final class RemoteBinding {
	private static final String DIMENSION_KEY = "cinema_remote_dimension";
	private static final String POSITION_KEY = "cinema_remote_position";
	private static final String KIND_KEY = "cinema_remote_kind";

	private RemoteBinding() {
	}

	public static Optional<Binding> get(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || data.isEmpty()) {
			return Optional.empty();
		}
		CompoundTag tag = data.copyTag();
		if (!tag.contains(DIMENSION_KEY) || !tag.contains(POSITION_KEY) || !tag.contains(KIND_KEY)) {
			return Optional.empty();
		}
		ResourceLocation dimensionId = ResourceLocation.tryParse(tag.getString(DIMENSION_KEY));
		Kind kind = Kind.byId(tag.getString(KIND_KEY));
		if (dimensionId == null || kind == null) {
			return Optional.empty();
		}
		return Optional.of(new Binding(
			ResourceKey.create(Registries.DIMENSION, dimensionId),
			BlockPos.of(tag.getLong(POSITION_KEY)),
			kind));
	}

	public static void set(ItemStack stack, ResourceKey<Level> dimension, BlockPos pos, Kind kind) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.putString(DIMENSION_KEY, dimension.location().toString());
			tag.putLong(POSITION_KEY, pos.asLong());
			tag.putString(KIND_KEY, kind.id());
		});
	}

	public static void clear(ItemStack stack) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
			tag.remove(DIMENSION_KEY);
			tag.remove(POSITION_KEY);
			tag.remove(KIND_KEY);
		});
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data != null && data.isEmpty()) {
			stack.remove(DataComponents.CUSTOM_DATA);
		}
	}

	public static boolean matches(ItemStack stack, ResourceKey<Level> dimension,
								  BlockPos pos, Kind kind) {
		return get(stack)
			.map(binding -> binding.dimension().equals(dimension)
				&& binding.pos().equals(pos)
				&& binding.kind() == kind)
			.orElse(false);
	}

	/** Returns the bindable device kind at {@code pos}, or empty for other blocks. */
	public static Optional<Kind> kindAt(Level level, BlockPos pos) {
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (level.getBlockState(pos).is(Cinema.PROJECTOR_BLOCK)
			&& blockEntity instanceof ProjectorBlockEntity) {
			return Optional.of(Kind.PROJECTOR);
		}
		if (level.getBlockState(pos).is(Cinema.AV_RECEIVER_BLOCK)
			&& blockEntity instanceof AvReceiverBlockEntity) {
			return Optional.of(Kind.RECEIVER);
		}
		return Optional.empty();
	}

	public record Binding(ResourceKey<Level> dimension, BlockPos pos, Kind kind) {
	}

	public enum Kind {
		PROJECTOR("projector"),
		RECEIVER("receiver");

		private final String id;

		Kind(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		public static Kind byId(String id) {
			for (Kind kind : values()) {
				if (kind.id.equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}
}
