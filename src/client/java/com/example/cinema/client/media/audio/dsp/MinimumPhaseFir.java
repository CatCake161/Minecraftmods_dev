package com.example.cinema.client.media.audio.dsp;

/**
 * One minimum-phase FIR designed for a speaker direction. The tap array is
 * immutable after construction and is shared by the audio processor; callers
 * must not modify it.
 */
public record MinimumPhaseFir(float[] taps, double overallGain, double offsetDb,
							  double maxErrorDb, String profileId,
							  double horizontalDegrees, double verticalDegrees) {
	public static final MinimumPhaseFir FLAT =
		new MinimumPhaseFir(new float[]{1.0f}, 1.0, 0.0, 0.0, "", 0.0, 0.0);

	public MinimumPhaseFir {
		taps = taps == null || taps.length == 0 ? new float[]{1.0f} : taps;
		overallGain = Double.isFinite(overallGain) ? Math.max(0.0, overallGain) : 1.0;
		offsetDb = Double.isFinite(offsetDb) ? offsetDb : 0.0;
		maxErrorDb = Double.isFinite(maxErrorDb) ? maxErrorDb : 0.0;
		profileId = profileId == null ? "" : profileId;
	}

	public boolean isFlat() {
		return taps.length == 1 && Math.abs(taps[0] - 1.0f) < 1.0e-6f;
	}

	public int length() {
		return taps.length;
	}
}
