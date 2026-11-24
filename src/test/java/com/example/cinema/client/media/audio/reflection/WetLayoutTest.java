package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.acousticfir.MaterialReflectionFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WetLayoutTest {
	private static final float EPS = 1.0e-4f;
	private static final int[] CHANNELS = {0, 1, 2, 3, 4, 5};

	@Test
	void lfeWetPointExistsButIsNotAVbapTarget() {
		WetLayout.Builder builder = new WetLayout.Builder();
		WetLayout.Layout layout = builder.build(
			List.of(tap(1, 0.0f, 1.0f, 0.0f, 1.0)),
			layout(), CHANNELS, 0L);
		int lfe = layout.lfeIndex();

		assertEquals(5, lfe);
		assertEquals(WetLayout.Kind.LFE,
			layout.points().get(lfe).kind());
		Vbap.Weights weights = layout.pan(new float[]{0.0f, 0.0f,
			-1.0f}, true);
		for (int channel : weights.channels()) {
			assertTrue(channel != lfe,
				"LFE must not be a directional VBAP target");
		}
	}

	@Test
	void multipleLfeSpeakersBecomeSeparateWetPoints() {
		WetLayout.Builder builder = new WetLayout.Builder();
		float[][] speakers = {
			{-0.7071f, 0.0f, -0.7071f},
			{0.7071f, 0.0f, -0.7071f},
			{0.0f, 0.0f, -1.0f},
			{-0.7071f, 0.0f, 0.7071f},
			{0.7071f, 0.0f, 0.7071f},
			{-0.5f, 0.0f, -0.8f},
			{0.5f, 0.0f, -0.8f},
		};
		int[] channels = {0, 1, 2, 4, 5, 3, 3};

		WetLayout.Layout layout = builder.build(
			List.of(tap(1, 0.0f, 1.0f, 0.0f, 1.0)),
			speakers, channels, 0L);

		assertEquals(2, layout.lfeIndices().length);
		assertEquals(WetLayout.Kind.LFE,
			layout.points().get(layout.lfeIndices()[0]).kind());
		assertEquals(WetLayout.Kind.LFE,
			layout.points().get(layout.lfeIndices()[1]).kind());
		Vbap.Weights weights = layout.pan(new float[]{0.0f, 0.0f,
			-1.0f}, true);
		for (int channel : weights.channels()) {
			assertFalse(layout.isLfeIndex(channel),
				"LFE points must not be VBAP targets");
		}
	}

	@Test
	void ghostStaysWorldFixedWhenListenerTurns() {
		WetLayout.Builder builder = new WetLayout.Builder();
		float[][] speakers = layout();
		WetLayout.Frame facingNorth = WetLayout.Frame.identity();
		WetLayout.Frame facingEast = new WetLayout.Frame(
			new float[3], new float[]{0.0f, 0.0f, 1.0f},
			new float[]{0.0f, 1.0f, 0.0f},
			new float[]{1.0f, 0.0f, 0.0f});

		// Tap directions are world space, so the same tap must produce the same
		// world ghost even when the listener frame rotated.
		builder.build(List.of(tap(1, 1.0f, 0.0f, 0.0f, 1.0)),
			speakers, CHANNELS, facingNorth, 0L);
		WetLayout.Layout turned = builder.build(
			List.of(tap(1, 1.0f, 0.0f, 0.0f, 1.0)), speakers, CHANNELS,
			facingEast, 200L);

		float[] world = turned.worldPositions()[6];
		assertTrue(world[0] > 0.9f, "ghost must stay east of the listener");
		assertTrue(Math.abs(world[2]) < 0.35f,
			"ghost must not rotate with the listener");
	}

	@Test
	void debugPointsExposeOrderKindAndActiveState() {
		WetLayout.Builder builder = new WetLayout.Builder();
		WetLayout.Layout layout = builder.build(
			List.of(tap(2, 0.0f, 1.0f, 0.0f, 1.0)),
			layout(), CHANNELS, 0L);
		List<WetLayout.Point> points = layout.points();

		assertEquals(WetLayout.MAX_POINTS, points.size());
		assertTrue(points.get(0).active());
		assertEquals(WetLayout.Kind.SPEAKER, points.get(0).kind());
		assertEquals(WetLayout.Kind.LFE, points.get(5).kind());
		assertEquals(WetLayout.Kind.GHOST, points.get(6).kind());
		assertEquals(2, points.get(6).order());
		assertTrue(points.get(6).active());
		assertFalse(points.get(7).active(),
			"unused ghost slot should be inactive");
	}

	@Test
	void wetPointLimitMatchesBusOutputLimit() {
		assertEquals(LateReflectionBus.MAX_OUTPUT_CHANNELS,
			WetLayout.MAX_POINTS);
	}

	@Test
	void physicalSpeakersBecomeStablePoints() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(
			List.of(tap(0, 0.0f, 0.0f, -1.0f, 1.0)),
			layout(), CHANNELS, 0L);

		assertEquals(WetLayout.MAX_POINTS, layout.pointCount());
		assertEquals(5, layout.physicalCount(),
			"LFE must not become a wet point");
		assertTrue(layout.activeCount() >= 5);
	}

	@Test
	void ghostPointFollowsReflectionDirection() {
		WetLayout.Builder builder = new WetLayout.Builder();
		float[] direction = normalize(0.0f, 1.0f, -0.3f);

		WetLayout.Layout layout = builder.build(
			List.of(tap(1, direction[0], direction[1], direction[2], 1.0)),
			layout(), CHANNELS, 0L);

		assertEquals(6, layout.activeCount(),
			"five physical points plus one order-1 ghost");
		Vbap.Weights weights = layout.pan(direction, true);
		assertFalse(weights.isEmpty());
		assertTrue(weights.channels()[0] >= 5,
			"exact ghost direction should win");
		assertEquals(1.0, energy(weights), EPS);
	}

	@Test
	void separateDirectionsCreateSeparateGhosts() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(List.of(
			tap(1, 0.0f, 0.0f, 1.0f, 1.0),
			tap(1, 1.0f, 0.0f, 0.0f, 1.0)),
			layout(), CHANNELS, 0L);

		assertEquals(7, layout.activeCount());
	}

	@Test
	void eachOrderGetsAtLeastOneGhost() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(List.of(
			tap(1, 0.0f, 1.0f, 0.0f, 1.0),
			tap(2, 1.0f, 0.0f, 0.0f, 1.0),
			tap(3, -1.0f, 0.0f, 0.0f, 1.0),
			tap(4, 0.0f, 0.0f, 1.0f, 1.0)),
			layout(), CHANNELS, 0L);

		assertEquals(9, layout.activeCount(),
			"five physical points plus four order ghosts");
	}

	@Test
	void pointCountNeverExceedsMaximum() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(List.of(
			tap(1, 0.0f, 1.0f, 0.0f, 1.0),
			tap(1, 1.0f, 1.0f, 0.0f, 1.0),
			tap(2, -1.0f, 1.0f, 0.0f, 1.0),
			tap(3, 0.0f, 1.0f, 1.0f, 1.0),
			tap(4, 0.0f, 1.0f, -1.0f, 1.0)),
			layout(), CHANNELS, 0L);

		assertTrue(layout.pointCount() <= WetLayout.MAX_POINTS);
		assertTrue(layout.activeCount() <= WetLayout.MAX_POINTS);
	}

	@Test
	void diffusePanStaysOnPhysicalSpeakers() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(
			List.of(tap(2, 0.0f, 1.0f, 0.0f, 1.0)),
			layout(), CHANNELS, 0L);
		Vbap.Weights weights = layout.pan(new float[3], false);

		assertFalse(weights.isEmpty());
		for (int channel : weights.channels()) {
			assertTrue(channel < layout.physicalCount(),
				"diffuse scatter must not target ghosts");
		}
	}

	@Test
	void ghostDirectionIsSmoothedAndKeepsItsSlot() {
		WetLayout.Builder builder = new WetLayout.Builder();
		builder.build(List.of(tap(2, 0.0f, 1.0f, 0.0f, 1.0)),
			layout(), CHANNELS, 0L);
		float[] moved = normalize(0.2588f, 0.9659f, 0.0f);

		WetLayout.Layout layout = builder.build(
			List.of(tap(2, moved[0], moved[1], moved[2], 1.0)),
			layout(), CHANNELS, 200L);

		float[] position = normalize(layout.positions()[6][0],
			layout.positions()[6][1], layout.positions()[6][2]);
		assertTrue(position[0] > 0.02f && position[0] < 0.23f,
			"ghost direction must move toward the new cluster, not jump");
		assertTrue(position[1] > 0.95f);
		assertEquals(6, layout.activeCount());
	}

	@Test
	void silentGhostIsDeactivatedAfterHold() {
		WetLayout.Builder builder = new WetLayout.Builder();
		float[][] speakers = layout();
		builder.build(List.of(tap(2, 0.0f, 1.0f, 0.0f, 1.0)),
			speakers, CHANNELS, 0L);

		WetLayout.Layout held = builder.build(
			List.of(tap(0, 0.0f, 0.0f, -1.0f, 1.0)),
			speakers, CHANNELS, 300L);
		WetLayout.Layout released = builder.build(
			List.of(tap(0, 0.0f, 0.0f, -1.0f, 1.0)),
			speakers, CHANNELS, 2_000L);

		assertEquals(6, held.activeCount(), "ghost is held briefly");
		assertEquals(5, released.activeCount(),
			"ghost is released after the hold window");
	}

	@Test
	void noTapsProducesEmptyLayout() {
		WetLayout.Builder builder = new WetLayout.Builder();

		WetLayout.Layout layout = builder.build(List.of(), layout(),
			CHANNELS, 0L);

		assertEquals(0, layout.pointCount());
		assertEquals(0, layout.activeCount());
	}

	private static LateReflectionBus.Tap tap(int order, float x, float y,
											 float z, double gain) {
		float[] direction = normalize(x, y, z);
		return new LateReflectionBus.Tap(0, 0.01, gain, 7_000.0,
			direction, new int[]{0}, new float[]{1.0f}, "tap:" + order,
			MaterialReflectionFilter.FLAT, order);
	}

	private static float[] normalize(float x, float y, float z) {
		double length = Math.sqrt(x * x + y * y + z * z);
		if (length < 1.0e-9) {
			return new float[3];
		}
		return new float[]{(float) (x / length), (float) (y / length),
			(float) (z / length)};
	}

	private static float[][] layout() {
		return new float[][]{
			{-0.7071f, 0.0f, -0.7071f},
			{0.7071f, 0.0f, -0.7071f},
			{0.0f, 0.0f, -1.0f},
			{0.0f, 0.0f, -1.0f},
			{-0.7071f, 0.0f, 0.7071f},
			{0.7071f, 0.0f, 0.7071f},
		};
	}

	private static double energy(Vbap.Weights weights) {
		double sum = 0.0;
		for (float value : weights.weights()) {
			sum += value * value;
		}
		return sum;
	}
}
