package com.example.cinema.client.gui;

import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.audio.speaker.SpeakerDataProfile;
import com.example.cinema.client.media.audio.speaker.SpeakerDataRegistry;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Scrollable, searchable profile picker for one cabinet type.
 *
 * <p>The measured library has hundreds of bookshelf profiles, so the picker
 * keeps a fixed set of row buttons and moves a window over the filtered list.
 * The search box matches every whitespace-separated term against the profile
 * name and id, and the mouse wheel, arrow keys, Page Up/Down, Home and End
 * scroll the list.
 */
public final class SpeakerDataSelectionScreen extends Screen {
	private static final int ROW_HEIGHT = 20;
	private static final int ROW_GAP = 2;
	private static final int SEARCH_HEIGHT = 16;

	private final Screen parent;
	private final SpeakerType type;
	private final String selectedId;
	private final Consumer<String> onSelect;
	private final List<SpeakerDataProfile> profiles;
	private final List<SpeakerDataProfile> filtered = new ArrayList<>();
	private final List<Button> rowButtons = new ArrayList<>();

	private EditBox searchBox;
	private Button clearButton;
	private String filter = "";
	private int scroll;
	private boolean initialScroll = true;
	private int left;
	private int top;
	private int panelWidth;
	private int panelHeight;
	private int listLeft;
	private int listTop;
	private int listBottom;
	private int rowWidth;
	private int visibleRows = 1;

	public SpeakerDataSelectionScreen(Screen parent, SpeakerType type, String selectedId,
									  Consumer<String> onSelect) {
		super(Component.literal("Select Speaker Data"));
		this.parent = parent;
		this.type = type;
		this.selectedId = selectedId == null ? "" : selectedId;
		this.onSelect = onSelect;
		SpeakerDataRegistry.load();
		this.profiles = SpeakerDataRegistry.profilesFor(type);
	}

