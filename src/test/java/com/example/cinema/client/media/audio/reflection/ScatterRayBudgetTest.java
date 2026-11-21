package com.example.cinema.client.media.audio.reflection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScatterRayBudgetTest {
	private static final double EPS = 1.0e-9;

	@Test
	void lowScatteringUsesAggregateOnly() {
		ScatterRayBudget budget = new ScatterRayBudget();

		ScatterRayBudget.Budget value = budget.update("1:0", 95.0, 5.0, 3, 0L);

		assertEquals(0, value.rayPaths());
		assertEquals(1, value.maxRayPaths());
		assertEquals(0.0, value.directionalShare(), EPS);
	}

	@Test
	void midScatteringAddsDirectionalPaths() {
		ScatterRayBudget budget = new ScatterRayBudget();

		ScatterRayBudget.Budget value = budget.update("1:0", 65.0, 35.0, 3, 0L);

		assertEquals(2, value.rayPaths());
		assertEquals(3, value.maxRayPaths());
		assertTrue(value.directionalShare() > 0.3);
		assertTrue(value.directionalShare() <= 0.5);
	}

	@Test
	void highScatteringUsesMaximumPathsAndHalfShare() {
		ScatterRayBudget budget = new ScatterRayBudget();

		ScatterRayBudget.Budget value = budget.update("2:0", 50.0, 50.0, 2, 0L);

		assertEquals(2, value.rayPaths());
		assertEquals(2, value.maxRayPaths());
		assertEquals(0.5, value.directionalShare(), EPS);
	}

	@Test
	void smoothingMovesTowardTheNewTarget() {
		ScatterRayBudget budget = new ScatterRayBudget();
		budget.update("1:0", 50.0, 50.0, 3, 0L);

		ScatterRayBudget.Budget afterShort = budget.update("1:0", 100.0, 0.0,
			3, 100L);
		ScatterRayBudget.Budget afterLong = budget.update("1:0", 100.0, 0.0,
			3, 2_000L);

		assertTrue(afterShort.smoothedScatterFraction() > 0.0);
		assertTrue(afterLong.smoothedScatterFraction()
			< afterShort.smoothedScatterFraction());
		assertEquals(0, afterLong.rayPaths());
		assertEquals(1, afterLong.maxRayPaths());
	}
}
