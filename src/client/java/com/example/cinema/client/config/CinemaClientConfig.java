package com.example.cinema.client.config;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.AcousticScale;
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
 * <p>Launch properties still win over the saved values. The Mod Menu screen
 * shows an active override instead of letting a saved value silently do
 * nothing.
 *
 * <p>Video settings here only control client-side decoding and upload. Output
 * resolution, scaling, colour and frame-rate processing belong to the future
 * AV Receiver Video Processing module.
 */
public final class CinemaClientConfig {
	public static final String SCREEN_MAX_SIZE_PROPERTY = "cinema.screen.maxSize";
	public static final String FFMPEG_PATH_PROPERTY = "cinema.ffmpeg.path";
	public static final String VIDEO_MAX_HEIGHT_PROPERTY = "cinema.video.maxHeight";
	public static final String VIDEO_GL_FINISH_PROPERTY = "cinema.video.glFinish";
	public static final String VIDEO_SHOWINFO_PROPERTY = "cinema.video.showinfo";
	public static final String FFMPEG_HWACCEL_PROPERTY = "cinema.ffmpeg.hwaccel";

	public static final int MIN_SCREEN_MAX_SIZE = 1;
	public static final int MAX_SCREEN_MAX_SIZE = 64;
	public static final int DEFAULT_SCREEN_MAX_SIZE = 16;

	/** Vanilla keeps the game running behind container-like screens. */
	public static final boolean DEFAULT_PAUSE_GAME_IN_DEVICE_GUIS = false;
	/** The mod adds this interaction on its own blocks. */
	public static final boolean DEFAULT_EMPTY_HAND_OPENS_DEVICE_GUIS = true;
	/** Only relevant when a {@code cinema.spike.*} test is active. */
	public static final boolean DEFAULT_SPIKE_MODIFIES_WORLD = true;

	/** Acoustic world scale; geometry and rendering stay in block units. */
	public static final double MIN_METERS_PER_BLOCK = AcousticScale.MIN_METERS_PER_BLOCK;
	public static final double MAX_METERS_PER_BLOCK = AcousticScale.MAX_METERS_PER_BLOCK;
	public static final double DEFAULT_METERS_PER_BLOCK =
		AcousticScale.DEFAULT_METERS_PER_BLOCK;
	public static final double METERS_PER_BLOCK_STEP = 0.05;

	/** ISO 9613-1 direct-path air absorption. */
	public static final boolean DEFAULT_AIR_ABSORPTION = true;
	public static final double MIN_AIR_TEMPERATURE_C = -20.0;
	public static final double MAX_AIR_TEMPERATURE_C = 50.0;
	public static final double DEFAULT_AIR_TEMPERATURE_C = 20.0;
	public static final double MIN_AIR_HUMIDITY_PERCENT = 0.0;
	public static final double MAX_AIR_HUMIDITY_PERCENT = 100.0;
	public static final double DEFAULT_AIR_HUMIDITY_PERCENT = 50.0;
	/** Geometric order-2/3/4 reflections; low-frequency modes remain A5's concern. */
	public static final boolean DEFAULT_LATE_REVERB = false;
	/**
	 * Late-bus audibility floor relative to the direct sound at the listener.
	 * -60 dB means the bus stops one RT60 after the direct arrival.
	 */
	public static final double MIN_LATE_FLOOR_DB = -80.0;
	public static final double MAX_LATE_FLOOR_DB = -20.0;
	public static final double DEFAULT_LATE_FLOOR_DB = -60.0;

	/** Reflection solver cadence and movement thresholds. */
	public static final int MIN_REFLECTION_UPDATE_MILLIS = 50;
	public static final int MAX_REFLECTION_UPDATE_MILLIS = 1000;
	public static final int DEFAULT_REFLECTION_UPDATE_MILLIS = 200;
	public static final int REFLECTION_UPDATE_MILLIS_STEP = 10;
	public static final double MIN_REFLECTION_MOVE_THRESHOLD_METERS = 0.05;
	public static final double MAX_REFLECTION_MOVE_THRESHOLD_METERS = 2.0;
	public static final double DEFAULT_REFLECTION_MOVE_THRESHOLD_METERS = 0.5;
	public static final double REFLECTION_MOVE_THRESHOLD_STEP = 0.05;

