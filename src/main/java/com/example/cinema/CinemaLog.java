package com.example.cinema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single entry point for Cinema log output.
 *
 * <p>Every line has the form {@code cinema <area>: <message>}. Areas are
 * short, stable subsystem names, so the game log can be filtered with one
 * search instead of matching several ad-hoc prefixes. New code should call
 * these helpers instead of {@link Cinema#LOGGER} directly.
 *
 * <p>Messages use SLF4J {@code {}} placeholders. A trailing
 * {@link Throwable} is logged with its stack trace, exactly like SLF4J.
 */
public final class CinemaLog {
	public static final String ACOUSTICS = "acoustics";
	public static final String AUDIO = "audio";
	public static final String AUDIO_DECODER = "audio decoder";
	public static final String AUDIO_SPIKE = "audio spike";
	public static final String CONFIG = "config";
	public static final String EFX_SPIKE = "EFX spike";
	public static final String FIR = "fir";
	public static final String LIFECYCLE = "lifecycle";
	public static final String MEDIA = "media";
	public static final String PAIRING = "pairing";
	public static final String PLAYER = "player";
	public static final String PREWARM = "prewarm";
	public static final String PROJECTOR_DEBUG = "projector debug";
	public static final String RECEIVER = "receiver";
	public static final String RECEIVER_GUI = "receiver gui";
	public static final String SCREEN = "screen";
	public static final String SPEAKER = "speaker data";
	public static final String SPIKE = "spike";
	public static final String VIDEO = "video";
	public static final String VIDEO_DECODER = "video decoder";
	public static final String VIDEO_SPIKE = "video spike";
	public static final String VIDEO_TEXTURE = "video texture";

	/** Same name as {@link Cinema#LOGGER}, but without initializing the mod. */
	private static final Logger LOGGER = LoggerFactory.getLogger("cinema");

	private CinemaLog() {
	}

	public static void info(String area, String message, Object... args) {
		LOGGER.info(line(area, message), args);
	}

	public static void warn(String area, String message, Object... args) {
		LOGGER.warn(line(area, message), args);
	}

	public static void error(String area, String message, Object... args) {
		LOGGER.error(line(area, message), args);
	}

	public static void debug(String area, String message, Object... args) {
		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(line(area, message), args);
		}
	}

	private static String line(String area, String message) {
		return "cinema " + area + ": " + message;
	}
}
