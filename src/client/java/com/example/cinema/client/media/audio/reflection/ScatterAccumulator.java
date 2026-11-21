package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.acousticfir.MaterialReflectionFilter;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregates scattered energy into at most one diffuse tap per 5.1 source
 * channel.
 *
 * <p>The caller adds one contribution per valid reflection, already including
 * speaker/FIR gain. {@link #build} turns the energy-weighted delay, damping
 * and material into a {@link LateReflectionBus.Tap} with a zero direction so
 * {@link Vbap} uses its diffuse fallback. No OpenAL source or FIR is created.
 */
public final class ScatterAccumulator {
	private static final double EPSILON = 1.0e-12;
	private static final int CHANNELS = LateReflectionBus.CHANNELS;
	private final double[] energy = new double[CHANNELS];
	private final double[] delayEnergy = new double[CHANNELS];
	private final double[] dampingEnergy = new double[CHANNELS];
	private final double[] lowEnergy = new double[CHANNELS];
	private final double[] midEnergy = new double[CHANNELS];
	private final double[] highEnergy = new double[CHANNELS];

	public void add(int channel, double contributionEnergy, double delaySeconds,
					double dampingHz) {
		add(channel, contributionEnergy, delaySeconds, dampingHz, null);
	}

	/** Adds one contribution with an optional material for A3-D1 filtering. */
	public void add(int channel, double contributionEnergy, double delaySeconds,
					double dampingHz, AcousticMaterial material) {
		if (channel < 0 || channel >= CHANNELS
			|| !Double.isFinite(contributionEnergy)
			|| contributionEnergy <= EPSILON) {
			return;
		}
		double delay = Double.isFinite(delaySeconds)
			? Math.max(0.0, delaySeconds) : 0.0;
		double damping = Double.isFinite(dampingHz) && dampingHz > 0.0
			? dampingHz : LateReflectionBus.DEFAULT_DAMPING_HZ;
		energy[channel] += contributionEnergy;
		delayEnergy[channel] += contributionEnergy * delay;
		dampingEnergy[channel] += contributionEnergy * damping;
		if (material != null) {
			lowEnergy[channel] += contributionEnergy * material.low();
			midEnergy[channel] += contributionEnergy * material.mid();
			highEnergy[channel] += contributionEnergy * material.high();
		}
	}

	public boolean isEmpty() {
		for (double value : energy) {
			if (value > EPSILON) {
				return false;
			}
		}
		return true;
	}

	public double energy(int channel) {
		return channel >= 0 && channel < CHANNELS ? energy[channel] : 0.0;
	}

	public double totalEnergy() {
		double total = 0.0;
		for (double value : energy) {
			total += value;
		}
		return total;
	}

	public List<LateReflectionBus.Tap> build(float[][] speakerPositions,
											 int[] speakerChannels,
											 String keyPrefix) {
		return build(speakerPositions, speakerChannels, keyPrefix, false,
			MaterialReflectionFilter.DEFAULT_SAMPLE_RATE);
	}

	/**
	 * Builds one tap per non-empty source channel. {@code speakerPositions}
	 * and {@code speakerChannels} are listener-relative and use the same
	 * physical layout as the existing late bus. When {@code materialEq} is
	 * true, the energy-weighted material becomes a three-band PEQ.
	 */
	public List<LateReflectionBus.Tap> build(float[][] speakerPositions,
											 int[] speakerChannels,
											 String keyPrefix,
											 boolean materialEq,
											 double sampleRate) {
		List<LateReflectionBus.Tap> taps = new ArrayList<>();
		String prefix = keyPrefix == null ? "scatter:" : keyPrefix;
		Vbap.Weights weights = Vbap.pan(new float[3], speakerPositions,
			speakerChannels);
		for (int channel = 0; channel < CHANNELS; channel++) {
			double channelEnergy = energy[channel];
			if (channelEnergy <= EPSILON) {
				continue;
			}
			double delay = delayEnergy[channel] / channelEnergy;
			double damping = dampingEnergy[channel] / channelEnergy;
			MaterialReflectionFilter filter = MaterialReflectionFilter.FLAT;
			if (materialEq) {
				AcousticMaterial material = new AcousticMaterial("scatter",
					lowEnergy[channel] / channelEnergy,
					midEnergy[channel] / channelEnergy,
					highEnergy[channel] / channelEnergy, 0.0);
				filter = MaterialReflectionFilter.of(material, sampleRate);
			}
			taps.add(new LateReflectionBus.Tap(channel, delay,
				Math.sqrt(channelEnergy), damping, new float[3],
				weights.channels(), weights.weights(), prefix + channel, filter));
		}
		return List.copyOf(taps);
	}
}
