package com.example.cinema.client.gui;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.ScreenBlockEntity;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.AvReceiverPlayback;
import com.example.cinema.client.media.AvReceiverPlaybackManager;
import com.example.cinema.client.media.CinemaPlayer;
import com.example.cinema.client.network.DeviceNameClient;
import com.example.cinema.client.network.ProjectorSettingsClient;
import com.example.cinema.client.render.ProjectorOptics;
import com.example.cinema.client.render.ScreenGeometry;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.util.CinemaNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Server-authoritative projector panel.
 *
 * <p>M1 status (paired screen, lamp, AV signal) plus M2 optical/wash controls
 * and M3 simulation switches. Changes apply immediately: slider changes are
 * debounced, button changes are sent at once, and pausing follows the Mod Menu
 * behavior setting (off by default so video keeps playing).
 */
public final class ProjectorConfigScreen extends Screen {
	private static final double DEFAULT_THROW_RATIO = 1.75;
	private static final double DEFAULT_FOCUS_DISTANCE = 8.0;
	private static final double DEFAULT_BRIGHTNESS = 1.0;
	private static final double DEFAULT_AMBIENT_WASH = 0.18;
	private static final long NAME_SAVE_DEBOUNCE_MS = 250L;

	private final ProjectorBlockEntity projector;
	private final Screen parent;
	private boolean throwAuto;
	private boolean focusAuto;
	private double draftThrowRatio;
	private double draftFocusDistance;
	private double draftKeystoneVerticalDegrees;
	private double draftKeystoneHorizontalDegrees;
	private double draftBrightness;
	private double draftAmbientWash;
	private boolean draftPowered;
	private boolean draftSimFocus;
	private boolean draftSimKeystone;
	private boolean draftSimWash;
	private boolean draftSimWarmup;
	private double lastManualFocus = DEFAULT_FOCUS_DISTANCE;
	private double screenDistance;
	private double resolvedThrowRatio = DEFAULT_THROW_RATIO;
	private boolean closing;
	private boolean dirty;
	private long lastChangeMillis;

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentBottom;

	private final List<String> statusLines = new ArrayList<>();
	private EditBox projectorNameBox;
	private EditBox screenNameBox;
	private boolean projectorNameDirty;
	private boolean screenNameDirty;
	private long projectorNameDirtyAtMillis;
	private long screenNameDirtyAtMillis;
	private boolean syncingNames;
	private ProjectorSlider throwSlider;
	private ProjectorSlider focusSlider;
	private ProjectorSlider keystoneVerticalDegreesSlider;
	private ProjectorSlider keystoneHorizontalDegreesSlider;
	private ProjectorSlider brightnessSlider;
	private ProjectorSlider ambientWashSlider;
	private Button powerButton;
	private Button throwAutoButton;
	private Button focusAutoButton;
	private Button simFocusButton;
	private Button simKeystoneButton;
	private Button simWashButton;
	private Button simWarmupButton;

	public ProjectorConfigScreen(ProjectorBlockEntity projector) {
		this(projector, null);
	}

	public ProjectorConfigScreen(ProjectorBlockEntity projector, Screen parent) {
		super(Component.literal("Cinema Projector"));
		this.projector = projector;
		this.parent = parent;
		loadDraft();
	}

	private void loadDraft() {
		throwAuto = projector.throwRatio() < 0.0;
		draftThrowRatio = throwAuto ? DEFAULT_THROW_RATIO : projector.throwRatio();
		focusAuto = projector.focusDistance() < 0.0;
		draftFocusDistance = focusAuto ? DEFAULT_FOCUS_DISTANCE : projector.focusDistance();
		lastManualFocus = draftFocusDistance;
		draftKeystoneVerticalDegrees = projector.keystoneVerticalDegrees();
		draftKeystoneHorizontalDegrees = projector.keystoneHorizontalDegrees();
		draftBrightness = projector.brightness();
		draftAmbientWash = projector.ambientWash();
		draftPowered = projector.isPowered();
		draftSimFocus = projector.isSimFocus();
		draftSimKeystone = projector.isSimKeystone();
		draftSimWash = projector.isSimWash();
		draftSimWarmup = projector.isSimWarmup();
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, 420);
		panelHeight = Math.min(height - 12, 250);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		contentBottom = top + panelHeight - 20;

