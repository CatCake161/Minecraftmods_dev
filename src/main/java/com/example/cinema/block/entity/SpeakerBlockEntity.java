package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import com.example.cinema.block.SpeakerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Locale;
import java.util.Objects;

/** One physical speaker; the receiver assigns its channel role and gain. */
public class SpeakerBlockEntity extends BlockEntity {
	private SpeakerRole role = SpeakerRole.NONE;
	private float gain = 1.0f;
	private BlockPos receiverPos;

	public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
		super(Cinema.speakerBlockEntity, pos, blockState);
	}

	public SpeakerType type() {
		if (getBlockState().getBlock() instanceof SpeakerBlock speakerBlock) {
			return speakerBlock.type();
		}
		return SpeakerType.FLOORSTANDING;
	}

	public SpeakerRole role() {
		return role;
	}

	public float gain() {
		return gain;
	}

	/** Receiver this speaker is explicitly wired to, or {@code null}. */
	public BlockPos receiverPos() {
		return receiverPos;
	}

	/** Server-only: drop a link whose receiver was removed or no longer lists us. */
	public void serverTick(Level level) {
		if (receiverPos == null || level.getGameTime() % 20L != 0L) {
			return;
		}
		if (!level.isLoaded(receiverPos)) {
			return;
		}
		if (!(level.getBlockEntity(receiverPos) instanceof AvReceiverBlockEntity receiver)
			|| !receiver.speakerTargets().contains(worldPosition)) {
			setReceiverPos(null);
		}
	}

	public void setReceiverPos(BlockPos pos) {
		if (Objects.equals(receiverPos, pos)) {
			return;
		}
		receiverPos = pos;
		markUpdated();
	}

	public void clearReceiver(BlockPos expected) {
		if (receiverPos != null && receiverPos.equals(expected)) {
			setReceiverPos(null);
		}
	}

	public SpeakerRole cycleRole() {
		role = type().nextRole(role);
		markUpdated();
		return role;
	}

	public void setRole(SpeakerRole value) {
		role = type().sanitize(value);
		markUpdated();
	}

	public void setGain(float value) {
		gain = Math.max(0.0f, Math.min(2.0f, value));
		markUpdated();
	}

	public Component status() {
		return Component.literal(String.format(Locale.ROOT,
			"Speaker %s (%s): role=%s gain=%.2f receiver=%s",
			worldPosition.toShortString(), type().id(), role.label(), gain,
			receiverPos == null ? "none" : receiverPos.toShortString()));
	}

	@Override
	public void setRemoved() {
		// Chunk unload also calls setRemoved(); only tell the receiver when the
		// speaker block itself was removed.
		if (wasActuallyRemoved() && receiverPos != null && level.isLoaded(receiverPos)
			&& level.getBlockEntity(receiverPos) instanceof AvReceiverBlockEntity receiver) {
			receiver.removeSpeaker(worldPosition);
		}
		super.setRemoved();
	}

	private boolean wasActuallyRemoved() {
		if (level == null || level.isClientSide) {
			return false;
		}
		ChunkPos chunkPos = new ChunkPos(worldPosition);
		LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
		if (chunk == null) {
			// The chunk is already gone; this is an unload, not a block break.
			return false;
		}
		return !(chunk.getBlockState(worldPosition).getBlock() instanceof SpeakerBlock);
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
		tag.putString("role", role.name());
		tag.putFloat("gain", gain);
		if (receiverPos != null) {
			tag.putLong("receiverPos", receiverPos.asLong());
		}
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		role = type().sanitize(SpeakerRole.byName(tag.getString("role")));
		gain = tag.contains("gain") ? tag.getFloat("gain") : 1.0f;
		receiverPos = tag.contains("receiverPos") ? BlockPos.of(tag.getLong("receiverPos")) : null;
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
