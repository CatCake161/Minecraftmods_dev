package com.example.firstmod.client.gui;

import com.example.firstmod.block.entity.ProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Minimal projector control screen.
 *
 * <p>This is a temporary GUI slice: it exposes one control (ambient wash) so
 * the setting can be tuned in-game. The full projector GUI is planned in
 * {@code docs/design/PROJECTOR-GUI.md}; do not grow this screen piecemeal.
 */
public final class ProjectorScreen extends Screen {
	private final ProjectorBlockEntity projector;
	private final double originalWash;
	private double wash;
	private boolean saved;

	public ProjectorScreen(ProjectorBlockEntity projector) {
		super(Component.literal("Cinema Projector"));
		this.projector = projector;
		this.originalWash = projector.ambientWash();
		this.wash = originalWash;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int top = height / 2 - 36;
		addRenderableWidget(new WashSlider(centerX - 100, top, 200, 20, wash));
		addRenderableWidget(Button.builder(Component.literal("Done"), button -> saveAndClose())
			.bounds(centerX - 100, top + 48, 98, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
			.bounds(centerX + 2, top + 48, 98, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		int top = height / 2 - 36;
		graphics.drawCenteredString(font, title, width / 2, top - 24, 0xFFFFFF);
		graphics.drawCenteredString(font, Component.literal("Ambient wash: 0% = off, 100% = fully washed"),
			width / 2, top + 26, 0xA0A0A0);
	}

	private void saveAndClose() {
		apply();
		saved = true;
		onClose();
	}

	private void apply() {
		projector.setAmbientWash(wash);
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.connection.sendCommand(
				"cinema_projector wash " + String.format(Locale.ROOT, "%.3f", wash));
		}
	}

	@Override
	public void onClose() {
		if (!saved) {
			// Cancel/Esc must not leave a locally modified block entity behind.
			projector.setAmbientWash(originalWash);
		}
		super.onClose();
	}

	private final class WashSlider extends AbstractSliderButton {
		private WashSlider(int x, int y, int width, int height, double value) {
			super(x, y, width, height, Component.empty(), value);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal("Wash: " + Math.round(value * 100.0) + "%"));
		}

		@Override
		protected void applyValue() {
			wash = value;
		}
	}
}
