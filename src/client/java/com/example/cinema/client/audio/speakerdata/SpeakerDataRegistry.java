package com.example.cinema.client.audio.speakerdata;

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
 * Client-local speaker data profile registry.
 *
 * <p>Profiles are read from {@code <game>/cinema/speaker_data/profiles/}.
 * The selected profile and per-profile simple parameters are saved to
 * {@code settings.json}. Nothing here is synchronized to the server. Legacy
 * {@code speaker_specs} and {@code speaker_directivity} folders are deleted,
 * not migrated.
 */
public final class SpeakerDataRegistry {
	public static final String SUB_SEALED_OMNI_ID = "sub-sealed-omni";
	public static final String SUB_SEALED_CARDIOID_ID = "sub-sealed-cardioid";
	public static final String SUB_PORTED_OMNI_ID = "sub-ported-omni";
	public static final String SUB_PORTED_CARDIOID_ID = "sub-ported-cardioid";
	/** Legacy built-in ids kept for settings/assignment migration. */
	public static final String LEGACY_SUB_OMNI_ID = "sub-omni";
	public static final String LEGACY_SUB_CARDIOID_ID = "sub-cardioid";
	public static final String LEGACY_SUB_REFLEX_OMNI_ID = "sub-reflex-omni";
	public static final String LEGACY_SUB_REFLEX_CARDIOID_ID = "sub-reflex-cardioid";
	/** Manual subwoofer bass-management band. */
	public static final double SUBWOOFER_MIN_HZ = SubwooferBand.MIN_HZ;
	public static final double SUBWOOFER_MAX_HZ = SubwooferBand.MAX_HZ;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MAX_IMPORT_BYTES = 1_000_000L;
	private static final Map<SpeakerType, List<SpeakerDataProfile>> profilesByType =
		new EnumMap<>(SpeakerType.class);
	/** Selected profile per cabinet type. */
	private static final Map<SpeakerType, String> selectedByType = new EnumMap<>(SpeakerType.class);
	/** Simple parameter overrides keyed by {@code TYPE/profileId}. */
	private static final Map<String, SpeakerSettings> settingsOverrides = new HashMap<>();
	private static final List<String> profileErrors = new ArrayList<>();
	private static boolean loaded;