	@Override
	protected void init() {
		super.init();
		rowButtons.clear();

		panelWidth = Math.min(width - 24, 440);
		panelHeight = Math.min(height - 20, 300);
		left = (width - panelWidth) / 2;
		top = (height - panelHeight) / 2;

		int searchY = top + 26;
		int clearWidth = 40;
		searchBox = new EditBox(font, left + 12, searchY,
			panelWidth - 24 - clearWidth - 4, SEARCH_HEIGHT, Component.literal("Search"));
		searchBox.setHint(Component.literal("Search profiles"));
		searchBox.setMaxLength(80);
		// Set the restored text before the responder, so init() does not reset scroll.
		searchBox.setValue(filter);
		searchBox.setResponder(this::onSearchChanged);
		addRenderableWidget(searchBox);

		clearButton = Button.builder(Component.literal("Clear"),
				button -> searchBox.setValue(""))
			.bounds(left + panelWidth - 12 - clearWidth, searchY, clearWidth, SEARCH_HEIGHT)
			.build();
		clearButton.active = !filter.isBlank();
		addRenderableWidget(clearButton);

		listLeft = left + 12;
		listTop = searchY + SEARCH_HEIGHT + 6;
		listBottom = top + panelHeight - 30;
		rowWidth = panelWidth - 32;
		visibleRows = Math.max(1, (listBottom - listTop + ROW_GAP) / (ROW_HEIGHT + ROW_GAP));

		for (int row = 0; row < visibleRows; row++) {
			final int rowIndex = row;
			int y = listTop + row * (ROW_HEIGHT + ROW_GAP);
			Button button = Button.builder(Component.empty(), b -> chooseRow(rowIndex))
				.bounds(listLeft, y, rowWidth, ROW_HEIGHT).build();
			rowButtons.add(button);
			addRenderableWidget(button);
		}

		rebuildFiltered();
		if (initialScroll) {
			initialScroll = false;
			int index = indexOfSelected();
			if (index >= 0) {
				scroll = Math.max(0, Math.min(maxScroll(), index - visibleRows / 2));
			}
		}
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
		refreshRows();

		addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> returnToParent())
			.bounds(left + panelWidth / 2 - 32, top + panelHeight - 22, 64, 20).build());
	}

	private void onSearchChanged(String value) {
		filter = value;
		rebuildFiltered();
		scroll = 0;
		refreshRows();
	}

	private void rebuildFiltered() {
		filtered.clear();
		String query = filter.trim().toLowerCase(Locale.ROOT);
		if (query.isEmpty()) {
			filtered.addAll(profiles);
			return;
		}
		String[] terms = query.split("\\s+");
		for (SpeakerDataProfile profile : profiles) {
			String haystack = (profile.name() + " " + profile.id()).toLowerCase(Locale.ROOT);
			boolean matches = true;
			for (String term : terms) {
				if (!haystack.contains(term)) {
					matches = false;
					break;
				}
			}
			if (matches) {
				filtered.add(profile);
			}
		}
	}

	private void refreshRows() {
		scroll = Math.max(0, Math.min(maxScroll(), scroll));
		for (int row = 0; row < rowButtons.size(); row++) {
			Button button = rowButtons.get(row);
			int index = scroll + row;
			if (index < filtered.size()) {
				button.visible = true;
				button.active = true;
				button.setMessage(Component.literal(label(filtered.get(index))));
			} else {
				button.visible = false;
				button.active = false;
			}
		}
		if (clearButton != null) {
			clearButton.active = !filter.isBlank();
		}
	}

	private String label(SpeakerDataProfile profile) {
		String marker = profile.id().equals(selectedId) ? "> " : "  ";
		String detail = type == SpeakerType.SUBWOOFER
			? profile.enclosureLabel() + " · " + profile.directivityLabel()
			: profile.patternLabel();
		return truncate(marker + profile.name() + " · " + detail, Math.max(10, rowWidth - 12));
	}

	private String truncate(String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ellipsis)))
			+ ellipsis;
	}

	private int indexOfSelected() {
		for (int i = 0; i < filtered.size(); i++) {
			if (filtered.get(i).id().equals(selectedId)) {
				return i;
			}
		}
		return -1;
	}

	private int maxScroll() {
		return Math.max(0, filtered.size() - visibleRows);
	}

	private void chooseRow(int row) {
		int index = scroll + row;
		if (index >= 0 && index < filtered.size()) {
			choose(filtered.get(index));
		}
	}

	private void choose(SpeakerDataProfile profile) {
		if (onSelect != null) {
			onSelect.accept(profile.id());
		}
		returnToParent();
	}

	private void scrollBy(int rows) {
		int next = Math.max(0, Math.min(maxScroll(), scroll + rows));
		if (next != scroll) {
			scroll = next;
			refreshRows();
		}
	}

	private void scrollTo(int index) {
		int next = Math.max(0, Math.min(maxScroll(), index));
		if (next != scroll) {
			scroll = next;
			refreshRows();
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0.0 && mouseX >= listLeft && mouseX < listLeft + rowWidth + 8
			&& mouseY >= listTop && mouseY < listBottom) {
			scrollBy(-(int) Math.signum(scrollY));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (searchBox != null && searchBox.isFocused()) {
			return super.keyPressed(keyCode, scanCode, modifiers);
		}
		return switch (keyCode) {
			case GLFW.GLFW_KEY_UP -> {
				scrollBy(-1);
				yield true;
			}
			case GLFW.GLFW_KEY_DOWN -> {
				scrollBy(1);
				yield true;
			}
			case GLFW.GLFW_KEY_PAGE_UP -> {
				scrollBy(-visibleRows);
				yield true;
			}
			case GLFW.GLFW_KEY_PAGE_DOWN -> {
				scrollBy(visibleRows);
				yield true;
			}
			case GLFW.GLFW_KEY_HOME -> {
				scrollTo(0);
				yield true;
			}
			case GLFW.GLFW_KEY_END -> {
				scrollTo(maxScroll());
				yield true;
			}
			default -> super.keyPressed(keyCode, scanCode, modifiers);
		};
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
			left + 12, top + 10, 0xE8E8E8, false);

		graphics.fill(listLeft, listTop, listLeft + rowWidth, listBottom, 0x40000000);
		if (filtered.isEmpty()) {
			String empty = profiles.isEmpty() ? "No profiles" : "No matching profile";
			graphics.drawCenteredString(font, empty, left + panelWidth / 2,
				(listTop + listBottom) / 2 - 4, 0x9A9A9A);
		}

		int maxScroll = maxScroll();
		if (maxScroll > 0) {
			int trackHeight = Math.max(1, listBottom - listTop);
			int thumbHeight = Math.max(8,
				trackHeight * visibleRows / Math.max(visibleRows, filtered.size()));
			int thumbY = listTop + (trackHeight - thumbHeight) * scroll / maxScroll;
			int scrollbarX = left + panelWidth - 11;
			graphics.fill(scrollbarX, listTop, scrollbarX + 2, listBottom, 0x50000000);
			graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight,
				0xFFAAAAAA);
		}

		String status = filter.isBlank()
			? profiles.size() + " profile(s)"
			: filtered.size() + " / " + profiles.size() + " profile(s)";
		graphics.drawString(font, status, left + 12, top + panelHeight - 16,
			0x9A9A9A, false);
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
