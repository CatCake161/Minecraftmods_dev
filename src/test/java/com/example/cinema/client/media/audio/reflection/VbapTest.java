package com.example.cinema.client.media.audio.reflection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VbapTest {
	private static final float EPS = 1.0e-4f;

	@Test
	void frontDirectionUsesFrontCenterNotRear() {
		Vbap.Weights weights = Vbap.pan(new float[]{0.0f, 0.0f, -1.0f},
			layout());

		assertTrue(contains(weights, 2), "front should use FC");
		assertFalse(contains(weights, 4), "front must not use BL");
		assertFalse(contains(weights, 5), "front must not use BR");
	}

	@Test
	void rearDirectionUsesRearPairNotFront() {
		Vbap.Weights weights = Vbap.pan(new float[]{0.0f, 0.0f, 1.0f},
			layout());

		assertTrue(contains(weights, 4), "rear should use BL");
		assertTrue(contains(weights, 5), "rear should use BR");
		assertFalse(contains(weights, 0), "rear must not use FL");
		assertFalse(contains(weights, 1), "rear must not use FR");
		assertFalse(contains(weights, 2), "rear must not use FC");
		assertEquals(0.7071f, weight(weights, 4), EPS);
		assertEquals(0.7071f, weight(weights, 5), EPS);
	}

	@Test
	void leftAndRightKeepTheirSign() {
		Vbap.Weights left = Vbap.pan(
			new float[]{-0.7071f, 0.0f, -0.7071f}, layout());
		Vbap.Weights right = Vbap.pan(
			new float[]{0.7071f, 0.0f, -0.7071f}, layout());

		assertTrue(contains(left, 0), "listener-left should use FL");
		assertFalse(contains(left, 1), "listener-left must not use FR");
		assertTrue(contains(right, 1), "listener-right should use FR");
		assertFalse(contains(right, 0), "listener-right must not use FL");
	}

	@Test
	void horizontalPairInterpolatesAndStaysNormalized() {
		double azimuth = Math.toRadians(22.5);
		float[] direction = {(float) Math.sin(azimuth), 0.0f,
			(float) -Math.cos(azimuth)};
		Vbap.Weights weights = Vbap.pan(direction, layout());

		assertTrue(contains(weights, 2), "pair should include FC");
		assertTrue(contains(weights, 1), "pair should include FR");
		assertEquals(1.0, energy(weights), EPS);
	}

	@Test
	void exact3dUsesHeightSpeaker() {
		float[][] positions = new float[6][];
		positions[0] = new float[]{0.0f, 1.0f, 0.0f};
		positions[1] = new float[]{0.7071f, 0.0f, -0.7071f};
		positions[2] = new float[]{-0.7071f, 0.0f, -0.7071f};

		Vbap.Weights weights = Vbap.pan(
			new float[]{0.0f, 1.0f, -1.0f}, positions);

		assertTrue(contains(weights, 0), "3D should use the height speaker");
		assertTrue(contains(weights, 1), "3D should use the right speaker");
		assertTrue(contains(weights, 2), "3D should use the left speaker");
		assertEquals(1.0, energy(weights), EPS);
	}

	@Test
	void lfeIsNeverAnOutputTarget() {
		float[][] positions = layout();
		float[][] directions = {
			{0.0f, 0.0f, -1.0f},
			{0.0f, 0.0f, 1.0f},
			{-1.0f, 0.0f, 0.0f},
			{1.0f, 0.0f, 0.0f},
			{0.0f, 1.0f, 0.0f},
		};
		for (float[] direction : directions) {
			Vbap.Weights weights = Vbap.pan(direction, positions);
			assertFalse(contains(weights, Vbap.LFE_CHANNEL),
				"LFE must not be a VBAP output target");
		}

		float[][] onlyLfe = new float[6][];
		onlyLfe[Vbap.LFE_CHANNEL] = new float[]{0.0f, 0.0f, -1.0f};
		assertTrue(Vbap.pan(new float[]{0.0f, 0.0f, -1.0f}, onlyLfe)
			.isEmpty(), "LFE-only layout has no VBAP target");
	}

	@Test
	void weightsAreEnergyNormalized() {
		float[][] directions = {
			{0.0f, 0.0f, -1.0f},
			{0.0f, 0.0f, 1.0f},
			{-0.7071f, 0.0f, -0.7071f},
			{0.7071f, 0.0f, -0.7071f},
			{0.0f, 1.0f, 0.0f},
			{0.0f, 0.0f, 0.0f},
		};
		for (float[] direction : directions) {
			Vbap.Weights weights = Vbap.pan(direction, layout());
			assertFalse(weights.isEmpty());
			assertEquals(1.0, energy(weights), EPS);
		}
	}

	@Test
	void zeroDirectionFallsBackToDiffuse() {
		Vbap.Weights weights = Vbap.pan(new float[]{0.0f, 0.0f, 0.0f},
			layout());

		assertEquals(5, weights.channels().length,
			"diffuse should use all non-LFE speakers");
		float expected = (float) (1.0 / Math.sqrt(5.0));
		for (int channel = 0; channel < 6; channel++) {
			if (channel == Vbap.LFE_CHANNEL) {
				assertFalse(contains(weights, channel));
			} else {
				assertEquals(expected, weight(weights, channel), EPS);
			}
		}
	}

	@Test
	void verticalDirectionFallsBackToNearestSpeaker() {
		Vbap.Weights weights = Vbap.pan(new float[]{0.0f, 1.0f, 0.0f},
			layout());

		assertEquals(1, weights.channels().length);
		assertEquals(1.0f, weights.weights()[0], EPS);
	}

	@Test
	void noSpeakersReturnsEmpty() {
		assertTrue(Vbap.pan(new float[]{0.0f, 0.0f, -1.0f},
			new float[6][]).isEmpty());
		assertTrue(Vbap.pan(new float[]{0.0f, 0.0f, -1.0f}, null).isEmpty());
	}

	@Test
	void singleSpeakerIsUsedDirectly() {
		float[][] positions = new float[6][];
		positions[2] = new float[]{0.0f, 0.0f, -1.0f};

		Vbap.Weights weights = Vbap.pan(
			new float[]{0.0f, 0.0f, -1.0f}, positions);

		assertEquals(1, weights.channels().length);
		assertEquals(2, weights.channels()[0]);
		assertEquals(1.0f, weights.weights()[0], EPS);
	}

	private static float[][] layout() {
		float[][] positions = new float[6][];
		positions[0] = new float[]{-0.7071f, 0.0f, -0.7071f};
		positions[1] = new float[]{0.7071f, 0.0f, -0.7071f};
		positions[2] = new float[]{0.0f, 0.0f, -1.0f};
		positions[3] = new float[]{0.0f, 0.0f, -1.0f};
		positions[4] = new float[]{-0.7071f, 0.0f, 0.7071f};
		positions[5] = new float[]{0.7071f, 0.0f, 0.7071f};
		return positions;
	}

	private static boolean contains(Vbap.Weights weights, int channel) {
		for (int value : weights.channels()) {
			if (value == channel) {
				return true;
			}
		}
		return false;
	}

	private static float weight(Vbap.Weights weights, int channel) {
		for (int i = 0; i < weights.channels().length; i++) {
			if (weights.channels()[i] == channel) {
				return weights.weights()[i];
			}
		}
		return 0.0f;
	}

	private static double energy(Vbap.Weights weights) {
		double sum = 0.0;
		for (float value : weights.weights()) {
			sum += value * value;
		}
		return sum;
	}
}
