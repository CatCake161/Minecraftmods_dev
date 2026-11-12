package com.example.cinema.client.media.audio.acoustics.room;

/**
 * Approximate octave-band energy absorption and scattering for one surface
 * material. Values are energy absorption coefficients in [0, 1].
 *
 * <p>The initial values are engineering placeholders for A1, not calibrated
 * acoustic data. They must be checked in-game and may be replaced by a
 * data-driven material table later.
 */
public record AcousticMaterial(String name, double low, double mid, double high,
							   double scattering) {
	public AcousticMaterial {
		low = clamp01(low);
		mid = clamp01(mid);
		high = clamp01(high);
		scattering = clamp01(scattering);
	}

	public static AcousticMaterial mix(String name, double low, double mid, double high,
									   double scattering, int samples) {
		if (samples <= 0) {
			return new AcousticMaterial(name, 0.0, 0.0, 0.0, 0.0);
		}
		return new AcousticMaterial(name, low / samples, mid / samples,
			high / samples, scattering / samples);
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}
}
