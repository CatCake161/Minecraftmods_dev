package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionPathTap;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Six-channel geometric late-reflection bus.
 *
 * <p>This is the renderer prototype for the higher-order image-source path
 * spike. Each tap reads the raw source channel from a shared history ring,
 * applies its own delay, gain and one-pole damping, and accumulates into one or
 * more output channels with energy-normalized VBAP weights (with
 * 3D/horizontal/nearest/diffuse fallbacks). It deliberately does not allocate
 * OpenAL sources or per-tap FIRs: the caller gets six wet buses that can be
 * played through the existing lazy wet-source pool.
 *
 * <p>The class is not audio-thread safe for concurrent {@link #setTaps(List)}
 * and {@link #process}; the intended use is to publish a new immutable tap
 * list and swap it on the audio thread, as with the late-reflection
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
	private int[] tapOutputStarts = new int[0];
	private int[] tapOutputCounts = new int[0];
	private int[] tapOutputChannels = new int[0];
	private float[] tapOutputWeights = new float[0];
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

	/**
	 * One rendered tap. {@code channel} is the raw 5.1 source channel;
	 * {@code direction} is the listener-to-last-reflection-point unit vector
	 * (x right, y up, z forward negative); {@code outputChannels}/
	 * {@code outputWeights} carry the energy-normalized VBAP mapping for the
	 * physical speaker layout. Empty or invalid output arrays fall back to the
	 * input channel with weight 1.0.
	 */
	public record Tap(int channel, double delaySeconds, double gain,
					  double dampingHz, float[] direction,
					  int[] outputChannels, float[] outputWeights) {
		private static final float MIN_WEIGHT = 1.0e-6f;

		public Tap {
			channel = Math.max(0, Math.min(CHANNELS - 1, channel));
			delaySeconds = Double.isFinite(delaySeconds)
				? Math.max(0.0, delaySeconds) : 0.0;
			gain = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
			dampingHz = Double.isFinite(dampingHz) && dampingHz > 0.0
				? dampingHz : DEFAULT_DAMPING_HZ;
			direction = normalizeDirection(direction);
			int[] channels = new int[CHANNELS];
			float[] weights = new float[CHANNELS];
			int count = 0;
			if (outputChannels != null && outputWeights != null) {
				int length = Math.min(outputChannels.length,
					outputWeights.length);
				for (int i = 0; i < length; i++) {
					int output = outputChannels[i];
					float weight = outputWeights[i];
					if (output < 0 || output >= CHANNELS
						|| !Float.isFinite(weight) || weight <= MIN_WEIGHT) {
						continue;
					}
					int existing = -1;
					for (int j = 0; j < count; j++) {
						if (channels[j] == output) {
							existing = j;
							break;
						}
					}
					if (existing >= 0) {
						weights[existing] += weight;
					} else {
						channels[count] = output;
						weights[count] = weight;
						count++;
					}
				}
			}
			if (count == 0) {
				channels[0] = channel;
				weights[0] = 1.0f;
				count = 1;
			}
			double sum = 0.0;
			for (int i = 0; i < count; i++) {
				sum += weights[i] * weights[i];
			}
			float norm = sum > 0.0 ? (float) Math.sqrt(sum) : 1.0f;
			outputChannels = Arrays.copyOf(channels, count);
			outputWeights = Arrays.copyOf(weights, count);
			for (int i = 0; i < count; i++) {
				outputWeights[i] /= norm;
			}
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz) {
			this(channel, delaySeconds, gain, dampingHz,
				new float[]{0.0f, 0.0f, 0.0f},
				new int[]{channel}, new float[]{1.0f});
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz, int[] outputChannels,
				   float[] outputWeights) {
			this(channel, delaySeconds, gain, dampingHz,
				new float[]{0.0f, 0.0f, 0.0f},
				outputChannels, outputWeights);
		}

		public static Tap fromPath(ReflectionPathTap path) {
			return fromPath(path, 1.0);
		}

		public static Tap fromPath(ReflectionPathTap path, double gainMultiplier) {
			return fromPath(path, gainMultiplier, null, null, null);
		}

		public static Tap fromPath(ReflectionPathTap path, double gainMultiplier,
								   int[] outputChannels, float[] outputWeights) {
			return fromPath(path, gainMultiplier, null, outputChannels,
				outputWeights);
		}

		public static Tap fromPath(ReflectionPathTap path, double gainMultiplier,
								   float[] direction, int[] outputChannels,
								   float[] outputWeights) {
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
				- 150.0 * AcousticScale.blocksToMeters(path.pathLength());
			double multiplier = Double.isFinite(gainMultiplier)
				? Math.max(0.0, gainMultiplier) : 0.0;
			return new Tap(path.channel(), path.delaySeconds(),
				path.gain() * multiplier, Math.max(2_000.0, cutoff),
				direction, outputChannels, outputWeights);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Tap other)) {
				return false;
			}
			return channel == other.channel
				&& Double.compare(delaySeconds, other.delaySeconds) == 0
				&& Double.compare(gain, other.gain) == 0
				&& Double.compare(dampingHz, other.dampingHz) == 0
				&& Arrays.equals(direction, other.direction)
				&& Arrays.equals(outputChannels, other.outputChannels)
				&& Arrays.equals(outputWeights, other.outputWeights);
		}

		@Override
		public int hashCode() {
			int result = Integer.hashCode(channel);
			result = 31 * result + Double.hashCode(delaySeconds);
			result = 31 * result + Double.hashCode(gain);
			result = 31 * result + Double.hashCode(dampingHz);
			result = 31 * result + Arrays.hashCode(direction);
			result = 31 * result + Arrays.hashCode(outputChannels);
			result = 31 * result + Arrays.hashCode(outputWeights);
			return result;
		}

		private static float[] normalizeDirection(float[] direction) {
			if (direction == null || direction.length < 3) {
				return new float[3];
			}
			double x = direction[0];
			double y = direction[1];
			double z = direction[2];
			double length = Math.sqrt(x * x + y * y + z * z);
			if (!Double.isFinite(length) || length < MIN_WEIGHT) {
				return new float[3];
			}
			return new float[]{(float) (x / length), (float) (y / length),
				(float) (z / length)};
		}
	}

	/** Keeps the strongest taps when the list exceeds the bus capacity. */
	public void setTaps(List<Tap> taps) {
		setTaps(taps, historyFrames / (double) sampleRate);
	}

	/**
	 * Keeps the strongest taps whose delay is inside {@code maxDelaySeconds}.
	 * The playback layer supplies the room/order budget; the history capacity
	 * is still the hard upper bound.
	 */
	public void setTaps(List<Tap> taps, double maxDelaySeconds) {
		double historyLimit = historyFrames / (double) sampleRate;
		double limit = Double.isFinite(maxDelaySeconds)
			? Math.max(0.0, Math.min(maxDelaySeconds, historyLimit))
			: historyLimit;
		List<Tap> valid = new ArrayList<>();
		if (taps != null) {
			for (Tap tap : taps) {
				if (tap != null && tap.gain() > 0.0
					&& tap.delaySeconds() < limit) {
					valid.add(tap);
				}
			}
		}
		valid.sort(Comparator.comparingDouble(Tap::gain).reversed());
		int count = Math.min(maxTaps, valid.size());
		int outputCount = 0;
		for (int i = 0; i < count; i++) {
			outputCount += valid.get(i).outputChannels().length;
		}
		ensureCapacity(count, outputCount);
		tapCount = count;
		int outputIndex = 0;
		for (int i = 0; i < count; i++) {
			Tap tap = valid.get(i);
			tapChannels[i] = tap.channel();
			tapDelays[i] = tap.delaySeconds() * sampleRate;
			tapGains[i] = tap.gain();
			tapAlphas[i] = dampingAlpha(tap.dampingHz());
			tapStates[i] = 0.0;
			int[] channels = tap.outputChannels();
			float[] weights = tap.outputWeights();
			tapOutputStarts[i] = outputIndex;
			tapOutputCounts[i] = channels.length;
			for (int j = 0; j < channels.length; j++) {
				tapOutputChannels[outputIndex] = channels[j];
				tapOutputWeights[outputIndex] = weights[j];
				outputIndex++;
			}
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
			double delay = tapDelays[tap];
			int delayInteger = (int) Math.floor(delay);
			double delayFraction = delay - delayInteger;
			double alpha = tapAlphas[tap];
			double gain = tapGains[tap] * outputGain;
			double state = tapStates[tap];
			int outputStart = tapOutputStarts[tap];
			int outputCount = tapOutputCounts[tap];
			if (outputCount == 1) {
				float[] output = outputs[tapOutputChannels[outputStart]];
				float weight = tapOutputWeights[outputStart];
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
					output[i] += (float) (gain * state) * weight;
				}
			} else {
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
					float value = (float) (gain * state);
					for (int output = 0; output < outputCount; output++) {
						int indexOut = outputStart + output;
						outputs[tapOutputChannels[indexOut]][i] +=
							value * tapOutputWeights[indexOut];
					}
				}
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

	private void ensureCapacity(int count, int outputs) {
		if (tapChannels.length < count) {
			tapChannels = new int[count];
			tapDelays = new double[count];
			tapGains = new double[count];
			tapAlphas = new double[count];
			tapStates = new double[count];
		}
		if (tapOutputStarts.length < count) {
			tapOutputStarts = new int[count];
			tapOutputCounts = new int[count];
		}
		if (tapOutputChannels.length < outputs) {
			int capacity = Math.max(outputs,
				Math.max(CHANNELS, tapOutputChannels.length * 2));
			tapOutputChannels = Arrays.copyOf(tapOutputChannels, capacity);
			tapOutputWeights = Arrays.copyOf(tapOutputWeights, capacity);
		}
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