		powerButton = Button.builder(powerLabel(), button -> {
			draftPowered = !draftPowered;
			powerButton.setMessage(powerLabel());
			markDirtyImmediate();
		}).bounds(contentLeft + contentWidth - 84, top + 4, 84, 16).build();
		addRenderableWidget(powerButton);

		int half = (contentWidth - 4) / 2;
		int rightX = contentLeft + half + 4;
		int nameY = top + 56;

		projectorNameBox = new EditBox(font, contentLeft + 62, nameY, half - 62, 16,
			Component.literal("Projector name"));
		projectorNameBox.setMaxLength(CinemaNames.MAX_LENGTH);
		projectorNameBox.setHint(Component.literal("Projector name"));
		syncingNames = true;
		projectorNameBox.setValue(projector.name());
		syncingNames = false;
		projectorNameBox.setResponder(value -> onNameChanged(true));
		addRenderableWidget(projectorNameBox);

		screenNameBox = new EditBox(font, rightX + 46, nameY, half - 46, 16,
			Component.literal("Screen name"));
		screenNameBox.setMaxLength(CinemaNames.MAX_LENGTH);
		syncingNames = true;
		screenNameBox.setValue(linkedScreenName());
		syncingNames = false;
		screenNameBox.setResponder(value -> onNameChanged(false));
		addRenderableWidget(screenNameBox);
		syncNameWidgets();

		int row1 = top + 78;
		int row2 = row1 + 20;
		int row3 = row2 + 20;

		throwAutoButton = Button.builder(throwAutoLabel(), button -> toggleThrowAuto())
			.bounds(contentLeft, row1, 64, 18).build();
		addRenderableWidget(throwAutoButton);
		throwSlider = addSlider(contentLeft + 66, row1, half - 66,
			ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO,
			draftThrowRatio, 0.01,
			value -> {
				draftThrowRatio = value;
				throwAuto = false;
				if (throwAutoButton != null) {
					throwAutoButton.setMessage(throwAutoLabel());
				}
				markDirty();
			},
			value -> throwAuto
				? (screenDistance > 0.5
					? String.format(Locale.ROOT, "Throw: auto · %.2f", resolvedThrowRatio)
					: "Throw: auto")
				: String.format(Locale.ROOT, "Throw ratio %.2f", value));
		keystoneVerticalDegreesSlider = addSlider(rightX, row1, half,
			ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES,
			draftKeystoneVerticalDegrees, 1.0,
			value -> {
				draftKeystoneVerticalDegrees = value;
				markDirty();
			},
			value -> String.format(Locale.ROOT, "Keystone V %+.1f°", value));

		focusAutoButton = Button.builder(focusAutoLabel(), button -> toggleFocusAuto())
			.bounds(contentLeft, row2, 64, 18).build();
		addRenderableWidget(focusAutoButton);
		focusSlider = addSlider(contentLeft + 66, row2, half - 66,
			ProjectorBlockEntity.MIN_FOCUS_DISTANCE, ProjectorBlockEntity.MAX_FOCUS_DISTANCE,
			draftFocusDistance, 0.05,
			value -> {
				draftFocusDistance = value;
				lastManualFocus = value;
				focusAuto = false;
				if (focusAutoButton != null) {
					focusAutoButton.setMessage(focusAutoLabel());
				}
				markDirty();
			},
			value -> focusAuto
				? (screenDistance > 0.5
					? String.format(Locale.ROOT, "Focus: auto · %.2f m", screenDistance)
					: "Focus: auto")
				: String.format(Locale.ROOT, "Focus %.2f m", value));
		keystoneHorizontalDegreesSlider = addSlider(rightX, row2, half,
			ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES,
			draftKeystoneHorizontalDegrees, 1.0,
			value -> {
				draftKeystoneHorizontalDegrees = value;
				markDirty();
			},
			value -> String.format(Locale.ROOT, "Keystone H %+.1f°", value));

		brightnessSlider = addSlider(contentLeft, row3, half,
			ProjectorBlockEntity.MIN_BRIGHTNESS, ProjectorBlockEntity.MAX_BRIGHTNESS,
			draftBrightness, 0.01,
			value -> {
				draftBrightness = value;
				markDirty();
			},
			value -> String.format(Locale.ROOT, "Brightness %.0f%%", value * 100.0));
		ambientWashSlider = addSlider(rightX, row3, half,
			ProjectorBlockEntity.MIN_AMBIENT_WASH, ProjectorBlockEntity.MAX_AMBIENT_WASH,
			draftAmbientWash, 0.01,
			value -> {
				draftAmbientWash = value;
				markDirty();
			},
			value -> String.format(Locale.ROOT, "Wash %.0f%%", value * 100.0));

