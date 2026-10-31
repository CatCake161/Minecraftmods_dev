package com.example.firstmod.client.media;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * A texture whose pixels come from the video decoder instead of a resource pack.
 *
 * <p>All methods that touch GL must run on the render thread. The decoder only
 * writes into direct {@link ByteBuffer}s and hands them over through the player.
 */
public final class CinemaVideoTexture extends AbstractTexture {
	private int width = -1;
	private int height = -1;
	private boolean allocated;

	@Override
	public void load(ResourceManager resourceManager) {
		// Pixels are supplied by upload().
	}

	public void upload(ByteBuffer rgba, int frameWidth, int frameHeight) {
		RenderSystem.assertOnRenderThread();
		if (frameWidth <= 0 || frameHeight <= 0) {
			return;
		}
		bind();
		rgba.rewind();
		if (!allocated || frameWidth != width || frameHeight != height) {
			width = frameWidth;
			height = frameHeight;
			allocated = true;
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frameWidth, frameHeight, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		}
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
		GlStateManager._bindTexture(0);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() {
		allocated = false;
		width = -1;
		height = -1;
		super.close();
	}
}
