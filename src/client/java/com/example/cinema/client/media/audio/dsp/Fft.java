package com.example.cinema.client.media.audio.dsp;

/**
 * Small in-place radix-2 complex FFT used by the minimum-phase FIR designer and
 * the overlap-add convolver. Forward uses e^(-i*2*pi*k*n/N); inverse applies
 * the 1/N scale and e^(+i*2*pi*k*n/N).
 */
final class Fft {
	private Fft() {
	}

	static void transform(double[] re, double[] im, boolean inverse) {
		int n = re.length;
		if (n != im.length || n == 0 || (n & (n - 1)) != 0) {
			throw new IllegalArgumentException("FFT needs two power-of-two arrays");
		}
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >> 1;
			while ((j & bit) != 0) {
				j ^= bit;
				bit >>= 1;
			}
			j ^= bit;
			if (i < j) {
				double tr = re[i];
				re[i] = re[j];
				re[j] = tr;
				double ti = im[i];
				im[i] = im[j];
				im[j] = ti;
			}
		}
		for (int len = 2; len <= n; len <<= 1) {
			double angle = (inverse ? 2.0 : -2.0) * Math.PI / len;
			double stepRe = Math.cos(angle);
			double stepIm = Math.sin(angle);
			int half = len >> 1;
			for (int start = 0; start < n; start += len) {
				double wRe = 1.0;
				double wIm = 0.0;
				for (int j = 0; j < half; j++) {
					int even = start + j;
					int odd = even + half;
					double oddRe = re[odd] * wRe - im[odd] * wIm;
					double oddIm = re[odd] * wIm + im[odd] * wRe;
					re[odd] = re[even] - oddRe;
					im[odd] = im[even] - oddIm;
					re[even] += oddRe;
					im[even] += oddIm;
					double nextRe = wRe * stepRe - wIm * stepIm;
					wIm = wRe * stepIm + wIm * stepRe;
					wRe = nextRe;
				}
			}
		}
		if (inverse) {
			double scale = 1.0 / n;
			for (int i = 0; i < n; i++) {
				re[i] *= scale;
				im[i] *= scale;
			}
		}
	}
}
