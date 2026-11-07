package com.example.cinema.client.audio.acoustics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class OcclusionResultTest {
	@Test
	void combineSumsAttenuationAndKeepsWorstShape() {
		OcclusionResult first = new OcclusionResult(10.0, 4_000.0, 1);
		OcclusionResult second = new OcclusionResult(15.0, 1_000.0, 3);

		OcclusionResult combined = OcclusionResult.combine(first, second);

		assertEquals(25.0, combined.attenuationDb(), 1.0e-9);
		assertEquals(1_000.0, combined.cutoffHz(), 1.0e-9);
		assertEquals(3, combined.order());
	}

	@Test
	void combineWithUnblockedLegKeepsBlockedShape() {
		OcclusionResult blocked = new OcclusionResult(8.0, 600.0, 2);

		OcclusionResult combined = OcclusionResult.combine(OcclusionResult.NONE,
			blocked);

		assertEquals(8.0, combined.attenuationDb(), 1.0e-9);
		assertEquals(600.0, combined.cutoffHz(), 1.0e-9);
		assertEquals(2, combined.order());
	}

	@Test
	void combineReturnsNoneForTwoUnblockedLegs() {
		assertSame(OcclusionResult.NONE,
			OcclusionResult.combine(null, null));
		assertSame(OcclusionResult.NONE,
			OcclusionResult.combine(OcclusionResult.NONE, OcclusionResult.NONE));
	}

	@Test
	void combineClampsTheSumAtTheDirectPathCap() {
		OcclusionResult combined = OcclusionResult.combine(
			new OcclusionResult(30.0, 500.0, 2),
			new OcclusionResult(30.0, 800.0, 4));

		assertEquals(40.0, combined.attenuationDb(), 1.0e-9);
		assertEquals(500.0, combined.cutoffHz(), 1.0e-9);
		assertEquals(4, combined.order());
	}
}