	/** Higher-order late-path solver cadence, step budget and stability window. */
	public static final int MIN_LATE_REFLECTION_UPDATE_MILLIS = 20;
	public static final int MAX_LATE_REFLECTION_UPDATE_MILLIS = 500;
	public static final int DEFAULT_LATE_REFLECTION_UPDATE_MILLIS = 50;
	public static final int LATE_REFLECTION_UPDATE_MILLIS_STEP = 10;
	public static final double MIN_LATE_PATH_MOVE_THRESHOLD_METERS = 0.05;
	public static final double MAX_LATE_PATH_MOVE_THRESHOLD_METERS = 1.0;
	public static final double DEFAULT_LATE_PATH_MOVE_THRESHOLD_METERS = 0.5;
	public static final double LATE_PATH_MOVE_THRESHOLD_STEP = 0.05;
	public static final int MIN_LATE_PATH_STEP_BUDGET_MILLIS = 1;
	public static final int MAX_LATE_PATH_STEP_BUDGET_MILLIS = 16;
	public static final int DEFAULT_LATE_PATH_STEP_BUDGET_MILLIS = 4;
	public static final int LATE_PATH_STEP_BUDGET_MILLIS_STEP = 1;
	public static final int MIN_LATE_PATH_MIN_STABLE_MILLIS = 50;
	public static final int MAX_LATE_PATH_MIN_STABLE_MILLIS = 500;
	public static final int DEFAULT_LATE_PATH_MIN_STABLE_MILLIS = 150;
	public static final int LATE_PATH_MIN_STABLE_MILLIS_STEP = 10;
	public static final int MIN_LATE_PATH_MAX_STABLE_MILLIS = 100;
	public static final int MAX_LATE_PATH_MAX_STABLE_MILLIS = 1500;
	public static final int DEFAULT_LATE_PATH_MAX_STABLE_MILLIS = 600;
	public static final int LATE_PATH_MAX_STABLE_MILLIS_STEP = 10;

	/** Feed-forward output limiter shared by the direct, reflection and wet buses. */
	public static final double MIN_LIMITER_CEILING_DB = -6.0;
	public static final double MAX_LIMITER_CEILING_DB = 0.0;
	public static final double DEFAULT_LIMITER_CEILING_DB = -0.3;
	public static final double LIMITER_CEILING_STEP_DB = 0.1;
	public static final double MIN_LIMITER_RELEASE_SECONDS = 0.05;
	public static final double MAX_LIMITER_RELEASE_SECONDS = 2.0;
	public static final double DEFAULT_LIMITER_RELEASE_SECONDS = 0.4;
	public static final double LIMITER_RELEASE_STEP_SECONDS = 0.05;

	/** 0 means "keep the source height"; values snap to 120-pixel steps. */
	public static final int MIN_VIDEO_MAX_HEIGHT = 0;
	public static final int MAX_VIDEO_MAX_HEIGHT = 2160;
	public static final int VIDEO_MAX_HEIGHT_STEP = 120;
	public static final int DEFAULT_VIDEO_MAX_HEIGHT_SDR = 1080;
	public static final int DEFAULT_VIDEO_MAX_HEIGHT_HDR = 720;
	public static final boolean DEFAULT_HARDWARE_DECODE = true;
	public static final boolean DEFAULT_FFMPEG_SHOW_INFO = false;
	public static final boolean DEFAULT_VIDEO_GL_FINISH = false;
	public static final int MAX_FFMPEG_PATH_LENGTH = 260;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int CONFIG_VERSION = 4;
	private static final CinemaClientConfig INSTANCE = load();
	private static final Integer SCREEN_MAX_SIZE_OVERRIDE = readScreenMaxSizeOverride();

