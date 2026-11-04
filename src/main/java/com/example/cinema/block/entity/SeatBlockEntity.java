package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marker block entity for the cinema seat; it has no audio or calibration role. */
public class SeatBlockEntity extends BlockEntity {
	public SeatBlockEntity(BlockPos pos, BlockState state) {
		super(Cinema.seatBlockEntity, pos, state);
	}
}
