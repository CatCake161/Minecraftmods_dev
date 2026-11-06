package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

/** Player ear position and look direction used by A1/A2. */
public record AcousticListener(Vec3 eye, Vec3 look, Vec3 up) {
	public AcousticListener {
		if (look == null || look.lengthSqr() < 1.0e-9) {
			look = new Vec3(0.0, 0.0, -1.0);
		} else {
			look = look.normalize();
		}
		if (up == null || up.lengthSqr() < 1.0e-9) {
			up = new Vec3(0.0, 1.0, 0.0);
		} else {
			up = up.normalize();
		}
	}
}
