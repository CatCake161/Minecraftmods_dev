package com.example.cinema.client.audio.fir;

import com.example.cinema.client.audio.acoustics.OcclusionResult;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;

/**
 * Builds the common minimum-phase FIR target for direct and reflected sound.
 *
 * <p>The target is always the same frequency-shaping chain:
 * on-axis + directivity relative to 1 kHz, minus air absorption, plus optional
 * material reflection, optional occlusion low-pass and the reflection-only
 * Schroeder hard cut. Broadband levels (speaker trim, distance, material
 * mid-band coefficient and occlusion broadband attenuation) are deliberately
 * not part of this filter; they belong to the source gain.
 */
public final class CommonAcousticFirDesigner {
	public static final double SAMPLE_RATE = 48_000.0;
	public static final double REFERENCE_HZ = 1_000.0;
	public static final double MIN_HZ = 20.0;
	public static final double MAX_HZ = 20_000.0;
	private static final int CURVE_POINTS = 512;
	private static final double MIN_DB = -96.0;
	private static final double FLAT_THRESHOLD_DB = 0.01;
	private static final double MAX_ERROR_TARGET_DB = -40.0;
	/** Ignore the unavoidable transition just above the hard cut. */
	private static final double CUT_TRANSITION_RATIO = 1.5;
	private static final double CUT_TRANSITION_HZ = 50.0;
	private static final double MIN_OVERALL_DB = -120.0;
	private static final double MAX_OVERALL_DB = 24.0;

	private CommonAcousticFirDesigner() {
	}

