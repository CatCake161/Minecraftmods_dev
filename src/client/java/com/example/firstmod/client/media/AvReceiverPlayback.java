package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side playback proxy for one AV receiver.
 *
 * <p>The receiver block entity is authoritative for intent, position, volume
 * and mute. This proxy only reacts to the synced state, opens a local decoder
 * for the receiver's media key, and keeps the local player near the expected
 * position.
 */
public final class AvReceiverPlayback {
	private static final double MAX_DRIFT_SECONDS = 0.75;
	private static final long FAILURE_RETRY_MILLIS = 5_000L;
	private static final long MISSING_LOG_INTERVAL_MILLIS = 5_000L;

	private final BlockPos receiverPos;
	private final ResourceLocation textureId;
	private final CinemaVideoTexture texture;
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
	private boolean missing;

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
			closePlayer();
			mediaKey = receiver.mediaKey();
			mediaSize = receiver.mediaSize();
			mediaSha256 = receiver.mediaSha256();
			failedKey = "";
			lastMissingLogMillis = 0L;
			missing = false;
			clockAligned = false;
			clockOffset = 0.0;
		}
		if (player == null && !open(level, receiver)) {
			return true;
		}
		if (player.error() != null) {
			FirstMod.LOGGER.warn("cinema receiver {}: playback failed for {}: {}",
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
			// HEVC/4K decoders can need more than a second to deliver the first
			// frame. Do not drag a still-starting player back to the live clock;
			// that used to create a one-second restart loop.
			boolean starting = player.presentedFrames() == 0
				&& System.currentTimeMillis() - openedAtMillis < 10_000L;
			if (!starting) {
				double expected = expectedPosition(level, receiver);
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
	}

	private void closePlayer() {
		if (player != null) {
			player.close();
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
		FirstMod.LOGGER.info("cinema receiver {}: opened {} at {} s",
			receiverPos.toShortString(), mediaKey, String.format("%.2f", expected));
		return true;
	}

	private void restartAt(ClientLevel level, AvReceiverBlockEntity receiver, double expected) {
		Path file;
		try {
			file = MediaLibrary.resolve(mediaKey);
		} catch (IOException e) {
			reportMissing(e.getMessage());
			closePlayer();
			return;
		}
		closePlayer();
		if (openAt(file, receiver, expected)) {
			FirstMod.LOGGER.info("cinema receiver {}: resynced {} to {} s",
				receiverPos.toShortString(), mediaKey, String.format("%.2f", expected));
		}
	}

	private boolean openAt(Path file, AvReceiverBlockEntity receiver, double expected) {
		try {
			player = CinemaPlayer.open(Minecraft.getInstance(), file, expected,
				AvReceiverPlaybackManager.audioEngine(), textureId, texture);
		} catch (Exception e) {
			failedKey = mediaKey;
			failedAtMillis = System.currentTimeMillis();
			FirstMod.LOGGER.warn("cinema receiver {}: could not open {}: {}",
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

	private void reportMissing(String reason) {
		missing = true;
		failedKey = mediaKey;
		long now = System.currentTimeMillis();
		if (lastMissingLogMillis == 0L || now - lastMissingLogMillis >= MISSING_LOG_INTERVAL_MILLIS) {
			lastMissingLogMillis = now;
			FirstMod.LOGGER.warn("cinema receiver {}: media unavailable: {}",
				receiverPos.toShortString(), reason);
		}
	}

	private static double expectedPosition(ClientLevel level, AvReceiverBlockEntity receiver) {
		double expected = receiver.positionSeconds();
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING) {
			long elapsed = Math.max(0L, level.getGameTime() - receiver.stateGameTime());
			expected += elapsed / 20.0;
		}
		if (receiver.durationSeconds() > 0.0) {
			expected = Math.min(expected, receiver.durationSeconds());
		}
		return Math.max(0.0, expected);
	}
}
