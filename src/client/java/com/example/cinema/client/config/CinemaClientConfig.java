package com.example.cinema.client.config;

import com.example.cinema.Cinema;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-local Cinema settings saved to {@code config/cinema.json}.
 *
 * <p>The screen-size launch property still wins over the saved value. The Mod
 * Menu screen shows an active override instead of letting a saved value
 * silently do nothing.
 */
public final class CinemaClientConfig {
	public static final String SCREEN_MAX_SIZE_PROPERTY = "cinema.screen.maxSize";

	public static final int MIN_SCREEN_MAX_SIZE = 1;
	public static final int MAX_SCREEN_MAX_SIZE = 64;
	public static final int DEFAULT_SCREEN_MAX_SIZE = 16;

	/** Vanilla keeps the game running behind container-like screens. */
	public static final boolean DEFAULT_PAUSE_GAME_IN_DEVICE_GUIS = false;
	/** The mod adds this interaction on its own blocks. */
	public static final boolean DEFAULT_EMPTY_HAND_OPENS_DEVICE_GUIS = true;
	/** Only relevant when a {@code cinema.spike.*} test is active. */
	public static final boolean DEFAULT_SPIKE_MODIFIES_WORLD = true;

	/** ISO 9613-1 direct-path air absorption. */
	public static final boolean DEFAULT_AIR_ABSORPTION = true;
	public static final double MIN_AIR_TEMPERATURE_C = -20.0;
	public static final double MAX_AIR_TEMPERATURE_C = 50.0;
	public static final double DEFAULT_AIR_TEMPERATURE_C = 20.0;
	public static final double MIN_AIR_HUMIDITY_PERCENT = 0.0;
	public static final double MAX_AIR_HUMIDITY_PERCENT = 100.0;
	public static final double DEFAULT_AIR_HUMIDITY_PERCENT = 50.0;
	/** Debug only; no audible effect until early reflections are implemented. */
	public static final double MIN_EARLY_GAIN_DB = -24.0;
	public static final double MAX_EARLY_GAIN_DB = 12.0;
	public static final double DEFAULT_EARLY_GAIN_DB = 0.0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final CinemaClientConfig INSTANCE = load();
	private static final Integer SCREEN_MAX_SIZE_OVERRIDE = readScreenMaxSizeOverride();

	private int screenMaxSize = DEFAULT_SCREEN_MAX_SIZE;
	private boolean pauseGameInDeviceGuis = DEFAULT_PAUSE_GAME_IN_DEVICE_GUIS;
	private boolean emptyHandOpensDeviceGuis = DEFAULT_EMPTY_HAND_OPENS_DEVICE_GUIS;
	private boolean spikeModifiesWorld = DEFAULT_SPIKE_MODIFIES_WORLD;
	private boolean airAbsorption = DEFAULT_AIR_ABSORPTION;
	private double airTemperatureC = DEFAULT_AIR_TEMPERATURE_C;
	private double airHumidityPercent = DEFAULT_AIR_HUMIDITY_PERCENT;
	private double earlyGainDb = DEFAULT_EARLY_GAIN_DB;

	private CinemaClientConfig() {
	}

	public static CinemaClientConfig get() {
		return INSTANCE;
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cinema.json");
	}

	/** Launch override for the screen size, or null when the config is used. */
	public static Integer screenMaxSizeOverride() {
		return SCREEN_MAX_SIZE_OVERRIDE;
	}

	public int screenMaxSize() {
		return screenMaxSize;
	}

	public void setScreenMaxSize(int value) {
		screenMaxSize = clampScreenMaxSize(value);
	}

	public boolean pauseGameInDeviceGuis() {
		return pauseGameInDeviceGuis;
	}

	public void setPauseGameInDeviceGuis(boolean value) {
		pauseGameInDeviceGuis = value;
	}

	public boolean emptyHandOpensDeviceGuis() {
		return emptyHandOpensDeviceGuis;
	}

	public void setEmptyHandOpensDeviceGuis(boolean value) {
		emptyHandOpensDeviceGuis = value;
	}

	public boolean spikeModifiesWorld() {
		return spikeModifiesWorld;
	}

	public void setSpikeModifiesWorld(boolean value) {
		spikeModifiesWorld = value;
	}

	public boolean airAbsorption() {
		return airAbsorption;
	}

	public void setAirAbsorption(boolean value) {
		airAbsorption = value;
	}

	public double airTemperatureC() {
		return airTemperatureC;
	}

	public void setAirTemperatureC(double value) {
		airTemperatureC = clamp(value, MIN_AIR_TEMPERATURE_C,
			MAX_AIR_TEMPERATURE_C);
	}

	public double airHumidityPercent() {
		return airHumidityPercent;
	}

	public void setAirHumidityPercent(double value) {
		airHumidityPercent = clamp(value, MIN_AIR_HUMIDITY_PERCENT,
			MAX_AIR_HUMIDITY_PERCENT);
	}

	public double earlyGainDb() {
		return earlyGainDb;
	}

