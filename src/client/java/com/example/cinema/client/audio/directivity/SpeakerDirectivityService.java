package com.example.cinema.client.audio.directivity;

import net.minecraft.world.phys.Vec3;

/**
 * CTA-2034-A-style angle helpers shared by the direct and reflection paths.
 *
 * <p>Positive horizontal is listener-right when facing the speaker; positive
 * vertical is up. The speaker forward vector is horizontal. Angles are
 * quantized to 5 degree steps before they become part of a FIR cache key.
 */
public final class SpeakerDirectivityService {
	private static final int ANGLE_BUCKET_DEGREES = 5;

	private SpeakerDirectivityService() {
	}

	public static Angles angles(Vec3 speakerPosition, Vec3 forward, Vec3 listener) {
		Vec3 toListener = listener.subtract(speakerPosition);
		double horizontalDistance = Math.hypot(toListener.x, toListener.z);
		double horizontal;
		if (horizontalDistance < 1.0e-6) {
			horizontal = 0.0;
		} else {
			Vec3 direction = new Vec3(toListener.x, 0.0, toListener.z)
				.scale(1.0 / horizontalDistance);
			Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(forward);
			if (right.lengthSqr() < 1.0e-9) {
				right = new Vec3(1.0, 0.0, 0.0);
			} else {
				right = right.normalize();
			}
			horizontal = Math.toDegrees(Math.atan2(direction.dot(right),
				direction.dot(forward)));
		}
		double vertical = Math.toDegrees(Math.atan2(toListener.y, horizontalDistance));
		return new Angles(horizontal, vertical);
	}

	public record Angles(double horizontal, double vertical) {
	}

	public static int quantizeHorizontal(double value) {
		double wrapped = value % 360.0;
		if (wrapped > 180.0) {
			wrapped -= 360.0;
		} else if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		int quantized = (int) Math.round(wrapped / ANGLE_BUCKET_DEGREES)
			* ANGLE_BUCKET_DEGREES;
		if (quantized > 180) {
			quantized -= 360;
		}
		return quantized;
	}

	public static int quantizeVertical(double value) {
		int quantized = (int) Math.round(value / ANGLE_BUCKET_DEGREES)
			* ANGLE_BUCKET_DEGREES;
		return Math.max(-90, Math.min(90, quantized));
	}
}
