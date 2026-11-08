package com.example.cinema.client.audio.directivity;

/**
 * Minimum-phase FIR design from a one-sided magnitude response.
 *
 * <p>The common acoustic FIR builder is {@code CommonAcousticFirDesigner};
 * this class only keeps the generic cepstrum implementation and the magnitude
 * response helper used by tests and diagnostics.
 */
public final class MinimumPhaseFirDesigner {
	private static final double FLAT_THRESHOLD_DB = 0.01;
	private static final double LN10_OVER_20 = Math.log(10.0) / 20.0;

	private MinimumPhaseFirDesigner() {
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

	/**
	 * Magnitude response of {@code taps} at one frequency. Exposed for
	 * diagnostics and for checking designed acoustic filters.
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

	private static double maxAbs(double[] values) {
		double max = 0.0;
		for (double value : values) {
			max = Math.max(max, Math.abs(value));
		}
		return max;
	}
}
