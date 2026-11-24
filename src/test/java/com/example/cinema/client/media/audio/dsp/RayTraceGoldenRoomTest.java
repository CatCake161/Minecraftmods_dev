package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.client.media.audio.acoustics.geometry.AcousticVoxelGrid;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Golden room" validation for the CUDA late-field tracer.
 *
 * <p>The room and the ray set are synthetic but fixed, so the GPU output can be
 * diffed against {@link CpuRayTraceReference} sample by sample. Nothing here
 * depends on listening: a mismatch is a number, not an impression.
 *
 * <p>Every test is skipped when no NVIDIA driver / shared CUDA context / RT
 * module is available, so a GPU-less machine still gets a green build.
 */
class RayTraceGoldenRoomTest {
	private static final int CHANNELS = 6;

	private static final float LISTENER_X = 8.0f;
	private static final float LISTENER_Y = 4.0f;
	private static final float LISTENER_Z = 8.0f;
	private static final int RAYS = 1024;
	private static final int BOUNCES = 4;
	private static final float METERS_PER_BLOCK = 1.0f;
	private static final float MAX_DISTANCE_METERS = 40.0f;

	@Test
	void goldenRoomMatchesCpuReference() {
		Assumptions.assumeTrue(CudaRayTracer.available(), "CUDA unavailable");
		AcousticVoxelGrid grid = goldenRoom();
		List<CudaRayTracer.Speaker> speakers = goldenSpeakers();

		CudaRayTracer tracer = new CudaRayTracer();
		try {
			tracer.upload(grid);
			assertTrue(tracer.hasGrid(), "grid must be uploaded");

			CudaRayTracer.Hit[] gpu = tracer.trace(LISTENER_X, LISTENER_Y,
				LISTENER_Z, RAYS, BOUNCES, MAX_DISTANCE_METERS,
				METERS_PER_BLOCK, speakers);
			CudaRayTracer.Hit[] cpu = CpuRayTraceReference.trace(grid,
				LISTENER_X, LISTENER_Y, LISTENER_Z, RAYS, BOUNCES,
				MAX_DISTANCE_METERS, METERS_PER_BLOCK, speakers);

			assertEquals(RAYS * BOUNCES, gpu.length,
				"the tracer must return one record per (ray, bounce)");
			assertEquals(cpu.length, gpu.length,
				"CPU and GPU record counts must match");

			int gpuHitCount = 0;
			int cpuHitCount = 0;
			for (int i = 0; i < gpu.length; i++) {
				if (gpu[i].energy() > 0.0f) {
					gpuHitCount++;
				}
				if (cpu[i].energy() > 0.0f) {
					cpuHitCount++;
				}
			}
			assertEquals(cpuHitCount, gpuHitCount,
				"hit count must match the CPU reference");
			assertEquals(RAYS * BOUNCES, gpuHitCount,
				"a closed room must hit on every bounce");

			// Energy per order, the quantity the late bus actually consumes.
			double[] gpuOrder = new double[BOUNCES + 1];
			double[] cpuOrder = new double[BOUNCES + 1];
			for (int i = 0; i < gpu.length; i++) {
				gpuOrder[gpu[i].order()] += gpu[i].energy();
				cpuOrder[cpu[i].order()] += cpu[i].energy();
			}
			for (int order = 1; order <= BOUNCES; order++) {
				double relative = Math.abs(gpuOrder[order] - cpuOrder[order])
					/ Math.max(1.0e-9, cpuOrder[order]);
				System.out.printf(Locale.ROOT,
					"[golden] order=%d gpu=%.6e cpu=%.6e rel=%.6e%n",
					order, gpuOrder[order], cpuOrder[order], relative);
				assertTrue(relative < 0.05,
					"order " + order + " energy mismatch: gpu="
						+ gpuOrder[order] + " cpu=" + cpuOrder[order]
						+ " relative=" + relative);
			}

			int connected = 0;
			int cpuConnected = 0;
			double maxDelayDelta = 0.0;
			double maxDirectionDelta = 0.0;
			double maxNormError = 0.0;
			double maxGainRelative = 0.0;
			double maxEnergyRelative = 0.0;
			for (int i = 0; i < gpu.length; i++) {
				CudaRayTracer.Hit g = gpu[i];
				CudaRayTracer.Hit c = cpu[i];
				boolean gpuHasHit = g.energy() > 0.0f;
				boolean cpuHasHit = c.energy() > 0.0f;
				assertEquals(cpuHasHit, gpuHasHit,
					"slot " + i + " hit presence differs");

				if (g.channel() >= 0) {
					assertTrue(g.gain() > 0.0f, "slot " + i + " gain");
					assertTrue(g.channel() < CHANNELS, "slot " + i + " channel");
					connected++;
				}
				if (c.channel() >= 0) {
					cpuConnected++;
				}
				if (!gpuHasHit) {
					continue;
				}

				double delayDelta = Math.abs((double) g.delaySeconds()
					- c.delaySeconds());
				maxDelayDelta = Math.max(maxDelayDelta, delayDelta);
				assertTrue(delayDelta < 1.0e-3,
					"slot " + i + " delay delta " + delayDelta + " s");

				double length = Math.sqrt((double) g.dirX() * g.dirX()
					+ (double) g.dirY() * g.dirY()
					+ (double) g.dirZ() * g.dirZ());
				maxNormError = Math.max(maxNormError, Math.abs(length - 1.0));
				assertTrue(Math.abs(length - 1.0) < 1.0e-3,
					"slot " + i + " direction not normalized: " + length);

				double directionDelta = Math.max(
					Math.abs((double) g.dirX() - c.dirX()),
					Math.max(Math.abs((double) g.dirY() - c.dirY()),
						Math.abs((double) g.dirZ() - c.dirZ())));
				maxDirectionDelta = Math.max(maxDirectionDelta,
					directionDelta);
				assertTrue(directionDelta < 1.0e-3,
					"slot " + i + " direction delta " + directionDelta);

				double energyRelative = Math.abs((double) g.energy() - c.energy())
					/ Math.max(1.0e-12, c.energy());
				maxEnergyRelative = Math.max(maxEnergyRelative, energyRelative);
				assertTrue(energyRelative < 0.05,
					"slot " + i + " energy mismatch: gpu=" + g.energy()
						+ " cpu=" + c.energy() + " relative=" + energyRelative);

				if (g.channel() >= 0 && c.channel() >= 0) {
					double gainRelative = Math.abs((double) g.gain() - c.gain())
						/ Math.max(1.0e-12, c.gain());
					maxGainRelative = Math.max(maxGainRelative, gainRelative);
					assertTrue(gainRelative < 0.05,
						"slot " + i + " NEE gain mismatch: gpu=" + g.gain()
							+ " cpu=" + c.gain() + " relative=" + gainRelative);
				}
			}

			assertEquals(cpuConnected, connected,
				"NEE-connected hit count must match the CPU reference");
			assertTrue(connected > 0,
				"next-event estimation must light up some speaker channels");
			double ratio = connected / (double) gpuHitCount;
			assertTrue(ratio > 0.0 && ratio < 1.0,
				"connected-hit ratio out of range: " + ratio);
			System.out.printf(Locale.ROOT,
				"[golden] hits=%d connected=%d ratio=%.4f"
					+ " maxDelay=%.3e maxDir=%.3e maxNorm=%.3e"
					+ " maxGainRel=%.3e maxEnergyRel=%.3e%n",
				gpuHitCount, connected, ratio, maxDelayDelta,
				maxDirectionDelta, maxNormError, maxGainRelative,
				maxEnergyRelative);

			// Diagnostic breadcrumb: only printed when the test is verbose.
			assertTrue(maxDelayDelta < 1.0e-3
					&& maxDirectionDelta < 1.0e-3
					&& maxNormError < 1.0e-3
					&& maxGainRelative < 0.05
					&& maxEnergyRelative < 0.05,
				"aggregate deltas delay=" + maxDelayDelta
					+ " direction=" + maxDirectionDelta
					+ " norm=" + maxNormError
					+ " gain=" + maxGainRelative
					+ " energy=" + maxEnergyRelative);
		} finally {
			tracer.close();
		}
	}

