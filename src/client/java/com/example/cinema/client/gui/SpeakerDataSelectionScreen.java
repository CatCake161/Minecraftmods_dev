package com.example.cinema.client.gui;

import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.config.CinemaClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * Paged profile picker for one cabinet type. The old config screen cycled one
 * profile at a time; with the measured library there are up to 13 bookshelf
 * profiles, so every profile needs a direct button.
 */
public final class SpeakerDataSelectionScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 3;
	private static final int PAGE_SIZE = 8;

	private final Screen parent;
	private final SpeakerType type;
	private final String selectedId;
	private final Consumer<String> onSelect;
	private final List<SpeakerDataProfile> profiles;
	private int page;
	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;

	public SpeakerDataSelectionScreen(Screen parent, SpeakerType type, String selectedId,
									  Consumer<String> onSelect) {
		super(Component.literal("Select Speaker Data"));
		this.parent = parent;
		this.type = type;
		this.selectedId = selectedId == null ? "" : selectedId;
		this.onSelect = onSelect;
		SpeakerDataRegistry.load();
		this.profiles = SpeakerDataRegistry.profilesFor(type);
		this.page = pageOf(this.selectedId);
	}

	@Override
	protected void init() {
		super.init();
		int pageCount = pageCount();
		if (page >= pageCount) {
			page = pageCount - 1;
		}
		if (page < 0) {
			page = 0;
		}
		panelWidth = Math.min(width - 24, 380);
		panelHeight = Math.min(height - 20, 300);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;
		int rowLeft = left + 12;
		int rowWidth = panelWidth - 24;
		int rowTop = top + 34;
		int from = page * PAGE_SIZE;
		int to = Math.min(profiles.size(), from + PAGE_SIZE);
		for (int i = from; i < to; i++) {
			SpeakerDataProfile profile = profiles.get(i);
			int y = rowTop + (i - from) * (ROW_HEIGHT + ROW_GAP);
			addRenderableWidget(Button.builder(Component.literal(label(profile)),
					button -> choose(profile))
				.bounds(rowLeft, y, rowWidth, ROW_HEIGHT).build());
		}
		int bottom = top + panelHeight - 26;
		if (page > 0) {
			addRenderableWidget(Button.builder(Component.literal("Previous"),
					button -> changePage(-1))
				.bounds(rowLeft, bottom, 74, 20).build());
		}
		if (page + 1 < pageCount) {
			addRenderableWidget(Button.builder(Component.literal("Next"),
					button -> changePage(1))
				.bounds(rowLeft + rowWidth - 54, bottom, 54, 20).build());
		}
		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> returnToParent())
			.bounds(left + panelWidth / 2 - 32, bottom, 64, 20).build());
	}

	private String label(SpeakerDataProfile profile) {
		String marker = profile.id().equals(selectedId) ? "> " : "  ";
		return marker + profile.name() + " · " + profile.patternLabel();
	}

	private int pageOf(String profileId) {
		for (int i = 0; i < profiles.size(); i++) {
			if (profiles.get(i).id().equals(profileId)) {
				return i / PAGE_SIZE;
			}
		}
		return 0;
	}

	private int pageCount() {
		return Math.max(1, (profiles.size() + PAGE_SIZE - 1) / PAGE_SIZE);
	}

	private void changePage(int delta) {
		page = Math.max(0, Math.min(pageCount() - 1, page + delta));
		rebuildWidgets();
	}

	private void choose(SpeakerDataProfile profile) {
		if (onSelect != null) {
			onSelect.accept(profile.id());
		}
		returnToParent();
	}

	private void returnToParent() {
		if (minecraft == null) {
			return;
		}
		Minecraft client = minecraft;
		Screen target = parent;
		client.execute(() -> client.setScreen(target));
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE0101010);
		graphics.fill(left, top, left + panelWidth, top + 1, 0xFF707070);
		graphics.fill(left, top + panelHeight - 1, left + panelWidth, top + panelHeight, 0xFF707070);
		graphics.fill(left, top, left + 1, top + panelHeight, 0xFF404040);
		graphics.fill(left + panelWidth - 1, top, left + panelWidth, top + panelHeight, 0xFF404040);
		graphics.drawString(font, "Select Speaker Data · " + type.id(),
			left + 12, top + 11, 0xE8E8E8, false);
		graphics.drawString(font, "Page " + (page + 1) + "/" + pageCount()
				+ " · " + profiles.size() + " profile(s)",
			left + panelWidth - 12 - font.width("Page " + (page + 1) + "/" + pageCount()
				+ " · " + profiles.size() + " profile(s)"),
			top + 11, 0x9A9A9A, false);
	}

	@Override
	public boolean isPauseScreen() {
		return CinemaClientConfig.get().pauseGameInDeviceGuis();
	}

	@Override
	public void onClose() {
		returnToParent();
	}
}
