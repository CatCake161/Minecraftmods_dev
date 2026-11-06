package com.example.cinema.client.audio.speakerdata;

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
 * Client-local per-speaker speaker data assignment.
 *
 * <p>The key is dimension + block position. The values are intentionally local:
 * speaker data affect the client's rendering/audio, like a graphics setting, and
 * the profile data itself is client-local. If a speaker has no valid assignment,
 * the type-level defaults from {@link SpeakerDataRegistry} are used.
 */
public final class SpeakerDataAssignments {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Assignment> assignmentsByKey = new HashMap<>();
	private static boolean loaded;

	private SpeakerDataAssignments() {
	}

	public record Assignment(SpeakerType type,
							 SpeakerDataRegistry.SpeakerSettings settings) {
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
					SpeakerDataRegistry.normalizeSettings(type,
						new SpeakerDataRegistry.SpeakerSettings(
							profileId, low, high, gain))));
			}
			Cinema.LOGGER.info("cinema speaker data: {} speaker assignment(s)", assignmentsByKey.size());
		} catch (Exception e) {
			Cinema.LOGGER.warn("cinema speaker data: could not load speaker assignments: {}",
				e.getMessage());
		}
	}

	public static synchronized Assignment get(SpeakerBlockEntity speaker) {
		if (speaker == null || speaker.getLevel() == null) {
			return null;
		}
		return assignmentsByKey.get(key(speaker));
	}

	public static synchronized SpeakerDataRegistry.SpeakerSettings settingsFor(
		SpeakerBlockEntity speaker) {
		SpeakerDataRegistry.SpeakerSettings fallback =
			SpeakerDataRegistry.settings(speaker.type());
		Assignment assignment = get(speaker);
		if (assignment == null || assignment.type() != speaker.type()) {
			return fallback;
		}
		if (SpeakerDataRegistry.find(speaker.type(),
			assignment.settings().profileId()) == null) {
			return fallback;
		}
		return SpeakerDataRegistry.normalizeSettings(speaker.type(), assignment.settings());
	}

	public static synchronized void apply(SpeakerBlockEntity speaker,
										  SpeakerDataRegistry.SpeakerSettings settings) {
		if (speaker == null || speaker.getLevel() == null || settings == null) {
			return;
		}
		if (SpeakerDataRegistry.find(speaker.type(), settings.profileId()) == null) {
			return;
		}
		assignmentsByKey.put(key(speaker), new Assignment(speaker.type(),
			SpeakerDataRegistry.normalizeSettings(speaker.type(), settings)));
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
			Files.createDirectories(SpeakerDataRegistry.rootDir());
			Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema speaker data: could not save speaker assignments: {}",
				e.getMessage());
		}
	}

	public static Path file() {
		return SpeakerDataRegistry.rootDir().resolve("assignments.json");
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

}
