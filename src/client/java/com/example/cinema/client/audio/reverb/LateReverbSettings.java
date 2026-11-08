package com.example.cinema.client.audio.reverb;

import com.example.cinema.client.audio.acoustics.AcousticRoom;

/**
 * Immutable full-band late-reverb parameters derived from the room snapshot
 * and the physical late-field gain target.
 *
 * <p>The late field now covers the full audio band. A low DC/subsonic
 * high-pass (20 Hz) protects the FDN from DC, but content below the Schroeder
 * frequency is no longer excluded; low-frequency room modes remain the
 * separate A5 modal treatment.
 *
 * <p>{@code wetGainDb} is the effective upload gain: the computed
 * source-power/diffuse-field gain plus the user trim. The FDN input is source
 * power, so this gain is independent of listener distance. The diagnostic
 * fields keep the wet/direct target, source gain, room constant and critical
 * distance visible to {@code /cinema_acoustics}.
 */
public record LateReverbSettings(boolean enabled, double rt60LowSeconds,
								 double rt60MidSeconds, double rt60HighSeconds,
								 double highPassHz, double preDelaySeconds,
								 double wetGainDb, double targetWetGainDb,
								 double sourceGainDb, double trimDb,
								 double roomConstant,
								 double criticalDistanceMeters) {
	public static final double MIN_RT60_SECONDS = 0.15;
	public static final double MAX_RT60_SECONDS = 5.0;
	public static final double MIN_PRE_DELAY_SECONDS = 0.020;
	public static final double MAX_PRE_DELAY_SECONDS = 0.050;
	public static final double MIN_HIGH_PASS_HZ = 10.0;
	public static final double MAX_HIGH_PASS_HZ = 2_000.0;
	public static final double DEFAULT_HIGH_PASS_HZ = 20.0;
	public static final double MIN_WET_GAIN_DB = -48.0;
	public static final double MAX_WET_GAIN_DB = 12.0;
	public static final double MIN_TARGET_GAIN_DB = LateReverbTarget.MIN_TARGET_DB;
	public static final double MAX_TARGET_GAIN_DB = LateReverbTarget.MAX_TARGET_DB;
	public static final double MIN_SOURCE_GAIN_DB =
		LateReverbTarget.MIN_SOURCE_GAIN_DB;
	public static final double MAX_SOURCE_GAIN_DB =
		LateReverbTarget.MAX_SOURCE_GAIN_DB;
	public static final double MIN_TRIM_DB = -24.0;
	public static final double MAX_TRIM_DB = 6.0;
	public static final double DEFAULT_WET_GAIN_DB =
		LateReverbTarget.FALLBACK_TARGET_DB;
	public static final double DEFAULT_TRIM_DB = 0.0;
	private static final double FALLBACK_RT60_SECONDS = 0.60;
	private static final double FALLBACK_LOW_RT60_SECONDS = 0.75;
	private static final double FALLBACK_HIGH_RT60_SECONDS = 0.45;
	private static final double FALLBACK_HIGH_PASS_HZ = DEFAULT_HIGH_PASS_HZ;
	private static final double FALLBACK_MEAN_FREE_PATH_METERS = 8.0;
	private static final double SPEED_OF_SOUND = 343.0;

	public static final LateReverbSettings OFF =
		new LateReverbSettings(false, FALLBACK_LOW_RT60_SECONDS,
			FALLBACK_RT60_SECONDS, FALLBACK_HIGH_RT60_SECONDS,
			FALLBACK_HIGH_PASS_HZ, MIN_PRE_DELAY_SECONDS,
			DEFAULT_WET_GAIN_DB, DEFAULT_WET_GAIN_DB, DEFAULT_WET_GAIN_DB,
			DEFAULT_TRIM_DB, Double.NaN, Double.NaN);

	/**
	 * Six-argument compatibility constructor used by tests and simple callers.
	 * It uses the mid RT60 for the low band and treats {@code wetGainDb} as the
	 * target, source gain and effective gain.
	 */
	public LateReverbSettings(boolean enabled, double rt60MidSeconds,
							  double rt60HighSeconds, double highPassHz,
							  double preDelaySeconds, double wetGainDb) {
		this(enabled, rt60MidSeconds, rt60MidSeconds, rt60HighSeconds,
			highPassHz, preDelaySeconds, wetGainDb, wetGainDb, wetGainDb,
			DEFAULT_TRIM_DB, Double.NaN, Double.NaN);
	}

	/** Seven-argument convenience constructor with an independent low band. */
	public LateReverbSettings(boolean enabled, double rt60LowSeconds,
							  double rt60MidSeconds, double rt60HighSeconds,
							  double highPassHz, double preDelaySeconds,
							  double wetGainDb) {
		this(enabled, rt60LowSeconds, rt60MidSeconds, rt60HighSeconds,
			highPassHz, preDelaySeconds, wetGainDb, wetGainDb, wetGainDb,
			DEFAULT_TRIM_DB, Double.NaN, Double.NaN);
	}

	public LateReverbSettings {
		rt60LowSeconds = sanitizeRt60(rt60LowSeconds, FALLBACK_LOW_RT60_SECONDS);
		rt60MidSeconds = sanitizeRt60(rt60MidSeconds, FALLBACK_RT60_SECONDS);
		rt60HighSeconds = sanitizeRt60(rt60HighSeconds,
			FALLBACK_HIGH_RT60_SECONDS);
		highPassHz = Double.isFinite(highPassHz)
			? Math.max(MIN_HIGH_PASS_HZ,
				Math.min(MAX_HIGH_PASS_HZ, highPassHz))
			: FALLBACK_HIGH_PASS_HZ;
		preDelaySeconds = Double.isFinite(preDelaySeconds)
			? Math.max(MIN_PRE_DELAY_SECONDS,
				Math.min(MAX_PRE_DELAY_SECONDS, preDelaySeconds))
			: MIN_PRE_DELAY_SECONDS;
		targetWetGainDb = Double.isFinite(targetWetGainDb)
			? Math.max(MIN_TARGET_GAIN_DB,
				Math.min(MAX_TARGET_GAIN_DB, targetWetGainDb))
			: DEFAULT_WET_GAIN_DB;
		sourceGainDb = Double.isFinite(sourceGainDb)
			? Math.max(MIN_SOURCE_GAIN_DB,
				Math.min(MAX_SOURCE_GAIN_DB, sourceGainDb))
			: DEFAULT_WET_GAIN_DB;
		trimDb = Double.isFinite(trimDb)
			? Math.max(MIN_TRIM_DB, Math.min(MAX_TRIM_DB, trimDb))
			: DEFAULT_TRIM_DB;
		wetGainDb = Double.isFinite(wetGainDb)
			? Math.max(MIN_WET_GAIN_DB, Math.min(MAX_WET_GAIN_DB, wetGainDb))
			: DEFAULT_WET_GAIN_DB;
		roomConstant = Double.isFinite(roomConstant) && roomConstant > 0.0
			? roomConstant : Double.NaN;
		criticalDistanceMeters = Double.isFinite(criticalDistanceMeters)
			&& criticalDistanceMeters > 0.0 ? criticalDistanceMeters : Double.NaN;
	}

	/**
	 * Builds settings from the room, the computed wet/direct target and the
	 * source-power gain.
	 */
	public static LateReverbSettings forRoom(AcousticRoom room, boolean enabled,
											 double targetWetGainDb,
											 double sourceGainDb, double trimDb,
											 double roomConstant,
											 double criticalDistanceMeters) {
		double target = Double.isFinite(targetWetGainDb)
			? Math.max(MIN_TARGET_GAIN_DB,
				Math.min(MAX_TARGET_GAIN_DB, targetWetGainDb))
			: DEFAULT_WET_GAIN_DB;
		double sourceGain = Double.isFinite(sourceGainDb)
			? Math.max(MIN_SOURCE_GAIN_DB,
				Math.min(MAX_SOURCE_GAIN_DB, sourceGainDb))
			: DEFAULT_WET_GAIN_DB;
		double trim = Double.isFinite(trimDb)
			? Math.max(MIN_TRIM_DB, Math.min(MAX_TRIM_DB, trimDb))
			: DEFAULT_TRIM_DB;
		double effective = Math.max(MIN_WET_GAIN_DB,
			Math.min(MAX_WET_GAIN_DB, sourceGain + trim));
		if (room == null) {
			return new LateReverbSettings(enabled, FALLBACK_LOW_RT60_SECONDS,
				FALLBACK_RT60_SECONDS, FALLBACK_HIGH_RT60_SECONDS,
				FALLBACK_HIGH_PASS_HZ, MIN_PRE_DELAY_SECONDS, effective, target,
				sourceGain, trim, roomConstant, criticalDistanceMeters);
		}
		double volume = room.effectiveVolume();
		double area = room.effectiveSurfaceArea();
		double meanFreePath = volume > 0.0 && area > 0.0
			? 4.0 * volume / area : FALLBACK_MEAN_FREE_PATH_METERS;
		double preDelay = Math.max(MIN_PRE_DELAY_SECONDS,
			Math.min(MAX_PRE_DELAY_SECONDS, meanFreePath / SPEED_OF_SOUND));
		return new LateReverbSettings(enabled, room.rt60Low(), room.rt60Mid(),
			room.rt60High(), DEFAULT_HIGH_PASS_HZ, preDelay, effective, target,
			sourceGain, trim, roomConstant, criticalDistanceMeters);
	}

	/**
	 * Legacy 3-argument entry point: the supplied value is an effective wet
	 * gain, not a physical target.
	 */
	public static LateReverbSettings forRoom(AcousticRoom room, boolean enabled,
											 double wetGainDb) {
		double roomConstant = LateReverbTarget.roomConstant(room);
		return forRoom(room, enabled, wetGainDb, wetGainDb, 0.0, roomConstant,
			LateReverbTarget.criticalDistance(roomConstant));
	}

	private static double sanitizeRt60(double value, double fallback) {
		if (Double.isNaN(value)) {
			return fallback;
		}
		if (value == Double.POSITIVE_INFINITY) {
			return MAX_RT60_SECONDS;
		}
		if (value == Double.NEGATIVE_INFINITY) {
			return fallback;
		}
		return Math.max(MIN_RT60_SECONDS, Math.min(MAX_RT60_SECONDS, value));
	}
}
