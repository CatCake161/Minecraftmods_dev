package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AcousticRoomTest {
	@Test
	void schroederFrequencyFollowsMidRt60AndEffectiveVolume() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO, new Vec3(5.0, 4.0, 3.0),
			List.of(), true, "");

		double expected = 2_000.0 * Math.sqrt(room.rt60Mid() / room.effectiveVolume());
		expected = Math.max(AcousticRoom.MIN_SCHROEDER_HZ,
			Math.min(AcousticRoom.MAX_SCHROEDER_HZ, expected));
		assertEquals(expected, room.schroederHz(), 1.0e-9);
	}

	@Test
	void invalidRt60FallsBackTo1000Hz() {
		AcousticRoom room = new AcousticRoom(true, "", Vec3.ZERO,
			new Vec3(1.0, 1.0, 1.0), 1.0, 6.0, 0.0, 0.0, 0.0,
			Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
			Double.POSITIVE_INFINITY, List.of(), null);

		assertEquals(AcousticRoom.DEFAULT_SCHROEDER_HZ, room.schroederHz(),
			1.0e-9);
	}

	@Test
	void reflectionSurfacesPreferFillPatchesAndFallBackToBoxPlanes() {
		AcousticSurface patch = new AcousticSurface(AcousticSurface.Axis.X, 4.0,
			-1.0, 0.0, 2.0, 0.0, 2.0,
			new AcousticMaterial("patch", 0.1, 0.2, 0.3, 0.0), 4);
		RoomFill fill = new RoomFill(8.0, 24.0, 0.0, 0.1, 0.2, 0.3, 8, true,
			0, Vec3.ZERO, new Vec3(2.0, 2.0, 2.0), 1L, List.of(patch));
		AcousticSurface boxPlane = new AcousticSurface(AcousticSurface.Axis.Z, 6.0,
			-1.0, 0.0, 4.0, 0.0, 4.0,
			new AcousticMaterial("box", 0.2, 0.3, 0.4, 0.0), 16);

		AcousticRoom withFill = AcousticRoom.box(Vec3.ZERO,
			new Vec3(4.0, 3.0, 6.0), List.of(boxPlane), true, "", fill);
		AcousticRoom withoutFill = AcousticRoom.box(Vec3.ZERO,
			new Vec3(4.0, 3.0, 6.0), List.of(boxPlane), true, "");

		assertEquals(1, withFill.reflectionSurfaces().size());
		assertSame(patch, withFill.reflectionSurfaces().get(0));
		assertEquals(1, withoutFill.reflectionSurfaces().size());
		assertSame(boxPlane, withoutFill.reflectionSurfaces().get(0));
	}
}
