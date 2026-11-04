package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import com.example.cinema.block.ScreenBlock;
import com.example.cinema.util.CinemaNames;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Marker block entity for one screen cell. The live texture is shared globally;
 * this entity carries the optional user-facing screen name and the single
 * projector that owns this screen group.
 */
public class ScreenBlockEntity extends BlockEntity {
	private static final int MAX_SCREEN_BLOCKS = 64 * 64;

	private String name = "";
	private BlockPos ownerProjector;

	public ScreenBlockEntity(BlockPos pos, BlockState state) {
		super(Cinema.screenBlockEntity, pos, state);
	}

	public String name() {
		return name;
	}

	public void setName(String value) {
		String sanitized = CinemaNames.sanitize(value);
		if (name.equals(sanitized)) {
			return;
		}
		name = sanitized;
		markUpdated();
	}

	/** Projector that owns this screen group, or {@code null}. */
	public BlockPos ownerProjector() {
		return ownerProjector;
	}

	public void setOwnerProjector(BlockPos owner) {
		if (Objects.equals(ownerProjector, owner)) {
			return;
		}
		ownerProjector = owner;
		// Owner is server-side pairing state; no client block update needed.
		setChanged();
	}

	/**
	 * Applies one name to every connected screen cell on the same facing plane.
	 * This keeps the name readable from any block the projector is paired to.
	 */
	public static void applyToConnected(Level level, BlockPos start, String value) {
		for (BlockPos pos : connected(level, start)) {
			if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
				screen.setName(value);
			}
		}
	}

	public static boolean isSameScreen(Level level, BlockPos first, BlockPos second) {
		return connected(level, first).contains(second);
	}

	/** Returns the owner stored anywhere in the connected screen group. */
	public static BlockPos findOwner(Level level, BlockPos start) {
		for (BlockPos pos : connected(level, start)) {
			if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen
				&& screen.ownerProjector() != null) {
				return screen.ownerProjector();
			}
		}
		return null;
	}

	public static void applyOwnerToConnected(Level level, BlockPos start, BlockPos owner) {
		for (BlockPos pos : connected(level, start)) {
			if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen) {
				screen.setOwnerProjector(owner);
			}
		}
	}

	public static void clearOwnerFor(Level level, BlockPos start, BlockPos expectedOwner) {
		for (BlockPos pos : connected(level, start)) {
			if (level.getBlockEntity(pos) instanceof ScreenBlockEntity screen
				&& Objects.equals(screen.ownerProjector(), expectedOwner)) {
				screen.setOwnerProjector(null);
			}
		}
	}

	private static Set<BlockPos> connected(Level level, BlockPos start) {
		Set<BlockPos> result = new HashSet<>();
		BlockState startState = level.getBlockState(start);
		if (!(startState.getBlock() instanceof ScreenBlock)) {
			return result;
		}
		Direction facing = startState.getValue(ScreenBlock.FACING);
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		result.add(start);
		queue.addLast(start);
		while (!queue.isEmpty() && result.size() <= MAX_SCREEN_BLOCKS) {
			BlockPos pos = queue.removeFirst();
			for (Direction direction : Direction.values()) {
				BlockPos next = pos.relative(direction);
				if (result.contains(next) || !level.isLoaded(next)) {
					continue;
				}
				BlockState state = level.getBlockState(next);
				if (state.getBlock() instanceof ScreenBlock
					&& state.getValue(ScreenBlock.FACING) == facing) {
					result.add(next);
					queue.addLast(next);
				}
			}
		}
		return result;
	}

	private void markUpdated() {
		setChanged();
		if (level != null && !level.isClientSide) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.saveAdditional(tag, registries);
		tag.putString("name", name);
		if (ownerProjector != null) {
			tag.putLong("ownerProjector", ownerProjector.asLong());
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		name = CinemaNames.sanitize(tag.getString("name"));
		ownerProjector = tag.contains("ownerProjector")
			? BlockPos.of(tag.getLong("ownerProjector")) : null;
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return saveWithoutMetadata(registries);
	}

	@Override
	public Packet<ClientGamePacketListener> getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
