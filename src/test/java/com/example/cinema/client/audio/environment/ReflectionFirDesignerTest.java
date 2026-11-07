package com.example.cinema.client.audio.environment;

import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionFirDesignerTest {
	private static final double SAMPLE_RATE = 48_000.0;

	@Test
	void airAbsorptionDesignUses2048TapsNormalizedAtSchroeder() {
		ReflectionFirProfile profile = new ReflectionFirProfile(null, 0.0, 0.0,
			10.0, true, 20.0, 50.0, 101.325, 500.0);

		MinimumPhaseFir fir = ReflectionFirDesigner.design(profile);

		assertEquals(2_048, fir.length());
		double response = MinimumPhaseFirDesigner.responseAt(fir.taps(), 500.0,
			SAMPLE_RATE);
		assertEquals(1.0, response, 0.02);
		assertEquals(profile.trimGain(), fir.overallGain(), 1.0e-9);
		assertTrue(profile.trimGain() > 0.0 && profile.trimGain() <= 1.0);
	}

	@Test
	void disabledAirAbsorptionProducesAFlatFilter() {
		ReflectionFirProfile profile = new ReflectionFirProfile(null, 0.0, 0.0,
			10.0, false, 20.0, 50.0, 101.325, 500.0);

		MinimumPhaseFir fir = ReflectionFirDesigner.design(profile);

		assertTrue(fir.isFlat());
		assertEquals(1.0, fir.overallGain(), 1.0e-9);
	}

	@Test
	void schroederFallbackKeepsTrimFinite() {
		ReflectionFirProfile profile = new ReflectionFirProfile(null, 0.0, 0.0,
			10.0, false, 20.0, 50.0, 101.325, Double.NaN);

		assertEquals(ReflectionFirProfile.DEFAULT_SCHROEDER_HZ,
			profile.schroederHz(), 1.0e-9);
		assertEquals(1.0, profile.trimGain(), 1.0e-9);
	}
}
