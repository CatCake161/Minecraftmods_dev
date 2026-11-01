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
 * Minimal projector control screen. The first control is the manual ambient
 * wash strength; future versions can add focus/keystone/lamp controls here.
 *
 * <p>The slider updates the local block entity for immediate preview and sends
 * the matching server command when applied, so real (server-side) projectors
 * stay authoritative.
 */
public final class ProjectorScreen extends Screen {
	private final ProjectorBlockEntity projector;
	private double wash;
	private WashSlider washSlider;

	public ProjectorScreen(ProjectorBlockEntity projector) {
		super(Component.literal("Cinema Projector"));
		this.projector = projector;
		this.wash = projector.ambientWash();
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int top = height / 2 - 24;
		washSlider = new WashSlider(centerX - 100, top, 200, 20, wash);
		addRenderableWidget(washSlider);
		addRenderableWidget(Button.builder(Component.literal("Apply"), button -> apply())
			.bounds(centerX - 100, top + 30, 98, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(centerX + 2, top + 30, 98, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics, mouseX, mouseY, partialTick);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(font, title, width / 2, height / 2 - 52, 0xFFFFFF);
		graphics.drawCenteredString(font, Component.literal("Ambient wash: 0% = off, 100% = fully washed"),
			width / 2, height / 2 + 8, 0xA0A0A0);
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
		apply();
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
