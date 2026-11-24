package com.example.cinema.client.media.audio.dsp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host-side binning contract for the order &gt;= 3 late field. No GPU is
 * involved: the bins consume {@link CudaRayTracer.Hit} records, so the merge
 * arithmetic can be checked directly.
 */
class RtLateFieldBinsTest {
	private static final int CHANNEL = 2;
	private static final int ORDER = 3;
	private static final float HIGH = 0.2f;
	private static final int MATERIAL = 5;

	@Test
	void directionBinsSpanTheSphere() {
		int rays = 2000;
		boolean[] used = new boolean[RtLateFieldBins.DIR_BINS];
		int distinct = 0;
		for (int i = 0; i < rays; i++) {
			float z = 1.0f - 2.0f * ((float) i + 0.5f) / rays;
			float r = (float) Math.sqrt(Math.max(0.0f, 1.0f - z * z));
			float phi = (float) i * 2.39996322972865332f;
			float x = (float) Math.cos(phi) * r;
			float y = (float) Math.sin(phi) * r;
			int bin = RtLateFieldBins.directionBin(x, y, z);
			assertTrue(bin >= 0 && bin < RtLateFieldBins.DIR_BINS,
				"bin out of range: " + bin);
			if (!used[bin]) {
				used[bin] = true;
				distinct++;
			}
		}
		assertTrue(distinct > RtLateFieldBins.DIR_BINS * 3 / 4,
			"a full-sphere ray set must fill the octahedral map, got "
				+ distinct);
		assertNotEquals(RtLateFieldBins.directionBin(0.0f, 0.0f, 1.0f),
			RtLateFieldBins.directionBin(0.0f, 0.0f, -1.0f),
			"the two poles must not share a bin");
		assertNotEquals(RtLateFieldBins.directionBin(1.0f, 0.0f, 0.0f),
			RtLateFieldBins.directionBin(-1.0f, 0.0f, 0.0f),
			"antipodal directions must not share a bin");
	}

	@Test
	void binIndexLayoutIsUnique() {
		int cells = RtLateFieldBins.CHANNELS * RtLateFieldBins.DELAY_BINS
			* RtLateFieldBins.DIR_BINS;
		assertEquals(cells, RtLateFieldBins.BIN_COUNT);
		assertEquals(0, RtLateFieldBins.index(0, 0, 0));
		assertEquals(cells - 1, RtLateFieldBins.index(
			RtLateFieldBins.CHANNELS - 1, RtLateFieldBins.DELAY_BINS - 1,
			RtLateFieldBins.DIR_BINS - 1));
	}

