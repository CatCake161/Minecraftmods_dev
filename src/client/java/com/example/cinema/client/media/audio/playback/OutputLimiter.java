package com.example.cinema.client.media.audio.playback;

/**
 * Feed-forward output limiter shared by the direct, reflection and wet source
 * buses of one {@link CinemaAudioEngine}.
 *
 * <p>The engine predicts the worst-case mixed peak of every output channel from
 * the queued blocks (the OpenAL queue provides roughly 170 ms of look-ahead)
 * and scales every source {@code AL_GAIN} by {@link #gainFor(float)}. The
 * ceiling therefore applies to the digital mix that OpenAL sends to the
 * device, which is the last point the mod controls before the listener. It is
 * not a measured SPL: distance, air absorption, speaker sensitivity, room gain
 * and the listener's own volume control remain part of the acoustic model.
 *
 * <p>The limiter has an instant attack and an exponential release. A gain
 * reduction can be applied immediately because the affected block is still
 * queued; recovery is slowed down so quiet passages do not pump.
 */
public final class OutputLimiter {
	/** Output ceiling in dBFS. */
	public static final float CEILING_DB = -0.3f;
	/** Linear ceiling used for the predicted mix peak. */
	public static final float CEILING_LINEAR =
		(float) Math.pow(10.0, CEILING_DB / 20.0);
	/** Time constant for recovering gain after a peak. */
	public static final double RELEASE_SECONDS = 0.4;
	/** Never attenuate more than 60 dB; below this the output is effectively off. */
	private static final float MIN_GAIN = 0.001f;

	private OutputLimiter() {
	}

	/**
	 * Returns the gain needed to keep a predicted worst-case mix peak below
	 * {@link #CEILING_LINEAR}. Values at or below the ceiling pass through
	 * untouched.
	 */
	public static float gainFor(float predictedPeak) {
		if (!(predictedPeak > CEILING_LINEAR)) {
			return 1.0f;
		}
		return Math.max(MIN_GAIN, CEILING_LINEAR / predictedPeak);
	}

	/**
	 * Advances the limiter gain. A lower target is applied immediately; a
	 * higher target is approached with {@link #RELEASE_SECONDS}.
	 */
	public static float advance(float current, float target, double dt) {
		if (!Float.isFinite(current) || current <= 0.0f) {
			current = 1.0f;
		}
		if (!Float.isFinite(target) || target <= 0.0f) {
			target = 1.0f;
		}
		target = Math.min(1.0f, target);
		if (target < current) {
			return target;
		}
		double alpha = 1.0 - Math.exp(-Math.max(0.0, dt) / RELEASE_SECONDS);
		float next = current + (target - current) * (float) alpha;
		return Math.min(target, next);
	}

	/** Positive gain reduction in dB, or {@code 0} when the limiter is idle. */
	public static float reductionDb(float gain) {
		if (!(gain > 0.0f) || gain >= 0.9999f) {
			return 0.0f;
		}
		return (float) (-20.0 * Math.log10(gain));
	}

	/**
	 * Peak magnitude of one mono block. When the OpenAL upload path is 16-bit,
	 * samples above unity are already clipped by the uploader, so the peak is
	 * clamped to match what actually reaches the mixer.
	 */
	public static float blockPeak(float[] samples, int frames, boolean clipToUnity) {
		float peak = 0.0f;
		int count = Math.min(frames, samples.length);
		for (int i = 0; i < count; i++) {
			float value = samples[i];
			if (!Float.isFinite(value)) {
				continue;
			}
			float magnitude = Math.abs(value);
			if (clipToUnity && magnitude > 1.0f) {
				magnitude = 1.0f;
			}
			if (magnitude > peak) {
				peak = magnitude;
			}
		}
		return peak;
	}
}