	@Test
	void doorwayConnectsButSolidWallDoesNot() {
		Assumptions.assumeTrue(CudaRayTracer.available(), "CUDA unavailable");
		AcousticVoxelGrid grid = lRoomWithDoor();
		// Speaker 0 sits in the alcove behind the doorway, speaker 1 in a
		// sealed pocket that no ray can enter.
		List<CudaRayTracer.Speaker> speakers = List.of(
			new CudaRayTracer.Speaker(12.5f, 2.0f, 5.0f, 0, 1.0f),
			new CudaRayTracer.Speaker(5.0f, 3.0f, 14.0f, 1, 1.0f));

		CudaRayTracer tracer = new CudaRayTracer();
		try {
			tracer.upload(grid);
			assertTrue(tracer.hasGrid(), "grid must be uploaded");

			CudaRayTracer.Hit[] gpu = tracer.trace(5.0f, 3.0f, 5.0f,
				4096, 6, MAX_DISTANCE_METERS, METERS_PER_BLOCK, speakers);
			CudaRayTracer.Hit[] cpu = CpuRayTraceReference.trace(grid,
				5.0f, 3.0f, 5.0f, 4096, 6, MAX_DISTANCE_METERS,
				METERS_PER_BLOCK, speakers);

			int gpuDoor = countChannel(gpu, 0);
			int cpuDoor = countChannel(cpu, 0);
			int gpuWall = countChannel(gpu, 1);
			int cpuWall = countChannel(cpu, 1);

			assertEquals(cpuDoor, gpuDoor,
				"doorway connection count must match the CPU reference");
			assertEquals(cpuWall, gpuWall,
				"solid-wall connection count must match the CPU reference");
			assertTrue(gpuDoor > 0,
				"the doorway must produce at least one connected hit, got "
					+ gpuDoor);
			assertEquals(0, gpuWall,
				"the sealed wall must never connect, got " + gpuWall);
			System.out.printf(java.util.Locale.ROOT,
				"[l-room] gpuDoor=%d cpuDoor=%d gpuWall=%d cpuWall=%d%n",
				gpuDoor, cpuDoor, gpuWall, cpuWall);
		} finally {
			tracer.close();
		}
	}

