package com.example.cinema.client.media.audio.speaker;

/**
 * Manual subwoofer bass-management band.
 *
 * <p>The band is the user-editable 10-1000 Hz low/high range stored in the
 * speaker settings. It is applied as a gentle second-order Butterworth
 * high-pass/low-pass pair on top of the profile response, so the subwoofer
 * only reproduces the requested band. The profile's own -3 dB range remains
 * separate metadata.
 */
public record SubwooferBand(double lowHz, double highHz) {
	public static final double MIN_HZ = 10.0;
	public static final double MAX_HZ = 1_000.0;
	private static final double BUTTERWORTH_Q = 1.0 / Math.sqrt(2.0);
	private static final double MIN_DB = -120.0;

	public SubwooferBand {
		lowHz = clamp(Math.round(lowHz), MIN_HZ, MAX_HZ - 1.0);
		highHz = clamp(Math.round(highHz), lowHz + 1.0, MAX_HZ);
	}

	/** Combined high-pass/low-pass band shape in dB. */
	public double gainDb(double frequencyHz) {
		if (!Double.isFinite(frequencyHz) || frequencyHz <= 0.0) {
			return MIN_DB;
		}
		double highPass = SubwooferAcoustics.secondOrderHighPassDb(
			frequencyHz, lowHz, BUTTERWORTH_Q);
		double lowPass = SubwooferAcoustics.secondOrderLowPassDb(
			frequencyHz, highHz, BUTTERWORTH_Q);
		return Math.max(MIN_DB, highPass + lowPass);
	}

	/** In-band geometric mean used as the subwoofer FIR/source reference. */
	public double referenceHz() {
		return Math.sqrt(lowHz * highHz);
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
