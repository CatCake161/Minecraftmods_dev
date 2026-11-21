package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

import java.util.List;

/**
 * Frequency-dependent specular/scattered pressure split for reflections.
 *
 * <p>For one surface and one octave band, the incident energy is split into
 * {@code (1-alpha)*(1-s)} specular and {@code (1-alpha)*s} scattered energy.
 * The returned values are pressure amplitudes. Higher-order paths use the
 * per-band products
 * {@code specular^2 = product((1-alpha_i)*(1-s_i))} and
 * {@code scattered^2 = product(1-alpha_i) * (1-product(1-s_i))}.
 *
 * <p>{@link #specularMaterial} and {@link #scatteredMaterial} convert those
 * per-band energies back into an effective {@link AcousticMaterial}, so the
 * existing FIR/PEQ material curve can carry the frequency-dependent shape.
 */
public final class AcousticScattering {
	private AcousticScattering() {
	}

	/** Specular pressure coefficient for one reflection and band. */
	public static double specularPressure(double alpha, double scattering) {
		double absorption = clamp01(alpha);
		double diffuse = clamp01(scattering);
		return Math.sqrt(Math.max(0.0,
			(1.0 - absorption) * (1.0 - diffuse)));
	}

	/** Scattered pressure coefficient for one reflection and band. */
	public static double scatteredPressure(double alpha, double scattering) {
		double absorption = clamp01(alpha);
		double diffuse = clamp01(scattering);
		return Math.sqrt(Math.max(0.0,
			(1.0 - absorption) * diffuse));
	}

	/**
	 * Product of the mid-band specular pressure coefficients over all bounces.
	 * An empty path returns {@code 1.0}; callers should still reject empty
	 * geometry before using the result.
	 */
	public static double specularPressure(List<AcousticMaterial> materials) {
		if (materials == null || materials.isEmpty()) {
			return 1.0;
		}
		double product = 1.0;
		for (AcousticMaterial material : materials) {
			if (material == null) {
				continue;
			}
			product *= specularPressure(material.mid(), material.scatteringMid());
		}
		return product;
	}

	/** Lumped mid-band scattered pressure coefficient over all bounces. */
	public static double scatteredPressure(List<AcousticMaterial> materials) {
		if (materials == null || materials.isEmpty()) {
			return 0.0;
		}
		double remainingEnergy = 1.0;
		double specularProbability = 1.0;
		for (AcousticMaterial material : materials) {
			if (material == null) {
				continue;
			}
			remainingEnergy *= 1.0 - clamp01(material.mid());
			specularProbability *= 1.0 - clamp01(material.scatteringMid());
		}
		return Math.sqrt(Math.max(0.0,
			remainingEnergy * (1.0 - specularProbability)));
	}

	/**
	 * Effective material for the specular path. Its absorption per band is
	 * {@code 1 - product((1-alpha_i)*(1-s_i))}, so a material curve applied to
	 * it carries the frequency-dependent scattering attenuation.
	 */
	public static AcousticMaterial specularMaterial(
		String name, List<AcousticMaterial> materials) {
		if (materials == null || materials.isEmpty()) {
			return new AcousticMaterial(name, 0.0, 0.0, 0.0, 0.0);
		}
		double lowRemaining = 1.0;
		double midRemaining = 1.0;
		double highRemaining = 1.0;
		for (AcousticMaterial material : materials) {
			if (material == null) {
				continue;
			}
			lowRemaining *= (1.0 - clamp01(material.low()))
				* (1.0 - clamp01(material.scatteringLow()));
			midRemaining *= (1.0 - clamp01(material.mid()))
				* (1.0 - clamp01(material.scatteringMid()));
			highRemaining *= (1.0 - clamp01(material.high()))
				* (1.0 - clamp01(material.scatteringHigh()));
		}
		return new AcousticMaterial(name,
			1.0 - lowRemaining, 1.0 - midRemaining, 1.0 - highRemaining, 0.0);
	}

	/**
	 * Effective material for the scattered path. Its absorption per band is
	 * {@code 1 - product(1-alpha_i) * (1-product(1-s_i))}, matching the lumped
	 * diffuse energy.
	 */
	public static AcousticMaterial scatteredMaterial(
		String name, List<AcousticMaterial> materials) {
		if (materials == null || materials.isEmpty()) {
			return new AcousticMaterial(name, 0.0, 0.0, 0.0, 0.0);
		}
		double lowEnergy = 1.0;
		double midEnergy = 1.0;
		double highEnergy = 1.0;
		double lowSpecular = 1.0;
		double midSpecular = 1.0;
		double highSpecular = 1.0;
		for (AcousticMaterial material : materials) {
			if (material == null) {
				continue;
			}
			lowEnergy *= 1.0 - clamp01(material.low());
			midEnergy *= 1.0 - clamp01(material.mid());
			highEnergy *= 1.0 - clamp01(material.high());
			lowSpecular *= 1.0 - clamp01(material.scatteringLow());
			midSpecular *= 1.0 - clamp01(material.scatteringMid());
			highSpecular *= 1.0 - clamp01(material.scatteringHigh());
		}
		return new AcousticMaterial(name,
			1.0 - lowEnergy * (1.0 - lowSpecular),
			1.0 - midEnergy * (1.0 - midSpecular),
			1.0 - highEnergy * (1.0 - highSpecular), 0.0);
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}
}
