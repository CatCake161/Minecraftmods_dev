package com.example.cinema.client.audio.speakerspecs;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-local per-speaker speaker specs assignment.
 *
 * <p>The key is dimension + block position. The values are intentionally local:
 * speaker specs affect the client's rendering/audio, like a graphics setting, and
 * the profile data itself is client-local. If a speaker has no valid assignment,
 * the type-level defaults from {@link SpeakerSpecsRegistry} are used.
 */
public final class SpeakerSpecsAssignments {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Assignment> assignmentsByKey = new HashMap<>();
	private static boolean loaded;

	private SpeakerSpecsAssignments() {
	}

	public record Assignment(SpeakerType type,
							 SpeakerSpecsRegistry.SpeakerSettings settings) {
	}

	public static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return;
			}
			JsonObject assignments = parsed.getAsJsonObject().getAsJsonObject("assignments");
			if (assignments == null) {
				return;
			}
			for (Map.Entry<String, JsonElement> entry : assignments.entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject value = entry.getValue().getAsJsonObject();
				SpeakerType type = typeById(getString(value, "type", ""));
				if (type == null) {
					continue;
				}
				String profileId = getString(value, "profile", "");
				if (profileId.isBlank()) {
					continue;
				}
				double low = getDouble(value, "lowHz", 20.0);
				double high = getDouble(value, "highHz", 20_000.0);
				double gain = getDouble(value, "gainDb", 0.0);
				assignmentsByKey.put(entry.getKey(), new Assignment(type,
					clampSettings(new SpeakerSpecsRegistry.SpeakerSettings(
						profileId, low, high, gain))));
			}
			Cinema.LOGGER.info("cinema speaker specs: {} speaker assignment(s)", assignmentsByKey.size());
		} catch (Exception e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not load speaker assignments: {}",
				e.getMessage());
		}
	}

	public static synchronized Assignment get(SpeakerBlockEntity speaker) {
		if (speaker == null || speaker.getLevel() == null) {
			return null;
		}
		return assignmentsByKey.get(key(speaker));
	}

	public static synchronized SpeakerSpecsRegistry.SpeakerSettings settingsFor(
		SpeakerBlockEntity speaker) {
		SpeakerSpecsRegistry.SpeakerSettings fallback =
			SpeakerSpecsRegistry.settings(speaker.type());
		Assignment assignment = get(speaker);
		if (assignment == null || assignment.type() != speaker.type()) {
			return fallback;
		}
		if (SpeakerSpecsRegistry.find(speaker.type(),
			assignment.settings().profileId()) == null) {
			return fallback;
		}
		return assignment.settings();
	}

	public static synchronized void apply(SpeakerBlockEntity speaker,
										  SpeakerSpecsRegistry.SpeakerSettings settings) {
		if (speaker == null || speaker.getLevel() == null || settings == null) {
			return;
		}
		if (SpeakerSpecsRegistry.find(speaker.type(), settings.profileId()) == null) {
			return;
		}
		assignmentsByKey.put(key(speaker), new Assignment(speaker.type(), clampSettings(settings)));
		save();
	}

	public static synchronized void save() {
		JsonObject root = new JsonObject();
		JsonObject assignments = new JsonObject();
		for (Map.Entry<String, Assignment> entry : assignmentsByKey.entrySet()) {
			Assignment assignment = entry.getValue();
			JsonObject value = new JsonObject();
			value.addProperty("type", assignment.type().id());
			value.addProperty("profile", assignment.settings().profileId());
			value.addProperty("lowHz", assignment.settings().lowHz());
			value.addProperty("highHz", assignment.settings().highHz());
			value.addProperty("gainDb", assignment.settings().gainDb());
			assignments.add(entry.getKey(), value);
		}
		root.add("assignments", assignments);
		try {
			Files.createDirectories(SpeakerSpecsRegistry.rootDir());
			Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker specs: could not save speaker assignments: {}",
				e.getMessage());
		}
	}

	public static Path file() {
		return SpeakerSpecsRegistry.rootDir().resolve("assignments.json");
	}

	private static String key(SpeakerBlockEntity speaker) {
		return speaker.getLevel().dimension().location() + "@" + speaker.getBlockPos().asLong();
	}

	private static SpeakerType typeById(String id) {
		for (SpeakerType type : SpeakerType.values()) {
			if (type.id().equalsIgnoreCase(id)) {
				return type;
			}
		}
		return null;
	}

	private static SpeakerSpecsRegistry.SpeakerSettings clampSettings(
		SpeakerSpecsRegistry.SpeakerSettings settings) {
		double low = clamp(settings.lowHz(), 1.0, 23_999.0);
		double high = clamp(settings.highHz(), low + 1.0, 24_000.0);
		return new SpeakerSpecsRegistry.SpeakerSettings(settings.profileId(), low, high,
			clamp(settings.gainDb(), -60.0, 60.0));
	}

	private static String getString(JsonObject object, String key, String fallback) {
		JsonElement element = object.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		return element.getAsString();
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
}
