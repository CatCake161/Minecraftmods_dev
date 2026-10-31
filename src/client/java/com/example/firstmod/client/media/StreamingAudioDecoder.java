package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Streams decoded 5.1 audio from an external FFmpeg process into bounded chunks.
 */
public final class StreamingAudioDecoder implements AutoCloseable {
	private static final int CHANNELS = 6;
	private static final int SAMPLE_RATE = 48_000;

	private Process process;
	private volatile boolean closed;
	private final BooleanSupplier cancelled;
	private final double startSeconds;

	public StreamingAudioDecoder(double startSeconds, BooleanSupplier cancelled) {
		this.startSeconds = Math.max(0.0, startSeconds);
		this.cancelled = cancelled;
	}

	public boolean decode(Path file, ChunkSink sink) throws Exception {
		int chunkFrames = SAMPLE_RATE / 2;
		int chunkBytes = chunkFrames * CHANNELS * 2;
		byte[] bytes = new byte[chunkBytes];
		long totalFrames = 0;
		long started = System.nanoTime();

		List<String> command = new ArrayList<>();
		command.add(Ffmpeg.ffmpeg().toString());
		command.add("-hide_banner");
		command.add("-nostdin");
		command.add("-loglevel");
		command.add("error");
		if (startSeconds > 0.0) {
			command.add("-ss");
			command.add(String.format(java.util.Locale.ROOT, "%.3f", startSeconds));
		}
		command.add("-i");
		command.add(file.toString());
		command.add("-map");
		command.add("0:a:0");
		command.add("-vn");
		command.add("-sn");
		command.add("-dn");
		command.add("-ac");
		command.add(Integer.toString(CHANNELS));
		command.add("-ar");
		command.add(Integer.toString(SAMPLE_RATE));
		command.add("-channel_layout");
		command.add("5.1");
		command.add("-f");
		command.add("s16le");
		command.add("-acodec");
		command.add("pcm_s16le");
		command.add("pipe:1");

		process = new ProcessBuilder(command).redirectErrorStream(false).start();
		FirstMod.LOGGER.info("cinema audio decoder: ffmpeg {} Hz, {} channels, start={} s",
			SAMPLE_RATE, CHANNELS, String.format("%.2f", startSeconds));

		try (InputStream input = new BufferedInputStream(process.getInputStream(), 64 * 1024)) {
			while (!closed && !cancelled.getAsBoolean()) {
				int read = readFully(input, bytes);
				if (read < 0) {
					break;
				}
				if (read == 0) {
					continue;
				}
				int frames = read / (CHANNELS * 2);
				if (frames <= 0) {
					continue;
				}
				double pts = startSeconds + totalFrames / (double) SAMPLE_RATE;
				ShortBuffer samples = ByteBuffer.wrap(bytes, 0, read)
					.order(ByteOrder.LITTLE_ENDIAN)
					.asShortBuffer();
				short[] data = new short[samples.remaining()];
				samples.get(data);
				sink.accept(new AudioChunk(pts, data));
				totalFrames += frames;
			}
		}

		int exit = process.waitFor();
		if (exit != 0 && totalFrames == 0 && !closed && !cancelled.getAsBoolean()) {
			FirstMod.LOGGER.warn("cinema audio decoder: no usable audio stream (ffmpeg exit {})", exit);
			return false;
		}
		double elapsed = (System.nanoTime() - started) / 1e9;
		double duration = totalFrames / (double) SAMPLE_RATE;
		FirstMod.LOGGER.info("cinema audio decoder: {} frames, {} s media in {} s wall",
			totalFrames, String.format("%.3f", duration), String.format("%.3f", elapsed));
		return totalFrames > 0;
	}

	private static int readFully(InputStream input, byte[] buffer) throws IOException {
		int total = 0;
		while (total < buffer.length) {
			int read = input.read(buffer, total, buffer.length - total);
			if (read < 0) {
				return total == 0 ? -1 : total;
			}
			total += read;
			if (read == 0) {
				break;
			}
		}
		return total;
	}

	@FunctionalInterface
	public interface ChunkSink {
		void accept(AudioChunk chunk) throws InterruptedException;
	}

	@Override
	public void close() {
		closed = true;
		Process current = process;
		if (current != null) {
			current.destroy();
			try {
				if (!current.waitFor(500, TimeUnit.MILLISECONDS)) {
					current.destroyForcibly();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				current.destroyForcibly();
			}
		}
	}
}