	/**
	 * The per-pass ray-set phase must stay bit-comparable with the CPU oracle,
	 * and it must actually change the traced directions.
	 */
	@Test
	void perPassOffsetMatchesCpuAndChangesTheRaySet() {
		Assumptions.assumeTrue(CudaRayTracer.available(), "CUDA unavailable");
		AcousticVoxelGrid grid = goldenRoom();
		List<CudaRayTracer.Speaker> speakers = goldenSpeakers();
		float offset = 0.371f;
		CudaRayTracer tracer = new CudaRayTracer();
		try {
			tracer.upload(grid);
			assertTrue(tracer.hasGrid(), "grid must be uploaded");
			CudaRayTracer.Hit[] gpu = tracer.trace(LISTENER_X, LISTENER_Y,
				LISTENER_Z, RAYS, BOUNCES, MAX_DISTANCE_METERS,
				METERS_PER_BLOCK, speakers, offset);
			CudaRayTracer.Hit[] cpu = CpuRayTraceReference.trace(grid,
				LISTENER_X, LISTENER_Y, LISTENER_Z, RAYS, BOUNCES,
				MAX_DISTANCE_METERS, METERS_PER_BLOCK, speakers, offset);
			CudaRayTracer.Hit[] base = tracer.trace(LISTENER_X, LISTENER_Y,
				LISTENER_Z, RAYS, BOUNCES, MAX_DISTANCE_METERS,
				METERS_PER_BLOCK, speakers);
			assertEquals(RAYS * BOUNCES, gpu.length);

			int changed = 0;
			double maxDelayDelta = 0.0;
			double maxDirectionDelta = 0.0;
			for (int i = 0; i < gpu.length; i++) {
				maxDelayDelta = Math.max(maxDelayDelta,
					Math.abs((double) gpu[i].delaySeconds()
						- cpu[i].delaySeconds()));
				maxDirectionDelta = Math.max(maxDirectionDelta,
					Math.max(Math.abs((double) gpu[i].dirX()
							- cpu[i].dirX()),
						Math.max(Math.abs((double) gpu[i].dirY()
								- cpu[i].dirY()),
							Math.abs((double) gpu[i].dirZ()
								- cpu[i].dirZ()))));
				double directionShift = Math.max(
					Math.abs((double) base[i].dirX() - gpu[i].dirX()),
					Math.max(Math.abs((double) base[i].dirY() - gpu[i].dirY()),
						Math.abs((double) base[i].dirZ() - gpu[i].dirZ())));
				if (directionShift > 1.0e-3) {
					changed++;
				}
			}
			assertTrue(maxDelayDelta < 1.0e-3,
				"offset ray set diverged from the CPU oracle: " + maxDelayDelta);
			assertTrue(maxDirectionDelta < 1.0e-3,
				"offset directions diverged: " + maxDirectionDelta);
			assertTrue(changed > RAYS * BOUNCES / 2,
				"a nonzero offset must rotate most rays, changed=" + changed);
			System.out.printf(Locale.ROOT,
				"[offset] changed=%d/%d maxDelay=%.3e maxDir=%.3e%n",
				changed, gpu.length, maxDelayDelta, maxDirectionDelta);
		} finally {
			tracer.close();
		}
	}

