package com.example.firstmod.client.gui;

import com.example.firstmod.FirstMod;
import com.example.firstmod.block.entity.AvReceiverBlockEntity;
import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.block.entity.SpeakerBlockEntity;
import com.example.firstmod.client.media.AvReceiverPlayback;
import com.example.firstmod.client.media.AvReceiverPlaybackManager;
import com.example.firstmod.client.media.CinemaPlayer;
import com.example.firstmod.client.media.LibraryRegistry;
import com.example.firstmod.client.media.MediaLibrary;
import com.example.firstmod.client.network.ReceiverControlClient;
import com.example.firstmod.network.ReceiverControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M2 AV receiver front panel: media library, transport, outputs, diagnostics
 * and the future speaker setup page.
 *
 * <p>The screen reads the synced receiver block entity directly and sends all
 * changes through {@code ReceiverControlPayload}. It never pauses the game:
 * opening the panel while a movie is playing must not stop the integrated
 * server.
 */
public final class AvReceiverScreen extends Screen {
	private static final int TAB_HEIGHT = 18;
	private static final int ROW_HEIGHT = 11;
	private static final int PROBE_LIMIT = 30;

	private static String rememberedLibraryId = LibraryRegistry.DEFAULT_ID;
	private static String rememberedFolder = "";

	private final AvReceiverBlockEntity receiver;
	private Tab tab = Tab.MEDIA;
	private final List<EntryView> entries = new ArrayList<>();
	private final List<String> speakerLines = new ArrayList<>();

	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int contentTop;
	private int contentBottom;
	private int transportTop;
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
	private boolean scanning;
	private boolean folderLoaded;
	private int scanGeneration;
	private long lastClickTime;
	private String lastClickKey = "";
	private boolean pendingPlay;
	private String statusMessage = "";
	private long statusUntil;
	private boolean mediaMissing;
	private MediaLibrary.Probe receiverProbe;
	private String receiverProbeKey = "";
	private boolean receiverProbePending;

	private EditBox searchBox;
	private Button libraryButton;
	private Button playPauseButton;
	private Button stopButton;
	private Button muteButton;
	private Button backButton;
	private Button playSelectedButton;
	private Button removeLibraryButton;
	private EditBox libraryPathBox;
	private SeekSlider seekSlider;
	private VolumeSlider volumeSlider;
	private double pendingSeekSeconds = Double.NaN;
	private long pendingSeekUntilMillis;
	private float pendingVolume = Float.NaN;
	private long pendingVolumeUntilMillis;

