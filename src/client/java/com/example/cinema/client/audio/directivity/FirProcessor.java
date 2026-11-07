package com.example.cinema.client.audio.directivity;

import java.util.Arrays;

/**
 * Overlap-add FIR processor for one speaker channel.
 *
 * <p>The filter is applied to a whole decoded chunk with a zero-padded FFT of
 * {@code nextPow2(frames + taps - 1)}. The convolution tail is carried into the
 * next chunk, so there is no block latency and the media clock stays aligned
 * with the direct path. Input and output stay in 32-bit float: the FIR shape
 * is allowed to exceed 0 dBFS before OpenAL applies the per-source gain, so
 * neither stage may clamp to 16-bit.
 */
public final class FirProcessor {
	private MinimumPhaseFir filter = MinimumPhaseFir.FLAT;
	private double[] tail = new double[0];
	private double[] fftRe = new double[0];
	private double[] fftIm = new double[0];
	private double[] firRe = new double[0];
	private double[] firIm = new double[0];
	private int spectrumSize;
	private MinimumPhaseFir spectrumFilter;

	public void setFilter(MinimumPhaseFir next) {
		MinimumPhaseFir value = next == null ? MinimumPhaseFir.FLAT : next;
		if (value == filter) {
			return;
		}
		int oldLength = filter.length();
		filter = value;
		if (value.length() != oldLength) {
			tail = new double[Math.max(0, value.length() - 1)];
		}
		spectrumSize = 0;
		spectrumFilter = null;
	}

	public void reset() {
		Arrays.fill(tail, 0.0);
	}

	/**
	 * Writes {@code frames} mono samples as unclamped 32-bit floats. The caller
	 * owns {@code out} and must provide at least {@code frames} elements.
	 */
	public void process(float[] interleaved, int channels, int channel,
						int sourceFrameOffset, int frames, float[] out) {
		if (frames <= 0) {
			return;
		}
		int length = filter.length();
		if (length <= 1) {
			float gain = filter.taps()[0];
			for (int i = 0; i < frames; i++) {
				out[i] = gain * interleaved[(sourceFrameOffset + i) * channels + channel];
			}
			return;
		}
		int needed = frames + length - 1;
		int fftSize = nextPowerOfTwo(needed);
		ensureFft(fftSize);
		Arrays.fill(fftRe, 0.0);
		Arrays.fill(fftIm, 0.0);
		for (int i = 0; i < frames; i++) {
			fftRe[i] = interleaved[(sourceFrameOffset + i) * channels + channel];
		}
		Fft.transform(fftRe, fftIm, false);
		ensureSpectrum(fftSize);
		for (int i = 0; i < fftSize; i++) {
			double re = fftRe[i];
			double im = fftIm[i];
			fftRe[i] = re * firRe[i] - im * firIm[i];
			fftIm[i] = re * firIm[i] + im * firRe[i];
		}
		Fft.transform(fftRe, fftIm, true);

		for (int i = 0; i < frames; i++) {
			double value = fftRe[i] + (i < tail.length ? tail[i] : 0.0);
			out[i] = (float) value;
		}
		int copy = Math.min(tail.length, fftSize - frames);
		if (copy > 0) {
			System.arraycopy(fftRe, frames, tail, 0, copy);
			if (copy < tail.length) {
				Arrays.fill(tail, copy, tail.length, 0.0);
			}
		}
	}

	private void ensureFft(int size) {
		if (fftRe.length != size) {
			fftRe = new double[size];
			fftIm = new double[size];
		}
	}

	private void ensureSpectrum(int size) {
		if (spectrumSize == size && spectrumFilter == filter) {
			return;
		}
		firRe = new double[size];
		firIm = new double[size];
		float[] taps = filter.taps();
		for (int i = 0; i < taps.length && i < size; i++) {
			firRe[i] = taps[i];
		}
		Fft.transform(firRe, firIm, false);
		spectrumSize = size;
		spectrumFilter = filter;
	}

	private static int nextPowerOfTwo(int value) {
		int result = 1;
		while (result < value) {
			result <<= 1;
		}
		return result;
	}
}
