package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticRoomTest {
	private static final AcousticMaterial UNIFORM =
		new AcousticMaterial("uniform", 0.2, 0.2, 0.2, 0.0);
	private static final AcousticMaterial ABSORBENT =
		new AcousticMaterial("absorbent", 0.7, 0.62, 0.5, 0.0);
	private static final AcousticMaterial HARD =
		new AcousticMaterial("hard", 0.05, 0.05, 0.05, 0.0);
	private static final AcousticMaterial DEAD =
		new AcousticMaterial("dead", 0.0, 0.0, 0.0, 0.0);

	@Test
	void uniformAbsorptionFitzroyMatchesEyring() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO, new Vec3(10.0, 6.0, 4.0),
			boxSurfaces(10.0, 6.0, 4.0, UNIFORM), true, "");

		double volume = 10.0 * 6.0 * 4.0;
		double area = 2.0 * (10.0 * 6.0 + 10.0 * 4.0 + 6.0 * 4.0);
		double eyring = 0.161 * volume / (-area * Math.log(1.0 - 0.2));

		assertEquals(eyring, room.rt60Low(), 1.0e-9);
		assertEquals(eyring, room.rt60Mid(), 1.0e-9);
		assertEquals(eyring, room.rt60High(), 1.0e-9);
	}

	@Test
	void nonUniformAbsorptionUsesPerAxisFitzroyTimes() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO, new Vec3(10.0, 6.0, 4.0),
			nonUniformSurfaces(), true, "");

		double volume = 10.0 * 6.0 * 4.0;
		double total = 2.0 * (10.0 * 6.0 + 10.0 * 4.0 + 6.0 * 4.0);
		double areaX = 2.0 * 6.0 * 4.0;
		double areaY = 2.0 * 10.0 * 4.0;
		double areaZ = 2.0 * 10.0 * 6.0;
		double expected = 0.161 * volume / (total * total)
			* (areaX / -Math.log(1.0 - ABSORBENT.mid())
			+ areaY / -Math.log(1.0 - HARD.mid())
			+ areaZ / -Math.log(1.0 - HARD.mid()));
		double average = (areaX * ABSORBENT.mid()
			+ (areaY + areaZ) * HARD.mid()) / total;
		double eyring = 0.161 * volume / (-total * Math.log(1.0 - average));

		assertEquals(expected, room.rt60Mid(), 1.0e-9);
		assertTrue(room.rt60Mid() > eyring);
	}

	@Test
	void perfectlyReflectiveAxisPairGivesInfiniteRt60() {
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO, new Vec3(10.0, 6.0, 4.0),
			List.of(
				surface(AcousticSurface.Axis.X, 0.0, 1.0, 0.0, 4.0, 0.0, 6.0, DEAD),
				surface(AcousticSurface.Axis.X, 10.0, -1.0, 0.0, 4.0, 0.0, 6.0, DEAD),
				surface(AcousticSurface.Axis.Y, 0.0, 1.0, 0.0, 10.0, 0.0, 4.0, HARD),
				surface(AcousticSurface.Axis.Y, 6.0, -1.0, 0.0, 10.0, 0.0, 4.0, HARD),
				surface(AcousticSurface.Axis.Z, 0.0, 1.0, 0.0, 10.0, 0.0, 6.0, HARD),
				surface(AcousticSurface.Axis.Z, 4.0, -1.0, 0.0, 10.0, 0.0, 6.0, HARD)),
			true, "");

		assertTrue(Double.isInfinite(room.rt60Mid()));
	}

	@Test
	void completeFillProvidesPerAxisAbsorption() {
		AxisAbsorption xAxis = new AxisAbsorption(8.0, 0.6, 0.4, 0.3);
		AxisAbsorption yAxis = new AxisAbsorption(8.0, 0.2, 0.2, 0.2);
		AxisAbsorption zAxis = new AxisAbsorption(8.0, 0.1, 0.1, 0.1);
		RoomFill fill = new RoomFill(8.0, 24.0, 0.0, 0.3, 0.2333333333333333,
			0.2, xAxis, yAxis, zAxis, 8, true, 0, Vec3.ZERO,
			new Vec3(2.0, 2.0, 2.0), 1L, List.of());
		AcousticRoom room = AcousticRoom.box(Vec3.ZERO, new Vec3(4.0, 3.0, 6.0),
			boxSurfaces(4.0, 3.0, 6.0, UNIFORM), true, "", fill);

		double expected = 0.161 * 8.0 / (24.0 * 24.0)
			* (8.0 / -Math.log(1.0 - 0.4)
			+ 8.0 / -Math.log(1.0 - 0.2)
			+ 8.0 / -Math.log(1.0 - 0.1));

		assertEquals(expected, room.rt60Mid(), 1.0e-9);
		assertSame(xAxis, room.xAxis());
		assertSame(yAxis, room.yAxis());
		assertSame(zAxis, room.zAxis());
	}

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
			Double.POSITIVE_INFINITY, AxisAbsorption.EMPTY, AxisAbsorption.EMPTY,
			AxisAbsorption.EMPTY, List.of(), null);

		assertEquals(AcousticRoom.DEFAULT_SCHROEDER_HZ, room.schroederHz(),
			1.0e-9);
	}

	@Test
	void reflectionSurfacesPreferFillPatchesAndFallBackToBoxPlanes() {
		AcousticSurface patch = new AcousticSurface(AcousticSurface.Axis.X, 4.0,
			-1.0, 0.0, 2.0, 0.0, 2.0,
			new AcousticMaterial("patch", 0.1, 0.2, 0.3, 0.0), 4);
		RoomFill fill = new RoomFill(8.0, 24.0, 0.0, 0.1, 0.2, 0.3,
			AxisAbsorption.EMPTY, AxisAbsorption.EMPTY, AxisAbsorption.EMPTY,
			8, true, 0, Vec3.ZERO, new Vec3(2.0, 2.0, 2.0), 1L, List.of(patch));
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

	private static List<AcousticSurface> boxSurfaces(double dx, double dy, double dz,
													 AcousticMaterial material) {
		return List.of(
			surface(AcousticSurface.Axis.X, 0.0, 1.0, 0.0, dz, 0.0, dy, material),
			surface(AcousticSurface.Axis.X, dx, -1.0, 0.0, dz, 0.0, dy, material),
			surface(AcousticSurface.Axis.Y, 0.0, 1.0, 0.0, dx, 0.0, dz, material),
			surface(AcousticSurface.Axis.Y, dy, -1.0, 0.0, dx, 0.0, dz, material),
			surface(AcousticSurface.Axis.Z, 0.0, 1.0, 0.0, dx, 0.0, dy, material),
			surface(AcousticSurface.Axis.Z, dz, -1.0, 0.0, dx, 0.0, dy, material));
	}

	private static List<AcousticSurface> nonUniformSurfaces() {
		return List.of(
			surface(AcousticSurface.Axis.X, 0.0, 1.0, 0.0, 4.0, 0.0, 6.0,
				ABSORBENT),
			surface(AcousticSurface.Axis.X, 10.0, -1.0, 0.0, 4.0, 0.0, 6.0,
				ABSORBENT),
			surface(AcousticSurface.Axis.Y, 0.0, 1.0, 0.0, 10.0, 0.0, 4.0, HARD),
			surface(AcousticSurface.Axis.Y, 6.0, -1.0, 0.0, 10.0, 0.0, 4.0, HARD),
			surface(AcousticSurface.Axis.Z, 0.0, 1.0, 0.0, 10.0, 0.0, 6.0, HARD),
			surface(AcousticSurface.Axis.Z, 4.0, -1.0, 0.0, 10.0, 0.0, 6.0, HARD));
	}

	private static AcousticSurface surface(AcousticSurface.Axis axis,
										   double coordinate, double normal,
										   double minU, double maxU,
										   double minV, double maxV,
										   AcousticMaterial material) {
		return new AcousticSurface(axis, coordinate, normal, minU, maxU, minV, maxV,
			material, 1);
	}
}
