package com.example.cinema.client.audio.filter;

/**
 * Stateful biquad cascade for one audio path.
 *
 * <p>Coefficient changes crossfade between the old and new chains over 30 ms.
 * Both chains process the same input block; the old chain's output is faded
 * out while the new chain's output is faded in. A new request that arrives
 * during a fade waits until the current fade finishes, so coefficient updates
 * can never produce an unbounded chain of simultaneous filters.
 */
public final class BiquadProcessor {
	/** 30 ms at 48 kHz. */
	private static final int CROSSFADE_FRAMES = 1_440;

	private ChainState current = new ChainState(BiquadChain.FLAT);
	private ChainState fading;
	private BiquadChain pending;
	private int fadeFrames;
	private int fadeLength;
	private float[] scratch = new float[2_048];

	public void setChain(BiquadChain chain) {
		BiquadChain next = chain == null ? BiquadChain.FLAT : chain;
		if (next.equals(current.chain)) {
			pending = null;
			return;
		}
		pending = next;
	}

	public void reset() {
		current = new ChainState(BiquadChain.FLAT);
		fading = null;
		pending = null;
		fadeFrames = 0;
		fadeLength = 0;
	}

	/**
	 * Processes {@code frames} samples from {@code input} to {@code output}.
	 * Both arrays may be the same buffer. {@code stride} is in floats.
	 */
	public void process(float[] input, int stride, int offset, int frames,
						float[] output) {
		if (frames <= 0) {
			return;
		}
		if (pending != null && fading == null) {
			startFade(pending);
			pending = null;
		}
		if (fading == null) {
			current.process(input, stride, offset, frames, output);
			return;
		}
		ensureScratch(frames);
		for (int i = 0; i < frames; i++) {
			scratch[i] = input[offset + i * stride];
		}
		current.process(input, stride, offset, frames, output);
		fading.process(scratch, 1, 0, frames, scratch);
		int remaining = fadeFrames;
		for (int i = 0; i < frames; i++) {
			float mix = fadeLength <= 0 ? 1.0f
				: 1.0f - Math.max(0, remaining) / (float) fadeLength;
			int index = offset + i * stride;
			output[index] = scratch[i] * (1.0f - mix) + output[index] * mix;
			if (remaining > 0) {
				remaining--;
			}
		}
		fadeFrames = remaining;
		if (fadeFrames <= 0) {
			fading = null;
			if (pending != null) {
				startFade(pending);
				pending = null;
			}
		}
	}

	private void startFade(BiquadChain next) {
		fading = current;
		current = new ChainState(next);
		fadeLength = CROSSFADE_FRAMES;
		fadeFrames = fadeLength;
	}

	private void ensureScratch(int frames) {
		if (scratch.length < frames) {
			scratch = new float[frames];
		}
	}

	private static final class ChainState {
		private final BiquadChain chain;
		private final double[] z1;
		private final double[] z2;

		private ChainState(BiquadChain chain) {
			this.chain = chain;
			int count = chain.sectionCount();
			z1 = new double[count];
			z2 = new double[count];
		}

		private void process(float[] input, int stride, int offset, int frames,
							 float[] output) {
			Biquad[] sections = chain.sections();
			if (sections.length == 0) {
				for (int i = 0; i < frames; i++) {
					int index = offset + i * stride;
					output[index] = input[index];
				}
				return;
			}
			for (int i = 0; i < frames; i++) {
				int index = offset + i * stride;
				double value = input[index];
				for (int section = 0; section < sections.length; section++) {
					Biquad biquad = sections[section];
					double filtered = biquad.b0() * value + z1[section];
					z1[section] = biquad.b1() * value
						- biquad.a1() * filtered + z2[section];
					z2[section] = biquad.b2() * value
						- biquad.a2() * filtered;
					value = filtered;
				}
				output[index] = (float) value;
			}
		}
	}
}
