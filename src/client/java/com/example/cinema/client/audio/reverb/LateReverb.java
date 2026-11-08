package com.example.cinema.client.audio.reverb;

import com.example.cinema.client.audio.filter.Biquad;

import java.util.Arrays;

/**
 * 64-line feedback delay network for the full-band late field.
 *
 * <p>The direct path and first-order reflections already carry their own
 * frequency shapes. This network generates the diffuse tail across the full
 * audio band: the send uses only a low DC/subsonic high-pass
 * ({@link LateReverbSettings#highPassHz()}, default 20 Hz), delayed by
 * 20-50 ms, and passed through four Schroeder allpass diffusers before being
 * fed to 64 delay lines mixed by an orthogonal Householder matrix. Each line
 * has its own low/mid/high Fitzroy RT60 weighting, a one-pole damping filter
 * for the high band, and a slow delay modulation that avoids static metallic
 * modes.
 *
 * <p>Six outputs are formed from orthogonal rows of a 64-point Hadamard matrix,
 * giving decorrelated 5.1 wet channels. LFE is included in both the send and
 * the wet output; low-frequency room modes remain the separate A5 modal
 * treatment, not a hard split of the late field.
 *
 * <p>The class is audio-thread only after construction: {@link #process}
 * performs no per-sample allocation and {@link #setSettings} only copies scalar
 * targets. Runtime parameter changes are smoothed at block boundaries.
 */
public final class LateReverb {
	public static final int LINES = 64;
	public static final int CHANNELS = 6;
	public static final int LFE_CHANNEL = 3;
	public static final double HIGH_FREQUENCY_REFERENCE_HZ = 4_000.0;
	private static final double SETTINGS_SMOOTHING_SECONDS = 0.08;
	private static final double PREDELAY_SMOOTHING_SECONDS = 0.15;
	private static final double DAMPING_EPSILON = 1.0e-4;
	private static final double MAX_FEEDBACK_GAIN = 0.9995;
	private static final double LOW_SHELF_CUTOFF_HZ = 200.0;
	private static final double MIN_LOW_SHELF_GAIN = 0.25;
	private static final double MAX_LOW_SHELF_GAIN = 8.0;
	private static final int DIFFUSER_COUNT = 4;
	private static final double DIFFUSER_GAIN = 0.45;
	private static final double[] DIFFUSER_MILLISECONDS = {
		5.1, 7.7, 11.3, 15.7,
	};
	/**
	 * With 64 lines the network is already dense, so only a small amount of
	 * modulation is needed to break static modes. A larger depth turns the
	 * summed allpass-interpolated lines into audible HF shimmer ("sand").
	 */
	private static final double MODULATION_DEPTH_SECONDS = 0.00006;
	private static final double MODULATION_BASE_HZ = 0.03;
	private static final double MODULATION_STEP_HZ = 0.0025;
	/**
	 * Final wet-output low-pass. The FDN's RT60_high damping is applied per
	 * line, but short-RT60 rooms still leave a bright first pass and the
	 * allpass interpolation adds HF sidebands. A fixed 7 kHz Butterworth
	 * section removes that "sand" without touching the mid-band level.
	 */
	private static final double OUTPUT_LOW_PASS_HZ = 7_000.0;
	/**
	 * Extra low-frequency wet support. The per-line low shelf follows the
	 * physical RT60_low and is allowed to cut lows in bright/wooden rooms;
	 * this fixed shelf keeps the audible bass reverb from disappearing.
	 */
	private static final double OUTPUT_BASS_SHELF_HZ = 120.0;
	private static final double OUTPUT_BASS_SHELF_GAIN_DB = 3.0;
	/**
	 * The output normalization below targets one unit of total wet RMS per unit
	 * send RMS. The calibration constant compensates the difference between the
	 * uncorrelated line estimate and the actual Hadamard sum.
	 */
	private static final double OUTPUT_GAIN = 1.0;
	private static final double NORMALIZATION_CALIBRATION = 1.0;
	private static final double HADAMARD_SCALE = 1.0 / Math.sqrt(LINES);