	private int screenMaxSize = DEFAULT_SCREEN_MAX_SIZE;
	private boolean pauseGameInDeviceGuis = DEFAULT_PAUSE_GAME_IN_DEVICE_GUIS;
	private boolean emptyHandOpensDeviceGuis = DEFAULT_EMPTY_HAND_OPENS_DEVICE_GUIS;
	private boolean spikeModifiesWorld = DEFAULT_SPIKE_MODIFIES_WORLD;
	private double metersPerBlock = DEFAULT_METERS_PER_BLOCK;
	private boolean airAbsorption = DEFAULT_AIR_ABSORPTION;
	private double airTemperatureC = DEFAULT_AIR_TEMPERATURE_C;
	private double airHumidityPercent = DEFAULT_AIR_HUMIDITY_PERCENT;
	private boolean lateReverb = DEFAULT_LATE_REVERB;
	private double lateFloorDb = DEFAULT_LATE_FLOOR_DB;
	private int reflectionUpdateMillis = DEFAULT_REFLECTION_UPDATE_MILLIS;
	private double reflectionMoveThresholdMeters =
		DEFAULT_REFLECTION_MOVE_THRESHOLD_METERS;
	private int lateReflectionUpdateMillis = DEFAULT_LATE_REFLECTION_UPDATE_MILLIS;
	private double latePathMoveThresholdMeters =
		DEFAULT_LATE_PATH_MOVE_THRESHOLD_METERS;
	private int latePathStepBudgetMillis = DEFAULT_LATE_PATH_STEP_BUDGET_MILLIS;
	private int latePathMinStableMillis = DEFAULT_LATE_PATH_MIN_STABLE_MILLIS;
	private int latePathMaxStableMillis = DEFAULT_LATE_PATH_MAX_STABLE_MILLIS;
	private double limiterCeilingDb = DEFAULT_LIMITER_CEILING_DB;
	private double limiterReleaseSeconds = DEFAULT_LIMITER_RELEASE_SECONDS;
	private int videoMaxHeightSdr = DEFAULT_VIDEO_MAX_HEIGHT_SDR;
	private int videoMaxHeightHdr = DEFAULT_VIDEO_MAX_HEIGHT_HDR;
	private boolean hardwareDecode = DEFAULT_HARDWARE_DECODE;
	private boolean ffmpegShowInfo = DEFAULT_FFMPEG_SHOW_INFO;
	private boolean videoGlFinish = DEFAULT_VIDEO_GL_FINISH;
	private String ffmpegPath = "";

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

	public double metersPerBlock() {
		return metersPerBlock;
	}

