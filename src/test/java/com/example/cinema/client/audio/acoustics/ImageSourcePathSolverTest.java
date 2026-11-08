package com.example.cinema.client.audio.acoustics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSourcePathSolverTest {
	private static final AcousticMaterial MATERIAL =
		new AcousticMaterial("test", 0.10, 0.20, 0.30, 0.0);
	private static final ImageSourceSolver.World WORLD = new ImageSourceSolver.World() {
		@Override
		public AcousticMaterial wallAt(MirrorPlane plane, Point3 point) {
			return MATERIAL;
		}

		@Override
		public OcclusionResult occlusion(Point3 from, Point3 to) {
			return OcclusionResult.NONE;
		}
	};

	@Test
	void secondOrderPathMatchesBoxImageSource() {
		Point3 source = new Point3(2.0, 1.5, 3.0);
		Point3 listener = new Point3(3.0, 1.6, 4.0);
		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0, source, listener,
			false, WORLD, 2);
		ReflectionPathTap tap = find(taps, "0:Z-8000>X-6000");

		assertEquals(2, tap.order());
		assertPoint(new Point3(10.0, 1.5, 13.0), tap.imageSource());
		assertEquals(2, tap.reflectionPoints().size());
		assertPoint(new Point3(6.0, 1.557142857142857, 7.857142857142857),
			tap.reflectionPoints().get(1));
		assertPoint(new Point3(5.888888888888889, 1.555555555555556, 8.0),
			tap.reflectionPoints().get(0));
		assertEquals(2, tap.materials().size());

		double pathLength = Math.sqrt(130.01);
		double direct = Math.sqrt(2.01);
		assertEquals(pathLength, tap.pathLength(), 1.0e-9);
		assertEquals((pathLength - direct) / 343.0, tap.delaySeconds(),
			1.0e-9);
		assertEquals(0.8 / pathLength, tap.gain(), 1.0e-9);
		assertFalse(tap.occlusion().isBlocked());
	}

	@Test
	void pathKeysAreUniqueAndKeepBounceOrder() {
		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			false, WORLD, 2);
		ReflectionPathTap zThenX = find(taps, "0:Z-8000>X-6000");

		assertEquals(2, zThenX.order());
		assertTrue(zThenX.pathKey().contains(">"));
		assertEquals(taps.size(),
			taps.stream().map(ReflectionPathTap::pathKey).distinct().count());
	}

	@Test
	void thirdOrderRecursionProducesDeepPaths() {
		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			false, WORLD, 3);
		ReflectionPathTap deep = taps.stream()
			.filter(tap -> tap.order() == 3)
			.findFirst()
			.orElseThrow();

		assertEquals(3, deep.reflectionPoints().size());
		assertEquals(3, deep.materials().size());
		assertEquals(3, deep.pathKey().split(">").length);
		assertTrue(deep.delaySeconds() > 0.0);
	}

	@Test
	void outOfBoundsReflectionPointIsRejected() {
		List<MirrorPlane> planes = new ArrayList<>(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0));
		// Replace the full z=max wall with a narrow patch. The z-then-x path's
		// first reflection point is around x=5.89, so it must disappear.
		planes.removeIf(plane -> "Z-8000".equals(plane.key()));
		planes.add(MirrorPlane.bounded(MirrorPlane.Axis.Z, 8.0, -1.0,
			0.0, 1.0, 0.0, 4.0, new Point3(0.5, 2.0, 8.0)));

		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			planes, 0, new Point3(2.0, 1.5, 3.0),
			new Point3(3.0, 1.6, 4.0), false, WORLD, 2);

		assertTrue(taps.stream().noneMatch(
			tap -> "0:Z-8000>X-6000".equals(tap.pathKey())));
	}

	@Test
	void maxPathsCapsTheResult() {
		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			false, WORLD, 3, 10);

		assertTrue(taps.size() <= 10);
	}

	@Test
	void boxPathCountsStayBounded() {
		int previous = 0;
		for (int order = 2; order <= 5; order++) {
			List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
				boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
				new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
				false, WORLD, order, ImageSourcePathSolver.MAX_PATHS);
			assertTrue(taps.size() > previous,
				"order " + order + " should add paths");
			assertTrue(taps.size() <= ImageSourcePathSolver.MAX_PATHS);
			previous = taps.size();
		}
	}

	@Test
	void solveStatsReportVisitsAndTruncation() {
		ImageSourcePathSolver.SolveStats full = ImageSourcePathSolver.solveWithStats(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			false, WORLD, 2, ImageSourcePathSolver.MAX_PATHS);
		assertTrue(full.visits() > 0);
		assertFalse(full.truncated());

		ImageSourcePathSolver.SolveStats capped = ImageSourcePathSolver.solveWithStats(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 0,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			false, WORLD, 2, 1);
		assertEquals(1, capped.paths().size());
		assertTrue(capped.truncated());
	}

	@Test
	void resumableSessionMatchesSolve() {
		List<MirrorPlane> planes = boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0);
		Point3 source = new Point3(2.0, 1.5, 3.0);
		Point3 listener = new Point3(3.0, 1.6, 4.0);
		List<ReflectionPathTap> expected = ImageSourcePathSolver.solve(
			planes, 0, source, listener, false, WORLD, 2, 512);
		ImageSourcePathSolver.Session session =
			new ImageSourcePathSolver.Session(planes, 0, source, listener,
				WORLD, 2, 512, 2, 200_000);
		int steps = 0;
		while (!session.step(1L)) {
			steps++;
			assertTrue(steps < 10_000, "session should finish");
		}
		ImageSourcePathSolver.SolveStats stats = session.stats();
		assertEquals(expected.size(), stats.paths().size());
		assertEquals(expected.stream().map(ReflectionPathTap::pathKey).sorted().toList(),
			stats.paths().stream().map(ReflectionPathTap::pathKey).sorted().toList());
		assertTrue(steps > 1, "tiny budget should require multiple steps");
	}

	@Test
	void lfeIsExcluded() {
		List<ReflectionPathTap> taps = ImageSourcePathSolver.solve(
			boxPlanes(0.0, 0.0, 0.0, 6.0, 4.0, 8.0), 3,
			new Point3(2.0, 1.5, 3.0), new Point3(3.0, 1.6, 4.0),
			true, WORLD, 2);

		assertTrue(taps.isEmpty());
	}

	private static ReflectionPathTap find(List<ReflectionPathTap> taps,
										  String key) {
		return taps.stream()
			.filter(tap -> key.equals(tap.pathKey()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("missing path " + key));
	}

	private static void assertPoint(Point3 expected, Point3 actual) {
		assertEquals(expected.x(), actual.x(), 1.0e-9);
		assertEquals(expected.y(), actual.y(), 1.0e-9);
		assertEquals(expected.z(), actual.z(), 1.0e-9);
	}

	private static List<MirrorPlane> boxPlanes(double minX, double minY,
											   double minZ, double maxX,
											   double maxY, double maxZ) {
		double midX = (minX + maxX) * 0.5;
		double midY = (minY + maxY) * 0.5;
		double midZ = (minZ + maxZ) * 0.5;
		return List.of(
			MirrorPlane.bounded(MirrorPlane.Axis.X, minX, 1.0,
				minY, maxY, minZ, maxZ, new Point3(minX, midY, midZ)),
			MirrorPlane.bounded(MirrorPlane.Axis.X, maxX, -1.0,
				minY, maxY, minZ, maxZ, new Point3(maxX, midY, midZ)),
			MirrorPlane.bounded(MirrorPlane.Axis.Y, minY, 1.0,
				minX, maxX, minZ, maxZ, new Point3(midX, minY, midZ)),
			MirrorPlane.bounded(MirrorPlane.Axis.Y, maxY, -1.0,
				minX, maxX, minZ, maxZ, new Point3(midX, maxY, midZ)),
			MirrorPlane.bounded(MirrorPlane.Axis.Z, minZ, 1.0,
				minX, maxX, minY, maxY, new Point3(midX, midY, minZ)),
			MirrorPlane.bounded(MirrorPlane.Axis.Z, maxZ, -1.0,
				minX, maxX, minY, maxY, new Point3(midX, midY, maxZ)));
	}
}
