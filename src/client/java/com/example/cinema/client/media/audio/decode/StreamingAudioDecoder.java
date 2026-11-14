package com.example.cinema.client.media.audio.decode;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.AudioChunk;
import com.example.cinema.client.media.audio.dsp.StereoUpmixer;
import com.example.cinema.client.media.ffmpeg.Ffmpeg;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;

/**
 * Streams decoded 5.1 audio from an external FFmpeg process into bounded chunks.
 */
public final class StreamingAudioDecoder implements AutoCloseable {
	private static final int CHANNELS = 6;
	private static final int SAMPLE_RATE = 48_000;
	private static final int MAX_LOGGED_ERROR_LINES = 20;
	private static final Map<Path, Integer> SOURCE_CHANNEL_CACHE = new ConcurrentHashMap<>();

	private Process process;
	private Thread errorDrain;
	private volatile boolean closed;
	private final BooleanSupplier cancelled;
	private final double startSeconds;

	public StreamingAudioDecoder(double startSeconds, BooleanSupplier cancelled) {
		this.startSeconds = Math.max(0.0, startSeconds);
		this.cancelled = cancelled;
	}

	public boolean decode(Path file, ChunkSink sink) throws Exception {
		int sourceChannels = sourceChannels(file);
		boolean upmix = sourceChannels == 2;
		int chunkFrames = SAMPLE_RATE / 2;
		int chunkBytes = chunkFrames * sourceChannels * Float.BYTES;
		byte[] bytes = new byte[chunkBytes];
		StereoUpmixer upmixer = upmix ? new StereoUpmixer() : null;
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
		command.add(Integer.toString(sourceChannels));
		command.add("-ar");
		command.add(Integer.toString(SAMPLE_RATE));
		command.add("-channel_layout");
		command.add(upmix ? "stereo" : "5.1");
		command.add("-f");
		command.add("f32le");
		command.add("-acodec");
		command.add("pcm_f32le");
		command.add("pipe:1");

		process = new ProcessBuilder(command).redirectErrorStream(false).start();
		Process current = process;
		startErrorDrain(current);
		CinemaLog.info(CinemaLog.AUDIO_DECODER, "ffmpeg {} Hz, {} source channels{}, 32-bit float, start={} s",
			SAMPLE_RATE, sourceChannels, upmix ? " -> 5.1 upmix" : "",
			String.format("%.2f", startSeconds));

		try (InputStream input = new BufferedInputStream(process.getInputStream(), 64 * 1024)) {
			while (!closed && !cancelled.getAsBoolean()) {
				int read = readFully(input, bytes);
				if (read < 0) {
					break;
				}
				if (read == 0) {
					continue;
				}
				int frames = read / (sourceChannels * Float.BYTES);
				if (frames <= 0) {
					continue;
				}
				double pts = startSeconds + totalFrames / (double) SAMPLE_RATE;
				FloatBuffer samples = ByteBuffer.wrap(bytes, 0, read)
					.order(ByteOrder.LITTLE_ENDIAN)
					.asFloatBuffer();
				float[] source = new float[samples.remaining()];
				samples.get(source);
				float[] data = upmixer == null ? source : upmixer.upmix(source, frames);
				sink.accept(new AudioChunk(pts, data));
				totalFrames += frames;
			}
		}

		int exit = current.waitFor();
		awaitErrorDrain();
		if (exit != 0 && totalFrames == 0 && !closed && !cancelled.getAsBoolean()) {
			CinemaLog.warn(CinemaLog.AUDIO_DECODER, "no usable audio stream (ffmpeg exit {})", exit);
			return false;
		}
		double elapsed = (System.nanoTime() - started) / 1e9;
		double duration = totalFrames / (double) SAMPLE_RATE;
		CinemaLog.info(CinemaLog.AUDIO_DECODER, "{} frames, {} s media in {} s wall",
			totalFrames, String.format("%.3f", duration), String.format("%.3f", elapsed));
		return totalFrames > 0;
	}

	/**
	 * Drains FFmpeg stderr on a daemon thread. The process writes diagnostics
	 * there, and an unread pipe can fill up and block decoding; log a bounded
	 * number of lines and count the rest instead of keeping every line.
	 */
	private void startErrorDrain(Process current) {
		Thread drain = new Thread(() -> drainErrors(current), "cinema-ffmpeg-stderr");
		drain.setDaemon(true);
		errorDrain = drain;
		drain.start();
	}

	private void drainErrors(Process current) {
		int suppressed = 0;
		try {
			suppressed = drainErrorStream(current.getErrorStream(),
				MAX_LOGGED_ERROR_LINES,
				line -> CinemaLog.warn(CinemaLog.AUDIO_DECODER, "ffmpeg: {}", line));
		} catch (IOException e) {
			if (!closed) {
				CinemaLog.debug(CinemaLog.AUDIO_DECODER, "ffmpeg stderr drain ended: {}",
					e.getMessage());
			}
		} finally {
			if (suppressed > 0) {
				CinemaLog.warn(CinemaLog.AUDIO_DECODER,
					"{} additional ffmpeg stderr lines suppressed", suppressed);
			}
		}
	}

	/**
	 * Reads stderr to EOF, passing at most {@code maxLines} non-blank lines to
	 * {@code lineSink}. Returns the number of suppressed non-blank lines.
	 */
	static int drainErrorStream(InputStream stream, int maxLines,
								Consumer<String> lineSink) throws IOException {
		int logged = 0;
		int suppressed = 0;
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				if (logged < maxLines) {
					lineSink.accept(line);
					logged++;
				} else {
					suppressed++;
				}
			}
		}
		return suppressed;
	}

	private void awaitErrorDrain() {
		Thread drain = errorDrain;
		if (drain == null || drain == Thread.currentThread()) {
			return;
		}
		try {
			drain.join(500L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static int sourceChannels(Path file) {
		Path key = file.toAbsolutePath().normalize();
		Integer cached = SOURCE_CHANNEL_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		int channels = CHANNELS;
		try {
			Ffmpeg.AudioInfo info = Ffmpeg.probeAudio(file);
			if (info != null) {
				channels = info.channels() <= 2 ? 2 : CHANNELS;
			}
		} catch (Exception e) {
			CinemaLog.warn(CinemaLog.AUDIO_DECODER, "could not probe source channels; assuming 5.1: {}",
				e.getMessage());
		}
		SOURCE_CHANNEL_CACHE.put(key, channels);
		return channels;
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
		awaitErrorDrain();
	}
}
