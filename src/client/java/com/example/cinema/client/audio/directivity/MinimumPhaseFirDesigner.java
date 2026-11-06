package com.example.cinema.client.audio.directivity;

import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;

/**
 * Designs a minimum-phase FIR from the profile's H/V directivity and on-axis
 * response. The returned filter reproduces the directivity shape relative to
 * 1 kHz; {@link MinimumPhaseFir#overallGain()} carries the broadband level that
 * was removed before fitting.
 *
 * <p>The cepstrum method is used: log magnitude -> real cepstrum -> minimum-
 * phase window -> complex spectrum -> inverse FFT -> truncated impulse
 * response. A 4096-tap filter at 48 kHz is enough to keep the tested measured
 * profiles within about 0.2 dB of the raw H/V target.
 */
public final class MinimumPhaseFirDesigner {
	public static final int LENGTH = 4096;
	private static final int FFT_SIZE = 16384;
	private static final double SAMPLE_RATE = 48_000.0;
	private static final double MIN_HZ = 20.0;
	private static final double MAX_HZ = 20_000.0;
	private static final double REFERENCE_HZ = 1_000.0;
	private static final double FLAT_THRESHOLD_DB = 0.01;

	private MinimumPhaseFirDesigner() {
	}

	public static MinimumPhaseFir design(SpeakerDataProfile profile,
										 double horizontalDegrees,
										 double verticalDegrees) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		double h = wrapHorizontal(horizontalDegrees);
		double v = Math.max(-90.0, Math.min(90.0, verticalDegrees));
		double offsetDb = profile.gainDb(h, v, REFERENCE_HZ);
		double[] target = targetShape(profile, h, v, offsetDb);
		if (maxAbs(target) < FLAT_THRESHOLD_DB) {
			return new MinimumPhaseFir(new float[]{1.0f}, gain(offsetDb), offsetDb,
				0.0, profile.id(), h, v);
		}

		double[] re = new double[FFT_SIZE];
		double[] im = new double[FFT_SIZE];
		int half = FFT_SIZE / 2;
		double ln10Over20 = Math.log(10.0) / 20.0;
		for (int i = 0; i <= half; i++) {
			re[i] = target[i] * ln10Over20;
			if (i > 0 && i < half) {
				re[FFT_SIZE - i] = re[i];
			}
		}
		Fft.transform(re, im, true);

		// Real cepstrum minimum-phase window: c[0], 2*c[1..N/2-1], c[N/2].
		for (int i = 1; i < half; i++) {
			re[i] *= 2.0;
		}
		for (int i = half + 1; i < FFT_SIZE; i++) {
			re[i] = 0.0;
		}
		im = new double[FFT_SIZE];
		Fft.transform(re, im, false);

		for (int i = 0; i < FFT_SIZE; i++) {
			double magnitude = Math.exp(re[i]);
			double phase = im[i];
			re[i] = magnitude * Math.cos(phase);
			im[i] = magnitude * Math.sin(phase);
		}
		Fft.transform(re, im, true);

		float[] taps = new float[LENGTH];
		double energy = 0.0;
		for (int i = 0; i < LENGTH; i++) {
			taps[i] = (float) re[i];
			energy += taps[i] * taps[i];
		}
		if (energy < 1.0e-20) {
			return new MinimumPhaseFir(new float[]{1.0f}, gain(offsetDb), offsetDb,
				0.0, profile.id(), h, v);
		}

		double reference = responseAt(taps, REFERENCE_HZ);
		if (Math.abs(reference) > 1.0e-9) {
			float scale = (float) (1.0 / reference);
			for (int i = 0; i < taps.length; i++) {
				taps[i] *= scale;
			}
		}
		double maxError = maxError(profile, h, v, offsetDb, taps);
		return new MinimumPhaseFir(taps, gain(offsetDb), offsetDb, maxError,
			profile.id(), h, v);
	}

	private static double[] targetShape(SpeakerDataProfile profile, double h,
										double v, double offsetDb) {
		int half = FFT_SIZE / 2;
		double[] target = new double[half + 1];
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / FFT_SIZE;
			double clamped = Math.max(MIN_HZ, Math.min(MAX_HZ, frequency));
			target[i] = profile.gainDb(h, v, clamped) - offsetDb;
		}
		// Force exactly 0 dB at the reference bin so the FIR normalization and
		// the requested -10 dB/±1 dB checks use the same anchor.
		int referenceBin = (int) Math.round(REFERENCE_HZ * FFT_SIZE / SAMPLE_RATE);
		referenceBin = Math.max(0, Math.min(half, referenceBin));
		double referenceValue = target[referenceBin];
		for (int i = 0; i <= half; i++) {
			target[i] -= referenceValue;
		}
		return target;
	}

	private static double maxError(SpeakerDataProfile profile, double h, double v,
								   double offsetDb, float[] taps) {
		double maxError = 0.0;
		for (int i = 1; i < 512; i++) {
			double t = i / 512.0;
			double frequency = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t);
			double target = profile.gainDb(h, v, frequency) - offsetDb;
			double actual = 20.0 * Math.log10(Math.max(1.0e-12,
				responseAt(taps, frequency)));
			maxError = Math.max(maxError, Math.abs(target - actual));
		}
		return maxError;
	}

	private static double responseAt(float[] taps, double frequencyHz) {
		double w = 2.0 * Math.PI * frequencyHz / SAMPLE_RATE;
		double re = 0.0;
		double im = 0.0;
		for (int i = 0; i < taps.length; i++) {
			double phase = -w * i;
			re += taps[i] * Math.cos(phase);
			im += taps[i] * Math.sin(phase);
		}
		return Math.hypot(re, im);
	}

	private static double maxAbs(double[] values) {
		double max = 0.0;
		for (double value : values) {
			max = Math.max(max, Math.abs(value));
		}
		return max;
	}

	private static double gain(double db) {
		return Math.pow(10.0, db / 20.0);
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
