package com.example.cinema.client.config;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the editable acoustic material table from
 * {@code config/cinema-acoustic-materials.json}.
 *
 * <p>Missing keys keep their built-in value. Invalid JSON leaves the built-in
 * table active. The JSON is intended for in-game tuning; the built-in table is
 * always available as a fallback for tests and first launch.
 */
public final class AcousticMaterialConfig {
	public static final String FILE_NAME = "cinema-acoustic-materials.json";
	private static final int VERSION = 1;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private AcousticMaterialConfig() {
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static boolean load() {
		return load(file());
	}

	public static boolean reload() {
		return load(file());
	}

	/** Path-based entry point used by startup. */
	public static boolean load(Path file) {
		return load(file, true);
	}

	/** Test-friendly overload; logging is skipped when {@code log} is false. */
	static boolean load(Path file, boolean log) {
		if (file == null) {
			AcousticMaterials.resetToDefaults();
			return false;
		}
		if (!Files.isRegularFile(file)) {
			AcousticMaterials.resetToDefaults();
			writeDefaults(file, log);
			return true;
		}
		try {
			JsonObject root = JsonParser.parseString(
				Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject materials = root.getAsJsonObject("materials");
			if (materials == null) {
				throw new IOException("missing materials object");
			}
			Map<String, AcousticMaterial> table =
				new LinkedHashMap<>(AcousticMaterials.defaultsSnapshot());
			for (Map.Entry<String, JsonElement> entry : materials.entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					continue;
				}
				AcousticMaterial fallback = table.get(entry.getKey());
				if (fallback == null) {
					fallback = AcousticMaterials.get("default");
				}
				table.put(entry.getKey(), parse(entry.getKey(),
					entry.getValue().getAsJsonObject(), fallback));
			}
			AcousticMaterials.apply(table);
			if (log) {
				CinemaLog.info(CinemaLog.CONFIG,
					"loaded acoustic materials from {} ({} entries)", file,
					table.size());
			}
			return true;
		} catch (Exception e) {
			if (log) {
				CinemaLog.warn(CinemaLog.CONFIG,
					"could not load acoustic materials {}: {}; keeping defaults",
					file, e.getMessage());
			}
			AcousticMaterials.resetToDefaults();
			return false;
		}
	}

	public static void writeDefaults(Path file) {
		writeDefaults(file, true);
	}

	private static void writeDefaults(Path file, boolean log) {
		try {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			JsonObject root = new JsonObject();
			root.addProperty("version", VERSION);
			JsonObject materials = new JsonObject();
			for (Map.Entry<String, AcousticMaterial> entry
				: AcousticMaterials.defaultsSnapshot().entrySet()) {
				materials.add(entry.getKey(), toJson(entry.getValue()));
			}
			root.add("materials", materials);
			Files.writeString(file, GSON.toJson(root) + System.lineSeparator(),
				StandardCharsets.UTF_8);
			if (log) {
				CinemaLog.info(CinemaLog.CONFIG,
					"wrote default acoustic materials to {}", file);
			}
		} catch (IOException e) {
			if (log) {
				CinemaLog.warn(CinemaLog.CONFIG,
					"could not write acoustic materials {}: {}", file,
					e.getMessage());
			}
		}
	}

	private static AcousticMaterial parse(String id, JsonObject json,
										  AcousticMaterial fallback) {
		return new AcousticMaterial(id,
			read(json, "low", fallback.low()),
			read(json, "mid", fallback.mid()),
			read(json, "high", fallback.high()),
			read(json, "scatteringLow", fallback.scatteringLow()),
			read(json, "scatteringMid", fallback.scatteringMid()),
			read(json, "scatteringHigh", fallback.scatteringHigh()));
	}

	private static double read(JsonObject json, String key, double fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive()
			? json.get(key).getAsDouble() : fallback;
	}

	private static JsonObject toJson(AcousticMaterial material) {
		JsonObject json = new JsonObject();
		json.addProperty("low", material.low());
		json.addProperty("mid", material.mid());
		json.addProperty("high", material.high());
		json.addProperty("scatteringLow", material.scatteringLow());
		json.addProperty("scatteringMid", material.scatteringMid());
		json.addProperty("scatteringHigh", material.scatteringHigh());
		return json;
	}
}
