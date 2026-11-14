package com.example.cinema.client.media.audio.acoustics.path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectPropagationDelayTest {
	private static final double EPS = 1.0e-12;
	private static final double C = DirectPropagationDelay.SPEED_OF_SOUND;

	@Test
	void nearestSpeakerHasZeroDelay() {
		assertEquals(0.0, DirectPropagationDelay.seconds(4.0, 4.0, C, 10.0),
			EPS);
	}

	@Test
	void extraFlightTimeUsesMetresPerSecond() {
		assertEquals(0.01, DirectPropagationDelay.seconds(7.43, 4.0, C, 10.0),
			1.0e-6);
	}

	@Test
	void fartherReferenceAndInvalidValuesClampToZero() {
		assertEquals(0.0, DirectPropagationDelay.seconds(3.0, 4.0, C, 10.0),
			EPS);
		assertEquals(0.0, DirectPropagationDelay.seconds(Double.NaN, 4.0, C,
			10.0), EPS);
		assertEquals(0.0, DirectPropagationDelay.seconds(4.0, Double.NaN, C,
			10.0), EPS);
	}

	@Test
	void delayIsCappedByTheHistoryBudget() {
		assertEquals(0.25, DirectPropagationDelay.seconds(200.0, 0.0, C, 0.25),
			EPS);
	}

	@Test
	void reflectionOffsetKeepsTheSameRelativeTiming() {
		double pathLength = 10.0;
		double speakerDistance = 6.0;
		double minDistance = 4.0;
		double reflection = (pathLength - speakerDistance) / C;
		double direct = DirectPropagationDelay.seconds(speakerDistance,
			minDistance, C, 10.0);
		double shiftedReflection = reflection + direct;
		double commonReference = (pathLength - minDistance) / C;
		assertEquals(commonReference, shiftedReflection, EPS);
	}
}
