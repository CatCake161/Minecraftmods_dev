package com.example.cinema.client.media.audio.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceBudgetTest {
	@Test
	void totalBudgetIsOneHundredTwentyEight() {
		assertEquals(128, CinemaAudioEngine.MAX_SOURCES);
	}

	@Test
	void fiveOneKeepsOneHundredSixteenReflectionSources() {
		assertEquals(116, CinemaAudioEngine.reflectionSourcesFor(6));
		assertEquals(116, CinemaAudioEngine.MAX_REFLECTION_SOURCES);
	}

	@Test
	void multipleSpeakersPerChannelShrinkTheReflectionPool() {
		assertEquals(110, CinemaAudioEngine.reflectionSourcesFor(12));
		assertEquals(90, CinemaAudioEngine.reflectionSourcesFor(32));
		assertEquals(58, CinemaAudioEngine.reflectionSourcesFor(64));
	}

	@Test
	void directSourcesAreClampedToTheDesignCap() {
		assertEquals(116, CinemaAudioEngine.reflectionSourcesFor(0));
		assertEquals(58, CinemaAudioEngine.reflectionSourcesFor(100));
	}
}
