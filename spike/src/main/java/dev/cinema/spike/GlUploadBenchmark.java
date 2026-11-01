package dev.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

public final class GlUploadBenchmark {
	private GlUploadBenchmark() {
	}

	public static void run(Path file) throws Exception {
		List<Long> uploadCpu = new ArrayList<>();
		List<Long> uploadSync = new ArrayList<>();
		List<Long> frameGaps = new ArrayList<>();
		int frames = 0;
		long lastFrameEnd = 0;

		try (GlScreen screen = new GlScreen(1280, 720, false);
			 FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.setPixelFormat(AV_PIX_FMT_BGR24);
			grabber.setVideoOption("threads", "auto");
			grabber.start();

			long started = System.nanoTime();
			Frame frame;
			while ((frame = grabber.grabImage()) != null) {
				if (frame.image == null || frame.image.length == 0) {
					continue;
				}

				long frameStart = System.nanoTime();
				int width = frame.imageWidth;
				int height = frame.imageHeight;
				ByteBuffer pixels = (ByteBuffer) frame.image[0];

				long uploadStart = System.nanoTime();
				screen.upload(pixels, width, height);
				long uploadEnd = System.nanoTime();
				GL11.glFinish();
				long uploadFinished = System.nanoTime();

				screen.draw();

				uploadCpu.add(uploadEnd - uploadStart);
				uploadSync.add(uploadFinished - uploadStart);
				if (lastFrameEnd != 0) {
					frameGaps.add(frameStart - lastFrameEnd);
				}
				lastFrameEnd = System.nanoTime();
				frames++;
			}
			grabber.stop();

			long elapsed = System.nanoTime() - started;
			System.out.printf("frames        : %d%n", frames);
			System.out.printf("total time    : %.3f s%n", elapsed / 1e9);
			System.out.printf("end-to-end    : %.1f fps%n", frames / (elapsed / 1e9));
			printStats("upload cpu ms", uploadCpu);
			printStats("upload sync ms", uploadSync);
			printStats("frame gap ms", frameGaps);
		}
	}

	static void printStats(String label, List<Long> nanos) {
		if (nanos.isEmpty()) {
			System.out.println(label + " : no data");
			return;
		}
		long[] values = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
		double avg = Arrays.stream(values).average().orElse(0) / 1e6;
		System.out.printf("%-14s: avg %.3f  p50 %.3f  p95 %.3f  p99 %.3f  max %.3f%n",
			label, avg, pct(values, 0.50) / 1e6, pct(values, 0.95) / 1e6,
			pct(values, 0.99) / 1e6, values[values.length - 1] / 1e6);
	}

	static long pct(long[] sorted, double p) {
		int index = (int) Math.ceil(p * sorted.length) - 1;
		index = Math.max(0, Math.min(sorted.length - 1, index));
		return sorted[index];
	}
}
