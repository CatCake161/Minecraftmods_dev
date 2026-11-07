package com.example.cinema.client.audio.environment;

/**
 * Atmospheric absorption of sound by the atmosphere, ISO 9613-1:1993.
 *
 * <p>The returned coefficient is {@code alpha} in dB/m for sound pressure
 * level. The amplitude gain over a path of {@code x} metres is
 * {@code 10^(-alpha(f,T,RH,p) * x / 20)}. The implementation follows the
 * standard's analytical method:
 *
 * <pre>
 * T   = ambient temperature in kelvin
 * T0  = 293.15 K, T01 = 273.16 K, pr = 101.325 kPa
 * psat = pr * 10^(-6.8346 * (T01/T)^1.261 + 4.6151)
 * h   = RH * psat / pa
 * frO = (pa/pr) * (24 + 4.04e4 h (0.02+h)/(0.391+h))
 * frN = (pa/pr) * (T/T0)^-0.5 * (9 + 280 h exp(-4.170 ((T/T0)^-1/3 - 1)))
 * alpha = 8.686 f^2 * (
 *     1.84e-11 (pr/pa) (T/T0)^0.5
 *     + (T/T0)^-2.5 * (
 *         0.01275 exp(-2239.1/T) / (frO + f^2/frO)
 *         + 0.1068 exp(-3352/T) / (frN + f^2/frN)))
 * </pre>
 *
 * <p>Reference: ISO 9613-1:1993, "Acoustics - Attenuation of sound during
 * propagation outdoors - Part 1: Calculation of the absorption of sound by the
 * atmosphere". The formula was cross-checked against the python-acoustics
 * implementation ({@code acoustics.standards.iso_9613_1_1993}) and the
 * octave-band table in ISO 9613-2.
 */
public final class AirAbsorption {
	/**
	 * Direct-path zero reference distance. Air absorption starts from this
	 * distance instead of from 0 m, matching the engine's
	 * {@code 1 / max(r, 1 m)} distance law.
	 */
	public static final double REFERENCE_DISTANCE_METERS = 1.0;
	/** Default reference conditions of the direct-path zero point. */
	public static final double REFERENCE_TEMPERATURE_C = 20.0;
	public static final double REFERENCE_HUMIDITY_PERCENT = 50.0;
	public static final double REFERENCE_PRESSURE_KPA = 101.325;
	public static final double MIN_TEMPERATURE_C = -20.0;
	public static final double MAX_TEMPERATURE_C = 50.0;
	private static final double REFERENCE_TEMPERATURE_K = 293.15;
	private static final double TRIPLE_POINT_TEMPERATURE_K = 273.16;

	private AirAbsorption() {
	}

	/**
	 * Returns the ISO 9613-1 attenuation coefficient in dB/m. Temperature is
	 * clamped to the standard's -20..+50 C range; humidity is clamped to
	 * 0..100 percent. Pressure defaults to 101.325 kPa but is a parameter for
	 * later altitude support.
	 */
	public static double alphaDbPerMeter(double frequencyHz, double temperatureC,
										 double humidityPercent, double pressureKpa) {
		double frequency = Math.max(0.0, frequencyHz);
		double temperature = clamp(temperatureC, MIN_TEMPERATURE_C, MAX_TEMPERATURE_C);
		double humidity = clamp(humidityPercent, 0.0, 100.0);
		double pressure = Math.max(1.0, pressureKpa);
		double kelvin = temperature + 273.15;
		double ratio = kelvin / REFERENCE_TEMPERATURE_K;
		double saturationPressure = REFERENCE_PRESSURE_KPA * Math.pow(10.0,
			-6.8346 * Math.pow(TRIPLE_POINT_TEMPERATURE_K / kelvin, 1.261) + 4.6151);
		double h = humidity * saturationPressure / pressure;
		double oxygenRelaxation = (pressure / REFERENCE_PRESSURE_KPA)
			* (24.0 + 4.04e4 * h * (0.02 + h) / (0.391 + h));
		double nitrogenRelaxation = (pressure / REFERENCE_PRESSURE_KPA)
			* Math.pow(ratio, -0.5)
			* (9.0 + 280.0 * h * Math.exp(-4.170
			* (Math.pow(ratio, -1.0 / 3.0) - 1.0)));
		double frequencySquared = frequency * frequency;
		return 8.686 * frequencySquared * (
			1.84e-11 * (REFERENCE_PRESSURE_KPA / pressure) * Math.sqrt(ratio)
				+ Math.pow(ratio, -2.5) * (
				0.01275 * Math.exp(-2239.1 / kelvin)
					/ (oxygenRelaxation + frequencySquared / oxygenRelaxation)
					+ 0.1068 * Math.exp(-3352.0 / kelvin)
					/ (nitrogenRelaxation + frequencySquared / nitrogenRelaxation)
			)
		);
	}

	/** Returns the one-way attenuation in dB over {@code distanceMeters}. */
	public static double attenuationDb(double distanceMeters, double frequencyHz,
									   double temperatureC, double humidityPercent,
									   double pressureKpa) {
		return alphaDbPerMeter(frequencyHz, temperatureC, humidityPercent, pressureKpa)
			* Math.max(0.0, distanceMeters);
	}

	/**
	 * Returns the additional direct-path air absorption beyond the 1 m
	 * reference. At 1 m (and closer) this is exactly 0 dB; beyond 1 m only the
	 * excess path {@code distance - 1 m} is attenuated. With the default
	 * 20 C / 50 % RH / 101.325 kPa conditions, 1 m is therefore the zero
	 * reference point of the direct-path air-absorption curve.
	 */
	public static double directPathAttenuationDb(double distanceMeters,
												 double frequencyHz,
												 double temperatureC,
												 double humidityPercent,
												 double pressureKpa) {
		return attenuationDb(Math.max(0.0,
				distanceMeters - REFERENCE_DISTANCE_METERS),
			frequencyHz, temperatureC, humidityPercent, pressureKpa);
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