	private static final double HOUSEHOLDER_SCALE = 2.0 / LINES;
	private static final double[] DELAY_MILLISECONDS = createDelayMilliseconds();
	private static final double[] INJECTION = hadamardSigns(1);
	/** Rows 1..6 of the 64-point Hadamard matrix; channel 3 is LFE. */
	private static final double[][] OUTPUT_SIGNS = hadamardRows(6);

	private static double[] createDelayMilliseconds() {
		double[] values = new double[LINES];
		for (int line = 0; line < LINES; line++) {
			double fraction = (line * 0.6180339887498949) % 1.0;
			values[line] = 20.0 + 130.0 * fraction;
		}
		return values;
	}

	private static double[] hadamardSigns(int row) {
		double[] signs = new double[LINES];
		for (int line = 0; line < LINES; line++) {
			signs[line] = (Integer.bitCount(row & line) & 1) == 0 ? 1.0 : -1.0;
		}
		return signs;
	}

	private static double[][] hadamardRows(int rows) {
		double[][] signs = new double[rows][];
		for (int row = 0; row < rows; row++) {
			signs[row] = hadamardSigns(row + 1);
		}
		return signs;
	}

	private final int sampleRate;
	private final float[][] lines = new float[LINES][];
	private final int[] writeIndices = new int[LINES];
	private final int[] delaySamples = new int[LINES];
	private final double[] modulationPhases = new double[LINES];
	private final double[] modulationIncrements = new double[LINES];
	private final double[] fractionalInputStates = new double[LINES];
	private final double[] fractionalOutputStates = new double[LINES];
	private final double modulationDepthSamples;
	private final float[][] diffuserBuffers = new float[DIFFUSER_COUNT][];
	private final int[] diffuserIndices = new int[DIFFUSER_COUNT];
	private final double[] dampingStates = new double[LINES];
	private final double[] outputDampingStates = new double[LINES];
	private final double[] lowShelfStates = new double[LINES];
	private final double[] feedbackGains = new double[LINES];
	private final double[] lowShelfGains = new double[LINES];
	private final double[] dampingPoles = new double[LINES];
	private final double[] lineOutputs = new double[LINES];
	private final double[] feedbackValues = new double[LINES];
	private final double[] outputLowPassX1 = new double[CHANNELS];
	private final double[] outputLowPassX2 = new double[CHANNELS];
	private final double[] outputLowPassY1 = new double[CHANNELS];
	private final double[] outputLowPassY2 = new double[CHANNELS];
	private final double[] outputBassShelfX1 = new double[CHANNELS];
	private final double[] outputBassShelfX2 = new double[CHANNELS];
	private final double[] outputBassShelfY1 = new double[CHANNELS];
	private final double[] outputBassShelfY2 = new double[CHANNELS];
	private final float[] preDelayLine;
	private final Biquad outputLowPass;
	private final Biquad outputBassShelf;
	private double normalizationGain = 1.0;
	private int preDelayWriteIndex;
	private double preDelaySamples;

	private double targetRt60Low = LateReverbSettings.OFF.rt60LowSeconds();
	private double targetRt60Mid = LateReverbSettings.OFF.rt60MidSeconds();
	private double targetRt60High = LateReverbSettings.OFF.rt60HighSeconds();
	private double targetHighPassHz = LateReverbSettings.OFF.highPassHz();
	private double targetPreDelaySeconds = LateReverbSettings.OFF.preDelaySeconds();
	private boolean targetEnabled;
	private double currentRt60Low = targetRt60Low;
	private double currentRt60Mid = targetRt60Mid;
	private double currentRt60High = targetRt60High;
	private double currentHighPassHz = targetHighPassHz;
	private double currentPreDelaySeconds = targetPreDelaySeconds;
	private boolean currentEnabled;
	private boolean initialized;
	private final double lowShelfAlpha;

	private Biquad highPass;
	private double highPassCoefficientHz = Double.NaN;
	private double highPassX1;
	private double highPassX2;
	private double highPassY1;
	private double highPassY2;