	/**
	 * The point of the whole change: a 4-pass binned accumulation must track
	 * the 8-pass ground truth closer than a single pass, while its total
	 * energy stays the unbiased pass average (not a sum).
	 */
	@Test
	void fourPassAccumulationBeatsSinglePass() {
		AcousticVoxelGrid grid = goldenRoom();
		int rays = 1024;
		int passes = 8;
		int trials = 2;
		CudaRayTracer tracer = null;
		if (CudaRayTracer.available()) {
			tracer = new CudaRayTracer();
			tracer.upload(grid);
			assertTrue(tracer.hasGrid(), "grid must be uploaded");
		}
		try {
			double singleError = 0.0;
			double fourError = 0.0;
			double singleEnergyError = 0.0;
			double fourEnergyError = 0.0;
			double truthEnergy = 0.0;
			for (int trial = 0; trial < trials; trial++) {
				double[][] perPass = new double[passes][];
				double[] totals = new double[passes];
				RtLateFieldBins fourBins = new RtLateFieldBins();
				for (int k = 0; k < passes; k++) {
					int serial = trial * passes + k + 1;
					float offset = (float) ((serial
						* 0.6180339887498949) % 1.0);
					CudaRayTracer.Hit[] hits = rayTrace(grid, tracer, rays,
						offset);
					RtLateFieldBins passBins = new RtLateFieldBins();
					fold(passBins, hits);
					passBins.finishPass();
					perPass[k] = binEnergies(passBins);
					totals[k] = passBins.meanEnergy();
					if (k < 4) {
						fold(fourBins, hits);
						fourBins.finishPass();
					}
				}
				assertEquals(4, fourBins.passes());
				double truthTotal = 0.0;
				for (int k = 0; k < passes; k++) {
					truthTotal += totals[k] / passes;
				}
				double fourTotal = 0.0;
				for (int k = 0; k < 4; k++) {
					fourTotal += totals[k] / 4;
				}
				// Unbiased normalization: the 4-pass merge is exactly the mean
				// of the four per-pass energies, and its tap powers match.
				assertEquals(fourTotal, fourBins.meanEnergy(),
					1.0e-6 * Math.max(1.0e-12, fourTotal));
				double tapPower = 0.0;
				for (RtLateFieldBins.BinTap tap : fourBins.taps(0.0)) {
					tapPower += tap.gain() * tap.gain();
				}
				assertEquals(fourBins.meanEnergy(), tapPower,
					1.0e-6 * Math.max(1.0e-12, fourBins.meanEnergy()));
				assertTrue(truthTotal > 0.0,
					"the golden room must feed the order >= 3 bins");

				double[] truth = new double[RtLateFieldBins.BIN_COUNT];
				for (int k = 0; k < passes; k++) {
					for (int i = 0; i < truth.length; i++) {
						truth[i] += perPass[k][i] / passes;
					}
				}
				double[] four = new double[RtLateFieldBins.BIN_COUNT];
				for (int k = 0; k < 4; k++) {
					for (int i = 0; i < four.length; i++) {
						four[i] += perPass[k][i] / 4;
					}
				}
				singleError += relativeError(perPass[0], truth);
				fourError += relativeError(four, truth);
				singleEnergyError += Math.abs(totals[0] - truthTotal)
					/ truthTotal;
				fourEnergyError += Math.abs(fourTotal - truthTotal)
					/ truthTotal;
				truthEnergy += truthTotal;
			}
			System.out.printf(Locale.ROOT,
				"[bins] passes=1 relL2=%.4f energyErr=%.4f | "
					+ "passes=4 relL2=%.4f energyErr=%.4f | groundTruth=8"
					+ " passes meanEnergy=%.6e%n",
				singleError / trials, singleEnergyError / trials,
				fourError / trials, fourEnergyError / trials,
				truthEnergy / trials);
			assertTrue(singleError > 0.05,
				"single pass must be visibly noisy: " + singleError);
			assertTrue(fourError < singleError * 0.85,
				"4-pass bin variance must beat 1 pass: 4=" + fourError
					+ " 1=" + singleError);
			assertTrue(fourEnergyError <= singleEnergyError * 1.05 + 1.0e-9,
				"4-pass total energy must not drift: 4=" + fourEnergyError
					+ " 1=" + singleEnergyError);
		} finally {
			if (tracer != null) {
				tracer.close();
			}
		}
	}

