package com.example.cinema.client.audio.speakerspecs;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.SpeakerType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-local speaker specs profile registry.
 *
 * <p>Profiles are read from {@code <game>/cinema/speaker_specs/profiles/}.
 * The selected profile and per-profile simple parameters are saved to
 * {@code settings.json}. Nothing here is synchronized to the server.
 */
public final class SpeakerSpecsRegistry {
	public static final String SUB_OMNI_ID = "sub-omni";
	public static final String SUB_CARDIOID_ID = "sub-cardioid";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_IMPORT_BYTES = 1_000_000L;
	private static final Map<SpeakerType, List<SpeakerSpecsProfile>> profilesByType =
		new EnumMap<>(SpeakerType.class);
	/** Selected profile per cabinet type. */
	private static final Map<SpeakerType, String> selectedByType = new EnumMap<>(SpeakerType.class);
	/** Simple parameter overrides keyed by {@code TYPE/profileId}. */
	private static final Map<String, SpeakerSettings> settingsOverrides = new HashMap<>();
	private static final List<String> profileErrors = new ArrayList<>();
	private static boolean loaded;

	private SpeakerSpecsRegistry() {
	}

	public record SpeakerSettings(String profileId, double lowHz, double highHz,
								  double gainDb) {
	}

	public record ReloadResult(int profileCount, List<String> errors) {
	}