	public AvReceiverScreen(AvReceiverBlockEntity receiver) {
		super(Component.literal("Cinema AV Receiver"));
		this.receiver = receiver;
		if (LibraryRegistry.findById(libraryId) == null) {
			libraryId = LibraryRegistry.DEFAULT_ID;
			folder = "";
		}
		selectedLibraryId = libraryId;
		FirstMod.LOGGER.info("cinema receiver gui: opened {}", receiver.getBlockPos().toShortString());
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, 420);
		panelHeight = Math.min(height - 12, 300);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 8;
		contentWidth = panelWidth - 16;
		tabY = top + 20;
		contentTop = top + 60;
		transportTop = top + panelHeight - 42;
		contentBottom = transportTop - 4;

		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
			.bounds(left + panelWidth - 48, top + 2, 40, 16).build());

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
		addRenderableWidget(Button.builder(Component.literal("-10s"), button -> seekRelative(-10))
			.bounds(contentLeft + 76, rowY, 34, 18).build());
		addRenderableWidget(Button.builder(Component.literal("+10s"), button -> seekRelative(10))
			.bounds(contentLeft + 112, rowY, 34, 18).build());

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
		} else if (tab == Tab.SETTINGS) {
			addRenderableWidget(Button.builder(Component.literal("Re-scan projector"), button -> requestLink())
				.bounds(contentLeft, contentTop + 4, 124, 18).build());
			addRenderableWidget(Button.builder(Component.literal("Refresh library"),
					button -> loadFolder(libraryId, folder, true))
				.bounds(contentLeft, contentTop + 24, 124, 18).build());
			libraryPathBox = new EditBox(font, contentLeft, contentTop + 46, contentWidth, 16,
				Component.literal("External library folder"));
			libraryPathBox.setHint(Component.literal("External library folder path"));
			libraryPathBox.setMaxLength(260);
			libraryPathBox.setResponder(value -> libraryPathDraft = value);
			libraryPathBox.setValue(libraryPathDraft);
			addRenderableWidget(libraryPathBox);
			addRenderableWidget(Button.builder(Component.literal("Add library"), button -> addLibrary())
				.bounds(contentLeft, contentTop + 64, 92, 18).build());
			removeLibraryButton = Button.builder(Component.literal("Remove library"), button -> removeLibrary())
				.bounds(contentLeft + 96, contentTop + 64, 104, 18).build();
			addRenderableWidget(removeLibraryButton);
		}
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
		if (minecraft != null && minecraft.level != null && minecraft.level.getGameTime() % 20L == 0L) {
			refreshWarnings();
			refreshSpeakers();
			ensureReceiverProbe();
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
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// Screen.render invokes renderBackground() first, then the widgets. The
		// panel and descriptive text therefore live in renderBackground so they
		// are not covered by a second blur pass.
		super.render(graphics, mouseX, mouseY, partialTick);
		drawTabAccent(graphics);
		if (tab == Tab.MEDIA || tab == Tab.SETTINGS) {
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
		if (tab == Tab.SETTINGS
			&& mouseX >= contentLeft && mouseX < contentLeft + contentWidth
			&& mouseY >= contentTop + 98 && mouseY < contentBottom - 2) {
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
		return false;
	}

	@Override
	public void onClose() {
		scanGeneration++;
		FirstMod.LOGGER.info("cinema receiver gui: closed {}", receiver.getBlockPos().toShortString());
		super.onClose();
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
		if (LibraryRegistry.remove(selectedLibraryId)) {
			if (libraryId.equals(selectedLibraryId)) {
				libraryId = LibraryRegistry.DEFAULT_ID;
				folder = "";
				folderLoaded = false;
			}
			if (rememberedLibraryId.equals(selectedLibraryId)) {
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
			|| mouseY < contentTop + 98 || mouseY >= contentBottom - 2) {
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
			seekSlider.active = receiver.durationSeconds() > 0.0;
			seekSlider.setExternal(currentPosition());
		}
		if (volumeSlider != null) {
			volumeSlider.setExternal(receiver.volume());
		}
		if (playSelectedButton != null) {
			playSelectedButton.active = selectedFile() != null && !pendingPlay;
		}
		if (backButton != null) {
			backButton.active = !folder.isEmpty();
		}
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
			String video = receiver.videoTarget() == null
				? "Video: none"
				: "Video: " + receiver.videoTarget().toShortString() + (projectorOnline() ? " online" : " offline");
			AvReceiverPlayback playback = AvReceiverPlaybackManager.playbackAt(receiver.getBlockPos());
			String audio = playback != null && playback.player() != null ? "Audio: 5.1 active" : "Audio: idle";
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
			graphics.drawString(font, "No video files in this folder", contentLeft + 4, top + 4, 0x9A9A9A, false);
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
			? "Select a movie to play"
			: (selected.entry.directory() ? "Folder: " + selected.entry.key() : fullMetadata(selected));
		graphics.drawString(font, truncate(detail, contentWidth), contentLeft, contentBottom - 34,
			0x9A9A9A, false);
	}

	private void drawSpeakersTab(GuiGraphics graphics) {
		int y = contentTop;
		graphics.drawString(font, "Speaker routing (M4)", contentLeft, y, 0xE8E8E8, false);
		graphics.drawString(font, "M1/M2 output: virtual 5.1 through six OpenAL sources.",
			contentLeft, y + 12, 0x9A9A9A, false);
		graphics.drawString(font, "Detected speaker blocks within 48 blocks: " + speakerLines.size(),
			contentLeft, y + 28, 0xB8B8B8, false);
		int lineY = y + 44;
		for (int i = 0; i < Math.min(7, speakerLines.size()); i++) {
			graphics.drawString(font, truncate(speakerLines.get(i), contentWidth), contentLeft, lineY,
				0xB8B8B8, false);
			lineY += 10;
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
			"Playback: " + (player == null ? "not open"
				: player.width() + "x" + player.height() + "  presented=" + player.presentedFrames()
					+ "  dropped=" + player.droppedFrames()),
			"Receiver: " + receiver.state().name().toLowerCase(Locale.ROOT)
				+ "  " + String.format(Locale.ROOT, "%.1f", currentPosition())
				+ " / " + String.format(Locale.ROOT, "%.1f", receiver.durationSeconds()) + " s",
			"Volume: " + Math.round(receiver.volume() * 100.0) + "%"
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

	private void drawSettingsTab(GuiGraphics graphics) {
		int y = contentTop;
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
			int trackHeight = Math.max(1, contentBottom - 2 - firstRow);
			int thumbHeight = Math.max(8, trackHeight * rows / Math.max(rows, libraries.size()));
			int thumbY = firstRow + (trackHeight - thumbHeight) * libraryScroll / maxScroll;
			graphics.fill(contentLeft + contentWidth - 2, firstRow, contentLeft + contentWidth,
				contentBottom - 2, 0x50000000);
			graphics.fill(contentLeft + contentWidth - 2, thumbY, contentLeft + contentWidth,
				thumbY + thumbHeight, 0xFFAAAAAA);
		}
	}

	private void renderWrappedTooltip(GuiGraphics graphics, String text, int mouseX, int mouseY) {
		int maxWidth = Math.max(80, Math.min(240, width - 32));
		graphics.renderTooltip(font, font.split(Component.literal(text), maxWidth), mouseX, mouseY);
	}

	private void drawHoverTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
		if (tab == Tab.SETTINGS) {
			if (libraryPathBox != null
				&& mouseX >= libraryPathBox.getX() && mouseX < libraryPathBox.getX() + libraryPathBox.getWidth()
				&& mouseY >= libraryPathBox.getY() && mouseY < libraryPathBox.getY() + libraryPathBox.getHeight()) {
				renderWrappedTooltip(graphics,
					"Absolute path to a folder containing videos. The library is stored locally in "
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
		if (LibraryRegistry.findById(targetLibrary) == null) {
			targetLibrary = LibraryRegistry.DEFAULT_ID;
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
					ReceiverControlClient.selectMedia(receiver.getBlockPos(), metadata);
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
			setStatus("Select a movie from the Media tab first");
			return;
		}
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING) {
			ReceiverControlClient.sendSimple(receiver.getBlockPos(), ReceiverControlPayload.Action.PAUSE);
		} else {
			ReceiverControlClient.sendSimple(receiver.getBlockPos(), ReceiverControlPayload.Action.PLAY);
		}
	}

	private void sendStop() {
		ReceiverControlClient.sendSimple(receiver.getBlockPos(), ReceiverControlPayload.Action.STOP);
	}

	private void seekRelative(double delta) {
		double target = Math.max(0.0, currentPosition() + delta);
		if (receiver.durationSeconds() > 0.0) {
			target = Math.min(target, receiver.durationSeconds());
		}
		ReceiverControlClient.sendValue(receiver.getBlockPos(),
			ReceiverControlPayload.Action.SEEK, target);
		setStatus("Seek requested: " + formatTime(target));
	}

	private void sendSeek(double seconds) {
		if (receiver.durationSeconds() <= 0.0) {
			return;
		}
		ReceiverControlClient.sendValue(receiver.getBlockPos(),
			ReceiverControlPayload.Action.SEEK, seconds);
		setStatus("Seek requested: " + formatTime(seconds));
	}

	private void sendVolume(double value) {
		ReceiverControlClient.sendValue(receiver.getBlockPos(),
			ReceiverControlPayload.Action.SET_VOLUME, value);
	}

	private void toggleMute() {
		ReceiverControlClient.sendValue(receiver.getBlockPos(),
			ReceiverControlPayload.Action.SET_MUTED,
			receiver.isMuted() ? 0.0 : 1.0);
	}

	private void requestLink() {
		ReceiverControlClient.sendSimple(receiver.getBlockPos(),
			ReceiverControlPayload.Action.LINK_PROJECTOR);
		setStatus("Projector link requested");
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
		Level level = receiver.getLevel();
		if (level == null) {
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
					speakerLines.add(speaker.role().label() + "  " + speaker.type().id()
						+ "  " + speaker.getBlockPos().toShortString());
				}
			}
		}
		speakerLines.sort(String.CASE_INSENSITIVE_ORDER);
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

	private double currentPosition() {
		double position = receiver.positionSeconds();
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING && receiver.getLevel() != null) {
			position += Math.max(0L, receiver.getLevel().getGameTime() - receiver.stateGameTime()) / 20.0;
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
		return Math.max(1, (contentBottom - 2 - (contentTop + 98)) / 11);
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
		String video = probe.video().width() + "x" + probe.video().height()
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
		return view.probe().video().width() + "x" + view.probe().video().height()
			+ "  " + view.probe().video().codec()
			+ "  " + formatTime(view.probe().durationSeconds());
	}

	private String fullMetadata(EntryView view) {
		if (view.probe() == null) {
			return view.entry.name() + (view.error() != null ? "  |  " + view.error() : "");
		}
		String hdr = view.probe().video().isHdr() ? "HDR" : "SDR";
		String audio = view.probe().audio() == null ? "no audio"
			: view.probe().audio().channels() + "ch " + view.probe().audio().codec();
		return view.entry.name() + "  |  " + formatSize(view.entry.size())
			+ "  |  " + formatTime(view.probe().durationSeconds())
			+ "  |  " + view.probe().video().width() + "x" + view.probe().video().height()
			+ " " + hdr + " " + view.probe().video().codec() + "  |  " + audio;
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
			if (receiver.durationSeconds() > 0.0) {
				pendingSeekSeconds = draftSeconds;
				pendingSeekUntilMillis = System.currentTimeMillis() + 1_500L;
				sendSeek(draftSeconds);
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
			super(x, y, width, height, Component.empty(), receiver.volume());
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Volume " + Math.round(value * 100.0) + "%"));
		}

		@Override
		protected void applyValue() {
			updateMessage();
		}

		@Override
		public void onClick(double mouseX, double mouseY) {
			dragging = true;
			pendingVolume = Float.NaN;
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
			pendingVolume = (float) value;
			pendingVolumeUntilMillis = System.currentTimeMillis() + 1_500L;
			sendVolume(value);
			super.onRelease(mouseX, mouseY);
		}

		private void setExternal(float volume) {
			if (dragging) {
				return;
			}
			if (!Float.isNaN(pendingVolume)) {
				boolean matches = Math.abs(volume - pendingVolume) <= 0.01f;
				if (System.currentTimeMillis() < pendingVolumeUntilMillis && !matches) {
					return;
				}
				pendingVolume = Float.NaN;
			}
			value = Math.max(0.0, Math.min(1.0, volume));
			updateMessage();
		}
	}
}
