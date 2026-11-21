package com.example.cinema.client.media.audio.reflection;

import java.util.HashMap;
import java.util.Map;

/**
 * Feed-forward ray budget for the directional scattered field.
 *
 * <p>The physical specular/scattered energy split is already controlled by the
 * material scattering coefficient. This class only decides how much of the
 * scattered energy should be rendered as directional rays instead of the
 * channel aggregate. Low scattering keeps most energy in the aggregate; high
 * scattering allows more directional rays. The value is smoothed and never
 * changes total energy.
 */
public final class ScatterRayBudget {
	private static final double LOW_SCATTER = 0.05;
	private static final double HIGH_SCATTER = 0.45;
	private static final double MIN_DIRECTIONAL_SHARE = 0.20;
	private static final double MAX_DIRECTIONAL_SHARE = 0.50;
	private static final double SMOOTHING_SECONDS = 0.25;
	private final Map<String, State> states = new HashMap<>();

	public record Budget(int rayPaths, int maxRayPaths,
						 double directionalShare,
						 double smoothedScatterFraction) {
	}

	private static final class State {
		private double smoothed;
		private long lastMillis;
		private boolean initialized;
	}

	/**
	 * @param specularEnergy summed specular energy in the same domain
	 * @param scatterEnergy  summed scattered energy in the same domain
	 * @param maxRayPaths    maximum directional paths for this channel/order
	 */
	public Budget update(String key, double specularEnergy, double scatterEnergy,
						 int maxRayPaths, long nowMillis) {
		double total = Math.max(0.0, specularEnergy)
			+ Math.max(0.0, scatterEnergy);
		double target = total > 1.0e-12
			? Math.max(0.0, scatterEnergy) / total : 0.0;
		State state = states.computeIfAbsent(key, ignored -> new State());
		if (!state.initialized) {
			state.smoothed = target;
			state.initialized = true;
		} else {
			long dt = Math.max(0L, nowMillis - state.lastMillis);
			double alpha = 1.0 - Math.exp(-(dt / 1_000.0)
				/ SMOOTHING_SECONDS);
			state.smoothed += (target - state.smoothed) * alpha;
		}
		state.lastMillis = nowMillis;
		double normalized = clamp01((state.smoothed - LOW_SCATTER)
			/ (HIGH_SCATTER - LOW_SCATTER));
		int hardMax = Math.max(1, maxRayPaths);
		// The cap itself is dynamic: low scattering only allows one path,
		// while high scattering opens up the full hard cap.
		int dynamicMax = Math.max(1, Math.min(hardMax,
			1 + roundCount(normalized * (hardMax - 1))));
		int count = Math.max(0, Math.min(dynamicMax,
			roundCount(normalized * dynamicMax)));
		double share = count == 0 ? 0.0
			: MIN_DIRECTIONAL_SHARE
				+ (MAX_DIRECTIONAL_SHARE - MIN_DIRECTIONAL_SHARE)
					* normalized;
		return new Budget(count, dynamicMax, share, state.smoothed);
	}

	public void clear() {
		states.clear();
	}

	private static double clamp01(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static int roundCount(double value) {
		// Avoid a floating-point boundary such as 0.35 -> .749999... making
		// a quantized ray count fall one step low.
		return (int) Math.floor(value + 0.5 + 1.0e-9);
	}
}
