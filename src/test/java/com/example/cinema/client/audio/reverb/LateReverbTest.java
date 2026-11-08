package com.example.cinema.client.audio.reverb;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateReverbTest {
	private static final int SAMPLE_RATE = 48_000;
	private static final int FRAMES = 512;

	@Test
	void lfeOutputIsActive() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(1.0, 0.7, 100.0, 0.020));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		send[0] = 1.0f;
		double lfeEnergy = 0.0;
		double otherEnergy = 0.0;
		for (int block = 0; block < 200; block++) {
			reverb.process(send, FRAMES, outputs);
			for (int frame = 0; frame < FRAMES; frame++) {
				float lfe = outputs[LateReverb.LFE_CHANNEL][frame];
				lfeEnergy += lfe * lfe;
				for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
					if (channel != LateReverb.LFE_CHANNEL) {
						float value = outputs[channel][frame];
						otherEnergy += value * value;
					}
				}
			}
			java.util.Arrays.fill(send, 0.0f);
		}
		assertTrue(lfeEnergy > 0.0, "LFE must receive late-reverb energy");
		assertTrue(otherEnergy > 0.0, "non-LFE wet channels must stay active");
	}

	@Test
	void lowRt60ChangesLowFrequencyTailEnergy() {
		double shortLow = lowFrequencyRms(0.3);
		double longLow = lowFrequencyRms(2.0);
		assertTrue(shortLow < longLow * 0.85,
			"short low-band RT60 should reduce LF tail: short=" + shortLow
				+ " long=" + longLow);
	}

	private static double lowFrequencyRms(double rt60Low) {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(new LateReverbSettings(true, rt60Low, 1.0, 0.7,
			20.0, 0.020, -12.0));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		double sum = 0.0;
		long count = 0L;
		for (int block = 0; block < 300; block++) {
			for (int frame = 0; frame < FRAMES; frame++) {
				double phase = 2.0 * Math.PI * 100.0
					* (block * FRAMES + frame) / SAMPLE_RATE;
				send[frame] = (float) (0.1 * Math.sin(phase));
			}
			reverb.process(send, FRAMES, outputs);
			if (block > 100) {
				for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
					for (int frame = 0; frame < FRAMES; frame++) {
						sum += outputs[channel][frame] * outputs[channel][frame];
					}
				}
				count += FRAMES;
			}
		}
		return Math.sqrt(sum / count);
	}

	@Test
	void resetClearsTheTail() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(2.0, 1.0, 20.0, 0.020));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		send[0] = 1.0f;
		for (int block = 0; block < 50; block++) {
			reverb.process(send, FRAMES, outputs);
			java.util.Arrays.fill(send, 0.0f);
		}
		reverb.reset();
		for (int block = 0; block < 20; block++) {
			reverb.process(send, FRAMES, outputs);
			for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
				for (int frame = 0; frame < FRAMES; frame++) {
					assertEquals(0.0f, outputs[channel][frame]);
				}
			}
		}
	}

	@Test
	void disabledReverbProducesSilence() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(new LateReverbSettings(false, 1.0, 0.7, 100.0,
			0.020, -12.0));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		java.util.Arrays.fill(send, 0.5f);
		for (int block = 0; block < 100; block++) {
			reverb.process(send, FRAMES, outputs);
			for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
				for (int frame = 0; frame < FRAMES; frame++) {
					assertEquals(0.0f, outputs[channel][frame]);
				}
			}
		}
	}

	@Test
	void impulseTailDecaysAndStaysFinite() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(0.8, 0.6, 100.0, 0.020));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		send[0] = 1.0f;
		double early = 0.0;
		double late = 0.0;
		double peak = 0.0;
		for (int block = 0; block < 300; block++) {
			reverb.process(send, FRAMES, outputs);
			double energy = 0.0;
			for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
				for (int frame = 0; frame < FRAMES; frame++) {
					float value = outputs[channel][frame];
					assertTrue(Float.isFinite(value));
					peak = Math.max(peak, Math.abs(value));
					energy += value * value;
				}
			}
			double time = block * FRAMES / (double) SAMPLE_RATE;
			if (time >= 0.1 && time < 0.4) {
				early += energy;
			} else if (time >= 1.0 && time < 1.6) {
				late += energy;
			}
			java.util.Arrays.fill(send, 0.0f);
		}
		assertTrue(early > 0.0, "impulse should excite a tail");
		assertTrue(late < early * 0.05,
			"tail should decay: early=" + early + " late=" + late);
		assertTrue(peak < 4.0, "late reverb peak should stay bounded: " + peak);
	}

	@Test
	void outputChannelsAreDecorrelated() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(1.0, 0.7, 100.0, 0.020));
		float[][] outputs = outputBuffers();
		Random random = new Random(7L);
		float[] send = new float[FRAMES];
		double[] energy = new double[LateReverb.CHANNELS];
		double[] cross = new double[LateReverb.CHANNELS];
		for (int block = 0; block < 200; block++) {
			for (int frame = 0; frame < FRAMES; frame++) {
				send[frame] = (float) (random.nextDouble() * 2.0 - 1.0) * 0.1f;
			}
			reverb.process(send, FRAMES, outputs);
			for (int frame = 0; frame < FRAMES; frame++) {
				float reference = outputs[0][frame];
				energy[0] += reference * reference;
				for (int channel = 1; channel < LateReverb.CHANNELS; channel++) {
					float value = outputs[channel][frame];
					energy[channel] += value * value;
					cross[channel] += reference * value;
				}
			}
		}
		for (int channel = 1; channel < LateReverb.CHANNELS; channel++) {
			double correlation = cross[channel]
				/ Math.sqrt(Math.max(1.0e-12, energy[0] * energy[channel]));
			assertTrue(Math.abs(correlation) < 0.5,
				"wet channels should be decorrelated: ch=" + channel
					+ " r=" + correlation);
		}
	}

	@Test
	void highFrequencyTailIsDarkerThanMid() {
		double mid = sineWetRms(500.0, 1.0, 0.4);
		double high = sineWetRms(10_000.0, 1.0, 0.4);
		assertTrue(high < mid * 0.25,
			"10 kHz wet tail should be well below mid: mid=" + mid
				+ " high=" + high);
	}

	private static double sineWetRms(double frequency, double rt60Mid,
									 double rt60High) {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(rt60Mid, rt60High, 20.0, 0.020));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		double sum = 0.0;
		long count = 0L;
		for (int block = 0; block < 300; block++) {
			for (int frame = 0; frame < FRAMES; frame++) {
				double phase = 2.0 * Math.PI * frequency
					* (block * FRAMES + frame) / SAMPLE_RATE;
				send[frame] = (float) (0.1 * Math.sin(phase));
			}
			reverb.process(send, FRAMES, outputs);
			if (block > 100) {
				for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
					for (int frame = 0; frame < FRAMES; frame++) {
						sum += outputs[channel][frame] * outputs[channel][frame];
					}
				}
				count += FRAMES;
			}
		}
		return Math.sqrt(sum / count);
	}

	@Test
	void filteredWetRmsIsNormalizedAcrossRt60() {
		// The 7 kHz output low-pass removes roughly half of the white-noise
		// energy, so the absolute RMS is lower than the send. What matters is
		// that the post-filter level stays constant as RT60 changes.
		for (double rt60 : new double[]{0.3, 0.6, 1.0, 3.0, 5.0}) {
			double rms = totalWetRms(rt60, rt60);
			assertTrue(rms > 0.05 && rms < 0.06,
				"normalized wet RMS at RT60=" + rt60 + " is " + rms);
		}
		double undamped = totalWetRms(1.0, 1.0);
		double damped = totalWetRms(1.0, 0.4);
		assertTrue(damped > 0.0 && damped < undamped * 0.98,
			"HF damping should reduce total wet energy: damped=" + damped
				+ " undamped=" + undamped);
	}

	/** Post-filter total field RMS: sum all six output channels, divide by frame count. */
	private static double totalWetRms(double rt60Mid, double rt60High) {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(rt60Mid, rt60High, 100.0, 0.020));
		float[][] outputs = outputBuffers();
		Random random = new Random(11L);
		float[] send = new float[FRAMES];
		double sum = 0.0;
		long count = 0L;
		for (int block = 0; block < 300; block++) {
			for (int frame = 0; frame < FRAMES; frame++) {
				send[frame] = (float) (random.nextGaussian() * 0.1);
			}
			reverb.process(send, FRAMES, outputs);
			if (block > 100) {
				for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
					for (int frame = 0; frame < FRAMES; frame++) {
						sum += outputs[channel][frame] * outputs[channel][frame];
					}
				}
				count += FRAMES;
			}
		}
		return Math.sqrt(sum / count);
	}

	@Test
	void feedbackStaysStableUnderLongContinuousInput() {
		LateReverb reverb = new LateReverb(SAMPLE_RATE);
		reverb.setSettings(settings(5.0, 5.0, 100.0, 0.020));
		float[][] outputs = outputBuffers();
		float[] send = new float[FRAMES];
		java.util.Arrays.fill(send, 0.25f);
		double peak = 0.0;
		for (int block = 0; block < 1_000; block++) {
			reverb.process(send, FRAMES, outputs);
			for (int channel = 0; channel < LateReverb.CHANNELS; channel++) {
				for (int frame = 0; frame < FRAMES; frame++) {
					float value = outputs[channel][frame];
					assertTrue(Float.isFinite(value));
					peak = Math.max(peak, Math.abs(value));
				}
			}
		}
		assertTrue(peak < 8.0, "FDN output should stay bounded: " + peak);
	}

	private static LateReverbSettings settings(double mid, double high,
											   double highPass, double preDelay) {
		return new LateReverbSettings(true, mid, high, highPass, preDelay,
			LateReverbSettings.DEFAULT_WET_GAIN_DB);
	}

	private static float[][] outputBuffers() {
		float[][] outputs = new float[LateReverb.CHANNELS][FRAMES];
		return outputs;
	}
}
