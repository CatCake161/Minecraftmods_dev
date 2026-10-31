package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client-side cinema playback pipeline for one local file.
 *
 * <p>Video and audio decode independently. Video frames go through a small
 * bounded queue and are uploaded on the render thread. Audio is deinterleaved
 * into six speaker sources by {@link CinemaAudioEngine}; the audible audio PTS
 * is the master clock for video presentation.
 */
public final class CinemaPlayer implements AutoCloseable {
	public static final ResourceLocation TEXTURE_ID = FirstMod.id("cinema_video");
	private static final double MAX_LATE_SECONDS = 0.100;
	private static final double PRESENT_LEAD_SECONDS = 0.005;

	private static CinemaPlayer active;
	private static CinemaAudioEngine sharedAudio;

	private final Minecraft client;
	private final Path file;
	private final double startSeconds;
	private final CinemaVideoTexture texture = new CinemaVideoTexture();
	private final CinemaAudioEngine audio;
	private final BlockingQueue<VideoFrame> frames = new ArrayBlockingQueue<>(8);
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<Throwable> error = new AtomicReference<>();

	private final Thread videoThread;
	private final Thread audioThread;

	private volatile int width = -1;
	private volatile int height = -1;
	private volatile boolean paused;
	private boolean firstVideoFrame;
	private VideoFrame pendingFrame;
	private int presented;
	private int dropped;
	private double lastPts;
	private double lastStatsAt;

	private CinemaPlayer(Minecraft client, Path file, double startSeconds) {
		this.client = client;
		this.file = file;
		this.startSeconds = startSeconds;
		if (sharedAudio == null) {
			sharedAudio = new CinemaAudioEngine();
		}
		this.audio = sharedAudio;
		client.getTextureManager().register(TEXTURE_ID, texture);
		videoThread = new Thread(this::decodeVideo, "cinema-video");
		videoThread.setDaemon(true);
		audioThread = new Thread(this::decodeAudio, "cinema-audio-decode");
		audioThread.setDaemon(true);
	}

	public static CinemaPlayer play(Minecraft client, Path file) {
		return play(client, file, 0.0);
	}

	public static CinemaPlayer play(Minecraft client, Path file, double startSeconds) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("not a local media file: " + file);
		}
		stopActive(client);
		CinemaPlayer player = new CinemaPlayer(client, file, Math.max(0.0, startSeconds));
		active = player;
		player.start();
		return player;
	}

	/** Restarts the active file at a new timestamp. */
	public static CinemaPlayer seekActive(Minecraft client, double seconds) {
		CinemaPlayer current = active;
		if (current == null) {
			throw new IllegalStateException("cinema is not playing");
		}
		return play(client, current.file, seconds);
	}

	public static CinemaPlayer active() {
		return active;
	}

	public static void presentActiveFrame() {
		CinemaPlayer player = active;
		if (player != null) {
			player.presentFrame();
		}
	}

	public static void stopActive(Minecraft client) {
		CinemaPlayer player = active;
		active = null;
		if (player != null) {
			player.close();
		}
	}

	public ResourceLocation textureId() {
		return TEXTURE_ID;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean isPaused() {
		return paused;
	}

	public Throwable error() {
		return error.get();
	}

	private void start() {
		audio.start();
		videoThread.start();
		audioThread.start();
		FirstMod.LOGGER.info("cinema player: started {}", file);
	}

	public void togglePause() {
		paused = !paused;
		audio.setPaused(paused);
		FirstMod.LOGGER.info("cinema player: {}", paused ? "paused" : "resumed");
	}

	/**
	 * Called from the render thread before the screen quad is drawn. It selects
	 * the frame that should be visible for the current audible audio time and
	 * uploads it to the video texture.
	 */
	public void presentFrame() {
		if (!running.get() || paused) {
			return;
		}
		Throwable failure = error.get();
		if (failure != null) {
			FirstMod.LOGGER.error("cinema player decode failed", failure);
			running.set(false);
			return;
		}

		VideoFrame frame = pendingFrame != null ? pendingFrame : frames.poll();
		if (frame == null) {
			return;
		}
		if (!firstVideoFrame) {
			firstVideoFrame = true;
			audio.startPlayback();
		}
		if (!audio.isStarted()) {
			pendingFrame = frame;
			return;
		}

		double target = audio.audiblePtsSeconds();
		while (frame != null && frame.ptsSeconds() < target - MAX_LATE_SECONDS) {
			dropped++;
			frame = frames.poll();
		}
		if (frame == null) {
			pendingFrame = null;
			return;
		}
		if (frame.ptsSeconds() <= target + PRESENT_LEAD_SECONDS) {
			texture.upload(frame.rgba(), frame.width(), frame.height());
			width = frame.width();
			height = frame.height();
			lastPts = frame.ptsSeconds();
			presented++;
			pendingFrame = null;
			logStatsIfNeeded();
		} else {
			pendingFrame = frame;
		}
	}

	private void decodeVideo() {
		CinemaPrewarm.await();
		try (VideoDecoder decoder = new VideoDecoder(file, startSeconds)) {
			VideoFrame frame;
			while (running.get() && (frame = decoder.next()) != null) {
				if (!frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
					while (running.get() && !frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
						// Wait for the render thread to consume a frame.
					}
				}
			}
		} catch (Throwable t) {
			error.compareAndSet(null, t);
		}
	}

	private void decodeAudio() {
		CinemaPrewarm.await();
		try {
			StreamingAudioDecoder.decode(file, CinemaAudioEngine.SAMPLE_RATE, CinemaAudioEngine.CHANNELS,
				startSeconds, audio::submit, () -> !running.get());
		} catch (Throwable t) {
			error.compareAndSet(null, t);
		}
	}

	private void logStatsIfNeeded() {
		double now = System.nanoTime() / 1e9;
		if (lastStatsAt == 0) {
			lastStatsAt = now;
		}
		if (now - lastStatsAt >= 5.0) {
			lastStatsAt = now;
			FirstMod.LOGGER.info("cinema player: {}x{}, presented={}, dropped={}, audioPts={}, latency={} ms",
				width, height, presented, dropped,
				String.format("%.3f", audio.audiblePtsSeconds()),
				String.format("%.1f", audio.latencySeconds() * 1000));
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		running.set(false);
		audio.reset();
		videoThread.interrupt();
		audioThread.interrupt();
		try {
			videoThread.join(1_000);
			audioThread.join(1_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		client.getTextureManager().release(TEXTURE_ID);
		FirstMod.LOGGER.info("cinema player: stopped (presented={}, dropped={})", presented, dropped);
	}
}
