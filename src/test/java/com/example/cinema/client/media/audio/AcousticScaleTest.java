package com.example.cinema.client.media.audio;

import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayBudget;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayLimit;
import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AcousticScaleTest {
	private static final double EPS = 1.0e-9;

	@Test
	void convertsLengthAreaAndVolume() {
		AcousticScale.setMetersPerBlock(0.5);
		try {
			assertEquals(1.0, AcousticScale.blocksToMeters(2.0), EPS);
			assertEquals(2.0, AcousticScale.metersToBlocks(1.0), EPS);
			assertEquals(3.0,
				AcousticScale.squareBlocksToSquareMeters(12.0), EPS);
			assertEquals(2.0,
				AcousticScale.cubicBlocksToCubicMeters(16.0), EPS);
		} finally {
			AcousticScale.setMetersPerBlock(
				AcousticScale.DEFAULT_METERS_PER_BLOCK);
		}
	}

	@Test
	void clampsToTheConfiguredRange() {
		try {
			AcousticScale.setMetersPerBlock(0.1);
			assertEquals(AcousticScale.MIN_METERS_PER_BLOCK,
				AcousticScale.metersPerBlock(), EPS);
			AcousticScale.setMetersPerBlock(3.0);
			assertEquals(AcousticScale.MAX_METERS_PER_BLOCK,
				AcousticScale.metersPerBlock(), EPS);
			AcousticScale.setMetersPerBlock(Double.NaN);
			assertEquals(AcousticScale.DEFAULT_METERS_PER_BLOCK,
				AcousticScale.metersPerBlock(), EPS);
		} finally {
			AcousticScale.setMetersPerBlock(
				AcousticScale.DEFAULT_METERS_PER_BLOCK);
		}
	}

	@Test
	void roomAndDelayBudgetUseMetres() {
		AcousticScale.setMetersPerBlock(1.0);
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO,
			new Vec3(5.0, 4.0, 3.0), List.of(), true, "");
		double rt60Blocks = room.rt60Mid();
		double schroederBlocks = room.schroederHz();
		AcousticScale.setMetersPerBlock(0.5);
		try {
			assertEquals(2.5, room.widthMeters(), EPS);
			assertEquals(7.5, room.volumeMeters3(), EPS);
			assertEquals(room.surfaceArea() * 0.25,
				room.surfaceAreaMeters2(), EPS);
			assertEquals(rt60Blocks * 0.5, room.rt60MidMeters(), EPS);
			assertEquals(schroederBlocks * 2.0, room.schroederHz(), 1.0e-6);

			ReflectionDelayLimit limit = ReflectionDelayBudget.forRoom(room,
				2.0, 2.0);
			assertEquals(3.0 * room.diagonalMeters() / 343.0,
				limit.maxDelaySeconds(2), EPS);
		} finally {
			AcousticScale.setMetersPerBlock(
				AcousticScale.DEFAULT_METERS_PER_BLOCK);
		}
	}
}
