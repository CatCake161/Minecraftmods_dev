package dev.cinema.spike;

import org.lwjgl.opengl.GL11;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end A/V sync spike: audio clock drives video presentation, frames more
 * than 100 ms late are dropped. A visible 1280x720 window shows the result.
 */
public final class SyncPlayer {
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 2;
	private static final double MAX_LATE_SECONDS = 0.100;
	private static final double PRESENT_LEAD_SECONDS = 0.005;

	private SyncPlayer() {
	}

	public static void run(Path file) throws Exception {
		System.out.println("decoding audio...");
		List<AudioChunk> audioChunks = AudioDecoder.decode(file, SAMPLE_RATE, CHANNELS);

		BlockingQueue<VideoFrame> frames = new ArrayBlockingQueue<>(8);
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicBoolean videoDone = new AtomicBoolean();
		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicInteger decoded = new AtomicInteger();

		Thread videoThread = new Thread(() -> {
			try (VideoDecoder decoder = new VideoDecoder(file)) {
				System.out.printf("video stream : %dx%d%n", decoder.width(), decoder.height());
				VideoFrame frame;
				while (!cancelled.get() && (frame = decoder.next()) != null) {
					if (!frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
						// Queue stayed full; retry until cancelled or space is available.
						while (!cancelled.get() && !frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
							// keep waiting
						}
					}
					decoded.incrementAndGet();
				}
			} catch (Throwable t) {
				error.compareAndSet(null, t);
			} finally {
				videoDone.set(true);
			}
		}, "video-decode");
		videoThread.setDaemon(true);
		videoThread.start();

		VideoFrame first = awaitFirstFrame(frames, error, 15_000);
		if (first == null) {
			cancelled.set(true);
			throw new IllegalStateException("no video frame decoded", error.get());
		}

		try (GlScreen screen = new GlScreen(1280, 720, true);
			 OpenAlAudioPlayer player = new OpenAlAudioPlayer(SAMPLE_RATE, CHANNELS)) {
			audioChunks.forEach(player::submit);
			player.start();
			long startNanos = player.startedAtNanos();

			List<Double> lateSamples = new ArrayList<>();
			List<Double> uploadSamples = new ArrayList<>();
			List<Double> latencySamples = new ArrayList<>();
			VideoFrame pending = first;
			int presented = 0;
			int dropped = 0;
			double maxLate = 0;

			System.out.println("playing, close the window to stop...");
			while (!player.finished() && !screen.shouldClose()) {
				player.pump();
				screen.pollEvents();

				if (error.get() != null) {
					throw new IllegalStateException("video decoder failed", error.get());
				}

				double target = player.audiblePtsSeconds();
				latencySamples.add(player.latencySeconds() * 1000);
				if (pending == null && !videoDone.get()) {
					pending = frames.poll();
				}
				while (pending != null && pending.ptsSeconds() < target - MAX_LATE_SECONDS) {
					dropped++;
					pending = frames.poll();
				}
				if (pending != null && pending.ptsSeconds() <= target + PRESENT_LEAD_SECONDS) {
					long uploadStart = System.nanoTime();
					screen.upload(pending.pixels(), pending.width(), pending.height());
					screen.draw();
					GL11.glFinish();
					uploadSamples.add((System.nanoTime() - uploadStart) / 1e6);

					double late = target - pending.ptsSeconds();
					lateSamples.add(late * 1000);
					maxLate = Math.max(maxLate, late);
					presented++;
					pending = frames.poll();
				}

				Thread.sleep(1);
			}

			double wall = (System.nanoTime() - startNanos) / 1e9;
			System.out.printf("playback      : %.3f s wall%n", wall);
			System.out.printf("video frames  : %d decoded, %d presented, %d dropped%n",
				decoded.get(), presented, dropped);
			System.out.printf("video fps     : %.1f presented, %.1f decoded%n",
				presented / Math.max(wall, 1e-9), decoded.get() / Math.max(wall, 1e-9));
			printStats("late ms", lateSamples, false);
			System.out.printf("max lateness  : %.1f ms%n", maxLate * 1000);
			printStats("device latency ms", latencySamples, true);
			printStats("present ms", uploadSamples, true);
		} finally {
			cancelled.set(true);
			frames.clear();
			videoThread.join(2_000);
		}
	}

	private static VideoFrame awaitFirstFrame(BlockingQueue<VideoFrame> frames,
											  AtomicReference<Throwable> error,
											  long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
		while (System.nanoTime() < deadline) {
			if (error.get() != null) {
				return null;
			}
			VideoFrame frame = frames.poll(50, TimeUnit.MILLISECONDS);
			if (frame != null) {
				return frame;
			}
		}
		return null;
	}

	private static void printStats(String label, List<Double> samples, boolean abs) {
		if (samples.isEmpty()) {
			System.out.println(label + " : no data");
			return;
		}
		double[] values = samples.stream().mapToDouble(Double::doubleValue)
			.map(v -> abs ? Math.abs(v) : v).sorted().toArray();
		double avg = Arrays.stream(values).average().orElse(0);
		System.out.printf("%-13s: avg %.2f  p50 %.2f  p95 %.2f  p99 %.2f  max %.2f%n",
			label, avg, values[values.length / 2], values[(int) Math.ceil(values.length * 0.95) - 1],
			values[(int) Math.ceil(values.length * 0.99) - 1], values[values.length - 1]);
	}
}
