package dev.cinema.spike;

import java.util.Arrays;

/**
 * Diagnostic for the meaning of {@code AL_SEC_OFFSET} on a queued streaming
 * source: is it relative to the current buffer or to the whole queue? The
 * answer decides how the media clock must add buffer PTS values.
 */
final class StreamingOffsetProbe {
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final double CHUNK_SECONDS = 0.25;
	private static final int CHUNKS = 20;

	private StreamingOffsetProbe() {
	}

	static void run() throws Exception {
		short[] silence = new short[(int) (SAMPLE_RATE * CHUNK_SECONDS) * CHANNELS];
		Arrays.fill(silence, (short) 0);

		try (OpenAlAudioPlayer player = new OpenAlAudioPlayer(SAMPLE_RATE, CHANNELS)) {
			for (int i = 0; i < CHUNKS; i++) {
				player.submit(new AudioChunk(i * CHUNK_SECONDS, silence));
			}
			player.start();
			long startNanos = player.startedAtNanos();
			long nextPrint = 0;
			while (System.nanoTime() - startNanos < 3_000_000_000L) {
				player.pump();
				long elapsed = System.nanoTime() - startNanos;
				if (elapsed >= nextPrint) {
					nextPrint = elapsed + 100_000_000L;
					System.out.printf("t=%5.3f  offset=%6.3f  front=%6.3f  media=%6.3f  audible=%6.3f  latency=%5.1fms  processed=%d queued=%d%n",
						elapsed / 1e9,
						player.sourceOffsetSeconds(),
						player.frontBufferStartSeconds(),
						player.mediaPtsSeconds(),
						player.audiblePtsSeconds(),
						player.latencySeconds() * 1000,
						player.buffersProcessed(),
						player.buffersQueued());
				}
				Thread.sleep(5);
			}
		}
	}
}
