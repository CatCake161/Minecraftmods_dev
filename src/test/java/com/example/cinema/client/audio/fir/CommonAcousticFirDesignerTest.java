package com.example.cinema.client.audio.fir;

import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.OcclusionResult;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonAcousticFirDesignerTest {
	private static final double SAMPLE_RATE = 48_000.0;
	private static final AcousticMaterial MATERIAL =
		new AcousticMaterial("test", 0.20, 0.50, 0.80, 0.10);

	@Test
	void equalMaterialBandsProduceAFlatCurve() {
		AcousticMaterial flat = new AcousticMaterial("flat", 0.4, 0.4, 0.4, 0.0);
		assertEquals(0.0, MaterialReflectionCurve.of(flat).db(1_000.0), 1.0e-9);
	}

	@Test
	void directProfileUses4096TapsAndAirOcclusionTarget() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.direct(null,
			0.0, 0.0, 10.0, true, 20.0, 50.0, 101.325,
			new OcclusionResult(0.0, 1_000.0, 2));

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);

		assertEquals(4_096, fir.length());
		assertEquals(1.0, fir.overallGain(), 1.0e-9);
		assertResponseMatchesTarget(profile, fir, 1_000.0, 0.5);
		assertResponseMatchesTarget(profile, fir, 100.0, 1.0);
		assertResponseMatchesTarget(profile, fir, 8_000.0, 1.0);
		assertTrue(fir.maxErrorDb() < 2.0,
			"direct maxError " + fir.maxErrorDb() + " dB");
	}

	@Test
	void reflectionMaterialBandsMatchRelativeTargets() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(null,
			0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, MATERIAL,
			OcclusionResult.NONE, 100.0);

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);

		assertEquals(2_048, fir.length());
		assertResponseMatchesTarget(profile, fir,
			MaterialReflectionCurve.LOW_BAND_HZ, 0.7);
		assertResponseMatchesTarget(profile, fir,
			MaterialReflectionCurve.MID_BAND_HZ, 0.7);
		assertResponseMatchesTarget(profile, fir,
			MaterialReflectionCurve.HIGH_BAND_HZ, 0.7);
		assertTrue(fir.maxErrorDb() < 2.0,
			"material maxError " + fir.maxErrorDb() + " dB");
	}

	@Test
	void reflectionOcclusionFollowsButterworthLowPass() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(null,
			0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, null,
			new OcclusionResult(0.0, 1_000.0, 2), 100.0);

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);

		assertResponseMatchesTarget(profile, fir, 500.0, 0.7);
		assertResponseMatchesTarget(profile, fir, 1_000.0, 0.7);
		assertResponseMatchesTarget(profile, fir, 2_000.0, 1.0);
	}

	@Test
	void reflectionHardCutIsSilentAtAndBelowSchroeder() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(null,
			0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, MATERIAL,
			OcclusionResult.NONE, 500.0);

		assertEquals(-96.0, CommonAcousticFirDesigner.targetDb(profile, 100.0),
			1.0e-9);
		assertEquals(-96.0, CommonAcousticFirDesigner.targetDb(profile, 500.0),
			1.0e-9);
		assertTrue(CommonAcousticFirDesigner.targetDb(profile, 501.0) > -96.0);

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);
		double low = responseDb(fir, 100.0);
		double pass = responseDb(fir, 2_000.0);
		assertTrue(low < pass - 20.0,
			"hard cut should suppress the stop band, low=" + low
				+ " dB pass=" + pass + " dB");
	}

	@Test
	void directAndReflectionUseTheSameOnAxisDirectivityGain() {
		SpeakerDataProfile speaker = syntheticProfile();
		CommonAcousticFirProfile direct = CommonAcousticFirProfile.direct(speaker,
			60.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE);
		CommonAcousticFirProfile reflection = CommonAcousticFirProfile.reflection(
			speaker, 60.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, null,
			OcclusionResult.NONE, 100.0);

		assertEquals(CommonAcousticFirDesigner.overallDb(direct),
			CommonAcousticFirDesigner.overallDb(reflection), 1.0e-9);
		assertEquals(CommonAcousticFirDesigner.targetDb(direct, 2_000.0),
			CommonAcousticFirDesigner.targetDb(reflection, 2_000.0), 1.0e-9);
		MinimumPhaseFir directFir = CommonAcousticFirDesigner.design(direct);
		MinimumPhaseFir reflectionFir = CommonAcousticFirDesigner.design(reflection);
		assertEquals(directFir.overallGain(), reflectionFir.overallGain(), 1.0e-9);
	}

	@Test
	void singleDirectFirMatchesTheOldTwoStageResponse() {
		SpeakerDataProfile speaker = syntheticProfile();
		double horizontal = 60.0;
		double vertical = 0.0;
		double distance = 10.0;
		OcclusionResult occlusion = new OcclusionResult(5.0, 1_000.0, 2);
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.direct(speaker,
			horizontal, vertical, distance, true, 20.0, 50.0, 101.325,
			occlusion);
		MinimumPhaseFir combined = CommonAcousticFirDesigner.design(profile);

		MinimumPhaseFir oldDirectivity = oldDirectivityFilter(speaker, horizontal,
			vertical);
		MinimumPhaseFir oldEnvironment = oldEnvironmentFilter(profile);
		double overallGain = Math.pow(10.0,
			CommonAcousticFirDesigner.overallDb(profile) / 20.0);

		for (double frequency : new double[]{100.0, 250.0, 1_000.0, 4_000.0,
			8_000.0, 16_000.0}) {
			double oldResponse = overallGain
				* MinimumPhaseFirDesigner.responseAt(oldDirectivity.taps(),
				frequency, SAMPLE_RATE)
				* MinimumPhaseFirDesigner.responseAt(oldEnvironment.taps(),
				frequency, SAMPLE_RATE);
			double newResponse = overallGain
				* MinimumPhaseFirDesigner.responseAt(combined.taps(), frequency,
				SAMPLE_RATE);
			double oldDb = 20.0 * Math.log10(Math.max(1.0e-12, oldResponse));
			double newDb = 20.0 * Math.log10(Math.max(1.0e-12, newResponse));
			assertEquals(oldDb, newDb, 1.0,
				"combined direct response at " + frequency + " Hz");
		}
	}

	private static MinimumPhaseFir oldDirectivityFilter(SpeakerDataProfile speaker,
														double horizontal,
														double vertical) {
		int fftSize = 16_384;
		int half = fftSize / 2;
		double offsetDb = speaker.onAxisEqGainDb(1_000.0)
			+ speaker.gainDb(horizontal, vertical, 1_000.0);
		double[] target = new double[half + 1];
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / fftSize;
			double clamped = Math.max(20.0, Math.min(20_000.0, frequency));
			target[i] = speaker.onAxisEqGainDb(clamped)
				+ speaker.gainDb(horizontal, vertical, clamped) - offsetDb;
		}
		return MinimumPhaseFirDesigner.designMagnitude(target, fftSize, 4_096,
			SAMPLE_RATE, 1_000.0, "old-directivity");
	}

	private static MinimumPhaseFir oldEnvironmentFilter(
		CommonAcousticFirProfile profile) {
		int fftSize = 8_192;
		int half = fftSize / 2;
		double[] target = new double[half + 1];
		for (int i = 0; i <= half; i++) {
			double frequency = i * SAMPLE_RATE / fftSize;
			target[i] = environmentDb(profile, frequency) - environmentDb(profile, 0.0);
		}
		return MinimumPhaseFirDesigner.designMagnitude(target, fftSize, 2_048,
			SAMPLE_RATE, 50.0, "old-environment");
	}

	private static double environmentDb(CommonAcousticFirProfile profile,
										double frequencyHz) {
		double air = AirAbsorption.directPathAttenuationDb(
			profile.pathLengthMeters(), frequencyHz, profile.temperatureC(),
			profile.humidityPercent(), profile.pressureKpa());
		double occlusion = -10.0 * Math.log10(1.0
			+ Math.pow(frequencyHz / profile.occlusion().cutoffHz(),
			2.0 * profile.occlusion().order()));
		return -air + occlusion;
	}

	@Test
	void reflectionHighSchroederKeepsTheHardCutAtOneKilohertz() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(null,
			0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, MATERIAL,
			OcclusionResult.NONE, 1_500.0);

		assertEquals(-96.0, CommonAcousticFirDesigner.targetDb(profile, 1_000.0),
			1.0e-9);
		assertTrue(CommonAcousticFirDesigner.referenceHz(profile) > 1_500.0);
		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);
		double atOneK = responseDb(fir, 1_000.0);
		double atThreeK = responseDb(fir, 3_000.0);
		assertTrue(atOneK < atThreeK - 20.0,
			"1 kHz must stay cut when f_s >= 1 kHz, 1k=" + atOneK
				+ " dB 3k=" + atThreeK + " dB");
	}

	private static void assertResponseMatchesTarget(CommonAcousticFirProfile profile,
													MinimumPhaseFir fir,
													double frequencyHz,
													double toleranceDb) {
		double expected = CommonAcousticFirDesigner.targetDb(profile, frequencyHz);
		double actual = responseDb(fir, frequencyHz);
		assertEquals(expected, actual, toleranceDb,
			"response at " + frequencyHz + " Hz");
	}

	private static double responseDb(MinimumPhaseFir fir, double frequencyHz) {
		double response = MinimumPhaseFirDesigner.responseAt(fir.taps(),
			frequencyHz, SAMPLE_RATE);
		return 20.0 * Math.log10(Math.max(1.0e-12, response));
	}

	private static SpeakerDataProfile syntheticProfile() {
		String json = """
			{
			  "id": "synthetic-test",
			  "type": "floorstanding",
			  "pattern": "measured",
			  "frequencyRange": [40, 20000],
			  "frequencies": [1000, 2000],
			  "onAxis": [0, 0],
			  "horizontal": [
			    {"angle": 0, "spl": [0, 0]},
			    {"angle": 60, "spl": [-2, -4]}
			  ]
			}
			""";
		return SpeakerDataProfile.fromJson(
			JsonParser.parseString(json).getAsJsonObject());
	}
}
