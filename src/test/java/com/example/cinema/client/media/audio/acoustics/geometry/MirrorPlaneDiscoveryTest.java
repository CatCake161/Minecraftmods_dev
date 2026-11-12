package com.example.cinema.client.media.audio.acoustics.geometry;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorPlaneDiscoveryTest {
	private static final double EPS = 1.0e-9;

	@Test
	void probeDirectionsCoverAllTwentySixAxisEdgeAndCornerDirections() {
		List<Vec3> directions = MirrorPlaneDiscovery.probeDirections();

		assertEquals(26, directions.size());
		Set<String> unique = new HashSet<>();
		for (Vec3 direction : directions) {
			assertEquals(1.0, direction.length(), EPS);
			unique.add(direction.x() + "," + direction.y() + "," + direction.z());
		}
		assertEquals(26, unique.size());
	}

	@Test
	void mergesNearCoplanarUnboundedPlanes() {
		MirrorPlane first = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.0, -1.0,
			Point3.of(5.0, 1.0, 1.0));
		MirrorPlane second = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.097, -1.0,
			Point3.of(5.097, 2.0, 2.0));

		List<MirrorPlane> merged = MirrorPlaneDiscovery.mergeCoplanar(
			List.of(first, second), MirrorPlane.COPLANAR_TOLERANCE);

		assertEquals(1, merged.size());
		assertEquals(5.0, merged.get(0).coordinate(), EPS);
	}

	@Test
	void keepsBoundedSeedAndDropsCoplanarRayPlane() {
		MirrorPlane seed = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.0, -1.0,
			0.0, 3.0, 0.0, 4.0, Point3.of(5.0, 1.5, 2.0));
		MirrorPlane ray = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.097, -1.0,
			Point3.of(5.097, 1.5, 2.0));

		List<MirrorPlane> merged = MirrorPlaneDiscovery.mergeCoplanar(
			List.of(seed, ray), MirrorPlane.COPLANAR_TOLERANCE);

		assertEquals(1, merged.size());
		assertTrue(merged.get(0).bounded());
		assertEquals(5.0, merged.get(0).coordinate(), EPS);
		assertEquals(0.0, merged.get(0).minU(), EPS);
		assertEquals(3.0, merged.get(0).maxU(), EPS);
		assertEquals(0.0, merged.get(0).minV(), EPS);
		assertEquals(4.0, merged.get(0).maxV(), EPS);
	}

	@Test
	void doesNotMergeBoundedPlanesWithDisjointTangentialBounds() {
		MirrorPlane first = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.0, -1.0,
			0.0, 1.0, 0.0, 1.0, Point3.of(5.0, 0.5, 0.5));
		MirrorPlane second = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.05, -1.0,
			3.0, 4.0, 3.0, 4.0, Point3.of(5.05, 3.5, 3.5));

		List<MirrorPlane> merged = MirrorPlaneDiscovery.mergeCoplanar(
			List.of(first, second), MirrorPlane.COPLANAR_TOLERANCE);

		assertEquals(2, merged.size());
	}

	@Test
	void mergesOverlappingBoundedPlanesAndUnionsBounds() {
		MirrorPlane first = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.0, -1.0,
			0.0, 2.0, 0.0, 2.0, Point3.of(5.0, 1.0, 1.0));
		MirrorPlane second = MirrorPlane.bounded(MirrorPlane.Axis.X, 5.05, -1.0,
			1.0, 3.0, 1.0, 3.0, Point3.of(5.05, 2.0, 2.0));

		List<MirrorPlane> merged = MirrorPlaneDiscovery.mergeCoplanar(
			List.of(first, second), MirrorPlane.COPLANAR_TOLERANCE);

		assertEquals(1, merged.size());
		assertEquals(5.0, merged.get(0).coordinate(), EPS);
		assertEquals(0.0, merged.get(0).minU(), EPS);
		assertEquals(3.0, merged.get(0).maxU(), EPS);
		assertEquals(0.0, merged.get(0).minV(), EPS);
		assertEquals(3.0, merged.get(0).maxV(), EPS);
	}

	@Test
	void doesNotMergePlanesWithOppositeNormals() {
		MirrorPlane first = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.0, -1.0,
			Point3.of(5.0, 1.0, 1.0));
		MirrorPlane second = MirrorPlane.unbounded(MirrorPlane.Axis.X, 5.05, 1.0,
			Point3.of(5.05, 1.0, 1.0));

		List<MirrorPlane> merged = MirrorPlaneDiscovery.mergeCoplanar(
			List.of(first, second), MirrorPlane.COPLANAR_TOLERANCE);

		assertEquals(2, merged.size());
	}
}
