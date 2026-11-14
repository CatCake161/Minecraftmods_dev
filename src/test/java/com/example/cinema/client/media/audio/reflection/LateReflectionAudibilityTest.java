package com.example.cinema.client.media.audio.reflection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateReflectionAudibilityTest {
	private static final double EPS = 1.0e-9;

	@Test
	void directPowerSumsSquaresAndIgnoresInvalidAmplitudes() {
		assertEquals(0.09 + 0.16 + 0.25,
			LateReflectionAudibility.directPower(
				new double[]{0.3, 0.4, 0.5, -1.0, Double.NaN}),
			1.0e-12);
		assertEquals(0.0, LateReflectionAudibility.directPower(null));
	}

	@Test
	void sixtyDbFloorIsDirectLevelMinusSixty() {
		double directAmplitude = Math.pow(10.0, -12.0 / 20.0);
		double directPower = directAmplitude * directAmplitude;
		double floorPower = LateReflectionAudibility.floorPower(directPower,
			LateReflectionAudibility.DEFAULT_FLOOR_DB);
		assertEquals(-72.0, LateReflectionAudibility.amplitudeDb(
			Math.sqrt(floorPower)), 1.0e-6);
	}

	@Test
	void floorIsRelativeToDirectNotAbsolute() {
		double quietDirect = Math.pow(10.0, -30.0 / 20.0);
		double loudDirect = Math.pow(10.0, -6.0 / 20.0);
		double quietFloor = LateReflectionAudibility.floorPower(
			quietDirect * quietDirect, -60.0);
		double loudFloor = LateReflectionAudibility.floorPower(
			loudDirect * loudDirect, -60.0);
		assertEquals(-90.0, LateReflectionAudibility.amplitudeDb(
			Math.sqrt(quietFloor)), 1.0e-6);
		assertEquals(-66.0, LateReflectionAudibility.amplitudeDb(
			Math.sqrt(loudFloor)), 1.0e-6);
	}

	@Test
	void audibleUsesThePowerFloor() {
		double floorPower = LateReflectionAudibility.floorPower(1.0, -60.0);
		double boundary = Math.sqrt(floorPower);
		assertTrue(LateReflectionAudibility.audible(boundary * 1.0001,
			floorPower));
		assertFalse(LateReflectionAudibility.audible(boundary * 0.9999,
			floorPower));
		assertFalse(LateReflectionAudibility.audible(0.0, floorPower));
		assertFalse(LateReflectionAudibility.audible(1.0,
			Double.POSITIVE_INFINITY));
	}

	@Test
	void maxDelayFollowsRt60AndFloor() {
		assertEquals(0.8, LateReflectionAudibility.maxDelaySeconds(
			0.8, -60.0, 1.3), EPS);
		assertEquals(0.8 * 40.0 / 60.0, LateReflectionAudibility.maxDelaySeconds(
			0.8, -40.0, 1.3), EPS);
		assertEquals(0.5, LateReflectionAudibility.maxDelaySeconds(
			0.8, -60.0, 0.5), EPS);
		assertEquals(1.3, LateReflectionAudibility.maxDelaySeconds(
			0.0, -60.0, 1.3), EPS);
	}

	@Test
	void noDirectSoundRejectsAllTaps() {
		assertEquals(Double.POSITIVE_INFINITY,
			LateReflectionAudibility.floorPower(0.0, -60.0));
		assertFalse(LateReflectionAudibility.audible(1.0,
			LateReflectionAudibility.floorPower(0.0, -60.0)));
	}
}
