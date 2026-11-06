package com.example.cinema.client.media;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.audio.speakerdata.SpeakerDataAssignments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Client-side playback proxy for one AV receiver.
 *
 * <p>The receiver block entity is authoritative for intent, position, volume
 * and mute. This proxy only reacts to the synced state, opens a local decoder
 * for the receiver's media key, and keeps the local player near the expected
 * position.
 */
public final class AvReceiverPlayback {
	/** Time after a seek during which another seek is merged or rejected. */
	public static final long SEEK_COOLDOWN_MILLIS = 3_000L;
	private static final double MAX_DRIFT_SECONDS = 0.75;
	private static final long FAILURE_RETRY_MILLIS = 5_000L;
	private static final long MISSING_LOG_INTERVAL_MILLIS = 5_000L;

	private final BlockPos receiverPos;
	private final ResourceLocation textureId;
	private final CinemaVideoTexture texture;
	private CinemaAudioEngine audio;
	private boolean textureRegistered;
	private CinemaPlayer player;
	private String mediaKey = "";
	private long mediaSize = -1L;
	private String mediaSha256 = "";
	private String failedKey = "";
	private long failedAtMillis;
	private long openedAtMillis;
	private long lastMissingLogMillis;
	private double clockOffset;
	private boolean clockAligned;
	private double lastExpectedSeconds = Double.NaN;
	private long seekGraceUntilMillis;
	private long seekCooldownUntilMillis;
	private double pendingSeekSeconds = Double.NaN;
	private boolean missing;
	private int physicalChannels;

	AvReceiverPlayback(BlockPos receiverPos) {
		this.receiverPos = receiverPos;
		this.textureId = AvReceiverPlaybackManager.nextTextureId();
		this.texture = new CinemaVideoTexture();
		Minecraft.getInstance().getTextureManager().register(textureId, texture);
		this.textureRegistered = true;
	}

	public BlockPos receiverPos() {
		return receiverPos;
	}

	public CinemaPlayer player() {
		return player;
	}

	/** Number of 5.1 channels currently routed to physical linked speakers. */
	public int physicalChannels() {
		return physicalChannels;
	}

	/** Remaining seek cooldown in milliseconds, or {@code 0} when seek is available. */
	public long seekCooldownRemainingMillis() {
		return Math.max(0L, seekCooldownUntilMillis - System.currentTimeMillis());
	}

	/**
	 * Reconciles the local player with the receiver state. Returns {@code false}
	 * when the receiver is stopped and this proxy should be removed.
	 */
	boolean sync(ClientLevel level, AvReceiverBlockEntity receiver) {
		if (receiver.state() == AvReceiverBlockEntity.State.STOPPED || receiver.mediaKey().isEmpty()) {
			close();
			return false;
		}
		boolean mediaChanged = !receiver.mediaKey().equals(mediaKey)
			|| receiver.mediaSize() != mediaSize
			|| !receiver.mediaSha256().equals(mediaSha256);
		if (mediaChanged) {
			closePlayerForRestart(expectedPosition(level, receiver));
			mediaKey = receiver.mediaKey();
			mediaSize = receiver.mediaSize();
			mediaSha256 = receiver.mediaSha256();
			failedKey = "";
			lastMissingLogMillis = 0L;
			missing = false;
			clockAligned = false;
			clockOffset = 0.0;
			lastExpectedSeconds = Double.NaN;
		}
		if (player == null && !open(level, receiver)) {
			return true;
		}
		updateChannelLayout(level, receiver);
		if (player.error() != null) {
			Cinema.LOGGER.warn("cinema receiver {}: playback failed for {}: {}",
				receiverPos.toShortString(), mediaKey, player.error().toString());
			closePlayer();
			failedKey = mediaKey;
			failedAtMillis = System.currentTimeMillis();
			return true;
		}
		if (Minecraft.getInstance().isPaused()) {
			// The integrated server (and therefore the receiver clock) is frozen
			// while the pause menu is open. Pause the local decoder too, otherwise
			// the drift check restarts the player in a loop.
			player.setPaused(true);
			return true;
		}
		if (receiver.state() == AvReceiverBlockEntity.State.PAUSED) {
			player.setPaused(true);
		} else {
			player.setPaused(false);
			// Always advance the expected-position tracker, even while a seek
			// is settling. Otherwise the stale value looks like a fresh server
			// seek when the grace period ends and creates a restart loop.
			double expected = expectedPosition(level, receiver);
			boolean jumped = !Double.isNaN(lastExpectedSeconds)
				&& Math.abs(expected - lastExpectedSeconds) > 0.5;
			lastExpectedSeconds = expected;
			long nowMillis = System.currentTimeMillis();
			if (!Double.isNaN(pendingSeekSeconds) && nowMillis >= seekCooldownUntilMillis
				&& !player.isSeeking()) {
				double target = pendingSeekSeconds;
				pendingSeekSeconds = Double.NaN;
				restartAt(level, receiver, target);
				if (player != null) {
					player.setVolume(receiver.volume(), receiver.isMuted());
				}
				return true;
			}
			if (jumped) {
				if (nowMillis < seekCooldownUntilMillis || player.isSeeking()) {
					// Coalesce a burst of seek clicks into one restart, or wait
					// for an in-flight seek worker to finish.
					pendingSeekSeconds = expected;
					return true;
				}
				// A new server seek can arrive while the previous restart is
				// still inside its settle window. Apply it now instead of
				// swallowing it and leaving the old audio playing until the
				// grace period expires.
				restartAt(level, receiver, expected);
				if (player != null) {
					player.setVolume(receiver.volume(), receiver.isMuted());
				}
				return true;
			}
			if (player.isSeeking() || nowMillis < seekGraceUntilMillis) {
				// The seek worker is still draining the old decoders/audio, or
				// the newly started decoder has not reached the target clock
				// yet. Do not run drift correction against an intermediate clock.
				player.setVolume(receiver.volume(), receiver.isMuted());
				return true;
			}
			boolean starting = player.presentedFrames() == 0
				&& System.currentTimeMillis() - openedAtMillis < 10_000L;
			if (!starting) {
				if (!clockAligned) {
					double offset = player.positionSeconds() - expected;
					if (Math.abs(offset) > 30.0) {
						restartAt(level, receiver, expected);
						if (player == null) {
							return true;
						}
					} else {
						clockOffset = offset;
						clockAligned = true;
					}
				}
				if (clockAligned
					&& Math.abs(player.positionSeconds() - (expected + clockOffset)) > MAX_DRIFT_SECONDS) {
					restartAt(level, receiver, expected);
					if (player == null) {
						return true;
					}
				}
			}
		}
		player.setVolume(receiver.volume(), receiver.isMuted());
		return true;
	}

