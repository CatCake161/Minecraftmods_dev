package com.example.cinema.client.gui;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.render.SpeakerLocator;
import com.example.cinema.network.RemoteScanListPayload;
import com.example.cinema.network.RemoteScanRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Universal remote device scanner.
 *
 * <p>Shows three columns: AV receivers, projectors and speakers. The server
 * scans loaded devices within 128 blocks of the player. Each row provides Ping
 * and Access only; pairing is intentionally handled by the receiver/projector
 * pairing menus and the physical cable.
 */
public final class RemoteDeviceScreen extends Screen {
	private static final int ROW_HEIGHT = 34;
	private static final int COLUMN_GAP = 8;
	private static final int COLUMNS = 3;

	private List<RemoteScanListPayload.Entry> entries = List.of();
	private final int[] scroll = new int[COLUMNS];
	private final int[] columnX = new int[COLUMNS];
	private final int[] columnWidth = new int[COLUMNS];
	private boolean requested;
	private boolean waiting;
	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int listTop;
	private int listBottom;

	public RemoteDeviceScreen() {
		super(Component.literal("Cinema Remote"));
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, 900);
		panelHeight = Math.min(height - 12, 600);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		listTop = top + 48;
		listBottom = top + panelHeight - 32;

		int available = contentWidth - COLUMN_GAP * (COLUMNS - 1);
		int widthPerColumn = available / COLUMNS;
		for (int i = 0; i < COLUMNS; i++) {
			columnX[i] = contentLeft + i * (widthPerColumn + COLUMN_GAP);
			columnWidth[i] = widthPerColumn;
			addColumnWidgets(i);
		}

		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestList())
			.bounds(contentLeft, top + panelHeight - 24, 70, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, top + panelHeight - 24, 60, 18).build());

		if (!requested) {
			requested = true;
			requestList();
		}
	}

	private void addColumnWidgets(int column) {
		RemoteScanListPayload.Kind kind = RemoteScanListPayload.Kind.values()[column];
		List<RemoteScanListPayload.Entry> category = entriesOf(kind);
		int visibleRows = visibleRows();
		int maxScroll = Math.max(0, category.size() - visibleRows);
		scroll[column] = Math.max(0, Math.min(scroll[column], maxScroll));
		for (int row = 0; row < visibleRows && scroll[column] + row < category.size(); row++) {
			RemoteScanListPayload.Entry entry = category.get(scroll[column] + row);
			int rowY = listTop + row * ROW_HEIGHT;
			int x = columnX[column];
			int width = columnWidth[column];
			addRenderableWidget(Button.builder(Component.literal("Ping"),
					button -> pingEntry(entry))
				.bounds(x + width - 92, rowY + 16, 34, 16).build());
			Button access = Button.builder(Component.literal("Access"),
					button -> openEntry(entry))
				.bounds(x + width - 56, rowY + 16, 48, 16).build();
			access.active = !waiting;
			addRenderableWidget(access);
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
		graphics.drawCenteredString(font, title, left + panelWidth / 2, top + 6, 0xE8E8E8);
		graphics.drawString(font, "Nearby devices within 128 blocks (loaded chunks):",
			contentLeft, top + 22, 0x808080, false);

		for (int column = 0; column < COLUMNS; column++) {
			RemoteScanListPayload.Kind kind = RemoteScanListPayload.Kind.values()[column];
			graphics.drawString(font, columnLabel(kind), columnX[column] + 2, top + 36,
				0xFFD54F, false);
			graphics.fill(columnX[column], listTop, columnX[column] + columnWidth[column],
				listBottom, 0x60000000);
			List<RemoteScanListPayload.Entry> category = entriesOf(kind);
			if (waiting) {
				graphics.drawString(font, "Scanning...", columnX[column] + 4, listTop + 4,
					0xFFD54F, false);
				continue;
			}
			if (category.isEmpty()) {
				graphics.drawString(font, "None", columnX[column] + 4, listTop + 4,
					0x9A9A9A, false);
				continue;
			}
			int visibleRows = visibleRows();
			for (int row = 0; row < visibleRows && scroll[column] + row < category.size(); row++) {
				RemoteScanListPayload.Entry entry = category.get(scroll[column] + row);
				int rowY = listTop + row * ROW_HEIGHT;
				graphics.drawString(font, truncate(entryLabel(entry), columnWidth[column] - 12),
					columnX[column] + 2, rowY + 1, 0xD0D0D0, false);
			}
			int maxScroll = Math.max(0, category.size() - visibleRows);
			if (maxScroll > 0) {
				int trackHeight = Math.max(1, listBottom - listTop);
				int thumbHeight = Math.max(8, trackHeight * visibleRows / category.size());
				int thumbY = listTop + (trackHeight - thumbHeight)
					* scroll[column] / maxScroll;
				int scrollbarX = columnX[column] + columnWidth[column] - 5;
				graphics.fill(scrollbarX, listTop, scrollbarX + 2, listBottom, 0x50000000);
				graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight,
					0xFFAAAAAA);
			}
		}
	}

	private void requestList() {
		waiting = true;
		ClientPlayNetworking.send(new RemoteScanRequestPayload());
		rebuildWidgets();
	}

	private void openEntry(RemoteScanListPayload.Entry entry) {
		Level level = Minecraft.getInstance().level;
		Player player = Minecraft.getInstance().player;
		if (level == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(entry.pos());
		if (entry.kind() == RemoteScanListPayload.Kind.RECEIVER
			&& blockEntity instanceof AvReceiverBlockEntity receiver) {
			Minecraft.getInstance().setScreen(new AvReceiverConfigScreen(receiver, this));
			return;
		}
		if (entry.kind() == RemoteScanListPayload.Kind.PROJECTOR
			&& blockEntity instanceof ProjectorBlockEntity projector) {
			Minecraft.getInstance().setScreen(new ProjectorConfigScreen(projector, this));
			return;
		}
		if (entry.kind() == RemoteScanListPayload.Kind.SPEAKER
			&& blockEntity instanceof SpeakerBlockEntity speaker) {
			Minecraft.getInstance().setScreen(new SpeakerDataConfigScreen(this, speaker));
			return;
		}
		if (player != null) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.device_not_loaded"), true);
		}
	}

	private void pingEntry(RemoteScanListPayload.Entry entry) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		client.getSoundManager().play(new SimpleSoundInstance(
			SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.0f, 1.0f,
			client.level.random, entry.pos()));
		SpeakerLocator.highlight(client, entry.pos());
	}

	/** Called from the client payload receiver when a new scan arrives. */
	public void applyList(RemoteScanListPayload payload) {
		entries = List.copyOf(payload.entries());
		waiting = false;
		rebuildWidgets();
	}

	private List<RemoteScanListPayload.Entry> entriesOf(RemoteScanListPayload.Kind kind) {
		return entries.stream().filter(entry -> entry.kind() == kind).toList();
	}

	private int visibleRows() {
		return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
	}

	private static String columnLabel(RemoteScanListPayload.Kind kind) {
		return switch (kind) {
			case RECEIVER -> "AV Receivers";
			case PROJECTOR -> "Projectors";
			case SPEAKER -> "Speakers";
		};
	}

	private String entryLabel(RemoteScanListPayload.Entry entry) {
		String type = switch (entry.kind()) {
			case RECEIVER -> "AV Receiver";
			case PROJECTOR -> "Projector";
			case SPEAKER -> "Speaker";
		};
		String name = entry.name().isBlank() ? type : entry.name();
		return name + " · " + String.format(Locale.ROOT, "%.0f blocks",
			entry.distance());
	}

	private String fullEntryLabel(RemoteScanListPayload.Entry entry) {
		String type = switch (entry.kind()) {
			case RECEIVER -> "AV Receiver";
			case PROJECTOR -> "Projector";
			case SPEAKER -> "Speaker";
		};
		String name = entry.name().isBlank() ? "(unnamed)" : entry.name();
		return name + "\n" + type
			+ "\nBlock: " + entry.pos().toShortString()
			+ "\nDistance: " + String.format(Locale.ROOT, "%.1f blocks",
				entry.distance());
	}

	private String truncate(String text, int maxWidth) {
		int width = Math.max(10, maxWidth);
		if (font.width(text) <= width) {
			return text;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY < listTop || mouseY >= listBottom) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		for (int column = 0; column < COLUMNS; column++) {
			if (mouseX < columnX[column] || mouseX >= columnX[column] + columnWidth[column]) {
				continue;
			}
			RemoteScanListPayload.Kind kind = RemoteScanListPayload.Kind.values()[column];
			List<RemoteScanListPayload.Entry> category = entriesOf(kind);
			int maxScroll = Math.max(0, category.size() - visibleRows());
			int next = Math.max(0, Math.min(maxScroll,
				scroll[column] - (int) Math.signum(scrollY)));
			if (next != scroll[column]) {
				scroll[column] = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		RemoteScanListPayload.Entry entry = entryAt(mouseX, mouseY);
		if (entry != null) {
			int maxWidth = Math.max(100, Math.min(280, width - 32));
			List<FormattedCharSequence> lines = new ArrayList<>();
			for (String line : fullEntryLabel(entry).split("\\n")) {
				lines.addAll(font.split(Component.literal(line), maxWidth));
			}
			graphics.renderTooltip(font, lines, mouseX, mouseY);
		}
	}

	private RemoteScanListPayload.Entry entryAt(double mouseX, double mouseY) {
		if (mouseY < listTop || mouseY >= listBottom) {
			return null;
		}
		for (int column = 0; column < COLUMNS; column++) {
			if (mouseX < columnX[column] || mouseX >= columnX[column] + columnWidth[column]) {
				continue;
			}
			int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
			int rowY = listTop + row * ROW_HEIGHT;
			if (mouseY >= rowY + 16) {
				return null; // over the row buttons
			}
			RemoteScanListPayload.Kind kind = RemoteScanListPayload.Kind.values()[column];
			List<RemoteScanListPayload.Entry> category = entriesOf(kind);
			int index = scroll[column] + row;
			return index >= 0 && index < category.size() ? category.get(index) : null;
		}
		return null;
	}

	@Override
	public boolean isPauseScreen() {
		return CinemaClientConfig.get().pauseGameInDeviceGuis();
	}
}
