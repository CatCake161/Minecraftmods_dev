package com.example.cinema.client.audio.acoustics;

/**
 * Boundary area and area-weighted energy absorption for one axis pair.
 *
 * <p>Fitzroy's reverberation formula needs the total area of the two opposite
 * boundary planes perpendicular to each room axis and their mean absorption.
 * The flood fill reports the measured boundary per axis; the geometric box
 * fallback derives the same values from the six sampled planes. Open boundary
 * faces are counted as fully absorbing, matching the A1 opening approximation.
 */
public record AxisAbsorption(double area, double alphaLow, double alphaMid,
							 double alphaHigh) {
	public static final AxisAbsorption EMPTY =
		new AxisAbsorption(0.0, 0.0, 0.0, 0.0);

	public AxisAbsorption {
		area = Double.isFinite(area) ? Math.max(0.0, area) : 0.0;
		alphaLow = clamp01(alphaLow);
		alphaMid = clamp01(alphaMid);
		alphaHigh = clamp01(alphaHigh);
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}
}
