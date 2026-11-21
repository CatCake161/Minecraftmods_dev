package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcousticScatteringTest {
	private static final double EPS = 1.0e-12;

	@Test
	void zeroScatteringIsSpecularOnly() {
		assertEquals(Math.sqrt(1.0 - 0.2),
			AcousticScattering.specularPressure(0.2, 0.0), EPS);
		assertEquals(0.0, AcousticScattering.scatteredPressure(0.2, 0.0), EPS);
	}

	@Test
	void fullScatteringRemovesSpecular() {
		assertEquals(0.0, AcousticScattering.specularPressure(0.2, 1.0), EPS);
		assertEquals(Math.sqrt(1.0 - 0.2),
			AcousticScattering.scatteredPressure(0.2, 1.0), EPS);
	}

	@Test
	void specularAndScatteredSquaresPreserveRemainingEnergy() {
		double alpha = 0.35;
		double scattering = 0.4;
		double specular = AcousticScattering.specularPressure(alpha, scattering);
		double scattered = AcousticScattering.scatteredPressure(alpha, scattering);

		assertEquals(1.0 - alpha,
			specular * specular + scattered * scattered, EPS);
	}

	@Test
	void multiBounceUsesLumpedEnergyConservingFormula() {
		AcousticMaterial first = new AcousticMaterial("a", 0.1, 0.2, 0.3, 0.5);
		AcousticMaterial second = new AcousticMaterial("b", 0.2, 0.4, 0.5, 0.1);
		double expectedRemaining = (1.0 - 0.2) * (1.0 - 0.4);
		double specular = AcousticScattering.specularPressure(
			List.of(first, second));
		double scattered = AcousticScattering.scatteredPressure(
			List.of(first, second));

		assertEquals(expectedRemaining,
			specular * specular + scattered * scattered, EPS);
		assertTrue(specular > 0.0);
		assertTrue(scattered > 0.0);
	}

	@Test
	void anyFullScatteringBounceRemovesTheSpecularPath() {
		AcousticMaterial first = new AcousticMaterial("a", 0.1, 0.2, 0.3, 0.0);
		AcousticMaterial second = new AcousticMaterial("b", 0.2, 0.4, 0.5, 1.0);

		assertEquals(0.0, AcousticScattering.specularPressure(
			List.of(first, second)), EPS);
		assertEquals(Math.sqrt((1.0 - 0.2) * (1.0 - 0.4)),
			AcousticScattering.scatteredPressure(List.of(first, second)), EPS);
	}

	@Test
	void frequencyDependentMaterialsUsePerBandProduct() {
		AcousticMaterial material = new AcousticMaterial("bands",
			0.10, 0.20, 0.30, 0.10, 0.40, 0.70);
		AcousticMaterial specular = AcousticScattering.specularMaterial(
			"spec", List.of(material));
		AcousticMaterial scattered = AcousticScattering.scatteredMaterial(
			"scat", List.of(material));

		assertEquals(1.0 - (1.0 - 0.10) * (1.0 - 0.10),
			specular.low(), EPS);
		assertEquals(1.0 - (1.0 - 0.20) * (1.0 - 0.40),
			specular.mid(), EPS);
		assertEquals(1.0 - (1.0 - 0.30) * (1.0 - 0.70),
			specular.high(), EPS);
		assertEquals(1.0 - (1.0 - 0.10) * 0.10,
			scattered.low(), EPS);
		assertEquals(1.0 - (1.0 - 0.20) * 0.40,
			scattered.mid(), EPS);
		assertEquals(1.0 - (1.0 - 0.30) * 0.70,
			scattered.high(), EPS);
	}

	@Test
	void emptyPathHasNoScatteredEnergy() {
		assertEquals(1.0, AcousticScattering.specularPressure(List.of()), EPS);
		assertEquals(0.0, AcousticScattering.scatteredPressure(List.of()), EPS);
	}

	@Test
	void nonFiniteValuesAreClampedSafely() {
		assertEquals(1.0,
			AcousticScattering.specularPressure(Double.NaN, Double.NaN), EPS);
		assertEquals(0.0,
			AcousticScattering.scatteredPressure(Double.NaN, Double.NaN), EPS);
		assertEquals(0.0,
			AcousticScattering.specularPressure(2.0, 2.0), EPS);
		assertEquals(0.0,
			AcousticScattering.scatteredPressure(-1.0, -1.0), EPS);
	}
}
