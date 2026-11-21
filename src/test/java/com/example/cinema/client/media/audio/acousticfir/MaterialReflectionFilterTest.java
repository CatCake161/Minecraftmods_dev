package com.example.cinema.client.media.audio.acousticfir;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialReflectionFilterTest {
	private static final AcousticMaterial MATERIAL =
		new AcousticMaterial("test", 0.40, 0.20, 0.50, 0.0);
	private static final double EPS = 1.0e-9;

	@Test
	void matchesTheFirMaterialCurveAtTheBandCenters() {
		MaterialReflectionFilter filter =
			MaterialReflectionFilter.of(MATERIAL, MaterialReflectionFilter.DEFAULT_SAMPLE_RATE);
		MaterialReflectionCurve curve = MaterialReflectionCurve.of(MATERIAL);

		assertEquals(curve.db(MaterialReflectionCurve.LOW_BAND_HZ),
			filter.magnitudeDb(MaterialReflectionCurve.LOW_BAND_HZ,
				MaterialReflectionFilter.DEFAULT_SAMPLE_RATE), 0.01);
		assertEquals(curve.db(MaterialReflectionCurve.MID_BAND_HZ),
			filter.magnitudeDb(MaterialReflectionCurve.MID_BAND_HZ,
				MaterialReflectionFilter.DEFAULT_SAMPLE_RATE), 0.01);
		assertEquals(curve.db(MaterialReflectionCurve.HIGH_BAND_HZ),
			filter.magnitudeDb(MaterialReflectionCurve.HIGH_BAND_HZ,
				MaterialReflectionFilter.DEFAULT_SAMPLE_RATE), 0.01);
	}

	@Test
	void midBandIsTheZeroDecibelReference() {
		MaterialReflectionFilter filter =
			MaterialReflectionFilter.of(MATERIAL, MaterialReflectionFilter.DEFAULT_SAMPLE_RATE);

		assertEquals(0.0, filter.magnitudeDb(
			MaterialReflectionCurve.MID_BAND_HZ,
			MaterialReflectionFilter.DEFAULT_SAMPLE_RATE), 0.2);
		assertFalse(filter.isFlat());
	}

	@Test
	void nullMaterialIsFlat() {
		assertTrue(MaterialReflectionFilter.of(null,
			MaterialReflectionFilter.DEFAULT_SAMPLE_RATE).isFlat());
		assertTrue(MaterialReflectionFilter.of(
			new AcousticMaterial("flat", 0.0, 0.0, 0.0, 0.0),
			MaterialReflectionFilter.DEFAULT_SAMPLE_RATE).isFlat());
	}

	@Test
	void combineUsesEnergyProducts() {
		AcousticMaterial first =
			new AcousticMaterial("a", 0.10, 0.20, 0.30, 0.10);
		AcousticMaterial second =
			new AcousticMaterial("b", 0.20, 0.40, 0.50, 0.20);
		AcousticMaterial combined = AcousticMaterials.combine(
			List.of(first, second));

		assertEquals(1.0 - (1.0 - 0.10) * (1.0 - 0.20),
			combined.low(), EPS);
		assertEquals(1.0 - (1.0 - 0.20) * (1.0 - 0.40),
			combined.mid(), EPS);
		assertEquals(1.0 - (1.0 - 0.30) * (1.0 - 0.50),
			combined.high(), EPS);
		assertEquals(1.0 - (1.0 - 0.10) * (1.0 - 0.20),
			combined.scattering(), EPS);
	}
}
