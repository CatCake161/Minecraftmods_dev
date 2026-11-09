package com.example.cinema.client.gui;

import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.audio.fir.CommonAcousticFirService;
import com.example.cinema.client.audio.speakerdata.SpeakerDataAssignments;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.network.SpeakerNameClient;
import com.example.cinema.client.network.SpeakerRoleClient;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * GUI for client-local speaker data profiles.
 *
 * <p>Profiles are loaded from {@code <game>/cinema/speaker_data/profiles/}.
 * Drop a JSON profile onto this screen to import it. Changes save immediately;
 * Close flushes any debounced field edit. Use Defaults to restore the selected
 * profile's defaults. The profile button opens a paged picker so every profile
 * for the selected cabinet type is directly selectable.
 */
public final class SpeakerDataConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 16;
	private static final int TYPE_ROW_HEIGHT = 18;
	private static final long FIELD_SAVE_DEBOUNCE_MS = 250L;

	private final Screen parent;
	private final SpeakerBlockEntity speaker;
	private final boolean fixedType;
	private SpeakerType selectedType = SpeakerType.SUBWOOFER;
	private String draftProfileId = "";
	private double draftLowHz;
	private double draftHighHz;
	private double draftGainDb;
	private SpeakerRole draftRole = SpeakerRole.NONE;

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentBottom;
	private int typeY;
	private int profileY;
	private int fieldY;
	private int sourceY;
	private int compactY;
	private int secondColumn;
	private boolean compact;

	private EditBox lowBox;
	private EditBox highBox;
	private EditBox gainBox;
	private EditBox nameBox;
	private Button profileButton;
	private Button roleButton;
	private boolean nameDirty;
	private long nameDirtyAtMillis;
	private boolean syncingName;

	private String status = "";
	private long statusUntil;
	private boolean closing;
	private boolean fieldsDirty;
	private long fieldsDirtyAtMillis;
	private boolean syncingFields;

	public SpeakerDataConfigScreen(Screen parent) {
		this(parent, null);
	}

	public SpeakerDataConfigScreen(Screen parent, SpeakerBlockEntity speaker) {
		super(Component.literal("Speaker Data"));
		this.parent = parent;
		this.speaker = speaker;
		this.fixedType = speaker != null;
		if (speaker != null) {
			this.selectedType = speaker.type();
			this.draftRole = speaker.role();
		}
		SpeakerDataRegistry.load();
		SpeakerDataAssignments.load();
		loadDraftFromRegistry();
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, 420);
		panelHeight = Math.min(height - 12, 310);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		contentBottom = top + panelHeight - 26;
		typeY = top + 28;
		profileY = top + 52;
		fieldY = top + 76;
		sourceY = top + 122;
		compactY = top + 162;
		secondColumn = contentWidth / 2;
		compact = panelHeight < 250;

		if (!fixedType) {
			SpeakerType[] types = SpeakerType.values();
			int typeWidth = (contentWidth - (types.length - 1) * 2) / types.length;
			for (int i = 0; i < types.length; i++) {
				SpeakerType type = types[i];
				int x = contentLeft + i * (typeWidth + 2);
				addRenderableWidget(Button.builder(Component.literal(typeLabel(type)),
						button -> selectType(type))
					.bounds(x, typeY, typeWidth, TYPE_ROW_HEIGHT).build());
			}
		}

		if (fixedType && speaker != null) {
			nameBox = new EditBox(font, contentLeft, typeY, contentWidth - 110, 18,
				Component.literal("Speaker name"));
			nameBox.setMaxLength(SpeakerBlockEntity.MAX_NAME_LENGTH);
			nameBox.setHint(Component.literal("Speaker name"));
			syncingName = true;
			nameBox.setValue(speaker.name());
			syncingName = false;
			nameBox.setResponder(value -> onNameChanged());
			addRenderableWidget(nameBox);
			roleButton = Button.builder(roleLabel(), button -> cycleRole())
				.bounds(contentLeft + contentWidth - 104, typeY, 104, 18).build();
			addRenderableWidget(roleButton);
		}

		profileButton = Button.builder(profileLabel(), button -> openProfilePicker())
			.bounds(contentLeft, profileY, contentWidth - 62, 18).build();
		addRenderableWidget(profileButton);
		addRenderableWidget(Button.builder(Component.literal("Reload"), button -> reloadProfiles())
			.bounds(contentLeft + contentWidth - 58, profileY, 58, 18).build());

		int fieldWidth = Math.max(40, secondColumn - 70);
		int highWidth = Math.max(40, contentWidth - secondColumn - 66);
		lowBox = addField(contentLeft + 62, fieldY, fieldWidth);
		highBox = addField(contentLeft + secondColumn + 62, fieldY, highWidth);
		gainBox = addField(contentLeft + 62, fieldY + 22, fieldWidth);

		addRenderableWidget(Button.builder(Component.literal("Open folder"), button -> openFolder())
			.bounds(contentLeft, contentBottom, 80, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Defaults"), button -> resetDefaults())
			.bounds(contentLeft + 84, contentBottom, 64, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, contentBottom, 60, 18).build());

		syncFields();
	}

	private EditBox addField(int x, int y, int width) {
		EditBox box = new EditBox(font, x, y, width, ROW_HEIGHT, Component.empty());
		box.setMaxLength(12);
		box.setResponder(value -> onFieldChanged());
		addRenderableWidget(box);
		return box;
	}

	private void onFieldChanged() {
		if (syncingFields) {
			return;
		}
		fieldsDirty = true;
		fieldsDirtyAtMillis = System.currentTimeMillis() + FIELD_SAVE_DEBOUNCE_MS;
	}

	private void onNameChanged() {
		if (syncingName || nameBox == null) {
			return;
		}
		nameDirty = true;
		nameDirtyAtMillis = System.currentTimeMillis() + FIELD_SAVE_DEBOUNCE_MS;
	}

	private void flushName() {
		if (!nameDirty || nameBox == null || speaker == null) {
			return;
		}
		nameDirty = false;
		SpeakerNameClient.setName(speaker.getBlockPos(), nameBox.getValue());
	}

	@Override
	public void tick() {
		super.tick();
		if (fieldsDirty && System.currentTimeMillis() >= fieldsDirtyAtMillis) {
			fieldsDirty = false;
			applyDraftSettings(false);
		}
		if (nameDirty && System.currentTimeMillis() >= nameDirtyAtMillis) {
			flushName();
		}
	}

	private void selectType(SpeakerType type) {
		if (fixedType || selectedType == type) {
			return;
		}
		selectedType = type;
		loadDraftFromRegistry();
		rebuildWidgets();
	}

	private void loadDraftFromRegistry() {
		SpeakerDataRegistry.SpeakerSettings settings = speaker != null
			? SpeakerDataAssignments.settingsFor(speaker)
			: SpeakerDataRegistry.settings(selectedType);
		if (settings == null) {
			draftProfileId = "";
			draftLowHz = 20.0;
			draftHighHz = 20_000.0;
			draftGainDb = 0.0;
			return;
		}
		draftProfileId = settings.profileId();
		draftLowHz = settings.lowHz();
		draftHighHz = settings.highHz();
		draftGainDb = settings.gainDb();
	}

	private void syncFields() {
		syncingFields = true;
		fieldsDirty = false;
		try {
			if (lowBox != null) {
				lowBox.setValue(format(draftLowHz, 0));
				highBox.setValue(format(draftHighHz, 0));
				gainBox.setValue(format(draftGainDb, 2));
				updateRangeEditing();
			}
			if (profileButton != null) {
				profileButton.setMessage(profileLabel());
			}
			if (roleButton != null) {
				roleButton.setMessage(roleLabel());
			}
		} finally {
			syncingFields = false;
		}
	}

	/**
	 * Non-subwoofer measured profiles have a fixed -3 dB frequency range. Only
	 * the level trim remains editable; subwoofers always keep a manually
	 * editable bass-management band, and unmeasured/analytic profiles can still
	 * edit low/high Hz.
	 */
	private void updateRangeEditing() {
		SpeakerDataProfile profile = selectedProfile();
		boolean subwoofer = selectedType == SpeakerType.SUBWOOFER;
		boolean measured = profile != null && profile.isMeasured();
		boolean editable = profile != null && (subwoofer || !measured);
		lowBox.setEditable(editable);
		highBox.setEditable(editable);
		lowBox.setTextColor(editable ? 0xE0E0E0 : 0x707070);
		highBox.setTextColor(editable ? 0xE0E0E0 : 0x707070);
		if (measured && !subwoofer) {
			draftLowHz = profile.lowHz();
			draftHighHz = profile.highHz();
			lowBox.setValue(format(draftLowHz, 0));
			highBox.setValue(format(draftHighHz, 0));
		}
	}

	private Component profileLabel() {
		SpeakerDataProfile profile = selectedProfile();
		String name = profile == null ? "none" : profile.name();
		int maxWidth = Math.max(80, contentWidth - 100);
		return Component.literal("Profile: " + truncate(name, maxWidth));
	}

	private Component roleLabel() {
		return Component.literal("Role: " + draftRole.label());
	}

	private void cycleRole() {
		if (speaker == null) {
			return;
		}
		List<SpeakerRole> roles = new ArrayList<>();
		for (SpeakerRole role : SpeakerRole.values()) {
			if (speaker.type().allows(role)) {
				roles.add(role);
			}
		}
		if (roles.isEmpty()) {
			return;
		}
		int index = roles.indexOf(draftRole);
		draftRole = roles.get((index + 1) % roles.size());
		if (roleButton != null) {
			roleButton.setMessage(roleLabel());
		}
		SpeakerRoleClient.setRole(speaker.getBlockPos(), draftRole);
		setStatus("Role: " + draftRole.label() + " (saved)");
	}

	private void openProfilePicker() {
		List<SpeakerDataProfile> profiles = SpeakerDataRegistry.profilesFor(selectedType);
		if (profiles.isEmpty()) {
			setStatus("No profiles for this type");
			return;
		}
		if (fieldsDirty) {
			fieldsDirty = false;
			if (!applyDraftSettings(true)) {
				return;
			}
		}
		if (minecraft != null) {
			minecraft.setScreen(new SpeakerDataSelectionScreen(this, selectedType,
				draftProfileId, this::selectProfile));
		}
	}

	private void selectProfile(String profileId) {
		SpeakerDataProfile profile = SpeakerDataRegistry.find(selectedType, profileId);
		if (profile == null) {
			setStatus("Profile not found: " + profileId);
			return;
		}
		SpeakerDataRegistry.SpeakerSettings defaults = settingsForProfile(profile.id());
		draftProfileId = profile.id();
		draftLowHz = defaults.lowHz();
		draftHighHz = defaults.highHz();
		draftGainDb = defaults.gainDb();
		syncFields();
		if (applyDraftSettings(true)) {
			setStatus("Profile: " + profile.name() + " (saved)");
		}
	}

	private SpeakerDataRegistry.SpeakerSettings settingsForProfile(String profileId) {
		if (speaker != null) {
			SpeakerDataAssignments.Assignment assignment =
				SpeakerDataAssignments.get(speaker);
			if (assignment != null && assignment.type() == selectedType
				&& assignment.settings().profileId().equals(profileId)) {
				return SpeakerDataRegistry.normalizeSettings(selectedType, assignment.settings());
			}
		}
		return SpeakerDataRegistry.profileSettings(selectedType, profileId);
	}

	private void reloadProfiles() {
		SpeakerDataRegistry.ReloadResult result = SpeakerDataRegistry.reloadProfiles();
		CommonAcousticFirService.clearCache();
		if (SpeakerDataRegistry.find(selectedType, draftProfileId) == null) {
			loadDraftFromRegistry();
		}
		syncFields();
		String message = "Reloaded " + result.profileCount() + " profile(s)";
		if (!result.errors().isEmpty()) {
			message += " · " + result.errors().size() + " error(s)";
		}
		setStatus(message);
	}

	private void openFolder() {
		try {
			java.nio.file.Files.createDirectories(SpeakerDataRegistry.profilesDir());
			Util.getPlatform().openPath(SpeakerDataRegistry.profilesDir());
			setStatus("Opened " + SpeakerDataRegistry.profilesDir());
		} catch (IOException e) {
			setStatus("Could not open folder: " + e.getMessage());
		}
	}

	private void resetDefaults() {
		SpeakerDataProfile profile = selectedProfile();
		if (profile == null) {
			setStatus("No profile available for this type");
			return;
		}
		draftProfileId = profile.id();
		draftLowHz = profile.lowHz();
		draftHighHz = profile.highHz();
		draftGainDb = 0.0;
		syncFields();
		if (applyDraftSettings(true)) {
			setStatus("Defaults applied for " + profile.name());
		}
	}

	private boolean applyDraftSettings(boolean quiet) {
		if (!readFields(quiet)) {
			return false;
		}
		SpeakerDataRegistry.SpeakerSettings settings =
			new SpeakerDataRegistry.SpeakerSettings(draftProfileId, draftLowHz,
				draftHighHz, draftGainDb);
		SpeakerDataRegistry.SpeakerSettings current = speaker != null
			? SpeakerDataAssignments.settingsFor(speaker)
			: SpeakerDataRegistry.settings(selectedType);
		if (settings.equals(current)) {
			if (!quiet) {
				setStatus("Saved");
			}
			return true;
		}
		if (speaker != null) {
			SpeakerDataAssignments.apply(speaker, settings);
			// Also write type defaults in the current format so the first
			// spike settings.json is migrated after a speaker edit.
			SpeakerDataRegistry.saveSettings();
		} else {
			SpeakerDataRegistry.applySettings(selectedType, settings);
			SpeakerDataRegistry.saveSettings();
		}
		if (!quiet) {
			setStatus("Saved");
		}
		return true;
	}

	private boolean readFields(boolean quiet) {
		SpeakerDataProfile profile = selectedProfile();
		if (profile == null) {
			if (!quiet) {
				setStatus("Select a profile first");
			}
			return false;
		}
		try {
			double gain = Double.parseDouble(gainBox.getValue().trim());
			if (!Double.isFinite(gain)) {
				throw new NumberFormatException("not finite");
			}
			boolean subwoofer = selectedType == SpeakerType.SUBWOOFER;
			if (profile.isMeasured() && !subwoofer) {
				draftLowHz = profile.lowHz();
				draftHighHz = profile.highHz();
			} else {
				double low = Double.parseDouble(lowBox.getValue().trim());
				double high = Double.parseDouble(highBox.getValue().trim());
				if (!Double.isFinite(low) || !Double.isFinite(high)) {
					throw new NumberFormatException("not finite");
				}
				long roundedLow = Math.round(low);
				long roundedHigh = Math.round(high);
				if (subwoofer) {
					long minHz = (long) SpeakerDataRegistry.SUBWOOFER_MIN_HZ;
					long maxHz = (long) SpeakerDataRegistry.SUBWOOFER_MAX_HZ;
					if (roundedLow < minHz || roundedHigh > maxHz
						|| roundedHigh <= roundedLow) {
						if (!quiet) {
							setStatus("Subwoofer range must satisfy 10 <= low < high <= 1000 (1 Hz)");
						}
						return false;
					}
				} else if (roundedLow < 1L || roundedHigh > 24_000L
					|| roundedHigh <= roundedLow) {
					if (!quiet) {
						setStatus("Frequency range must satisfy 1 <= low < high <= 24000 (1 Hz)");
					}
					return false;
				}
				draftLowHz = roundedLow;
				draftHighHz = roundedHigh;
			}
			draftGainDb = clamp(gain, -60.0, 60.0);
			return true;
		} catch (NumberFormatException e) {
			if (!quiet) {
				setStatus("Enter valid numbers in all fields");
			}
			return false;
		}
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		int imported = 0;
		List<String> errors = new ArrayList<>();
		for (Path path : paths) {
			try {
				SpeakerDataProfile profile = SpeakerDataRegistry.importProfile(path);
				imported++;
				if (profile.type() == selectedType) {
					SpeakerDataRegistry.SpeakerSettings defaults =
						settingsForProfile(profile.id());
					draftProfileId = profile.id();
					draftLowHz = defaults.lowHz();
					draftHighHz = defaults.highHz();
					draftGainDb = defaults.gainDb();
					syncFields();
					applyDraftSettings(true);
				}
			} catch (Exception e) {
				errors.add(path.getFileName() + ": " + e.getMessage());
			}
		}
		if (imported > 0) {
			String message = "Imported " + imported + " profile(s)";
			if (!errors.isEmpty()) {
				message += " · " + errors.size() + " failed";
			}
			setStatus(message);
		} else {
			setStatus(errors.isEmpty() ? "No .json files found" : errors.get(0));
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
		graphics.drawString(font, truncate(titleLabel().getString(), panelWidth - 20),
			left + 10, top + 7, titleColor(), false);

		if (!fixedType) {
			SpeakerType[] types = SpeakerType.values();
			int typeWidth = (contentWidth - (types.length - 1) * 2) / types.length;
			int selectedIndex = selectedType.ordinal();
			int accentX = contentLeft + selectedIndex * (typeWidth + 2);
			graphics.fill(accentX, typeY + TYPE_ROW_HEIGHT, accentX + typeWidth,
				typeY + TYPE_ROW_HEIGHT + 2, 0xFFFFC107);
		} else if (speaker != null) {
			String header = "Speaker: " + speaker.type().displayName().getString()
				+ " @ " + speaker.getBlockPos().toShortString();
			graphics.drawString(font, truncate(header, contentWidth), contentLeft, top + 18,
				0xB8B8B8, false);
		}

		graphics.drawString(font, "Low Hz", contentLeft, fieldY + 4, 0xB8B8B8, false);
		graphics.drawString(font, "High Hz", contentLeft + secondColumn, fieldY + 4, 0xB8B8B8, false);
		graphics.drawString(font, "SPL gain dB", contentLeft, fieldY + 26, 0xB8B8B8, false);

		SpeakerDataProfile profile = selectedProfile();
		if (profile == null) {
			graphics.drawString(font, "No profile selected", contentLeft, sourceY, 0xFF7070, false);
		} else {
			if (!compact) {
				String kind = selectedType == SpeakerType.SUBWOOFER
					? profile.enclosureLabel() + " · " + profile.directivityLabel()
					: profile.patternLabel();
				String sourceLine = kind + " · " + profile.source()
					+ " · " + profile.license();
				if (profile.hasOnAxisResponse()) {
					sourceLine += String.format(Locale.ROOT,
						" · on-axis %d bands · ref %+.1f dB",
						profile.onAxisBandCount(), profile.onAxisReferenceDb());
				}
				graphics.drawString(font, truncate(sourceLine, contentWidth), contentLeft, sourceY,
					0xB8B8B8, false);
			}
			String preview = previewLine(profile);
			graphics.drawString(font, truncate(preview, contentWidth), contentLeft, sourceY + 12,
				0x9A9A9A, false);
			if (!compact) {
				String range = String.format(Locale.ROOT,
					"Profile -3 dB %.0f–%.0f Hz · SPL gain %+.1f dB",
					profile.lowHz(), profile.highHz(), draftGainDb);
				if (selectedType == SpeakerType.SUBWOOFER
					&& (Math.round(draftLowHz) != Math.round(profile.lowHz())
					|| Math.round(draftHighHz) != Math.round(profile.highHz()))) {
					range += String.format(Locale.ROOT, " · manual %.0f–%.0f Hz",
						draftLowHz, draftHighHz);
				}
				graphics.drawString(font, truncate(range, contentWidth), contentLeft, sourceY + 24,
					0x9A9A9A, false);
			}
		}

		if (!compact) {
			graphics.drawString(font,
				truncate("Drop a .json profile here, or put it in the profiles folder.", contentWidth),
				contentLeft, compactY, 0x808080, false);
			graphics.drawString(font, truncate(SpeakerDataRegistry.profilesDir().toString(),
				contentWidth), contentLeft, compactY + 12, 0x707070, false);
		}

		String statusText = System.currentTimeMillis() < statusUntil && !status.isEmpty()
			? status
			: "Changes save immediately. Close when done.";
		int statusY = compact ? top + 116 : contentBottom - 14;
		graphics.drawString(font, truncate(statusText, contentWidth),
			contentLeft, statusY, System.currentTimeMillis() < statusUntil
				? 0xFFD54F : 0x9A9A9A, false);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		drawHoverTooltip(graphics, mouseX, mouseY);
	}

	private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (nameBox != null && inBox(mouseX, mouseY, nameBox.getX(), nameBox.getY(),
			nameBox.getWidth(), nameBox.getHeight())) {
			renderWrappedTooltip(graphics,
				"Custom speaker name shown in the receiver Speakers list. "
					+ "Leave empty to use role/type/coordinates. Max "
					+ SpeakerBlockEntity.MAX_NAME_LENGTH + " characters.",
				mouseX, mouseY);
			return;
		}
		if (roleButton != null && inBox(mouseX, mouseY, roleButton.getX(), roleButton.getY(),
			roleButton.getWidth(), roleButton.getHeight())) {
			renderWrappedTooltip(graphics,
				"Click to cycle the roles allowed by this cabinet. Applies immediately to the server.",
				mouseX, mouseY);
			return;
		}
		if (lowBox != null && (inBox(mouseX, mouseY, lowBox.getX(), lowBox.getY(),
			lowBox.getWidth(), lowBox.getHeight())
			|| inBox(mouseX, mouseY, highBox.getX(), highBox.getY(),
			highBox.getWidth(), highBox.getHeight()))) {
			SpeakerDataProfile profile = selectedProfile();
			if (profile != null && selectedType == SpeakerType.SUBWOOFER) {
				renderWrappedTooltip(graphics,
					"Subwoofer: Low/High Hz are the manual bass-management band "
						+ "(10-1000 Hz). The profile -3 dB range is shown below.",
					mouseX, mouseY);
				return;
			}
			if (profile != null && profile.isMeasured()) {
				renderWrappedTooltip(graphics,
					"Measured profile: Low/High Hz are fixed by the on-axis -3 dB "
						+ "frequency range. Only SPL gain dB can be edited.",
					mouseX, mouseY);
				return;
			}
		}
		if (profileButton != null && inBox(mouseX, mouseY, profileButton.getX(), profileButton.getY(),
			profileButton.getWidth(), profileButton.getHeight())) {
			SpeakerDataProfile profile = selectedProfile();
			if (profile != null) {
				renderWrappedTooltip(graphics,
					"Click to choose any profile for this cabinet type.\n"
						+ profile.name() + "\n" + profile.summaryLabel() + " · " + profile.source()
						+ "\nLicense: " + profile.license(),
					mouseX, mouseY);
			}
			return;
		}
		if (!compact && mouseY >= compactY && mouseY < compactY + 22
			&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth) {
			renderWrappedTooltip(graphics,
				"Local folder:\n" + SpeakerDataRegistry.profilesDir()
					+ "\n\nSettings file:\n" + SpeakerDataRegistry.settingsFile(),
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

	private SpeakerDataProfile selectedProfile() {
		return SpeakerDataRegistry.find(selectedType, draftProfileId);
	}

	private String previewLine(SpeakerDataProfile profile) {
		double frequency = selectedType == SpeakerType.SUBWOOFER ? 80.0 : 1_000.0;
		frequency = Math.max(profile.lowHz(), Math.min(profile.highHz(), frequency));
		return String.format(Locale.ROOT,
			"%.0f Hz: 0° %+.1f · 45° %+.1f · 90° %+.1f · 135° %+.1f · 180° %+.1f dB",
			frequency,
			profile.gainDb(0, 0, frequency),
			profile.gainDb(45, 0, frequency),
			profile.gainDb(90, 0, frequency),
			profile.gainDb(135, 0, frequency),
			profile.gainDb(180, 0, frequency));
	}

	private Component titleLabel() {
		if (speaker == null) {
			return title;
		}
		BlockPos receiver = speaker.receiverPos();
		if (receiver == null) {
			return Component.literal("Speaker Data · Receiver: not linked");
		}
		return Component.literal("Speaker Data · Receiver: "
			+ (receiverLinkMissing() ? "link missing @ " : "linked @ ")
			+ receiver.toShortString());
	}

	private int titleColor() {
		return receiverLinkMissing() ? 0xFFD54F : 0xE8E8E8;
	}

	private boolean receiverLinkMissing() {
		if (speaker == null || speaker.receiverPos() == null) {
			return false;
		}
		BlockPos receiver = speaker.receiverPos();
		if (speaker.getLevel() == null || !speaker.getLevel().isLoaded(receiver)) {
			return false;
		}
		return !(speaker.getLevel().getBlockEntity(receiver) instanceof AvReceiverBlockEntity);
	}

	private void setStatus(String message) {
		status = message == null ? "" : message;
		statusUntil = System.currentTimeMillis() + 6_000L;
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

	private static String typeLabel(SpeakerType type) {
		return switch (type) {
			case FLOORSTANDING -> "Floor";
			case BOOKSHELF -> "Bookshelf";
			case CENTER -> "Center";
			case SUBWOOFER -> "Sub";
		};
	}

	private static String format(double value, int decimals) {
		return String.format(Locale.ROOT, "%." + decimals + "f", value);
	}

	private static double clamp(double value, double min, double max) {
		if (!Double.isFinite(value)) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	@Override
	public boolean isPauseScreen() {
		return CinemaClientConfig.get().pauseGameInDeviceGuis();
	}

	@Override
	public void onClose() {
		if (closing) {
			return;
		}
		if (fieldsDirty) {
			fieldsDirty = false;
			applyDraftSettings(true);
		}
		flushName();
		closing = true;
		if (parent != null && minecraft != null) {
			// Defer the parent switch until after the mouse release. Opening the
			// parent immediately can let the same release click a widget under
			// the cursor and play a second button sound.
			Minecraft client = minecraft;
			Screen target = parent;
			client.execute(() -> client.setScreen(target));
		} else {
			super.onClose();
		}
	}
}
