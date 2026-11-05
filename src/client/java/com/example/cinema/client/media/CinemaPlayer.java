package com.example.cinema.client.media;

import com.example.cinema.Cinema;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
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
	public static final ResourceLocation LEGACY_TEXTURE_ID = Cinema.id("legacy_video");
	private static final double MAX_LATE_SECONDS = 0.100;
	private static final double PRESENT_LEAD_SECONDS = 0.005;
	private static final int MAX_PENDING_UPLOADS = 3;

	private static CinemaPlayer active;
	private static CinemaAudioEngine legacyAudio;

	private final Minecraft client;
	private final Path file;
	private final double startSeconds;
	private final ResourceLocation textureId;
	private final CinemaVideoTexture texture;
	private final boolean ownsTexture;
	private final CinemaAudioEngine audio;
	private final BlockingQueue<VideoFrame> frames = new ArrayBlockingQueue<>(8);
	private final Deque<PendingUpload> pendingUploads = new ArrayDeque<>();
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicReference<Throwable> error = new AtomicReference<>();
	private final Object seekLock = new Object();
	private volatile StreamingAudioDecoder audioDecoder;
	private volatile VideoDecoder videoDecoder;
	private volatile Thread seekWorker;
	private volatile Ffmpeg.VideoInfo videoInfo;
	private volatile double seekTarget = Double.NaN;
	private volatile double videoSeekTarget = Double.NaN;
	private volatile boolean seekResetPending;
	private volatile boolean seekStarting;
	private long seekGeneration;
	private volatile double playbackFloorSeconds;

	private final Thread videoThread;
	private final Thread audioThread;

	private volatile int width = -1;
	private volatile int height = -1;
	private volatile boolean paused;
	private volatile boolean audioUnavailable;
	/** True for audio-only media; video-extension files may set it after probing. */
	private volatile boolean audioOnly;
	private boolean firstVideoFrame;
	private boolean firstFramePresented;
	private long firstFrameWaitNanos;
	private VideoFrame pendingFrame;
	private int presented;
	private int dropped;
	private double lastPtsSeconds;
	private double lastStatsAt;
	private long playbackStartNanos = -1;
	private long pausedAtNanos;
	private long pausedTotalNanos;

	private CinemaPlayer(Minecraft client, Path file, double startSeconds, CinemaAudioEngine audio,
						 ResourceLocation textureId, CinemaVideoTexture texture) throws IOException {
		Ffmpeg.ffmpeg();
		this.client = client;
		this.file = file;
		this.startSeconds = startSeconds;
		this.playbackFloorSeconds = startSeconds;
		String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
		this.audioOnly = MediaLibrary.isAudioFile(fileName);
		this.audio = audio;
		this.textureId = textureId;
		this.ownsTexture = texture == null;
		this.texture = ownsTexture ? new CinemaVideoTexture() : texture;
		if (ownsTexture) {
			client.getTextureManager().register(textureId, this.texture);
		}
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
			legacyAudio, LEGACY_TEXTURE_ID, null);
		active = player;
		player.start();
		return player;
	}

	/**
	 * Opens an independent player for an AV receiver. The caller owns the audio
	 * engine and the video texture, so a seek can reuse the same GL texture
	 * instead of deleting and recreating it for every restart.
	 */
	public static CinemaPlayer open(Minecraft client, Path file, double startSeconds,
									CinemaAudioEngine audio, ResourceLocation textureId,
									CinemaVideoTexture texture) throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("not a local media file: " + file.getFileName());
		}
		CinemaPlayer player = new CinemaPlayer(client, file, Math.max(0.0, startSeconds),
			audio, textureId, texture);
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

	public int presentedFrames() {
		return presented;
	}

	public int droppedFrames() {
		return dropped;
	}

	public double lastPtsSecondsSeconds() {
		return lastPtsSeconds;
	}

	/** Applies projector focus blur to the shared video texture for this draw. */
	public void setFocusBlur(float lod) {
		texture.setBlurBias(lod);
	}

	public boolean isPaused() {
		return paused;
	}

	/** True while an in-place seek is waiting for decoders/audio to catch up. */
	public boolean isSeeking() {
		return seekStarting || seekResetPending;
	}

	/** True when this player has no video output (pure audio media). */
	public boolean isAudioOnly() {
		return audioOnly;
	}

	/** True once audio has started or the first video frame has been presented. */
	public boolean isStarted() {
		return audio.isStarted() || playbackStartNanos >= 0;
	}

	public Throwable error() {
		return error.get();
	}

	private void start() {
		audio.start();
		if (!audioOnly) {
			videoThread.start();
		}
		audioThread.start();
		if (audioOnly) {
			// There is no first video frame to trigger the audio clock.
			audio.startPlayback();
		}
		Cinema.LOGGER.info("cinema player: started {}{}", file.getFileName(),
			audioOnly ? " (audio only)" : "");
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
		Cinema.LOGGER.info("cinema player: {}", paused ? "paused" : "resumed");
	}

	public void togglePause() {
		setPaused(!paused);
	}

	/** Current media position in seconds, from the audio clock or the wall clock. */
	public double positionSeconds() {
		if (audioUnavailable) {
			return wallClockTarget();
		}
		if (seekStarting) {
			if (audio.isStarted()
				&& Math.abs(audio.audiblePtsSeconds() - playbackFloorSeconds) <= 2.0) {
				seekStarting = false;
			} else {
				return playbackFloorSeconds;
			}
		}
		if (!audio.isStarted()) {
			return playbackFloorSeconds;
		}
		return Math.max(playbackFloorSeconds, audio.audiblePtsSeconds());
	}

	/**
	 * Flushes queued media and restarts the active decoder(s) at {@code seconds}
	 * without rebuilding the player, texture or OpenAL context.
	 */
	public boolean seekTo(double seconds) {
		boolean audioActive = audioThread.isAlive() && !audioUnavailable;
		boolean videoActive = !audioOnly && videoThread.isAlive();
		if (!running.get() || (!audioActive && !audioUnavailable) || (!audioOnly && !videoActive)) {
			return false;
		}
		double target = Math.max(0.0, seconds);
		StreamingAudioDecoder audioRef;
		VideoDecoder videoRef;
		long generation;
		synchronized (seekLock) {
			seekTarget = audioActive ? target : Double.NaN;
			videoSeekTarget = videoActive ? target : Double.NaN;
			playbackFloorSeconds = target;
			seekStarting = true;
			seekResetPending = true;
			generation = ++seekGeneration;
			audioRef = audioActive ? audioDecoder : null;
			videoRef = videoActive ? videoDecoder : null;
		}

		clearVideoQueue();
		firstVideoFrame = false;
		firstFramePresented = false;
		if (audioUnavailable) {
			playbackStartNanos = System.nanoTime();
			pausedAtNanos = 0L;
			pausedTotalNanos = 0L;
		}

		boolean wasPaused = paused;
		Thread worker = new Thread(() -> performSeek(
			target, generation, audioRef, videoRef, audioActive, wasPaused), "cinema-seek");
		worker.setDaemon(true);
		seekWorker = worker;
		worker.start();
		return true;
	}

	private void performSeek(double target, long generation,
							 StreamingAudioDecoder audioRef, VideoDecoder videoRef,
							 boolean audioActive, boolean wasPaused) {
		try {
			if (audioRef != null) {
				audioRef.close();
				// Unblock a decoder that is waiting to push into the engine queue.
				audio.clearInput();
			}
			if (videoRef != null) {
				videoRef.close();
			}
			long deadline = System.nanoTime() + 500_000_000L;
			while ((audioRef != null && audioDecoder != null
				|| videoRef != null && videoDecoder != null)
				&& System.nanoTime() < deadline) {
				try {
					Thread.sleep(5L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			drainQueuedVideoFrames();
			synchronized (seekLock) {
				if (!running.get() || generation != seekGeneration) {
					return;
				}
			}
			if (audioActive) {
				audio.clearInput();
				audio.flush(target);
				if (!running.get()) {
					return;
				}
				audio.startPlayback();
				if (wasPaused) {
					audio.setPaused(true);
				}
			}
		} finally {
			synchronized (seekLock) {
				if (generation == seekGeneration) {
					seekStarting = false;
					seekResetPending = false;
					seekLock.notifyAll();
				}
			}
		}
	}

	private void drainQueuedVideoFrames() {
		VideoFrame frame;
		while ((frame = frames.poll()) != null) {
			frame.release();
		}
	}

	private void clearVideoQueue() {
		if (pendingFrame != null) {
			pendingFrame.release();
			pendingFrame = null;
		}
		VideoFrame queued;
		while ((queued = frames.poll()) != null) {
			queued.release();
		}
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
			Cinema.LOGGER.error("cinema player decode failed", failure);
			running.set(false);
			return;
		}
		if (audioOnly) {
			logStatsIfNeeded();
			return;
		}
		releaseCompletedUploads();

		VideoFrame frame = pendingFrame != null ? pendingFrame : frames.poll();
		if (frame == null) {
			return;
		}
		if (!firstVideoFrame) {
			firstVideoFrame = true;
			firstFrameWaitNanos = System.nanoTime();
			playbackStartNanos = firstFrameWaitNanos;
			audio.startPlayback();
		}
		long now = System.nanoTime();
		boolean firstFrameTimedOut = !firstFramePresented
			&& now - firstFrameWaitNanos > 500_000_000L;
		if (!audio.isStarted() && !audioUnavailable && !firstFrameTimedOut) {
			pendingFrame = frame;
			return;
		}
		if (!firstFramePresented && !audioUnavailable && !firstFrameTimedOut) {
			long due = audio.scheduledStartSystemNanos();
			if (due <= 0L || now < due) {
				pendingFrame = frame;
				return;
			}
		}

		double target = !firstFramePresented ? frame.ptsSeconds()
			: (audioUnavailable ? wallClockTarget() : audio.audiblePtsSeconds());
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
			long fence = texture.upload(frame.rgba(), frame.width(), frame.height());
			pendingUploads.addLast(new PendingUpload(frame, fence));
			width = frame.width();
			height = frame.height();
			lastPtsSeconds = frame.ptsSeconds();
			presented++;
			firstFramePresented = true;
			pendingFrame = null;
			releaseCompletedUploads();
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
		return playbackFloorSeconds + Math.max(0L, elapsed) / 1e9;
	}

	private void decodeVideo() {
		CinemaPrewarm.await();
		Ffmpeg.VideoInfo info;
		try {
			info = videoInfo;
			if (info == null) {
				info = Ffmpeg.probeVideoOrNull(file);
				videoInfo = info;
			}
		} catch (Throwable t) {
			error.compareAndSet(null, t);
			return;
		}
		if (info == null) {
			audioOnly = true;
			audio.startPlayback();
			Cinema.LOGGER.info("cinema player: no video stream; playing audio only");
			return;
		}

		while (running.get()) {
			double start;
			long generation;
			synchronized (seekLock) {
				while (seekResetPending && running.get()) {
					try {
						seekLock.wait(50L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (!running.get()) {
					return;
				}
				start = Double.isNaN(videoSeekTarget) ? startSeconds : videoSeekTarget;
				videoSeekTarget = Double.NaN;
				generation = seekGeneration;
			}

			boolean seeked = false;
			try (VideoDecoder decoder = new VideoDecoder(file, start, info)) {
				synchronized (seekLock) {
					if (!running.get() || generation != seekGeneration) {
						continue;
					}
					videoDecoder = decoder;
				}
				try {
					while (running.get()) {
						VideoFrame frame = decoder.next();
						if (frame == null) {
							break;
						}
						synchronized (seekLock) {
							if (generation != seekGeneration || seekResetPending) {
								frame.release();
								seeked = true;
								break;
							}
						}
						if (!running.get()) {
							frame.release();
							break;
						}
						if (!frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
							while (running.get() && !frames.offer(frame, 100, TimeUnit.MILLISECONDS)) {
								synchronized (seekLock) {
									if (generation != seekGeneration || seekResetPending) {
										seeked = true;
										break;
									}
								}
								if (seeked) {
									break;
								}
							}
							if (!running.get() || seeked) {
								frame.release();
								break;
							}
						}
					}
				} finally {
					synchronized (seekLock) {
						if (videoDecoder == decoder) {
							videoDecoder = null;
						}
					}
				}
			} catch (Throwable t) {
				synchronized (seekLock) {
					seeked = running.get() && (seekResetPending
						|| !Double.isNaN(videoSeekTarget) || generation != seekGeneration);
				}
				if (!running.get() || closed.get() || seeked) {
					Cinema.LOGGER.info("cinema video decode cancelled");
					continue;
				}
				error.compareAndSet(null, t);
				return;
			}

			synchronized (seekLock) {
				seeked = seekResetPending || !Double.isNaN(videoSeekTarget)
					|| generation != seekGeneration;
			}
			if (!seeked) {
				break;
			}
		}
	}

	private void decodeAudio() {
		CinemaPrewarm.await();
		while (running.get()) {
			double start;
			long generation;
			synchronized (seekLock) {
				while (seekResetPending && running.get()) {
					try {
						seekLock.wait(50L);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (!running.get()) {
					return;
				}
				start = Double.isNaN(seekTarget) ? startSeconds : seekTarget;
				seekTarget = Double.NaN;
				generation = seekGeneration;
			}

			boolean hasAudio = false;
			boolean seeked;
			try (StreamingAudioDecoder decoder = new StreamingAudioDecoder(start, () -> !running.get())) {
				synchronized (seekLock) {
					if (!running.get() || generation != seekGeneration) {
						continue;
					}
					audioDecoder = decoder;
				}
				try {
					hasAudio = decoder.decode(file, audio::submit);
				} finally {
					synchronized (seekLock) {
						if (audioDecoder == decoder) {
							audioDecoder = null;
						}
					}
				}
			} catch (Throwable t) {
				if (t instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				synchronized (seekLock) {
					seeked = running.get() && (seekResetPending || !Double.isNaN(seekTarget)
						|| generation != seekGeneration);
				}
				if (!running.get() || closed.get() || seeked) {
					// close()/seekTo() closed the decoder while it waited for FFmpeg.
					Cinema.LOGGER.info("cinema audio decode cancelled");
					continue;
				}
				if (audioOnly) {
					error.compareAndSet(null, t);
					Cinema.LOGGER.warn("cinema audio decode failed for audio-only media", t);
				} else {
					audioUnavailable = true;
					Cinema.LOGGER.warn("cinema audio decode failed; continuing video-only", t);
				}
				return;
			}

			synchronized (seekLock) {
				seeked = seekResetPending || !Double.isNaN(seekTarget)
					|| generation != seekGeneration;
			}
			if (seeked) {
				continue;
			}
			if (!hasAudio) {
				if (audioOnly) {
					error.compareAndSet(null, new IOException("No decodable audio stream"));
					Cinema.LOGGER.warn("cinema player: audio-only media has no decodable audio");
				} else {
					audioUnavailable = true;
					Cinema.LOGGER.info("cinema player: no audio stream; using wall clock for video");
				}
			}
			return;
		}
	}

	/**
	 * Hands uploaded buffers back to the pool only after the GPU fence signals,
	 * or after a forced finish under queue pressure. The driver may still be
	 * reading client memory after glTexSubImage2D returns.
	 */
	private void releaseCompletedUploads() {
		while (!pendingUploads.isEmpty()) {
			PendingUpload upload = pendingUploads.peekFirst();
			if (!texture.isUploadComplete(upload.fence())) {
				break;
			}
			pendingUploads.removeFirst();
			upload.frame().release();
		}
		if (pendingUploads.size() > MAX_PENDING_UPLOADS) {
			texture.finishUploads();
			while (!pendingUploads.isEmpty()) {
				PendingUpload upload = pendingUploads.removeFirst();
				texture.isUploadComplete(upload.fence());
				upload.frame().release();
			}
		}
	}

	private void logStatsIfNeeded() {
		double now = System.nanoTime() / 1e9;
		if (lastStatsAt == 0) {
			lastStatsAt = now;
		}
		if (now - lastStatsAt >= 5.0) {
			lastStatsAt = now;
			if (audioOnly) {
				Cinema.LOGGER.info("cinema player: audio only, audioPts={}, latency={} ms",
					String.format("%.3f", audio.audiblePtsSeconds()),
					String.format("%.1f", audio.latencySeconds() * 1000));
			} else {
				Cinema.LOGGER.info("cinema player: {}x{}, presented={}, dropped={}, audioPts={}, latency={} ms",
					width, height, presented, dropped,
					String.format("%.3f", audio.audiblePtsSeconds()),
					String.format("%.1f", audio.latencySeconds() * 1000));
			}
		}
	}

	private record PendingUpload(VideoFrame frame, long fence) {
	}

	@Override
	public void close() {
		closeInternal(false, 0.0);
	}

	/**
	 * Stops this player but keeps the shared audio engine's OpenAL source pool
	 * allocated. Full-restart seeks use this so only the decoders are reopened,
	 * not the audio backend.
	 */
	public void closeForRestart(double nextStartSeconds) {
		closeInternal(true, Math.max(0.0, nextStartSeconds));
	}

	private void closeInternal(boolean reuseAudioSources, double nextStartSeconds) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		running.set(false);
		Thread worker = seekWorker;
		if (worker != null) {
			worker.interrupt();
			try {
				worker.join(1_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		// Stop both decoders and wait for their threads BEFORE resetting the
		// engine. Otherwise the old decoder can submit a stale chunk after the
		// reset and it will be heard as residue from the previous media.
		VideoDecoder decoder = videoDecoder;
		if (decoder != null) {
			decoder.close();
		}
		StreamingAudioDecoder audioDecoderRef = audioDecoder;
		if (audioDecoderRef != null) {
			audioDecoderRef.close();
		}
		audio.clearInput();
		drainQueuedVideoFrames();
		videoThread.interrupt();
		audioThread.interrupt();
		try {
			videoThread.join(1_000);
			audioThread.join(1_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		audio.clearInput();
		if (reuseAudioSources) {
			// Keep the OpenAL sources/buffers; stop/unqueue them and move the
			// clock to the next start. performFlush() rewinds each source and
			// falls back to a source-pool rebuild if OpenAL reports a problem.
			audio.flush(nextStartSeconds);
		} else {
			audio.reset();
		}
		if (!pendingUploads.isEmpty()) {
			texture.finishUploads();
			while (!pendingUploads.isEmpty()) {
				PendingUpload upload = pendingUploads.removeFirst();
				texture.isUploadComplete(upload.fence());
				upload.frame().release();
			}
		}
		if (pendingFrame != null) {
			pendingFrame.release();
			pendingFrame = null;
		}
		VideoFrame queued;
		while ((queued = frames.poll()) != null) {
			queued.release();
		}
		if (ownsTexture) {
			client.getTextureManager().release(textureId);
		}
		Cinema.LOGGER.info("cinema player: stopped (presented={}, dropped={})", presented, dropped);
	}
}
