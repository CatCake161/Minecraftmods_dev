package com.example.cinema.client.audio.environment;

import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;

/**
 * Designs the direct-path environment FIR: ISO 9613-1 air absorption plus the
 * occlusion low-pass shape.
 *
 * <p>The filter is 2048 taps at 48 kHz, designed with an 8192-point cepstrum
 * and normalized to 0 dB at 50 Hz. Broadband distance attenuation
 * ({@code 1 / max(r, 1 m)}) and broadband occlusion attenuation are deliberately
 * <em>not</em> part of this filter; they are applied to the source gain. The
 * FIR only carries the frequency-dependent part, so neither effect is applied
 * twice.
 */
public final class EnvironmentFirDesigner {
	public static final int LENGTH = 2048;
	public static final int FFT_SIZE = 8192;
	public static final double SAMPLE_RATE = 48_000.0;
	/** Low-frequency anchor for the minimum-phase normalization. */
	public static final double REFERENCE_HZ = 50.0;
	private static final double MIN_DB = -96.0;
	private static final double FLAT_THRESHOLD_DB = 0.01;

	private EnvironmentFirDesigner() {
	}

	public static MinimumPhaseFir design(EnvironmentProfile profile) {
		if (profile == null || profile.isFlat()) {
			return MinimumPhaseFir.FLAT;
		}
		int half = FFT_SIZE / 2;
		double[] target = new double[half + 1];
		double referenceDb = responseDb(profile, 0.0);
		boolean shaped = false;
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / FFT_SIZE;
			double value = responseDb(profile, frequency) - referenceDb;
			value = Math.max(MIN_DB, value);
			target[i] = value;
			if (Math.abs(value) >= FLAT_THRESHOLD_DB) {
				shaped = true;
			}
		}
		if (!shaped) {
			return MinimumPhaseFir.FLAT;
		}
		return MinimumPhaseFirDesigner.designMagnitude(target, FFT_SIZE, LENGTH,
			SAMPLE_RATE, REFERENCE_HZ, "environment");
	}

	/** Target magnitude in dB for one frequency. */
	static double responseDb(EnvironmentProfile profile, double frequencyHz) {
		double db = 0.0;
		if (profile.airAbsorption()) {
			// 1 m is the zero reference, matching the engine's 1/max(r,1)
			// distance law. Only the path beyond 1 m absorbs high frequencies.
			db -= AirAbsorption.directPathAttenuationDb(profile.distanceMeters(),
				frequencyHz, profile.temperatureC(), profile.humidityPercent(),
				profile.pressureKpa());
		}
		if (profile.occlusionOrder() > 0
			&& profile.occlusionCutoffHz() < SAMPLE_RATE * 0.5) {
			db += lowPassDb(frequencyHz, profile.occlusionCutoffHz(),
				profile.occlusionOrder());
		}
		return db;
	}

	/**
	 * Butterworth low-pass magnitude:
	 * {@code |H|^2 = 1 / (1 + (f/fc)^(2n))}.
	 */
	private static double lowPassDb(double frequencyHz, double cutoffHz, int order) {
		if (frequencyHz <= 0.0 || cutoffHz <= 0.0 || order <= 0) {
			return 0.0;
		}
		double ratio = frequencyHz / cutoffHz;
		double power = Math.pow(ratio, 2.0 * order);
		return -10.0 * Math.log10(1.0 + power);
	}
}
