package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffuseRaySamplerTest {
	@Test
	void directionsAreDeterministicAndInTheCosineHemisphere() {
		Point3 normal = new Point3(0.0, 1.0, 0.0);
		List<Point3> first = DiffuseRaySampler.directions(normal, "2:test", 8);
		List<Point3> second = DiffuseRaySampler.directions(normal, "2:test", 8);

		assertEquals(8, first.size());
		assertEquals(first, second);
		for (Point3 direction : first) {
			assertEquals(1.0, direction.length(), 1.0e-9);
			assertTrue(direction.dot(normal) >= 0.0);
		}
	}

	@Test
	void differentKeysGiveDifferentDirections() {
		Point3 normal = new Point3(0.0, 1.0, 0.0);

		assertTrue(!DiffuseRaySampler.directions(normal, "a", 4)
			.equals(DiffuseRaySampler.directions(normal, "b", 4)));
	}

	@Test
	void invalidNormalOrCountProducesNoDirections() {
		assertTrue(DiffuseRaySampler.directions(Point3.ZERO, "a", 4).isEmpty());
		assertTrue(DiffuseRaySampler.directions(new Point3(0.0, 1.0, 0.0),
			"a", 0).isEmpty());
		assertTrue(DiffuseRaySampler.directions(null, "a", 4).isEmpty());
	}
}
