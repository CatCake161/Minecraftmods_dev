package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionPathTap;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateReflectionBusTest {
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = LateReflectionBus.CHANNELS;

	@Test
	void impulseArrivesAtIntegerDelay() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 0.010, 1.0, 48_000.0)));
		float[][] input = buffers(1024);
		float[][] outputs = buffers(1024);
		input[0][0] = 1.0f;
		bus.process(input, 1024, outputs);
		assertEquals(1.0, outputs[0][480], 1.0e-4);
		for (int frame = 0; frame < 1024; frame++) {
			if (frame != 480) {
				assertEquals(0.0f, outputs[0][frame]);
			}
		}
		for (int channel = 1; channel < CHANNELS; channel++) {
			for (int frame = 0; frame < 1024; frame++) {
				assertEquals(0.0f, outputs[channel][frame]);
			}
		}
	}

	@Test
	void fractionalDelayInterpolates() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4);
		bus.setTaps(List.of(new LateReflectionBus.Tap(0,
			10.5 / SAMPLE_RATE, 1.0, 48_000.0)));
		float[][] input = buffers(64);
		float[][] outputs = buffers(64);
		input[0][0] = 1.0f;
		bus.process(input, 64, outputs);
		assertEquals(0.5, outputs[0][10], 1.0e-3);
		assertEquals(0.5, outputs[0][11], 1.0e-3);
	}

	@Test
	void clearResetsHistoryAndFilterState() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 0.010, 1.0, 1_000.0)));
		float[][] input = buffers(512);
		float[][] outputs = buffers(512);
		input[0][0] = 1.0f;
		for (int block = 0; block < 4; block++) {
			bus.process(input, 512, outputs);
			java.util.Arrays.fill(input[0], 0.0f);
		}
		bus.clear();
		for (int block = 0; block < 4; block++) {
			bus.process(input, 512, outputs);
			for (int channel = 0; channel < CHANNELS; channel++) {
				for (int frame = 0; frame < 512; frame++) {
					assertEquals(0.0f, outputs[channel][frame]);
				}
			}
		}
	}

	@Test
	void tapCapKeepsStrongest() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 2);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 0.0, 0.1, 48_000.0),
			new LateReflectionBus.Tap(0, 0.0, 0.2, 48_000.0),
			new LateReflectionBus.Tap(0, 0.0, 0.3, 48_000.0)));
		assertEquals(2, bus.tapCount());
		float[][] input = buffers(16);
		float[][] outputs = buffers(16);
		input[0][0] = 1.0f;
		bus.process(input, 16, outputs);
		assertEquals(0.5, outputs[0][0], 1.0e-5);
	}

	@Test
	void denseTapRenderStaysFiniteAndMeasuresRealtime() {
		int history = 8192;
		int tapCount = 418;
		int frames = 2048;
		int blocks = 100;
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, history,
			tapCount);
		Random random = new Random(23L);
		List<LateReflectionBus.Tap> taps = new ArrayList<>(tapCount);
		for (int i = 0; i < tapCount; i++) {
			double delay = 0.020 + random.nextDouble() * 0.120;
			double gain = 0.01 + random.nextDouble() * 0.09;
			int channel = random.nextInt(CHANNELS);
			taps.add(new LateReflectionBus.Tap(channel, delay, gain, 7_000.0));
		}
		bus.setTaps(taps);
		assertEquals(tapCount, bus.tapCount());
		float[][] input = buffers(frames);
		float[][] outputs = buffers(frames);
		Random noise = new Random(31L);
		double energy = 0.0;
		long startNanos = System.nanoTime();
		for (int block = 0; block < blocks; block++) {
			for (int channel = 0; channel < CHANNELS; channel++) {
				for (int frame = 0; frame < frames; frame++) {
					input[channel][frame] =
						(float) (noise.nextDouble() * 2.0 - 1.0) * 0.1f;
				}
			}
			bus.process(input, frames, outputs);
			for (int channel = 0; channel < CHANNELS; channel++) {
				for (int frame = 0; frame < frames; frame++) {
					float value = outputs[channel][frame];
					assertTrue(Float.isFinite(value),
						"dense output must be finite");
					energy += value * value;
				}
			}
		}
		long elapsedNanos = System.nanoTime() - startNanos;
		double elapsedMs = elapsedNanos / 1_000_000.0;
		double audioSeconds = blocks * (double) frames / SAMPLE_RATE;
		double realtime = audioSeconds / (elapsedNanos / 1_000_000_000.0);
		System.out.printf(Locale.ROOT,
			"LateReflectionBus dense 418 taps: %.2f ms for 100 blocks"
				+ " (%.1fx realtime)%n", elapsedMs, realtime);
		assertTrue(energy > 0.0, "dense render must produce output");
	}

	@Test
	void fadeInRampsAfterTapSwap() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4,
			0.010);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 0.0, 1.0, 48_000.0)));
		float[][] input = buffers(512);
		float[][] outputs = buffers(512);
		for (int frame = 0; frame < 512; frame++) {
			input[0][frame] = 1.0f;
		}
		bus.process(input, 512, outputs);
		assertEquals(0.0, outputs[0][0], 1.0e-6);
		assertEquals(0.5, outputs[0][240], 1.0e-3);
		assertEquals(1.0, outputs[0][511], 1.0e-3);
	}

	@Test
	void tapFromPathScalesGainAndDerivesDamping() {
		AcousticMaterial material = new AcousticMaterial("wood", 0.1, 0.2, 0.3, 0.1);
		ReflectionPathTap path = new ReflectionPathTap(2, "2:test", 2,
			new Point3(0.0, 0.0, 0.0),
			List.of(new Point3(1.0, 0.0, 0.0), new Point3(2.0, 0.0, 0.0)),
			List.of(material, material), 8.0, 0.020, 0.5,
			OcclusionResult.NONE);
		LateReflectionBus.Tap tap = LateReflectionBus.Tap.fromPath(path, 0.25);
		assertEquals(2, tap.channel());
		assertEquals(0.020, tap.delaySeconds(), 1.0e-9);
		assertEquals(0.125, tap.gain(), 1.0e-9);
		// 12000 - 6000 * 0.3 (mean high absorption) - 150 * 8 m = 9000 Hz.
		assertEquals(9_000.0, tap.dampingHz(), 1.0e-6);
	}

	@Test
	void multiOutputTapUsesEnergyNormalizedWeights() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4);
		bus.setTaps(List.of(new LateReflectionBus.Tap(0, 0.010, 1.0,
			48_000.0, new int[]{1, 2}, new float[]{0.6f, 0.8f})));
		float[][] input = buffers(1024);
		float[][] outputs = buffers(1024);
		input[0][0] = 1.0f;

		bus.process(input, 1024, outputs);

		assertEquals(0.6, outputs[1][480], 1.0e-4);
		assertEquals(0.8, outputs[2][480], 1.0e-4);
		assertEquals(0.0f, outputs[0][480]);
	}

	@Test
	void tapNormalizesWeightsAndDedupesChannels() {
		LateReflectionBus.Tap tap = new LateReflectionBus.Tap(0, 0.0, 1.0,
			7_000.0, new int[]{1, 1, 2}, new float[]{1.0f, 1.0f, 1.0f});

		assertEquals(2, tap.outputChannels().length);
		assertEquals(2.0 / Math.sqrt(5.0), tap.outputWeights()[0], 1.0e-5);
		assertEquals(1.0 / Math.sqrt(5.0), tap.outputWeights()[1], 1.0e-5);
	}

	@Test
	void tapFallsBackToInputChannelWithoutValidWeights() {
		LateReflectionBus.Tap tap = new LateReflectionBus.Tap(2, 0.0, 1.0,
			7_000.0, new int[0], new float[0]);

		assertEquals(1, tap.outputChannels().length);
		assertEquals(2, tap.outputChannels()[0]);
		assertEquals(1.0f, tap.outputWeights()[0], 1.0e-6);
	}

	@Test
	void tapEqualityIncludesVbapWeights() {
		LateReflectionBus.Tap first = new LateReflectionBus.Tap(0, 0.010, 1.0,
			7_000.0, new int[]{1, 2}, new float[]{0.6f, 0.8f});
		LateReflectionBus.Tap second = new LateReflectionBus.Tap(0, 0.010, 1.0,
			7_000.0, new int[]{1, 2}, new float[]{0.6f, 0.8f});
		LateReflectionBus.Tap different = new LateReflectionBus.Tap(0, 0.010,
			1.0, 7_000.0, new int[]{1, 2}, new float[]{0.8f, 0.6f});

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
		assertNotEquals(first, different);
	}

	@Test
	void tapFromPathCarriesVbapOutput() {
		AcousticMaterial material = new AcousticMaterial("wood", 0.1, 0.2, 0.3, 0.1);
		ReflectionPathTap path = new ReflectionPathTap(2, "2:test", 2,
			new Point3(0.0, 0.0, 0.0),
			List.of(new Point3(1.0, 0.0, 0.0), new Point3(2.0, 0.0, 0.0)),
			List.of(material, material), 8.0, 0.020, 0.5,
			OcclusionResult.NONE);

		LateReflectionBus.Tap tap = LateReflectionBus.Tap.fromPath(path, 0.25,
			new float[]{0.0f, 0.0f, 2.0f}, new int[]{4, 5},
			new float[]{0.7071f, 0.7071f});

		assertEquals(2, tap.channel());
		assertEquals(0.0f, tap.direction()[0], 1.0e-6);
		assertEquals(0.0f, tap.direction()[1], 1.0e-6);
		assertEquals(1.0f, tap.direction()[2], 1.0e-6);
		assertEquals(2, tap.outputChannels().length);
		assertEquals(4, tap.outputChannels()[0]);
		assertEquals(5, tap.outputChannels()[1]);
		assertEquals(0.7071f, tap.outputWeights()[0], 1.0e-4);
		assertEquals(0.7071f, tap.outputWeights()[1], 1.0e-4);
	}

	@Test
	void constructorRejectsNonPowerOfTwoHistory() {
		assertThrows(IllegalArgumentException.class,
			() -> new LateReflectionBus(SAMPLE_RATE, 1_000, 4));
	}

	@Test
	void setTapsSkipsTapsBeyondHistoryWindow() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 1024, 4);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 0.010, 1.0, 7_000.0),
			new LateReflectionBus.Tap(1, 0.030, 1.0, 7_000.0),
			new LateReflectionBus.Tap(2, 1024.0 / SAMPLE_RATE, 1.0, 7_000.0)));
		assertEquals(1, bus.tapCount());
	}

	@Test
	void longDelayInsideLargeHistoryIsAccepted() {
		LateReflectionBus bus = new LateReflectionBus(SAMPLE_RATE, 65_536, 4);
		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 1.20, 1.0, 7_000.0)));
		assertEquals(1, bus.tapCount());

		bus.setTaps(List.of(
			new LateReflectionBus.Tap(0, 1.20, 1.0, 7_000.0)), 0.60);
		assertEquals(0, bus.tapCount());
	}

	private static float[][] buffers(int frames) {
		return new float[CHANNELS][frames];
	}
}
