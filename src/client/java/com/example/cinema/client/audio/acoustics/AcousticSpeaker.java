package com.example.cinema.client.audio.acoustics;

import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** A physical speaker with its acoustic-center position and horizontal facing. */
public record AcousticSpeaker(BlockPos blockPos, SpeakerRole role, SpeakerType type,
							  Vec3 position, Direction facing, double levelDb,
							  String profileId) {
	public Vec3 forward() {
		return new Vec3(facing.getStepX(), 0.0, facing.getStepZ()).normalize();
	}

	public double distanceTo(Vec3 point) {
		return position.distanceTo(point);
	}
}
