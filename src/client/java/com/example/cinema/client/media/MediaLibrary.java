package com.example.cinema.client.media;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.ffmpeg.Ffmpeg;
import com.example.cinema.media.MediaKey;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
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
	private static final long FULL_HASH_LIMIT_BYTES = 64L * 1024 * 1024;
	private static final int FINGERPRINT_SAMPLE_BYTES = 4 * 1024 * 1024;
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

	/** Lists directories, video files and audio-only files in one library folder. */
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
					} else if (isMediaFile(name) && Files.isRegularFile(path)) {
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
		String name = file.getFileName() == null ? "" : file.getFileName().toString();
		// Audio containers can carry cover art as an attached video stream.
		// Keep them on the audio-only path so the receiver never starts a video decoder.
		Ffmpeg.VideoInfo video = isAudioFile(name) ? null : Ffmpeg.probeVideoOrNull(file);
		double duration = Ffmpeg.probeDuration(file);
		Ffmpeg.AudioInfo audio = Ffmpeg.probeAudio(file);
		if (video == null && audio == null) {
			throw new IOException("No audio or video stream: " + name);
		}
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
		return hasExtension(name, VIDEO_EXTENSIONS);
	}

	public static boolean isAudioFile(String name) {
		return MediaKey.isAudioKey(name);
	}

	public static boolean isMediaFile(String name) {
		return isVideoFile(name) || isAudioFile(name);
	}

	private static boolean hasExtension(String name, Set<String> extensions) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return false;
		}
		return extensions.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	public static Metadata describe(Path file, String key) throws IOException {
		long size = Files.size(file);
		String sha256 = fingerprint(file, size);
		double duration = 0.0;
		try {
			duration = Ffmpeg.probeDuration(file);
		} catch (IOException e) {
			CinemaLog.warn(CinemaLog.MEDIA, "could not probe duration for {}", displayKey(key));
		}
		return new Metadata(key, size, sha256, duration);
	}

	/**
	 * Full SHA-256 for normal files; for very large media use a fast sampled
	 * fingerprint (head + tail + size) so selecting a movie does not require
	 * reading the whole file. The {@code q1:} prefix marks the sampled form.
	 */
	public static String fingerprint(Path file, long size) throws IOException {
		if (size <= FULL_HASH_LIMIT_BYTES) {
			return sha256(file);
		}
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
		digest.update("cinema-quick-fingerprint-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(size).array());
		byte[] buffer = new byte[FINGERPRINT_SAMPLE_BYTES];
		try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
			input.readFully(buffer);
			digest.update(buffer);
			input.seek(size - FINGERPRINT_SAMPLE_BYTES);
			input.readFully(buffer);
			digest.update(buffer);
		}
		return "q1:" + HexFormat.of().formatHex(digest.digest());
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
		public boolean audioOnly() {
			return video == null && audio != null;
		}
	}
}
