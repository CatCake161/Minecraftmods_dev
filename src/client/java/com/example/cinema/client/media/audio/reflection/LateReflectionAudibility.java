package com.example.cinema.client.media.audio.reflection;

/**
 * Listener-relative audibility rules for the geometric late bus.
 *
 * <p>The late bus is not filtered by an absolute dBFS threshold or by the room
 * box diagonal. Instead the direct sound at the listener is the reference: a
 * tap is kept only while its power stays within {@link #DEFAULT_FLOOR_DB} of
 * the total direct power. With the default -60 dB floor the audible tail ends
 * after one RT60, so the maximum late delay can be derived directly from the
 * room's mid-band RT60:
 *
 * <pre>{@code
 * maxDelay = min(historySafe, RT60_mid * (-floorDb) / 60)
 * }</pre>
 *
 * <p>All values are in the pre-master digital domain: direct amplitude and tap
 * amplitude both exclude the receiver volume and channel gain, so changing the
 * volume does not move the accepted tap set.
 */
public final class LateReflectionAudibility {
	/** Relative late-bus floor; -60 dB means "one RT60 below the direct". */
	public static final double DEFAULT_FLOOR_DB = -60.0;
	public static final double MIN_FLOOR_DB = -80.0;
	public static final double MAX_FLOOR_DB = -20.0;

	private LateReflectionAudibility() {
	}

	/**
	 * Total direct power at the listener. Each amplitude already includes
	 * speaker gain, directivity/FIR level, occlusion and the 1/max(1,d)
	 * distance rule; entries are summed incoherently because separate physical
	 * speakers do not share a fixed phase reference.
	 */
	public static double directPower(double[] amplitudes) {
		if (amplitudes == null) {
			return 0.0;
		}
		double power = 0.0;
		for (double amplitude : amplitudes) {
			if (Double.isFinite(amplitude) && amplitude > 0.0) {
				power += amplitude * amplitude;
			}
		}
		return Double.isFinite(power) ? power : 0.0;
	}

	/**
	 * Absolute power floor for one listener position. Returns positive infinity
	 * when there is no direct sound, so no late tap can pass.
	 */
	public static double floorPower(double directPower, double floorDb) {
		if (!(directPower > 0.0) || !Double.isFinite(directPower)) {
			return Double.POSITIVE_INFINITY;
		}
		double relative = Double.isFinite(floorDb)
			? Math.max(MIN_FLOOR_DB, Math.min(MAX_FLOOR_DB, floorDb))
			: DEFAULT_FLOOR_DB;
		double floor = directPower * Math.pow(10.0, relative / 10.0);
		return Double.isFinite(floor) ? floor : Double.POSITIVE_INFINITY;
	}

	/**
	 * RT60-derived late-bus delay cap. The floor is relative to the direct
	 * level, so a -60 dB floor corresponds to one full RT60. A missing/zero
	 * RT60 (no room box yet) falls back to the hard capacity limit.
	 */
	public static double maxDelaySeconds(double rt60Seconds, double floorDb,
										 double hardCapSeconds) {
		double cap = Double.isFinite(hardCapSeconds)
			? Math.max(0.0, hardCapSeconds) : 0.0;
		if (!(rt60Seconds > 0.0) || !Double.isFinite(rt60Seconds)) {
			return cap;
		}
		double relative = Double.isFinite(floorDb)
			? Math.max(MIN_FLOOR_DB, Math.min(MAX_FLOOR_DB, floorDb))
			: DEFAULT_FLOOR_DB;
		double decayDb = Math.max(0.0, -relative);
		double seconds = rt60Seconds * decayDb / 60.0;
		if (!Double.isFinite(seconds)) {
			return cap;
		}
		return Math.min(cap, Math.max(0.0, seconds));
	}

	/** Power-based comparison against {@link #floorPower}. */
	public static boolean audible(double tapGain, double floorPower) {
		if (!Double.isFinite(tapGain) || tapGain <= 0.0) {
			return false;
		}
		return tapGain * tapGain >= floorPower;
	}

	/** Diagnostics helper: amplitude to dB, or a stable sentinel for zero. */
	public static double amplitudeDb(double amplitude) {
		if (amplitude == Double.POSITIVE_INFINITY) {
			return Double.POSITIVE_INFINITY;
		}
		return amplitude > 0.0 && Double.isFinite(amplitude)
			? 20.0 * Math.log10(amplitude) : Double.NEGATIVE_INFINITY;
	}
}
