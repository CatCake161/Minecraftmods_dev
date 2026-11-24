package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acousticfir.MaterialReflectionFilter;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionPathTap;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.dsp.Biquad;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
	/**
	 * Maximum wet output points: physical speakers plus dynamic reflection
	 * ghost speakers. The input history is still the six 5.1 source channels.
	 */
	public static final int MAX_OUTPUT_CHANNELS = 24;
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
	private int tapCount;
	private String[] tapKeys = new String[0];
	private int[] tapChannels = new int[0];
	private double[] tapDelays = new double[0];
	private double[] tapGains = new double[0];
	private double[] tapAlphas = new double[0];
	private double[] tapStates = new double[0];
	private MaterialReflectionFilter[] tapFilters =
		new MaterialReflectionFilter[0];
	private double[] tapFilterStates = new double[0];
	private float[] tapFadeGains = new float[0];
	private float[] tapFadeTargets = new float[0];
	private int[] tapOutputStarts = new int[0];
	private int[] tapOutputCounts = new int[0];
	private int[] tapOutputChannels = new int[0];
	private float[] tapOutputWeights = new float[0];
	private int outputCount = CHANNELS;
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
	 * {@code direction} is the world-space listener-to-virtual-source unit
	 * vector, so a head turn never rewrites the tap list (VBAP converts it
	 * through the current listener frame); {@code outputChannels}/
	 * {@code outputWeights} carry the energy-normalized VBAP mapping for the
	 * dynamic wet layout. Empty or invalid output arrays fall back to the
	 * input channel with weight 1.0.
	 */
	public record Tap(int channel, double delaySeconds, double gain,
					  double dampingHz, float[] direction,
					  int[] outputChannels, float[] outputWeights,
					  String key, MaterialReflectionFilter materialFilter,
					  int order) {
		private static final float MIN_WEIGHT = 1.0e-6f;

		public Tap {
			key = key == null ? "" : key;
			materialFilter = materialFilter == null
				? MaterialReflectionFilter.FLAT : materialFilter;
			channel = Math.max(0, Math.min(CHANNELS - 1, channel));
			order = Math.max(0, Math.min(5, order));
			delaySeconds = Double.isFinite(delaySeconds)
				? Math.max(0.0, delaySeconds) : 0.0;
			gain = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
			dampingHz = Double.isFinite(dampingHz) && dampingHz > 0.0
				? dampingHz : DEFAULT_DAMPING_HZ;
			direction = normalizeDirection(direction);
			int[] channels = new int[MAX_OUTPUT_CHANNELS];
			float[] weights = new float[MAX_OUTPUT_CHANNELS];
			int count = 0;
			if (outputChannels != null && outputWeights != null) {
				int length = Math.min(outputChannels.length,
					outputWeights.length);
				for (int i = 0; i < length; i++) {
					int output = outputChannels[i];
					float weight = outputWeights[i];
					if (output < 0 || output >= MAX_OUTPUT_CHANNELS
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
				   double dampingHz, float[] direction, int[] outputChannels,
				   float[] outputWeights) {
			this(channel, delaySeconds, gain, dampingHz, direction,
				outputChannels, outputWeights, "",
				MaterialReflectionFilter.FLAT, 0);
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz) {
			this(channel, delaySeconds, gain, dampingHz,
				new float[]{0.0f, 0.0f, 0.0f},
				new int[]{channel}, new float[]{1.0f}, "",
				MaterialReflectionFilter.FLAT, 0);
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz, int[] outputChannels,
				   float[] outputWeights) {
			this(channel, delaySeconds, gain, dampingHz,
				new float[]{0.0f, 0.0f, 0.0f},
				outputChannels, outputWeights, "",
				MaterialReflectionFilter.FLAT, 0);
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz, float[] direction, int[] outputChannels,
				   float[] outputWeights, String key) {
			this(channel, delaySeconds, gain, dampingHz, direction,
				outputChannels, outputWeights, key,
				MaterialReflectionFilter.FLAT, 0);
		}

		public Tap(int channel, double delaySeconds, double gain,
				   double dampingHz, float[] direction, int[] outputChannels,
				   float[] outputWeights, String key,
				   MaterialReflectionFilter materialFilter) {
			this(channel, delaySeconds, gain, dampingHz, direction,
				outputChannels, outputWeights, key, materialFilter, 0);
		}

		public Tap withOutput(int[] channels, float[] weights) {
			return new Tap(channel, delaySeconds, gain, dampingHz, direction,
				channels, weights, key, materialFilter, order);
		}

		public Tap withDirection(float[] newDirection) {
			return new Tap(channel, delaySeconds, gain, dampingHz,
				newDirection, outputChannels, outputWeights, key,
				materialFilter, order);
		}

		public Tap withOrder(int value) {
			return new Tap(channel, delaySeconds, gain, dampingHz, direction,
				outputChannels, outputWeights, key, materialFilter, value);
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
			return fromPath(path, gainMultiplier, direction, outputChannels,
				outputWeights, false, MaterialReflectionFilter.DEFAULT_SAMPLE_RATE);
		}

		/**
		 * Builds a lazy tap. With {@code materialEq} the one-pole keeps only
		 * the distance term and the three-band material PEQ is attached; with
		 * {@code materialEq=false} the pre-A3-D1 damping is preserved exactly.
		 */
		public static Tap fromPath(ReflectionPathTap path, double gainMultiplier,
								   float[] direction, int[] outputChannels,
								   float[] outputWeights, boolean materialEq,
								   double sampleRate) {
			if (path == null) {
				return new Tap(0, 0.0, 0.0, DEFAULT_DAMPING_HZ);
			}
			double high = 0.0;
			int count = path.materials().size();
			for (AcousticMaterial material : path.materials()) {
				high += material.high();
			}
			high = count == 0 ? 0.0 : high / count;
			double distanceMeters = AcousticScale.blocksToMeters(path.pathLength());
			double cutoff = materialEq
				? 12_000.0 - 150.0 * distanceMeters
				: 12_000.0 - 6_000.0 * high - 150.0 * distanceMeters;
			MaterialReflectionFilter filter = materialEq
				? MaterialReflectionFilter.of(path.specularMaterial(), sampleRate)
				: MaterialReflectionFilter.FLAT;
			double multiplier = Double.isFinite(gainMultiplier)
				? Math.max(0.0, gainMultiplier) : 0.0;
			return new Tap(path.channel(), path.delaySeconds(),
				path.gain() * multiplier, Math.max(2_000.0, cutoff),
				direction, outputChannels, outputWeights,
				path.sourcePathKey(), filter, path.order());
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
				&& order == other.order
				&& key.equals(other.key)
				&& Objects.equals(materialFilter, other.materialFilter)
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
			result = 31 * result + Integer.hashCode(order);
			result = 31 * result + key.hashCode();
			result = 31 * result + Objects.hashCode(materialFilter);
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
		setTaps(taps, maxDelaySeconds, 0.0);
	}

	/**
	 * Keeps taps inside both the delay cap and the gain floor. The floor is an
	 * amplitude in the same domain as {@link Tap#gain()} and normally comes
	 * from the listener-relative direct level. A non-finite or non-positive
	 * floor disables gain filtering except for the existing {@code gain > 0}
	 * validity check.
	 */
	public void setTaps(List<Tap> taps, double maxDelaySeconds, double minGain) {
		double historyLimit = historyFrames / (double) sampleRate;
		double limit = Double.isFinite(maxDelaySeconds)
			? Math.max(0.0, Math.min(maxDelaySeconds, historyLimit))
			: historyLimit;
		double floor = Double.isNaN(minGain) ? 0.0 : Math.max(0.0, minGain);
		List<Tap> valid = new ArrayList<>();
		if (taps != null) {
			for (Tap tap : taps) {
				if (tap != null && tap.gain() > 0.0 && tap.gain() >= floor
					&& tap.delaySeconds() < limit) {
					valid.add(tap);
				}
			}
		}
		valid.sort(Comparator.comparingDouble(Tap::gain).reversed());
		int newCount = Math.min(maxTaps, valid.size());

		// Keep the previous generation around long enough to fade out removed
		// taps. The audio thread only rewrites the live arrays after every old
		// value needed for matching has been copied here.
		int oldCount = tapCount;
		String[] oldKeys = Arrays.copyOf(tapKeys, oldCount);
		int[] oldChannels = Arrays.copyOf(tapChannels, oldCount);
		double[] oldDelays = Arrays.copyOf(tapDelays, oldCount);
		double[] oldGains = Arrays.copyOf(tapGains, oldCount);
		double[] oldAlphas = Arrays.copyOf(tapAlphas, oldCount);
		double[] oldStates = Arrays.copyOf(tapStates, oldCount);
		MaterialReflectionFilter[] oldFilters = Arrays.copyOf(tapFilters, oldCount);
		double[] oldFilterStates = Arrays.copyOf(tapFilterStates, oldCount * 6);
		float[] oldFades = Arrays.copyOf(tapFadeGains, oldCount);
		int[] oldOutputStarts = Arrays.copyOf(tapOutputStarts, oldCount);
		int[] oldOutputCounts = Arrays.copyOf(tapOutputCounts, oldCount);
		int[] oldOutputChannels = Arrays.copyOf(tapOutputChannels,
			tapOutputChannels.length);
		float[] oldOutputWeights = Arrays.copyOf(tapOutputWeights,
			tapOutputWeights.length);

		Set<String> newKeys = new HashSet<>();
		int newOutputCount = 0;
		for (int i = 0; i < newCount; i++) {
			Tap tap = valid.get(i);
			if (!tap.key().isEmpty()) {
				newKeys.add(tap.key());
			}
			newOutputCount += tap.outputChannels().length;
		}
		Map<String, Integer> oldByKey = new HashMap<>();
		for (int i = 0; i < oldCount; i++) {
			if (!oldKeys[i].isEmpty()) {
				oldByKey.putIfAbsent(oldKeys[i], i);
			}
		}
		boolean[] matched = new boolean[oldCount];
		int capacity = maxTaps + Math.min(maxTaps, oldCount);
		int keepBudget = Math.max(0, capacity - newCount);
		int keepCount = 0;
		int oldKeptOutputCount = 0;
		for (int i = 0; i < oldCount; i++) {
			boolean keep = !oldKeys[i].isEmpty()
				&& !newKeys.contains(oldKeys[i]) && oldFades[i] > 0.0f;
			if (keep && keepCount < keepBudget) {
				keepCount++;
				oldKeptOutputCount += oldOutputCounts[i];
			}
		}
		ensureCapacity(newCount + keepCount,
			newOutputCount + oldKeptOutputCount);

		int outputIndex = 0;
		int write = 0;
		for (int i = 0; i < newCount; i++) {
			Tap tap = valid.get(i);
			int old = -1;
			if (!tap.key().isEmpty()) {
				Integer found = oldByKey.get(tap.key());
				if (found != null) {
					old = found;
				}
			} else if (i < oldCount && oldKeys[i].isEmpty()) {
				// Tests and legacy callers without keys: preserve state by
				// position instead of forcing a fade-in on every update.
				old = i;
			}
			double state = old >= 0 ? oldStates[old] : 0.0;
			boolean filterChanged = old >= 0
				&& !Objects.equals(oldFilters[old], tap.materialFilter());
			float fade = old >= 0 ? oldFades[old]
				: (fadeInFrames > 0 ? 0.0f : 1.0f);
			if (filterChanged) {
				fade = 0.0f;
			}
			if (old >= 0) {
				matched[old] = true;
			}
			writeTap(write, tap, state, fade, 1.0f, outputIndex);
			if (old >= 0 && !filterChanged) {
				System.arraycopy(oldFilterStates, old * 6, tapFilterStates,
					write * 6, 6);
			}
			outputIndex += tap.outputChannels().length;
			write++;
		}
		for (int i = 0; i < oldCount && write < newCount + keepCount; i++) {
			if (matched[i] || oldKeys[i].isEmpty()
				|| newKeys.contains(oldKeys[i]) || oldFades[i] <= 0.0f) {
				continue;
			}
			tapKeys[write] = oldKeys[i];
			tapChannels[write] = oldChannels[i];
			tapDelays[write] = oldDelays[i];
			tapGains[write] = oldGains[i];
			tapAlphas[write] = oldAlphas[i];
			tapStates[write] = oldStates[i];
			tapFilters[write] = oldFilters[i];
			System.arraycopy(oldFilterStates, i * 6, tapFilterStates,
				write * 6, 6);
			tapFadeGains[write] = oldFades[i];
			tapFadeTargets[write] = 0.0f;
			tapOutputStarts[write] = outputIndex;
			tapOutputCounts[write] = oldOutputCounts[i];
			int oldStart = oldOutputStarts[i];
			for (int j = 0; j < oldOutputCounts[i]; j++) {
				tapOutputChannels[outputIndex] = oldOutputChannels[oldStart + j];
				tapOutputWeights[outputIndex] = oldOutputWeights[oldStart + j];
				outputIndex++;
			}
			write++;
		}
		tapCount = write;
	}

	private void writeTap(int index, Tap tap, double state, float fade,
						  float fadeTarget, int outputIndex) {
		tapKeys[index] = tap.key();
		tapChannels[index] = tap.channel();
		tapDelays[index] = tap.delaySeconds() * sampleRate;
		tapGains[index] = tap.gain();
		tapAlphas[index] = dampingAlpha(tap.dampingHz());
		tapStates[index] = state;
		tapFilters[index] = tap.materialFilter();
		Arrays.fill(tapFilterStates, index * 6, index * 6 + 6, 0.0);
		tapFadeGains[index] = fade;
		tapFadeTargets[index] = fadeTarget;
		int[] channels = tap.outputChannels();
		float[] weights = tap.outputWeights();
		tapOutputStarts[index] = outputIndex;
		tapOutputCounts[index] = channels.length;
		for (int j = 0; j < channels.length; j++) {
			tapOutputChannels[outputIndex] = channels[j];
			tapOutputWeights[outputIndex] = weights[j];
			outputIndex++;
		}
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
	 * Sets the number of wet output points. The input history stays at
	 * {@link #CHANNELS}; only the output side grows. Callers should publish a
	 * fresh tap list after changing this.
	 */
	public void setOutputCount(int count) {
		outputCount = Math.max(1, Math.min(MAX_OUTPUT_CHANNELS, count));
	}

	public int outputCount() {
		return outputCount;
	}

	/**
	 * Renders one block. Input must contain {@link #CHANNELS} rows of at least
	 * {@code frames} samples; output must contain {@link #outputCount()} rows.
	 */
	public void process(float[][] input, int frames, float[][] outputs) {
		if (frames <= 0) {
			return;
		}
		if (frames > historyFrames) {
			throw new IllegalArgumentException("block longer than history");
		}
		validateInput(input, frames);
		validateOutput(outputs, frames);
		for (int channel = 0; channel < outputCount; channel++) {
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
			float fade = tapFadeGains[tap];
			float fadeTarget = tapFadeTargets[tap];
			float fadeStep = fadeInFrames > 0 ? 1.0f / fadeInFrames : 1.0f;
			int outputStart = tapOutputStarts[tap];
			int tapOutputs = tapOutputCounts[tap];
			MaterialReflectionFilter filter = tapFilters[tap];
			Biquad low = filter == null ? null : filter.low();
			Biquad peak = filter == null ? null : filter.peak();
			Biquad high = filter == null ? null : filter.high();
			int filterStart = tap * 6;
			double low1 = tapFilterStates[filterStart];
			double low2 = tapFilterStates[filterStart + 1];
			double peak1 = tapFilterStates[filterStart + 2];
			double peak2 = tapFilterStates[filterStart + 3];
			double high1 = tapFilterStates[filterStart + 4];
			double high2 = tapFilterStates[filterStart + 5];
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
				double filtered = state;
				if (low != null) {
					double y = low.b0() * filtered + low1;
					low1 = low.b1() * filtered - low.a1() * y + low2;
					low2 = low.b2() * filtered - low.a2() * y;
					filtered = y;
				}
				if (peak != null) {
					double y = peak.b0() * filtered + peak1;
					peak1 = peak.b1() * filtered - peak.a1() * y + peak2;
					peak2 = peak.b2() * filtered - peak.a2() * y;
					filtered = y;
				}
				if (high != null) {
					double y = high.b0() * filtered + high1;
					high1 = high.b1() * filtered - high.a1() * y + high2;
					high2 = high.b2() * filtered - high.a2() * y;
					filtered = y;
				}
				float value = (float) (gain * filtered) * fade;
				for (int output = 0; output < tapOutputs; output++) {
					int indexOut = outputStart + output;
					int outputChannel = tapOutputChannels[indexOut];
					if (outputChannel < 0 || outputChannel >= outputCount) {
						continue;
					}
					outputs[outputChannel][i] +=
						value * tapOutputWeights[indexOut];
				}
				fade = advanceFade(fade, fadeTarget, fadeStep);
			}
			tapStates[tap] = state;
			tapFilterStates[filterStart] = low1;
			tapFilterStates[filterStart + 1] = low2;
			tapFilterStates[filterStart + 2] = peak1;
			tapFilterStates[filterStart + 3] = peak2;
			tapFilterStates[filterStart + 4] = high1;
			tapFilterStates[filterStart + 5] = high2;
			tapFadeGains[tap] = fade;
		}
		writeIndex = (start + frames) & historyMask;
	}

	private static float advanceFade(float fade, float target, float step) {
		if (fade < target) {
			return Math.min(target, fade + step);
		}
		if (fade > target) {
			return Math.max(target, fade - step);
		}
		return fade;
	}

	/** Clears the raw history and all per-tap filter states. */
	public void clear() {
		for (float[] ring : history) {
			Arrays.fill(ring, 0.0f);
		}
		Arrays.fill(tapStates, 0.0);
		Arrays.fill(tapFilterStates, 0.0);
		Arrays.fill(tapFadeGains, 0.0f);
		Arrays.fill(tapFadeTargets, 0.0f);
		writeIndex = 0;
	}

	private void ensureCapacity(int count, int outputs) {
		if (tapChannels.length < count) {
			tapKeys = new String[count];
			tapChannels = new int[count];
			tapDelays = new double[count];
			tapGains = new double[count];
			tapAlphas = new double[count];
			tapStates = new double[count];
			tapFilters = new MaterialReflectionFilter[count];
			tapFilterStates = Arrays.copyOf(tapFilterStates, count * 6);
			tapFadeGains = new float[count];
			tapFadeTargets = new float[count];
		}
		if (tapOutputStarts.length < count) {
			tapOutputStarts = new int[count];
			tapOutputCounts = new int[count];
		}
		if (tapOutputChannels.length < outputs) {
			int capacity = Math.max(outputs,
				Math.max(MAX_OUTPUT_CHANNELS, tapOutputChannels.length * 2));
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

	private static void validateInput(float[][] buffers, int frames) {
		if (buffers == null || buffers.length < CHANNELS) {
			throw new IllegalArgumentException("input needs six channels");
		}
		for (int channel = 0; channel < CHANNELS; channel++) {
			if (buffers[channel] == null || buffers[channel].length < frames) {
				throw new IllegalArgumentException("input buffer too short");
			}
		}
	}

	private void validateOutput(float[][] buffers, int frames) {
		if (buffers == null || buffers.length < outputCount) {
			throw new IllegalArgumentException("output needs " + outputCount
				+ " channels");
		}
		for (int channel = 0; channel < outputCount; channel++) {
			if (buffers[channel] == null || buffers[channel].length < frames) {
				throw new IllegalArgumentException("output buffer too short");
			}
		}
	}
}
