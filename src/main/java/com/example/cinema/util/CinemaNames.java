package com.example.cinema.util;

/** Shared validation for user-assigned device names. */
public final class CinemaNames {
	public static final int MAX_LENGTH = 32;

	private CinemaNames() {
	}

	public static String sanitize(String value) {
		if (value == null) {
			return "";
		}
		String trimmed = value.trim();
		StringBuilder result = new StringBuilder(Math.min(trimmed.length(), MAX_LENGTH));
		for (int i = 0; i < trimmed.length() && result.length() < MAX_LENGTH; i++) {
			char c = trimmed.charAt(i);
			if (!Character.isISOControl(c)) {
				result.append(c);
			}
		}
		return result.toString();
	}
}
