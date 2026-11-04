package com.example.cinema.client.gui;

import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.render.ProjectorOptics;
import com.example.cinema.client.render.ScreenLimits;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Client-local Cinema settings opened from Mod Menu.
 *
 * <p>Changes save immediately. The screen-size slider is debounced briefly and
 * flushed when the screen closes. The screen-size launch property still
 * overrides the saved value; the control is disabled and the override is shown
 * in the panel.
 *
 * <p>The behavior switches control the places where the mod changes vanilla
 * behavior: whether device GUIs pause the game, whether a bare hand opens
 * them, and whether spike tests modify the world.
 *
 * <p>Video output resolution/processing is intentionally not exposed here; it
 * belongs to the future AV Receiver Video Processing module.
 */
public final class CinemaConfigScreen extends Screen {
	private static final int PANEL_MAX_WIDTH = 420;
	private static final int PANEL_MAX_HEIGHT = 240;
	private static final int ROW_HEIGHT = 18;
	private static final int COMPACT_HEIGHT = 230;
	private static final long SAVE_DEBOUNCE_MS = 250L;

	private final Screen parent;
	private int draftScreenMaxSize;
	private boolean draftPauseGameInDeviceGuis;
	private boolean draftEmptyHandOpensDeviceGuis;
	private boolean draftSpikeModifiesWorld;
	private boolean closing;
	private boolean savePending;
	private long saveAtMillis;

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentBottom;
	private int screenLabelY;
	private int screenControlY;
	private int screenNoteY;
	private int behaviorLabelY;
	private int behaviorFirstRowY;
	private int noteY;
	private int pathY;
	private boolean compact;

	private ScreenSizeSlider screenSlider;
	private Button pauseButton;
	private Button emptyHandButton;
	private Button spikeButton;

	public CinemaConfigScreen(Screen parent) {
		super(Component.literal("Cinema Settings"));
		this.parent = parent;
		CinemaClientConfig config = CinemaClientConfig.get();
		draftScreenMaxSize = config.screenMaxSize();
		draftPauseGameInDeviceGuis = config.pauseGameInDeviceGuis();
		draftEmptyHandOpensDeviceGuis = config.emptyHandOpensDeviceGuis();
		draftSpikeModifiesWorld = config.spikeModifiesWorld();
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, PANEL_MAX_WIDTH);
		panelHeight = Math.min(height - 12, PANEL_MAX_HEIGHT);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		contentBottom = top + panelHeight - 24;
		compact = panelHeight < COMPACT_HEIGHT;

		if (compact) {
			screenLabelY = top + 22;
			screenControlY = top + 34;
			screenNoteY = -1;
			behaviorLabelY = top + 58;
			behaviorFirstRowY = top + 72;
			noteY = top + 138;
			pathY = -1;
		} else {
			screenLabelY = top + 24;
			screenControlY = top + 38;
			screenNoteY = top + 62;
			behaviorLabelY = top + 82;
			behaviorFirstRowY = top + 96;
			noteY = top + 174;
			pathY = contentBottom - 12;
		}

		screenSlider = new ScreenSizeSlider(contentLeft, screenControlY, contentWidth,
			draftScreenMaxSize);
		if (ScreenLimits.hasPropertyOverride()) {
			screenSlider.active = false;
		}
		addRenderableWidget(screenSlider);

		pauseButton = addRenderableWidget(toggle(contentLeft, behaviorFirstRowY,
			"Pause game in device GUIs", () -> draftPauseGameInDeviceGuis,
			value -> draftPauseGameInDeviceGuis = value));
		emptyHandButton = addRenderableWidget(toggle(contentLeft, behaviorFirstRowY + 20,
			"Empty hand opens device GUIs", () -> draftEmptyHandOpensDeviceGuis,
			value -> draftEmptyHandOpensDeviceGuis = value));
		spikeButton = addRenderableWidget(toggle(contentLeft, behaviorFirstRowY + 40,
			"Spike tests modify world", () -> draftSpikeModifiesWorld,
			value -> draftSpikeModifiesWorld = value));

