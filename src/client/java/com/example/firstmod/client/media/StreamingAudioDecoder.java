package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.BufferUtils;

import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/**
 * Streams decoded 5.1 audio into bounded chunks.
 *
 * <p>The sink is expected to block when the playback queue is full. The
 * decoder therefore never holds more than a small, bounded amount of PCM.
 */
public final class StreamingAudioDecoder {
	private StreamingAudioDecoder() {
	}

	@FunctionalInterface
	public interface ChunkSink {
		void accept(AudioChunk chunk) throws InterruptedException;
	}

	public static void decode(Path file, int sampleRate, int channels, ChunkSink sink,
							  BooleanSupplier cancelled) throws Exception {
		decode(file, sampleRate, channels, 0.0, sink, cancelled);
	}

	public static void decode(Path file, int sampleRate, int channels, double startSeconds, ChunkSink sink,
							  BooleanSupplier cancelled) throws Exception {
		int chunkFrames = sampleRate / 2;
		int chunkShorts = chunkFrames * channels;
		ShortBuffer accumulator = BufferUtils.createShortBuffer(chunkShorts);
		int accumulatedFrames = 0;
		double chunkStartPts = -1;
		double fallbackPts = 0;
		long totalFrames = 0;
		long started = System.nanoTime();

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(sampleRate);
			grabber.setAudioChannels(channels);
			grabber.start();
			if (startSeconds > 0.0) {
				grabber.setTimestamp((long) (startSeconds * 1_000_000L));
			}
			FirstMod.LOGGER.info("cinema audio decoder: {} Hz, {} channels requested, {} reported, codec={}, start={} s",
				grabber.getSampleRate(), channels, grabber.getAudioChannels(), grabber.getAudioCodecName(),
				String.format("%.2f", startSeconds));

			Frame frame;
			while (!cancelled.getAsBoolean() && (frame = grabber.grabSamples()) != null) {
				if (frame.samples == null || frame.samples.length == 0) {
					continue;
				}
				ShortBuffer source = (ShortBuffer) frame.samples[0];
				int frameFrames = source.remaining() / channels;
				double framePts = frame.timestamp >= 0 ? frame.timestamp / 1_000_000.0 : fallbackPts;
				fallbackPts = framePts + frameFrames / (double) sampleRate;
				int consumedFrames = 0;

				while (source.hasRemaining() && !cancelled.getAsBoolean()) {
					if (accumulatedFrames == 0) {
						chunkStartPts = framePts + consumedFrames / (double) sampleRate;
					}

					int framesToCopy = Math.min(source.remaining() / channels, chunkFrames - accumulatedFrames);
					int shortsToCopy = framesToCopy * channels;
					ShortBuffer slice = source.duplicate();
					slice.limit(slice.position() + shortsToCopy);
					accumulator.put(slice);
					source.position(source.position() + shortsToCopy);

					accumulatedFrames += framesToCopy;
					consumedFrames += framesToCopy;
					totalFrames += framesToCopy;

					if (accumulatedFrames == chunkFrames) {
						accumulator.flip();
						sink.accept(new AudioChunk(chunkStartPts, toArray(accumulator)));
						accumulator = BufferUtils.createShortBuffer(chunkShorts);
						accumulatedFrames = 0;
					}
				}
			}

			if (accumulatedFrames > 0 && !cancelled.getAsBoolean()) {
				accumulator.flip();
				sink.accept(new AudioChunk(chunkStartPts < 0 ? 0 : chunkStartPts, toArray(accumulator)));
			}
		}

		double elapsed = (System.nanoTime() - started) / 1e9;
		double duration = totalFrames / (double) sampleRate;
		FirstMod.LOGGER.info("cinema audio decoder: {} frames, {} s media in {} s wall",
			totalFrames, String.format("%.3f", duration), String.format("%.3f", elapsed));
	}

	private static short[] toArray(ShortBuffer buffer) {
		short[] result = new short[buffer.remaining()];
		buffer.get(result);
		return result;
	}
}