	public void setEarlyGainDb(double value) {
		earlyGainDb = clamp(value, MIN_EARLY_GAIN_DB, MAX_EARLY_GAIN_DB);
	}

	public synchronized void save() {
		JsonObject root = new JsonObject();
		root.addProperty("screenMaxSize", screenMaxSize);
		root.addProperty("pauseGameInDeviceGuis", pauseGameInDeviceGuis);
		root.addProperty("emptyHandOpensDeviceGuis", emptyHandOpensDeviceGuis);
		root.addProperty("spikeModifiesWorld", spikeModifiesWorld);
		root.addProperty("airAbsorption", airAbsorption);
		root.addProperty("airTemperatureC", airTemperatureC);
		root.addProperty("airHumidityPercent", airHumidityPercent);
		root.addProperty("earlyGainDb", earlyGainDb);
		Path file = configFile();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root) + System.lineSeparator(),
				StandardCharsets.UTF_8);
			Cinema.LOGGER.info(
				"cinema config: saved {} (screenMaxSize={}, pauseGameInDeviceGuis={}, "
					+ "emptyHandOpensDeviceGuis={}, spikeModifiesWorld={}, "
					+ "airAbsorption={}, airTemperatureC={}, airHumidityPercent={}, "
					+ "earlyGainDb={})",
				file, screenMaxSize, pauseGameInDeviceGuis,
				emptyHandOpensDeviceGuis, spikeModifiesWorld,
				airAbsorption, airTemperatureC, airHumidityPercent, earlyGainDb);
		} catch (IOException e) {
			Cinema.LOGGER.warn("cinema config: could not save {}: {}", file, e.getMessage());
		}
	}

	public static int clampScreenMaxSize(int value) {
		return Math.max(MIN_SCREEN_MAX_SIZE, Math.min(MAX_SCREEN_MAX_SIZE, value));
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static CinemaClientConfig load() {
		CinemaClientConfig config = new CinemaClientConfig();
		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			return config;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
				.getAsJsonObject();
			if (root.has("screenMaxSize")) {
				config.screenMaxSize = clampScreenMaxSize(root.get("screenMaxSize").getAsInt());
			}
			if (root.has("pauseGameInDeviceGuis")) {
				config.pauseGameInDeviceGuis = root.get("pauseGameInDeviceGuis").getAsBoolean();
			}
			if (root.has("emptyHandOpensDeviceGuis")) {
				config.emptyHandOpensDeviceGuis =
					root.get("emptyHandOpensDeviceGuis").getAsBoolean();
			}
			if (root.has("spikeModifiesWorld")) {
				config.spikeModifiesWorld = root.get("spikeModifiesWorld").getAsBoolean();
			}
			if (root.has("airAbsorption")) {
				config.airAbsorption = root.get("airAbsorption").getAsBoolean();
			}
			if (root.has("airTemperatureC")) {
				config.airTemperatureC = clamp(
					root.get("airTemperatureC").getAsDouble(),
					MIN_AIR_TEMPERATURE_C, MAX_AIR_TEMPERATURE_C);
			}
			if (root.has("airHumidityPercent")) {
				config.airHumidityPercent = clamp(
					root.get("airHumidityPercent").getAsDouble(),
					MIN_AIR_HUMIDITY_PERCENT, MAX_AIR_HUMIDITY_PERCENT);
			}
			if (root.has("earlyGainDb")) {
				config.earlyGainDb = clamp(root.get("earlyGainDb").getAsDouble(),
					MIN_EARLY_GAIN_DB, MAX_EARLY_GAIN_DB);
			}
			Cinema.LOGGER.info(
				"cinema config: loaded {} (screenMaxSize={}, pauseGameInDeviceGuis={}, "
					+ "emptyHandOpensDeviceGuis={}, spikeModifiesWorld={}, "
					+ "airAbsorption={}, airTemperatureC={}, airHumidityPercent={}, "
					+ "earlyGainDb={})",
				file, config.screenMaxSize, config.pauseGameInDeviceGuis,
				config.emptyHandOpensDeviceGuis, config.spikeModifiesWorld,
				config.airAbsorption, config.airTemperatureC,
				config.airHumidityPercent, config.earlyGainDb);
		} catch (Exception e) {
			Cinema.LOGGER.warn("cinema config: could not read {}: {}", file, e.getMessage());
		}
		return config;
	}

	private static Integer readScreenMaxSizeOverride() {
		Integer value = readIntProperty(SCREEN_MAX_SIZE_PROPERTY);
		if (value == null) {
			return null;
		}
		int clamped = clampScreenMaxSize(value);
		if (clamped != value) {
			Cinema.LOGGER.warn("cinema config: {}={} is outside {}..{}; using {}",
				SCREEN_MAX_SIZE_PROPERTY, value, MIN_SCREEN_MAX_SIZE, MAX_SCREEN_MAX_SIZE, clamped);
		}
		return clamped;
	}

	private static Integer readIntProperty(String name) {
		String raw = System.getProperty(name);
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			Cinema.LOGGER.warn("cinema config: invalid {}='{}'; using the saved config",
				name, raw);
			return null;
		}
	}
}
