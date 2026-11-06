package com.example.cinema.client.audio.acoustics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side A1 scene cache. Recomputes the room/speaker snapshot at ~5 Hz
 * when the listener moves or looks around, and every 20 ticks even when idle.
 * It does not touch audio; A2/A3 consume the snapshot later.
 */
public final class AcousticScene {
	private static final Logger LOGGER = LoggerFactory.getLogger("cinema-acoustics");
	private static final int MIN_UPDATE_INTERVAL_TICKS = 4;
	private static final int IDLE_UPDATE_INTERVAL_TICKS = 20;
	private static final double MOVE_THRESHOLD_SQR = 0.25 * 0.25;
	private static final double LOOK_DOT_THRESHOLD = 0.996;
	private static final Vec3 DEFAULT_LOOK = new Vec3(0.0, 0.0, -1.0);

	private static volatile RoomAcousticSnapshot snapshot;
	private static long lastGameTime;
	private static Vec3 lastEye = Vec3.ZERO;
	private static Vec3 lastLook = DEFAULT_LOOK;
	private static long version;
	private static String lastError = "";

	private AcousticScene() {
	}

	public static void tick(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			invalidate();
			return;
		}
		long now = minecraft.level.getGameTime();
		Vec3 eye = minecraft.player.getEyePosition();
		Vec3 look = minecraft.player.getLookAngle().normalize();
		RoomAcousticSnapshot current = snapshot;
		int interval = current == null ? 0 : (eye.distanceToSqr(lastEye) > MOVE_THRESHOLD_SQR
			|| look.dot(lastLook) < LOOK_DOT_THRESHOLD
			? MIN_UPDATE_INTERVAL_TICKS : IDLE_UPDATE_INTERVAL_TICKS);
		if (current != null && now - lastGameTime < interval) {
			return;
		}
		version++;
		try {
			snapshot = RoomDetector.detect(minecraft.level, minecraft.player, version);
			lastError = "";
			lastGameTime = now;
			lastEye = eye;
			lastLook = look;
		} catch (Throwable t) {
			snapshot = null;
			String message = String.valueOf(t);
			if (!message.equals(lastError)) {
				lastError = message;
				LOGGER.warn("cinema acoustics: room detection failed: {}", message, t);
			}
		}
	}

	public static RoomAcousticSnapshot refresh(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			invalidate();
			return null;
		}
		version++;
		try {
			snapshot = RoomDetector.detect(minecraft.level, minecraft.player, version);
			lastError = "";
			lastGameTime = minecraft.level.getGameTime();
			lastEye = minecraft.player.getEyePosition();
			lastLook = minecraft.player.getLookAngle().normalize();
			return snapshot;
		} catch (Throwable t) {
			snapshot = null;
			String message = String.valueOf(t);
			if (!message.equals(lastError)) {
				lastError = message;
				LOGGER.warn("cinema acoustics: room detection failed: {}", message, t);
			}
			return null;
		}
	}

	public static RoomAcousticSnapshot snapshot() {
		return snapshot;
	}

	public static void invalidate() {
		snapshot = null;
		lastGameTime = 0L;
		lastEye = Vec3.ZERO;
		lastLook = DEFAULT_LOOK;
		lastError = "";
	}
}
