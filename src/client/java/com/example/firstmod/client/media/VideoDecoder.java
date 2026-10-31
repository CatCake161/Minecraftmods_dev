package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;

/** Decodes a video stream to packed RGBA frames with presentation timestamps. */
public final class VideoDecoder implements AutoCloseable {
	private final FFmpegFrameGrabber grabber;
	private final int width;
	private final int height;

	public VideoDecoder(Path file) throws Exception {
		grabber = new FFmpegFrameGrabber(file.toFile());
		grabber.setPixelFormat(AV_PIX_FMT_RGBA);
		grabber.setVideoOption("threads", "auto");
		grabber.start();
		width = grabber.getImageWidth();
		height = grabber.getImageHeight();
		FirstMod.LOGGER.info("cinema video decoder: {}x{} {} fps", width, height, grabber.getFrameRate());
	}

	/**
	 * Returns the next frame, or {@code null} at end of stream. JavaCV reuses its
	 * internal image buffer, so the returned frame owns a fresh direct copy.
	 */
	public VideoFrame next() throws Exception {
		Frame frame;
		while ((frame = grabber.grabImage()) != null) {
			if (frame.image == null || frame.image.length == 0) {
				continue;
			}
			int frameWidth = frame.imageWidth;
			int frameHeight = frame.imageHeight;
			int bytes = frameWidth * frameHeight * 4;
			ByteBuffer source = ((ByteBuffer) frame.image[0]).duplicate();
			if (source.remaining() < bytes) {
				FirstMod.LOGGER.warn("cinema video decoder: short frame buffer {} < {}", source.remaining(), bytes);
				continue;
			}
			ByteBuffer pixels = ByteBuffer.allocateDirect(bytes);
			source.limit(source.position() + bytes);
			pixels.put(source);
			pixels.flip();
			double pts = frame.timestamp >= 0 ? frame.timestamp / 1_000_000.0 : 0.0;
			return new VideoFrame(pixels, frameWidth, frameHeight, pts);
		}
		return null;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() throws Exception {
		grabber.stop();
		grabber.close();
	}
}
