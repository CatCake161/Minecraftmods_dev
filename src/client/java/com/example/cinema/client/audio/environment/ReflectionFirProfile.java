package com.example.cinema.client.audio.environment;

import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;

/**
 * Immutable request for the reflection-path FIR.
 *
 * <p>The FIR carries the reflection-angle directivity shape and air absorption,
 * normalized to 0 dB at the room's Schroeder frequency. The broadband source
 * trim at that frequency is exposed separately by {@link #trimGain()}, so the
 * FIR never applies the same level twice.
 */
public record ReflectionFirProfile(SpeakerDataProfile speaker,
								   double horizontalDegrees,
								   double verticalDegrees,
								   double pathLengthMeters,
								   boolean airAbsorption,
								   double temperatureC,
								   double humidityPercent,
								   double pressureKpa,
								   double schroederHz) {
	public static final double MIN_SCHROEDER_HZ = 50.0;
	public static final double MAX_SCHROEDER_HZ = 2_000.0;
	public static final double DEFAULT_SCHROEDER_HZ = 1_000.0;

	public ReflectionFirProfile {
		horizontalDegrees = Double.isFinite(horizontalDegrees) ? horizontalDegrees : 0.0;
		verticalDegrees = Double.isFinite(verticalDegrees) ? verticalDegrees : 0.0;
		pathLengthMeters = clamp(pathLengthMeters, 0.0, 256.0, 0.0);
		temperatureC = clamp(temperatureC, AirAbsorption.MIN_TEMPERATURE_C,
			AirAbsorption.MAX_TEMPERATURE_C, AirAbsorption.MIN_TEMPERATURE_C);
		humidityPercent = clamp(humidityPercent, 0.0, 100.0, 50.0);
		pressureKpa = clamp(pressureKpa, 50.0, 110.0,
			AirAbsorption.REFERENCE_PRESSURE_KPA);
		schroederHz = clamp(schroederHz, MIN_SCHROEDER_HZ, MAX_SCHROEDER_HZ,
			DEFAULT_SCHROEDER_HZ);
	}

	/**
	 * Combined on-axis EQ, reflection-angle directivity and air absorption in
	 * dB at one frequency. The FIR uses this minus its value at
	 * {@link #schroederHz()}.
	 */
	public double combinedDb(double frequencyHz) {
		double db = 0.0;
		if (speaker != null) {
			db += speaker.onAxisEqGainDb(frequencyHz);
			db += speaker.gainDb(horizontalDegrees, verticalDegrees, frequencyHz);
		}
		if (airAbsorption) {
			db -= AirAbsorption.directPathAttenuationDb(pathLengthMeters,
				frequencyHz, temperatureC, humidityPercent, pressureKpa);
		}
		return db;
	}

	/** Broadband source trim at the Schroeder frequency. */
	public double trimGain() {
		double db = combinedDb(schroederHz);
		if (!Double.isFinite(db)) {
			return 0.0;
		}
		double clamped = Math.max(-120.0, Math.min(24.0, db));
		return Math.pow(10.0, clamped / 20.0);
	}

	public String speakerId() {
		return speaker == null ? "" : speaker.id();
	}

	private static double clamp(double value, double min, double max,
								double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
