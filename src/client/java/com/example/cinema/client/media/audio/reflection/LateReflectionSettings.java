package com.example.cinema.client.media.audio.reflection;

/**
 * Immutable settings for the geometric late-reflection bus.
 *
 * <p>{@code wetGainDb} is the effective upload gain applied to the six wet
 * sources. It is the room/source-power calibration plus the user trim; the
 * geometric taps themselves already carry path gain, speaker trim and the
 * reflection FIR level.
 *
 * <p>The former FDN late reverb has been removed. Only the geometric
 * {@link LateReflectionBus} path remains, so RT60, pre-delay and high-pass
 * parameters no longer exist.
 */
public record LateReflectionSettings(boolean enabled, double wetGainDb) {
	public static final double MIN_WET_GAIN_DB = -48.0;
	public static final double MAX_WET_GAIN_DB = 12.0;
	public static final double DEFAULT_WET_GAIN_DB = -12.0;

	public static final LateReflectionSettings OFF =
		new LateReflectionSettings(false, DEFAULT_WET_GAIN_DB);

	public LateReflectionSettings {
		wetGainDb = Double.isFinite(wetGainDb)
			? Math.max(MIN_WET_GAIN_DB, Math.min(MAX_WET_GAIN_DB, wetGainDb))
			: DEFAULT_WET_GAIN_DB;
	}
}
