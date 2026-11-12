package com.example.cinema.client.media.audio.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutputLimiterTest {
	private static final float EPS = 1.0e-6f;

	@Test
	void ceilingIsMinus03Dbfs() {
		assertEquals(Math.pow(10.0, -0.3 / 20.0),
			OutputLimiter.CEILING_LINEAR, 1.0e-6);
	}

	@Test
	void quietMixPassesThrough() {
		assertEquals(1.0f, OutputLimiter.gainFor(0.0f), EPS);
		assertEquals(1.0f, OutputLimiter.gainFor(0.5f), EPS);
		assertEquals(1.0f,
			OutputLimiter.gainFor(OutputLimiter.CEILING_LINEAR), EPS);
	}

	@Test
	void loudMixIsScaledToTheCeiling() {
		float predicted = 2.0f;
		float gain = OutputLimiter.gainFor(predicted);
		assertEquals(OutputLimiter.CEILING_LINEAR, predicted * gain, 1.0e-6f);
	}

	@Test
	void attackIsInstantAndReleaseIsSmooth() {
		assertEquals(0.5f, OutputLimiter.advance(1.0f, 0.5f, 0.005), EPS);
		float released = OutputLimiter.advance(0.5f, 1.0f, 0.005);
		assertTrue(released > 0.5f);
		assertTrue(released < 1.0f);
		assertEquals(1.0f, OutputLimiter.advance(0.5f, 1.0f, 10.0),
			1.0e-4f);
	}

	@Test
	void reductionDbIsPositive() {
		assertEquals(0.0f, OutputLimiter.reductionDb(1.0f), EPS);
		assertEquals(6.0206f, OutputLimiter.reductionDb(0.5f), 1.0e-3f);
		assertEquals(0.0f, OutputLimiter.reductionDb(0.0f), EPS);
	}

	@Test
	void blockPeakHonoursTheUploadFormat() {
		float[] samples = {0.2f, -1.5f, 0.7f};
		assertEquals(1.5f, OutputLimiter.blockPeak(samples, 3, false), EPS);
		assertEquals(1.0f, OutputLimiter.blockPeak(samples, 3, true), EPS);
		assertEquals(0.0f,
			OutputLimiter.blockPeak(new float[]{Float.NaN}, 1, false), EPS);
	}
}
