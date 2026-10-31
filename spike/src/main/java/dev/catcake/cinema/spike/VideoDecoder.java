package dev.catcake.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24;

/** Decodes a video stream to packed BGR24 frames with presentation timestamps. */
final class VideoDecoder implements AutoCloseable {
	private final FFmpegFrameGrabber grabber;
	private final int width;
	private final int height;

	VideoDecoder(Path file) throws Exception {
		grabber = new FFmpegFrameGrabber(file.toFile());
		grabber.setPixelFormat(AV_PIX_FMT_BGR24);
		grabber.setVideoOption("threads", "auto");
		grabber.start();
		width = grabber.getImageWidth();
		height = grabber.getImageHeight();
	}

	/**
	 * Returns the next frame, or {@code null} at end of stream. Each frame owns a
	 * freshly allocated buffer because JavaCV reuses its internal image buffer.
	 */
	VideoFrame next() throws Exception {
		Frame frame;
		while ((frame = grabber.grabImage()) != null) {
			if (frame.image == null || frame.image.length == 0) {
				continue;
			}
			int frameWidth = frame.imageWidth;
			int frameHeight = frame.imageHeight;
			ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
			ByteBuffer pixels = ByteBuffer.allocateDirect(frameWidth * frameHeight * 3);
			pixels.put(source);
			pixels.flip();
			return new VideoFrame(pixels, frameWidth, frameHeight, frame.timestamp / 1_000_000.0);
		}
		return null;
	}

	int width() {
		return width;
	}

	int height() {
		return height;
	}

	@Override
	public void close() throws Exception {
		grabber.stop();
		grabber.close();
	}
}
