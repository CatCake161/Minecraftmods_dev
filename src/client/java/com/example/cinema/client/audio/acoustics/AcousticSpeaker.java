package com.example.cinema.client.audio.acoustics;

import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A physical speaker with its acoustic-center position and 16-step horizontal aim. */
public record AcousticSpeaker(BlockPos blockPos, SpeakerRole role, SpeakerType type,
							  Vec3 position, Vec3 forward, float yawDegrees,
							  double levelDb, String profileId) {
	public AcousticSpeaker {
		forward = forward == null || forward.lengthSqr() < 1.0e-9
			? new Vec3(0.0, 0.0, -1.0) : forward.normalize();
	}

	public double distanceTo(Vec3 point) {
		return position.distanceTo(point);
	}
}
