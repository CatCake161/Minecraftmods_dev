package com.example.cinema.client.audio.fir;

import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.OcclusionResult;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.MinimumPhaseFirDesigner;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SubwooferBand;
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
			OcclusionResult.NONE);

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);

		assertEquals(4_096, fir.length());
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
			new OcclusionResult(0.0, 1_000.0, 2));

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);

		assertResponseMatchesTarget(profile, fir, 500.0, 0.7);
		assertResponseMatchesTarget(profile, fir, 1_000.0, 0.7);
		assertResponseMatchesTarget(profile, fir, 2_000.0, 1.0);
	}

	@Test
	void reflectionIsFullBandWithSpeakerAndMaterial() {
		SpeakerDataProfile speaker = syntheticProfile();
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(
			speaker, 60.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, MATERIAL,
			OcclusionResult.NONE);
		double lowTarget = CommonAcousticFirDesigner.targetDb(profile, 100.0);
		assertTrue(lowTarget > -30.0,
			"full-band reflection target should not be cut below Schroeder: "
				+ lowTarget);
		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);
		assertResponseMatchesTarget(profile, fir, 100.0, 2.0);
		assertTrue(fir.maxErrorDb() < 2.0,
			"full-band reflection maxError " + fir.maxErrorDb() + " dB");
	}

	@Test
	void directAndReflectionUseTheSameOnAxisDirectivityGain() {
		SpeakerDataProfile speaker = syntheticProfile();
		CommonAcousticFirProfile direct = CommonAcousticFirProfile.direct(speaker,
			60.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE);
		CommonAcousticFirProfile reflection = CommonAcousticFirProfile.reflection(
			speaker, 60.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, null,
			OcclusionResult.NONE);

		assertEquals(CommonAcousticFirDesigner.overallDb(direct),
			CommonAcousticFirDesigner.overallDb(reflection), 1.0e-9);
		assertEquals(CommonAcousticFirDesigner.targetDb(direct, 2_000.0),
			CommonAcousticFirDesigner.targetDb(reflection, 2_000.0), 0.01);
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
	void reflectionUsesOneKilohertzAsNormalizationReference() {
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.reflection(null,
			0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325, null,
			OcclusionResult.NONE);

		assertEquals(CommonAcousticFirDesigner.REFERENCE_HZ,
			CommonAcousticFirDesigner.referenceHz(profile), 1.0e-9);
	}

	@Test
	void subwooferUsesInBandReferenceAndBandLimitedTarget() {
		SpeakerDataProfile sealed = subwooferProfile("sealed", 30.0);
		SubwooferBand band = new SubwooferBand(30.0, 200.0);
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.direct(
			sealed, 0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE, band);

		assertEquals(Math.sqrt(30.0 * 200.0),
			CommonAcousticFirDesigner.referenceHz(profile), 1.0e-9);
		double passband = CommonAcousticFirDesigner.targetDb(profile, 60.0);
		double deepBass = CommonAcousticFirDesigner.targetDb(profile, 15.0);
		double aboveBand = CommonAcousticFirDesigner.targetDb(profile, 800.0);
		assertTrue(deepBass < passband - 6.0,
			"sealed rolloff should cut 15 Hz: " + deepBass + " vs " + passband);
		assertTrue(aboveBand < passband - 20.0,
			"manual high edge should cut 800 Hz: " + aboveBand + " vs " + passband);

		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);
		assertEquals(4_096, fir.length());
		assertResponseMatchesTarget(profile, fir, 60.0, 2.0);
	}

	@Test
	void subwooferWithoutBandFallsBackToTwiceTheLowEdge() {
		SpeakerDataProfile sealed = subwooferProfile("sealed", 30.0);
		CommonAcousticFirProfile profile = CommonAcousticFirProfile.direct(
			sealed, 0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE);

		assertEquals(60.0, CommonAcousticFirDesigner.referenceHz(profile),
			1.0e-9);
	}

	@Test
	void portedSubwooferKeepsMoreDeepBassThanSealed() {
		SpeakerDataProfile sealed = subwooferProfile("sealed", 30.0);
		SpeakerDataProfile ported = subwooferProfile("ported", 20.0);
		SubwooferBand band = new SubwooferBand(20.0, 200.0);
		CommonAcousticFirProfile sealedProfile = CommonAcousticFirProfile.direct(
			sealed, 0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE, band);
		CommonAcousticFirProfile portedProfile = CommonAcousticFirProfile.direct(
			ported, 0.0, 0.0, 5.0, false, 20.0, 50.0, 101.325,
			OcclusionResult.NONE, band);

		assertTrue(CommonAcousticFirDesigner.targetDb(portedProfile, 20.0)
				> CommonAcousticFirDesigner.targetDb(sealedProfile, 20.0) + 3.0,
			"ported alignment should extend lower than sealed");
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

	private static SpeakerDataProfile subwooferProfile(String enclosure,
													   double lowHz) {
		String json = """
			{
			  "id": "test-sub-%s",
			  "type": "subwoofer",
			  "pattern": "omni",
			  "enclosure": "%s",
			  "frequencyRange": [%s, 200]
			}
			""".formatted(enclosure, enclosure, lowHz);
		return SpeakerDataProfile.fromJson(
			JsonParser.parseString(json).getAsJsonObject());
	}
}