	public void presentFrame() {
		if (player != null) {
			player.presentFrame();
		}
	}

	public void close() {
		closePlayer();
		if (audio != null) {
			audio.close();
			audio = null;
		}
		if (textureRegistered) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			textureRegistered = false;
		}
		mediaKey = "";
		mediaSize = -1L;
		mediaSha256 = "";
		missing = false;
		clockAligned = false;
		clockOffset = 0.0;
		seekGraceUntilMillis = 0L;
		seekCooldownUntilMillis = 0L;
		pendingSeekSeconds = Double.NaN;
		lastExpectedSeconds = Double.NaN;
		physicalChannels = 0;
	}

	private void closePlayer() {
		if (player != null) {
			player.close();
			player = null;
		}
	}

	private void closePlayerForRestart(double nextStartSeconds) {
		if (player != null) {
			player.closeForRestart(nextStartSeconds);
			player = null;
		}
	}

	private boolean open(ClientLevel level, AvReceiverBlockEntity receiver) {
		if (mediaKey.equals(failedKey) && System.currentTimeMillis() - failedAtMillis < FAILURE_RETRY_MILLIS) {
			return false;
		}
		Path file;
		try {
			file = MediaLibrary.resolve(mediaKey);
		} catch (IOException e) {
			reportMissing(e.getMessage());
			return false;
		}
		if (!Files.isRegularFile(file)) {
			reportMissing("missing: " + mediaKey);
			return false;
		}
		double expected = expectedPosition(level, receiver);
		if (!openAt(file, receiver, expected)) {
			return false;
		}
		missing = false;
		Cinema.LOGGER.info("cinema receiver {}: opened {} at {} s",
			receiverPos.toShortString(), mediaKey, String.format("%.2f", expected));
		return true;
	}

	private void restartAt(ClientLevel level, AvReceiverBlockEntity receiver, double expected) {
		long seekStartedNanos = System.nanoTime();
		long now = System.currentTimeMillis();
		seekGraceUntilMillis = now + SEEK_COOLDOWN_MILLIS + 1_000L;
		seekCooldownUntilMillis = now + SEEK_COOLDOWN_MILLIS;
		pendingSeekSeconds = Double.NaN;
		Path file;
		try {
			file = MediaLibrary.resolve(mediaKey);
		} catch (IOException e) {
			reportMissing(e.getMessage());
			closePlayer();
			return;
		}
		// In-place seek is disabled until its decoder/clock state machine is
		// proven stable. Keep the full player restart, but retain the audio
		// engine's source pool so seek does not rebuild the audio backend.
		closePlayerForRestart(expected);
		if (openAt(file, receiver, expected)) {
			Cinema.LOGGER.info("cinema receiver {}: resynced {} to {} s in {} ms",
				receiverPos.toShortString(), mediaKey, String.format("%.2f", expected),
				(System.nanoTime() - seekStartedNanos) / 1_000_000L);
		}
	}

	private boolean openAt(Path file, AvReceiverBlockEntity receiver, double expected) {
		try {
			player = CinemaPlayer.open(Minecraft.getInstance(), file, expected,
				audioEngine(), textureId, texture);
		} catch (Exception e) {
			failedKey = mediaKey;
			failedAtMillis = System.currentTimeMillis();
			Cinema.LOGGER.warn("cinema receiver {}: could not open {}: {}",
				receiverPos.toShortString(), mediaKey, e.getMessage());
			return false;
		}
		player.setVolume(receiver.volume(), receiver.isMuted());
		player.setPaused(receiver.state() == AvReceiverBlockEntity.State.PAUSED);
		openedAtMillis = System.currentTimeMillis();
		clockAligned = false;
		clockOffset = 0.0;
		return true;
	}

	/** One OpenAL context per receiver; reused across seeks, closed with the proxy. */
	private CinemaAudioEngine audioEngine() {
		if (audio == null) {
			audio = new CinemaAudioEngine();
			audio.start();
		}
		return audio;
	}

	private void reportMissing(String reason) {
		missing = true;
		failedKey = mediaKey;
		long now = System.currentTimeMillis();
		if (lastMissingLogMillis == 0L || now - lastMissingLogMillis >= MISSING_LOG_INTERVAL_MILLIS) {
			lastMissingLogMillis = now;
			Cinema.LOGGER.warn("cinema receiver {}: media unavailable: {}",
				receiverPos.toShortString(), reason);
		}
	}

	/**
	 * The listener is always the local player's actual ears and head direction.
	 * There is no Seat/sweet-spot calibration and no screen-locked correction:
	 * walking or looking away therefore keeps the physical speaker directions
	 * correct instead of re-centering the sound field on a fixed seat.
	 */
	void updateChannelLayout(ClientLevel level, AvReceiverBlockEntity receiver) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Vec3 listener;
		Vec3 forward;
		if (client.player != null) {
			listener = client.player.getEyePosition();
			Vec3 look = client.player.getLookAngle();
			forward = new Vec3(look.x, 0.0, look.z);
		} else {
			listener = Vec3.atCenterOf(receiver.getBlockPos()).add(0.0, 1.5, 0.0);
			forward = new Vec3(0.0, 0.0, -1.0);
		}
		if (forward.lengthSqr() < 1.0e-4) {
			forward = new Vec3(0.0, 0.0, -1.0);
		} else {
			forward = forward.normalize();
		}

		float[][] positions = new float[CinemaAudioEngine.CHANNELS][];
		float[] gains = new float[CinemaAudioEngine.CHANNELS];
		Arrays.fill(gains, 1.0f);
		double[] bestDistance = new double[CinemaAudioEngine.CHANNELS];
		Arrays.fill(bestDistance, Double.MAX_VALUE);
		for (BlockPos pos : receiver.speakerTargets()) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
				continue;
			}
			SpeakerRole role = speaker.role();
			int channel = role.ordinal() - 1;
			if (channel < 0 || channel >= positions.length) {
				continue;
			}
			Vec3 center = speakerCenter(speaker);
			Vec3 relative = center.subtract(listener);
			double distance = relative.lengthSqr();
			if (distance < 0.01 || distance >= bestDistance[channel]) {
				continue;
			}
			bestDistance[channel] = distance;
			positions[channel] = toListenerSpace(relative, forward);
			gains[channel] = speakerGain(speaker);
		}

		int routed = 0;
		for (float[] position : positions) {
			if (position != null) {
				routed++;
			}
		}
		physicalChannels = routed;
		engine.setChannelLayout(positions, gains);
	}

	private static Vec3 speakerCenter(SpeakerBlockEntity speaker) {
		Vec3 center = Vec3.atCenterOf(speaker.getBlockPos());
		if (speaker.type() == SpeakerType.FLOORSTANDING) {
			center = center.add(0.0, 0.5, 0.0);
		}
		return center;
	}

	private static float speakerGain(SpeakerBlockEntity speaker) {
		double db = speaker.levelDb() + SpeakerDataAssignments.settingsFor(speaker).gainDb();
		double linear = Math.pow(10.0, db / 20.0);
		double max = Math.pow(10.0, SpeakerBlockEntity.MAX_LEVEL_DB / 20.0);
		return (float) Math.max(0.0, Math.min(max, linear));
	}

	private static float[] toListenerSpace(Vec3 relative, Vec3 forward) {
		Vec3 up = new Vec3(0.0, 1.0, 0.0);
		Vec3 right = forward.cross(up);
		if (right.lengthSqr() < 1.0e-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		} else {
			right = right.normalize();
		}
		return new float[]{
			(float) relative.dot(right),
			(float) relative.y,
			(float) -relative.dot(forward),
		};
	}

	private static double expectedPosition(ClientLevel level, AvReceiverBlockEntity receiver) {
		double expected = receiver.positionSeconds();
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING) {
			long elapsed = Math.max(0L, level.getGameTime() - receiver.stateChangedGameTime());
			expected += elapsed / 20.0;
		}
		if (receiver.durationSeconds() > 0.0) {
			expected = Math.min(expected, receiver.durationSeconds());
		}
		return Math.max(0.0, expected);
	}
}
