package com.example.cinema.client.audio.environment;

import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;

/**
 * Designs the 2048-tap reflection-path FIR.
 *
 * <p>Target magnitude is the speaker's on-axis EQ plus the directivity at the
 * speaker-to-reflection-point angle, minus air absorption over the full
 * reflection path. The target is normalized at the room's Schroeder frequency;
 * the combined level at that frequency becomes the broadband source trim. The
 * reflection path therefore uses the same physical pieces as the direct path
 * but with its own radiation angle and its own normalization anchor.
 */
public final class ReflectionFirDesigner {
	public static final int LENGTH = 2_048;
	public static final int FFT_SIZE = 8_192;
	public static final double SAMPLE_RATE = 48_000.0;
	public static final double MIN_HZ = 20.0;
	public static final double MAX_HZ = 20_000.0;
	private static final double ERROR_MIN_HZ = 50.0;
	private static final double MIN_DB = -96.0;
	private static final double FLAT_THRESHOLD_DB = 0.01;
	private static final double MAX_ERROR_TARGET_DB = -40.0;

	private ReflectionFirDesigner() {
	}

	public static MinimumPhaseFir design(ReflectionFirProfile profile) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		double horizontal = wrapHorizontal(profile.horizontalDegrees());
		double vertical = Math.max(-90.0, Math.min(90.0, profile.verticalDegrees()));
		double schroeder = profile.schroederHz();
		double referenceDb = profile.combinedDb(schroeder);
		if (!Double.isFinite(referenceDb)) {
			referenceDb = 0.0;
		}
		int half = FFT_SIZE / 2;
		double[] target = new double[half + 1];
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / FFT_SIZE;
			double clamped = Math.max(MIN_HZ, Math.min(MAX_HZ, frequency));
			double value = profile.combinedDb(clamped) - referenceDb;
			target[i] = Math.max(MIN_DB, value);
		}
		int referenceBin = (int) Math.round(schroeder * FFT_SIZE / SAMPLE_RATE);
		referenceBin = Math.max(0, Math.min(half, referenceBin));
		double referenceValue = target[referenceBin];
		boolean shaped = false;
		for (int i = 0; i <= half; i++) {
			target[i] -= referenceValue;
			if (Math.abs(target[i]) >= FLAT_THRESHOLD_DB) {
				shaped = true;
			}
		}
		String id = id(profile);
		double trimGain = profile.trimGain();
		if (!shaped) {
			return new MinimumPhaseFir(new float[]{1.0f}, trimGain, referenceDb,
				0.0, id, horizontal, vertical);
		}
		MinimumPhaseFir shape = MinimumPhaseFirDesigner.designMagnitude(target,
			FFT_SIZE, LENGTH, SAMPLE_RATE, schroeder, id);
		double maxError = maxError(profile, referenceDb, shape.taps());
		return new MinimumPhaseFir(shape.taps(), trimGain, referenceDb, maxError,
			id, horizontal, vertical);
	}

	private static double maxError(ReflectionFirProfile profile, double referenceDb,
								   float[] taps) {
		double maxError = 0.0;
		int samples = 256;
		for (int i = 0; i < samples; i++) {
			double t = i / (double) (samples - 1);
			double frequency = ERROR_MIN_HZ * Math.pow(MAX_HZ / ERROR_MIN_HZ, t);
			double target = profile.combinedDb(frequency) - referenceDb;
			if (target < MAX_ERROR_TARGET_DB) {
				continue;
			}
			double actual = 20.0 * Math.log10(Math.max(1.0e-12,
				MinimumPhaseFirDesigner.responseAt(taps, frequency, SAMPLE_RATE)));
			maxError = Math.max(maxError, Math.abs(target - actual));
		}
		return maxError;
	}

	private static String id(ReflectionFirProfile profile) {
		return "reflection:" + (profile.speaker() == null ? "flat"
			: profile.speaker().id());
	}

	private static double wrapHorizontal(double angle) {
		double wrapped = angle % 360.0;
		if (wrapped > 180.0) {
			wrapped -= 360.0;
		} else if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		return wrapped;
	}
}
