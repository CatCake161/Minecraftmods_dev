package dev.catcake.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

public final class DecodeBenchmark {
	private DecodeBenchmark() {
	}

	public static void run(Path file) throws Exception {
		System.out.println("== FFmpeg video decode (H.264 -> BGR24) ==");
		ffmpegPass(file, true);
		ffmpegPass(file, false);

		System.out.println();
		System.out.println("== JCodec video decode (H.264 -> YUV) ==");
		jcodecPass(file);
	}

	private static void ffmpegPass(Path file, boolean warmup) throws Exception {
		List<Long> frameTimes = new ArrayList<>();
		int frames = 0;
		long firstPts = -1;
		long lastPts = 0;

		long started = System.nanoTime();
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.setPixelFormat(AV_PIX_FMT_BGR24);
			grabber.setVideoOption("threads", "auto");
			grabber.start();

			Frame frame;
			while ((frame = grabber.grabImage()) != null) {
				if (frame.image == null || frame.image.length == 0) {
					continue;
				}
				frames++;
				if (firstPts < 0) {
					firstPts = frame.timestamp;
				}
				lastPts = frame.timestamp;
				if (!warmup) {
					frameTimes.add(System.nanoTime());
				}
			}
			grabber.stop();
		}

		long totalNanos = System.nanoTime() - started;
		if (warmup) {
			System.out.printf("warmup   : %d frames in %.3f s (%.1f fps)%n",
				frames, totalNanos / 1e9, frames / (totalNanos / 1e9));
			return;
		}

		long[] deltas = deltas(frameTimes);
		System.out.printf("frames   : %d%n", frames);
		System.out.printf("time     : %.3f s%n", totalNanos / 1e9);
		System.out.printf("throughput: %.1f fps%n", frames / (totalNanos / 1e9));
		System.out.printf("media pts: %.3f .. %.3f s%n", firstPts / 1e6, lastPts / 1e6);
		System.out.printf("frame ms : avg %.3f  p50 %.3f  p95 %.3f  p99 %.3f  max %.3f%n",
			avg(deltas) / 1e6, pct(deltas, 0.50) / 1e6, pct(deltas, 0.95) / 1e6,
			pct(deltas, 0.99) / 1e6, deltas[deltas.length - 1] / 1e6);
	}

	private static void jcodecPass(Path file) {
		SeekableByteChannel channel = null;
		try {
			channel = NIOUtils.readableChannel(file.toFile());
			FrameGrab grab = FrameGrab.createFrameGrab(channel);
			long started = System.nanoTime();
			int frames = 0;
			while (grab.getNativeFrame() != null) {
				frames++;
			}
			long totalNanos = System.nanoTime() - started;
			System.out.printf("frames   : %d%n", frames);
			System.out.printf("time     : %.3f s%n", totalNanos / 1e9);
			System.out.printf("throughput: %.1f fps%n", frames / (totalNanos / 1e9));
		} catch (Throwable t) {
			System.out.println("FAILED   : " + t.getClass().getSimpleName() + ": " + t.getMessage());
		} finally {
			if (channel != null) {
				try {
					channel.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private static long[] deltas(List<Long> timestamps) {
		if (timestamps.size() < 2) {
			return new long[]{0};
		}
		long[] result = new long[timestamps.size() - 1];
		for (int i = 1; i < timestamps.size(); i++) {
			result[i - 1] = timestamps.get(i) - timestamps.get(i - 1);
		}
		Arrays.sort(result);
		return result;
	}

	private static double avg(long[] values) {
		return Arrays.stream(values).average().orElse(0);
	}

	private static long pct(long[] sorted, double p) {
		int index = (int) Math.ceil(p * sorted.length) - 1;
		index = Math.max(0, Math.min(sorted.length - 1, index));
		return sorted[index];
	}
}
