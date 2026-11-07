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
 *
 * <p>Changing the directivity coefficients at a block boundary clicks, so the
 * old and new filters run in parallel for a short crossfade. The old path is
 * faded out and then discarded; the new path already carries its own tail for
 * the next block.
 */
public final class FirProcessor {
	/** 1024 frames is about 21 ms at 48 kHz. */
	private static final int CROSSFADE_FRAMES = 1024;

	private Path primary = new Path();
	private Path fading;
	private MinimumPhaseFir pendingFilter;
	private int fadeRemaining;
	private boolean hasOutput;
	private float[] fadeScratch = new float[0];

	public void setFilter(MinimumPhaseFir next) {
		MinimumPhaseFir value = next == null ? MinimumPhaseFir.FLAT : next;
		if (value == primary.filter) {
			pendingFilter = null;
			return;
		}
		if (!hasOutput) {
			// Nothing has been rendered since construction/reset, so there is
			// no output to keep continuous; avoid a pointless startup fade.
			primary = new Path(value);
			fading = null;
			pendingFilter = null;
			fadeRemaining = 0;
			return;
		}
		if (fading != null) {
			// The 20 Hz layout updates are slower than this crossfade, so a
			// request normally arrives after the previous fade has finished.
			// Keep only the latest one if it does not.
			pendingFilter = value;
			return;
		}
		beginFade(value);
	}

	public void reset() {
		primary.reset();
		fading = null;
		pendingFilter = null;
		fadeRemaining = 0;
		hasOutput = false;
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
		hasOutput = true;
		primary.process(interleaved, channels, channel, sourceFrameOffset, frames, out);
		if (fading == null || fadeRemaining <= 0) {
			return;
		}
		ensureFadeScratch(frames);
		fading.process(interleaved, channels, channel, sourceFrameOffset, frames, fadeScratch);
		int fade = Math.min(fadeRemaining, frames);
		float span = Math.max(1, CROSSFADE_FRAMES - 1);
		for (int i = 0; i < fade; i++) {
			float oldWeight = (fadeRemaining - 1 - i) / span;
			out[i] = out[i] * (1.0f - oldWeight) + fadeScratch[i] * oldWeight;
		}
		fadeRemaining -= fade;
		if (fadeRemaining <= 0) {
			fading = null;
			MinimumPhaseFir next = pendingFilter;
			pendingFilter = null;
			if (next != null && next != primary.filter) {
				beginFade(next);
			}
		}
	}

	private void beginFade(MinimumPhaseFir next) {
		fading = primary;
		primary = new Path(next);
		fadeRemaining = CROSSFADE_FRAMES;
	}

	private void ensureFadeScratch(int frames) {
		if (fadeScratch.length < frames) {
			fadeScratch = new float[frames];
		}
	}

	/** One filter plus its convolution tail and cached FFT. */
	private static final class Path {
		private MinimumPhaseFir filter;
		private double[] tail;
		private double[] fftRe = new double[0];
		private double[] fftIm = new double[0];
		private double[] firRe = new double[0];
		private double[] firIm = new double[0];
		private int spectrumSize;
		private MinimumPhaseFir spectrumFilter;

		Path() {
			this(MinimumPhaseFir.FLAT);
		}

		Path(MinimumPhaseFir filter) {
			this.filter = filter == null ? MinimumPhaseFir.FLAT : filter;
			tail = new double[Math.max(0, this.filter.length() - 1)];
		}

		void reset() {
			Arrays.fill(tail, 0.0);
		}

		void process(float[] interleaved, int channels, int channel,
					 int sourceFrameOffset, int frames, float[] out) {
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
}
