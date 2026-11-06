package com.example.cinema.client.gui;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.AvReceiverPlayback;
import com.example.cinema.client.media.AvReceiverPlaybackManager;
import com.example.cinema.client.media.CinemaPlayer;
import com.example.cinema.client.media.LibraryRegistry;
import com.example.cinema.client.media.MediaLibrary;
import com.example.cinema.client.network.AvReceiverControlClient;
import com.example.cinema.client.network.DeviceNameClient;
import com.example.cinema.client.network.SpeakerLevelClient;
import com.example.cinema.client.render.SpeakerLocator;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.item.RemoteControlAccess;
import com.example.cinema.network.AvReceiverControlPayload;
import com.example.cinema.util.CinemaNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M2 AV receiver front panel: media library, transport, outputs, diagnostics
 * and the future speaker setup page.
 *
 * <p>The screen reads the synced receiver block entity directly and sends all
 * changes through {@code AvReceiverControlPayload}. Pausing follows the Mod
 * Menu behavior setting; the default keeps the game running so a movie does
 * not stop while the panel is open.
 */
public final class AvReceiverConfigScreen extends Screen {
	private static final int TAB_HEIGHT = 18;
	private static final int ROW_HEIGHT = 11;
	private static final int SPEAKER_ROW_HEIGHT = 19;
	private static final int PROBE_LIMIT = 30;
	private static final long SPEAKER_LEVEL_DEBOUNCE_MS = 150L;
	private static final long NAME_SAVE_DEBOUNCE_MS = 250L;

	private static String rememberedLibraryId = LibraryRegistry.DEFAULT_ID;
	private static String rememberedFolder = "";
	private static String rememberedSearch = "";
	/**
	 * Session cache of scanned folders. The first open probes up to
	 * {@link #PROBE_LIMIT} files; later opens reuse the result until Refresh.
	 */
	private static final Map<FolderCacheKey, List<EntryView>> folderCache =
		new ConcurrentHashMap<>();

	private final AvReceiverBlockEntity receiver;
	private final Screen parent;
	private Tab tab = Tab.MEDIA;
	private final List<EntryView> entries = new ArrayList<>();
	private final List<String> speakerLines = new ArrayList<>();
	private final List<SpeakerRow> linkedSpeakers = new ArrayList<>();
	private final Map<BlockPos, SpeakerLevelSlider> speakerLevelSliders = new LinkedHashMap<>();
	private final Map<BlockPos, Button> speakerAccessButtons = new LinkedHashMap<>();
	private String speakerRowsKey = "";
	private String builtSpeakerRowsKey = "";

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentTop;
	private int contentBottom;
	private int transportTop;
	private int footerY;
	private int tabY;
	private int tabWidth;

	private String libraryId = rememberedLibraryId;
	private String folder = rememberedFolder;
	private String search = "";
	private String selectedKey = "";
	private String libraryPathDraft = "";
	private String selectedLibraryId = rememberedLibraryId;
	private int scroll;
	private int libraryScroll;
	private int speakerScroll;
	private boolean scanning;
	private boolean folderLoaded;
	private int scanGeneration;
	private long lastClickTime;
	private String lastClickKey = "";
	private boolean pendingPlay;
	private String statusMessage = "";
	private long statusUntil;
	private boolean mediaMissing;
	private boolean closing;
	private MediaLibrary.Probe receiverProbe;
	private String receiverProbeKey = "";
	private boolean receiverProbePending;

	private EditBox searchBox;
	private EditBox receiverNameBox;
	private boolean receiverNameDirty;
	private long receiverNameDirtyAtMillis;
	private boolean syncingReceiverName;
	private Button libraryButton;
	private Button playPauseButton;
	private Button stopButton;
	private Button muteButton;
	private Button backButton;
	private Button playSelectedButton;
	private Button removeLibraryButton;
	private Button seekBackButton;
	private Button seekForwardButton;
	private EditBox libraryPathBox;
	private SeekSlider seekSlider;
	private VolumeSlider volumeSlider;
	private double pendingSeekSeconds = Double.NaN;
	private long pendingSeekUntilMillis;
	private long seekCooldownUntilMillis;
	private double pendingVolumeDb = Double.NaN;
	private long pendingVolumeUntilMillis;

	public AvReceiverConfigScreen(AvReceiverBlockEntity receiver) {
		this(receiver, null);
	}

	public AvReceiverConfigScreen(AvReceiverBlockEntity receiver, Screen parent) {
		super(Component.literal("Cinema AV Receiver"));
		this.receiver = receiver;
		this.parent = parent;
		if (LibraryRegistry.findById(libraryId) == null) {
			libraryId = LibraryRegistry.DEFAULT_ID;
			folder = "";
		}
		selectedLibraryId = libraryId;
		Cinema.LOGGER.info("cinema receiver gui: opened {}", receiver.getBlockPos().toShortString());
	}

