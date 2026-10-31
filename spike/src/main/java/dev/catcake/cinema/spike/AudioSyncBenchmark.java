package dev.catcake.cinema.spike;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Streams decoded audio through the standalone OpenAL player and compares its
 * media clock against the wall clock.
 */
public final class AudioSyncBenchmark {
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final double WARMUP_SECONDS = 0.25;

	private AudioSyncBenchmark() {
	}

	public static void run(Path file) throws Exception {
		List<AudioChunk> chunks = AudioDecoder.decode(file, SAMPLE_RATE, CHANNELS);
		if (chunks.isEmpty()) {
			System.out.println("no audio stream found");
			return;
		}

		double mediaEnd = chunks.get(chunks.size() - 1).startPtsSeconds()
			+ chunks.get(chunks.size() - 1).samples().length / (double) CHANNELS / SAMPLE_RATE;
		System.out.printf("media audio  : %.3f s in %d chunks%n", mediaEnd, chunks.size());

		List<Double> ptsDrift = new ArrayList<>();
		List<Double> clockDrift = new ArrayList<>();
		double maxAbsPtsDrift = 0;
		double finalPtsDrift = 0;
		double wallAtEnd;

		try (OpenAlAudioPlayer player = new OpenAlAudioPlayer(SAMPLE_RATE, CHANNELS)) {
			chunks.forEach(player::submit);
			player.start();

			long startNanos = player.startedAtNanos();
			double lastLog = 0;

			while (!player.finished()) {
				player.pump();
				double wall = (System.nanoTime() - startNanos) / 1e9;
				double pts = player.mediaPtsSeconds();
				double clock = player.clockSeconds();

				if (wall >= WARMUP_SECONDS) {
					double ptsError = pts - wall;
					double clockError = wall - clock;
					ptsDrift.add(ptsError);
					clockDrift.add(clockError);
					maxAbsPtsDrift = Math.max(maxAbsPtsDrift, Math.abs(ptsError));
					finalPtsDrift = ptsError;
				}
				if (wall - lastLog >= 1.0) {
					lastLog = wall;
					System.out.printf("t=%5.2fs  pts=%6.3f  clock=%6.3f  drift=%+7.1f ms%n",
						wall, pts, clock, (pts - wall) * 1000);
				}
				Thread.sleep(5);
			}
			wallAtEnd = (System.nanoTime() - startNanos) / 1e9;
		}

		System.out.printf("wall time    : %.3f s%n", wallAtEnd);
		System.out.printf("A/V offset   : final %+.1f ms, max |drift| %.1f ms%n",
			finalPtsDrift * 1000, maxAbsPtsDrift * 1000);
		printDrift("pts-wall ms", ptsDrift);
		printDrift("wall-clock ms", clockDrift);
	}

	private static void printDrift(String label, List<Double> samples) {
		if (samples.isEmpty()) {
			System.out.println(label + " : no samples");
			return;
		}
		double[] values = samples.stream().mapToDouble(Double::doubleValue).map(Math::abs).sorted().toArray();
		double sum = Arrays.stream(values).sum();
		System.out.printf("%-12s : avg %.1f  p50 %.1f  p95 %.1f  max %.1f%n",
			label, sum / values.length * 1000, values[values.length / 2] * 1000,
			values[(int) Math.ceil(values.length * 0.95) - 1] * 1000, values[values.length - 1] * 1000);
	}
}