	public LateReverb(int sampleRate) {
		if (sampleRate < 8_000) {
			throw new IllegalArgumentException("sample rate too low");
		}
		this.sampleRate = sampleRate;
		outputLowPass = Biquad.lowPass(OUTPUT_LOW_PASS_HZ, Biquad.DEFAULT_Q,
			sampleRate);
		outputBassShelf = Biquad.lowShelf(OUTPUT_BASS_SHELF_HZ,
			Biquad.DEFAULT_Q, OUTPUT_BASS_SHELF_GAIN_DB, sampleRate);
		modulationDepthSamples = Math.max(2.0,
			MODULATION_DEPTH_SECONDS * sampleRate);
		for (int line = 0; line < LINES; line++) {
			int samples = Math.max(2,
				(int) Math.round(DELAY_MILLISECONDS[line] * sampleRate / 1_000.0));
			delaySamples[line] = samples;
			int bufferSamples = samples
				+ (int) Math.ceil(modulationDepthSamples * 2.0) + 2;
			lines[line] = new float[bufferSamples];
			modulationPhases[line] = line * 2.0 * Math.PI / LINES;
			modulationIncrements[line] = 2.0 * Math.PI
				* (MODULATION_BASE_HZ + line * MODULATION_STEP_HZ) / sampleRate;
		}
		for (int diffuser = 0; diffuser < DIFFUSER_COUNT; diffuser++) {
			int samples = Math.max(2, (int) Math.round(
				DIFFUSER_MILLISECONDS[diffuser] * sampleRate / 1_000.0));
			diffuserBuffers[diffuser] = new float[samples];
		}
		int preDelayLength = Math.max(2,
			(int) Math.ceil(LateReverbSettings.MAX_PRE_DELAY_SECONDS
				* sampleRate) + 1);
		preDelayLine = new float[preDelayLength];
		lowShelfAlpha = 1.0 - Math.exp(-2.0 * Math.PI * LOW_SHELF_CUTOFF_HZ
			/ sampleRate);
		preDelaySamples = currentPreDelaySeconds * sampleRate;
		updateHighPassCoefficients();
	}

	/** Copies new targets. The audio thread normally calls this via a volatile. */
	public void setSettings(LateReverbSettings settings) {
		LateReverbSettings value = settings == null
			? LateReverbSettings.OFF : settings;
		targetEnabled = value.enabled();
		targetRt60Low = value.rt60LowSeconds();
		targetRt60Mid = value.rt60MidSeconds();
		targetRt60High = value.rt60HighSeconds();
		targetHighPassHz = value.highPassHz();
		targetPreDelaySeconds = value.preDelaySeconds();
		if (!initialized) {
			snapSettings();
		}
	}

	/** Clears all delay/filter state. Used on seek, reset and device rebuild. */
	public void reset() {
		for (float[] line : lines) {
			Arrays.fill(line, 0.0f);
		}
		Arrays.fill(writeIndices, 0);
		Arrays.fill(dampingStates, 0.0);
		Arrays.fill(outputDampingStates, 0.0);
		Arrays.fill(lowShelfStates, 0.0);
		Arrays.fill(preDelayLine, 0.0f);
		for (int diffuser = 0; diffuser < DIFFUSER_COUNT; diffuser++) {
			Arrays.fill(diffuserBuffers[diffuser], 0.0f);
			diffuserIndices[diffuser] = 0;
		}
		for (int line = 0; line < LINES; line++) {
			modulationPhases[line] = line * 2.0 * Math.PI / LINES;
			fractionalInputStates[line] = 0.0;
			fractionalOutputStates[line] = 0.0;
		}
		preDelayWriteIndex = 0;
		Arrays.fill(outputLowPassX1, 0.0);
		Arrays.fill(outputLowPassX2, 0.0);
		Arrays.fill(outputLowPassY1, 0.0);
		Arrays.fill(outputLowPassY2, 0.0);
		Arrays.fill(outputBassShelfX1, 0.0);
		Arrays.fill(outputBassShelfX2, 0.0);
		Arrays.fill(outputBassShelfY1, 0.0);
		Arrays.fill(outputBassShelfY2, 0.0);
		highPassX1 = 0.0;
		highPassX2 = 0.0;
		highPassY1 = 0.0;
		highPassY2 = 0.0;
		initialized = false;
		snapSettings();
	}

