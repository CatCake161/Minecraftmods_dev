package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;

/**
 * Conservative room/order budget for geometric reflection delays.
 *
 * <p>Every segment of an image-source path (source, reflection points,
 * listener) lies inside the detected room box, so a path of order {@code N}
 * has at most {@code N + 1} segments and cannot be longer than
 * {@code (N + 1) * roomDiagonal}. This gives a source-position independent
 * upper bound:
 *
 * <pre>{@code
 * maxDelay(order) = min(hardCap, (order + 1) * diagonal / c, historySafe)
 * }</pre>
 *
 * <p>The bound is intentionally conservative. It is only a permission filter;
 * actual path geometry, the late-bus tap cap and the path/visit caps still
 * decide what reaches the audio engine.
 */
public record ReflectionDelayBudget(double roomDiagonalMeters,
									double hardCapSeconds,
									double historySafeSeconds)
	implements ReflectionDelayLimit {
	public static final double SPEED_OF_SOUND = 343.0;

	public ReflectionDelayBudget {
		roomDiagonalMeters = finiteNonNegative(roomDiagonalMeters);
		hardCapSeconds = finiteNonNegative(hardCapSeconds);
		historySafeSeconds = finiteNonNegative(historySafeSeconds);
	}

	/**
	 * Builds a room-aware limit, or a constant hard/history limit when the
	 * room box is unavailable. The fallback keeps the solver usable before the
	 * first room detection instead of rejecting every reflection.
	 */
	public static ReflectionDelayLimit forRoom(AcousticRoom room,
											   double hardCapSeconds,
											   double historySafeSeconds) {
		double hardCap = finiteNonNegative(hardCapSeconds);
		double history = finiteNonNegative(historySafeSeconds);
		if (room == null || !(room.width() > 0.0)
			|| !(room.height() > 0.0) || !(room.depth() > 0.0)) {
			return ReflectionDelayLimit.constant(Math.min(hardCap, history));
		}
		return new ReflectionDelayBudget(room.diagonalMeters(), hardCap, history);
	}

	/** Unclamped room-size bound for one order, in seconds. */
	public double roomBoundSeconds(int order) {
		int reflections = Math.max(1, order);
		return (reflections + 1.0) * roomDiagonalMeters / SPEED_OF_SOUND;
	}

	@Override
	public double maxDelaySeconds(int order) {
		return Math.max(0.0, Math.min(hardCapSeconds,
			Math.min(roomBoundSeconds(order), historySafeSeconds)));
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
	}
}