	@Override
	protected void init() {
		super.init();
		speakerLevelSliders.clear();
		speakerAccessButtons.clear();
		panelWidth = Math.min(width - 12, 420);
		panelHeight = Math.min(height - 12, 300);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 8;
		contentWidth = panelWidth - 16;
		tabY = top + 20;
		contentTop = top + 60;
		transportTop = top + panelHeight - 62;
		contentBottom = transportTop - 4;
		footerY = top + panelHeight - 22;

		int nameX = left + Math.min(160, panelWidth / 2);
		int nameWidth = Math.max(80, panelWidth - (nameX - left) - 12);
		receiverNameBox = new EditBox(font, nameX, top + 2, nameWidth, 16,
			Component.literal("Receiver name"));
		receiverNameBox.setMaxLength(CinemaNames.MAX_LENGTH);
		receiverNameBox.setHint(Component.literal("Receiver name"));
		syncingReceiverName = true;
		receiverNameBox.setValue(receiver.name());
		syncingReceiverName = false;
		receiverNameBox.setResponder(value -> onReceiverNameChanged());
		addRenderableWidget(receiverNameBox);

		Tab[] tabs = Tab.values();
		tabWidth = (contentWidth - 6) / tabs.length;
		for (int i = 0; i < tabs.length; i++) {
			Tab value = tabs[i];
			int x = contentLeft + i * (tabWidth + 2);
			addRenderableWidget(Button.builder(Component.literal(value.label), button -> switchTab(value))
				.bounds(x, tabY, tabWidth, TAB_HEIGHT).build());
		}

		seekSlider = new SeekSlider(contentLeft, transportTop, contentWidth, 18);
		addRenderableWidget(seekSlider);
		int rowY = transportTop + 20;
		playPauseButton = Button.builder(Component.literal("Play"), button -> togglePlayPause())
			.bounds(contentLeft, rowY, 38, 18).build();
		addRenderableWidget(playPauseButton);
		stopButton = Button.builder(Component.literal("Stop"), button -> sendStop())
			.bounds(contentLeft + 40, rowY, 34, 18).build();
		addRenderableWidget(stopButton);
		seekBackButton = Button.builder(Component.literal("-10s"), button -> seekRelative(-10))
			.bounds(contentLeft + 76, rowY, 34, 18).build();
		addRenderableWidget(seekBackButton);
		seekForwardButton = Button.builder(Component.literal("+10s"), button -> seekRelative(10))
			.bounds(contentLeft + 112, rowY, 34, 18).build();
		addRenderableWidget(seekForwardButton);

		int muteWidth = 42;
		int volumeX = contentLeft + 150;
		int volumeWidth = Math.max(40, contentWidth - (volumeX - contentLeft) - muteWidth - 2);
		volumeSlider = new VolumeSlider(volumeX, rowY, volumeWidth, 18);
		addRenderableWidget(volumeSlider);
		muteButton = Button.builder(Component.literal("Mute"), button -> toggleMute())
			.bounds(contentLeft + contentWidth - muteWidth, rowY, muteWidth, 18).build();
		addRenderableWidget(muteButton);

		if (tab == Tab.MEDIA) {
			libraryButton = Button.builder(libraryButtonLabel(), button -> cycleLibrary())
				.bounds(contentLeft, contentTop, 150, 16).build();
			addRenderableWidget(libraryButton);
			searchBox = new EditBox(font, contentLeft, contentTop + 20, contentWidth - 50, 16,
				Component.literal("Search"));
			searchBox.setHint(Component.literal("Search"));
			searchBox.setMaxLength(80);
			searchBox.setResponder(value -> {
				search = value;
				scroll = 0;
			});
			searchBox.setValue(search);
			addRenderableWidget(searchBox);
			addRenderableWidget(Button.builder(Component.literal("Refresh"),
					button -> loadFolder(libraryId, folder, true))
				.bounds(contentLeft + contentWidth - 46, contentTop + 20, 46, 16).build());
			backButton = Button.builder(Component.literal("Up"),
					button -> loadFolder(libraryId, MediaLibrary.parent(folder), false))
				.bounds(contentLeft, contentBottom - 18, 44, 16).build();
			addRenderableWidget(backButton);
			playSelectedButton = Button.builder(Component.literal("Play file"), button -> playSelected())
				.bounds(contentLeft + 48, contentBottom - 18, 64, 16).build();
			addRenderableWidget(playSelectedButton);
			if (!folderLoaded && !scanning) {
				loadFolder(libraryId, folder, false);
			}
			// loadFolder() clears the filter for a fresh scan. Restore the
			// previous search after it so closing and reopening the screen
			// keeps the last query.
			if (!rememberedSearch.isEmpty()) {
				search = rememberedSearch;
				searchBox.setValue(rememberedSearch);
			}
		} else if (tab == Tab.SPEAKERS) {
			refreshSpeakers();
			addSpeakerLevelWidgets();
			builtSpeakerRowsKey = speakerRowsKey;
		} else if (tab == Tab.SETTINGS) {
			addRenderableWidget(Button.builder(Component.literal("Refresh library"),
					button -> loadFolder(libraryId, folder, true))
				.bounds(contentLeft, contentTop + 4, 124, 18).build());
			libraryPathBox = new EditBox(font, contentLeft, contentTop + 26, contentWidth, 16,
				Component.literal("External library folder"));
			libraryPathBox.setHint(Component.literal("External library folder path"));
			libraryPathBox.setMaxLength(260);
			libraryPathBox.setResponder(value -> libraryPathDraft = value);
			libraryPathBox.setValue(libraryPathDraft);
			addRenderableWidget(libraryPathBox);
			addRenderableWidget(Button.builder(Component.literal("Add library"), button -> addLibrary())
				.bounds(contentLeft, contentTop + 44, 92, 18).build());
			removeLibraryButton = Button.builder(Component.literal("Remove library"), button -> removeLibrary())
				.bounds(contentLeft + 96, contentTop + 44, 104, 18).build();
			addRenderableWidget(removeLibraryButton);
			addRenderableWidget(Button.builder(Component.literal("Pair devices..."),
					button -> minecraft.setScreen(new RemotePairingScreen(this, receiver.getBlockPos(),
						RemoteBinding.Kind.RECEIVER)))
				.bounds(contentLeft + contentWidth - 104, contentTop + 66, 104, 18).build());
		}
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, contentBottom - 18, 60, 18).build());
		syncWidgets();
	}

	@Override
	public void tick() {
		if (receiver.isRemoved()) {
			onClose();
			return;
		}
		super.tick();
		syncWidgets();
		if (receiverNameDirty && System.currentTimeMillis() >= receiverNameDirtyAtMillis) {
			flushReceiverName();
		}
		if (minecraft != null && minecraft.level != null && minecraft.level.getGameTime() % 20L == 0L) {
			refreshWarnings();
			refreshSpeakers();
			ensureReceiverProbe();
			if (tab == Tab.SPEAKERS
				&& !speakerRowsKey.equals(builtSpeakerRowsKey)
				&& !hasDraggingSpeakerLevel()) {
				rebuildWidgets();
			}
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
		graphics.drawString(font, title, left + 8, top + 4, 0xE8E8E8, false);
		drawStatus(graphics);
		drawContent(graphics, mouseX, mouseY);
		graphics.drawString(font, truncate("Changes apply immediately. Close when done.",
				contentWidth - 58), contentLeft, footerY + 5, 0x707070, false);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Screen.render invokes renderBackground() first, then the widgets. The
		// panel and descriptive text therefore live in renderBackground so they
		// are not covered by a second blur pass.
		super.render(graphics, mouseX, mouseY, partialTick);
		drawTabAccent(graphics);
		if (receiverNameBox != null
			&& mouseX >= receiverNameBox.getX()
			&& mouseX < receiverNameBox.getX() + receiverNameBox.getWidth()
			&& mouseY >= receiverNameBox.getY()
			&& mouseY < receiverNameBox.getY() + receiverNameBox.getHeight()) {
			renderWrappedTooltip(graphics,
				"Custom AV receiver name shown in the projector status and commissioning output. "
					+ "Max " + CinemaNames.MAX_LENGTH + " characters.", mouseX, mouseY);
			return;
		}
		if (tab == Tab.MEDIA || tab == Tab.SPEAKERS || tab == Tab.SETTINGS) {
			drawHoverTooltip(graphics, mouseX, mouseY);
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}
		if (button == 0 && tab == Tab.SETTINGS) {
			int libraryRow = libraryRowAt(mouseX, mouseY);
			if (libraryRow >= 0) {
				selectedLibraryId = LibraryRegistry.libraries().get(libraryRow).id();
				syncWidgets();
				return true;
			}
		}
		if (button != 0 || tab != Tab.MEDIA || !inList(mouseX, mouseY)) {
			return false;
		}
		List<EntryView> filtered = filteredEntries();
		int row = (int) ((mouseY - listTop()) / ROW_HEIGHT) + scroll;
		if (row < 0 || row >= filtered.size()) {
			return false;
		}
		EntryView view = filtered.get(row);
		if (view.entry.directory()) {
			loadFolder(view.entry.libraryId(), view.entry.folder(), false);
			return true;
		}
		long now = System.currentTimeMillis();
		boolean doubleClick = view.entry.key().equals(lastClickKey) && now - lastClickTime < 350L;
		selectedKey = view.entry.key();
		lastClickKey = view.entry.key();
		lastClickTime = now;
		if (doubleClick) {
			playSelected();
		}
		syncWidgets();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == Tab.SPEAKERS && inSpeakerList(mouseX, mouseY)) {
			int visibleRows = visibleSpeakerRows();
			int maxScroll = Math.max(0, linkedSpeakers.size() - visibleRows);
			if (maxScroll > 0) {
				speakerScroll = Math.max(0, Math.min(maxScroll,
					speakerScroll - (int) Math.signum(scrollY)));
				rebuildWidgets();
			}
			return true;
		}
		if (tab == Tab.SETTINGS
			&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth
			&& mouseY >= contentTop + 98 && mouseY < contentBottom - 22) {
			int maxScroll = Math.max(0, LibraryRegistry.libraries().size() - visibleLibraryRows());
			libraryScroll = Math.max(0, Math.min(maxScroll,
				libraryScroll - (int) Math.signum(scrollY) * 2));
			return true;
		}
		if (tab == Tab.MEDIA && inList(mouseX, mouseY)) {
			int maxScroll = Math.max(0, filteredEntries().size() - visibleRows());
			scroll = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY) * 2));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
		closing = true;
		flushPendingControls();
		flushReceiverName();
		if (searchBox != null) {
			search = searchBox.getValue();
		}
		rememberedSearch = search;
		scanGeneration++;
		Cinema.LOGGER.info("cinema receiver gui: closed {}", receiver.getBlockPos().toShortString());
		if (parent != null && minecraft != null) {
			Minecraft client = minecraft;
			Screen target = parent;
			client.execute(() -> client.setScreen(target));
		} else {
			super.onClose();
		}
	}

	private void flushPendingControls() {
		if (seekSlider != null && seekSlider.dragging && receiver.durationSeconds() > 0.0) {
			seekSlider.dragging = false;
			if (sendSeek(seekSlider.draftSeconds)) {
				pendingSeekSeconds = seekSlider.draftSeconds;
				pendingSeekUntilMillis = System.currentTimeMillis()
					+ AvReceiverPlayback.SEEK_COOLDOWN_MILLIS;
			}
		}
		if (volumeSlider != null && volumeSlider.dragging) {
			volumeSlider.dragging = false;
			pendingVolumeDb = volumeSlider.currentDb();
			pendingVolumeUntilMillis = System.currentTimeMillis() + 1_500L;
			sendVolume(pendingVolumeDb);
		}
		for (SpeakerLevelSlider slider : speakerLevelSliders.values()) {
			if (slider.dragging) {
				slider.dragging = false;
				slider.sendCurrentLevel();
			}
		}
	}

	private void switchTab(Tab newTab) {
		if (tab == newTab) {
			return;
		}
		tab = newTab;
		scroll = 0;
		if (newTab == Tab.SETTINGS) {
			selectedLibraryId = libraryId;
			libraryScroll = 0;
		}
		rebuildWidgets();
		if (newTab == Tab.SETTINGS) {
			revealSelectedLibrary();
		}
	}

	private Component libraryButtonLabel() {
		LibraryRegistry.Library library = LibraryRegistry.findById(libraryId);
		String name = library == null ? "Default" : library.name();
		if (name.length() > 14) {
			name = name.substring(0, 13) + "…";
		}
		return Component.literal("Library: " + name);
	}

	private void cycleLibrary() {
		List<LibraryRegistry.Library> libraries = LibraryRegistry.libraries();
		if (libraries.size() <= 1) {
			return;
		}
		int index = 0;
		for (int i = 0; i < libraries.size(); i++) {
			if (libraries.get(i).id().equals(libraryId)) {
				index = i;
				break;
			}
		}
		LibraryRegistry.Library next = libraries.get((index + 1) % libraries.size());
		loadFolder(next.id(), "", false);
	}

	private void addLibrary() {
		String raw = libraryPathDraft == null ? "" : libraryPathDraft.trim();
		if (raw.isEmpty()) {
			setStatus("Enter an external library folder path first");
			return;
		}
		try {
			LibraryRegistry.Library library = LibraryRegistry.add(Path.of(raw));
			selectedLibraryId = library.id();
			libraryPathDraft = "";
			if (libraryPathBox != null) {
				libraryPathBox.setValue("");
			}
			setStatus("Added library: " + library.name());
			rebuildWidgets();
			revealSelectedLibrary();
		} catch (Exception e) {
			setStatus("Add library failed: " + e.getMessage());
		}
	}

	private void removeLibrary() {
		if (selectedLibraryId == null || LibraryRegistry.DEFAULT_ID.equals(selectedLibraryId)) {
			setStatus("The default library cannot be removed");
			return;
		}
		String removedId = selectedLibraryId;
		if (LibraryRegistry.remove(removedId)) {
			invalidateLibraryCache(removedId);
			if (libraryId.equals(removedId)) {
				libraryId = LibraryRegistry.DEFAULT_ID;
				folder = "";
				folderLoaded = false;
			}
			if (rememberedLibraryId.equals(removedId)) {
				rememberedLibraryId = LibraryRegistry.DEFAULT_ID;
				rememberedFolder = "";
			}
			selectedLibraryId = LibraryRegistry.DEFAULT_ID;
			libraryScroll = 0;
			setStatus("Library removed");
			rebuildWidgets();
		}
	}

	private void revealSelectedLibrary() {
		List<LibraryRegistry.Library> libraries = LibraryRegistry.libraries();
		int index = -1;
		for (int i = 0; i < libraries.size(); i++) {
			if (libraries.get(i).id().equals(selectedLibraryId)) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			libraryScroll = 0;
			return;
		}
		int rows = visibleLibraryRows();
		if (index < libraryScroll) {
			libraryScroll = index;
		} else if (index >= libraryScroll + rows) {
			libraryScroll = Math.max(0, index - rows + 1);
		}
	}

	private int libraryRowAt(double mouseX, double mouseY) {
		if (mouseX < contentLeft || mouseX >= contentLeft + contentWidth
			|| mouseY < contentTop + 98 || mouseY >= contentBottom - 22) {
			return -1;
		}
		int firstY = contentTop + 98;
		int row = (int) ((mouseY - firstY) / 11.0) + libraryScroll;
		if (row < 0 || row >= LibraryRegistry.libraries().size()) {
			return -1;
		}
		return row;
	}

	private void syncWidgets() {
		syncReceiverName();
		if (playPauseButton != null) {
			playPauseButton.setMessage(Component.literal(
				receiver.state() == AvReceiverBlockEntity.State.PLAYING ? "Pause" : "Play"));
		}
		if (muteButton != null) {
			muteButton.setMessage(Component.literal(receiver.isMuted() ? "Unmute" : "Mute"));
		}
		if (stopButton != null) {
			stopButton.active = receiver.state() != AvReceiverBlockEntity.State.STOPPED;
		}
		if (libraryButton != null) {
			libraryButton.setMessage(libraryButtonLabel());
		}
		if (removeLibraryButton != null) {
			removeLibraryButton.active = selectedLibraryId != null
				&& !LibraryRegistry.DEFAULT_ID.equals(selectedLibraryId);
		}
		if (seekSlider != null) {
			boolean seekCoolingDown = seekCooldownRemainingMillis() > 0L;
			seekSlider.active = receiver.durationSeconds() > 0.0 && !seekCoolingDown;
			seekSlider.setExternal(currentPosition());
			if (seekBackButton != null) {
				seekBackButton.active = seekSlider.active;
			}
			if (seekForwardButton != null) {
				seekForwardButton.active = seekSlider.active;
			}
		}
		if (volumeSlider != null) {
			volumeSlider.setExternal(receiver.volumeDb());
		}
		syncSpeakerLevels();
		for (Map.Entry<BlockPos, Button> entry : speakerAccessButtons.entrySet()) {
			entry.getValue().active = canAccessSpeaker(entry.getKey());
		}
		if (playSelectedButton != null) {
			playSelectedButton.active = selectedFile() != null && !pendingPlay;
		}
		if (backButton != null) {
			backButton.active = !folder.isEmpty();
		}
	}

	private void onReceiverNameChanged() {
		if (syncingReceiverName) {
			return;
		}
		receiverNameDirty = true;
		receiverNameDirtyAtMillis = System.currentTimeMillis() + NAME_SAVE_DEBOUNCE_MS;
	}

	private void flushReceiverName() {
		if (!receiverNameDirty || receiverNameBox == null) {
			return;
		}
		receiverNameDirty = false;
		DeviceNameClient.setReceiverName(receiver.getBlockPos(), receiverNameBox.getValue());
	}

	private void syncReceiverName() {
		if (receiverNameBox == null || receiverNameBox.isFocused() || receiverNameDirty) {
			return;
		}
		if (receiverNameBox.getValue().equals(receiver.name())) {
			return;
		}
		syncingReceiverName = true;
		receiverNameBox.setValue(receiver.name());
		syncingReceiverName = false;
	}

	private void drawStatus(GuiGraphics graphics) {
		int y = top + 41;
		String media = receiver.mediaKey().isEmpty()
			? "No media selected"
			: mediaFileName(receiver.mediaKey());
		String state = receiver.state().name().toLowerCase(Locale.ROOT);
		String time = formatTime(currentPosition()) + " / " + formatTime(receiver.durationSeconds());
		String line1 = state + "  |  " + media + "  |  " + time;
		graphics.drawString(font, truncate(line1, contentWidth), contentLeft, y, 0xE8E8E8, false);

		String line2;
		if (System.currentTimeMillis() < statusUntil && !statusMessage.isEmpty()) {
			line2 = statusMessage;
		} else if (mediaMissing) {
			line2 = "Media file missing from its library";
		} else {
			String video = videoTargetLabel();
			AvReceiverPlayback playback = AvReceiverPlaybackManager.playbackAt(receiver.getBlockPos());
			if (playback != null && playback.player() != null && playback.player().isAudioOnly()) {
				video = "Video: audio only";
			}
			String audio;
			if (playback == null || playback.player() == null) {
				audio = "Audio: idle";
			} else if (playback.physicalChannels() > 0) {
				audio = "Audio: 5.1 physical " + playback.physicalChannels() + "/6";
			} else {
				audio = "Audio: virtual 5.1";
			}
			line2 = video + "  |  " + audio;
		}
		graphics.drawString(font, truncate(line2, contentWidth), contentLeft, y + 10,
			mediaMissing ? 0xFF7070 : 0x9A9A9A, false);
	}

	private void drawTabAccent(GuiGraphics graphics) {
		int x = contentLeft + tab.ordinal() * (tabWidth + 2);
		graphics.fill(x, tabY + TAB_HEIGHT, x + tabWidth, tabY + TAB_HEIGHT + 2, 0xFFFFC107);
	}

	private void drawContent(GuiGraphics graphics, int mouseX, int mouseY) {
		switch (tab) {
			case MEDIA -> drawMediaTab(graphics, mouseX, mouseY);
			case SPEAKERS -> drawSpeakersTab(graphics);
			case SIGNAL -> drawSignalTab(graphics);
			case SETTINGS -> drawSettingsTab(graphics);
		}
	}

	private void drawMediaTab(GuiGraphics graphics, int mouseX, int mouseY) {
		String folderText = folder.isEmpty() ? "/" : "/" + folder + "/";
		graphics.drawString(font, truncate(folderText, contentWidth - 154),
			contentLeft + 154, contentTop + 4, 0xA0A0A0, false);
		int top = listTop();
		int bottom = listBottom();
		graphics.fill(contentLeft, top, contentLeft + contentWidth, bottom, 0x70000000);
		List<EntryView> filtered = filteredEntries();
		int rows = visibleRows();
		int maxScroll = Math.max(0, filtered.size() - rows);
		scroll = Math.max(0, Math.min(maxScroll, scroll));

		if (scanning) {
			graphics.drawString(font, "Scanning media library...", contentLeft + 4, top + 4, 0xFFD54F, false);
		} else if (filtered.isEmpty()) {
			graphics.drawString(font, "No media files in this folder", contentLeft + 4, top + 4, 0x9A9A9A, false);
		} else {
			graphics.enableScissor(contentLeft, top, contentLeft + contentWidth, bottom);
			for (int i = 0; i < rows; i++) {
				int index = scroll + i;
				if (index >= filtered.size()) {
					break;
				}
				EntryView view = filtered.get(index);
				int rowY = top + i * ROW_HEIGHT;
				boolean selected = view.entry.key().equals(selectedKey);
				if (selected) {
					graphics.fill(contentLeft, rowY, contentLeft + contentWidth, rowY + ROW_HEIGHT - 1, 0x50FFC107);
				} else if (mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
					&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth) {
					graphics.fill(contentLeft, rowY, contentLeft + contentWidth, rowY + ROW_HEIGHT - 1, 0x30FFFFFF);
				}
				String line = view.entry.directory()
					? "[DIR] " + view.entry.name()
					: view.entry.name() + "  " + shortMetadata(view);
				int color = view.entry.directory()
					? 0xFFD54F
					: view.error() != null ? 0xFF7070 : selected ? 0xFFFFFF : 0xB8B8B8;
				graphics.drawString(font, truncate(line, contentWidth - 8), contentLeft + 4, rowY + 1, color, false);
			}
			graphics.disableScissor();
		}

		if (maxScroll > 0) {
			int trackHeight = Math.max(1, bottom - top);
			int thumbHeight = Math.max(8, trackHeight * rows / Math.max(rows, filtered.size()));
			int thumbY = top + (trackHeight - thumbHeight) * scroll / maxScroll;
			graphics.fill(contentLeft + contentWidth - 2, top, contentLeft + contentWidth, bottom, 0x50000000);
			graphics.fill(contentLeft + contentWidth - 2, thumbY, contentLeft + contentWidth,
				thumbY + thumbHeight, 0xFFAAAAAA);
		}

		EntryView selected = selectedView();
		String detail = selected == null
			? "Select media to play"
			: (selected.entry.directory() ? "Folder: " + selected.entry.key() : fullMetadata(selected));
		graphics.drawString(font, truncate(detail, contentWidth), contentLeft, contentBottom - 34,
			0x9A9A9A, false);
	}

	private void drawSpeakersTab(GuiGraphics graphics) {
		int y = contentTop;
		AvReceiverPlayback playback = AvReceiverPlaybackManager.playbackAt(receiver.getBlockPos());
		int physical = playback == null ? 0 : playback.physicalChannels();
		graphics.drawString(font, "Speaker levels", contentLeft, y, 0xE8E8E8, false);
		graphics.drawString(font, "Linked " + receiver.speakerTargets().size()
			+ "  ·  routing " + (playback == null ? "idle" : "physical " + physical + "/6")
			+ "  ·  detected " + speakerLines.size(),
			contentLeft, y + 11, 0x9A9A9A, false);
		graphics.drawString(font, "Channel trim "
			+ formatDb(SpeakerBlockEntity.MIN_LEVEL_DB) + " .. "
			+ formatDb(SpeakerBlockEntity.MAX_LEVEL_DB)
			+ " dB; changes save immediately.",
			contentLeft, y + 22, 0x808080, false);
		if (linkedSpeakers.isEmpty()) {
			graphics.drawString(font, "No linked speakers. Use the Cinema Cable on this receiver, then a speaker.",
				contentLeft, y + 40, 0x9A9A9A, false);
			return;
		}
		int visibleRows = visibleSpeakerRows();
		int labelWidth = speakerLabelWidth();
		for (int i = 0; i < visibleRows && speakerScroll + i < linkedSpeakers.size(); i++) {
			SpeakerRow row = linkedSpeakers.get(speakerScroll + i);
			SpeakerLevelSlider slider = speakerLevelSliders.get(row.pos());
			if (slider == null) {
				continue;
			}
			String label = row.name().isEmpty()
				? row.role().label() + " " + speakerTypeShortLabel(row.type())
					+ " " + row.pos().toShortString()
				: row.name() + " (" + row.role().label() + ")";
			graphics.drawString(font, truncate(label, labelWidth - 4),
				contentLeft, slider.getY() + 4, 0xB8B8B8, false);
		}
		int maxScroll = Math.max(0, linkedSpeakers.size() - visibleRows);
		if (maxScroll > 0) {
			int trackTop = speakerRowsTop();
			int trackBottom = contentBottom - 20;
			int trackHeight = Math.max(1, trackBottom - trackTop);
			int thumbHeight = Math.max(8,
				trackHeight * visibleRows / linkedSpeakers.size());
			int thumbY = trackTop
				+ (trackHeight - thumbHeight) * speakerScroll / maxScroll;
			int scrollbarX = contentLeft + contentWidth - 3;
			graphics.fill(scrollbarX, trackTop, contentLeft + contentWidth, trackBottom,
				0x50000000);
			graphics.fill(scrollbarX, thumbY, contentLeft + contentWidth,
				thumbY + thumbHeight, 0xFFAAAAAA);
		}
	}

	private void drawSignalTab(GuiGraphics graphics) {
		int y = contentTop;
		AvReceiverPlayback playback = AvReceiverPlaybackManager.playbackAt(receiver.getBlockPos());
		CinemaPlayer player = playback == null ? null : playback.player();
		String[] lines = {
			"Media: " + (receiver.mediaKey().isEmpty() ? "none" : receiver.mediaKey()),
			"Duration: " + formatTime(receiver.durationSeconds())
				+ "  Size: " + formatSize(receiver.mediaSize()),
			"Fingerprint: " + shortHash(receiver.mediaSha256()),
			probeLine(),
			"Playback: " + playbackLine(player),
			"Receiver: " + receiver.state().name().toLowerCase(Locale.ROOT)
				+ "  " + String.format(Locale.ROOT, "%.1f", currentPosition())
				+ " / " + String.format(Locale.ROOT, "%.1f", receiver.durationSeconds()) + " s",
			"Volume: " + formatSignedDb(receiver.volumeDb()) + " dBFS"
				+ (receiver.isMuted() ? " (muted)" : ""),
			"Projector: " + (receiver.videoTarget() == null ? "none"
				: receiver.videoTarget().toShortString() + (projectorOnline() ? " online" : " offline")),
			"Sync: receiver block entity, client poll every 10 ticks"
		};
		for (String line : lines) {
			graphics.drawString(font, truncate(line, contentWidth), contentLeft, y, 0xB8B8B8, false);
			y += 11;
		}
	}

	private static String playbackLine(CinemaPlayer player) {
		if (player == null) {
			return "not open";
		}
		if (player.isAudioOnly()) {
			return "audio only";
		}
		return player.width() + "x" + player.height() + "  presented=" + player.presentedFrames()
			+ "  dropped=" + player.droppedFrames();
	}

	private void drawSettingsTab(GuiGraphics graphics) {
		int y = contentTop;
		graphics.drawString(font, "Link a projector with the Cinema Cable.",
			contentLeft, y + 68, 0x9A9A9A, false);
		graphics.drawString(font, "Libraries (hover for details):",
			contentLeft, y + 88, 0xB8B8B8, false);
		List<LibraryRegistry.Library> libraries = LibraryRegistry.libraries();
		int firstRow = contentTop + 98;
		int rows = visibleLibraryRows();
		int maxScroll = Math.max(0, libraries.size() - rows);
		libraryScroll = Math.max(0, Math.min(maxScroll, libraryScroll));
		for (int i = 0; i < rows; i++) {
			int index = libraryScroll + i;
			if (index >= libraries.size()) {
				break;
			}
			LibraryRegistry.Library library = libraries.get(index);
			boolean selected = library.id().equals(selectedLibraryId);
			String line = (selected ? "> " : "  ") + library.name()
				+ (library.id().equals(LibraryRegistry.DEFAULT_ID) ? "  [default]" : "  [external]");
			graphics.drawString(font, truncate(line, contentWidth), contentLeft, firstRow + i * 11,
				selected ? 0xFFFFFF : 0xB8B8B8, false);
		}
		if (maxScroll > 0) {
			int trackHeight = Math.max(1, contentBottom - 22 - firstRow);
			int thumbHeight = Math.max(8, trackHeight * rows / Math.max(rows, libraries.size()));
			int thumbY = firstRow + (trackHeight - thumbHeight) * libraryScroll / maxScroll;
			graphics.fill(contentLeft + contentWidth - 2, firstRow, contentLeft + contentWidth,
				contentBottom - 22, 0x50000000);
			graphics.fill(contentLeft + contentWidth - 2, thumbY, contentLeft + contentWidth,
				thumbY + thumbHeight, 0xFFAAAAAA);
		}
	}

	private void renderWrappedTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
		int maxWidth = Math.max(80, Math.min(240, width - 32));
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String line : text.split("\\n")) {
			lines.addAll(font.split(Component.literal(line), maxWidth));
		}
		graphics.renderTooltip(font, lines, mouseX, mouseY);
	}

	private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (tab == Tab.SPEAKERS) {
			if (!inSpeakerList(mouseX, mouseY)) {
				return;
			}
			int rowIndex = speakerScroll
				+ (int) ((mouseY - speakerRowsTop()) / SPEAKER_ROW_HEIGHT);
			if (rowIndex < 0 || rowIndex >= linkedSpeakers.size()) {
				return;
			}
			SpeakerRow row = linkedSpeakers.get(rowIndex);
			double distance = Math.sqrt(Vec3.atCenterOf(row.pos())
				.distanceToSqr(Vec3.atCenterOf(receiver.getBlockPos())));
			String title = row.name().isEmpty() ? "(unnamed)" : row.name();
			renderWrappedTooltip(graphics,
				title + "\nRole: " + row.role().label() + " · " + speakerTypeLabel(row.type())
					+ "\nBlock: " + row.pos().toShortString()
					+ "\nDistance from receiver: " + String.format(Locale.ROOT, "%.1f m", distance)
					+ "\nLevel: " + formatSignedDb(row.levelDb()) + " dB"
					+ "\nPing plays a sound and a particle marker at this block.",
				mouseX, mouseY);
			return;
		}
		if (tab == Tab.SETTINGS) {
			if (libraryPathBox != null
				&& mouseX >= libraryPathBox.getX() && mouseX < libraryPathBox.getX() + libraryPathBox.getWidth()
				&& mouseY >= libraryPathBox.getY() && mouseY < libraryPathBox.getY() + libraryPathBox.getHeight()) {
				renderWrappedTooltip(graphics,
					"Absolute path to a folder containing videos or music. The library is stored locally in "
						+ "cinema/libraries.json; receiver keys use lib/<id>/<relative>.",
					mouseX, mouseY);
				return;
			}
			if (mouseY >= contentTop + 88 && mouseY < contentTop + 97
				&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth) {
				renderWrappedTooltip(graphics,
					"Click a library row to select it. " + LibraryRegistry.libraries().size()
						+ " libraries (default + external). External libraries are client-local.",
					mouseX, mouseY);
				return;
			}
			int libraryRow = libraryRowAt(mouseX, mouseY);
			if (libraryRow < 0) {
				return;
			}
			LibraryRegistry.Library library = LibraryRegistry.libraries().get(libraryRow);
			renderWrappedTooltip(graphics,
				library.name() + "  |  " + library.root(), mouseX, mouseY);
			return;
		}
		if (!inList(mouseX, mouseY)) {
			return;
		}
		List<EntryView> filtered = filteredEntries();
		int row = (int) ((mouseY - listTop()) / ROW_HEIGHT) + scroll;
		if (row < 0 || row >= filtered.size()) {
			return;
		}
		EntryView view = filtered.get(row);
		String text = view.entry.directory()
			? "Folder: " + view.entry.key()
			: view.entry.key() + "  |  " + formatSize(view.entry.size()) + "  |  " + shortMetadata(view);
		if (view.error() != null) {
			text += "  |  " + view.error();
		}
		renderWrappedTooltip(graphics, text, mouseX, mouseY);
	}

	private void loadFolder(String newLibraryId, String newFolder, boolean force) {
		if (scanning && !force) {
			return;
		}
		String targetLibrary = newLibraryId == null ? LibraryRegistry.DEFAULT_ID : newLibraryId;
		LibraryRegistry.Library library = LibraryRegistry.findById(targetLibrary);
		if (library == null) {
			targetLibrary = LibraryRegistry.DEFAULT_ID;
			library = LibraryRegistry.findById(targetLibrary);
		}
		if (library == null) {
			scanning = false;
			setStatus("Media library is unavailable");
			return;
		}
		libraryId = targetLibrary;
		folder = newFolder == null ? "" : newFolder;
		rememberedLibraryId = libraryId;
		rememberedFolder = folder;
		folderLoaded = false;
		scanning = true;
		scroll = 0;
		selectedKey = "";
		search = "";
		if (searchBox != null) {
			searchBox.setValue("");
		}
		int generation = ++scanGeneration;
		String targetFolder = folder;
		LibraryRegistry.Library cachedLibrary = library;

		if (!force) {
			List<EntryView> cached = cachedFolder(cachedLibrary, targetFolder);
			if (cached != null) {
				entries.clear();
				entries.addAll(cached);
				scanning = false;
				folderLoaded = true;
				syncWidgets();
				return;
			}
		}

		String finalLibrary = targetLibrary;
		Thread thread = new Thread(() -> {
			List<EntryView> loaded = new ArrayList<>();
			String error = null;
			try {
				List<MediaLibrary.Entry> listed = MediaLibrary.list(finalLibrary, targetFolder);
				int probed = 0;
				for (MediaLibrary.Entry entry : listed) {
					if (entry.directory()) {
						loaded.add(new EntryView(entry, null, null));
					} else if (probed++ < PROBE_LIMIT) {
						try {
							loaded.add(new EntryView(entry, MediaLibrary.probe(MediaLibrary.resolve(entry.key())), null));
						} catch (Exception e) {
							loaded.add(new EntryView(entry, null, e.getMessage()));
						}
					} else {
						loaded.add(new EntryView(entry, null, null));
					}
				}
			} catch (Exception e) {
				error = e.getMessage();
			}
			String finalError = error;
			if (finalError == null) {
				// Cache even when the screen closed during the scan, so reopening
				// does not start the same probe work again.
				cacheFolder(cachedLibrary, targetFolder, loaded);
			}
			Minecraft.getInstance().execute(() -> {
				if (generation != scanGeneration) {
					return;
				}
				entries.clear();
				entries.addAll(loaded);
				scanning = false;
				folderLoaded = true;
				if (finalError != null) {
					setStatus("Scan failed: " + finalError);
				}
				syncWidgets();
			});
		}, "cinema-media-scan");
		thread.setDaemon(true);
		thread.start();
	}

	private static List<EntryView> cachedFolder(LibraryRegistry.Library library, String folder) {
		return folderCache.get(new FolderCacheKey(library.id(), library.root(), folder));
	}

	private static void cacheFolder(LibraryRegistry.Library library, String folder,
									List<EntryView> entries) {
		folderCache.put(new FolderCacheKey(library.id(), library.root(), folder),
			List.copyOf(entries));
	}

	private static void invalidateLibraryCache(String libraryId) {
		folderCache.keySet().removeIf(key -> key.libraryId().equals(libraryId));
	}

	private void playSelected() {
		EntryView selected = selectedFile();
		if (selected == null || pendingPlay) {
			return;
		}
		pendingPlay = true;
		setStatus("Preparing " + selected.entry.key() + " ...");
		syncWidgets();
		String key = selected.entry.key();
		MediaLibrary.Probe knownProbe = selected.probe();
		Thread thread = new Thread(() -> {
			try {
				MediaLibrary.Metadata metadata = MediaLibrary.describe(MediaLibrary.resolve(key), key);
				Minecraft.getInstance().execute(() -> {
					AvReceiverControlClient.selectMedia(receiver.getBlockPos(), metadata);
					receiverProbe = knownProbe;
					receiverProbeKey = knownProbe != null ? metadata.key() : "";
					pendingPlay = false;
					setStatus("Play requested: " + metadata.key());
					syncWidgets();
				});
			} catch (Exception e) {
				Minecraft.getInstance().execute(() -> {
					pendingPlay = false;
					setStatus("Play failed: " + e.getMessage());
					syncWidgets();
				});
			}
		}, "cinema-media-prepare");
		thread.setDaemon(true);
		thread.start();
	}

	private void togglePlayPause() {
		if (receiver.mediaKey().isEmpty()) {
			setStatus("Select media from the Media tab first");
			return;
		}
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING) {
			AvReceiverControlClient.sendSimple(receiver.getBlockPos(), AvReceiverControlPayload.Action.PAUSE);
		} else {
			AvReceiverControlClient.sendSimple(receiver.getBlockPos(), AvReceiverControlPayload.Action.PLAY);
		}
	}

	private void sendStop() {
		AvReceiverControlClient.sendSimple(receiver.getBlockPos(), AvReceiverControlPayload.Action.STOP);
	}

	private void seekRelative(double delta) {
		double target = Math.max(0.0, currentPosition() + delta);
		if (receiver.durationSeconds() > 0.0) {
			target = Math.min(target, receiver.durationSeconds());
		}
		sendSeek(target);
	}

	private boolean sendSeek(double seconds) {
		if (receiver.durationSeconds() <= 0.0) {
			return false;
		}
		long cooldownRemaining = seekCooldownRemainingMillis();
		if (cooldownRemaining > 0L) {
			setStatus("Seek available again in "
				+ String.format(Locale.ROOT, "%.1f", cooldownRemaining / 1000.0) + " s");
			return false;
		}
		seekCooldownUntilMillis = System.currentTimeMillis() + AvReceiverPlayback.SEEK_COOLDOWN_MILLIS;
		AvReceiverControlClient.sendValue(receiver.getBlockPos(),
			AvReceiverControlPayload.Action.SEEK, seconds);
		setStatus("Seek requested: " + formatTime(seconds));
		return true;
	}

	/**
	 * Seek is unavailable until both the local request cooldown and the
	 * playback-side restart cooldown have elapsed.
	 */
	private long seekCooldownRemainingMillis() {
		long remaining = seekCooldownUntilMillis - System.currentTimeMillis();
		AvReceiverPlayback playback = AvReceiverPlaybackManager.playbackAt(receiver.getBlockPos());
		if (playback != null) {
			remaining = Math.max(remaining, playback.seekCooldownRemainingMillis());
		}
		return Math.max(0L, remaining);
	}

	private void sendVolume(double volumeDb) {
		AvReceiverControlClient.sendValue(receiver.getBlockPos(),
			AvReceiverControlPayload.Action.SET_VOLUME_DB, volumeDb);
	}

	private void toggleMute() {
		AvReceiverControlClient.sendValue(receiver.getBlockPos(),
			AvReceiverControlPayload.Action.SET_MUTED,
			receiver.isMuted() ? 0.0 : 1.0);
	}

	private void refreshWarnings() {
		if (receiver.mediaKey().isEmpty()) {
			mediaMissing = false;
			return;
		}
		try {
			mediaMissing = !Files.isRegularFile(MediaLibrary.resolve(receiver.mediaKey()));
		} catch (IOException e) {
			mediaMissing = true;
		}
		if (mediaMissing) {
			setStatus("Media file missing: " + receiver.mediaKey());
		}
	}

	private void refreshSpeakers() {
		speakerLines.clear();
		linkedSpeakers.clear();
		Level level = receiver.getLevel();
		if (level == null) {
			speakerRowsKey = "";
			return;
		}
		BlockPos center = receiver.getBlockPos();
		Vec3 origin = Vec3.atCenterOf(center);
		ChunkPos centerChunk = new ChunkPos(center);
		int radius = 3;
		for (int chunkX = centerChunk.x - radius; chunkX <= centerChunk.x + radius; chunkX++) {
			for (int chunkZ = centerChunk.z - radius; chunkZ <= centerChunk.z + radius; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
						continue;
					}
					if (Vec3.atCenterOf(speaker.getBlockPos()).distanceToSqr(origin) > 48.0 * 48.0) {
						continue;
					}
					boolean linked = receiver.speakerTargets().contains(speaker.getBlockPos());
					speakerLines.add((linked ? "LINKED " : "       ") + speaker.role().label() + "  "
						+ speaker.type().id() + "  " + speaker.getBlockPos().toShortString());
					if (linked) {
						linkedSpeakers.add(new SpeakerRow(speaker.getBlockPos(), speaker.name(),
							speaker.role(), speaker.type(), speaker.levelDb()));
					}
				}
			}
		}
		speakerLines.sort(String.CASE_INSENSITIVE_ORDER);
		linkedSpeakers.sort(Comparator.comparingInt(row -> row.role().ordinal()));
		StringBuilder key = new StringBuilder();
		for (SpeakerRow row : linkedSpeakers) {
			key.append(row.pos().asLong()).append(':').append(row.name()).append(':')
				.append(row.role().name()).append(':').append(row.type().id()).append(';');
		}
		speakerRowsKey = key.toString();
	}

	private void addSpeakerLevelWidgets() {
		int visibleRows = visibleSpeakerRows();
		int maxScroll = Math.max(0, linkedSpeakers.size() - visibleRows);
		speakerScroll = Math.max(0, Math.min(maxScroll, speakerScroll));
		boolean scrollable = linkedSpeakers.size() > visibleRows;
		int labelWidth = speakerLabelWidth();
		int pingWidth = 34;
		int accessWidth = 46;
		int pingX = contentLeft + labelWidth;
		int accessX = pingX + pingWidth + 2;
		int sliderX = accessX + accessWidth + 2;
		int sliderWidth = Math.max(60,
			contentLeft + contentWidth - (scrollable ? 6 : 0) - sliderX);
		for (int i = 0; i < visibleRows && speakerScroll + i < linkedSpeakers.size(); i++) {
			SpeakerRow row = linkedSpeakers.get(speakerScroll + i);
			int rowY = speakerRowsTop() + i * SPEAKER_ROW_HEIGHT;
			addRenderableWidget(Button.builder(Component.literal("Ping"),
					button -> pingSpeaker(row))
				.bounds(pingX, rowY, pingWidth, 16).build());
			Button access = Button.builder(Component.literal("Access"),
					button -> openSpeakerAccess(row))
				.bounds(accessX, rowY, accessWidth, 16).build();
			access.active = canAccessSpeaker(row.pos());
			addRenderableWidget(access);
			speakerAccessButtons.put(row.pos(), access);
			SpeakerLevelSlider slider = new SpeakerLevelSlider(sliderX, rowY, sliderWidth, 16,
				row.pos(), row.levelDb());
			addRenderableWidget(slider);
			speakerLevelSliders.put(row.pos(), slider);
		}
	}

	private boolean canAccessSpeaker(BlockPos speakerPos) {
		if (minecraft == null || minecraft.player == null) {
			return false;
		}
		if (minecraft.player.blockPosition().distSqr(speakerPos) <= 16.0 * 16.0) {
			return true;
		}
		if (minecraft.player.blockPosition().distSqr(receiver.getBlockPos())
			> RemoteControlAccess.MAX_REMOTE_DISTANCE * RemoteControlAccess.MAX_REMOTE_DISTANCE) {
			return false;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = minecraft.player.getItemInHand(hand);
			if (stack.is(Cinema.REMOTE_CONTROLLER_ITEM)
				&& RemoteBinding.matches(stack, minecraft.player.level().dimension(),
					receiver.getBlockPos(), RemoteBinding.Kind.RECEIVER)) {
				return true;
			}
		}
		return false;
	}

	private void openSpeakerAccess(SpeakerRow row) {
		if (minecraft == null || minecraft.level == null) {
			return;
		}
		if (minecraft.level.getBlockEntity(row.pos()) instanceof SpeakerBlockEntity speaker) {
			minecraft.setScreen(new SpeakerSpecsConfigScreen(this, speaker));
		} else {
			setStatus("Speaker chunk is not loaded");
		}
	}

	private int speakerRowsTop() {
		return contentTop + 38;
	}

	private int speakerLabelWidth() {
		return Math.max(90, Math.min(150, contentWidth * 2 / 5));
	}

	private int visibleSpeakerRows() {
		int available = contentBottom - 20 - speakerRowsTop();
		return Math.max(1, available / SPEAKER_ROW_HEIGHT);
	}

	private boolean inSpeakerList(double mouseX, double mouseY) {
		return mouseX >= contentLeft && mouseX < contentLeft + contentWidth
			&& mouseY >= speakerRowsTop() && mouseY < contentBottom - 20;
	}

	private void syncSpeakerLevels() {
		if (speakerLevelSliders.isEmpty()) {
			return;
		}
		Level level = receiver.getLevel();
		if (level == null) {
			return;
		}
		for (Map.Entry<BlockPos, SpeakerLevelSlider> entry : speakerLevelSliders.entrySet()) {
			if (level.getBlockEntity(entry.getKey()) instanceof SpeakerBlockEntity speaker) {
				entry.getValue().setExternal(speaker.levelDb());
			}
		}
	}

	private boolean hasDraggingSpeakerLevel() {
		for (SpeakerLevelSlider slider : speakerLevelSliders.values()) {
			if (slider.dragging) {
				return true;
			}
		}
		return false;
	}

	private void pingSpeaker(SpeakerRow row) {
		if (minecraft == null || minecraft.level == null) {
			return;
		}
		minecraft.getSoundManager().play(new SimpleSoundInstance(
			SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.0f, 1.0f,
			minecraft.level.random, row.pos()));
		SpeakerLocator.highlight(minecraft, row.pos());
		String label = row.name().isEmpty() ? row.role().label() : row.name();
		setStatus("Ping " + label + " @ " + row.pos().toShortString());
	}

	private void ensureReceiverProbe() {
		String key = receiver.mediaKey();
		if (key.isEmpty() || key.equals(receiverProbeKey) || receiverProbePending) {
			return;
		}
		receiverProbePending = true;
		receiverProbeKey = key;
		receiverProbe = null;
		Thread thread = new Thread(() -> {
			MediaLibrary.Probe probe = null;
			try {
				probe = MediaLibrary.probe(MediaLibrary.resolve(key));
			} catch (Exception ignored) {
			}
			MediaLibrary.Probe finalProbe = probe;
			Minecraft.getInstance().execute(() -> {
				if (key.equals(receiver.mediaKey())) {
					receiverProbe = finalProbe;
				}
				receiverProbePending = false;
			});
		}, "cinema-receiver-probe");
		thread.setDaemon(true);
		thread.start();
	}

	private boolean projectorOnline() {
		if (receiver.videoTarget() == null || receiver.getLevel() == null) {
			return false;
		}
		return receiver.getLevel().getBlockEntity(receiver.videoTarget()) instanceof ProjectorBlockEntity;
	}

	private String videoTargetLabel() {
		BlockPos target = receiver.videoTarget();
		if (target == null) {
			return "Video: none";
		}
		String pos = target.toShortString();
		if (receiver.getLevel() != null
			&& receiver.getLevel().getBlockEntity(target) instanceof ProjectorBlockEntity projector) {
			return "Video: " + (projector.name().isEmpty()
				? pos : projector.name() + " (" + pos + ")") + " online";
		}
		return "Video: " + pos + " offline";
	}

	private double currentPosition() {
		double position = receiver.positionSeconds();
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING && receiver.getLevel() != null) {
			position += Math.max(0L, receiver.getLevel().getGameTime() - receiver.stateChangedGameTime()) / 20.0;
		}
		if (receiver.durationSeconds() > 0.0) {
			position = Math.min(position, receiver.durationSeconds());
		}
		return Math.max(0.0, position);
	}

	private int listTop() {
		return contentTop + 40;
	}

	private int listBottom() {
		return Math.max(listTop() + ROW_HEIGHT, contentBottom - 36);
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - listTop()) / ROW_HEIGHT);
	}

	private int visibleLibraryRows() {
		return Math.max(1, (contentBottom - 22 - (contentTop + 98)) / 11);
	}

	private boolean inList(double mouseX, double mouseY) {
		return mouseX >= contentLeft && mouseX < contentLeft + contentWidth
			&& mouseY >= listTop() && mouseY < listBottom();
	}

	private List<EntryView> filteredEntries() {
		if (search.isBlank()) {
			return entries;
		}
		String needle = search.toLowerCase(Locale.ROOT);
		List<EntryView> filtered = new ArrayList<>();
		for (EntryView view : entries) {
			if (view.entry.name().toLowerCase(Locale.ROOT).contains(needle)) {
				filtered.add(view);
			}
		}
		return filtered;
	}

	private EntryView selectedView() {
		for (EntryView view : entries) {
			if (view.entry.key().equals(selectedKey)) {
				return view;
			}
		}
		return null;
	}

	private EntryView selectedFile() {
		EntryView view = selectedView();
		return view == null || view.entry.directory() ? null : view;
	}

	private String probeLine() {
		MediaLibrary.Probe probe = receiverProbe;
		if (probe == null) {
			return "Video: metadata pending  |  Audio: metadata pending";
		}
		String video = probe.video() == null ? "audio only"
			: probe.video().width() + "x" + probe.video().height()
				+ " " + String.format(Locale.ROOT, "%.1f", probe.video().frameRate()) + "fps "
				+ probe.video().codec() + " " + (probe.video().isHdr() ? "HDR" : "SDR");
		String audio = probe.audio() == null ? "no audio"
			: probe.audio().codec().toUpperCase(Locale.ROOT) + " "
				+ probe.audio().channels() + "ch " + probe.audio().sampleRate() + "Hz";
		return "Video: " + video + "  |  Audio: " + audio;
	}

	private String shortMetadata(EntryView view) {
		if (view.probe() == null) {
			return view.error() != null ? "metadata error" : "metadata pending";
		}
		MediaLibrary.Probe probe = view.probe();
		if (probe.audioOnly()) {
			return "audio  " + audioSummary(probe) + "  " + formatTime(probe.durationSeconds());
		}
		return probe.video().width() + "x" + probe.video().height()
			+ "  " + probe.video().codec()
			+ "  " + formatTime(probe.durationSeconds());
	}

	private String fullMetadata(EntryView view) {
		if (view.probe() == null) {
			return view.entry.name() + (view.error() != null ? "  |  " + view.error() : "");
		}
		MediaLibrary.Probe probe = view.probe();
		if (probe.audioOnly()) {
			return view.entry.name() + "  |  " + formatSize(view.entry.size())
				+ "  |  " + formatTime(probe.durationSeconds())
				+ "  |  audio only  |  " + audioSummary(probe);
		}
		String hdr = probe.video().isHdr() ? "HDR" : "SDR";
		String audio = probe.audio() == null ? "no audio"
			: probe.audio().channels() + "ch " + probe.audio().codec();
		return view.entry.name() + "  |  " + formatSize(view.entry.size())
			+ "  |  " + formatTime(probe.durationSeconds())
			+ "  |  " + probe.video().width() + "x" + probe.video().height()
			+ " " + hdr + " " + probe.video().codec() + "  |  " + audio;
	}

	private static String audioSummary(MediaLibrary.Probe probe) {
		if (probe.audio() == null) {
			return "no audio";
		}
		return probe.audio().codec().toUpperCase(Locale.ROOT) + " "
			+ probe.audio().channels() + "ch " + probe.audio().sampleRate() + "Hz";
	}

	private String shortHash(String sha) {
		if (sha == null || sha.isEmpty()) {
			return "none";
		}
		return sha.length() <= 16 ? sha : sha.substring(0, 16) + "...";
	}

	private void setStatus(String message) {
		statusMessage = message == null ? "" : message;
		statusUntil = System.currentTimeMillis() + 5_000L;
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

	private static String mediaFileName(String key) {
		if (key == null || key.isEmpty()) {
			return "none";
		}
		int slash = key.lastIndexOf('/');
		return slash < 0 ? key : key.substring(slash + 1);
	}

	private static String formatTime(double seconds) {
		if (!Double.isFinite(seconds) || seconds < 0.0) {
			seconds = 0.0;
		}
		int total = (int) Math.floor(seconds);
		int hours = total / 3600;
		int minutes = (total % 3600) / 60;
		int secs = total % 60;
		return hours > 0
			? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
			: String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024L * 1024L) {
			return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
		}
		return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	private static double volumeToValue(double volumeDb) {
		double clamped = AvReceiverBlockEntity.clampVolumeDb(volumeDb);
		return (clamped - AvReceiverBlockEntity.MIN_VOLUME_DB)
			/ (AvReceiverBlockEntity.MAX_VOLUME_DB - AvReceiverBlockEntity.MIN_VOLUME_DB);
	}

	private static double levelToValue(double levelDb) {
		double clamped = SpeakerBlockEntity.clampLevelDb(levelDb);
		return (clamped - SpeakerBlockEntity.MIN_LEVEL_DB)
			/ (SpeakerBlockEntity.MAX_LEVEL_DB - SpeakerBlockEntity.MIN_LEVEL_DB);
	}

	private static String formatDb(double db) {
		return String.format(Locale.ROOT, "%.1f", db);
	}

	private static String formatSignedDb(double db) {
		return String.format(Locale.ROOT, "%+.1f", db);
	}

	private static String speakerTypeLabel(SpeakerType type) {
		return switch (type) {
			case FLOORSTANDING -> "Floorstanding";
			case BOOKSHELF -> "Bookshelf";
			case CENTER -> "Center";
			case SUBWOOFER -> "Subwoofer";
		};
	}

	private static String speakerTypeShortLabel(SpeakerType type) {
		return switch (type) {
			case FLOORSTANDING -> "Floor";
			case BOOKSHELF -> "Shelf";
			case CENTER -> "Center";
			case SUBWOOFER -> "Sub";
		};
	}

	private record SpeakerRow(BlockPos pos, String name, SpeakerRole role, SpeakerType type,
							  double levelDb) {
	}

	private record FolderCacheKey(String libraryId, Path root, String folder) {
	}

	private record EntryView(MediaLibrary.Entry entry, MediaLibrary.Probe probe, String error) {
	}

	private enum Tab {
		MEDIA("Media"),
		SPEAKERS("Speakers"),
		SIGNAL("Signal"),
		SETTINGS("Settings");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private final class SeekSlider extends AbstractSliderButton {
		private boolean dragging;
		private double draftSeconds;

		private SeekSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), 0.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(formatTime(draftSeconds) + " / " + formatTime(receiver.durationSeconds())));
		}

		@Override
		protected void applyValue() {
			double duration = receiver.durationSeconds();
			draftSeconds = duration > 0.0 ? value * duration : 0.0;
			updateMessage();
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			dragging = true;
			pendingSeekSeconds = Double.NaN;
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
			dragging = true;
			super.onDrag(mouseX, mouseY, dragX, dragY);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			dragging = false;
			if (receiver.durationSeconds() > 0.0 && sendSeek(draftSeconds)) {
				pendingSeekSeconds = draftSeconds;
				pendingSeekUntilMillis = System.currentTimeMillis()
					+ AvReceiverPlayback.SEEK_COOLDOWN_MILLIS;
			}
			super.onRelease(mouseX, mouseY);
		}

		private void setExternal(double seconds) {
			if (dragging) {
				return;
			}
			if (!Double.isNaN(pendingSeekSeconds)) {
				boolean matches = Math.abs(seconds - pendingSeekSeconds) <= 0.5;
				if (System.currentTimeMillis() < pendingSeekUntilMillis && !matches) {
					return;
				}
				pendingSeekSeconds = Double.NaN;
			}
			double duration = receiver.durationSeconds();
			draftSeconds = Math.max(0.0, seconds);
			value = duration > 0.0 ? Math.max(0.0, Math.min(1.0, draftSeconds / duration)) : 0.0;
			updateMessage();
		}
	}

	private final class VolumeSlider extends AbstractSliderButton {
		private boolean dragging;

		private VolumeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Component.empty(), volumeToValue(receiver.volumeDb()));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Volume " + formatSignedDb(currentDb()) + " dBFS"));
		}

		@Override
		protected void applyValue() {
			updateMessage();
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			dragging = true;
			pendingVolumeDb = Double.NaN;
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
			dragging = true;
			super.onDrag(mouseX, mouseY, dragX, dragY);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			dragging = false;
			pendingVolumeDb = currentDb();
			pendingVolumeUntilMillis = System.currentTimeMillis() + 1_500L;
			sendVolume(pendingVolumeDb);
			super.onRelease(mouseX, mouseY);
		}

		private double currentDb() {
			return AvReceiverBlockEntity.MIN_VOLUME_DB
				+ value * (AvReceiverBlockEntity.MAX_VOLUME_DB - AvReceiverBlockEntity.MIN_VOLUME_DB);
		}

		private void setExternal(double volumeDb) {
			if (dragging) {
				return;
			}
			if (!Double.isNaN(pendingVolumeDb)) {
				boolean matches = Math.abs(volumeDb - pendingVolumeDb) <= 0.1;
				if (System.currentTimeMillis() < pendingVolumeUntilMillis && !matches) {
					return;
				}
				pendingVolumeDb = Double.NaN;
			}
			value = volumeToValue(volumeDb);
			updateMessage();
		}
	}

	private final class SpeakerLevelSlider extends AbstractSliderButton {
		private final BlockPos speakerPos;
		private boolean dragging;
		private double pendingLevelDb = Double.NaN;
		private long pendingUntilMillis;
		private long lastSentAtMillis;

		private SpeakerLevelSlider(int x, int y, int width, int height, BlockPos speakerPos,
								   double levelDb) {
			super(x, y, width, height, Component.empty(), levelToValue(levelDb));
			this.speakerPos = speakerPos;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Level " + formatSignedDb(currentDb()) + " dB"));
		}

		@Override
		protected void applyValue() {
			updateMessage();
			if (dragging
				&& System.currentTimeMillis() - lastSentAtMillis >= SPEAKER_LEVEL_DEBOUNCE_MS) {
				sendCurrentLevel();
			}
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			dragging = true;
			pendingLevelDb = Double.NaN;
			super.onClick(mouseX, mouseY);
		}

		@Override
		protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
			dragging = true;
			super.onDrag(mouseX, mouseY, dragX, dragY);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			dragging = false;
			sendCurrentLevel();
			super.onRelease(mouseX, mouseY);
		}

		private double currentDb() {
			return SpeakerBlockEntity.MIN_LEVEL_DB
				+ value * (SpeakerBlockEntity.MAX_LEVEL_DB - SpeakerBlockEntity.MIN_LEVEL_DB);
		}

		private void sendCurrentLevel() {
			double db = SpeakerBlockEntity.clampLevelDb(currentDb());
			lastSentAtMillis = System.currentTimeMillis();
			pendingLevelDb = db;
			pendingUntilMillis = lastSentAtMillis + 1_500L;
			SpeakerLevelClient.setLevel(receiver.getBlockPos(), speakerPos, db);
		}

		private void setExternal(double levelDb) {
			if (dragging) {
				return;
			}
			if (!Double.isNaN(pendingLevelDb)) {
				boolean matches = Math.abs(levelDb - pendingLevelDb) <= 0.05;
				if (System.currentTimeMillis() < pendingUntilMillis && !matches) {
					return;
				}
				pendingLevelDb = Double.NaN;
			}
			value = levelToValue(levelDb);
			updateMessage();
		}
	}
}
