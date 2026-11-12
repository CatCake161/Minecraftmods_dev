package com.example.cinema.client.media.audio.speaker;

/**
 * Analytic low-frequency response for unmeasured subwoofer profiles.
 *
 * <p>Measured profiles already contain the real on-axis response, including
 * their enclosure alignment, so they are returned unchanged. The built-in
 * analytic profiles use the enclosure label and the profile's -3 dB low edge:
 * sealed boxes roll off as a second-order Butterworth high-pass, ported boxes
 * as a fourth-order Butterworth high-pass. This keeps the ported alignment's
 * steeper infrasonic roll-off instead of treating the enclosure as metadata.
 */
public final class SubwooferAcoustics {
	private static final double BUTTERWORTH_Q = 1.0 / Math.sqrt(2.0);
	private static final double FOURTH_ORDER_Q1 = 0.5411961001461969;
	private static final double FOURTH_ORDER_Q2 = 1.3065629648763766;
	private static final double MIN_DB = -120.0;

	private SubwooferAcoustics() {
	}

	/**
	 * Enclosure response relative to the passband, in dB. Returns 0 for
	 * non-subwoofers, measured profiles and unknown enclosures.
	 */
	public static double enclosureGainDb(SpeakerDataProfile profile,
										 double frequencyHz) {
		if (profile == null || !profile.isSubwoofer() || profile.isMeasured()) {
			return 0.0;
		}
		double lowEdgeHz = profile.lowHz();
		if (!(lowEdgeHz > 0.0) || !Double.isFinite(lowEdgeHz)) {
			return 0.0;
		}
		return switch (profile.enclosure()) {
			case SEALED -> secondOrderHighPassDb(frequencyHz, lowEdgeHz,
				BUTTERWORTH_Q);
			case PORTED -> fourthOrderHighPassDb(frequencyHz, lowEdgeHz);
			case UNKNOWN -> 0.0;
		};
	}

	public static double secondOrderHighPassDb(double frequencyHz,
											  double cutoffHz, double q) {
		if (!(cutoffHz > 0.0) || !(q > 0.0) || !Double.isFinite(frequencyHz)) {
			return 0.0;
		}
		if (frequencyHz <= 0.0) {
			return MIN_DB;
		}
		double x = frequencyHz / cutoffHz;
		double x2 = x * x;
		double denominator = Math.sqrt(
			(1.0 - x2) * (1.0 - x2) + (x / q) * (x / q));
		double magnitude = x2 / Math.max(1.0e-12, denominator);
		return clampDb(20.0 * Math.log10(Math.max(1.0e-12, magnitude)));
	}

	public static double secondOrderLowPassDb(double frequencyHz,
											 double cutoffHz, double q) {
		if (!(cutoffHz > 0.0) || !(q > 0.0) || !Double.isFinite(frequencyHz)) {
			return 0.0;
		}
		if (frequencyHz <= 0.0) {
			return 0.0;
		}
		double x = frequencyHz / cutoffHz;
		double x2 = x * x;
		double denominator = Math.sqrt(
			(1.0 - x2) * (1.0 - x2) + (x / q) * (x / q));
		double magnitude = 1.0 / Math.max(1.0e-12, denominator);
		return clampDb(20.0 * Math.log10(Math.max(1.0e-12, magnitude)));
	}

	public static double fourthOrderHighPassDb(double frequencyHz,
											 double cutoffHz) {
		return clampDb(secondOrderHighPassDb(frequencyHz, cutoffHz,
			FOURTH_ORDER_Q1)
			+ secondOrderHighPassDb(frequencyHz, cutoffHz, FOURTH_ORDER_Q2));
	}

	private static double clampDb(double value) {
		if (!Double.isFinite(value)) {
			return MIN_DB;
		}
		return Math.max(MIN_DB, value);
	}
}