	/**
	 * Processes one mono send block into six wet channels. The caller owns all
	 * arrays; {@code outputs} must contain at least {@link #CHANNELS} rows of
	 * {@code frames} samples.
	 */
	public void process(float[] send, int frames, float[][] outputs) {
		if (frames <= 0) {
			return;
		}
		if (send == null || outputs == null || outputs.length < CHANNELS) {
			throw new IllegalArgumentException("invalid late reverb buffers");
		}
		for (int channel = 0; channel < CHANNELS; channel++) {
			if (outputs[channel] == null || outputs[channel].length < frames) {
				throw new IllegalArgumentException("late reverb output too short");
			}
			Arrays.fill(outputs[channel], 0, frames, 0.0f);
		}
		applySettings(frames);
		double startPreDelay = preDelaySamples;
		double targetPreDelay = currentPreDelaySeconds * sampleRate;
		double preDelayStep = (targetPreDelay - startPreDelay) / frames;
		for (int frame = 0; frame < frames; frame++) {
			double input = currentEnabled ? send[frame] : 0.0;
			if (!Double.isFinite(input)) {
				input = 0.0;
			}
			input = highPass(input);
			double delayedInput = preDelay(input,
				startPreDelay + preDelayStep * (frame + 1));
			delayedInput = diffuse(delayedInput);

			for (int line = 0; line < LINES; line++) {
				double delay = delaySamples[line] + modulationDepthSamples
					* Math.sin(modulationPhases[line]);
				modulationPhases[line] += modulationIncrements[line];
				if (modulationPhases[line] >= 2.0 * Math.PI) {
					modulationPhases[line] -= 2.0 * Math.PI;
				}
				int integerDelay = (int) Math.floor(delay);
				double fraction = delay - integerDelay;
				int readIndex = writeIndices[line] - integerDelay;
				while (readIndex < 0) {
					readIndex += lines[line].length;
				}
				double sample = lines[line][readIndex];
				if (fraction < 1.0e-3) {
					lineOutputs[line] = sample;
					fractionalInputStates[line] = sample;
					fractionalOutputStates[line] = sample;
				} else {
					double coefficient = (1.0 - fraction) / (1.0 + fraction);
					double output = coefficient * sample
						+ fractionalInputStates[line]
						- coefficient * fractionalOutputStates[line];
					fractionalInputStates[line] = sample;
					fractionalOutputStates[line] = output;
					lineOutputs[line] = output;
				}
			}

			double sum = 0.0;
			for (int line = 0; line < LINES; line++) {
				double state = lineOutputs[line];
				lowShelfStates[line] += lowShelfAlpha
					* (state - lowShelfStates[line]);
				// The feedback path uses the physical low-band ratio (it may
				// cut or boost). The output path only ever boosts lows: a cut
				// here made the wet tail lose weight whenever the room's
				// low-band absorption was higher than its mid-band value.
				double outputShelfGain = Math.max(1.0, lowShelfGains[line]);
				double outputLowShelved = state
					+ (outputShelfGain - 1.0) * lowShelfStates[line];
				outputDampingStates[line] = dampingPoles[line]
					* outputDampingStates[line]
					+ (1.0 - dampingPoles[line]) * outputLowShelved;
				double lowShelved = state + (lowShelfGains[line] - 1.0)
					* lowShelfStates[line];
				dampingStates[line] = dampingPoles[line] * dampingStates[line]
					+ (1.0 - dampingPoles[line]) * lowShelved;
				double feedback = dampingStates[line] * feedbackGains[line];
				feedbackValues[line] = feedback;
				sum += feedback;
			}

			for (int line = 0; line < LINES; line++) {
				double value = feedbackValues[line] - HOUSEHOLDER_SCALE * sum
					+ delayedInput * INJECTION[line] * HADAMARD_SCALE;
				int index = writeIndices[line];
				lines[line][index] = (float) value;
				index++;
				if (index >= lines[line].length) {
					index = 0;
				}
				writeIndices[line] = index;
			}

			// Tap the output-damped line state, not the raw delay output. For
			// short RT60 the direct injection dominates the first pass through
			// each line; using the raw tap there would add an undamped, bright
			// copy of the send on top of the tail and sound like HF "sand".
			for (int channel = 0; channel < CHANNELS; channel++) {
				double value = 0.0;
				for (int line = 0; line < LINES; line++) {
					value += OUTPUT_SIGNS[channel][line] * outputDampingStates[line];
				}
				outputs[channel][frame] = (float) outputLowPass(outputBassShelf(
					value * HADAMARD_SCALE * OUTPUT_GAIN * normalizationGain,
					channel), channel);
			}
		}
		preDelaySamples = targetPreDelay;
	}

