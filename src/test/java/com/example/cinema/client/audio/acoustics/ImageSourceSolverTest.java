package com.example.cinema.client.audio.acoustics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSourceSolverTest {
	private static final double EPS = 1.0e-9;
	private static final AcousticMaterial STONE =
		new AcousticMaterial("stone", 0.015, 0.05, 0.09, 0.10);
	private static final AcousticMaterial GLASS =
		new AcousticMaterial("glass", 0.12, 0.035, 0.02, 0.05);

	@Test
	void mirrorsSourceAndComputesReflectionPoint() {
		MirrorPlane plane = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.0, -1.0, null);
		Point3 source = Point3.of(0.0, 1.0, 0.0);
		Point3 listener = Point3.of(2.0, 2.0, 0.0);

		Optional<ImageSourceSolver.Geometry> result =
			ImageSourceSolver.geometry(plane, source, listener);

		assertTrue(result.isPresent());
		ImageSourceSolver.Geometry geometry = result.get();
		assertEquals(10.0, geometry.imageSource().x(), EPS);
		assertEquals(1.0, geometry.imageSource().y(), EPS);
		assertEquals(0.0, geometry.imageSource().z(), EPS);
		assertEquals(5.0, geometry.reflectionPoint().x(), EPS);
		assertEquals(1.625, geometry.reflectionPoint().y(), EPS);
		assertEquals(0.0, geometry.reflectionPoint().z(), EPS);

		double pathLength = Math.sqrt(65.0);
		double directDistance = Math.sqrt(5.0);
		assertEquals(pathLength, geometry.pathLength(), EPS);
		assertEquals(directDistance, geometry.directDistance(), EPS);
		assertEquals((pathLength - directDistance) / ImageSourceSolver.SPEED_OF_SOUND,
			geometry.delaySeconds(), 1.0e-12);
		assertEquals(pathLength,
			source.distanceTo(geometry.reflectionPoint())
				+ geometry.reflectionPoint().distanceTo(listener), EPS);
	}

	@Test
	void boundedPlaneRejectsReflectionOutsideTangentialBounds() {
		MirrorPlane plane = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.0, -1.0,
			0.0, 1.0, -1.0, 1.0, null);

		Optional<ImageSourceSolver.Geometry> result = ImageSourceSolver.geometry(
			plane, Point3.of(0.0, 1.0, 0.0), Point3.of(2.0, 2.0, 0.0));

		assertTrue(result.isEmpty());
	}

	@Test
	void boundedPlaneAcceptsReflectionInsideTangentialBounds() {
		MirrorPlane plane = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.0, -1.0,
			0.0, 2.0, -1.0, 1.0, null);

		Optional<ImageSourceSolver.Geometry> result = ImageSourceSolver.geometry(
			plane, Point3.of(0.0, 1.0, 0.0), Point3.of(2.0, 2.0, 0.0));

		assertTrue(result.isPresent());
		assertEquals(5.0, result.get().reflectionPoint().x(), EPS);
	}

	@Test
	void sourceAndListenerMustBeOnSameSide() {
		MirrorPlane plane = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.0, -1.0, null);

		assertTrue(ImageSourceSolver.geometry(plane,
			Point3.of(6.0, 1.0, 0.0), Point3.of(2.0, 2.0, 0.0)).isEmpty());
		assertTrue(ImageSourceSolver.geometry(plane,
			Point3.of(0.0, 1.0, 0.0), Point3.of(6.0, 2.0, 0.0)).isEmpty());
	}

	@Test
	void doorwayAtReflectionPointRejectsTap() {
		FakeWorld world = new FakeWorld(null, OcclusionResult.NONE);

		List<ReflectionTap> taps = solveSinglePlane(world, false);

		assertTrue(taps.isEmpty());
	}

	@Test
	void occludedPathKeepsAttenuatedTap() {
		OcclusionResult occlusion = new OcclusionResult(6.0, 1_000.0, 2);
		FakeWorld world = new FakeWorld(STONE, occlusion);

		List<ReflectionTap> taps = solveSinglePlane(world, false);

		assertEquals(1, taps.size());
		ReflectionTap tap = taps.get(0);
		assertEquals(12.0, tap.occlusion().attenuationDb(), EPS);
		assertEquals(1_000.0, tap.occlusion().cutoffHz(), EPS);
		assertEquals(2, tap.occlusion().order());
		assertEquals(ImageSourceSolver.reflectionGain(STONE, Math.sqrt(65.0))
			* occlusion.linearGain() * occlusion.linearGain(), tap.gain(), EPS);
	}

	@Test
	void glassAtReflectionPointIsAValidReflector() {
		FakeWorld world = new FakeWorld(GLASS, OcclusionResult.NONE);

		List<ReflectionTap> taps = solveSinglePlane(world, false);

		assertEquals(1, taps.size());
		ReflectionTap tap = taps.get(0);
		assertEquals("glass", tap.material().name());
		assertEquals(MirrorPlane.Axis.X.name() + "-5000", tap.planeKey());
		assertEquals(10.0, tap.imageSource().x(), EPS);
		assertEquals(5.0, tap.reflectionPoint().x(), EPS);
		assertNotEquals(tap.imageSource().x(), tap.reflectionPoint().x());
		assertEquals(Math.sqrt(65.0), tap.pathLength(), EPS);
		assertTrue(tap.gain() > 0.0);
		assertTrue(tap.delayMilliseconds() > ImageSourceSolver.MIN_DELAY_SECONDS * 1000.0);
		assertEquals(OcclusionResult.NONE, tap.occlusion());
	}

	@Test
	void glassInThePathAttenuatesButKeepsTap() {
		OcclusionResult occlusion = new OcclusionResult(9.0, 800.0, 1);
		FakeWorld world = new FakeWorld(GLASS, occlusion);

		List<ReflectionTap> taps = solveSinglePlane(world, false);

		assertEquals(1, taps.size());
		assertEquals(18.0, taps.get(0).occlusion().attenuationDb(), EPS);
		assertTrue(taps.get(0).gain() > 0.0);
	}

	@Test
	void lfeSourceIsExcluded() {
		FakeWorld world = new FakeWorld(STONE, OcclusionResult.NONE);

		List<ReflectionTap> taps = solveSinglePlane(world, true);

		assertTrue(taps.isEmpty());
	}

	@Test
	void tooShortDelayIsRejected() {
		MirrorPlane plane = MirrorPlane.unbounded(MirrorPlane.Axis.X, 0.0, 1.0, null);
		FakeWorld world = new FakeWorld(STONE, OcclusionResult.NONE);

		List<ReflectionTap> taps = ImageSourceSolver.solve(List.of(plane), 0,
			Point3.of(0.1, 1.0, 0.0), Point3.of(0.2, 1.0, 0.0), false, world);

		assertTrue(taps.isEmpty());
	}

	@Test
	void strongestKeepsHighestGain() {
		ReflectionTap weak = new ReflectionTap(0, "weak", Point3.ZERO, Point3.ZERO,
			2.0, 0.01, STONE, 0.1);
		ReflectionTap strong = new ReflectionTap(0, "strong", Point3.ZERO, Point3.ZERO,
			1.0, 0.01, STONE, 0.5);

		List<ReflectionTap> result = ImageSourceSolver.strongest(
			List.of(weak, strong), 1);

		assertEquals(1, result.size());
		assertEquals("strong", result.get(0).planeKey());
		assertFalse(result.contains(weak));
	}

	private static List<ReflectionTap> solveSinglePlane(FakeWorld world, boolean lfe) {
		MirrorPlane plane = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.0, -1.0, null);
		return ImageSourceSolver.solve(List.of(plane), 0,
			Point3.of(0.0, 1.0, 0.0), Point3.of(2.0, 2.0, 0.0), lfe, world);
	}

	private static final class FakeWorld implements ImageSourceSolver.World {
		private final AcousticMaterial wall;
		private final OcclusionResult occlusion;

		private FakeWorld(AcousticMaterial wall, OcclusionResult occlusion) {
			this.wall = wall;
			this.occlusion = occlusion;
		}

		@Override
		public AcousticMaterial wallAt(MirrorPlane plane, Point3 point) {
			return wall;
		}

		@Override
		public OcclusionResult occlusion(Point3 from, Point3 to) {
			return occlusion;
		}
	}
}
