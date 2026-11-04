package com.example.cinema.client.media;

/**
 * Small, deterministic stereo-to-5.1 upmix.
 *
 * <p>FL/FR keep the original left/right signals. Center is the correlated
 * mid signal, LFE is a 120 Hz low-passed mid signal, and the back channels are
 * the side (difference) signal. This avoids changing real 5.1 sources and is
 * intentionally simple enough to tune by ear.
 */
final class StereoUpmixer {
	private static final double SAMPLE_RATE = 48_000.0;
	private static final double LFE_CUTOFF_HZ = 120.0;
	private static final double LFE_ALPHA =
		1.0 - Math.exp(-2.0 * Math.PI * LFE_CUTOFF_HZ / SAMPLE_RATE);
	private static final double LFE_GAIN = 0.5;

	private double lfeState;

	short[] upmix(short[] stereo, int frames) {
		short[] output = new short[frames * CinemaAudioEngine.CHANNELS];
		for (int frame = 0; frame < frames; frame++) {
			int left = stereo[frame * 2];
			int right = stereo[frame * 2 + 1];
			int center = (left + right) / 2;
			int backLeft = (left - right) / 2;
			int backRight = (right - left) / 2;

			double mid = (left + right) / 65536.0;
			lfeState += LFE_ALPHA * (mid - lfeState);
			int lfe = clampToPcm(lfeState * LFE_GAIN * 32767.0);

			int base = frame * CinemaAudioEngine.CHANNELS;
			output[base] = (short) left;
			output[base + 1] = (short) right;
			output[base + 2] = (short) center;
			output[base + 3] = (short) lfe;
			output[base + 4] = (short) backLeft;
			output[base + 5] = (short) backRight;
		}
		return output;
	}

	private static int clampToPcm(double value) {
		if (!Double.isFinite(value)) {
			return 0;
		}
		return (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
	}
}
