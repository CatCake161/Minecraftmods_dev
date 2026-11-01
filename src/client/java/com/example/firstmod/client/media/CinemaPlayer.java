package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
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
 * <p>Transitional ownership: this is currently a global player. The final
 * architecture assigns media decoding, playback state and A/V clock to an
 * AV Receiver block; projectors only receive its video signal and speakers
 * only receive its audio channels.
 */
public final class CinemaPlayer implements AutoCloseable {
	public static final ResourceLocation LEGACY_TEXTURE_ID = FirstMod.id("cinema_video");
	private static final double MAX_LATE_SECONDS = 0.100;
	private static final double PRESENT_LEAD_SECONDS = 0.005;

	private static CinemaPlayer active;
	private static CinemaAudioEngine legacyAudio;

	private final Minecraft client;
	private final Path file;
	private final double startSeconds;
	private final ResourceLocation textureId;
	private final CinemaVideoTexture texture;
	private final CinemaAudioEngine audio;
	private final StreamingAudioDecoder audioDecoder;
	private final BlockingQueue<VideoFrame> frames = new ArrayBlockingQueue<>(8);
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<Throwable> error = new AtomicReference<>();
	private volatile VideoDecoder videoDecoder;

	private final Thread videoThread;
	private final Thread audioThread;

	private volatile int width = -1;
	private volatile int height = -1;
	private volatile boolean paused;
	private volatile boolean audioUnavailable;
	private boolean firstVideoFrame;
	private VideoFrame pendingFrame;
	private int presented;
	private int dropped;
	private double lastPts;
	private double lastStatsAt;
	private long playbackStartNanos = -1;
	private long pausedAtNanos;
	private long pausedTotalNanos;

	private CinemaPlayer(Minecraft client, Path file, double startSeconds, CinemaAudioEngine audio,
						 ResourceLocation textureId) throws IOException {
		Ffmpeg.ffmpeg();
		this.client = client;
		this.file = file;
		this.startSeconds = startSeconds;
		this.audio = audio;
		this.textureId = textureId;
		this.texture = new CinemaVideoTexture();
		this.audioDecoder = new StreamingAudioDecoder(startSeconds, () -> !running.get());
		client.getTextureManager().register(textureId, texture);
		videoThread = new Thread(this::decodeVideo, "cinema-video");
		videoThread.setDaemon(true);
		audioThread = new Thread(this::decodeAudio, "cinema-audio-decode");
		audioThread.setDaemon(true);
	}

	public static CinemaPlayer play(Minecraft client, Path file) throws IOException {
		return play(client, file, 0.0);
	}

	public static CinemaPlayer play(Minecraft client, Path file, double startSeconds) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("not a local media file: " + file.getFileName());
		}
		stopActive(client);
		if (legacyAudio == null) {
			legacyAudio = new CinemaAudioEngine();
		}
		CinemaPlayer player = new CinemaPlayer(client, file, Math.max(0.0, startSeconds),
			legacyAudio, LEGACY_TEXTURE_ID);
		active = player;
		player.start();
		return player;
	}

	/**
	 * Opens an independent player for an AV receiver. The caller owns the audio
	 * engine and texture id, so several receivers can later get independent
	 * pipelines without sharing static state.
	 */
	public static CinemaPlayer open(Minecraft client, Path file, double startSeconds,
									CinemaAudioEngine audio, ResourceLocation textureId) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("not a local media file: " + file.getFileName());
		}
		CinemaPlayer player = new CinemaPlayer(client, file, Math.max(0.0, startSeconds), audio, textureId);
		player.start();
		return player;
	}

	/** Restarts the active file at a new timestamp. */
	public static CinemaPlayer seekActive(Minecraft client, double seconds) throws IOException {
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
		return textureId;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public float aspect() {
		return width > 0 && height > 0 ? width / (float) height : 16.0f / 9.0f;
	}

	/** Applies projector focus blur to the shared video texture for this draw. */
	public void setFocusBlur(float lod) {
		texture.setBlurBias(lod);
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
		FirstMod.LOGGER.info("cinema player: started {}", file.getFileName());
	}

	public void setPaused(boolean value) {
		if (paused == value) {
			return;
		}
		paused = value;
		audio.setPaused(paused);
		if (audioUnavailable) {
			long now = System.nanoTime();
			if (paused) {
				pausedAtNanos = now;
			} else if (pausedAtNanos != 0) {
				pausedTotalNanos += now - pausedAtNanos;
				pausedAtNanos = 0;
			}
		}
		FirstMod.LOGGER.info("cinema player: {}", paused ? "paused" : "resumed");
	}

	public void togglePause() {
		setPaused(!paused);
	}

	/** Current media position in seconds, from the audio clock or the wall clock. */
	public double positionSeconds() {
		if (audioUnavailable) {
			return wallClockTarget();
		}
		if (!audio.isStarted()) {
			return startSeconds;
		}
		return audio.audiblePtsSeconds();
	}

	public void setVolume(float volume, boolean muted) {
		audio.setMasterGain(volume, muted);
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
			playbackStartNanos = System.nanoTime();
			audio.startPlayback();
		}
		if (!audio.isStarted() && !audioUnavailable) {
			pendingFrame = frame;
			return;
		}

		double target = audioUnavailable ? wallClockTarget() : audio.audiblePtsSeconds();
		while (frame != null && frame.ptsSeconds() < target - MAX_LATE_SECONDS) {
			dropped++;
			frame.release();
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
			frame.release();
			logStatsIfNeeded();
		} else {
			pendingFrame = frame;
		}
	}

	private double wallClockTarget() {
		long now = System.nanoTime();
		long elapsed = now - (playbackStartNanos < 0 ? now : playbackStartNanos) - pausedTotalNanos;
		if (paused && pausedAtNanos != 0) {
			elapsed -= now - pausedAtNanos;
		}
		return startSeconds + Math.max(0L, elapsed) / 1e9;
	}

	private void decodeVideo() {
		CinemaPrewarm.await();
		try (VideoDecoder decoder = new VideoDecoder(file, startSeconds)) {
			videoDecoder = decoder;
			while (running.get()) {
				VideoFrame frame = decoder.next();
				if (frame == null) {
					break;
				}
				if (!running.get()) {
					frame.release();
					break;
				}
				if (!frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
					while (running.get() && !frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
						// Wait for the render thread to consume a frame.
					}
					if (!running.get()) {
						frame.release();
					}
				}
			}
		} catch (Throwable t) {
			error.compareAndSet(null, t);
		} finally {
			videoDecoder = null;
		}
	}

	private void decodeAudio() {
		CinemaPrewarm.await();
		try {
			boolean hasAudio = audioDecoder.decode(file, audio::submit);
			if (!hasAudio) {
				audioUnavailable = true;
				FirstMod.LOGGER.info("cinema player: no audio stream; using wall clock for video");
			}
		} catch (Throwable t) {
			audioUnavailable = true;
			FirstMod.LOGGER.warn("cinema audio decode failed; continuing video-only", t);
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
		VideoDecoder decoder = videoDecoder;
		if (decoder != null) {
			decoder.close();
		}
		audioDecoder.close();
		videoThread.interrupt();
		audioThread.interrupt();
		try {
			videoThread.join(1_000);
			audioThread.join(1_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		if (pendingFrame != null) {
			pendingFrame.release();
			pendingFrame = null;
		}
		VideoFrame queued;
		while ((queued = frames.poll()) != null) {
			queued.release();
		}
		client.getTextureManager().release(textureId);
		FirstMod.LOGGER.info("cinema player: stopped (presented={}, dropped={})", presented, dropped);
	}
}
