package com.example.cinema.client.media.audio.dsp;

/**
 * Deterministic 5.1-to-stereo downmix.
 *
 * <p>Channel order follows the decoder layout: FL, FR, FC, LFE, BL, BR.
 * The center and surround channels use the usual -3 dB amplitude factor
 * ({@code sqrt(0.5)}). LFE is dropped by default because it is normally
 * reproduced by bass management rather than folded into the main channels.
 *
 * <p>This is a pure function: it has no state, does not clamp, and does not
 * change the channel order. The caller decides whether to apply headroom
 * attenuation; the receiver's output limiter still runs after this stage.
 */
public final class SurroundDownmix {
	public static final int SURROUND_CHANNELS = 6;
	public static final int STEREO_CHANNELS = 2;
	public static final double CENTER_GAIN = Math.sqrt(0.5);
	public static final double SURROUND_GAIN = Math.sqrt(0.5);
	public static final double LFE_GAIN = 0.0;

	private SurroundDownmix() {
	}

	public static float[] toStereo(float[] surround, int frames) {
		return toStereo(surround, frames, CENTER_GAIN, SURROUND_GAIN, LFE_GAIN);
	}

	/**
	 * Downmixes {@code frames} interleaved 5.1 frames to interleaved stereo.
	 *
	 * @param surround   interleaved FL, FR, FC, LFE, BL, BR samples
	 * @param frames     number of 5.1 frames to read
	 * @param centerGain linear gain applied to FC
	 * @param surroundGain linear gain applied to BL/BR
	 * @param lfeGain    linear gain applied to LFE (0 drops it)
	 */
	public static float[] toStereo(float[] surround, int frames,
								   double centerGain, double surroundGain,
								   double lfeGain) {
		if (surround == null) {
			throw new IllegalArgumentException("surround samples must not be null");
		}
		if (frames < 0) {
			throw new IllegalArgumentException("frames must be >= 0");
		}
		if (frames > Integer.MAX_VALUE / SURROUND_CHANNELS) {
			throw new IllegalArgumentException("too many frames: " + frames);
		}
		if (surround.length < frames * SURROUND_CHANNELS) {
			throw new IllegalArgumentException("expected " + frames * SURROUND_CHANNELS
				+ " samples, got " + surround.length);
		}
		if (!isValidGain(centerGain) || !isValidGain(surroundGain)
			|| !isValidGain(lfeGain)) {
			throw new IllegalArgumentException("downmix gains must be finite and >= 0");
		}
		float[] stereo = new float[frames * STEREO_CHANNELS];
		for (int frame = 0; frame < frames; frame++) {
			int source = frame * SURROUND_CHANNELS;
			double left = surround[source];
			double right = surround[source + 1];
			double center = surround[source + 2];
			double lfe = surround[source + 3];
			double backLeft = surround[source + 4];
			double backRight = surround[source + 5];
			double lfeContribution = lfeGain * lfe;
			stereo[frame * STEREO_CHANNELS] = (float) (left
				+ centerGain * center + surroundGain * backLeft + lfeContribution);
			stereo[frame * STEREO_CHANNELS + 1] = (float) (right
				+ centerGain * center + surroundGain * backRight + lfeContribution);
		}
		return stereo;
	}

	private static boolean isValidGain(double gain) {
		return Double.isFinite(gain) && gain >= 0.0;
	}
}