	public static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		migrateLegacyFolder();
		try {
			Files.createDirectories(profilesDir());
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not create profile folder: {}", e.getMessage());
		}
		reloadProfiles();
		loadSettings();
		ensureSettingsValid();
		Cinema.LOGGER.info("cinema speaker specs: {} profile(s), {} load error(s)",
			profileCount(), profileErrors.size());
	}

	public static synchronized ReloadResult reloadProfiles() {
		profilesByType.clear();
		profileErrors.clear();
		installBuiltins();
		int imported = 0;
		try {
			Files.createDirectories(profilesDir());
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir(), "*.json")) {
				List<Path> files = new ArrayList<>();
				for (Path path : stream) {
					files.add(path);
				}
				files.sort(Comparator.comparing(path -> path.getFileName().toString(),
					String.CASE_INSENSITIVE_ORDER));
				for (Path path : files) {
					try {
						SpeakerSpecsProfile profile = readProfile(path);
						register(profile);
						imported++;
					} catch (Exception e) {
						String message = path.getFileName() + ": " + e.getMessage();
						profileErrors.add(message);
						Cinema.LOGGER.warn("cinema speaker specs: {}", message);
					}
				}
			}
		} catch (IOException e) {
			profileErrors.add("profiles folder: " + e.getMessage());
			Cinema.LOGGER.warn("cinema speaker specs: could not scan profiles: {}", e.getMessage());
		}
		ensureSettingsValid();
		return new ReloadResult(imported, List.copyOf(profileErrors));
	}

	public static synchronized SpeakerSpecsProfile importProfile(Path source) throws IOException {
		if (source == null || !Files.isRegularFile(source)) {
			throw new IOException("not a file");
		}
		String fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
		if (!fileName.endsWith(".json")) {
			throw new IOException("profile must be a .json file");
		}
		long size = Files.size(source);
		if (size <= 0L || size > MAX_IMPORT_BYTES) {
			throw new IOException("profile is empty or larger than " + MAX_IMPORT_BYTES + " bytes");
		}
		SpeakerSpecsProfile profile = readProfile(source);
		Files.createDirectories(profilesDir());
		Path target = profilesDir().resolve(safeFileName(profile.id()) + ".json");
		if (!sameFile(source, target)) {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
		register(profile);
		ensureSettingsValid();
		Cinema.LOGGER.info("cinema speaker specs: imported {} ({})", profile.id(), profile.name());
		return profile;
	}

	public static synchronized List<SpeakerSpecsProfile> profilesFor(SpeakerType type) {
		return List.copyOf(profilesByType.getOrDefault(type, List.of()));
	}

	public static synchronized SpeakerSpecsProfile find(SpeakerType type, String id) {
		if (type == null || id == null || id.isBlank()) {
			return null;
		}
		for (SpeakerSpecsProfile profile : profilesByType.getOrDefault(type, List.of())) {
			if (profile.id().equals(id)) {
				return profile;
			}
		}
		return null;
	}

	/** Settings for the currently selected profile of this type. */
	public static synchronized SpeakerSettings settings(SpeakerType type) {
		ensureSettingsValid();
		String selected = selectedByType.get(type);
		return profileSettings(type, selected);
	}

	/**
	 * Saved settings for one profile, or the profile defaults if this profile
	 * has never been edited. This is what the profile cycle uses, so each
	 * profile keeps its own low/high/on-axis/gain values.
	 */
	public static synchronized SpeakerSettings profileSettings(SpeakerType type, String profileId) {
		SpeakerSpecsProfile profile = find(type, profileId);
		if (profile == null) {
			return defaultSettings(type);
		}
		SpeakerSettings saved = settingsOverrides.get(key(type, profileId));
		if (saved != null) {
			return saved;
		}
		return defaultForProfile(profile);
	}

	public static synchronized void applySettings(SpeakerType type, SpeakerSettings settings) {
		if (type == null || settings == null || find(type, settings.profileId()) == null) {
			return;
		}
		putOverride(type, settings);
		selectedByType.put(type, settings.profileId());
	}

	public static synchronized void saveSettings() {
		ensureSettingsValid();
		JsonObject root = new JsonObject();
		JsonObject selected = new JsonObject();
		for (SpeakerType type : SpeakerType.values()) {
			String profileId = selectedByType.get(type);
			if (profileId != null) {
				selected.addProperty(type.name(), profileId);
			}
		}
		root.add("selected", selected);

		JsonObject profiles = new JsonObject();
		for (Map.Entry<String, SpeakerSettings> entry : settingsOverrides.entrySet()) {
			String key = entry.getKey();
			int slash = key.indexOf('/');
			if (slash <= 0 || slash == key.length() - 1) {
				continue;
			}
			SpeakerSettings settings = entry.getValue();
			SpeakerType type;
			try {
				type = SpeakerType.valueOf(key.substring(0, slash));
			} catch (IllegalArgumentException e) {
				continue;
			}
			if (find(type, settings.profileId()) == null) {
				continue;
			}
			JsonObject value = new JsonObject();
			value.addProperty("lowHz", settings.lowHz());
			value.addProperty("highHz", settings.highHz());
			value.addProperty("gainDb", settings.gainDb());
			profiles.add(key, value);
		}
		root.add("profiles", profiles);

		try {
			Files.createDirectories(rootDir());
			Files.writeString(settingsFile(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not save settings: {}", e.getMessage());
		}
	}

	public static synchronized int profileCount() {
		int count = 0;
		for (List<SpeakerSpecsProfile> list : profilesByType.values()) {
			count += list.size();
		}
		return count;
	}

	public static synchronized List<String> errors() {
		return List.copyOf(profileErrors);
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getGameDir().resolve("cinema").resolve("speaker_specs");
	}

	private static void migrateLegacyFolder() {
		Path current = rootDir();
		Path legacy = current.getParent().resolve("speaker_directivity");
		if (Files.exists(current) || !Files.isDirectory(legacy)) {
			return;
		}
		try {
			copyDirectory(legacy, current);
			Cinema.LOGGER.info("cinema speaker specs: migrated legacy folder {} to {}",
				legacy, current);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not migrate legacy folder: {}",
				e.getMessage());
		}
	}

	private static void copyDirectory(Path source, Path target) throws IOException {
		try (java.util.stream.Stream<Path> stream = Files.walk(source)) {
			for (Path path : (Iterable<Path>) stream::iterator) {
				Path relative = source.relativize(path);
				Path destination = target.resolve(relative);
				if (Files.isDirectory(path)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	public static Path profilesDir() {
		return rootDir().resolve("profiles");
	}

	public static Path settingsFile() {
		return rootDir().resolve("settings.json");
	}

	private static void installBuiltins() {
		addBuiltin(new SpeakerSpecsProfile(SUB_OMNI_ID, "Subwoofer Omni",
			SpeakerType.SUBWOOFER, SpeakerSpecsProfile.Pattern.OMNI,
			"built-in", "built-in", "built-in", 20.0, 200.0, null, null, null, null));
		addBuiltin(new SpeakerSpecsProfile(SUB_CARDIOID_ID, "Subwoofer Cardioid",
			SpeakerType.SUBWOOFER, SpeakerSpecsProfile.Pattern.CARDIOID,
			"built-in", "built-in", "built-in", 20.0, 200.0, null, null, null, null));
		addBuiltin(unmeasured(SpeakerType.FLOORSTANDING, 40.0));
		addBuiltin(unmeasured(SpeakerType.BOOKSHELF, 60.0));
		addBuiltin(unmeasured(SpeakerType.CENTER, 80.0));
	}

	private static SpeakerSpecsProfile unmeasured(SpeakerType type, double lowHz) {
		return new SpeakerSpecsProfile(type.id() + "-unmeasured",
			capitalize(type.id()) + " (unmeasured)",
			type, SpeakerSpecsProfile.Pattern.UNMEASURED,
			"built-in placeholder", "built-in", "unmeasured",
			lowHz, 20_000.0, null, null, null, null);
	}

	private static void addBuiltin(SpeakerSpecsProfile profile) {
		register(profile);
	}

	private static void register(SpeakerSpecsProfile profile) {
		List<SpeakerSpecsProfile> list = profilesByType.computeIfAbsent(
			profile.type(), type -> new ArrayList<>());
		list.removeIf(existing -> existing.id().equals(profile.id()));
		list.add(profile);
		list.sort(Comparator.comparing(SpeakerSpecsProfile::name, String.CASE_INSENSITIVE_ORDER));
	}

	private static void ensureSettingsValid() {
		for (SpeakerType type : SpeakerType.values()) {
			String selected = selectedByType.get(type);
			if (selected == null || find(type, selected) == null) {
				SpeakerSpecsProfile profile = defaultProfile(type);
				selectedByType.put(type, profile == null ? "" : profile.id());
			}
		}
	}

	private static SpeakerSettings defaultSettings(SpeakerType type) {
		SpeakerSpecsProfile profile = defaultProfile(type);
		return profile == null
			? new SpeakerSettings("", 20.0, 20_000.0, 0.0)
			: defaultForProfile(profile);
	}

	private static SpeakerSettings defaultForProfile(SpeakerSpecsProfile profile) {
		return new SpeakerSettings(profile.id(), profile.lowHz(), profile.highHz(), 0.0);
	}

	private static SpeakerSpecsProfile defaultProfile(SpeakerType type) {
		String preferred = type == SpeakerType.SUBWOOFER
			? SUB_OMNI_ID
			: type.id() + "-unmeasured";
		SpeakerSpecsProfile profile = find(type, preferred);
		if (profile != null) {
			return profile;
		}
		List<SpeakerSpecsProfile> list = profilesByType.getOrDefault(type, List.of());
		return list.isEmpty() ? null : list.get(0);
	}

	private static void loadSettings() {
		Path file = settingsFile();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return;
			}
			JsonObject root = parsed.getAsJsonObject();
			JsonObject selected = root.getAsJsonObject("selected");
			if (selected != null) {
				for (SpeakerType type : SpeakerType.values()) {
					JsonElement value = selected.get(type.name());
					if (value != null && value.isJsonPrimitive()) {
						String id = value.getAsString();
						if (find(type, id) != null) {
							selectedByType.put(type, id);
						}
					}
				}
			}

			JsonObject profiles = root.getAsJsonObject("profiles");
			if (profiles != null) {
				for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
					if (!entry.getValue().isJsonObject()) {
						continue;
					}
					String key = entry.getKey();
					int slash = key.indexOf('/');
					if (slash <= 0 || slash == key.length() - 1) {
						continue;
					}
					SpeakerType type;
					try {
						type = SpeakerType.valueOf(key.substring(0, slash));
					} catch (IllegalArgumentException e) {
						continue;
					}
					String profileId = key.substring(slash + 1);
					SpeakerSpecsProfile profile = find(type, profileId);
					if (profile == null) {
						continue;
					}
					JsonObject value = entry.getValue().getAsJsonObject();
					putOverride(type, new SpeakerSettings(profileId,
						getDouble(value, "lowHz", profile.lowHz()),
						getDouble(value, "highHz", profile.highHz()),
						getDouble(value, "gainDb", 0.0)));
				}
				return;
			}

			// Migration from the first spike format: one entry per speaker type.
			JsonObject speakers = root.getAsJsonObject("speakers");
			if (speakers == null) {
				return;
			}
			for (SpeakerType type : SpeakerType.values()) {
				JsonObject entry = speakers.getAsJsonObject(type.name());
				if (entry == null || !entry.has("profile")) {
					continue;
				}
				String profileId = entry.get("profile").getAsString();
				SpeakerSpecsProfile profile = find(type, profileId);
				if (profile == null) {
					continue;
				}
				selectedByType.put(type, profileId);
				putOverride(type, new SpeakerSettings(profileId,
					getDouble(entry, "lowHz", profile.lowHz()),
					getDouble(entry, "highHz", profile.highHz()),
					getDouble(entry, "gainDb", 0.0)));
			}
		} catch (Exception e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not load settings: {}", e.getMessage());
		}
	}

	private static void putOverride(SpeakerType type, SpeakerSettings settings) {
		if (type == null || settings == null || find(type, settings.profileId()) == null) {
			return;
		}
		double low = clamp(settings.lowHz(), 1.0, 23_999.0);
		double high = clamp(settings.highHz(), low + 1.0, 24_000.0);
		double gain = clamp(settings.gainDb(), -60.0, 60.0);
		settingsOverrides.put(key(type, settings.profileId()),
			new SpeakerSettings(settings.profileId(), low, high, gain));
	}

	private static String key(SpeakerType type, String profileId) {
		return type.name() + "/" + profileId;
	}

	private static SpeakerSpecsProfile readProfile(Path path) throws IOException {
		long size = Files.size(path);
		if (size <= 0L || size > MAX_IMPORT_BYTES) {
			throw new IOException("profile is empty or too large");
		}
		String json = Files.readString(path, StandardCharsets.UTF_8);
		JsonElement parsed = JsonParser.parseString(json);
		if (!parsed.isJsonObject()) {
			throw new IOException("profile root must be a JSON object");
		}
		try {
			return SpeakerSpecsProfile.fromJson(parsed.getAsJsonObject());
		} catch (RuntimeException e) {
			throw new IOException(e.getMessage() == null ? "invalid profile" : e.getMessage(), e);
		}
	}

	private static double getDouble(JsonObject object, String key, double fallback) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		double value = element.getAsDouble();
		return Double.isFinite(value) ? value : fallback;
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static String safeFileName(String id) {
		return id.replaceAll("[^A-Za-z0-9_.-]", "_");
	}

	private static boolean sameFile(Path a, Path b) {
		try {
			return Files.isSameFile(a, b);
		} catch (IOException e) {
			return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
		}
	}

	private static String capitalize(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}
}