	@Test
	void energyAddsIncoherentlyAndPassAverageIsRms() {
		CudaRayTracer.Hit loud = hit(0.023f, 1.0f, 0.0f, 0.0f, 0.5f);
		// Same octahedral cell as +x, so both hits must land in one bin.
		CudaRayTracer.Hit soft = hit(0.027f, 0.9950372f, 0.09950372f, 0.0f,
			0.3f);

		RtLateFieldBins onePass = new RtLateFieldBins();
		assertTrue(onePass.accumulate(loud, loud.delaySeconds()));
		assertTrue(onePass.accumulate(soft, soft.delaySeconds()));
		onePass.finishPass();
		List<RtLateFieldBins.BinTap> single = onePass.taps(0.0);
		assertEquals(1, single.size(),
			"one delay/direction cell must merge into one tap");
		double expectedGain = Math.sqrt(0.5 * 0.5 + 0.3 * 0.3);
		assertEquals(expectedGain, single.get(0).gain(), 1.0e-6);
		assertEquals(0.34, onePass.meanEnergy(), 1.0e-6);
		double expectedDelay = (0.25 * loud.delaySeconds()
			+ 0.09 * soft.delaySeconds()) / 0.34;
		assertEquals(expectedDelay, single.get(0).delaySeconds(), 1.0e-6);
		float[] direction = single.get(0).direction();
		double length = Math.sqrt(direction[0] * direction[0]
			+ direction[1] * direction[1]
			+ direction[2] * direction[2]);
		assertEquals(1.0, length, 1.0e-6);
		assertTrue(direction[0] > 0.99f,
			"the louder hit must dominate the centroid x: " + direction[0]);
		assertTrue(direction[1] > 0.01f && direction[1] < 0.05f,
			"the centroid must lean towards the softer hit's y: "
				+ direction[1]);
		assertEquals(0.0f, direction[2], 1.0e-9f);
		assertEquals(CHANNEL, single.get(0).channel());
		assertEquals(MATERIAL, single.get(0).material());
		assertTrue(single.get(0).order() >= ORDER);

		// Repeating the same hits as a second pass must not raise the level:
		// the published gain is the RMS of the per-pass amplitudes.
		assertTrue(onePass.accumulate(loud, loud.delaySeconds()));
		assertTrue(onePass.accumulate(soft, soft.delaySeconds()));
		onePass.finishPass();
		List<RtLateFieldBins.BinTap> two = onePass.taps(0.0);
		assertEquals(1, two.size());
		assertEquals(expectedGain, two.get(0).gain(), 1.0e-6,
			"two identical passes must publish the same amplitude");
		assertEquals(0.34, onePass.meanEnergy(), 1.0e-6);
		double tapPower = 0.0;
		for (RtLateFieldBins.BinTap tap : two) {
			tapPower += tap.gain() * tap.gain();
		}
		assertEquals(onePass.meanEnergy(), tapPower, 1.0e-9);
	}

	@Test
	void separateDelayAndDirectionCellsStaySeparate() {
		RtLateFieldBins bins = new RtLateFieldBins();
		assertTrue(bins.accumulate(hit(0.02f, 1.0f, 0.0f, 0.0f, 0.5f), 0.02));
		assertTrue(bins.accumulate(hit(0.05f, 1.0f, 0.0f, 0.0f, 0.5f), 0.05));
		assertTrue(bins.accumulate(hit(0.02f, -1.0f, 0.0f, 0.0f, 0.5f), 0.02));
		bins.finishPass();
		assertEquals(3, bins.taps(0.0).size(),
			"delay and direction cells must not collapse together");
	}

	@Test
	void rejectsOutOfWindowAndSilentHits() {
		RtLateFieldBins bins = new RtLateFieldBins();
		assertFalse(bins.accumulate(hit(0.02f, 1.0f, 0.0f, 0.0f, 0.0f), 0.02),
			"a hit that reached no speaker is not binnable");
		assertFalse(bins.accumulate(hit(0.02f, 1.0f, 0.0f, 0.0f, 0.5f),
			RtLateFieldBins.MAX_DELAY_SECONDS + 0.001),
			"delays past the window must be dropped");
		assertFalse(bins.accumulate(new CudaRayTracer.Hit(0.02f, 1.0f, 0.0f,
			0.0f, 0.5f, -1, 0.5f, ORDER, HIGH, MATERIAL), 0.02),
			"channel -1 is not routable");
		bins.finishPass();
		assertTrue(bins.taps(0.0).isEmpty());
		assertEquals(0.0, bins.meanEnergy(), 0.0);
	}

	@Test
	void resetClearsBinsAndPasses() {
		RtLateFieldBins bins = new RtLateFieldBins();
		bins.accumulate(hit(0.02f, 1.0f, 0.0f, 0.0f, 0.5f), 0.02);
		bins.finishPass();
		assertEquals(1, bins.passes());
		bins.reset();
		assertEquals(0, bins.passes());
		assertTrue(bins.taps(0.0).isEmpty());
		assertEquals(0.0, bins.rawEnergy(), 0.0);
	}

	private static CudaRayTracer.Hit hit(float delay, float dx, float dy,
										 float dz, float gain) {
		return new CudaRayTracer.Hit(delay, dx, dy, dz, gain * gain, CHANNEL,
			gain, ORDER, HIGH, MATERIAL);
	}
}
