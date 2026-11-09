package com.example.cinema.client.audio.acoustics;

/**
 * Per-order maximum geometric delay accepted by the reflection solvers.
 *
 * <p>The limit is deliberately a function of the path order: a room-size
 * bound for a second-order path is much smaller than for a fourth-order path.
 * Callers that do not need a room-aware budget can use
 * {@link #constant(double)}.
 */
@FunctionalInterface
public interface ReflectionDelayLimit {
	/**
	 * Returns the maximum accepted delay for one path order, in seconds.
	 * Implementations must return a finite, non-negative value.
	 */
	double maxDelaySeconds(int order);

	static ReflectionDelayLimit constant(double seconds) {
		double limit = Double.isFinite(seconds) ? Math.max(0.0, seconds) : 0.0;
		return order -> limit;
	}
}
