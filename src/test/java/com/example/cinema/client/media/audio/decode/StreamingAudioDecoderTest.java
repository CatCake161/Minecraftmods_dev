package com.example.cinema.client.media.audio.decode;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingAudioDecoderTest {
	@Test
	void drainsAllLinesAndKeepsOnlyTheConfiguredBudget() throws IOException {
		List<String> lines = new ArrayList<>();
		int suppressed = StreamingAudioDecoder.drainErrorStream(
			stream("first\n\nsecond\nthird\nfourth\n"), 2, lines::add);
		assertEquals(List.of("first", "second"), lines);
		assertEquals(2, suppressed);
	}

	@Test
	void returnsZeroWhenEverythingFits() throws IOException {
		List<String> lines = new ArrayList<>();
		int suppressed = StreamingAudioDecoder.drainErrorStream(
			stream("only\n"), 20, lines::add);
		assertEquals(List.of("only"), lines);
		assertEquals(0, suppressed);
	}

	@Test
	void handlesAnEmptyStream() throws IOException {
		List<String> lines = new ArrayList<>();
		int suppressed = StreamingAudioDecoder.drainErrorStream(
			stream(""), 20, lines::add);
		assertTrue(lines.isEmpty());
		assertEquals(0, suppressed);
	}

	private static ByteArrayInputStream stream(String text) {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}
}
