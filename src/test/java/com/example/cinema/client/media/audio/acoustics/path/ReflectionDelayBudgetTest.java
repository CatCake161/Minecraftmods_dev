package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReflectionDelayBudgetTest {
	private static final double EPS = 1.0e-9;

	@Test
	void largeRoomBudgetFollowsRoomDiagonalAndOrder() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO,
			new Vec3(48.0, 48.0, 48.0), List.of(), true, "");
		double diagonal = Math.sqrt(3.0) * 48.0;
		ReflectionDelayLimit limit = ReflectionDelayBudget.forRoom(room,
			1.30, 1.3226666666666667);

		assertEquals(diagonal, room.diagonal(), EPS);
		assertEquals(3.0 * diagonal / 343.0, limit.maxDelaySeconds(2), EPS);
		assertEquals(4.0 * diagonal / 343.0, limit.maxDelaySeconds(3), EPS);
		assertEquals(5.0 * diagonal / 343.0, limit.maxDelaySeconds(4), EPS);
		// Fifth order would exceed the hard cap.
		assertEquals(1.30, limit.maxDelaySeconds(5), EPS);
	}

	@Test
	void smallRoomBudgetIsTighterThanTheHardCap() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO,
			new Vec3(5.0, 5.0, 5.0), List.of(), true, "");
		ReflectionDelayLimit limit = ReflectionDelayBudget.forRoom(room,
			1.30, 1.3226666666666667);

		assertEquals(5.0 * room.diagonal() / 343.0,
			limit.maxDelaySeconds(4), EPS);
	}

	@Test
	void historyCapacityAndHardCapBothClamp() {
		ReflectionDelayBudget historyBound =
			new ReflectionDelayBudget(1_000.0, 2.0, 0.5);
		ReflectionDelayBudget hardBound =
			new ReflectionDelayBudget(83.13843876330611, 1.30, 10.0);

		assertEquals(0.5, historyBound.maxDelaySeconds(4), EPS);
		assertEquals(1.30, hardBound.maxDelaySeconds(5), EPS);
	}

	@Test
	void missingRoomFallsBackToTheCombinedCap() {
		ReflectionDelayLimit limit = ReflectionDelayBudget.forRoom(null,
			1.30, 1.3226666666666667);

		assertEquals(1.30, limit.maxDelaySeconds(1), EPS);
		assertEquals(1.30, limit.maxDelaySeconds(4), EPS);
	}
}