	private static CudaRayTracer.Hit[] rayTrace(AcousticVoxelGrid grid,
												CudaRayTracer tracer,
												int rays, float offset) {
		List<CudaRayTracer.Speaker> speakers = goldenSpeakers();
		if (tracer == null) {
			return CpuRayTraceReference.trace(grid, LISTENER_X, LISTENER_Y,
				LISTENER_Z, rays, BOUNCES, MAX_DISTANCE_METERS,
				METERS_PER_BLOCK, speakers, offset);
		}
		return tracer.trace(LISTENER_X, LISTENER_Y, LISTENER_Z, rays, BOUNCES,
			MAX_DISTANCE_METERS, METERS_PER_BLOCK, speakers, offset);
	}

	/** Folds the order >= 3 connected hits the way the playback host does. */
	private static void fold(RtLateFieldBins bins, CudaRayTracer.Hit[] hits) {
		for (CudaRayTracer.Hit hit : hits) {
			if (hit.order() < 3 || hit.channel() < 0
				|| !(hit.gain() > 0.0f)) {
				continue;
			}
			bins.accumulate(hit, hit.delaySeconds());
		}
	}

	/** Pass-averaged energy of every flat bin index. */
	private static double[] binEnergies(RtLateFieldBins bins) {
		double[] values = new double[RtLateFieldBins.BIN_COUNT];
		for (int channel = 0; channel < RtLateFieldBins.CHANNELS; channel++) {
			for (int delayBin = 0;
				 delayBin < RtLateFieldBins.DELAY_BINS; delayBin++) {
				for (int dirBin = 0; dirBin < RtLateFieldBins.DIR_BINS;
					 dirBin++) {
					values[RtLateFieldBins.index(channel, delayBin, dirBin)] =
						bins.meanBinEnergy(channel, delayBin, dirBin);
				}
			}
		}
		return values;
	}

	private static double relativeError(double[] estimate, double[] truth) {
		double error = 0.0;
		double norm = 0.0;
		for (int i = 0; i < truth.length; i++) {
			double delta = estimate[i] - truth[i];
			error += delta * delta;
			norm += truth[i] * truth[i];
		}
		return Math.sqrt(error / Math.max(1.0e-30, norm));
	}

	private static List<CudaRayTracer.Speaker> goldenSpeakers() {
		return List.of(
			new CudaRayTracer.Speaker(3.0f, 3.0f, 3.0f, 0, 1.0f),
			new CudaRayTracer.Speaker(8.0f, 3.0f, 3.0f, 1, 1.0f),
			new CudaRayTracer.Speaker(13.0f, 3.0f, 3.0f, 2, 1.0f));
	}

