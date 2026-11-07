package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MirrorPlaneDiscoveryTest {
	@Test
	void probeDirectionsCoverAllTwentySixAxisEdgeAndCornerDirections() {
		List<Vec3> directions = MirrorPlaneDiscovery.probeDirections();

		assertEquals(26, directions.size());
		Set<String> unique = new HashSet<>();
		for (Vec3 direction : directions) {
			assertEquals(1.0, direction.length(), 1.0e-9);
			unique.add(direction.x() + "," + direction.y() + "," + direction.z());
		}
		assertEquals(26, unique.size());
	}
}