	private void snapSettings() {
		currentEnabled = targetEnabled;
		currentRt60Low = targetRt60Low;
		currentRt60Mid = targetRt60Mid;
		currentRt60High = targetRt60High;
		currentHighPassHz = targetHighPassHz;
		currentPreDelaySeconds = targetPreDelaySeconds;
		preDelaySamples = currentPreDelaySeconds * sampleRate;
		updateHighPassCoefficients();
		updateFeedback();
		initialized = true;
	}

	private void applySettings(int frames) {
		if (!initialized) {
			snapSettings();
			return;
		}
		double alpha = 1.0 - Math.exp(-frames
			/ (SETTINGS_SMOOTHING_SECONDS * sampleRate));
		double preDelayAlpha = 1.0 - Math.exp(-frames
			/ (PREDELAY_SMOOTHING_SECONDS * sampleRate));
		currentRt60Low += (targetRt60Low - currentRt60Low) * alpha;
		currentRt60Mid += (targetRt60Mid - currentRt60Mid) * alpha;
		currentRt60High += (targetRt60High - currentRt60High) * alpha;
		currentHighPassHz += (targetHighPassHz - currentHighPassHz) * alpha;
		currentPreDelaySeconds += (targetPreDelaySeconds - currentPreDelaySeconds)
			* preDelayAlpha;
		currentEnabled = targetEnabled;
		updateHighPassCoefficients();
		updateFeedback();
	}

	private void updateHighPassCoefficients() {
		if (Double.isFinite(highPassCoefficientHz)
			&& Math.abs(currentHighPassHz - highPassCoefficientHz) < 0.01) {
			return;
		}
		highPass = Biquad.highPass(currentHighPassHz, Biquad.DEFAULT_Q, sampleRate);
		highPassCoefficientHz = currentHighPassHz;
	}

	private void updateFeedback() {
		double rt60Low = Math.max(LateReverbSettings.MIN_RT60_SECONDS,
			Math.min(LateReverbSettings.MAX_RT60_SECONDS, currentRt60Low));
		double rt60Mid = Math.max(LateReverbSettings.MIN_RT60_SECONDS,
			Math.min(LateReverbSettings.MAX_RT60_SECONDS, currentRt60Mid));
		double rt60High = Math.max(LateReverbSettings.MIN_RT60_SECONDS,
			Math.min(LateReverbSettings.MAX_RT60_SECONDS, currentRt60High));
		double lineEnergy = 0.0;
		for (int line = 0; line < LINES; line++) {
			double delaySeconds = delaySamples[line] / (double) sampleRate;
			double midGain = Math.pow(10.0, -3.0 * delaySeconds / rt60Mid);
			double lowGain = Math.pow(10.0, -3.0 * delaySeconds / rt60Low);
			double highGain = Math.pow(10.0, -3.0 * delaySeconds / rt60High);
			feedbackGains[line] = Math.min(MAX_FEEDBACK_GAIN, midGain);
			lowShelfGains[line] = Math.max(MIN_LOW_SHELF_GAIN,
				Math.min(MAX_LOW_SHELF_GAIN,
					midGain > 0.0 ? lowGain / midGain : 1.0));
			double ratio = midGain > 0.0 ? highGain / midGain : 1.0;
			// A one-pole damping filter can only shorten the HF decay. If the
			// measured RT60_high is longer than RT60_mid, the HF tail follows
			// RT60_mid rather than being artificially boosted.
			dampingPoles[line] = dampingPole(ratio);
			lineEnergy += 1.0 / Math.max(1.0e-6,
				1.0 - feedbackGains[line] * feedbackGains[line]);
		}
		// Six output rows, each row coefficient is 1/sqrt(LINES). This is the
		// uncorrelated steady-state energy estimate; the calibration constant
		// is measured against the actual Hadamard sum.
		double theoretical = Math.sqrt((6.0 / (LINES * (double) LINES))
			* lineEnergy);
		normalizationGain = theoretical > 1.0e-9
			? NORMALIZATION_CALIBRATION / theoretical : 1.0;
	}