	private SpeakerDataRegistry() {
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
		deleteLegacyFolders();
		try {
			Files.createDirectories(profilesDir());
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker data: could not create profile folder: {}", e.getMessage());
		}
		reloadProfiles();
		loadSettings();
		ensureSettingsValid();
		Cinema.LOGGER.info("cinema speaker data: {} profile(s), {} load error(s)",
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
						SpeakerDataProfile profile = readProfile(path);
						register(profile);
						imported++;
					} catch (Exception e) {
						String message = path.getFileName() + ": " + e.getMessage();
						profileErrors.add(message);
						Cinema.LOGGER.warn("cinema speaker data: {}", message);
					}
				}
			}
		} catch (IOException e) {
			profileErrors.add("profiles folder: " + e.getMessage());
			Cinema.LOGGER.warn("cinema speaker data: could not scan profiles: {}", e.getMessage());
		}
		ensureSettingsValid();
		return new ReloadResult(imported, List.copyOf(profileErrors));
	}

	public static synchronized SpeakerDataProfile importProfile(Path source) throws IOException {
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
		SpeakerDataProfile profile = readProfile(source);
		Files.createDirectories(profilesDir());
		Path target = profilesDir().resolve(safeFileName(profile.id()) + ".json");
		if (!sameFile(source, target)) {
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
		register(profile);
		ensureSettingsValid();
		Cinema.LOGGER.info("cinema speaker data: imported {} ({})", profile.id(), profile.name());
		return profile;
	}

	public static synchronized List<SpeakerDataProfile> profilesFor(SpeakerType type) {
		return List.copyOf(profilesByType.getOrDefault(type, List.of()));
	}

	public static synchronized SpeakerDataProfile find(SpeakerType type, String id) {
		if (type == null || id == null || id.isBlank()) {
			return null;
		}
		String canonicalId = canonicalProfileId(type, id);
		for (SpeakerDataProfile profile : profilesByType.getOrDefault(type, List.of())) {
			if (profile.id().equals(canonicalId)) {
				return profile;
			}
		}
		return null;
	}

	/**
	 * Maps legacy built-in ids to the current profile ids. Only subwoofers had
	 * renamed built-ins; all other ids are returned unchanged.
	 */
	public static String canonicalProfileId(SpeakerType type, String profileId) {
		if (type != SpeakerType.SUBWOOFER || profileId == null) {
			return profileId;
		}
		return switch (profileId) {
			case LEGACY_SUB_OMNI_ID -> SUB_SEALED_OMNI_ID;
			case LEGACY_SUB_CARDIOID_ID -> SUB_SEALED_CARDIOID_ID;
			case LEGACY_SUB_REFLEX_OMNI_ID -> SUB_PORTED_OMNI_ID;
			case LEGACY_SUB_REFLEX_CARDIOID_ID -> SUB_PORTED_CARDIOID_ID;
			default -> profileId;
		};
	}

	/** Settings for the currently selected profile of this type. */
	public static synchronized SpeakerSettings settings(SpeakerType type) {
		ensureSettingsValid();
		String selected = selectedByType.get(type);
		return profileSettings(type, selected);
	}

	/**
	 * Saved settings for one profile, or the profile defaults if this profile
	 * has never been edited. Non-subwoofer measured profiles use the profile's
	 * -3 dB frequency range and only expose the gain trim; subwoofers keep a
	 * manually editable 10-1000 Hz bass-management band.
	 */
	public static synchronized SpeakerSettings profileSettings(SpeakerType type, String profileId) {
		String canonicalId = canonicalProfileId(type, profileId);
		SpeakerDataProfile profile = find(type, canonicalId);
		if (profile == null) {
			return defaultSettings(type);
		}
		SpeakerSettings saved = settingsOverrides.get(key(type, canonicalId));
		return normalizeSettings(type, saved != null ? saved : defaultForProfile(profile));
	}

	/**
	 * Clamps editable settings. Subwoofers always keep manual low/high Hz;
	 * other measured profiles are forced to their measured -3 dB range. Gain
	 * remains a per-profile level trim.
	 */
	public static synchronized SpeakerSettings normalizeSettings(SpeakerType type,
																 SpeakerSettings settings) {
		if (type == null || settings == null) {
			return settings;
		}
		SpeakerDataProfile profile = find(type, settings.profileId());
		if (profile == null) {
			return settings;
		}
		return normalizeForProfile(type, profile, settings);
	}

	/**
	 * Pure normalization helper, separated from the registry lookup so it can be
	 * unit-tested without a Fabric game directory.
	 */
	static SpeakerSettings normalizeForProfile(SpeakerType type, SpeakerDataProfile profile,
											   SpeakerSettings settings) {
		double gain = clamp(settings.gainDb(), -60.0, 60.0);
		if (type == SpeakerType.SUBWOOFER) {
			double low = Math.round(clamp(settings.lowHz(),
				SUBWOOFER_MIN_HZ, SUBWOOFER_MAX_HZ - 1.0));
			double high = Math.round(clamp(settings.highHz(),
				low + 1.0, SUBWOOFER_MAX_HZ));
			return new SpeakerSettings(profile.id(), low, high, gain);
		}
		if (profile.isMeasured()) {
			return new SpeakerSettings(profile.id(), profile.lowHz(), profile.highHz(), gain);
		}
		double low = Math.round(clamp(settings.lowHz(), 1.0, 23_999.0));
		double high = Math.round(clamp(settings.highHz(), low + 1.0, 24_000.0));
		return new SpeakerSettings(profile.id(), low, high, gain);
	}

	public static synchronized void applySettings(SpeakerType type, SpeakerSettings settings) {
		if (type == null || settings == null) {
			return;
		}
		SpeakerSettings normalized = normalizeSettings(type, settings);
		if (find(type, normalized.profileId()) == null) {
			return;
		}
		putOverride(type, normalized);
		selectedByType.put(type, normalized.profileId());
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
			profiles.add(key(type, settings.profileId()), value);
		}
		root.add("profiles", profiles);

		try {
			Files.createDirectories(rootDir());
			Files.writeString(settingsFile(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker data: could not save settings: {}", e.getMessage());
		}
	}

	public static synchronized int profileCount() {
		int count = 0;
		for (List<SpeakerDataProfile> list : profilesByType.values()) {
			count += list.size();
		}
		return count;
	}

	public static synchronized List<String> errors() {
		return List.copyOf(profileErrors);
	}

	public static Path rootDir() {
		return FabricLoader.getInstance().getGameDir().resolve("cinema").resolve("speaker_data");
	}

	private static void deleteLegacyFolders() {
		Path parent = rootDir().getParent();
		// The speaker-data spike previously used speaker_specs; the first
		// spike used speaker_directivity. The new layout is a clean break:
		// delete both legacy folders instead of migrating them.
		for (String name : new String[]{"speaker_specs", "speaker_directivity"}) {
			Path legacy = parent.resolve(name);
			if (!Files.exists(legacy)) {
				continue;
			}
			try {
				deleteRecursively(legacy);
				Cinema.LOGGER.info("cinema speaker data: deleted legacy folder {}", legacy);
			} catch (IOException e) {
				Cinema.LOGGER.warn("cinema speaker data: could not delete legacy folder {}: {}",
					legacy, e.getMessage());
			}
		}
	}

	private static void deleteRecursively(Path root) throws IOException {
		try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
			List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
			for (Path path : paths) {
				Files.deleteIfExists(path);
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
		// The sealed built-in models a compact alignment with a 30 Hz -3 dB
		// point; the ported built-in extends to 20 Hz and rolls off more
		// steeply below tuning. The enclosure response is applied by
		// SubwooferAcoustics, not just displayed as metadata.
		addBuiltin(subwoofer(SUB_SEALED_OMNI_ID, "Subwoofer Sealed Omni",
			SpeakerDataProfile.Enclosure.SEALED, SpeakerDataProfile.Pattern.OMNI,
			30.0));
		addBuiltin(subwoofer(SUB_SEALED_CARDIOID_ID, "Subwoofer Sealed Cardioid",
			SpeakerDataProfile.Enclosure.SEALED, SpeakerDataProfile.Pattern.CARDIOID,
			30.0));
		addBuiltin(subwoofer(SUB_PORTED_OMNI_ID, "Subwoofer Ported Omni",
			SpeakerDataProfile.Enclosure.PORTED, SpeakerDataProfile.Pattern.OMNI,
			20.0));
		addBuiltin(subwoofer(SUB_PORTED_CARDIOID_ID, "Subwoofer Ported Cardioid",
			SpeakerDataProfile.Enclosure.PORTED, SpeakerDataProfile.Pattern.CARDIOID,
			20.0));
		addBuiltin(unmeasured(SpeakerType.FLOORSTANDING, 40.0));
		addBuiltin(unmeasured(SpeakerType.BOOKSHELF, 60.0));
		addBuiltin(unmeasured(SpeakerType.CENTER, 80.0));
	}

	private static SpeakerDataProfile subwoofer(String id, String name,
												SpeakerDataProfile.Enclosure enclosure,
												SpeakerDataProfile.Pattern pattern,
												double lowHz) {
		return new SpeakerDataProfile(id, name, SpeakerType.SUBWOOFER, enclosure,
			pattern, "built-in", "built-in", lowHz, 200.0,
			null, null, null, null);
	}

	private static SpeakerDataProfile unmeasured(SpeakerType type, double lowHz) {
		return new SpeakerDataProfile(type.id() + "-unmeasured",
			capitalize(type.id()) + " (unmeasured)",
			type, SpeakerDataProfile.Enclosure.UNKNOWN,
			SpeakerDataProfile.Pattern.UNMEASURED,
			"built-in placeholder", "built-in",
			lowHz, 20_000.0, null, null, null, null);
	}

	private static void addBuiltin(SpeakerDataProfile profile) {
		register(profile);
	}

	private static void register(SpeakerDataProfile profile) {
		List<SpeakerDataProfile> list = profilesByType.computeIfAbsent(
			profile.type(), type -> new ArrayList<>());
		list.removeIf(existing -> existing.id().equals(profile.id()));
		list.add(profile);
		list.sort(Comparator.comparing(SpeakerDataProfile::name, String.CASE_INSENSITIVE_ORDER));
	}

	private static void ensureSettingsValid() {
		for (SpeakerType type : SpeakerType.values()) {
			String selected = canonicalProfileId(type, selectedByType.get(type));
			if (selected == null || find(type, selected) == null) {
				SpeakerDataProfile profile = defaultProfile(type);
				selectedByType.put(type, profile == null ? "" : profile.id());
			} else {
				selectedByType.put(type, selected);
			}
		}
	}

	private static SpeakerSettings defaultSettings(SpeakerType type) {
		SpeakerDataProfile profile = defaultProfile(type);
		return profile == null
			? new SpeakerSettings("", 20.0,
				type == SpeakerType.SUBWOOFER ? 200.0 : 20_000.0, 0.0)
			: defaultForProfile(profile);
	}

	private static SpeakerSettings defaultForProfile(SpeakerDataProfile profile) {
		return new SpeakerSettings(profile.id(), profile.lowHz(), profile.highHz(), 0.0);
	}

	private static SpeakerDataProfile defaultProfile(SpeakerType type) {
		String preferred = type == SpeakerType.SUBWOOFER
			? SUB_SEALED_OMNI_ID
			: type.id() + "-unmeasured";
		SpeakerDataProfile profile = find(type, preferred);
		if (profile != null) {
			return profile;
		}
		List<SpeakerDataProfile> list = profilesByType.getOrDefault(type, List.of());
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
						String id = canonicalProfileId(type, value.getAsString());
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
					String profileId = canonicalProfileId(type, key.substring(slash + 1));
					SpeakerDataProfile profile = find(type, profileId);
					if (profile == null) {
						continue;
					}
					JsonObject value = entry.getValue().getAsJsonObject();
					putOverride(type, new SpeakerSettings(profile.id(),
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
				String profileId = canonicalProfileId(type,
					entry.get("profile").getAsString());
				SpeakerDataProfile profile = find(type, profileId);
				if (profile == null) {
					continue;
				}
				selectedByType.put(type, profile.id());
				putOverride(type, new SpeakerSettings(profile.id(),
					getDouble(entry, "lowHz", profile.lowHz()),
					getDouble(entry, "highHz", profile.highHz()),
					getDouble(entry, "gainDb", 0.0)));
			}
		} catch (Exception e) {
			Cinema.LOGGER.warn("cinema speaker data: could not load settings: {}", e.getMessage());
		}
	}

	private static void putOverride(SpeakerType type, SpeakerSettings settings) {
		if (type == null || settings == null || find(type, settings.profileId()) == null) {
			return;
		}
		SpeakerSettings normalized = normalizeSettings(type, settings);
		settingsOverrides.put(key(type, normalized.profileId()), normalized);
	}

	private static String key(SpeakerType type, String profileId) {
		return type.name() + "/" + profileId;
	}

	private static SpeakerDataProfile readProfile(Path path) throws IOException {
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
			return SpeakerDataProfile.fromJson(parsed.getAsJsonObject());
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
