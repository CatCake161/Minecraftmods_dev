package com.example.cinema.client.gui;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.render.SpeakerLocator;
import com.example.cinema.item.RemoteBinding;
import com.example.cinema.item.RemoteControlAccess;
import com.example.cinema.network.RemotePairingActionPayload;
import com.example.cinema.network.RemotePairingListPayload;
import com.example.cinema.network.RemotePairingRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Locale;

/**
 * Remote pairing panel opened from a receiver or projector GUI.
 *
 * <p>The server sends the list of compatible loaded devices around the anchor.
 * The player can link/unlink any row; the anchor access is revalidated on every
 * request and action (normal 16-block range or a bound remote within 128).
 */
public final class RemotePairingScreen extends Screen {
	private static final int ROW_HEIGHT = 20;

	private final Screen parent;
	private final BlockPos anchorPos;
	private final RemoteBinding.Kind anchorKind;
	private List<RemotePairingListPayload.Entry> entries = List.of();
	private boolean requested;
	private boolean waiting;
	private boolean closing;
	private int scroll;
	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int contentLeft;
	private int contentWidth;
	private int listTop;
	private int listBottom;

	public RemotePairingScreen(Screen parent, BlockPos anchorPos, RemoteBinding.Kind anchorKind) {
		super(Component.literal("Remote Pairing"));
		this.parent = parent;
		this.anchorPos = anchorPos;
		this.anchorKind = anchorKind;
	}

	@Override
	protected void init() {
		super.init();
		panelWidth = Math.min(width - 12, 380);
		panelHeight = Math.min(height - 12, 280);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		contentLeft = left + 10;
		contentWidth = panelWidth - 20;
		listTop = top + 58;
		listBottom = top + panelHeight - 30;

		int visibleRows = visibleRows();
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		for (int i = 0; i < visibleRows && scroll + i < entries.size(); i++) {
			RemotePairingListPayload.Entry entry = entries.get(scroll + i);
			int rowY = listTop + i * ROW_HEIGHT;
			Button action = Button.builder(Component.literal(entry.linked() ? "Unlink" : "Link"),
					button -> changeLink(entry))
				.bounds(contentLeft + contentWidth - 66, rowY + 1, 64, 16).build();
			action.active = !waiting;
			addRenderableWidget(action);
			addRenderableWidget(Button.builder(Component.literal("Ping"),
					button -> pingEntry(entry))
				.bounds(contentLeft + contentWidth - 108, rowY + 1, 38, 16).build());
			if (canAccessEntry(entry)) {
				Button access = Button.builder(Component.literal("Access"),
						button -> openEntry(entry))
					.bounds(contentLeft + contentWidth - 166, rowY + 1, 54, 16).build();
				access.active = !waiting;
				addRenderableWidget(access);
			}
		}
		addRenderableWidget(Button.builder(Component.literal("Open device"),
				button -> openAnchor())
			.bounds(contentLeft + 80, top + panelHeight - 24, 96, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Refresh"), button -> requestList())
			.bounds(contentLeft, top + panelHeight - 24, 70, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
			.bounds(contentLeft + contentWidth - 60, top + panelHeight - 24, 60, 18).build());

		if (!requested) {
			requested = true;
			requestList();
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
		graphics.drawString(font, truncate(anchorLabel(), contentWidth), contentLeft, top + 20,
			0x9A9A9A, false);
		graphics.drawString(font, "Nearby devices within 128 blocks (loaded chunks):",
			contentLeft, top + 34, 0x808080, false);
		graphics.fill(contentLeft, listTop, contentLeft + contentWidth, listBottom, 0x60000000);
		if (waiting) {
			graphics.drawString(font, "Scanning nearby devices...", contentLeft + 4, listTop + 4,
				0xFFD54F, false);
			return;
		}
		if (entries.isEmpty()) {
			graphics.drawString(font, "No compatible devices found", contentLeft + 4, listTop + 4,
				0x9A9A9A, false);
			return;
		}
		int visibleRows = visibleRows();
		for (int i = 0; i < visibleRows && scroll + i < entries.size(); i++) {
			RemotePairingListPayload.Entry entry = entries.get(scroll + i);
			int rowY = listTop + i * ROW_HEIGHT;
			String label = entryLabel(entry);
			int labelWidth = contentWidth - (canAccessEntry(entry) ? 172 : 116);
			graphics.drawString(font, truncate(label, labelWidth), contentLeft + 4, rowY + 4,
				entry.linked() ? 0x8BC34A : 0xD0D0D0, false);
		}
		int maxScroll = Math.max(0, entries.size() - visibleRows);
		if (maxScroll > 0) {
			int trackHeight = Math.max(1, listBottom - listTop);
			int thumbHeight = Math.max(8, trackHeight * visibleRows / entries.size());
			int thumbY = listTop + (trackHeight - thumbHeight) * scroll / maxScroll;
			graphics.fill(contentLeft + contentWidth - 2, listTop, contentLeft + contentWidth,
				listBottom, 0x50000000);
			graphics.fill(contentLeft + contentWidth - 2, thumbY, contentLeft + contentWidth,
				thumbY + thumbHeight, 0xFFAAAAAA);
		}
	}

	private void requestList() {
		waiting = true;
		ClientPlayNetworking.send(new RemotePairingRequestPayload(anchorPos, anchorKind));
		rebuildWidgets();
	}

	private void changeLink(RemotePairingListPayload.Entry entry) {
		if (waiting) {
			return;
		}
		waiting = true;
		ClientPlayNetworking.send(new RemotePairingActionPayload(anchorPos, anchorKind,
			entry.kind(), entry.pos(), !entry.linked()));
		rebuildWidgets();
	}

	private boolean canAccessEntry(RemotePairingListPayload.Entry entry) {
		if (entry.kind() == RemotePairingListPayload.TargetKind.SCREEN || !entry.linked()) {
			return false;
		}
		Player player = Minecraft.getInstance().player;
		if (player == null) {
			return false;
		}
		if (player.blockPosition().distSqr(entry.pos()) <= 16.0 * 16.0) {
			return true;
		}
		if (anchorKind != RemoteBinding.Kind.RECEIVER
			|| player.blockPosition().distSqr(anchorPos)
				> RemoteControlAccess.MAX_REMOTE_DISTANCE * RemoteControlAccess.MAX_REMOTE_DISTANCE) {
			return false;
		}
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (stack.is(Cinema.REMOTE_CONTROLLER_ITEM)
				&& RemoteBinding.matches(stack, player.level().dimension(), anchorPos,
					RemoteBinding.Kind.RECEIVER)) {
				return true;
			}
		}
		return false;
	}

	private void openEntry(RemotePairingListPayload.Entry entry) {
		Level level = Minecraft.getInstance().level;
		Player player = Minecraft.getInstance().player;
		if (level == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(entry.pos());
		if (entry.kind() == RemotePairingListPayload.TargetKind.PROJECTOR
			&& blockEntity instanceof ProjectorBlockEntity projector) {
			Minecraft.getInstance().setScreen(new ProjectorConfigScreen(projector, this));
			return;
		}
		if (entry.kind() == RemotePairingListPayload.TargetKind.SPEAKER
			&& blockEntity instanceof SpeakerBlockEntity speaker) {
			Minecraft.getInstance().setScreen(new SpeakerSpecsConfigScreen(this, speaker));
			return;
		}
		if (player != null) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.device_not_loaded"), true);
		}
	}

	private void openAnchor() {
		Level level = Minecraft.getInstance().level;
		Player player = Minecraft.getInstance().player;
		if (level == null) {
			return;
		}
		BlockEntity blockEntity = level.getBlockEntity(anchorPos);
		if (anchorKind == RemoteBinding.Kind.RECEIVER
			&& blockEntity instanceof AvReceiverBlockEntity receiver) {
			Minecraft.getInstance().setScreen(new AvReceiverConfigScreen(receiver, this));
			return;
		}
		if (anchorKind == RemoteBinding.Kind.PROJECTOR
			&& blockEntity instanceof ProjectorBlockEntity projector) {
			Minecraft.getInstance().setScreen(new ProjectorConfigScreen(projector, this));
			return;
		}
		if (player != null) {
			player.displayClientMessage(Component.translatable(
				"message.cinema.remote.device_not_loaded"), true);
		}
	}

	private void pingEntry(RemotePairingListPayload.Entry entry) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return;
		}
		client.getSoundManager().play(new SimpleSoundInstance(
			SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.BLOCKS, 1.0f, 1.0f,
			client.level.random, entry.pos()));
		SpeakerLocator.highlight(client, entry.pos());
	}

