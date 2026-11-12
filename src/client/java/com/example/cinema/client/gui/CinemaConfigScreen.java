package com.example.cinema.client.gui;

import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.audio.acoustics.scene.AcousticScene;
import com.example.cinema.client.media.ffmpeg.Ffmpeg;
import com.example.cinema.client.render.ProjectorOptics;
import com.example.cinema.client.render.ScreenLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Client-local Cinema settings opened from Mod Menu.
 *
 * <p>Settings are split into pages because the screen, acoustics, video decode
 * and diagnostic switches no longer fit in one two-column panel. Every change
 * saves immediately; sliders are debounced briefly and flushed when the screen
 * closes.
 *
 * <p>Video controls here only affect client-side decoding and texture upload.
 * Output resolution, scaling, colour and frame-rate processing stay in the
 * future AV Receiver Video Processing module.
 */
public final class CinemaConfigScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 500;
	private static final int PANEL_MAX_HEIGHT = 320;
	private static final int ROW_HEIGHT = 18;
	private static final int ROW_SPACING = 22;
	private static final int TAB_HEIGHT = 18;
	private static final int TAB_GAP = 2;
	private static final long SAVE_DEBOUNCE_MS = 250L;
	private static final long STATUS_MILLIS = 8_000L;

	private final Screen parent;
	private final Map<AbstractWidget, String> tooltips = new IdentityHashMap<>();

	private Tab tab = Tab.SCREEN;
	private boolean closing;
	private boolean savePending;
	private long saveAtMillis;
	private String statusMessage = "";
	private long statusUntil;

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentTop;
	private int footerY;
	private int tabY;
	private int tabWidth;

	private int draftScreenMaxSize;
	private boolean draftPauseGameInDeviceGuis;
	private boolean draftEmptyHandOpensDeviceGuis;
	private boolean draftSpikeModifiesWorld;
	private double draftMetersPerBlock;
	private boolean draftAirAbsorption;
	private double draftAirTemperatureC;
	private double draftAirHumidityPercent;
	private double draftEarlyGainDb;
	private boolean draftLateReverb;
	private double draftLateTrimDb;
	private int draftVideoMaxHeightSdr;
	private int draftVideoMaxHeightHdr;
	private boolean draftHardwareDecode;
	private boolean draftFfmpegShowInfo;
	private boolean draftVideoGlFinish;
	private String draftFfmpegPath;

	private EditBox ffmpegPathBox;

	public CinemaConfigScreen(Screen parent) {
		super(Component.literal("Cinema Settings"));
		this.parent = parent;
		CinemaClientConfig config = CinemaClientConfig.get();
		draftScreenMaxSize = config.screenMaxSize();
		draftPauseGameInDeviceGuis = config.pauseGameInDeviceGuis();
		draftEmptyHandOpensDeviceGuis = config.emptyHandOpensDeviceGuis();
		draftSpikeModifiesWorld = config.spikeModifiesWorld();
		draftMetersPerBlock = config.metersPerBlock();
		draftAirAbsorption = config.airAbsorption();
		draftAirTemperatureC = config.airTemperatureC();
		draftAirHumidityPercent = config.airHumidityPercent();
		draftEarlyGainDb = config.earlyGainDb();
		draftLateReverb = config.lateReverb();
		draftLateTrimDb = config.lateTrimDb();
		draftVideoMaxHeightSdr = config.videoMaxHeightSdr();
		draftVideoMaxHeightHdr = config.videoMaxHeightHdr();
		draftHardwareDecode = config.hardwareDecode();
		draftFfmpegShowInfo = config.ffmpegShowInfo();
		draftVideoGlFinish = config.videoGlFinish();
		draftFfmpegPath = config.ffmpegPath();
	}

	@Override
	protected void init() {
		super.init();
		tooltips.clear();
		panelWidth = Math.min(width - 12, PANEL_MAX_WIDTH);
		panelHeight = Math.min(height - 12, PANEL_MAX_HEIGHT);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		tabY = top + 22;
		contentTop = top + 50;
		footerY = top + panelHeight - 24;

		Tab[] tabs = Tab.values();
		tabWidth = (contentWidth - (tabs.length - 1) * TAB_GAP) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab value = tabs[i];
			int x = contentLeft + i * (tabWidth + TAB_GAP);
			addRenderableWidget(Button.builder(Component.literal(value.label),
					button -> switchTab(value))
				.bounds(x, tabY, tabWidth, TAB_HEIGHT).build());
		}

		switch (tab) {
			case SCREEN -> buildScreenTab();
			case AUDIO -> buildAudioTab();
			case VIDEO -> buildVideoTab();
			case BEHAVIOR -> buildBehaviorTab();
			case ADVANCED -> buildAdvancedTab();
		}

		addRenderableWidget(Button.builder(Component.literal("Defaults"),
				button -> resetDefaults())
			.bounds(contentLeft, footerY, 70, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Close"),
				button -> onClose())
			.bounds(contentLeft + contentWidth - 60, footerY, 60, ROW_HEIGHT).build());
	}

	private void switchTab(Tab newTab) {
		if (tab == newTab) {
			return;
		}
		tab = newTab;
		rebuildWidgets();
	}

	private void buildScreenTab() {
		ScreenSizeSlider slider = new ScreenSizeSlider(contentLeft, contentTop,
			contentWidth, draftScreenMaxSize);
		if (ScreenLimits.hasPropertyOverride()) {
			slider.active = false;
		}
		addRenderableWidget(slider);
		tooltip(slider, "Maximum rendered screen size in blocks. "
			+ "The launch property -D" + ScreenLimits.MAX_SIZE_PROPERTY
			+ " still overrides this value.");
	}

	private void buildAudioTab() {
		int y = contentTop;
		ValueSlider worldScale = valueSlider(contentLeft, y, contentWidth,
			"World scale: %.2f m/block", CinemaClientConfig.MIN_METERS_PER_BLOCK,
			CinemaClientConfig.MAX_METERS_PER_BLOCK,
			CinemaClientConfig.METERS_PER_BLOCK_STEP, draftMetersPerBlock,
			value -> draftMetersPerBlock = value);
		tooltip(worldScale, "Acoustic world scale. 1.00 m/block is Minecraft's "
			+ "block size; 0.25-0.50 approximates the movement and gravity scale. "
			+ "It changes room volume, RT60, air absorption and reflection delays, "
			+ "not the block grid or rendering.");
		y += ROW_SPACING;
		Button air = toggle(contentLeft, y, contentWidth, "Air absorption",
			() -> draftAirAbsorption, value -> draftAirAbsorption = value);
		tooltip(air, "ISO 9613-1 frequency-dependent air absorption on the direct "
			+ "path. Temperature and humidity below set the absorption curve.");
		y += ROW_SPACING;
		ValueSlider temperature = valueSlider(contentLeft, y, contentWidth,
			"Temperature: %.1f °C", CinemaClientConfig.MIN_AIR_TEMPERATURE_C,
			CinemaClientConfig.MAX_AIR_TEMPERATURE_C, 0.5, draftAirTemperatureC,
			value -> draftAirTemperatureC = value);
		tooltip(temperature, "Air temperature used by ISO 9613-1. "
			+ "20 °C and 50 % RH is the reference.");
		y += ROW_SPACING;
		ValueSlider humidity = valueSlider(contentLeft, y, contentWidth,
			"Humidity: %.0f %%", CinemaClientConfig.MIN_AIR_HUMIDITY_PERCENT,
			CinemaClientConfig.MAX_AIR_HUMIDITY_PERCENT, 1.0,
			draftAirHumidityPercent, value -> draftAirHumidityPercent = value);
		tooltip(humidity, "Relative humidity used by ISO 9613-1. Higher humidity "
			+ "moves absorption toward higher frequencies.");
		y += ROW_SPACING;
		ValueSlider early = valueSlider(contentLeft, y, contentWidth,
			"Early gain (debug): %+.1f dB", CinemaClientConfig.MIN_EARLY_GAIN_DB,
			CinemaClientConfig.MAX_EARLY_GAIN_DB, 0.5, draftEarlyGainDb,
			value -> draftEarlyGainDb = value);
		tooltip(early, "Debug-only early reflection gain. The physical "
			+ "early-reflection level comes from distance, material and directivity.");
		y += ROW_SPACING;
		Button late = toggle(contentLeft, y, contentWidth, "Late reflections",
			() -> draftLateReverb, value -> draftLateReverb = value);
		tooltip(late, "Eight-line full-band late field. Only a 20 Hz DC high-pass is "
			+ "applied; LFE is included, and room modes remain A5's concern.");
		y += ROW_SPACING;
		ValueSlider lateTrim = valueSlider(contentLeft, y, contentWidth,
			"Late trim: %+.1f dB", CinemaClientConfig.MIN_LATE_TRIM_DB,
			CinemaClientConfig.MAX_LATE_TRIM_DB, 0.5, draftLateTrimDb,
			value -> draftLateTrimDb = value);
		tooltip(lateTrim, "Trim added to the computed source-power late gain. "
			+ "The dry and early-reflection paths are unchanged.");
	}

	private void buildVideoTab() {
		int y = contentTop;
		HeightSlider sdr = new HeightSlider(contentLeft, y, contentWidth,
			"SDR max height", draftVideoMaxHeightSdr,
			value -> draftVideoMaxHeightSdr = value);
		addRenderableWidget(sdr);
		tooltip(sdr, "Maximum decoded height for SDR video. Source keeps the file's "
			+ "native height. Lower values reduce CPU and upload cost.");
		y += ROW_SPACING;
		HeightSlider hdr = new HeightSlider(contentLeft, y, contentWidth,
			"HDR max height", draftVideoMaxHeightHdr,
			value -> draftVideoMaxHeightHdr = value);
		addRenderableWidget(hdr);
		tooltip(hdr, "Maximum decoded height for HDR video before tone mapping. "
			+ "The 720p default keeps the HDR path cheap.");
		if (CinemaClientConfig.videoMaxHeightOverride() != null) {
			sdr.active = false;
			hdr.active = false;
		}
		y += ROW_SPACING;
		Button hardware = toggle(contentLeft, y, contentWidth, "Hardware decode",
			() -> draftHardwareDecode, value -> draftHardwareDecode = value);
		tooltip(hardware, "Uses FFmpeg -hwaccel auto when on. Turn off if the "
			+ "driver produces corrupt frames or crashes.");
		String override = CinemaClientConfig.hardwareAccelOverride();
		if (override != null) {
			hardware.active = false;
			hardware.setMessage(Component.literal("Hardware decode: JVM " + override));
		}
	}

	private void buildBehaviorTab() {
		int y = contentTop;
		Button pause = toggle(contentLeft, y, contentWidth, "Pause in device GUIs",
			() -> draftPauseGameInDeviceGuis, value -> draftPauseGameInDeviceGuis = value);
		tooltip(pause, "Off keeps integrated-server playback running while a device "
			+ "GUI is open.");
		y += ROW_SPACING;
		Button emptyHand = toggle(contentLeft, y, contentWidth,
			"Empty hand opens GUIs", () -> draftEmptyHandOpensDeviceGuis,
			value -> draftEmptyHandOpensDeviceGuis = value);
		tooltip(emptyHand, "Off restores vanilla empty-hand behavior. Held items "
			+ "always keep vanilla interaction.");
	}

	private void buildAdvancedTab() {
		int y = contentTop;
		ffmpegPathBox = new EditBox(font, contentLeft, y, contentWidth - 62,
			ROW_HEIGHT, Component.literal("FFmpeg path"));
		ffmpegPathBox.setHint(Component.literal("FFmpeg folder or executable"));
		ffmpegPathBox.setMaxLength(CinemaClientConfig.MAX_FFMPEG_PATH_LENGTH);
		ffmpegPathBox.setValue(draftFfmpegPath);
		ffmpegPathBox.setResponder(value -> {
			draftFfmpegPath = value;
			scheduleSave();
		});
		addRenderableWidget(ffmpegPathBox);
		tooltip(ffmpegPathBox, "Folder containing ffmpeg.exe/ffprobe.exe, or the "
			+ "ffmpeg executable itself. Blank = auto-detect under the game folder. "
			+ "-D" + CinemaClientConfig.FFMPEG_PATH_PROPERTY + " overrides this.");
		addRenderableWidget(Button.builder(Component.literal("Check"),
				button -> checkFfmpeg())
			.bounds(contentLeft + contentWidth - 56, y, 56, ROW_HEIGHT).build());

		y += ROW_SPACING;
		Button showInfo = toggle(contentLeft, y, contentWidth,
			"FFmpeg info logs", () -> draftFfmpegShowInfo,
			value -> draftFfmpegShowInfo = value);
		tooltip(showInfo, "Adds the FFmpeg showinfo filter and info-level stderr. "
			+ "Useful for frame timing and pixel format diagnosis; slower.");
		if (CinemaClientConfig.showInfoOverride()) {
			showInfo.active = false;
			showInfo.setMessage(Component.literal("FFmpeg info logs: JVM "
				+ (Boolean.parseBoolean(System.getProperty(
					CinemaClientConfig.VIDEO_SHOWINFO_PROPERTY)) ? "ON" : "OFF")));
		}

		y += ROW_SPACING;
		Button glFinish = toggle(contentLeft, y, contentWidth,
			"glFinish uploads", () -> draftVideoGlFinish,
			value -> draftVideoGlFinish = value);
		tooltip(glFinish, "Synchronously finishes every texture upload. Only for "
			+ "driver-crash diagnosis; it costs a lot of frame time.");
		if (CinemaClientConfig.glFinishOverride()) {
			glFinish.active = false;
			glFinish.setMessage(Component.literal("glFinish uploads: JVM "
				+ (Boolean.parseBoolean(System.getProperty(
					CinemaClientConfig.VIDEO_GL_FINISH_PROPERTY)) ? "ON" : "OFF")));
		}

		y += ROW_SPACING;
		Button spike = toggle(contentLeft, y, contentWidth,
			"Spike modifies world", () -> draftSpikeModifiesWorld,
			value -> draftSpikeModifiesWorld = value);
		tooltip(spike, "Only used by cinema.spike.* tests. Off leaves time, weather, "
			+ "game rules and mobs untouched.");
	}

	private Button toggle(int x, int y, int width, String label,
						  BooleanSupplier getter, Consumer<Boolean> setter) {
		Button button = Button.builder(toggleLabel(label, getter.getAsBoolean()), b -> {
			setter.accept(!getter.getAsBoolean());
			b.setMessage(toggleLabel(label, getter.getAsBoolean()));
			saveNow();
		}).bounds(x, y, width, ROW_HEIGHT).build();
		addRenderableWidget(button);
		return button;
	}

	private ValueSlider valueSlider(int x, int y, int width, String format,
									double min, double max, double step, double actual,
									DoubleConsumer setter) {
		ValueSlider slider = new ValueSlider(x, y, width, format, min, max, step,
			actual, setter);
		addRenderableWidget(slider);
		return slider;
	}

	private void tooltip(AbstractWidget widget, String text) {
		if (widget != null) {
			tooltips.put(widget, text);
		}
	}

	private static Component toggleLabel(String label, boolean value) {
		return Component.literal(label + ": " + (value ? "ON" : "OFF"));
	}

	private void resetDefaults() {
		draftScreenMaxSize = CinemaClientConfig.DEFAULT_SCREEN_MAX_SIZE;
		draftPauseGameInDeviceGuis = CinemaClientConfig.DEFAULT_PAUSE_GAME_IN_DEVICE_GUIS;
		draftEmptyHandOpensDeviceGuis =
			CinemaClientConfig.DEFAULT_EMPTY_HAND_OPENS_DEVICE_GUIS;
		draftSpikeModifiesWorld = CinemaClientConfig.DEFAULT_SPIKE_MODIFIES_WORLD;
		draftMetersPerBlock = CinemaClientConfig.DEFAULT_METERS_PER_BLOCK;
		draftAirAbsorption = CinemaClientConfig.DEFAULT_AIR_ABSORPTION;
		draftAirTemperatureC = CinemaClientConfig.DEFAULT_AIR_TEMPERATURE_C;
		draftAirHumidityPercent = CinemaClientConfig.DEFAULT_AIR_HUMIDITY_PERCENT;
		draftEarlyGainDb = CinemaClientConfig.DEFAULT_EARLY_GAIN_DB;
		draftLateReverb = CinemaClientConfig.DEFAULT_LATE_REVERB;
		draftLateTrimDb = CinemaClientConfig.DEFAULT_LATE_TRIM_DB;
		draftVideoMaxHeightSdr = CinemaClientConfig.DEFAULT_VIDEO_MAX_HEIGHT_SDR;
		draftVideoMaxHeightHdr = CinemaClientConfig.DEFAULT_VIDEO_MAX_HEIGHT_HDR;
		draftHardwareDecode = CinemaClientConfig.DEFAULT_HARDWARE_DECODE;
		draftFfmpegShowInfo = CinemaClientConfig.DEFAULT_FFMPEG_SHOW_INFO;
		draftVideoGlFinish = CinemaClientConfig.DEFAULT_VIDEO_GL_FINISH;
		draftFfmpegPath = "";
		saveNow();
		rebuildWidgets();
	}

	private void checkFfmpeg() {
		saveNow();
		setStatus("Checking FFmpeg...");
		Thread thread = new Thread(() -> {
			String result;
			try {
				result = "FFmpeg: " + Ffmpeg.version();
			} catch (Exception e) {
				result = "FFmpeg not usable: " + e.getMessage();
			}
			String finalResult = result;
			Minecraft.getInstance().execute(() -> setStatus(finalResult));
		}, "cinema-ffmpeg-check");
		thread.setDaemon(true);
		thread.start();
	}

	private void setStatus(String message) {
		statusMessage = message == null ? "" : message;
		statusUntil = System.currentTimeMillis() + STATUS_MILLIS;
	}

	private void scheduleSave() {
		savePending = true;
		saveAtMillis = System.currentTimeMillis() + SAVE_DEBOUNCE_MS;
	}

	private void saveNow() {
		savePending = false;
		CinemaClientConfig config = CinemaClientConfig.get();
		String path = draftFfmpegPath == null ? "" : draftFfmpegPath.trim();
		boolean ffmpegPathChanged = !path.equals(config.ffmpegPath());
		double previousMetersPerBlock = config.metersPerBlock();
		config.setScreenMaxSize(draftScreenMaxSize);
		config.setPauseGameInDeviceGuis(draftPauseGameInDeviceGuis);
		config.setEmptyHandOpensDeviceGuis(draftEmptyHandOpensDeviceGuis);
		config.setSpikeModifiesWorld(draftSpikeModifiesWorld);
		config.setMetersPerBlock(draftMetersPerBlock);
		config.setAirAbsorption(draftAirAbsorption);
		config.setAirTemperatureC(draftAirTemperatureC);
		config.setAirHumidityPercent(draftAirHumidityPercent);
		config.setEarlyGainDb(draftEarlyGainDb);
		config.setLateReverb(draftLateReverb);
		config.setLateTrimDb(draftLateTrimDb);
		config.setVideoMaxHeightSdr(draftVideoMaxHeightSdr);
		config.setVideoMaxHeightHdr(draftVideoMaxHeightHdr);
		config.setHardwareDecode(draftHardwareDecode);
		config.setFfmpegShowInfo(draftFfmpegShowInfo);
		config.setVideoGlFinish(draftVideoGlFinish);
		config.setFfmpegPath(path);
		config.save();
		if (ffmpegPathChanged) {
			Ffmpeg.invalidate();
		}
		if (Math.abs(config.metersPerBlock() - previousMetersPerBlock) > 1.0e-9) {
			AcousticScene.invalidate();
		}
		ProjectorOptics.clearCache();
	}

	@Override
	public void tick() {
		super.tick();
		if (savePending && System.currentTimeMillis() >= saveAtMillis) {
			saveNow();
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE0101010);
		graphics.fill(left, top, left + panelWidth, top + 1, 0xFF707070);
		graphics.fill(left, top + panelHeight - 1, left + panelWidth, top + panelHeight, 0xFF707070);
		graphics.fill(left, top, left + 1, top + panelHeight, 0xFF404040);
		graphics.fill(left + panelWidth - 1, top, left + panelWidth, top + panelHeight, 0xFF404040);
		graphics.drawString(font, title, left + 10, top + 7, 0xE8E8E8, false);

		int accentX = contentLeft + tab.ordinal() * (tabWidth + TAB_GAP);
		graphics.fill(accentX, tabY + TAB_HEIGHT, accentX + tabWidth,
			tabY + TAB_HEIGHT + 2, 0xFFFFC107);

		drawTabNotes(graphics);
		graphics.drawString(font, truncate("Saved to " + CinemaClientConfig.configFile(),
				contentWidth), contentLeft, footerY - 12, 0x707070, false);
	}

	private void drawTabNotes(GuiGraphics graphics) {
		switch (tab) {
			case SCREEN -> {
				int y = contentTop + 28;
				graphics.drawString(font, truncate("Applies after closing this screen. "
					+ "Larger sizes cost more to render.", contentWidth),
					contentLeft, y, 0x9A9A9A, false);
				Integer override = ScreenLimits.propertyOverride();
				if (override != null) {
					graphics.drawString(font, truncate("Screen size overridden by -D"
							+ ScreenLimits.MAX_SIZE_PROPERTY + "=" + override,
							contentWidth),
						contentLeft, y + 11, 0xFFD54F, false);
				}
			}
			case AUDIO -> {
				int y = contentTop + ROW_SPACING * 7 + 4;
				graphics.drawString(font, truncate("Air absorption uses ISO 9613-1 "
					+ "at the listener position.", contentWidth),
					contentLeft, y, 0x9A9A9A, false);
				graphics.drawString(font, truncate("Late level is computed from the "
					+ "room; the slider is a trim.", contentWidth),
					contentLeft, y + 11, 0x9A9A9A, false);
				graphics.drawString(font, truncate("World scale only changes "
					+ "acoustics; geometry and rendering stay in blocks.", contentWidth),
					contentLeft, y + 22, 0x9A9A9A, false);
			}
			case VIDEO -> {
				int y = contentTop + ROW_SPACING * 3 + 4;
				graphics.drawString(font, truncate("Client decode only. Changes "
					+ "apply to the next playback.", contentWidth),
					contentLeft, y, 0x9A9A9A, false);
				graphics.drawString(font, truncate("Output resolution/processing "
					+ "belongs to AV Receiver Video Processing.", contentWidth),
					contentLeft, y + 11, 0x9A9A9A, false);
			}
			case BEHAVIOR -> {
				int y = contentTop + ROW_SPACING * 2 + 4;
				graphics.drawString(font, truncate("Pause off keeps integrated-server "
					+ "playback running behind device GUIs.", contentWidth),
					contentLeft, y, 0x9A9A9A, false);
			}
			case ADVANCED -> {
				int y = contentTop + ROW_SPACING * 4 + 4;
				graphics.drawString(font, truncate("FFmpeg path is a folder or the "
					+ "executable. Blank = auto-detect.", contentWidth),
					contentLeft, y, 0x9A9A9A, false);
				graphics.drawString(font, truncate("Changes apply to the next "
					+ "playback; Check tests the saved path.", contentWidth),
					contentLeft, y + 11, 0x9A9A9A, false);
				if (System.currentTimeMillis() < statusUntil && !statusMessage.isEmpty()) {
					graphics.drawString(font, truncate(statusMessage, contentWidth),
						contentLeft, y + 22, 0xFFD54F, false);
				}
			}
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		for (Map.Entry<AbstractWidget, String> entry : tooltips.entrySet()) {
			if (entry.getKey().isMouseOver(mouseX, mouseY)) {
				renderSettingTooltip(graphics, entry.getValue(), mouseX, mouseY);
				break;
			}
		}
	}

	private void renderSettingTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
		graphics.renderTooltip(font,
			font.split(Component.literal(text), Math.max(80, contentWidth - 20)),
			mouseX, mouseY);
	}

	@Override
	public void onClose() {
		saveNow();
		closeToParent();
	}

	private void closeToParent() {
		if (closing) {
			return;
		}
		closing = true;
		if (parent != null && minecraft != null) {
			// Defer the parent switch so the mouse release that closed this
			// screen cannot also click a widget underneath it.
			Minecraft client = minecraft;
			Screen target = parent;
			client.execute(() -> client.setScreen(target));
		} else {
			super.onClose();
		}
	}

	private String truncate(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		int width = Math.max(10, maxWidth);
		if (font.width(text) <= width) {
			return text;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
	}

	private static double normalize(int actual, int min, int max) {
		if (max <= min) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (actual - min) / (double) (max - min)));
	}

	private static double normalize(double actual, double min, double max) {
		if (max <= min) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (actual - min) / (max - min)));
	}

	private final class ScreenSizeSlider extends AbstractSliderButton {
		private ScreenSizeSlider(int x, int y, int width, int actual) {
			super(x, y, width, ROW_HEIGHT, Component.empty(),
				normalize(actual, CinemaClientConfig.MIN_SCREEN_MAX_SIZE,
					CinemaClientConfig.MAX_SCREEN_MAX_SIZE));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			Integer override = ScreenLimits.propertyOverride();
			int value = override != null ? override : actual();
			String suffix = override != null ? " (JVM override)" : "";
			setMessage(Component.literal("Screen max size: " + value + "×" + value + suffix));
		}

		@Override
		protected void applyValue() {
			draftScreenMaxSize = actual();
			scheduleSave();
			updateMessage();
		}

		private int actual() {
			int min = CinemaClientConfig.MIN_SCREEN_MAX_SIZE;
			int max = CinemaClientConfig.MAX_SCREEN_MAX_SIZE;
			int value = (int) Math.round(min + this.value * (max - min));
			return Math.max(min, Math.min(max, value));
		}
	}

	/** Decode height slider; 0 means the source height. */
	private final class HeightSlider extends AbstractSliderButton {
		private final String label;
		private final Consumer<Integer> setter;
		private int actual;

		private HeightSlider(int x, int y, int width, String label, int actual,
							 Consumer<Integer> setter) {
			super(x, y, width, ROW_HEIGHT, Component.empty(),
				normalize(CinemaClientConfig.snapVideoMaxHeight(actual),
					CinemaClientConfig.MIN_VIDEO_MAX_HEIGHT,
					CinemaClientConfig.MAX_VIDEO_MAX_HEIGHT));
			this.label = label;
			this.actual = CinemaClientConfig.snapVideoMaxHeight(actual);
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			Integer override = CinemaClientConfig.videoMaxHeightOverride();
			int shown = override != null ? override : actual;
			String suffix = override != null ? " (JVM override)" : "";
			setMessage(Component.literal(label + ": "
				+ (shown <= 0 ? "Source" : shown + "p") + suffix));
		}

		@Override
		protected void applyValue() {
			int min = CinemaClientConfig.MIN_VIDEO_MAX_HEIGHT;
			int max = CinemaClientConfig.MAX_VIDEO_MAX_HEIGHT;
			int step = CinemaClientConfig.VIDEO_MAX_HEIGHT_STEP;
			int raw = (int) Math.round(min + this.value * (max - min));
			int snapped = Math.round(raw / (float) step) * step;
			actual = Math.max(min, Math.min(max, snapped));
			setter.accept(actual);
			scheduleSave();
			updateMessage();
		}
	}

	/**
	 * Shared slider for numeric acoustics values. The message format receives
	 * one double; a null label means the superclass constructor is still
	 * running, so the message is left empty until the fields are initialized.
	 */
	private final class ValueSlider extends AbstractSliderButton {
		private final String format;
		private final double min;
		private final double max;
		private final double step;
		private final DoubleConsumer setter;
		private String label;
		private double actual;

		private ValueSlider(int x, int y, int width, String format, double min,
							double max, double step, double actual,
							DoubleConsumer setter) {
			super(x, y, width, ROW_HEIGHT, Component.empty(),
				normalize(actual, min, max));
			this.format = format;
			this.min = min;
			this.max = max;
			this.step = step;
			this.actual = Math.max(min, Math.min(max, actual));
			this.setter = setter;
			this.label = format;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			if (label == null) {
				return;
			}
			setMessage(Component.literal(String.format(Locale.ROOT, format, actual)));
		}

		@Override
		protected void applyValue() {
			double raw = min + this.value * (max - min);
			double snapped = Math.round(raw / step) * step;
			actual = Math.max(min, Math.min(max, snapped));
			setter.accept(actual);
			scheduleSave();
			updateMessage();
		}
	}

	private enum Tab {
		SCREEN("Screen"),
		AUDIO("Audio"),
		VIDEO("Video"),
		BEHAVIOR("Behavior"),
		ADVANCED("Advanced");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}
}
