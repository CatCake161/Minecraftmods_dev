package com.example.cinema.client.media.audio.speaker;

import com.example.cinema.block.entity.SpeakerType;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeakerDataProfileTest {
	private static final double FOUR_PI = 4.0 * Math.PI;

	@Test
	void omniProfileHasFourPiPowerIntegral() {
		SpeakerDataProfile profile = profile(SpeakerDataProfile.Pattern.OMNI);

		assertEquals(FOUR_PI, profile.directivityPowerIntegral(1_000.0),
			1.0e-9);
	}

	@Test
	void cardioidProfileIsDirectionalButNotSilent() {
		SpeakerDataProfile profile = profile(SpeakerDataProfile.Pattern.CARDIOID);

		double integral = profile.directivityPowerIntegral(1_000.0);

		assertTrue(integral > 0.0);
		assertTrue(integral < FOUR_PI,
			"cardioid should radiate less total power than omni: " + integral);
	}

	@Test
	void cardioidSubwooferHasRearNull() {
		SpeakerDataProfile cardioid = subwoofer(SpeakerDataProfile.Pattern.CARDIOID);
		SpeakerDataProfile omni = subwoofer(SpeakerDataProfile.Pattern.OMNI);

		assertEquals(0.0, cardioid.gainDb(0.0, 0.0, 80.0), 1.0e-9);
		assertEquals(-90.0, cardioid.gainDb(180.0, 0.0, 80.0), 1.0e-9);
		assertEquals(0.0, omni.gainDb(180.0, 0.0, 80.0), 1.0e-9);
	}

	@Test
	void enclosureParsesCanonicalAndAliasValues() {
		assertEquals(SpeakerDataProfile.Enclosure.SEALED,
			parseEnclosure("\"enclosure\": \"sealed\",").enclosure());
		assertEquals(SpeakerDataProfile.Enclosure.SEALED,
			parseEnclosure("\"enclosure\": \"closed\",").enclosure());
		assertEquals(SpeakerDataProfile.Enclosure.PORTED,
			parseEnclosure("\"enclosure\": \"ported\",").enclosure());
		assertEquals(SpeakerDataProfile.Enclosure.PORTED,
			parseEnclosure("\"enclosure\": \"bass-reflex\",").enclosure());
		assertEquals(SpeakerDataProfile.Enclosure.PORTED,
			parseEnclosure("\"enclosure\": \"bass_reflex\",").enclosure());
		assertEquals(SpeakerDataProfile.Enclosure.PORTED,
			parseEnclosure("\"enclosure\": \"reflex\",").enclosure());
		assertEquals("ported",
			parseEnclosure("\"enclosure\": \"ported\",").enclosureLabel());
	}

	@Test
	void subwooferSummaryCombinesEnclosureAndDirectivity() {
		SpeakerDataProfile profile = subwoofer(SpeakerDataProfile.Pattern.CARDIOID);

		assertEquals("sealed · cardioid", profile.summaryLabel());
	}

	@Test
	void missingEnclosureIsUnknown() {
		assertEquals(SpeakerDataProfile.Enclosure.UNKNOWN,
			parseEnclosure("").enclosure());
	}

	@Test
	void invalidEnclosureIsRejected() {
		assertThrows(IllegalArgumentException.class,
			() -> parseEnclosure("\"enclosure\": \"bandpass\","));
	}

	@Test
	void measuredRangeUsesMinusThreeDb() {
		SpeakerDataProfile profile = measuredProfile("""
			{
			  "id": "test-range",
			  "type": "floorstanding",
			  "pattern": "measured",
			  "frequencyRange": [10, 20000],
			  "frequencies": [10, 20, 40, 80, 160, 320],
			  "onAxis": [70, 80, 80, 80, 80, 70],
			  "horizontal": [
			    {"angle": 0, "spl": [70, 80, 80, 80, 80, 70]}
			  ]
			}
			""");

		assertEquals(15.0, profile.lowHz(), 1.0);
		assertEquals(219.0, profile.highHz(), 2.0);
	}

	@Test
	void measuredRangeIgnoresInteriorDipAndRecovery() {
		SpeakerDataProfile profile = measuredProfile("""
			{
			  "id": "test-interior-dip",
			  "type": "bookshelf",
			  "pattern": "measured",
			  "frequencies": [20, 40, 80, 160, 320, 640, 1280, 2560, 5120],
			  "onAxis": [60, 80, 80, 80, 60, 80, 80, 80, 80],
			  "horizontal": [
			    {"angle": 0, "spl": [60, 80, 80, 80, 60, 80, 80, 80, 80]}
			  ]
			}
			""");

		assertEquals(35.0, profile.lowHz(), 1.0);
		assertEquals(5120.0, profile.highHz(), 0.0);
	}

	@Test
	void measuredRangeIgnoresStaleJsonRange() {
		SpeakerDataProfile profile = measuredProfile("""
			{
			  "id": "test-stale-range",
			  "type": "floorstanding",
			  "pattern": "measured",
			  "frequencyRange": [10, 20000],
			  "frequencies": [10, 20, 40, 80, 160, 320],
			  "onAxis": [70, 80, 80, 80, 80, 70],
			  "horizontal": [
			    {"angle": 0, "spl": [70, 80, 80, 80, 80, 70]}
			  ]
			}
			""");

		assertTrue(profile.lowHz() > 10.0, "low range should be recomputed");
		assertTrue(profile.highHz() < 20_000.0, "high range should be recomputed");
	}

	@Test
	void subwooferRangeUsesInBandAnchor() {
		SpeakerDataProfile profile = measuredProfile("""
			{
			  "id": "test-sub-range",
			  "type": "subwoofer",
			  "pattern": "measured",
			  "frequencyRange": [20, 200],
			  "frequencies": [20, 30, 40, 60, 80, 100, 150, 200],
			  "onAxis": [70, 80, 80, 80, 80, 80, 80, 70],
			  "horizontal": [
			    {"angle": 0, "spl": [70, 80, 80, 80, 80, 80, 80, 70]}
			  ]
			}
			""");

		assertTrue(profile.lowHz() > 20.0,
			"subwoofer low -3 dB point should be inside the measured band");
		assertTrue(profile.lowHz() < 30.0);
		assertTrue(profile.highHz() > 150.0);
		assertTrue(profile.highHz() < 200.0,
			"subwoofer high -3 dB point should be inside the measured band");
	}

	@Test
	void measuredResponseKeepsRollOffOutsideMinusThreeDbBand() {
		SpeakerDataProfile profile = measuredProfile("""
			{
			  "id": "test-rolloff",
			  "type": "floorstanding",
			  "pattern": "measured",
			  "frequencies": [20, 40, 80, 160, 320, 1000, 2000],
			  "onAxis": [60, 70, 80, 80, 80, 80, 80],
			  "horizontal": [
			    {"angle": 0, "spl": [60, 70, 80, 80, 80, 80, 80]}
			  ]
			}
			""");

		assertTrue(profile.lowHz() > 40.0,
			"the -3 dB point should be above the 40 Hz sample");
		assertTrue(profile.onAxisEqGainDb(20.0) < -10.0,
			"response below the -3 dB band must keep its measured roll-off");
	}

	private static SpeakerDataProfile profile(SpeakerDataProfile.Pattern pattern) {
		return new SpeakerDataProfile("test", "Test", SpeakerType.CENTER,
			SpeakerDataProfile.Enclosure.UNKNOWN, pattern, "", "", 20.0, 20_000.0,
			new double[]{1_000.0}, new double[]{80.0},
			new SpeakerDataProfile.Row[0], new SpeakerDataProfile.Row[0]);
	}

	private static SpeakerDataProfile subwoofer(SpeakerDataProfile.Pattern pattern) {
		return new SpeakerDataProfile("test-sub", "Test Sub", SpeakerType.SUBWOOFER,
			SpeakerDataProfile.Enclosure.SEALED, pattern, "", "", 20.0, 200.0,
			null, null, null, null);
	}

	private static SpeakerDataProfile parseEnclosure(String enclosureLine) {
		String json = """
			{
			  "id": "test-enclosure",
			  "type": "subwoofer",
			  "pattern": "omni",
			  %s
			  "frequencyRange": [20, 200]
			}
			""".formatted(enclosureLine);
		return SpeakerDataProfile.fromJson(
			JsonParser.parseString(json).getAsJsonObject());
	}

	private static SpeakerDataProfile measuredProfile(String json) {
		return SpeakerDataProfile.fromJson(
			JsonParser.parseString(json).getAsJsonObject());
	}
}
