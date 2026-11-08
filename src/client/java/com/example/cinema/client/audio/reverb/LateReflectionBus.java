package com.example.cinema.client.audio.reverb;

import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.ReflectionPathTap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Six-channel geometric late-reflection bus.
 *
 * <p>This is the renderer prototype for the higher-order image-source path
 * spike. Each tap reads the raw source channel from a shared history ring,
 * applies its own delay, gain and one-pole damping, and accumulates into the
 * matching output channel. It deliberately does not allocate OpenAL sources or
 * per-tap FIRs: the caller gets six wet buses that can be played through the
 * existing lazy wet-source pool.
 *
 * <p>The class is not audio-thread safe for concurrent {@link #setTaps(List)}
 * and {@link #process}; the intended use is to publish a new immutable tap
 * list and swap it on the audio thread, as with the existing late-reverb
 * settings.
 */
public final class LateReflectionBus {
	public static final int CHANNELS = 6;
	public static final double DEFAULT_DAMPING_HZ = 7_000.0;
	private static final double MIN_DAMPING_HZ = 200.0;
	private static final double MAX_DAMPING_FRACTION = 0.49;

	private final int sampleRate;
	private final int historyFrames;
	private final int historyMask;
	private final int maxTaps;
	private final int fadeInFrames;
	private final float[][] history = new float[CHANNELS][];

	private int writeIndex;
	private int fadeInRemaining;
	private int tapCount;
	private int[] tapChannels = new int[0];
	private double[] tapDelays = new double[0];
	private double[] tapGains = new double[0];
	private double[] tapAlphas = new double[0];
	private double[] tapStates = new double[0];
	private float outputGain = 1.0f;

	public LateReflectionBus(int sampleRate, int historyFrames, int maxTaps) {
		this(sampleRate, historyFrames, maxTaps, 0.0);
	}

	public LateReflectionBus(int sampleRate, int historyFrames, int maxTaps,
							 double fadeInSeconds) {
		if (sampleRate < 8_000) {
			throw new IllegalArgumentException("sample rate too low");
		}
		if (historyFrames < 2
			|| (historyFrames & (historyFrames - 1)) != 0) {
			throw new IllegalArgumentException("history frames must be a power of two");
		}
		if (maxTaps <= 0) {
			throw new IllegalArgumentException("max taps must be positive");
		}
		this.sampleRate = sampleRate;
		this.historyFrames = historyFrames;
		this.historyMask = historyFrames - 1;
		this.maxTaps = maxTaps;
		this.fadeInFrames = Double.isFinite(fadeInSeconds)
			? Math.max(0, (int) Math.round(fadeInSeconds * sampleRate)) : 0;
		for (int channel = 0; channel < CHANNELS; channel++) {
			history[channel] = new float[historyFrames];
		}
	}

	/** One rendered tap. Input and output channel are the same 5.1 channel. */
	public record Tap(int channel, double delaySeconds, double gain,
					  double dampingHz) {
		public Tap {
			channel = Math.max(0, Math.min(CHANNELS - 1, channel));
			delaySeconds = Double.isFinite(delaySeconds)
				? Math.max(0.0, delaySeconds) : 0.0;
			gain = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
			dampingHz = Double.isFinite(dampingHz) && dampingHz > 0.0
				? dampingHz : DEFAULT_DAMPING_HZ;
		}

		public static Tap fromPath(ReflectionPathTap path) {
			return fromPath(path, 1.0);
		}

		public static Tap fromPath(ReflectionPathTap path, double gainMultiplier) {
			if (path == null) {
				return new Tap(0, 0.0, 0.0, DEFAULT_DAMPING_HZ);
			}
			double high = 0.0;
			int count = path.materials().size();
			for (AcousticMaterial material : path.materials()) {
				high += material.high();
			}
			high = count == 0 ? 0.0 : high / count;
			double cutoff = 12_000.0 - 6_000.0 * high
				- 150.0 * path.pathLength();
			double multiplier = Double.isFinite(gainMultiplier)
				? Math.max(0.0, gainMultiplier) : 0.0;
			return new Tap(path.channel(), path.delaySeconds(),
				path.gain() * multiplier, Math.max(2_000.0, cutoff));
		}
	}

	/** Keeps the strongest taps when the list exceeds the bus capacity. */
	public void setTaps(List<Tap> taps) {
		List<Tap> valid = new ArrayList<>();
		if (taps != null) {
			double maxDelay = historyFrames / (double) sampleRate;
			for (Tap tap : taps) {
				if (tap != null && tap.gain() > 0.0
					&& tap.delaySeconds() < maxDelay) {
					valid.add(tap);
				}
			}
		}
		valid.sort(Comparator.comparingDouble(Tap::gain).reversed());
		int count = Math.min(maxTaps, valid.size());
		ensureCapacity(count);
		tapCount = count;
		for (int i = 0; i < count; i++) {
			Tap tap = valid.get(i);
			tapChannels[i] = tap.channel();
			tapDelays[i] = tap.delaySeconds() * sampleRate;
			tapGains[i] = tap.gain();
			tapAlphas[i] = dampingAlpha(tap.dampingHz());
			tapStates[i] = 0.0;
		}
		fadeInRemaining = count > 0 ? fadeInFrames : 0;
	}

	public void setOutputGain(float gain) {
		outputGain = Float.isFinite(gain) ? Math.max(0.0f, gain) : 0.0f;
	}

	public int tapCount() {
		return tapCount;
	}

	public int historyFrames() {
		return historyFrames;
	}

	/**
	 * Renders one block. Input and output must both contain
	 * {@link #CHANNELS} rows of at least {@code frames} samples.
	 */
	public void process(float[][] input, int frames, float[][] outputs) {
		if (frames <= 0) {
			return;
		}
		if (frames > historyFrames) {
			throw new IllegalArgumentException("block longer than history");
		}
		validateBuffers(input, frames, "input");
		validateBuffers(outputs, frames, "output");
		for (int channel = 0; channel < CHANNELS; channel++) {
			Arrays.fill(outputs[channel], 0, frames, 0.0f);
		}

		int start = writeIndex;
		for (int channel = 0; channel < CHANNELS; channel++) {
			float[] source = input[channel];
			float[] ring = history[channel];
			for (int i = 0; i < frames; i++) {
				ring[(start + i) & historyMask] = source[i];
			}
		}

		for (int tap = 0; tap < tapCount; tap++) {
			int channel = tapChannels[tap];
			float[] ring = history[channel];
			float[] output = outputs[channel];
			double delay = tapDelays[tap];
			int delayInteger = (int) Math.floor(delay);
			double delayFraction = delay - delayInteger;
			double alpha = tapAlphas[tap];
			double gain = tapGains[tap] * outputGain;
			double state = tapStates[tap];
			for (int i = 0; i < frames; i++) {
				int index = (start + i - delayInteger) & historyMask;
				double sample;
				if (delayFraction <= 1.0e-9) {
					sample = ring[index];
				} else {
					int previous = (index - 1) & historyMask;
					sample = ring[index] * (1.0 - delayFraction)
						+ ring[previous] * delayFraction;
				}
				state += alpha * (sample - state);
				output[i] += (float) (gain * state);
			}
			tapStates[tap] = state;
		}
		applyFadeIn(outputs, frames);
		writeIndex = (start + frames) & historyMask;
	}

	/** Clears the raw history and all per-tap filter states. */
	public void clear() {
		for (float[] ring : history) {
			Arrays.fill(ring, 0.0f);
		}
		Arrays.fill(tapStates, 0.0);
		fadeInRemaining = 0;
		writeIndex = 0;
	}

	private void applyFadeIn(float[][] outputs, int frames) {
		if (fadeInRemaining <= 0) {
			return;
		}
		int total = Math.max(1, fadeInFrames);
		for (int i = 0; i < frames; i++) {
			double remaining = Math.max(0, fadeInRemaining - i);
			float gain = (float) (1.0 - remaining / total);
			for (int channel = 0; channel < CHANNELS; channel++) {
				outputs[channel][i] *= gain;
			}
		}
		fadeInRemaining = Math.max(0, fadeInRemaining - frames);
	}

	private void ensureCapacity(int count) {
		if (tapChannels.length >= count) {
			return;
		}
		tapChannels = new int[count];
		tapDelays = new double[count];
		tapGains = new double[count];
		tapAlphas = new double[count];
		tapStates = new double[count];
	}

	private double dampingAlpha(double dampingHz) {
		double maxHz = sampleRate * MAX_DAMPING_FRACTION;
		double cutoff = Math.max(MIN_DAMPING_HZ,
			Math.min(maxHz, dampingHz));
		if (cutoff >= maxHz) {
			return 1.0;
		}
		return 1.0 - Math.exp(-2.0 * Math.PI * cutoff / sampleRate);
	}

	private static void validateBuffers(float[][] buffers, int frames,
										String name) {
		if (buffers == null || buffers.length < CHANNELS) {
			throw new IllegalArgumentException(name + " needs six channels");
		}
		for (int channel = 0; channel < CHANNELS; channel++) {
			if (buffers[channel] == null || buffers[channel].length < frames) {
				throw new IllegalArgumentException(name + " buffer too short");
			}
		}
	}
}
