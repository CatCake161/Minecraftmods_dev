package dev.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.BufferUtils;

import java.nio.ShortBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

final class AudioDecoder {
	private AudioDecoder() {
	}

	static List<AudioChunk> decode(Path file, int sampleRate, int channels) throws Exception {
		int chunkFrames = sampleRate / 2;
		int chunkShorts = chunkFrames * channels;

		List<AudioChunk> chunks = new ArrayList<>();
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

			Frame frame;
			while ((frame = grabber.grabSamples()) != null) {
				if (frame.samples == null || frame.samples.length == 0) {
					continue;
				}

				ShortBuffer source = (ShortBuffer) frame.samples[0];
				int frameFrames = source.remaining() / channels;
				double framePts = frame.timestamp >= 0 ? frame.timestamp / 1_000_000.0 : fallbackPts;
				fallbackPts = framePts + frameFrames / (double) sampleRate;
				int consumedFrames = 0;

				while (source.hasRemaining()) {
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
						chunks.add(new AudioChunk(chunkStartPts, toArray(accumulator)));
						accumulator = BufferUtils.createShortBuffer(chunkShorts);
						accumulatedFrames = 0;
					}
				}
			}
			grabber.stop();
		}

		if (accumulatedFrames > 0) {
			accumulator.flip();
			chunks.add(new AudioChunk(chunkStartPts < 0 ? 0 : chunkStartPts, toArray(accumulator)));
		}

		double elapsed = (System.nanoTime() - started) / 1e9;
		double duration = totalFrames / (double) sampleRate;
		System.out.printf("audio decode : %d frames, %.3f s media, %.3f s wall (%.1fx realtime), %d chunks%n",
			totalFrames, duration, elapsed, duration / Math.max(elapsed, 1e-9), chunks.size());
		return chunks;
	}

	private static short[] toArray(ShortBuffer buffer) {
		short[] result = new short[buffer.remaining()];
		buffer.get(result);
		return result;
	}
}
