package com.example.cinema.client.audio.speakerdata;

import com.example.cinema.block.entity.SpeakerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpeakerDataRegistryTest {
	@Test
	void subwooferManualRangeIsPreserved() {
		SpeakerDataProfile profile = profile(SpeakerType.SUBWOOFER,
			SpeakerDataProfile.Pattern.MEASURED, 25.0, 120.0);

		SpeakerDataRegistry.SpeakerSettings normalized =
			SpeakerDataRegistry.normalizeForProfile(SpeakerType.SUBWOOFER, profile,
				new SpeakerDataRegistry.SpeakerSettings(profile.id(), 30.0, 100.0, 2.5));

		assertEquals(30.0, normalized.lowHz(), 1.0e-9);
		assertEquals(100.0, normalized.highHz(), 1.0e-9);
		assertEquals(2.5, normalized.gainDb(), 1.0e-9);
	}

	@Test
	void subwooferRangeIsClampedToValidBounds() {
		SpeakerDataProfile profile = profile(SpeakerType.SUBWOOFER,
			SpeakerDataProfile.Pattern.OMNI, 20.0, 200.0);

		SpeakerDataRegistry.SpeakerSettings normalized =
			SpeakerDataRegistry.normalizeForProfile(SpeakerType.SUBWOOFER, profile,
				new SpeakerDataRegistry.SpeakerSettings(profile.id(), 1.0, 5_000.0, 0.0));

		assertEquals(SpeakerDataRegistry.SUBWOOFER_MIN_HZ, normalized.lowHz(), 1.0e-9);
		assertEquals(SpeakerDataRegistry.SUBWOOFER_MAX_HZ, normalized.highHz(), 1.0e-9);
	}

	@Test
	void measuredNonSubwooferRangeIsFixedToProfile() {
		SpeakerDataProfile profile = profile(SpeakerType.FLOORSTANDING,
			SpeakerDataProfile.Pattern.MEASURED, 40.0, 20_000.0);

		SpeakerDataRegistry.SpeakerSettings normalized =
			SpeakerDataRegistry.normalizeForProfile(SpeakerType.FLOORSTANDING, profile,
				new SpeakerDataRegistry.SpeakerSettings(profile.id(), 60.0, 18_000.0, 0.0));

		assertEquals(40.0, normalized.lowHz(), 1.0e-9);
		assertEquals(20_000.0, normalized.highHz(), 1.0e-9);
	}

	@Test
	void analyticNonSubwooferRangeIsEditable() {
		SpeakerDataProfile profile = profile(SpeakerType.FLOORSTANDING,
			SpeakerDataProfile.Pattern.UNMEASURED, 60.0, 20_000.0);

		SpeakerDataRegistry.SpeakerSettings normalized =
			SpeakerDataRegistry.normalizeForProfile(SpeakerType.FLOORSTANDING, profile,
				new SpeakerDataRegistry.SpeakerSettings(profile.id(), 80.0, 18_000.0, 0.0));

		assertEquals(80.0, normalized.lowHz(), 1.0e-9);
		assertEquals(18_000.0, normalized.highHz(), 1.0e-9);
	}

	@Test
	void legacySubwooferIdsMapToSealedProfiles() {
		assertEquals(SpeakerDataRegistry.SUB_SEALED_OMNI_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.SUBWOOFER,
				SpeakerDataRegistry.LEGACY_SUB_OMNI_ID));
		assertEquals(SpeakerDataRegistry.SUB_SEALED_CARDIOID_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.SUBWOOFER,
				SpeakerDataRegistry.LEGACY_SUB_CARDIOID_ID));
		assertEquals("custom-sub",
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.SUBWOOFER, "custom-sub"));
		assertEquals(SpeakerDataRegistry.LEGACY_SUB_OMNI_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.FLOORSTANDING,
				SpeakerDataRegistry.LEGACY_SUB_OMNI_ID));
	}

	@Test
	void legacyBassReflexIdsMapToPortedProfiles() {
		assertEquals(SpeakerDataRegistry.SUB_PORTED_OMNI_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.SUBWOOFER,
				SpeakerDataRegistry.LEGACY_SUB_REFLEX_OMNI_ID));
		assertEquals(SpeakerDataRegistry.SUB_PORTED_CARDIOID_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.SUBWOOFER,
				SpeakerDataRegistry.LEGACY_SUB_REFLEX_CARDIOID_ID));
		assertEquals(SpeakerDataRegistry.LEGACY_SUB_REFLEX_OMNI_ID,
			SpeakerDataRegistry.canonicalProfileId(SpeakerType.FLOORSTANDING,
				SpeakerDataRegistry.LEGACY_SUB_REFLEX_OMNI_ID));
	}

	private static SpeakerDataProfile profile(SpeakerType type,
											  SpeakerDataProfile.Pattern pattern,
											  double lowHz, double highHz) {
		return new SpeakerDataProfile("test-" + type.id(), "Test " + type.id(), type,
			SpeakerDataProfile.Enclosure.SEALED, pattern, "", "",
			lowHz, highHz, null, null, null, null);
	}
}
