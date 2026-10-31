package dev.catcake.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ShortBuffer;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/**
 * Decodes a 5.1 source as six discrete channels and identifies the dominant
 * tone in each channel. The generated {@code test51.mp4} uses a distinct tone
 * per channel, so this verifies both channel order and that JavaCV/FFmpeg is
 * not silently downmixing.
 */
final class SurroundAudioBenchmark {
	private static final int SAMPLE_RATE = 48_000;
	private static final int CHANNELS = 6;
	private static final int WINDOW_FRAMES = 24_000; // 0.5 s

	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final double[] TEST_FREQUENCIES = {440, 554, 659, 80, 880, 988};

	private SurroundAudioBenchmark() {
	}

	static void run(Path file) throws Exception {
		double[][] real = new double[CHANNELS][TEST_FREQUENCIES.length];
		double[][] imag = new double[CHANNELS][TEST_FREQUENCIES.length];
		double[] sumSquares = new double[CHANNELS];
		long[] peak = new long[CHANNELS];
		int collectedFrames = 0;

		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.setSampleFormat(AV_SAMPLE_FMT_S16);
			grabber.setSampleRate(SAMPLE_RATE);
			grabber.setAudioChannels(CHANNELS);
			grabber.start();
			System.out.printf("decoder      : %d Hz, %d channels requested, %d reported, codec=%s%n",
				grabber.getSampleRate(), CHANNELS, grabber.getAudioChannels(), grabber.getAudioCodecName());

			Frame frame;
			while (collectedFrames < WINDOW_FRAMES && (frame = grabber.grabSamples()) != null) {
				if (frame.samples == null || frame.samples.length == 0) {
					continue;
				}
				ShortBuffer source = (ShortBuffer) frame.samples[0];
				int frames = source.remaining() / CHANNELS;
				for (int i = 0; i < frames && collectedFrames < WINDOW_FRAMES; i++, collectedFrames++) {
					for (int channel = 0; channel < CHANNELS; channel++) {
						int sample = source.get();
						sumSquares[channel] += (double) sample * sample;
						peak[channel] = Math.max(peak[channel], Math.abs((long) sample));
						double phase = 2.0 * Math.PI * collectedFrames / SAMPLE_RATE;
						for (int f = 0; f < TEST_FREQUENCIES.length; f++) {
							double angle = phase * TEST_FREQUENCIES[f];
							real[channel][f] += sample * Math.cos(angle);
							imag[channel][f] -= sample * Math.sin(angle);
						}
					}
				}
			}
			grabber.stop();
		}

		if (collectedFrames == 0) {
			System.out.println("no audio samples decoded");
			return;
		}

		System.out.printf("analysed     : %d frames (%.3f s)%n", collectedFrames, collectedFrames / (double) SAMPLE_RATE);
		System.out.println();
		System.out.println("channel  rms      peak   dominant   power at FL/FR/FC/LFE/BL/BR test tones");
		for (int channel = 0; channel < CHANNELS; channel++) {
			double rms = Math.sqrt(sumSquares[channel] / collectedFrames);
			int best = 0;
			double bestPower = -1;
			for (int f = 0; f < TEST_FREQUENCIES.length; f++) {
				double power = real[channel][f] * real[channel][f] + imag[channel][f] * imag[channel][f];
				if (power > bestPower) {
					bestPower = power;
					best = f;
				}
			}
			System.out.printf("%-7s %8.1f %8d   %6.0f Hz   ", LABELS[channel], rms, peak[channel], TEST_FREQUENCIES[best]);
			for (int f = 0; f < TEST_FREQUENCIES.length; f++) {
				double power = real[channel][f] * real[channel][f] + imag[channel][f] * imag[channel][f];
				System.out.printf("%10.1f", Math.sqrt(power) / collectedFrames);
			}
			System.out.println();
		}

		boolean mappedCorrectly = true;
		for (int channel = 0; channel < CHANNELS; channel++) {
			double own = magnitude(real[channel][channel], imag[channel][channel]);
			for (int f = 0; f < TEST_FREQUENCIES.length; f++) {
				if (f != channel && magnitude(real[channel][f], imag[channel][f]) >= own * 0.5) {
					mappedCorrectly = false;
				}
			}
		}
		System.out.println();
		System.out.println("channel map  : " + (mappedCorrectly ? "OK (diagonal tones dominate)" : "MISMATCH"));
	}

	private static double magnitude(double real, double imag) {
		return Math.sqrt(real * real + imag * imag);
	}
}
