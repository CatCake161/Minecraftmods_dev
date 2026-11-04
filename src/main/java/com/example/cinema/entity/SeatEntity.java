package com.example.cinema.entity;

import com.example.cinema.block.SeatBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible vehicle spawned by a cinema seat. It exists only while a player is
 * sitting and is deliberately not connected to audio routing or calibration.
 */
public class SeatEntity extends Entity {
	public SeatEntity(EntityType<?> entityType, Level level) {
		super(entityType, level);
		noPhysics = true;
		setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	protected void readAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	protected void addAdditionalSaveData(CompoundTag tag) {
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	public Vec3 getPassengerRidingPosition(Entity passenger) {
		return position();
	}

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide) {
			return;
		}
		BlockPos pos = blockPosition();
		BlockState state = level().getBlockState(pos);
		if (!(state.getBlock() instanceof SeatBlock) || getPassengers().isEmpty()) {
			ejectPassengers();
			discard();
		}
	}
}
