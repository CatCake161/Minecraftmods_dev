package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.client.media.audio.playback.CinemaAudioEngine;

/**
 * Small, deterministic stereo-to-5.1 upmix.
 *
 * <p>FL/FR keep the original left/right signals. Center is the correlated
 * mid signal, LFE is a 120 Hz low-passed mid signal, and the back channels are
 * the side (difference) signal. This avoids changing real 5.1 sources and is
 * intentionally simple enough to tune by ear.
 *
 * <p>All samples stay in normalized 32-bit float. Nothing is clamped here:
 * the FIR directivity stage and the receiver/speaker gains still follow, and
 * only the final device conversion may quantize.
 */
public final class StereoUpmixer {
	private static final double SAMPLE_RATE = 48_000.0;
	private static final double LFE_CUTOFF_HZ = 120.0;
	private static final double LFE_ALPHA =
		1.0 - Math.exp(-2.0 * Math.PI * LFE_CUTOFF_HZ / SAMPLE_RATE);
	private static final double LFE_GAIN = 0.5;

	private double lfeState;

	public float[] upmix(float[] stereo, int frames) {
		float[] output = new float[frames * CinemaAudioEngine.CHANNELS];
		for (int frame = 0; frame < frames; frame++) {
			float left = stereo[frame * 2];
			float right = stereo[frame * 2 + 1];
			float center = (left + right) * 0.5f;
			float backLeft = (left - right) * 0.5f;
			float backRight = (right - left) * 0.5f;

			double mid = (left + right) * 0.5;
			lfeState += LFE_ALPHA * (mid - lfeState);
			float lfe = (float) (lfeState * LFE_GAIN);

			int base = frame * CinemaAudioEngine.CHANNELS;
			output[base] = left;
			output[base + 1] = right;
			output[base + 2] = center;
			output[base + 3] = lfe;
			output[base + 4] = backLeft;
			output[base + 5] = backRight;
		}
		return output;
	}
}
