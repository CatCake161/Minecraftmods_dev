package com.example.cinema.client.audio.filter;

/**
 * One normalized RBJ cookbook biquad section.
 *
 * <p>Coefficients are stored with {@code a0 = 1}. The magnitude response is
 * evaluated with the same {@code z^-1 = e^(-jw)} convention used by the
 * transposed direct form II processor, so design-time checks and runtime
 * filtering agree.
 */
public record Biquad(double b0, double b1, double b2, double a1, double a2) {
	/** Butterworth Q for a single second-order section. */
	public static final double DEFAULT_Q = 0.7071067811865476;
	private static final double MAX_GAIN_DB = 36.0;
	private static final double MIN_Q = 0.01;
	private static final double MIN_FREQUENCY_HZ = 1.0;

	public Biquad {
		if (!Double.isFinite(b0) || !Double.isFinite(b1) || !Double.isFinite(b2)
			|| !Double.isFinite(a1) || !Double.isFinite(a2)) {
			throw new IllegalArgumentException("non-finite biquad coefficient");
		}
	}

	public static Biquad peaking(double frequencyHz, double q, double gainDb,
								 double sampleRate) {
		double a = gainAmplitude(gainDb);
		double w0 = angularFrequency(frequencyHz, sampleRate);
		double alpha = Math.sin(w0) / (2.0 * clampQ(q));
		double cos = Math.cos(w0);
		return normalized(
			1.0 + alpha * a,
			-2.0 * cos,
			1.0 - alpha * a,
			1.0 + alpha / a,
			-2.0 * cos,
			1.0 - alpha / a);
	}

	public static Biquad lowShelf(double frequencyHz, double q, double gainDb,
								  double sampleRate) {
		double a = gainAmplitude(gainDb);
		double w0 = angularFrequency(frequencyHz, sampleRate);
		double alpha = Math.sin(w0) / (2.0 * clampQ(q));
		double cos = Math.cos(w0);
		double sqrtA = Math.sqrt(a);
		double twoSqrtAlpha = 2.0 * sqrtA * alpha;
		return normalized(
			a * ((a + 1.0) - (a - 1.0) * cos + twoSqrtAlpha),
			2.0 * a * ((a - 1.0) - (a + 1.0) * cos),
			a * ((a + 1.0) - (a - 1.0) * cos - twoSqrtAlpha),
			(a + 1.0) + (a - 1.0) * cos + twoSqrtAlpha,
			-2.0 * ((a - 1.0) + (a + 1.0) * cos),
			(a + 1.0) + (a - 1.0) * cos - twoSqrtAlpha);
	}

	public static Biquad highShelf(double frequencyHz, double q, double gainDb,
								   double sampleRate) {
		double a = gainAmplitude(gainDb);
		double w0 = angularFrequency(frequencyHz, sampleRate);
		double alpha = Math.sin(w0) / (2.0 * clampQ(q));
		double cos = Math.cos(w0);
		double sqrtA = Math.sqrt(a);
		double twoSqrtAlpha = 2.0 * sqrtA * alpha;
		return normalized(
			a * ((a + 1.0) + (a - 1.0) * cos + twoSqrtAlpha),
			-2.0 * a * ((a - 1.0) + (a + 1.0) * cos),
			a * ((a + 1.0) + (a - 1.0) * cos - twoSqrtAlpha),
			(a + 1.0) - (a - 1.0) * cos + twoSqrtAlpha,
			2.0 * ((a - 1.0) - (a + 1.0) * cos),
			(a + 1.0) - (a - 1.0) * cos - twoSqrtAlpha);
	}

	public static Biquad lowPass(double frequencyHz, double q, double sampleRate) {
		double w0 = angularFrequency(frequencyHz, sampleRate);
		double alpha = Math.sin(w0) / (2.0 * clampQ(q));
		double cos = Math.cos(w0);
		double b0 = (1.0 - cos) * 0.5;
		return normalized(b0, 1.0 - cos, b0,
			1.0 + alpha, -2.0 * cos, 1.0 - alpha);
	}