	/**
	 * One-pole low-pass pole whose magnitude at 4 kHz equals {@code ratio}.
	 * A ratio at or above unity needs no damping; very small ratios reach the
	 * stable pole limit instead of a resonant/boosting filter.
	 */
	private double dampingPole(double ratio) {
		double target = Math.max(1.0e-6, Math.min(1.0, ratio));
		if (target >= 1.0 - DAMPING_EPSILON) {
			return 0.0;
		}
		double omega = 2.0 * Math.PI * HIGH_FREQUENCY_REFERENCE_HZ / sampleRate;
		double cosine = Math.cos(omega);
		double targetSquared = target * target;
		double denominator = 1.0 - targetSquared;
		double b = (1.0 - targetSquared * cosine) / denominator;
		double root = Math.sqrt(Math.max(0.0, b * b - 1.0));
		double pole = 1.0 / (b + root);
		return Math.max(0.0, Math.min(0.9995, pole));
	}

	private double highPass(double input) {
		double output = highPass.b0() * input + highPass.b1() * highPassX1
			+ highPass.b2() * highPassX2
			- highPass.a1() * highPassY1 - highPass.a2() * highPassY2;
		highPassX2 = highPassX1;
		highPassX1 = input;
		highPassY2 = highPassY1;
		highPassY1 = output;
		return output;
	}

	private double outputLowPass(double input, int channel) {
		double output = outputLowPass.b0() * input
			+ outputLowPass.b1() * outputLowPassX1[channel]
			+ outputLowPass.b2() * outputLowPassX2[channel]
			- outputLowPass.a1() * outputLowPassY1[channel]
			- outputLowPass.a2() * outputLowPassY2[channel];
		outputLowPassX2[channel] = outputLowPassX1[channel];
		outputLowPassX1[channel] = input;
		outputLowPassY2[channel] = outputLowPassY1[channel];
		outputLowPassY1[channel] = output;
		return output;
	}

	private double outputBassShelf(double input, int channel) {
		double output = outputBassShelf.b0() * input
			+ outputBassShelf.b1() * outputBassShelfX1[channel]
			+ outputBassShelf.b2() * outputBassShelfX2[channel]
			- outputBassShelf.a1() * outputBassShelfY1[channel]
			- outputBassShelf.a2() * outputBassShelfY2[channel];
		outputBassShelfX2[channel] = outputBassShelfX1[channel];
		outputBassShelfX1[channel] = input;
		outputBassShelfY2[channel] = outputBassShelfY1[channel];
		outputBassShelfY1[channel] = output;
		return output;
	}

	private double diffuse(double input) {
		double value = input;
		for (int diffuser = 0; diffuser < DIFFUSER_COUNT; diffuser++) {
			float[] buffer = diffuserBuffers[diffuser];
			int index = diffuserIndices[diffuser];
			double delayed = buffer[index];
			double output = -DIFFUSER_GAIN * value + delayed;
			buffer[index] = (float) (value + DIFFUSER_GAIN * output);
			index++;
			if (index >= buffer.length) {
				index = 0;
			}
			diffuserIndices[diffuser] = index;
			value = output;
		}
		return value;
	}

	private double preDelay(double input, double delaySamples) {
		preDelayLine[preDelayWriteIndex] = (float) input;
		double read = preDelayWriteIndex - Math.max(1.0,
			Math.min(preDelayLine.length - 2.0, delaySamples));
		if (read < 0.0) {
			read += preDelayLine.length;
		}
		int first = (int) read;
		double fraction = read - first;
		int second = first + 1;
		if (second >= preDelayLine.length) {
			second = 0;
		}
		double output = preDelayLine[first]
			+ (preDelayLine[second] - preDelayLine[first]) * fraction;
		preDelayWriteIndex++;
		if (preDelayWriteIndex >= preDelayLine.length) {
			preDelayWriteIndex = 0;
		}
		return output;
	}

}