	private static int countChannel(CudaRayTracer.Hit[] hits, int channel) {
		int count = 0;
		for (CudaRayTracer.Hit hit : hits) {
			if (hit.channel() == channel) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 16 x 8 x 16 closed box: four hard side walls plus a hard ceiling, and a
	 * carpeted floor. Listener and speakers are placed at realistic ear
	 * height, so the ray set is not axis-aligned.
	 */
	private static AcousticVoxelGrid goldenRoom() {
		int sx = 16;
		int sy = 8;
		int sz = 16;
		int cells = sx * sy * sz;
		byte[] solid = new byte[cells];
		float[] reflect = new float[cells];
		float[] scatter = new float[cells];
		float[] high = new float[cells];
		int[] materialId = new int[cells];
		Arrays.fill(materialId, -1);
		List<AcousticMaterial> materials = List.of(
			new AcousticMaterial("hard-wall", 0.1, 0.1, 0.1, 0.3),
			new AcousticMaterial("carpet", 0.4, 0.4, 0.4, 0.8));
		for (int y = 0; y < sy; y++) {
			for (int z = 0; z < sz; z++) {
				for (int x = 0; x < sx; x++) {
					boolean sideWall = x == 0 || x == sx - 1
						|| z == 0 || z == sz - 1;
					boolean floor = y == 0;
					boolean ceiling = y == sy - 1;
					if (!sideWall && !floor && !ceiling) {
						continue;
					}
					int index = (z * sy + y) * sx + x;
					solid[index] = 1;
					if (floor) {
						reflect[index] = 0.6f;
						scatter[index] = 0.8f;
						high[index] = 0.7f;
						materialId[index] = 1;
					} else {
						reflect[index] = 0.9f;
						scatter[index] = 0.3f;
						high[index] = 0.2f;
						materialId[index] = 0;
					}
				}
			}
		}
		return AcousticVoxelGrid.of(solid, reflect, scatter, high, materialId,
			materials, sx, sy, sz, 0.0f, 0.0f, 0.0f);
	}

	/**
	 * L-shaped layout: a main room, a three-block doorway and an alcove, plus
	 * a sealed pocket that no ray can reach. Everything starts solid, then the
	 * air volumes are carved out, so the walls are guaranteed closed.
	 */
	private static AcousticVoxelGrid lRoomWithDoor() {
		int sx = 20;
		int sy = 10;
		int sz = 20;
		int cells = sx * sy * sz;
		byte[] solid = new byte[cells];
		Arrays.fill(solid, (byte) 1);
		carve(solid, sx, sy, sz, 1, 1, 1, 9, 5, 9);
		carve(solid, sx, sy, sz, 10, 1, 4, 10, 3, 6);
		carve(solid, sx, sy, sz, 11, 1, 3, 14, 3, 7);
		carve(solid, sx, sy, sz, 1, 1, 12, 9, 5, 16);

		float[] reflect = new float[cells];
		float[] scatter = new float[cells];
		float[] high = new float[cells];
		int[] materialId = new int[cells];
		Arrays.fill(materialId, -1);
		for (int i = 0; i < cells; i++) {
			if (solid[i] != 0) {
				reflect[i] = 0.9f;
				scatter[i] = 0.3f;
				high[i] = 0.2f;
				materialId[i] = 0;
			}
		}
		List<AcousticMaterial> materials = List.of(
			new AcousticMaterial("hard-wall", 0.1, 0.1, 0.1, 0.3));
		return AcousticVoxelGrid.of(solid, reflect, scatter, high, materialId,
			materials, sx, sy, sz, 0.0f, 0.0f, 0.0f);
	}

	/** Sets cells in the inclusive box to air. */
	private static void carve(byte[] solid, int sx, int sy, int sz,
							  int x0, int y0, int z0,
							  int x1, int y1, int z1) {
		for (int y = y0; y <= y1; y++) {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					solid[(z * sy + y) * sx + x] = 0;
				}
			}
		}
	}
}
