package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.client.media.audio.acoustics.geometry.AcousticVoxelGrid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CUDA ray tracer validation. Skipped automatically when no NVIDIA driver,
 * shared CUDA context or RT module is available.
 */
class CudaRayTracerTest {
	@Test
	void tracesClosedBoxWithFourBouncesAndSpeakerConnection() {
		CudaRayTracer tracer = new CudaRayTracer();
		try {
			Assumptions.assumeTrue(CudaRayTracer.available(),
				"CUDA ray tracing unavailable");

			int size = 8;
			byte[] solid = new byte[size * size * size];
			float[] reflect = new float[solid.length];
			float[] scatter = new float[solid.length];
			for (int y = 0; y < size; y++) {
				for (int z = 0; z < size; z++) {
					for (int x = 0; x < size; x++) {
						boolean wall = x == 0 || y == 0 || z == 0
							|| x == size - 1 || y == size - 1 || z == size - 1;
						int index = (z * size + y) * size + x;
						solid[index] = (byte) (wall ? 1 : 0);
						reflect[index] = wall ? 0.9f : 0.0f;
						scatter[index] = wall ? 0.4f : 0.0f;
					}
				}
			}
			AcousticVoxelGrid grid = AcousticVoxelGrid.of(solid, reflect, scatter, scatter,
				new int[size * size * size], java.util.List.of(), size, size, size,
				0.0f, 0.0f, 0.0f);
			tracer.upload(grid);
			assertTrue(tracer.hasGrid(), "grid must be uploaded");

			List<CudaRayTracer.Speaker> speakers = List.of(
				new CudaRayTracer.Speaker(2.0f, 2.0f, 2.0f, 0, 1.0f),
				new CudaRayTracer.Speaker(6.0f, 2.0f, 6.0f, 1, 1.0f),
				new CudaRayTracer.Speaker(2.0f, 6.0f, 6.0f, 4, 1.0f));

			int rays = 512;
			int bounces = 4;
			float center = 4.0f;
			CudaRayTracer.Hit[] hits = tracer.trace(center, center, center,
				rays, bounces, 20.0f, 1.0f, speakers);

			assertEquals(rays * bounces, hits.length);
			int wallHits = 0;
			int connected = 0;
			for (int ray = 0; ray < rays; ray++) {
				float previous = Float.MAX_VALUE;
				for (int bounce = 0; bounce < bounces; bounce++) {
					CudaRayTracer.Hit hit = hits[ray * bounces + bounce];
					if (hit.energy() <= 0.0f) {
						continue;
					}
					wallHits++;
					assertTrue(hit.delaySeconds() > 0.0f,
						"first hit must have a positive delay");
					assertTrue(hit.delaySeconds() < 1.0f,
						"box hits must stay well under a second");
					double length = Math.sqrt(hit.dirX() * hit.dirX()
						+ hit.dirY() * hit.dirY() + hit.dirZ() * hit.dirZ());
					assertEquals(1.0, length, 1.0e-3,
						"arrival direction must be normalized");
					assertTrue(hit.energy() <= previous + 1.0e-6f,
						"diffuse energy must not grow with bounces");
					previous = hit.energy();
					if (hit.channel() >= 0) {
						assertTrue(hit.gain() > 0.0f);
						assertTrue(hit.channel() < 6);
						connected++;
					}
				}
			}
			assertTrue(wallHits >= rays * bounces / 2,
				"closed box should hit on most bounces: " + wallHits);
			assertTrue(connected > 0,
				"next-event estimation must light up some speaker channels");
		} finally {
			tracer.close();
		}
	}

	@Test
	void missesEverythingOutsideTheGrid() {
		CudaRayTracer tracer = new CudaRayTracer();
		try {
			Assumptions.assumeTrue(CudaRayTracer.available(),
				"CUDA ray tracing unavailable");
			int size = 4;
			int cells = size * size * size;
			AcousticVoxelGrid grid = AcousticVoxelGrid.of(new byte[cells], new float[cells], new float[cells], new float[cells], new int[cells], java.util.List.of(), size, size, size, 0.0f, 0.0f, 0.0f);
			tracer.upload(grid);
			CudaRayTracer.Hit[] hits = tracer.trace(1.5f, 1.5f, 1.5f,
				64, 2, 4.0f, 1.0f, List.of());
			for (CudaRayTracer.Hit hit : hits) {
				assertEquals(0.0f, hit.energy(), 0.0f);
				assertEquals(-1, hit.channel());
			}
		} finally {
			tracer.close();
		}
	}
}
