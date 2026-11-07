package com.example.cinema.client.audio.acoustics;

import com.example.cinema.client.audio.filter.BiquadChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialReflectionEqTest {
	private static final double SAMPLE_RATE = 48_000.0;

	@Test
	void bandCentersMatchThePressureReflectionTargets() {
		AcousticMaterial material = new AcousticMaterial("test", 0.20, 0.50, 0.80,
			0.10);
		BiquadChain chain = MaterialReflectionEq.chain(material);
		double mid = pressure(material.mid());

		assertEquals(relativeDb(pressure(material.low()), mid),
			chain.magnitudeDb(MaterialReflectionEq.LOW_BAND_HZ, SAMPLE_RATE), 0.1);
		assertEquals(0.0,
			chain.magnitudeDb(MaterialReflectionEq.MID_BAND_HZ, SAMPLE_RATE), 0.1);
		assertEquals(relativeDb(pressure(material.high()), mid),
			chain.magnitudeDb(MaterialReflectionEq.HIGH_BAND_HZ, SAMPLE_RATE), 0.1);
	}

	@Test
	void equalBandsProduceAFlatChain() {
		AcousticMaterial material = new AcousticMaterial("flat", 0.4, 0.4, 0.4, 0.0);

		assertSame(BiquadChain.FLAT, MaterialReflectionEq.chain(material));
	}

	@Test
	void cacheReturnsTheSameChainForTheSameMaterial() {
		AcousticMaterial material = new AcousticMaterial("cached", 0.1, 0.3, 0.6, 0.0);

		assertSame(MaterialReflectionEq.chain(material),
			MaterialReflectionEq.chain(material));
		assertTrue(MaterialReflectionEq.cacheSize() > 0);
	}

	private static double pressure(double absorption) {
		return Math.sqrt(Math.max(0.0, 1.0 - absorption));
	}

	private static double relativeDb(double value, double reference) {
		return 20.0 * Math.log10(value / reference);
	}
}
