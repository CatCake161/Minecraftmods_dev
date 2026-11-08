package com.example.cinema.client.audio.reverb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateReverbTargetTest {
	private static final double FOUR_PI = 4.0 * Math.PI;

	@Test
	void singleOmniSourceMatchesCriticalDistance() {
		double roomConstant = 100.0;
		double distance = 5.0;
		LateReverbTarget.Result result = LateReverbTarget.computeForRoomConstant(
			roomConstant, List.of(new LateReverbTarget.Source(1.0,
				Math.sqrt(FOUR_PI), distance)));

		double criticalDistance = Math.sqrt(roomConstant / (16.0 * Math.PI));
		double expectedDb = 20.0 * Math.log10(distance / criticalDistance);
		assertEquals(expectedDb, result.targetWetGainDb(), 1.0e-6);
		assertEquals(criticalDistance, result.criticalDistanceMeters(), 1.0e-9);
		assertEquals(20.0 * Math.log10(2.0 / Math.sqrt(roomConstant)),
			result.sourceGainDb(), 1.0e-6);
	}

	@Test
	void sourceGainTimesSourcePowerReproducesTheWetDirectTarget() {
		double roomConstant = 100.0;
		double distance = 5.0;
		LateReverbTarget.Result result = LateReverbTarget.computeForRoomConstant(
			roomConstant, List.of(new LateReverbTarget.Source(1.0,
				Math.sqrt(FOUR_PI), distance)));

		double sourceGain = Math.pow(10.0, result.sourceGainDb() / 20.0);
		double actualWetDirect = sourceGain * Math.sqrt(FOUR_PI)
			/ (1.0 / distance);
		double targetWetDirect = Math.pow(10.0,
			result.targetWetGainDb() / 20.0);

		assertEquals(targetWetDirect, actualWetDirect, 1.0e-9);
	}

	@Test
	void offAxisDirectivityRaisesTheWetRatio() {
		double roomConstant = 200.0;
		LateReverbTarget.Result onAxis = LateReverbTarget.computeForRoomConstant(
			roomConstant, List.of(new LateReverbTarget.Source(1.0,
				Math.sqrt(FOUR_PI), 4.0)));
		LateReverbTarget.Result offAxis = LateReverbTarget.computeForRoomConstant(
			roomConstant, List.of(new LateReverbTarget.Source(0.5,
				Math.sqrt(FOUR_PI), 4.0)));

		double difference = offAxis.targetWetGainDb()
			- onAxis.targetWetGainDb();
		assertEquals(20.0 * Math.log10(2.0), difference, 1.0e-6);
	}

	@Test
	void sourcePowerIsTakenFromTheOnAxisStrengthAndPowerIntegral() {
		double roomConstant = 150.0;
		LateReverbTarget.Source source = new LateReverbTarget.Source(0.8,
			Math.sqrt(4.0 * Math.PI), 6.0);
		LateReverbTarget.Result result = LateReverbTarget.computeForRoomConstant(
			roomConstant, List.of(source));

		double direct = 0.8 * 0.8 / (6.0 * 6.0);
		double power = 4.0 * Math.PI;
		double expected = 10.0 * Math.log10(4.0 * power
			/ (roomConstant * direct));
		assertEquals(expected, result.targetWetGainDb(), 1.0e-6);
	}

	@Test
	void invalidInputFallsBackSafely() {
		LateReverbTarget.Result empty = LateReverbTarget.computeForRoomConstant(
			100.0, List.of());
		LateReverbTarget.Result invalid = LateReverbTarget.computeForRoomConstant(
			Double.NaN, List.of(new LateReverbTarget.Source(1.0,
				Math.sqrt(FOUR_PI), 3.0)));

		assertEquals(LateReverbTarget.FALLBACK_TARGET_DB,
			empty.targetWetGainDb(), 1.0e-9);
		assertEquals(LateReverbTarget.FALLBACK_TARGET_DB,
			invalid.targetWetGainDb(), 1.0e-9);
		assertTrue(!invalid.hasRoomConstant());
	}
}
