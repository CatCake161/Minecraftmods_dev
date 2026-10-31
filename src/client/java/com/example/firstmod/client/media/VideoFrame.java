package com.example.firstmod.client.media;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One decoded video frame in packed RGBA.
 *
 * <p>The buffer is borrowed from {@link FrameBufferPool}; call {@link #release()}
 * exactly once after it has been uploaded or dropped.
 */
public final class VideoFrame {
	private final ByteBuffer rgba;
	private final int width;
	private final int height;
	private final double ptsSeconds;
	private final AtomicBoolean released = new AtomicBoolean();

	public VideoFrame(ByteBuffer rgba, int width, int height, double ptsSeconds) {
		this.rgba = rgba;
		this.width = width;
		this.height = height;
		this.ptsSeconds = ptsSeconds;
	}

	public ByteBuffer rgba() {
		return rgba;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public double ptsSeconds() {
		return ptsSeconds;
	}

	public void release() {
		if (released.compareAndSet(false, true)) {
			FrameBufferPool.release(rgba);
		}
	}
}