	/** Called from the client payload receiver when a new snapshot arrives. */
	public void applyList(RemotePairingListPayload payload) {
		if (!payload.anchorPos().equals(anchorPos) || payload.anchorKind() != anchorKind) {
			return;
		}
		entries = List.copyOf(payload.entries());
		waiting = false;
		rebuildWidgets();
	}

	private int visibleRows() {
		return Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
	}

	private String anchorLabel() {
		String kind = anchorKind == RemoteBinding.Kind.RECEIVER ? "AV Receiver" : "Projector";
		Level level = Minecraft.getInstance().level;
		if (level != null && level.isLoaded(anchorPos)) {
			BlockEntity blockEntity = level.getBlockEntity(anchorPos);
			if (blockEntity instanceof AvReceiverBlockEntity receiver) {
				kind = receiver.name().isBlank() ? kind : receiver.name();
			} else if (blockEntity instanceof ProjectorBlockEntity projector) {
				kind = projector.name().isBlank() ? kind : projector.name();
			}
		}
		return "Anchor: " + kind + " @ " + anchorPos.toShortString();
	}

	private String entryLabel(RemotePairingListPayload.Entry entry) {
		String kind = switch (entry.kind()) {
			case PROJECTOR -> "Projector";
			case SPEAKER -> "Speaker";
			case SCREEN -> "Screen";
		};
		String name = entry.name().isBlank() ? kind : entry.name();
		return name + " · " + entry.pos().toShortString() + " · "
			+ String.format(Locale.ROOT, "%.0f m", entry.distance());
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
		if (mouseX >= contentLeft && mouseX < contentLeft + contentWidth
			&& mouseY >= listTop && mouseY < listBottom) {
			int maxScroll = Math.max(0, entries.size() - visibleRows());
			int next = Math.max(0, Math.min(maxScroll, scroll - (int) Math.signum(scrollY)));
			if (next != scroll) {
				scroll = next;
				rebuildWidgets();
			}
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
		if (parent != null && minecraft != null) {
			// Defer the parent switch until after the mouse release so the same
			// click does not activate a widget in the receiver/projector GUI.
			Minecraft client = minecraft;
			Screen target = parent;
			client.execute(() -> client.setScreen(target));
		} else {
			super.onClose();
		}
	}
}
