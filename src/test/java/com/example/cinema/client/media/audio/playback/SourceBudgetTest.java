package com.example.cinema.client.media.audio.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceBudgetTest {
	@Test
	void totalBudgetIsOneHundredTwentyEight() {
		assertEquals(128, CinemaAudioEngine.MAX_SOURCES);
	}

	@Test
	void fiveOneKeepsNinetyEightReflectionSources() {
		assertEquals(98, CinemaAudioEngine.reflectionSourcesFor(6));
		assertEquals(116, CinemaAudioEngine.MAX_REFLECTION_SOURCES);
	}

	@Test
	void multipleSpeakersPerChannelShrinkTheReflectionPool() {
		assertEquals(92, CinemaAudioEngine.reflectionSourcesFor(12));
		assertEquals(72, CinemaAudioEngine.reflectionSourcesFor(32));
		assertEquals(40, CinemaAudioEngine.reflectionSourcesFor(64));
	}

	@Test
	void directSourcesAreClampedToTheDesignCap() {
		assertEquals(104, CinemaAudioEngine.reflectionSourcesFor(0));
		assertEquals(40, CinemaAudioEngine.reflectionSourcesFor(100));
	}
}
