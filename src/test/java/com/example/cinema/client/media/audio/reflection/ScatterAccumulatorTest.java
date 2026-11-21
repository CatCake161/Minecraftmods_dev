package com.example.cinema.client.media.audio.reflection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScatterAccumulatorTest {
	private static final double EPS = 1.0e-9;

	@Test
	void aggregatesEnergyWeightedDelayAndDampingPerChannel() {
		ScatterAccumulator accumulator = new ScatterAccumulator();
		accumulator.add(0, 1.0, 0.010, 8_000.0);
		accumulator.add(0, 3.0, 0.030, 4_000.0);
		accumulator.add(1, 2.0, 0.020, 6_000.0);

		List<LateReflectionBus.Tap> taps = accumulator.build(
			speakerPositions(), new int[]{0, 1, 4, 5}, "scatter:");

		assertEquals(2, taps.size());
		assertEquals(0, taps.get(0).channel());
		assertEquals(2.0, taps.get(0).gain(), EPS);
		assertEquals(0.025, taps.get(0).delaySeconds(), EPS);
		assertEquals(5_000.0, taps.get(0).dampingHz(), EPS);
		assertEquals("scatter:0", taps.get(0).key());
		assertEquals(0.0f, taps.get(0).direction()[0], EPS);
		assertEquals(0.0f, taps.get(0).direction()[1], EPS);
		assertEquals(0.0f, taps.get(0).direction()[2], EPS);
		assertEquals(4, taps.get(0).outputChannels().length);
		float weightEnergy = 0.0f;
		for (float weight : taps.get(0).outputWeights()) {
			weightEnergy += weight * weight;
		}
		assertEquals(1.0f, weightEnergy, 1.0e-6f);

		assertEquals(1, taps.get(1).channel());
		assertEquals(Math.sqrt(2.0), taps.get(1).gain(), EPS);
		assertEquals(0.020, taps.get(1).delaySeconds(), EPS);
		assertEquals(6_000.0, taps.get(1).dampingHz(), EPS);
		assertEquals("scatter:1", taps.get(1).key());
	}

	@Test
	void emptyOrInvalidContributionsProduceNoTaps() {
		ScatterAccumulator accumulator = new ScatterAccumulator();
		accumulator.add(-1, 1.0, 0.01, 5_000.0);
		accumulator.add(0, Double.NaN, 0.01, 5_000.0);
		accumulator.add(0, -1.0, 0.01, 5_000.0);

		assertTrue(accumulator.isEmpty());
		assertEquals(0.0, accumulator.totalEnergy(), EPS);
		assertTrue(accumulator.build(speakerPositions(),
			new int[]{0, 1, 4, 5}, "scatter:").isEmpty());
	}

	@Test
	void noSpeakersFallsBackToSourceChannel() {
		ScatterAccumulator accumulator = new ScatterAccumulator();
		accumulator.add(3, 1.0, 0.010, 5_000.0);

		List<LateReflectionBus.Tap> taps = accumulator.build(null, null,
			"scatter:");

		assertEquals(1, taps.size());
		assertEquals(3, taps.get(0).channel());
		assertEquals(1.0f, taps.get(0).outputWeights()[0], 1.0e-6f);
	}

	private static float[][] speakerPositions() {
		return new float[][]{
			{1.0f, 0.0f, -1.0f},
			{-1.0f, 0.0f, -1.0f},
			{0.0f, 0.0f, -1.0f},
			{0.0f, 0.0f, 1.0f},
		};
	}
}
