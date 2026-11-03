package com.example.cinema.block.entity;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Marker block entity for the screen; the live texture is shared globally. */
public class ScreenBlockEntity extends BlockEntity {
	public ScreenBlockEntity(BlockPos pos, BlockState state) {
		super(Cinema.screenBlockEntity, pos, state);
	}
}
