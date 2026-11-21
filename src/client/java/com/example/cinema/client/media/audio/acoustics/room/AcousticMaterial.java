package com.example.cinema.client.media.audio.acoustics.room;

/**
 * Approximate octave-band energy absorption and scattering for one surface
 * material. Values are energy coefficients in [0, 1].
 *
 * <p>The values are engineering placeholders for A1/A3, not measured
 * Minecraft-block data. Scattering is stored per octave band because real
 * surface scattering is frequency dependent; the single-argument constructor
 * keeps the old flat-scattering call sites working.
 */
public record AcousticMaterial(String name, double low, double mid, double high,
							   double scatteringLow, double scatteringMid,
							   double scatteringHigh) {
	public AcousticMaterial {
		low = clamp01(low);
		mid = clamp01(mid);
		high = clamp01(high);
		scatteringLow = clamp01(scatteringLow);
		scatteringMid = clamp01(scatteringMid);
		scatteringHigh = clamp01(scatteringHigh);
	}

	/** Backward-compatible flat scattering. */
	public AcousticMaterial(String name, double low, double mid, double high,
							double scattering) {
		this(name, low, mid, high, scattering, scattering, scattering);
	}

	/** Mid-band scattering, kept for old diagnostics/callers. */
	public double scattering() {
		return scatteringMid;
	}

	public static AcousticMaterial mix(String name, double low, double mid,
									   double high, double scatteringLow,
									   double scatteringMid,
									   double scatteringHigh, int samples) {
		if (samples <= 0) {
			return new AcousticMaterial(name, 0.0, 0.0, 0.0, 0.0);
		}
		return new AcousticMaterial(name, low / samples, mid / samples,
			high / samples, scatteringLow / samples,
			scatteringMid / samples, scatteringHigh / samples);
	}

	/** Backward-compatible flat-scattering mix. */
	public static AcousticMaterial mix(String name, double low, double mid,
									   double high, double scattering,
									   int samples) {
		return mix(name, low, mid, high, scattering, scattering, scattering,
			samples);
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}
}
