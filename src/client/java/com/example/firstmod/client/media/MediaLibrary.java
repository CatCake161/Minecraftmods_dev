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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Client-side access to the media libraries.
 *
 * <p>The default library is {@code <game>/cinema/media/}. Additional folders
 * are registered through {@link LibraryRegistry} and exposed as
 * {@code lib/<id>/<relative>} keys. Absolute paths never leave the client.
 */
public final class MediaLibrary {
	private static final int HASH_BUFFER_BYTES = 1024 * 1024;
	private static final String EXTERNAL_PREFIX = "lib/";
	private static final Set<String> VIDEO_EXTENSIONS = Set.of(
		"mp4", "mkv", "webm", "mov", "m4v", "avi", "ts", "m2ts");

	private MediaLibrary() {
	}

	public static Path root() {
		return LibraryRegistry.defaultRoot();
	}

	/**
	 * Resolves a command argument or a media key to a local file. Accepted
	 * forms: a key relative to the default library, a {@code lib/<id>/...}
	 * key, or an absolute path inside a registered library.
	 */
	public static Path resolve(String raw) throws IOException {
		if (raw == null || raw.isBlank()) {
			throw new IOException("Media path is empty");
		}
		String value = raw.trim().replace('\\', '/');
		if (value.startsWith(EXTERNAL_PREFIX)) {
			return resolveKey(value);
		}
		try {
			Path input = Path.of(value);
			if (input.isAbsolute()) {
				Path absolute = input.toAbsolutePath().normalize();
				LibraryRegistry.Library library = LibraryRegistry.findByPath(absolute);
				if (library == null) {
					throw new IOException("Media is outside the registered libraries; add its folder in Settings");
				}
				return resolveKey(keyFor(absolute));
			}
		} catch (InvalidPathException e) {
			throw new IOException("Invalid media path");
		}
		return resolveKey(value);
	}

	private static Path resolveKey(String key) throws IOException {
		LibraryRegistry.Key parsed = LibraryRegistry.parseKey(key);
		if (parsed == null || parsed.library() == null) {
			throw new IOException("Unknown media library");
		}
		String relative = MediaKey.normalize(parsed.relative());
		if (relative == null) {
			throw new IOException("Invalid media key");
		}
		Path root = parsed.library().root();
		Path file = root.resolve(relative).normalize();
		if (!file.startsWith(root)) {
			throw new IOException("Media key escapes the library");
		}
		if (!Files.isRegularFile(file)) {
			throw new IOException("Media file not found: " + displayKey(key));
		}
		return file;
	}

	public static String keyFor(Path file) throws IOException {
		Path absolute = file.toAbsolutePath().normalize();
		LibraryRegistry.Library library = LibraryRegistry.findByPath(absolute);
		if (library == null) {
			throw new IOException("Media is outside the registered libraries");
		}
		String relative = library.root().relativize(absolute).toString().replace('\\', '/');
		return library.id().equals(LibraryRegistry.DEFAULT_ID)
			? relative
			: EXTERNAL_PREFIX + library.id() + "/" + relative;
	}

	/** Lists directories and video files in one library folder. */
	public static List<Entry> list(String libraryId, String folder) throws IOException {
		LibraryRegistry.Library library = LibraryRegistry.findById(libraryId);
		if (library == null) {
			throw new IOException("Unknown media library");
		}
		String normalizedFolder = folder == null ? "" : folder.trim();
		if (!normalizedFolder.isEmpty()) {
			String key = MediaKey.normalize(normalizedFolder);
			if (key == null) {
				throw new IOException("Invalid media folder");
			}
			normalizedFolder = key;
		}
		String folderKey = normalizedFolder;
		Path root = library.root();
		Path directory = folderKey.isEmpty() ? root : root.resolve(folderKey).normalize();
		if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
			throw new IOException("Media folder not found: " + folderKey);
		}
		List<Entry> entries = new ArrayList<>();
		try (Stream<Path> stream = Files.list(directory)) {
			stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
				.limit(250)
				.forEach(path -> {
					String name = path.getFileName().toString();
					if (name.startsWith(".") || name.equalsIgnoreCase("desktop.ini")) {
						return;
					}
					String relative = folderKey.isEmpty() ? name : folderKey + "/" + name;
					String key = library.id().equals(LibraryRegistry.DEFAULT_ID)
						? relative
						: EXTERNAL_PREFIX + library.id() + "/" + relative;
					if (Files.isDirectory(path)) {
						entries.add(new Entry(key, name, library.id(), relative, true, 0L));
					} else if (isVideoFile(name) && Files.isRegularFile(path)) {
						try {
							entries.add(new Entry(key, name, library.id(), folderKey, false, Files.size(path)));
						} catch (IOException ignored) {
							// Skip files that disappeared during the scan.
						}
					}
				});
		}
		entries.sort(Comparator
			.comparing(Entry::directory).reversed()
			.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT)));
		return entries;
	}

	public static List<Entry> list(String folder) throws IOException {
		return list(LibraryRegistry.DEFAULT_ID, folder);
	}

	public static Probe probe(Path file) throws IOException {
		Ffmpeg.VideoInfo video = Ffmpeg.probeVideo(file);
		double duration = Ffmpeg.probeDuration(file);
		Ffmpeg.AudioInfo audio = Ffmpeg.probeAudio(file);
		return new Probe(duration, video, audio);
	}

	public static String parent(String folder) {
		if (folder == null || folder.isEmpty()) {
			return "";
		}
		int slash = folder.lastIndexOf('/');
		return slash < 0 ? "" : folder.substring(0, slash);
	}

	public static boolean isVideoFile(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return false;
		}
		return VIDEO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	public static Metadata describe(Path file, String key) throws IOException {
		long size = Files.size(file);
		String sha256 = sha256(file);
		double duration = 0.0;
		try {
			duration = Ffmpeg.probeDuration(file);
		} catch (IOException e) {
			FirstMod.LOGGER.warn("cinema media: could not probe duration for {}", displayKey(key));
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

	/** Never log or display an absolute path for a media key. */
	public static String displayKey(String key) {
		if (key == null || key.isEmpty()) {
			return "none";
		}
		return key;
	}

	public record Metadata(String key, long size, String sha256, double durationSeconds) {
	}

	public record Entry(String key, String name, String libraryId, String folder,
						boolean directory, long size) {
	}

	public record Probe(double durationSeconds, Ffmpeg.VideoInfo video, Ffmpeg.AudioInfo audio) {
	}
}