	public static Biquad highPass(double frequencyHz, double q, double sampleRate) {
		double w0 = angularFrequency(frequencyHz, sampleRate);
		double alpha = Math.sin(w0) / (2.0 * clampQ(q));
		double cos = Math.cos(w0);
		double b0 = (1.0 + cos) * 0.5;
		return normalized(b0, -(1.0 + cos), b0,
			1.0 + alpha, -2.0 * cos, 1.0 - alpha);
	}

	/**
	 * First-order Butterworth low-pass expressed as a biquad. The bilinear
	 * transform gives {@code K = tan(pi f0 / fs)} and
	 * {@code H(z) = K(1 + z^-1) / ((1 + K) + (K - 1) z^-1)}.
	 */
	public static Biquad firstOrderLowPass(double frequencyHz, double sampleRate) {
		double k = Math.tan(Math.PI * clampFrequency(frequencyHz, sampleRate)
			/ sampleRate);
		double a0 = 1.0 + k;
		return new Biquad(k / a0, k / a0, 0.0, (k - 1.0) / a0, 0.0);
	}

	/** Magnitude response in dB at {@code frequencyHz}. */
	public double magnitudeDb(double frequencyHz, double sampleRate) {
		if (!(sampleRate > 0.0) || !Double.isFinite(frequencyHz)) {
			return -120.0;
		}
		double w = 2.0 * Math.PI * frequencyHz / sampleRate;
		double cos = Math.cos(w);
		double sin = Math.sin(w);
		double cos2 = Math.cos(2.0 * w);
		double sin2 = Math.sin(2.0 * w);
		double numReal = b0 + b1 * cos + b2 * cos2;
		double numImag = -(b1 * sin + b2 * sin2);
		double denReal = 1.0 + a1 * cos + a2 * cos2;
		double denImag = -(a1 * sin + a2 * sin2);
		double numerator = numReal * numReal + numImag * numImag;
		double denominator = denReal * denReal + denImag * denImag;
		if (denominator < 1.0e-30) {
			return -120.0;
		}
		return 10.0 * Math.log10(Math.max(1.0e-24, numerator / denominator));
	}

	public boolean isFlat() {
		return Math.abs(b0 - 1.0) < 1.0e-9
			&& Math.abs(b1) < 1.0e-9
			&& Math.abs(b2) < 1.0e-9
			&& Math.abs(a1) < 1.0e-9
			&& Math.abs(a2) < 1.0e-9;
	}

	private static Biquad normalized(double b0, double b1, double b2,
									 double a0, double a1, double a2) {
		if (!(a0 > 1.0e-12) || !Double.isFinite(a0)) {
			throw new IllegalArgumentException("invalid biquad a0");
		}
		return new Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
	}

	private static double angularFrequency(double frequencyHz, double sampleRate) {
		if (!(sampleRate > 0.0)) {
			throw new IllegalArgumentException("sample rate must be positive");
		}
		return 2.0 * Math.PI * clampFrequency(frequencyHz, sampleRate) / sampleRate;
	}

	private static double clampFrequency(double frequencyHz, double sampleRate) {
		if (!(sampleRate > 0.0)) {
			throw new IllegalArgumentException("sample rate must be positive");
		}
		if (!Double.isFinite(frequencyHz)) {
			return MIN_FREQUENCY_HZ;
		}
		return Math.max(MIN_FREQUENCY_HZ,
			Math.min(sampleRate * 0.499_999, frequencyHz));
	}

	private static double clampQ(double q) {
		return Double.isFinite(q) ? Math.max(MIN_Q, q) : DEFAULT_Q;
	}

	private static double gainAmplitude(double gainDb) {
		double clamped = Double.isFinite(gainDb)
			? Math.max(-MAX_GAIN_DB, Math.min(MAX_GAIN_DB, gainDb)) : 0.0;
		return Math.pow(10.0, clamped / 40.0);
	}
}
