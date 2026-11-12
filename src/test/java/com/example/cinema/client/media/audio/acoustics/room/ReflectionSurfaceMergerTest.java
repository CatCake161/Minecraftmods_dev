package com.example.cinema.client.media.audio.acoustics.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReflectionSurfaceMergerTest {
	private static final AcousticMaterial STONE =
		new AcousticMaterial("stone", 0.015, 0.05, 0.09, 0.10);
	private static final AcousticMaterial WOOD =
		new AcousticMaterial("wood", 0.10, 0.20, 0.30, 0.05);

	@Test
	void mergesAContiguousFaceGridIntoOnePatch() {
		ReflectionSurfaceMerger merger = new ReflectionSurfaceMerger();
		for (int y = 0; y < 2; y++) {
			for (int z = 0; z < 3; z++) {
				merger.add(Direction.EAST, new BlockPos(1, y, z), STONE);
			}
		}

		List<AcousticSurface> patches = merger.merge();

		assertEquals(1, patches.size());
		AcousticSurface patch = patches.get(0);
		assertEquals(AcousticSurface.Axis.X, patch.axis());
		assertEquals(1.0, patch.coordinate(), 1.0e-9);
		assertEquals(-1.0, patch.normal(), 1.0e-9);
		assertEquals(0.0, patch.minU(), 1.0e-9);
		assertEquals(2.0, patch.maxU(), 1.0e-9);
		assertEquals(0.0, patch.minV(), 1.0e-9);
		assertEquals(3.0, patch.maxV(), 1.0e-9);
		assertEquals(6.0, patch.area(), 1.0e-9);
		assertEquals(6, patch.samples());
		assertEquals(6, merger.faceCount());
	}

	@Test
	void keepsDisjointFaceGroupsAsSeparatePatches() {
		ReflectionSurfaceMerger merger = new ReflectionSurfaceMerger();
		merger.add(Direction.EAST, new BlockPos(1, 0, 0), STONE);
		merger.add(Direction.EAST, new BlockPos(1, 0, 4), STONE);

		List<AcousticSurface> patches = merger.merge();

		assertEquals(2, patches.size());
		assertEquals(2.0, patches.get(0).area() + patches.get(1).area(), 1.0e-9);
	}

	@Test
	void averagesMaterialsOverAMergedPatch() {
		ReflectionSurfaceMerger merger = new ReflectionSurfaceMerger();
		merger.add(Direction.EAST, new BlockPos(1, 0, 0), STONE);
		merger.add(Direction.EAST, new BlockPos(1, 1, 0), WOOD);

		List<AcousticSurface> patches = merger.merge();

		assertEquals(1, patches.size());
		assertEquals((STONE.low() + WOOD.low()) * 0.5,
			patches.get(0).material().low(), 1.0e-9);
		assertEquals((STONE.mid() + WOOD.mid()) * 0.5,
			patches.get(0).material().mid(), 1.0e-9);
	}
}