		addRenderableWidget(Button.builder(Component.literal("Defaults"), button -> resetDefaults())
			.bounds(contentLeft, contentBottom, 70, ROW_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, contentBottom, 60, ROW_HEIGHT).build());
	}

	private Button toggle(int x, int y, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		return Button.builder(toggleLabel(label, getter.getAsBoolean()), button -> {
			setter.accept(!getter.getAsBoolean());
			button.setMessage(toggleLabel(label, getter.getAsBoolean()));
			saveNow();
		}).bounds(x, y, contentWidth, ROW_HEIGHT).build();
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
		if (screenSlider != null) {
			screenSlider.setActual(draftScreenMaxSize);
		}
		if (pauseButton != null) {
			pauseButton.setMessage(toggleLabel("Pause game in device GUIs",
				draftPauseGameInDeviceGuis));
		}
		if (emptyHandButton != null) {
			emptyHandButton.setMessage(toggleLabel("Empty hand opens device GUIs",
				draftEmptyHandOpensDeviceGuis));
		}
		if (spikeButton != null) {
			spikeButton.setMessage(toggleLabel("Spike tests modify world",
				draftSpikeModifiesWorld));
		}
		saveNow();
	}

	private void scheduleSave() {
		savePending = true;
		saveAtMillis = System.currentTimeMillis() + SAVE_DEBOUNCE_MS;
	}

	private void saveNow() {
		savePending = false;
		CinemaClientConfig config = CinemaClientConfig.get();
		config.setScreenMaxSize(draftScreenMaxSize);
		config.setPauseGameInDeviceGuis(draftPauseGameInDeviceGuis);
		config.setEmptyHandOpensDeviceGuis(draftEmptyHandOpensDeviceGuis);
		config.setSpikeModifiesWorld(draftSpikeModifiesWorld);
		config.save();
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

		graphics.drawString(font, "Screen", contentLeft, screenLabelY, 0xFFFFFF, false);
		if (screenNoteY >= 0) {
			graphics.drawString(font, truncate(
					"Applies after closing this screen. Larger sizes cost more to render.",
					contentWidth),
				contentLeft, screenNoteY, 0x9A9A9A, false);
		}

		graphics.drawString(font, "Behavior", contentLeft, behaviorLabelY, 0xFFFFFF, false);

		int y = noteY;
		boolean override = false;
		Integer screenOverride = ScreenLimits.propertyOverride();
		if (screenOverride != null) {
			graphics.drawString(font, truncate("Screen size overridden by -D"
					+ ScreenLimits.MAX_SIZE_PROPERTY + "=" + screenOverride, contentWidth),
				contentLeft, y, 0xFFD54F, false);
			y += 11;
			override = true;
		}
		if (!override) {
			graphics.drawString(font, truncate(
					"Changes save immediately. Close when done.",
					contentWidth),
				contentLeft, y, 0x9A9A9A, false);
		}
		if (!compact) {
			graphics.drawString(font, truncate("Saved to " + CinemaClientConfig.configFile(),
					contentWidth), contentLeft, pathY, 0x707070, false);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return true;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		if (isOver(pauseButton, mouseX, mouseY)) {
			renderSettingTooltip(graphics,
				"Off keeps integrated-server playback running while a device GUI is open.",
				mouseX, mouseY);
		} else if (isOver(emptyHandButton, mouseX, mouseY)) {
			renderSettingTooltip(graphics,
				"Off restores vanilla empty-hand behavior. Held items always keep vanilla interaction.",
				mouseX, mouseY);
		} else if (isOver(spikeButton, mouseX, mouseY)) {
			renderSettingTooltip(graphics,
				"Only used by cinema.spike.* tests. Off leaves time, weather, game rules and mobs untouched.",
				mouseX, mouseY);
		}
	}

	private static boolean isOver(Button button, int mouseX, int mouseY) {
		return button != null && button.isMouseOver(mouseX, mouseY);
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

	private static double normalize(int actual) {
		int min = CinemaClientConfig.MIN_SCREEN_MAX_SIZE;
		int max = CinemaClientConfig.MAX_SCREEN_MAX_SIZE;
		if (max <= min) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (actual - min) / (double) (max - min)));
	}

	private final class ScreenSizeSlider extends AbstractSliderButton {
		private ScreenSizeSlider(int x, int y, int width, int actual) {
			super(x, y, width, ROW_HEIGHT, Component.empty(), normalize(actual));
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

		private void setActual(int actual) {
			value = normalize(actual);
			updateMessage();
		}
	}
}
