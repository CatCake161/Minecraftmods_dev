package com.example.firstmod.client.media;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

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
	private boolean mipmapsValid;
	private static long lastBlurLog;

	@Override
	public void load(ResourceManager resourceManager) {
		// Pixels are supplied by upload().
	}

	/**
	 * Render types such as {@code entityCutoutNoCull} request nearest filtering
	 * on every draw. Cinema keeps linear filtering; mipmaps are only enabled
	 * after the renderer actually asks for defocus, so sharp 4K playback does
	 * not pay for mipmap generation every frame.
	 */
	@Override
	public void setFilter(boolean blur, boolean mipmap) {
		super.setFilter(true, mipmapsValid);
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
			GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0f);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, frameWidth, frameHeight, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		}
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, frameWidth, frameHeight,
			GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
		mipmapsValid = false;
		GlStateManager._bindTexture(0);
	}

	/** 0 = sharp, higher values sample lower mip levels (projector defocus). */
	public void setBlurBias(float lod) {
		RenderSystem.assertOnRenderThread();
		float clamped = Math.max(0.0f, Math.min(4.0f, lod));
		bind();
		if (clamped > 0.05f && !mipmapsValid && allocated) {
			GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
			mipmapsValid = true;
		}
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
			mipmapsValid ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR);
		GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, clamped);
		if (System.getProperty("firstmod.spike.projector.debug") != null) {
			long now = System.nanoTime();
			if (now - lastBlurLog > 1_000_000_000L) {
				lastBlurLog = now;
				int minFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
				float readBias = GL11.glGetTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS);
				com.example.firstmod.FirstMod.LOGGER.info(
					"cinema video texture: blur={} biasRead={} minFilter=0x{} mipmaps={} size={}x{}",
					clamped, readBias, Integer.toHexString(minFilter), mipmapsValid, width, height);
			}
		}
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
		mipmapsValid = false;
		width = -1;
		height = -1;
		super.close();
	}
}