	public void setMetersPerBlock(double value) {
		metersPerBlock = clampMetersPerBlock(value);
		AcousticScale.setMetersPerBlock(metersPerBlock);
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

	public boolean lateReverb() {
		return lateReverb;
	}

	public void setLateReverb(boolean value) {
		lateReverb = value;
	}

	public double lateFloorDb() {
		return lateFloorDb;
	}

	public void setLateFloorDb(double value) {
		lateFloorDb = clamp(value, MIN_LATE_FLOOR_DB, MAX_LATE_FLOOR_DB);
	}

	public int reflectionUpdateMillis() {
		return reflectionUpdateMillis;
	}

	public void setReflectionUpdateMillis(int value) {
		reflectionUpdateMillis = clamp(value, MIN_REFLECTION_UPDATE_MILLIS,
			MAX_REFLECTION_UPDATE_MILLIS);
	}

	public double reflectionMoveThresholdMeters() {
		return reflectionMoveThresholdMeters;
	}

	public void setReflectionMoveThresholdMeters(double value) {
		reflectionMoveThresholdMeters = clamp(value,
			MIN_REFLECTION_MOVE_THRESHOLD_METERS,
			MAX_REFLECTION_MOVE_THRESHOLD_METERS);
	}

	public int lateReflectionUpdateMillis() {
		return lateReflectionUpdateMillis;
	}

	public void setLateReflectionUpdateMillis(int value) {
		lateReflectionUpdateMillis = clamp(value,
			MIN_LATE_REFLECTION_UPDATE_MILLIS, MAX_LATE_REFLECTION_UPDATE_MILLIS);
	}

	public double latePathMoveThresholdMeters() {
		return latePathMoveThresholdMeters;
	}

	public void setLatePathMoveThresholdMeters(double value) {
		latePathMoveThresholdMeters = clamp(value,
			MIN_LATE_PATH_MOVE_THRESHOLD_METERS,
			MAX_LATE_PATH_MOVE_THRESHOLD_METERS);
	}

	public int latePathStepBudgetMillis() {
		return latePathStepBudgetMillis;
	}

	public void setLatePathStepBudgetMillis(int value) {
		latePathStepBudgetMillis = clamp(value,
			MIN_LATE_PATH_STEP_BUDGET_MILLIS, MAX_LATE_PATH_STEP_BUDGET_MILLIS);
	}

	public int latePathMinStableMillis() {
		return latePathMinStableMillis;
	}

	public void setLatePathMinStableMillis(int value) {
		latePathMinStableMillis = clamp(value, MIN_LATE_PATH_MIN_STABLE_MILLIS,
			MAX_LATE_PATH_MIN_STABLE_MILLIS);
	}

	public int latePathMaxStableMillis() {
		return latePathMaxStableMillis;
	}

	public void setLatePathMaxStableMillis(int value) {
		latePathMaxStableMillis = clamp(value, MIN_LATE_PATH_MAX_STABLE_MILLIS,
			MAX_LATE_PATH_MAX_STABLE_MILLIS);
	}

	public double limiterCeilingDb() {
		return limiterCeilingDb;
	}

	public void setLimiterCeilingDb(double value) {
		limiterCeilingDb = clamp(value, MIN_LIMITER_CEILING_DB,
			MAX_LIMITER_CEILING_DB);
	}

	public double limiterReleaseSeconds() {
		return limiterReleaseSeconds;
	}

	public void setLimiterReleaseSeconds(double value) {
		limiterReleaseSeconds = clamp(value, MIN_LIMITER_RELEASE_SECONDS,
			MAX_LIMITER_RELEASE_SECONDS);
	}

	public int videoMaxHeightSdr() {
		return videoMaxHeightSdr;
	}

	public void setVideoMaxHeightSdr(int value) {
		videoMaxHeightSdr = snapVideoMaxHeight(value);
	}

	public int videoMaxHeightHdr() {
		return videoMaxHeightHdr;
	}

	public void setVideoMaxHeightHdr(int value) {
		videoMaxHeightHdr = snapVideoMaxHeight(value);
	}

	public boolean hardwareDecode() {
		return hardwareDecode;
	}

	public void setHardwareDecode(boolean value) {
		hardwareDecode = value;
	}

	public boolean ffmpegShowInfo() {
		return ffmpegShowInfo;
	}

	public void setFfmpegShowInfo(boolean value) {
		ffmpegShowInfo = value;
	}

	public boolean videoGlFinish() {
		return videoGlFinish;
	}

	public void setVideoGlFinish(boolean value) {
		videoGlFinish = value;
	}

	public String ffmpegPath() {
		return ffmpegPath;
	}

	public void setFfmpegPath(String value) {
		String path = value == null ? "" : value.trim();
		if (path.length() > MAX_FFMPEG_PATH_LENGTH) {
			path = path.substring(0, MAX_FFMPEG_PATH_LENGTH);
		}
		ffmpegPath = path;
	}

	public synchronized void save() {
		JsonObject root = new JsonObject();
		root.addProperty("configVersion", CONFIG_VERSION);
		root.addProperty("screenMaxSize", screenMaxSize);
		root.addProperty("pauseGameInDeviceGuis", pauseGameInDeviceGuis);
		root.addProperty("emptyHandOpensDeviceGuis", emptyHandOpensDeviceGuis);
		root.addProperty("spikeModifiesWorld", spikeModifiesWorld);
		root.addProperty("metersPerBlock", metersPerBlock);
		root.addProperty("airAbsorption", airAbsorption);
		root.addProperty("airTemperatureC", airTemperatureC);
		root.addProperty("airHumidityPercent", airHumidityPercent);
		root.addProperty("lateReverb", lateReverb);
		root.addProperty("lateFloorDb", lateFloorDb);
		root.addProperty("reflectionUpdateMillis", reflectionUpdateMillis);
		root.addProperty("reflectionMoveThresholdMeters",
			reflectionMoveThresholdMeters);
		root.addProperty("lateReflectionUpdateMillis", lateReflectionUpdateMillis);
		root.addProperty("latePathMoveThresholdMeters",
			latePathMoveThresholdMeters);
		root.addProperty("latePathStepBudgetMillis", latePathStepBudgetMillis);
		root.addProperty("latePathMinStableMillis", latePathMinStableMillis);
		root.addProperty("latePathMaxStableMillis", latePathMaxStableMillis);
		root.addProperty("limiterCeilingDb", limiterCeilingDb);
		root.addProperty("limiterReleaseSeconds", limiterReleaseSeconds);
		root.addProperty("videoMaxHeightSdr", videoMaxHeightSdr);
		root.addProperty("videoMaxHeightHdr", videoMaxHeightHdr);
		root.addProperty("hardwareDecode", hardwareDecode);
		root.addProperty("ffmpegShowInfo", ffmpegShowInfo);
		root.addProperty("videoGlFinish", videoGlFinish);
		root.addProperty("ffmpegPath", ffmpegPath);
		Path file = configFile();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(root) + System.lineSeparator(),
				StandardCharsets.UTF_8);
			CinemaLog.info(CinemaLog.CONFIG, "saved {} (screenMaxSize={}, "
					+ "pauseGameInDeviceGuis={}, emptyHandOpensDeviceGuis={}, "
					+ "spikeModifiesWorld={}, metersPerBlock={}, airAbsorption={}, "
					+ "airTemperatureC={}, airHumidityPercent={}, lateReverb={}, "
					+ "lateFloorDb={}, reflectionUpdateMillis={}, "
					+ "reflectionMoveThresholdMeters={}, "
					+ "lateReflectionUpdateMillis={}, "
					+ "latePathMoveThresholdMeters={}, latePathStepBudgetMillis={}, "
					+ "latePathMinStableMillis={}, latePathMaxStableMillis={}, "
					+ "limiterCeilingDb={}, limiterReleaseSeconds={}, "
					+ "videoMaxHeightSdr={}, videoMaxHeightHdr={}, hardwareDecode={}, "
					+ "ffmpegShowInfo={}, videoGlFinish={}, ffmpegPath={})",
				file, screenMaxSize, pauseGameInDeviceGuis,
				emptyHandOpensDeviceGuis, spikeModifiesWorld, metersPerBlock,
				airAbsorption, airTemperatureC, airHumidityPercent, lateReverb,
				lateFloorDb, reflectionUpdateMillis,
				reflectionMoveThresholdMeters, lateReflectionUpdateMillis,
				latePathMoveThresholdMeters, latePathStepBudgetMillis,
				latePathMinStableMillis, latePathMaxStableMillis,
				limiterCeilingDb, limiterReleaseSeconds, videoMaxHeightSdr,
				videoMaxHeightHdr, hardwareDecode, ffmpegShowInfo,
				videoGlFinish, ffmpegPath);
		} catch (IOException e) {
			CinemaLog.warn(CinemaLog.CONFIG, "could not save {}: {}", file, e.getMessage());
		}
	}

	public static int clampScreenMaxSize(int value) {
		return Math.max(MIN_SCREEN_MAX_SIZE, Math.min(MAX_SCREEN_MAX_SIZE, value));
	}

	public static double clampMetersPerBlock(double value) {
		if (!Double.isFinite(value)) {
			return DEFAULT_METERS_PER_BLOCK;
		}
		return Math.max(MIN_METERS_PER_BLOCK,
			Math.min(MAX_METERS_PER_BLOCK, value));
	}

	public static int snapVideoMaxHeight(int value) {
		int clamped = Math.max(MIN_VIDEO_MAX_HEIGHT,
			Math.min(MAX_VIDEO_MAX_HEIGHT, value));
		int snapped = Math.round(clamped / (float) VIDEO_MAX_HEIGHT_STEP)
			* VIDEO_MAX_HEIGHT_STEP;
		return Math.max(MIN_VIDEO_MAX_HEIGHT,
			Math.min(MAX_VIDEO_MAX_HEIGHT, snapped));
	}

	/** Launch override for the FFmpeg folder or executable, or null. */
	public static String ffmpegPathOverride() {
		String value = System.getProperty(FFMPEG_PATH_PROPERTY);
		return value == null || value.isBlank() ? null : value;
	}

	/** Launch override for the video height cap, or null. */
	public static Integer videoMaxHeightOverride() {
		Integer value = readIntProperty(VIDEO_MAX_HEIGHT_PROPERTY);
		if (value == null) {
			return null;
		}
		int snapped = snapVideoMaxHeight(value);
		if (snapped != value) {
			CinemaLog.warn(CinemaLog.CONFIG, "{}={} is not a {} px step; using {}",
				VIDEO_MAX_HEIGHT_PROPERTY, value, VIDEO_MAX_HEIGHT_STEP, snapped);
		}
		return snapped;
	}

	/** Raw {@code -Dcinema.ffmpeg.hwaccel} value, or null. */
	public static String hardwareAccelOverride() {
		String value = System.getProperty(FFMPEG_HWACCEL_PROPERTY);
		return value == null || value.isBlank() ? null : value;
	}

	public static boolean showInfoOverride() {
		return System.getProperty(VIDEO_SHOWINFO_PROPERTY) != null;
	}

	public static boolean glFinishOverride() {
		return System.getProperty(VIDEO_GL_FINISH_PROPERTY) != null;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static CinemaClientConfig load() {
		CinemaClientConfig config = new CinemaClientConfig();
		// The acoustic layer has a static 1.00 default so pure unit tests do
		// not need FabricLoader. Publish the configured scale here.
		AcousticScale.setMetersPerBlock(config.metersPerBlock);
		Path file = configFile();
		if (!Files.isRegularFile(file)) {
			return config;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
				.getAsJsonObject();
			int version = root.has("configVersion")
				? root.get("configVersion").getAsInt() : 1;
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
			if (root.has("metersPerBlock")) {
				config.setMetersPerBlock(root.get("metersPerBlock").getAsDouble());
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
			if (version < 2) {
				// The former FDN late reverb failed listening acceptance.
				// Existing configs are migrated to the new default once; the
				// Mod Menu toggle can still enable the geometric late bus.
				config.lateReverb = false;
			} else if (root.has("lateReverb")) {
				config.lateReverb = root.get("lateReverb").getAsBoolean();
			}
			if (root.has("lateGainDb")) {
				CinemaLog.info(CinemaLog.CONFIG, "legacy lateGainDb is ignored; "
					+ "late level is computed from the room");
			}
			if (root.has("lateFloorDb")) {
				config.setLateFloorDb(root.get("lateFloorDb").getAsDouble());
			}
			if (root.has("reflectionUpdateMillis")) {
				config.setReflectionUpdateMillis(
					root.get("reflectionUpdateMillis").getAsInt());
			}
			if (root.has("reflectionMoveThresholdMeters")) {
				config.setReflectionMoveThresholdMeters(
					root.get("reflectionMoveThresholdMeters").getAsDouble());
			}
			if (root.has("lateReflectionUpdateMillis")) {
				config.setLateReflectionUpdateMillis(
					root.get("lateReflectionUpdateMillis").getAsInt());
			}
			if (root.has("latePathMoveThresholdMeters")) {
				config.setLatePathMoveThresholdMeters(
					root.get("latePathMoveThresholdMeters").getAsDouble());
			}
			if (root.has("latePathStepBudgetMillis")) {
				config.setLatePathStepBudgetMillis(
					root.get("latePathStepBudgetMillis").getAsInt());
			}
			if (root.has("latePathMinStableMillis")) {
				config.setLatePathMinStableMillis(
					root.get("latePathMinStableMillis").getAsInt());
			}
			if (root.has("latePathMaxStableMillis")) {
				config.setLatePathMaxStableMillis(
					root.get("latePathMaxStableMillis").getAsInt());
			}
			if (root.has("limiterCeilingDb")) {
				config.setLimiterCeilingDb(
					root.get("limiterCeilingDb").getAsDouble());
			}
			if (root.has("limiterReleaseSeconds")) {
				config.setLimiterReleaseSeconds(
					root.get("limiterReleaseSeconds").getAsDouble());
			}
			if (root.has("videoMaxHeightSdr")) {
				config.videoMaxHeightSdr = snapVideoMaxHeight(
					root.get("videoMaxHeightSdr").getAsInt());
			}
			if (root.has("videoMaxHeightHdr")) {
				config.videoMaxHeightHdr = snapVideoMaxHeight(
					root.get("videoMaxHeightHdr").getAsInt());
			}
			if (root.has("hardwareDecode")) {
				config.hardwareDecode = root.get("hardwareDecode").getAsBoolean();
			}
			if (root.has("ffmpegShowInfo")) {
				config.ffmpegShowInfo = root.get("ffmpegShowInfo").getAsBoolean();
			}
			if (root.has("videoGlFinish")) {
				config.videoGlFinish = root.get("videoGlFinish").getAsBoolean();
			}
			if (root.has("ffmpegPath")) {
				config.setFfmpegPath(root.get("ffmpegPath").getAsString());
			}
			if (version < 2) {
				CinemaLog.info(CinemaLog.CONFIG, "migrated to version {}; "
					+ "late reflections default is off", CONFIG_VERSION);
			} else if (version < CONFIG_VERSION) {
				CinemaLog.info(CinemaLog.CONFIG, "migrated to version {}; "
					+ "new video, diagnostic and world-scale settings use defaults",
					CONFIG_VERSION);
			}
			CinemaLog.info(CinemaLog.CONFIG, "loaded {} (screenMaxSize={}, "
					+ "pauseGameInDeviceGuis={}, emptyHandOpensDeviceGuis={}, "
					+ "spikeModifiesWorld={}, metersPerBlock={}, airAbsorption={}, "
					+ "airTemperatureC={}, airHumidityPercent={}, lateReverb={}, "
					+ "lateFloorDb={}, reflectionUpdateMillis={}, "
					+ "reflectionMoveThresholdMeters={}, "
					+ "lateReflectionUpdateMillis={}, "
					+ "latePathMoveThresholdMeters={}, latePathStepBudgetMillis={}, "
					+ "latePathMinStableMillis={}, latePathMaxStableMillis={}, "
					+ "limiterCeilingDb={}, limiterReleaseSeconds={}, "
					+ "videoMaxHeightSdr={}, videoMaxHeightHdr={}, hardwareDecode={}, "
					+ "ffmpegShowInfo={}, videoGlFinish={}, ffmpegPath={})",
				file, config.screenMaxSize, config.pauseGameInDeviceGuis,
				config.emptyHandOpensDeviceGuis, config.spikeModifiesWorld,
				config.metersPerBlock, config.airAbsorption,
				config.airTemperatureC,
				config.airHumidityPercent, config.lateReverb,
				config.lateFloorDb, config.reflectionUpdateMillis,
				config.reflectionMoveThresholdMeters,
				config.lateReflectionUpdateMillis,
				config.latePathMoveThresholdMeters,
				config.latePathStepBudgetMillis, config.latePathMinStableMillis,
				config.latePathMaxStableMillis, config.limiterCeilingDb,
				config.limiterReleaseSeconds, config.videoMaxHeightSdr,
				config.videoMaxHeightHdr, config.hardwareDecode,
				config.ffmpegShowInfo, config.videoGlFinish, config.ffmpegPath);
		} catch (Exception e) {
			CinemaLog.warn(CinemaLog.CONFIG, "could not read {}: {}", file, e.getMessage());
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
			CinemaLog.warn(CinemaLog.CONFIG, "{}={} is outside {}..{}; using {}",
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
			CinemaLog.warn(CinemaLog.CONFIG, "invalid {}='{}'; using the saved config",
				name, raw);
			return null;
		}
	}
}
