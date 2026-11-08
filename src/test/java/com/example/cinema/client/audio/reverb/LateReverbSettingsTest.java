package com.example.cinema.client.audio.reverb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateReverbSettingsTest {
	@Test
	void missingRoomUsesAStableFallback() {
		LateReverbSettings settings = LateReverbSettings.forRoom(null, true, -12.0);

		assertTrue(settings.enabled());
		assertTrue(settings.rt60MidSeconds() >= LateReverbSettings.MIN_RT60_SECONDS);
		assertTrue(settings.rt60HighSeconds() >= LateReverbSettings.MIN_RT60_SECONDS);
		assertTrue(settings.preDelaySeconds()
			>= LateReverbSettings.MIN_PRE_DELAY_SECONDS);
		assertEquals(LateReverbSettings.DEFAULT_HIGH_PASS_HZ,
			settings.highPassHz(), 1.0e-9);
		assertEquals(-12.0, settings.wetGainDb(), 1.0e-9);
	}

	@Test
	void physicalTargetSourceGainAndTrimAreKeptSeparate() {
		LateReverbSettings settings = LateReverbSettings.forRoom(null, true,
			-3.0, -15.0, 2.0, 100.0, 1.4);

		assertEquals(-3.0, settings.targetWetGainDb(), 1.0e-9);
		assertEquals(-15.0, settings.sourceGainDb(), 1.0e-9);
		assertEquals(2.0, settings.trimDb(), 1.0e-9);
		assertEquals(-13.0, settings.wetGainDb(), 1.0e-9);
		assertEquals(100.0, settings.roomConstant(), 1.0e-9);
		assertEquals(1.4, settings.criticalDistanceMeters(), 1.0e-9);
	}

	@Test
	void lowMidAndHighRt60AreIndependent() {
		LateReverbSettings settings = new LateReverbSettings(true,
			0.2, 1.0, 0.5, 20.0, 0.03, -12.0);

		assertEquals(0.2, settings.rt60LowSeconds(), 1.0e-9);
		assertEquals(1.0, settings.rt60MidSeconds(), 1.0e-9);
		assertEquals(0.5, settings.rt60HighSeconds(), 1.0e-9);
	}

	@Test
	void valuesAreClampedToTheSupportedRange() {
		LateReverbSettings settings = new LateReverbSettings(true,
			0.01, 99.0, 10_000.0, 1.0, -100.0);

		assertEquals(LateReverbSettings.MIN_RT60_SECONDS,
			settings.rt60LowSeconds(), 1.0e-9);
		assertEquals(LateReverbSettings.MIN_RT60_SECONDS,
			settings.rt60MidSeconds(), 1.0e-9);
		assertEquals(LateReverbSettings.MAX_RT60_SECONDS,
			settings.rt60HighSeconds(), 1.0e-9);
		assertEquals(2_000.0, settings.highPassHz(), 1.0e-9);
		assertEquals(LateReverbSettings.MAX_PRE_DELAY_SECONDS,
			settings.preDelaySeconds(), 1.0e-9);
		assertEquals(LateReverbSettings.MIN_WET_GAIN_DB,
			settings.wetGainDb(), 1.0e-9);
	}
}