		int simY = top + 152;
		int simWidth = (contentWidth - 6) / 4;
		simFocusButton = addSimButton(contentLeft, simY, simWidth, "Focus",
			() -> draftSimFocus, value -> draftSimFocus = value);
		simKeystoneButton = addSimButton(contentLeft + simWidth + 2, simY, simWidth, "Keystone",
			() -> draftSimKeystone, value -> draftSimKeystone = value);
		simWashButton = addSimButton(contentLeft + (simWidth + 2) * 2, simY, simWidth, "Wash",
			() -> draftSimWash, value -> draftSimWash = value);
		simWarmupButton = addSimButton(contentLeft + (simWidth + 2) * 3, simY, simWidth, "Warmup",
			() -> draftSimWarmup, value -> draftSimWarmup = value);

		addRenderableWidget(Button.builder(Component.literal("Defaults"), button -> resetDefaults())
			.bounds(contentLeft, contentBottom, 70, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Pairing..."),
				button -> minecraft.setScreen(new RemotePairingScreen(this, projector.getBlockPos(),
					RemoteBinding.Kind.PROJECTOR)))
			.bounds(contentLeft + contentWidth - 140, contentBottom, 76, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, contentBottom, 60, 18).build());

		syncSliders();
		refreshStatus();
	}

	private ProjectorSlider addSlider(int x, int y, int width, double min, double max,
									  double actual, double step,
									  DoubleConsumer consumer,
									  DoubleFunction<String> formatter) {
		ProjectorSlider slider = new ProjectorSlider(x, y, width, min, max, actual, step,
			consumer, formatter);
		addRenderableWidget(slider);
		return slider;
	}

	private void syncSliders() {
		if (throwSlider == null) {
			return;
		}
		throwSlider.setActual(throwAuto ? resolvedThrowRatio : draftThrowRatio);
		throwSlider.active = !throwAuto;
		focusSlider.setActual(draftFocusDistance);
		focusSlider.active = !focusAuto;
		keystoneVerticalDegreesSlider.setActual(draftKeystoneVerticalDegrees);
		keystoneHorizontalDegreesSlider.setActual(draftKeystoneHorizontalDegrees);
		brightnessSlider.setActual(draftBrightness);
		ambientWashSlider.setActual(draftAmbientWash);
		powerButton.setMessage(powerLabel());
		throwAutoButton.setMessage(throwAutoLabel());
		focusAutoButton.setMessage(focusAutoLabel());
		simFocusButton.setMessage(simLabel("Focus", draftSimFocus));
		simKeystoneButton.setMessage(simLabel("Keystone", draftSimKeystone));
		simWashButton.setMessage(simLabel("Wash", draftSimWash));
		simWarmupButton.setMessage(simLabel("Warmup", draftSimWarmup));
	}

	private Component powerLabel() {
		return Component.literal(draftPowered ? "Power ON" : "Power OFF");
	}

	private Component throwAutoLabel() {
		return Component.literal(throwAuto ? "T Auto ON" : "T Auto OFF");
	}

	private Component focusAutoLabel() {
		return Component.literal(focusAuto ? "F Auto ON" : "F Auto OFF");
	}

	private void toggleThrowAuto() {
		throwAuto = !throwAuto;
		if (!throwAuto) {
			draftThrowRatio = resolvedThrowRatio;
		}
		syncSliders();
		markDirtyImmediate();
	}

	private void toggleFocusAuto() {
		focusAuto = !focusAuto;
		if (!focusAuto) {
			draftFocusDistance = lastManualFocus;
		}
		syncSliders();
		markDirtyImmediate();
	}

	private Button addSimButton(int x, int y, int width, String label,
								java.util.function.BooleanSupplier getter,
								java.util.function.Consumer<Boolean> setter) {
		Button button = Button.builder(simLabel(label, getter.getAsBoolean()), b -> {
			boolean next = !getter.getAsBoolean();
			setter.accept(next);
			b.setMessage(simLabel(label, next));
			markDirtyImmediate();
		}).bounds(x, y, width, 18).build();
		addRenderableWidget(button);
		return button;
	}

	private Component simLabel(String label, boolean enabled) {
		return Component.literal(label + (enabled ? " ON" : " OFF"));
	}

	private void resetDefaults() {
		throwAuto = true;
		draftThrowRatio = DEFAULT_THROW_RATIO;
		draftFocusDistance = screenDistance > 0.5 ? screenDistance : DEFAULT_FOCUS_DISTANCE;
		lastManualFocus = draftFocusDistance;
		focusAuto = true;
		draftKeystoneVerticalDegrees = 0.0;
		draftKeystoneHorizontalDegrees = 0.0;
		draftBrightness = DEFAULT_BRIGHTNESS;
		draftAmbientWash = DEFAULT_AMBIENT_WASH;
		draftPowered = true;
		draftSimFocus = true;
		draftSimKeystone = true;
		draftSimWash = true;
		draftSimWarmup = true;
		syncSliders();
		markDirtyImmediate();
	}

	private void refreshStatus() {
		statusLines.clear();
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			statusLines.add("Screen: unavailable");
			statusLines.add("Lamp: unavailable");
			statusLines.add("AV: unavailable");
			return;
		}

		float aspect = 16.0f / 9.0f;
		BlockPos receiverPos = projector.receiverPos();
		AvReceiverPlayback playback = receiverPos == null ? null
			: AvReceiverPlaybackManager.playbackAt(receiverPos);
		CinemaPlayer player = playback == null ? null : playback.player();
		if (player != null && player.aspect() > 0.01f) {
			aspect = player.aspect();
		}

		ProjectorOptics optics = ProjectorOptics.resolve(client.level, projector, aspect);
		boolean linkedScreen = projector.screenPos() != null;
		if (optics == null) {
			screenDistance = 0.0;
			resolvedThrowRatio = DEFAULT_THROW_RATIO;
			if (throwAuto && throwSlider != null) {
				throwSlider.setActual(resolvedThrowRatio);
			}
			statusLines.add(linkedScreen ? "Screen: linked target missing" : "Screen: not linked");
		} else {
			ScreenGeometry screen = optics.screen();
			screenDistance = optics.lens().distanceTo(optics.screenCenter());
			resolvedThrowRatio = optics.throwRatio();
			String screenName = linkedScreenName();
			String screenLabel = screenName.isEmpty() ? "" : screenName + " · ";
			statusLines.add(String.format(Locale.ROOT, "Screen: %s%d×%d @ %s · %.1f m · linked",
				screenLabel, screen.width(), screen.height(), screen.master().toShortString(),
				screenDistance));
			if (throwAuto && throwSlider != null) {
				throwSlider.setActual(resolvedThrowRatio);
			}
			if (focusAuto) {
				lastManualFocus = screenDistance;
				if (focusSlider != null) {
					focusSlider.setActual(screenDistance);
				}
			}
		}

		double lampPercent = projector.lampLevel() * 100.0;
		String lampState;
		if (projector.isPowered()) {
			lampState = projector.lampLevel() < 0.999f ? "warming" : "on";
		} else {
			lampState = projector.lampLevel() > 0.001f ? "cooling" : "off";
		}
		statusLines.add(String.format(Locale.ROOT, "Lamp: %.0f%% · %s", lampPercent, lampState));

		if (receiverPos == null) {
			statusLines.add("AV: not linked");
			return;
		}
		BlockEntity blockEntity = client.level.getBlockEntity(receiverPos);
		if (!(blockEntity instanceof AvReceiverBlockEntity receiver)
			|| !projector.getBlockPos().equals(receiver.videoTarget())) {
			statusLines.add("AV: link lost");
			return;
		}
		String state = receiver.state().name().toLowerCase(Locale.ROOT);
		String receiverLabel = receiver.name().isEmpty() ? "" : receiver.name() + " · ";
		if (player != null && player.isAudioOnly()) {
			statusLines.add("AV: " + receiverLabel + state + " · audio only");
		} else if (player != null) {
			statusLines.add(String.format(Locale.ROOT, "AV: %s%s · %d×%d",
				receiverLabel, state, player.width(), player.height()));
		} else {
			statusLines.add("AV: " + receiverLabel + state + " · no signal");
		}
	}

	@Override
	public void tick() {
		if (projector.isRemoved()) {
			onClose();
			return;
		}
		flushIfDue();
		if (minecraft != null && minecraft.level != null
			&& minecraft.level.getGameTime() % 10L == 0L) {
			refreshStatus();
			syncNameWidgets();
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
		graphics.drawString(font, title, left + 10, top + 6, 0xE8E8E8, false);

		for (int i = 0; i < statusLines.size(); i++) {
			String line = statusLines.get(i);
			boolean warning = (i == 0 && (line.contains("not linked") || line.contains("missing")))
				|| (i == 1 && (line.contains("warming") || line.contains("cooling")))
				|| (i == 2 && (line.contains("not linked") || line.contains("link lost")));
			int color = warning ? 0xFFD54F : 0x9A9A9A;
			graphics.drawString(font, truncate(line, contentWidth), contentLeft,
				top + 22 + i * 11, color, false);
		}
		int half = (contentWidth - 4) / 2;
		graphics.drawString(font, "Projector", contentLeft, top + 60, 0xB8B8B8, false);
		graphics.drawString(font, "Screen", contentLeft + half + 4, top + 60, 0xB8B8B8, false);
		graphics.drawString(font, "Simulation", contentLeft, top + 140, 0xB8B8B8, false);
		if (panelHeight >= 200) {
			graphics.drawString(font, "Changes apply immediately. Close when done.",
				contentLeft, contentBottom - 14, 0x707070, false);
		}
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		drawHoverTooltip(graphics, mouseX, mouseY);
	}

	private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (projectorNameBox != null && inBox(mouseX, mouseY, projectorNameBox.getX(),
			projectorNameBox.getY(), projectorNameBox.getWidth(), projectorNameBox.getHeight())) {
			renderWrappedTooltip(graphics,
				"Custom projector name. It is shown in status lines and commissioning labels. "
					+ "Max " + CinemaNames.MAX_LENGTH + " characters.",
				mouseX, mouseY);
			return;
		}
		if (screenNameBox != null && inBox(mouseX, mouseY, screenNameBox.getX(),
			screenNameBox.getY(), screenNameBox.getWidth(), screenNameBox.getHeight())) {
			renderWrappedTooltip(graphics,
				hasLinkedScreen()
					? "Custom name for the linked screen. It is applied to every connected screen cell."
					: "Link a screen with the Cinema Cable before naming it.",
				mouseX, mouseY);
			return;
		}
		if (powerButton != null && inBox(mouseX, mouseY, powerButton.getX(), powerButton.getY(),
			powerButton.getWidth(), powerButton.getHeight())) {
			renderWrappedTooltip(graphics,
				"Power off starts the lamp cool-down. The change is sent immediately.",
				mouseX, mouseY);
			return;
		}
		if (focusAutoButton != null && inBox(mouseX, mouseY, focusAutoButton.getX(),
			focusAutoButton.getY(), focusAutoButton.getWidth(), focusAutoButton.getHeight())) {
			renderWrappedTooltip(graphics,
				"Auto focus uses the distance to the paired screen. Turning it off keeps the last manual distance.",
				mouseX, mouseY);
			return;
		}
		if (throwAutoButton != null && inBox(mouseX, mouseY, throwAutoButton.getX(),
			throwAutoButton.getY(), throwAutoButton.getWidth(), throwAutoButton.getHeight())) {
			renderWrappedTooltip(graphics,
				"Auto throw ratio is derived from the paired screen distance and width.",
				mouseX, mouseY);
			return;
		}
		if (mouseY >= top + 146 && mouseY < top + 170
			&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth) {
			renderWrappedTooltip(graphics,
				"Simulation toggles only affect rendering: focus blur, keystone warp, ambient wash, and lamp warm-up. Changes apply immediately.",
				mouseX, mouseY);
		}
	}

	private void renderWrappedTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
		int maxWidth = Math.max(100, Math.min(280, width - 32));
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String line : text.split("\\n")) {
			lines.addAll(font.split(Component.literal(line), maxWidth));
		}
		graphics.renderTooltip(font, lines, mouseX, mouseY);
	}

	private boolean inBox(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	private void markDirty() {
		dirty = true;
		lastChangeMillis = System.currentTimeMillis();
	}

	private void markDirtyImmediate() {
		markDirty();
		flushNow();
	}

	private void flushIfDue() {
		if (dirty && System.currentTimeMillis() - lastChangeMillis >= 200L) {
			flushNow();
		}
		long now = System.currentTimeMillis();
		if ((projectorNameDirty && now >= projectorNameDirtyAtMillis)
			|| (screenNameDirty && now >= screenNameDirtyAtMillis)) {
			flushNames();
		}
	}

	private void onNameChanged(boolean projectorName) {
		if (syncingNames) {
			return;
		}
		long due = System.currentTimeMillis() + NAME_SAVE_DEBOUNCE_MS;
		if (projectorName) {
			projectorNameDirty = true;
			projectorNameDirtyAtMillis = due;
		} else {
			screenNameDirty = true;
			screenNameDirtyAtMillis = due;
		}
	}

	private void flushNames() {
		if (projectorNameDirty && projectorNameBox != null) {
			projectorNameDirty = false;
			DeviceNameClient.setProjectorName(projector.getBlockPos(), projectorNameBox.getValue());
		}
		if (screenNameDirty) {
			screenNameDirty = false;
			if (screenNameBox != null && hasLinkedScreen()) {
				DeviceNameClient.setLinkedScreenName(projector.getBlockPos(),
					screenNameBox.getValue());
			}
		}
	}

	private boolean hasLinkedScreen() {
		Minecraft client = Minecraft.getInstance();
		return client.level != null && projector.screenPos() != null
			&& client.level.getBlockEntity(projector.screenPos()) instanceof ScreenBlockEntity;
	}

	private String linkedScreenName() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || projector.screenPos() == null) {
			return "";
		}
		return client.level.getBlockEntity(projector.screenPos()) instanceof ScreenBlockEntity screen
			? screen.name() : "";
	}

	private void syncNameWidgets() {
		if (projectorNameBox != null && !projectorNameBox.isFocused() && !projectorNameDirty
			&& !projectorNameBox.getValue().equals(projector.name())) {
			syncingNames = true;
			projectorNameBox.setValue(projector.name());
			syncingNames = false;
		}
		if (screenNameBox == null) {
			return;
		}
		boolean linked = hasLinkedScreen();
		screenNameBox.setEditable(linked);
		screenNameBox.setHint(Component.literal(linked ? "Screen name" : "Screen not linked"));
		if (!linked) {
			screenNameDirty = false;
			if (!screenNameBox.getValue().isEmpty()) {
				syncingNames = true;
				screenNameBox.setValue("");
				syncingNames = false;
			}
			return;
		}
		String actual = linkedScreenName();
		if (!screenNameBox.isFocused() && !screenNameDirty
			&& !screenNameBox.getValue().equals(actual)) {
			syncingNames = true;
			screenNameBox.setValue(actual);
			syncingNames = false;
		}
	}

	private void flushNow() {
		if (!dirty) {
			return;
		}
		dirty = false;
		ProjectorSettingsClient.send(projector.getBlockPos(),
			throwAuto ? -1.0 : draftThrowRatio,
			focusAuto ? -1.0 : draftFocusDistance, draftKeystoneVerticalDegrees,
			draftKeystoneHorizontalDegrees, draftBrightness, draftAmbientWash,
			draftPowered, draftSimFocus, draftSimKeystone, draftSimWash, draftSimWarmup);
	}

	@Override
	public void onClose() {
		if (closing) {
			return;
		}
		closing = true;
		flushNow();
		flushNames();
		if (parent != null && minecraft != null) {
			Minecraft client = minecraft;
			Screen target = parent;
			client.execute(() -> client.setScreen(target));
		} else {
			super.onClose();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return CinemaClientConfig.get().pauseGameInDeviceGuis();
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

	private final class ProjectorSlider extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final double step;
		private final DoubleConsumer consumer;
		private final DoubleFunction<String> formatter;

		private ProjectorSlider(int x, int y, int width, double min, double max,
								double actual, double step, DoubleConsumer consumer,
								DoubleFunction<String> formatter) {
			super(x, y, width, 18, Component.empty(), normalize(actual, min, max));
			this.min = min;
			this.max = max;
			this.step = step;
			this.consumer = consumer;
			this.formatter = formatter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			if (formatter == null) {
				setMessage(Component.empty());
				return;
			}
			setMessage(Component.literal(formatter.apply(actual())));
		}

		@Override
		protected void applyValue() {
			double snapped = actual();
			if (step > 0.0) {
				snapped = Math.round(snapped / step) * step;
				snapped = Math.max(min, Math.min(max, snapped));
			}
			value = normalize(snapped, min, max);
			consumer.accept(snapped);
			updateMessage();
		}

		private double actual() {
			return min + value * (max - min);
		}

		private void setActual(double actual) {
			value = normalize(actual, min, max);
			updateMessage();
		}

		private void refreshMessage() {
			updateMessage();
		}
	}

	private static double normalize(double value, double min, double max) {
		if (!Double.isFinite(value) || max <= min) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
	}
}
