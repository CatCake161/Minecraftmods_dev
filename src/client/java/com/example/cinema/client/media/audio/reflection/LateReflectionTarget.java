package com.example.cinema.client.media.audio.reflection;

import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;

import java.util.List;

/**
 * Computes the room-constant gain target for the geometric late-reflection bus.
 *
 * <p>The late field is treated as diffuse. Let the source pressure at 1 m be
 * {@code a_k}, the directivity amplitude be {@code g_k(Ω)}, and the power
 * integral be {@code I_k = ∫g_k²dΩ}. The source power proxy is
 * {@code P_k = a_k²I_k}; the direct energy at the listener is
 * {@code D_k = (a_k·g_k(Ω_L)/r_k)²}. For a room constant
 * {@code R = S·α/(1-α)}, the expected reverberant/direct energy ratio is
 *
 * <pre>
 *   I_r / I_d = (4 / R) · ΣP_k / ΣD_k
 * </pre>
 *
 * <p>The bus taps already carry their physical path gain, so this class is
 * used for the wet-source calibration and for diagnostics:
 *
 * <ul>
 *   <li>{@link Result#targetWetGainDb()} - expected wet/direct ratio, useful
 *       for logs and A/B listening;</li>
 *   <li>{@link Result#sourceGainDb()} - {@code 20·log10(2/√R)}, the gain that
 *       keeps the late bus at the same calibration as before the FDN removal.</li>
 * </ul>
 */
public final class LateReflectionTarget {
	public static final double FALLBACK_TARGET_DB = -12.0;
	public static final double FALLBACK_SOURCE_GAIN_DB = -12.0;
	public static final double MIN_TARGET_DB = -48.0;
	public static final double MAX_TARGET_DB = 18.0;
	public static final double MIN_SOURCE_GAIN_DB = -48.0;
	public static final double MAX_SOURCE_GAIN_DB = 12.0;
	private static final double MIN_ALPHA = 0.01;
	private static final double MAX_ALPHA = 0.99;
	private static final double MIN_DENOMINATOR = 1.0e-9;

	/**
	 * One source as seen by the calibration model.
	 *
	 * @param directGainAtListener listener-direction gain at 1 m, including
	 *                             speaker trim and broadband occlusion
	 * @param sourcePowerGain      source-power amplitude at 1 m, including the
	 *                             measured directivity power integral and
	 *                             broadband occlusion
	 * @param distanceMeters       source-listener distance, clamped to 1 m
	 */
	public record Source(double directGainAtListener, double sourcePowerGain,
						 double distanceMeters) {
		public Source {
			directGainAtListener = finiteNonNegative(directGainAtListener);
			sourcePowerGain = finiteNonNegative(sourcePowerGain);
			distanceMeters = Double.isFinite(distanceMeters)
				? Math.max(1.0, distanceMeters) : 1.0;
		}
	}

	public record Result(double roomConstant, double criticalDistanceMeters,
						 double targetWetGainDb, double sourceGainDb) {
		public boolean hasRoomConstant() {
			return Double.isFinite(roomConstant) && roomConstant > 0.0;
		}

		public boolean hasCriticalDistance() {
			return Double.isFinite(criticalDistanceMeters)
				&& criticalDistanceMeters > 0.0;
		}
	}

	private LateReflectionTarget() {
	}

	public static Result compute(AcousticRoom room, List<Source> sources) {
		double roomConstant = roomConstant(room);
		if (!Double.isFinite(roomConstant) || roomConstant <= 0.0) {
			return new Result(roomConstant, Double.NaN, FALLBACK_TARGET_DB,
				FALLBACK_SOURCE_GAIN_DB);
		}
		return computeForRoomConstant(roomConstant, sources);
	}

	/**
	 * Useful for tests and for callers that already have a room constant.
	 */
	static Result computeForRoomConstant(double roomConstant,
										 List<Source> sources) {
		double criticalDistance = criticalDistance(roomConstant);
		double sourceGainDb = sourceGainDb(roomConstant);
		if (!Double.isFinite(roomConstant) || roomConstant <= 0.0
			|| sources == null || sources.isEmpty()) {
			return new Result(roomConstant, criticalDistance, FALLBACK_TARGET_DB,
				FALLBACK_SOURCE_GAIN_DB);
		}
		double powerSum = 0.0;
		double directSum = 0.0;
		for (Source source : sources) {
			if (source == null) {
				continue;
			}
			double power = source.sourcePowerGain() * source.sourcePowerGain();
			double direct = source.directGainAtListener()
				* source.directGainAtListener()
				/ (source.distanceMeters() * source.distanceMeters());
			if (Double.isFinite(power) && power > 0.0) {
				powerSum += power;
			}
			if (Double.isFinite(direct) && direct > 0.0) {
				directSum += direct;
			}
		}
		if (!(powerSum > 0.0) || !(directSum > MIN_DENOMINATOR)) {
			return new Result(roomConstant, criticalDistance, FALLBACK_TARGET_DB,
				FALLBACK_SOURCE_GAIN_DB);
		}
		double ratio = 4.0 * powerSum / (roomConstant * directSum);
		double target = Double.isFinite(ratio) && ratio > 0.0
			? 10.0 * Math.log10(ratio) : FALLBACK_TARGET_DB;
		if (!Double.isFinite(target)) {
			target = FALLBACK_TARGET_DB;
		}
		return new Result(roomConstant, criticalDistance,
			Math.max(MIN_TARGET_DB, Math.min(MAX_TARGET_DB, target)),
			sourceGainDb);
	}

	/**
	 * Gain from the source-power late bus to the physical diffuse field:
	 * {@code 20·log10(2/√R)}. This is retained so removing the FDN does not
	 * change the geometric late-bus calibration.
	 */
	public static double sourceGainDb(double roomConstant) {
		if (!Double.isFinite(roomConstant) || roomConstant <= 0.0) {
			return FALLBACK_SOURCE_GAIN_DB;
		}
		double gain = 20.0 * Math.log10(2.0 / Math.sqrt(roomConstant));
		return Double.isFinite(gain)
			? Math.max(MIN_SOURCE_GAIN_DB, Math.min(MAX_SOURCE_GAIN_DB, gain))
			: FALLBACK_SOURCE_GAIN_DB;
	}

	/**
	 * Room constant {@code R = S·α/(1-α)}. Uses the effective flood-fill area
	 * when available, otherwise the box surface. Returns NaN when the room data
	 * cannot support a meaningful value.
	 */
	public static double roomConstant(AcousticRoom room) {
		if (room == null) {
			return Double.NaN;
		}
		double area = room.effectiveSurfaceAreaMeters2();
		if (!(area > 0.0)) {
			area = room.surfaceAreaMeters2();
		}
		double alpha = room.alphaMid();
		if (!(area > 0.0) || !Double.isFinite(alpha) || alpha <= 0.0) {
			return Double.NaN;
		}
		alpha = Math.max(MIN_ALPHA, Math.min(MAX_ALPHA, alpha));
		double constant = area * alpha / (1.0 - alpha);
		return Double.isFinite(constant) && constant > 0.0
			? constant : Double.NaN;
	}

	public static double criticalDistance(double roomConstant) {
		if (!Double.isFinite(roomConstant) || roomConstant <= 0.0) {
			return Double.NaN;
		}
		return Math.sqrt(roomConstant / (16.0 * Math.PI));
	}

	private static double finiteNonNegative(double value) {
		return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
	}
}
