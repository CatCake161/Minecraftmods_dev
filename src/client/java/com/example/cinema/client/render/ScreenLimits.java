package com.example.cinema.client.render;

import com.example.cinema.client.config.CinemaClientConfig;

/**
 * Client-side limit for the size of a multi-block screen.
 *
 * <p>The saved value lives in {@code config/cinema.json} and defaults to
 * 16x16. A launch property {@code -Dcinema.screen.maxSize=N} still overrides
 * the saved value; both are clamped to 1..64 so the hard upper bound is 64x64.
 */
public final class ScreenLimits {
	public static final String MAX_SIZE_PROPERTY = CinemaClientConfig.SCREEN_MAX_SIZE_PROPERTY;
	public static final int MIN_MAX_SIZE = CinemaClientConfig.MIN_SCREEN_MAX_SIZE;
	public static final int DEFAULT_MAX_SIZE = CinemaClientConfig.DEFAULT_SCREEN_MAX_SIZE;
	public static final int HARD_MAX_SIZE = CinemaClientConfig.MAX_SCREEN_MAX_SIZE;

	private ScreenLimits() {
	}

	/** Effective limit, including an active launch override. */
	public static int maxSize() {
		Integer override = propertyOverride();
		return override != null ? override : CinemaClientConfig.get().screenMaxSize();
	}

	/** True when a launch property is taking precedence over the saved config. */
	public static boolean hasPropertyOverride() {
		return propertyOverride() != null;
	}

	/** Launch override, or null when the saved config is used. */
	public static Integer propertyOverride() {
		return CinemaClientConfig.screenMaxSizeOverride();
	}

	public static int clampMaxSize(int value) {
		return CinemaClientConfig.clampScreenMaxSize(value);
	}

	/** Where the effective value came from, for the startup log. */
	public static String source() {
		Integer override = propertyOverride();
		if (override != null) {
			return "property -D" + MAX_SIZE_PROPERTY + "=" + override;
		}
		return "config " + CinemaClientConfig.configFile();
	}
}
