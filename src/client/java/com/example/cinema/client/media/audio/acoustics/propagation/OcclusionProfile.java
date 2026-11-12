package com.example.cinema.client.media.audio.acoustics.propagation;

/**
 * Per-metre occlusion properties of one block material.
 *
 * <p>These are game-tuning values, not a direct conversion from STC or
 * measured transmission loss. A one-metre stone wall is the reference point
 * for the strongest common occluder; thinner blocks contribute proportionally
 * less because the scanner integrates the actual path length through the
 * block's collision shape.
 */
public record OcclusionProfile(double attenuationDbPerMeter, double cutoffHz,
							   int order) {
	public static final OcclusionProfile TRANSPARENT =
		new OcclusionProfile(0.0, 20_000.0, 0);
	private static final double MIN_CUTOFF_HZ = 200.0;
	private static final double MAX_CUTOFF_HZ = 24_000.0;
	private static final double MAX_ATTENUATION_DB_PER_METER = 60.0;

	public OcclusionProfile {
		attenuationDbPerMeter = clamp(attenuationDbPerMeter, 0.0,
			MAX_ATTENUATION_DB_PER_METER);
		cutoffHz = clamp(cutoffHz, MIN_CUTOFF_HZ, MAX_CUTOFF_HZ);
		order = Math.max(0, Math.min(4, order));
	}

	public boolean isTransparent() {
		return attenuationDbPerMeter <= 0.0 || order <= 0;
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
