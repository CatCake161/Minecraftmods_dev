package com.example.cinema.client.audio.speakerdata;

import com.example.cinema.block.entity.SpeakerType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	private static SpeakerDataProfile profile(SpeakerDataProfile.Pattern pattern) {
		return new SpeakerDataProfile("test", "Test", SpeakerType.CENTER,
			pattern, "", "", 20.0, 20_000.0, new double[]{1_000.0},
			new double[]{80.0}, new SpeakerDataProfile.Row[0],
			new SpeakerDataProfile.Row[0]);
	}
}
