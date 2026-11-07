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
 * response. A 4096-tap filter at 48 kHz keeps the tested measured profiles
 * within roughly 1 dB of the combined on-axis + H/V target; the longer 8192-tap
 * variant was about three times slower per channel and is not used.
 *
 * <p>{@link #designMagnitude} is the generic part of the same method. The
 * direct-path environment filter reuses it for air absorption and occlusion
 * low-pass shaping, so only one cepstrum implementation is maintained.
 */
public final class MinimumPhaseFirDesigner {
	public static final int LENGTH = 4096;
	private static final int FFT_SIZE = 16384;
	private static final double SAMPLE_RATE = 48_000.0;
	private static final double MIN_HZ = 20.0;
	private static final double MAX_HZ = 20_000.0;
	private static final double REFERENCE_HZ = 1_000.0;
	private static final double FLAT_THRESHOLD_DB = 0.01;
	private static final double LN10_OVER_20 = Math.log(10.0) / 20.0;

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
		// Reconstruct the absolute on-axis response and add the relative
		// directivity curve. The 1 kHz value of that combined response is the
		// source gain; the FIR carries the remaining frequency shape.
		double offsetDb = profile.onAxisEqGainDb(REFERENCE_HZ)
			+ profile.gainDb(h, v, REFERENCE_HZ);
		double[] target = targetShape(profile, h, v, offsetDb);
		if (maxAbs(target) < FLAT_THRESHOLD_DB) {
			return new MinimumPhaseFir(new float[]{1.0f}, gain(offsetDb), offsetDb,
				0.0, profile.id(), h, v);
		}
		MinimumPhaseFir shape = designMagnitude(target, FFT_SIZE, LENGTH, SAMPLE_RATE,
			REFERENCE_HZ, profile.id());
		float[] taps = shape.taps();
		double maxError = maxError(profile, h, v, offsetDb, taps);
		return new MinimumPhaseFir(taps, gain(offsetDb), offsetDb, maxError,
			profile.id(), h, v);
	}

	/**
	 * Designs a minimum-phase FIR from a one-sided magnitude response.
	 *
	 * <p>{@code targetDb} contains {@code fftSize / 2 + 1} bins from 0 Hz to
	 * Nyquist. The returned taps are normalized so the response at
	 * {@code referenceHz} is exactly 0 dB; the caller applies any broadband
	 * level separately. The filter identifier is carried through for caching
	 * and diagnostics.
	 */
	public static MinimumPhaseFir designMagnitude(double[] targetDb, int fftSize,
												  int length, double sampleRate,
												  double referenceHz, String id) {
		if (targetDb == null || fftSize < 2 || (fftSize & (fftSize - 1)) != 0) {
			throw new IllegalArgumentException("invalid FFT size");
		}
		int half = fftSize / 2;
		if (targetDb.length != half + 1 || length <= 0 || !(sampleRate > 0.0)
			|| !(referenceHz > 0.0) || referenceHz >= sampleRate * 0.5) {
			throw new IllegalArgumentException("invalid magnitude response");
		}
		String filterId = id == null ? "" : id;
		if (maxAbs(targetDb) < FLAT_THRESHOLD_DB) {
			return new MinimumPhaseFir(new float[]{1.0f}, 1.0, 0.0, 0.0,
				filterId, 0.0, 0.0);
		}

		double[] re = new double[fftSize];
		double[] im = new double[fftSize];
		for (int i = 0; i <= half; i++) {
			re[i] = targetDb[i] * LN10_OVER_20;
			if (i > 0 && i < half) {
				re[fftSize - i] = re[i];
			}
		}
		Fft.transform(re, im, true);

		// Real cepstrum minimum-phase window: c[0], 2*c[1..N/2-1], c[N/2].
		for (int i = 1; i < half; i++) {
			re[i] *= 2.0;
		}
		for (int i = half + 1; i < fftSize; i++) {
			re[i] = 0.0;
		}
		im = new double[fftSize];
		Fft.transform(re, im, false);

		for (int i = 0; i < fftSize; i++) {
			double magnitude = Math.exp(re[i]);
			double phase = im[i];
			re[i] = magnitude * Math.cos(phase);
			im[i] = magnitude * Math.sin(phase);
		}
		Fft.transform(re, im, true);

		float[] taps = new float[length];
		double energy = 0.0;
		int copy = Math.min(length, fftSize);
		for (int i = 0; i < copy; i++) {
			taps[i] = (float) re[i];
			energy += taps[i] * taps[i];
		}
		if (energy < 1.0e-20) {
			return new MinimumPhaseFir(new float[]{1.0f}, 1.0, 0.0, 0.0,
				filterId, 0.0, 0.0);
		}

		double reference = responseAt(taps, referenceHz, sampleRate);
		if (Math.abs(reference) > 1.0e-9) {
			float scale = (float) (1.0 / reference);
			for (int i = 0; i < taps.length; i++) {
				taps[i] *= scale;
			}
		}
		return new MinimumPhaseFir(taps, 1.0, 0.0, 0.0, filterId, 0.0, 0.0);
	}

	private static double[] targetShape(SpeakerDataProfile profile, double h,
										double v, double offsetDb) {
		int half = FFT_SIZE / 2;
		double[] target = new double[half + 1];
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / FFT_SIZE;
			double clamped = Math.max(MIN_HZ, Math.min(MAX_HZ, frequency));
			target[i] = profile.onAxisEqGainDb(clamped)
				+ profile.gainDb(h, v, clamped) - offsetDb;
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
			double target = profile.onAxisEqGainDb(frequency)
				+ profile.gainDb(h, v, frequency) - offsetDb;
			double actual = 20.0 * Math.log10(Math.max(1.0e-12,
				responseAt(taps, frequency)));
			maxError = Math.max(maxError, Math.abs(target - actual));
		}
		return maxError;
	}

	/**
	 * Magnitude response of {@code taps} at one frequency. Exposed for
	 * diagnostics and for checking designed environment filters.
	 */
	public static double responseAt(float[] taps, double frequencyHz,
									double sampleRate) {
		double w = 2.0 * Math.PI * frequencyHz / sampleRate;
		double re = 0.0;
		double im = 0.0;
		for (int i = 0; i < taps.length; i++) {
			double phase = -w * i;
			re += taps[i] * Math.cos(phase);
			im += taps[i] * Math.sin(phase);
		}
		return Math.hypot(re, im);
	}

	private static double responseAt(float[] taps, double frequencyHz) {
		return responseAt(taps, frequencyHz, SAMPLE_RATE);
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
