package com.example.firstmod.media;

/**
 * Lexical validation for media-library keys.
 *
 * <p>Keys are always relative to {@code <game>/cinema/media/} and use forward
 * slashes. Absolute paths, drive letters and parent-directory segments are
 * rejected so a client cannot ask the server to remember an arbitrary path.
 */
public final class MediaKey {
	public static final int MAX_LENGTH = 512;

	private MediaKey() {
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
