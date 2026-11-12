package com.example.cinema.client.media;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.ffmpeg.Ffmpeg;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-local cache of media probe results.
 *
 * <p>Probing a file with FFmpeg is the slow part of opening a media folder, so
 * the result is stored in {@code <game>/cinema/media-cache.json} and reused
 * across game sessions. A cached entry is only accepted when the file size and
 * last-modified time still match; the folder itself is always listed again, so
 * added, renamed and deleted files show up without pressing Refresh.
 *
 * <p>The cache is keyed by media key, which already contains the library id for
 * external libraries. Absolute paths are never written.
 */
public final class MediaLibraryCache {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int FORMAT_VERSION = 1;
	private static final int MAX_ENTRIES = 4_096;
	private static final long MAX_AGE_MILLIS = 90L * 24L * 60L * 60L * 1_000L;

	private static final Map<String, StoredProbe> probes = new HashMap<>();
	private static boolean loaded;
	private static boolean dirty;

	private MediaLibraryCache() {
	}

	public static Path cacheFile() {
		return Minecraft.getInstance().gameDirectory.toPath()
			.resolve("cinema").resolve("media-cache.json")
			.toAbsolutePath().normalize();
	}

	/** Returns the cached probe for an unchanged file, or null on a miss. */
	public static synchronized MediaLibrary.Probe lookup(String key, long size,
														long modifiedMillis) {
		load();
		StoredProbe stored = probes.get(key);
		if (stored == null || stored.size != size
			|| stored.modifiedMillis != modifiedMillis) {
			return null;
		}
		stored.lastUsedMillis = System.currentTimeMillis();
		return stored.toProbe();
	}

	public static synchronized void store(String key, String libraryId, long size,
										  long modifiedMillis,
										  MediaLibrary.Probe probe) {
		load();
		StoredProbe stored = new StoredProbe();
		stored.key = key;
		stored.libraryId = libraryId;
		stored.size = size;
		stored.modifiedMillis = modifiedMillis;
		stored.lastUsedMillis = System.currentTimeMillis();
		stored.durationSeconds = probe.durationSeconds();
		if (probe.video() != null) {
			stored.video = StoredVideo.of(probe.video());
		}
		if (probe.audio() != null) {
			stored.audio = StoredAudio.of(probe.audio());
		}
		probes.put(key, stored);
		dirty = true;
	}

	/** Drops every cached probe that belongs to a removed library. */
	public static synchronized void invalidateLibrary(String libraryId) {
		load();
		if (probes.values().removeIf(stored -> libraryId.equals(stored.libraryId))) {
			dirty = true;
		}
	}

	/** Writes the cache if it changed. Safe to call from a worker thread. */
	public static synchronized void flush() {
		load();
		if (!dirty) {
			return;
		}
		prune();
		StoredCache data = new StoredCache();
		data.version = FORMAT_VERSION;
		data.probes = new ArrayList<>(probes.values());
		Path file = cacheFile();
		try {
			Files.createDirectories(file.getParent());
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}
			dirty = false;
		} catch (IOException e) {
			CinemaLog.warn(CinemaLog.MEDIA, "could not save {}: {}", file, e.getMessage());
		}
	}

	private static void prune() {
		long now = System.currentTimeMillis();
		probes.values().removeIf(stored -> stored.lastUsedMillis > 0L
			&& now - stored.lastUsedMillis > MAX_AGE_MILLIS);
		if (probes.size() <= MAX_ENTRIES) {
			return;
		}
		List<StoredProbe> sorted = new ArrayList<>(probes.values());
		sorted.sort(Comparator.comparingLong(stored -> stored.lastUsedMillis));
		int excess = probes.size() - MAX_ENTRIES;
		for (int i = 0; i < excess; i++) {
			probes.remove(sorted.get(i).key);
		}
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = cacheFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			StoredCache data = GSON.fromJson(reader, StoredCache.class);
			if (data == null || data.version != FORMAT_VERSION || data.probes == null) {
				return;
			}
			for (StoredProbe stored : data.probes) {
				if (stored != null && stored.key != null && stored.libraryId != null) {
					probes.put(stored.key, stored);
				}
			}
		} catch (Exception e) {
			CinemaLog.warn(CinemaLog.MEDIA, "could not read {}: {}", file, e.getMessage());
		}
	}

	private static final class StoredCache {
		int version;
		List<StoredProbe> probes = new ArrayList<>();
	}

	private static final class StoredProbe {
		String key;
		String libraryId;
		long size;
		long modifiedMillis;
		long lastUsedMillis;
		double durationSeconds;
		StoredVideo video;
		StoredAudio audio;

		MediaLibrary.Probe toProbe() {
			Ffmpeg.VideoInfo videoInfo = video == null ? null : video.toInfo();
			Ffmpeg.AudioInfo audioInfo = audio == null ? null : audio.toInfo();
			return new MediaLibrary.Probe(durationSeconds, videoInfo, audioInfo);
		}
	}

	private static final class StoredVideo {
		int width;
		int height;
		double frameRate;
		String codec;
		String pixelFormat;
		String colorTransfer;
		String colorPrimaries;
		String colorSpace;

		static StoredVideo of(Ffmpeg.VideoInfo info) {
			StoredVideo stored = new StoredVideo();
			stored.width = info.width();
			stored.height = info.height();
			stored.frameRate = info.frameRate();
			stored.codec = info.codec();
			stored.pixelFormat = info.pixelFormat();
			stored.colorTransfer = info.colorTransfer();
			stored.colorPrimaries = info.colorPrimaries();
			stored.colorSpace = info.colorSpace();
			return stored;
		}

		Ffmpeg.VideoInfo toInfo() {
			return new Ffmpeg.VideoInfo(width, height, frameRate, codec,
				pixelFormat, colorTransfer, colorPrimaries, colorSpace);
		}
	}

	private static final class StoredAudio {
		String codec;
		int channels;
		int sampleRate;

		static StoredAudio of(Ffmpeg.AudioInfo info) {
			StoredAudio stored = new StoredAudio();
			stored.codec = info.codec();
			stored.channels = info.channels();
			stored.sampleRate = info.sampleRate();
			return stored;
		}

		Ffmpeg.AudioInfo toInfo() {
			return new Ffmpeg.AudioInfo(codec, channels, sampleRate);
		}
	}
}
