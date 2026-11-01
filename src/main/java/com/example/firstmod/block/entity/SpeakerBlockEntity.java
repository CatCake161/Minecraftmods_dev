package com.example.firstmod.block.entity;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.SpeakerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/** One physical speaker; the receiver assigns its channel role and gain. */
public class SpeakerBlockEntity extends BlockEntity {
	private SpeakerRole role = SpeakerRole.NONE;
	private float gain = 1.0f;

	public SpeakerBlockEntity(BlockPos pos, BlockState blockState) {
		super(FirstMod.SPEAKER_BLOCK_ENTITY, pos, blockState);
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
			"Speaker %s (%s): role=%s gain=%.2f",
			worldPosition.toShortString(), type().id(), role.label(), gain));
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
	}

	@Override
	protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
		super.loadAdditional(tag, registries);
		role = type().sanitize(SpeakerRole.byName(tag.getString("role")));
		gain = tag.contains("gain") ? tag.getFloat("gain") : 1.0f;
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
