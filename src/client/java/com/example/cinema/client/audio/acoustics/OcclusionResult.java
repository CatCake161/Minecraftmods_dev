package com.example.cinema.client.audio.acoustics;

/**
 * Result of one source-to-listener occlusion scan.
 *
 * <p>{@code attenuationDb} is broadband gain applied to the source gain;
 * {@code cutoffHz}/{@code order} describe the low-pass shape folded into the
 * environment FIR. The scanner caps attenuation so a deeply buried source
 * cannot disappear completely.
 */
public record OcclusionResult(double attenuationDb, double cutoffHz, int order) {
	public static final OcclusionResult NONE = new OcclusionResult(0.0, 20_000.0, 0);
	private static final double MAX_ATTENUATION_DB = 40.0;
	private static final double MIN_CUTOFF_HZ = 200.0;
	private static final double MAX_CUTOFF_HZ = 20_000.0;

	public OcclusionResult {
		attenuationDb = Math.max(0.0, Math.min(MAX_ATTENUATION_DB, attenuationDb));
		cutoffHz = Math.max(MIN_CUTOFF_HZ, Math.min(MAX_CUTOFF_HZ, cutoffHz));
		order = Math.max(0, Math.min(4, order));
	}

	public boolean isBlocked() {
		return attenuationDb > 0.0 && order > 0;
	}

	/** Linear broadband gain for the source gain stage. */
	public double linearGain() {
		return Math.pow(10.0, -attenuationDb / 20.0);
	}
}
