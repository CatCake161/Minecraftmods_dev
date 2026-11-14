package com.example.cinema.client.media.audio.acoustics.path;

/**
 * Direct-path propagation delay relative to the nearest selected speaker.
 *
 * <p>For a speaker at distance {@code d_i} and the nearest speaker at
 * {@code d_min}, the extra flight time is {@code (d_i - d_min) / c}. Using the
 * nearest speaker as the t=0 reference keeps the earliest direct arrival on the
 * media clock, so the A/V offset does not change when the layout grows or
 * shrinks. Reflections add the same offset, which turns their existing
 * {@code (L_i - d_i) / c} into {@code (L_i - d_min) / c} and preserves the
 * direct-to-reflection timing of every individual speaker.
 */
public final class DirectPropagationDelay {
	public static final double SPEED_OF_SOUND = 343.0;

	private DirectPropagationDelay() {
	}

	public static double seconds(double distanceMeters, double referenceMeters,
								 double speedOfSound, double maxSeconds) {
		if (!Double.isFinite(distanceMeters) || !Double.isFinite(referenceMeters)) {
			return 0.0;
		}
		double distance = Math.max(0.0, distanceMeters);
		double reference = Math.max(0.0, referenceMeters);
		double speed = Double.isFinite(speedOfSound) && speedOfSound > 0.0
			? speedOfSound : SPEED_OF_SOUND;
		double cap = finiteNonNegative(maxSeconds);
		double delay = Math.max(0.0, (distance - reference) / speed);
		return Math.min(cap, delay);
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
	}
}
