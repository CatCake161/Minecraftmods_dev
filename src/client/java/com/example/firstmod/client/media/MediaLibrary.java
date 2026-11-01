package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import com.example.firstmod.media.MediaKey;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Client-side access to the fixed {@code <game>/cinema/media/} library.
 *
 * <p>The server never reads these files; it only stores the relative key and
 * the metadata the client reports when selecting a movie.
 */
public final class MediaLibrary {
	private static final String LIBRARY_PREFIX = "cinema/media/";
	private static final int HASH_BUFFER_BYTES = 1024 * 1024;

	private MediaLibrary() {
	}

	public static Path root() {
		Path root = Minecraft.getInstance().gameDirectory.toPath()
			.resolve("cinema").resolve("media").toAbsolutePath().normalize();
		try {
			Files.createDirectories(root);
		} catch (IOException e) {
			FirstMod.LOGGER.warn("cinema media: could not create library directory", e);
		}
		return root;
	}

	/**
	 * Resolves a command argument or a relative key to a file inside the
	 * library. Absolute paths are accepted only when they already point inside
	 * the library, so pasted paths keep working without allowing arbitrary
	 * server-side paths.
	 */
	public static Path resolve(String raw) throws IOException {
		Path root = root();
		String key = keyFromInput(raw, root);
		if (key == null) {
			throw new IOException("Media must be inside cinema/media/ (relative key required)");
		}
		Path file = root.resolve(key).normalize();
		if (!file.startsWith(root)) {
			throw new IOException("Media key escapes the cinema library");
		}
		if (!Files.isRegularFile(file)) {
			throw new IOException("Media file not found: " + key);
		}
		return file;
	}

	public static String keyFromInput(String raw, Path root) {
		if (raw == null) {
			return null;
		}
		String value = raw.trim().replace('\\', '/');
		if (value.isEmpty()) {
			return null;
		}
		if (value.regionMatches(true, 0, LIBRARY_PREFIX, 0, LIBRARY_PREFIX.length())) {
			value = value.substring(LIBRARY_PREFIX.length());
		}
		try {
			Path input = Path.of(value);
			if (input.isAbsolute()) {
				Path absolute = input.toAbsolutePath().normalize();
				if (!absolute.startsWith(root)) {
					return null;
				}
				return root.relativize(absolute).toString().replace('\\', '/');
			}
		} catch (InvalidPathException e) {
			return null;
		}
		return MediaKey.normalize(value);
	}

	public static String keyFor(Path file) throws IOException {
		Path root = root();
		Path absolute = file.toAbsolutePath().normalize();
		if (!absolute.startsWith(root)) {
			throw new IOException("Media must be inside cinema/media/");
		}
		return root.relativize(absolute).toString().replace('\\', '/');
	}

	public static Metadata describe(Path file, String key) throws IOException {
		long size = Files.size(file);
		String sha256 = sha256(file);
		double duration = 0.0;
		try {
			duration = Ffmpeg.probeDuration(file);
		} catch (IOException e) {
			FirstMod.LOGGER.warn("cinema media: could not probe duration for {}", key);
		}
		return new Metadata(key, size, sha256, duration);
	}

	public static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		byte[] buffer = new byte[HASH_BUFFER_BYTES];
		try (InputStream input = Files.newInputStream(file)) {
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	public record Metadata(String key, long size, String sha256, double durationSeconds) {
	}
}
