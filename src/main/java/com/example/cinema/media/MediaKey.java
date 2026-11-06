package com.example.cinema.media;

import java.util.Locale;
import java.util.Set;

/**
 * Lexical validation for media-library keys.
 *
 * <p>Keys are always relative to {@code <game>/cinema/media/} and use forward
 * slashes. Absolute paths, drive letters and parent-directory segments are
 * rejected so a client cannot ask the server to remember an arbitrary path.
 */
public final class MediaKey {
	public static final int MAX_LENGTH = 512;
	public static final Set<String> AUDIO_EXTENSIONS = Set.of(
		"mp3", "mp2", "flac", "wav", "m4a", "m4b", "aac", "ogg", "oga", "opus",
		"wma", "aiff", "aif", "ape", "alac", "wv", "mka", "ac3", "dts", "tta");

	private MediaKey() {
	}

	/** True when the key's final extension is a known audio-only container. */
	public static boolean isAudioKey(String key) {
		if (key == null) {
			return false;
		}
		int dot = key.lastIndexOf('.');
		if (dot < 0 || dot == key.length() - 1) {
			return false;
		}
		return AUDIO_EXTENSIONS.contains(key.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	/** Returns a normalized key, or {@code null} when the input is not a valid key. */
	public static String normalize(String raw) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim().replace('\\', '/');
		if (value.isEmpty() || value.length() > MAX_LENGTH) {
			return null;
		}
		if (value.startsWith("/") || value.indexOf(':') >= 0) {
			return null;
		}
		String[] parts = value.split("/", -1);
		StringBuilder normalized = new StringBuilder(value.length());
		for (String part : parts) {
			if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
				return null;
			}
			if (normalized.length() > 0) {
				normalized.append('/');
			}
			normalized.append(part);
		}
		return normalized.toString();
	}

	public static boolean isValid(String raw) {
		return normalize(raw) != null;
	}
}