	public static MinimumPhaseFir design(CommonAcousticFirProfile profile) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		int half = profile.fftSize() / 2;
		double[] target = new double[half + 1];
		double overallDb = overallDb(profile);
		MaterialReflectionCurve material =
			MaterialReflectionCurve.of(profile.material());
		double[] directivityCurve = new double[CURVE_POINTS];
		double[] airCurve = new double[CURVE_POINTS];
		double[] materialCurve = new double[CURVE_POINTS];
		for (int i = 0; i < CURVE_POINTS; i++) {
			double t = i / (double) (CURVE_POINTS - 1);
			double frequency = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t);
			directivityCurve[i] = onAxisDirectivityDb(profile, frequency);
			airCurve[i] = -airAbsorptionDb(profile, frequency);
			materialCurve[i] = material.db(frequency);
		}
		boolean shaped = false;
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / profile.fftSize();
			target[i] = targetDb(profile, frequency, overallDb, directivityCurve,
				airCurve, materialCurve);
			if (Math.abs(target[i]) >= FLAT_THRESHOLD_DB) {
				shaped = true;
			}
		}
		double overallGain = gain(overallDb);
		String id = profile.id();
		if (!shaped) {
			return new MinimumPhaseFir(new float[]{1.0f}, overallGain, overallDb,
				0.0, id, profile.horizontalDegrees(), profile.verticalDegrees());
		}

		double referenceHz = referenceHz(profile);
		int referenceBin = (int) Math.round(referenceHz * profile.fftSize()
			/ SAMPLE_RATE);
		referenceBin = Math.max(0, Math.min(half, referenceBin));
		double referenceDb = target[referenceBin];
		MinimumPhaseFir shape = MinimumPhaseFirDesigner.designMagnitude(target,
			profile.fftSize(), profile.taps(), SAMPLE_RATE, referenceHz, id);
		float[] taps = shape.taps().clone();
		double scale = gain(referenceDb);
		for (int i = 0; i < taps.length; i++) {
			taps[i] *= (float) scale;
		}
		double[] responseDb = MinimumPhaseFirDesigner.magnitudeDb(taps,
			profile.fftSize());
		double maxError = maxError(profile, target, responseDb,
			profile.fftSize());
		return new MinimumPhaseFir(taps, overallGain, overallDb, maxError, id,
			profile.horizontalDegrees(), profile.verticalDegrees());
	}

	/**
	 * Frequency-shaping target in dB relative to the on-axis/directivity level
	 * at 1 kHz. The reflection profile returns the hard-cut floor at and below
	 * the Schroeder frequency.
	 */
	public static double targetDb(CommonAcousticFirProfile profile,
								  double frequencyHz) {
		double frequency = Math.max(MIN_HZ, Math.min(MAX_HZ, frequencyHz));
		if (profile.isReflection() && frequency <= profile.schroederHz()) {
			return MIN_DB;
		}
		double db = onAxisDirectivityDb(profile, frequency) - overallDb(profile)
			- airAbsorptionDb(profile, frequency)
			+ MaterialReflectionCurve.of(profile.material()).db(frequency)
			+ occlusionLowPassDb(profile.occlusion(), frequency);
		return Math.max(MIN_DB, db);
	}

	private static double targetDb(CommonAcousticFirProfile profile,
								   double frequencyHz, double overallDb,
								   double[] directivityCurve,
								   double[] airCurve,
								   double[] materialCurve) {
		double frequency = Math.max(MIN_HZ, Math.min(MAX_HZ, frequencyHz));
		if (profile.isReflection() && frequency <= profile.schroederHz()) {
			return MIN_DB;
		}
		double db = interpolateCurve(directivityCurve, frequency) - overallDb
			+ interpolateCurve(airCurve, frequency)
			+ interpolateCurve(materialCurve, frequency)
			+ occlusionLowPassDb(profile.occlusion(), frequency);
		return Math.max(MIN_DB, db);
	}

	private static double interpolateCurve(double[] curve, double frequencyHz) {
		double t = Math.log(frequencyHz / MIN_HZ)
			/ Math.log(MAX_HZ / MIN_HZ) * (curve.length - 1);
		t = Math.max(0.0, Math.min(curve.length - 1.0, t));
		int index = (int) t;
		if (index >= curve.length - 1) {
			return curve[curve.length - 1];
		}
		double fraction = t - index;
		return curve[index] + (curve[index + 1] - curve[index]) * fraction;
	}

	/** On-axis plus directivity level at 1 kHz; excludes all other terms. */
	public static double overallDb(CommonAcousticFirProfile profile) {
		if (profile == null || profile.speaker() == null) {
			return 0.0;
		}
		double db = onAxisDirectivityDb(profile, REFERENCE_HZ);
		return Math.max(MIN_OVERALL_DB, Math.min(MAX_OVERALL_DB, db));
	}

	/**
	 * FIR normalization reference. Direct designs use 1 kHz. Reflection designs
	 * use 1 kHz when it is above the hard cut, otherwise a point above
	 * {@code f_s} so the cut is not normalized away.
	 */
	public static double referenceHz(CommonAcousticFirProfile profile) {
		if (profile == null || !profile.isReflection()) {
			return REFERENCE_HZ;
		}
		double schroeder = profile.schroederHz();
		if (schroeder < REFERENCE_HZ) {
			return REFERENCE_HZ;
		}
		return Math.min(MAX_HZ, Math.max(REFERENCE_HZ, schroeder * 1.1));
	}

	private static double onAxisDirectivityDb(CommonAcousticFirProfile profile,
											  double frequencyHz) {
		SpeakerDataProfile speaker = profile.speaker();
		if (speaker == null) {
			return 0.0;
		}
		return speaker.onAxisEqGainDb(frequencyHz)
			+ speaker.gainDb(profile.horizontalDegrees(),
			profile.verticalDegrees(), frequencyHz);
	}

	private static double airAbsorptionDb(CommonAcousticFirProfile profile,
										  double frequencyHz) {
		if (!profile.airAbsorption()) {
			return 0.0;
		}
		return AirAbsorption.directPathAttenuationDb(profile.pathLengthMeters(),
			frequencyHz, profile.temperatureC(), profile.humidityPercent(),
			profile.pressureKpa());
	}

	private static double occlusionLowPassDb(OcclusionResult occlusion,
											 double frequencyHz) {
		if (occlusion == null || occlusion.order() <= 0
			|| occlusion.cutoffHz() >= SAMPLE_RATE * 0.5) {
			return 0.0;
		}
		double ratio = frequencyHz / occlusion.cutoffHz();
		double power = Math.pow(ratio, 2.0 * occlusion.order());
		return -10.0 * Math.log10(1.0 + power);
	}

	private static double maxError(CommonAcousticFirProfile profile,
								   double[] target, double[] responseDb,
								   int fftSize) {
		double maxError = 0.0;
		for (int i = 0; i < target.length; i++) {
			double frequency = i * SAMPLE_RATE / fftSize;
			if (profile.isReflection()) {
				double transition = Math.max(
					profile.schroederHz() * CUT_TRANSITION_RATIO,
					profile.schroederHz() + CUT_TRANSITION_HZ);
				if (frequency <= transition) {
					continue;
				}
			}
			double value = target[i];
			if (value < MAX_ERROR_TARGET_DB) {
				continue;
			}
			maxError = Math.max(maxError, Math.abs(value - responseDb[i]));
		}
		return maxError;
	}

	private static double gain(double db) {
		if (!Double.isFinite(db)) {
			return 0.0;
		}
		double clamped = Math.max(MIN_OVERALL_DB, Math.min(MAX_OVERALL_DB, db));
		return Math.pow(10.0, clamped / 20.0);
	}
}
