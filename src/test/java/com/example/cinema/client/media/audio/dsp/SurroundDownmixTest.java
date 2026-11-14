package com.example.cinema.client.media.audio.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurroundDownmixTest {
	private static final double EPSILON = 1.0e-6;

	@Test
	void keepsFrontLeftAndFrontRightUnchanged() {
		float[] stereo = SurroundDownmix.toStereo(
			new float[] {0.5f, -0.25f, 0.0f, 0.0f, 0.0f, 0.0f}, 1);
		assertEquals(0.5, stereo[0], EPSILON);
		assertEquals(-0.25, stereo[1], EPSILON);
	}

	@Test
	void foldsCenterAndSurroundAtMinusThreeDb() {
		float[] stereo = SurroundDownmix.toStereo(
			new float[] {0.1f, -0.2f, 0.3f, 0.4f, 0.5f, -0.6f}, 1);
		double left = 0.1 + Math.sqrt(0.5) * 0.3 + Math.sqrt(0.5) * 0.5;
		double right = -0.2 + Math.sqrt(0.5) * 0.3 - Math.sqrt(0.5) * 0.6;
		assertEquals(left, stereo[0], EPSILON);
		assertEquals(right, stereo[1], EPSILON);
	}

	@Test
	void dropsLfeByDefault() {
		float[] withLfe = SurroundDownmix.toStereo(
			new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f}, 1);
		assertEquals(0.0, withLfe[0], EPSILON);
		assertEquals(0.0, withLfe[1], EPSILON);
	}

	@Test
	void optionalLfeGainFeedsBothChannels() {
		float[] stereo = SurroundDownmix.toStereo(
			new float[] {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f}, 1,
			SurroundDownmix.CENTER_GAIN, SurroundDownmix.SURROUND_GAIN, 0.5);
		assertEquals(0.5, stereo[0], EPSILON);
		assertEquals(0.5, stereo[1], EPSILON);
	}

	@Test
	void doesNotClamp() {
		float[] stereo = SurroundDownmix.toStereo(
			new float[] {1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 1.0f}, 1);
		assertTrue(stereo[0] > 1.0f);
		assertTrue(stereo[1] > 1.0f);
		assertEquals(1.0 + 2.0 * Math.sqrt(0.5), stereo[0], EPSILON);
		assertEquals(1.0 + 2.0 * Math.sqrt(0.5), stereo[1], EPSILON);
	}

	@Test
	void processesFramesInOrder() {
		float[] stereo = SurroundDownmix.toStereo(
			new float[] {
				1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
				0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f,
			}, 2);
		assertEquals(1.0, stereo[0], EPSILON);
		assertEquals(0.0, stereo[1], EPSILON);
		assertEquals(0.0, stereo[2], EPSILON);
		assertEquals(Math.sqrt(0.5), stereo[3], EPSILON);
	}

	@Test
	void zeroFramesReturnsEmptyOutput() {
		assertEquals(0, SurroundDownmix.toStereo(new float[0], 0).length);
	}

	@Test
	void rejectsShortInput() {
		assertThrows(IllegalArgumentException.class,
			() -> SurroundDownmix.toStereo(new float[5], 1));
	}

	@Test
	void rejectsNegativeFrameCount() {
		assertThrows(IllegalArgumentException.class,
			() -> SurroundDownmix.toStereo(new float[6], -1));
	}

	@Test
	void rejectsInvalidGains() {
		float[] input = new float[6];
		assertThrows(IllegalArgumentException.class,
			() -> SurroundDownmix.toStereo(input, 1, -0.1, 0.7, 0.0));
		assertThrows(IllegalArgumentException.class,
			() -> SurroundDownmix.toStereo(input, 1, 0.7, Double.NaN, 0.0));
		assertThrows(IllegalArgumentException.class,
			() -> SurroundDownmix.toStereo(input, 1, 0.7, 0.7, Double.POSITIVE_INFINITY));
	}
}
