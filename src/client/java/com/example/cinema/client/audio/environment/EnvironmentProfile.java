package com.example.cinema.client.audio.environment;

/**
 * Immutable request for the direct-path environment FIR.
 *
 * <p>The FIR carries frequency-dependent air absorption and the occlusion
 * low-pass shape. Broadband occlusion attenuation is applied separately to the
 * source gain, and distance attenuation is applied by the audio engine from the
 * smoothed source radius. Keeping the three effects separate makes it obvious
 * that none of them is applied twice.
 */
public record EnvironmentProfile(double distanceMeters, boolean airAbsorption,
								 double temperatureC, double humidityPercent,
								 double pressureKpa, double occlusionCutoffHz,
								 int occlusionOrder) {
	public EnvironmentProfile {
		distanceMeters = clamp(distanceMeters, 0.0, 256.0, 0.0);
		temperatureC = clamp(temperatureC, AirAbsorption.MIN_TEMPERATURE_C,
			AirAbsorption.MAX_TEMPERATURE_C, AirAbsorption.MIN_TEMPERATURE_C);
		humidityPercent = clamp(humidityPercent, 0.0, 100.0, 50.0);
		pressureKpa = clamp(pressureKpa, 50.0, 110.0,
			AirAbsorption.REFERENCE_PRESSURE_KPA);
		occlusionCutoffHz = clamp(occlusionCutoffHz, 20.0, 24_000.0, 20_000.0);
		occlusionOrder = Math.max(0, Math.min(4, occlusionOrder));
	}

	public boolean isFlat() {
		return (!airAbsorption
			|| distanceMeters <= AirAbsorption.REFERENCE_DISTANCE_METERS)
			&& occlusionOrder <= 0;
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
