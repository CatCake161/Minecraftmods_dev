package com.example.firstmod.client.render;

import com.example.firstmod.FirstMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * P0.5 visual probe: a world-space screen fed by a {@link DynamicTexture},
 * with a projector-style UV mapping and a translucent light cone.
 *
 * <p>This deliberately does not add blocks. It is a client-only command/test
 * scene used to verify the rendering path before the real screen/projector
 * blocks exist. The texture is a moving SMPTE-like pattern so orientation,
 * color channels and UV projection are easy to inspect.
 */
public final class CinemaVisualSpike {
	private static final int WIDTH = 256;
	private static final int HEIGHT = 144;
	private static final int SHADOW_COLS = 32;
	private static final int SHADOW_ROWS = 18;
	private static final ResourceLocation TEXTURE_ID = FirstMod.id("spike_screen");
	private static final RenderType BEAM = RenderType.create("firstmod_cinema_beam",
		DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 1536,
		RenderType.CompositeState.builder()
			.setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
			.setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
			.setCullState(RenderStateShard.NO_CULL)
			.setWriteMaskState(RenderStateShard.COLOR_WRITE)
			.setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
			.createCompositeState(false));

	private static final boolean[] SHADOW = new boolean[SHADOW_COLS * SHADOW_ROWS];
	private static final float[] UVS = new float[8];

	private static boolean enabled;
	private static boolean initialized;
	private static DynamicTexture texture;
	private static Vec3 lens;
	private static Vec3 origin;
	private static Vec3 right;
	private static Vec3 up;
	private static Vec3 normal;
	private static Matrix4f projectorView;
	private static Matrix4f projectorProjection;

	private static int frame;
	private static int shadowFrame;
	private static double lastRaycastMs;

	private CinemaVisualSpike() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void toggle(Minecraft client) {
		if (enabled) {
			enabled = false;
			FirstMod.LOGGER.info("cinema visual spike: off");
			return;
		}
		enable(client);
	}

	public static void enable(Minecraft client) {
		if (client.player == null || client.level == null) {
			return;
		}
		ensureTexture(client);

		Vec3 look = client.player.getLookAngle();
		Vec3 forward = new Vec3(look.x, 0.0, look.z);
		if (forward.lengthSqr() < 1.0E-4) {
			forward = new Vec3(0.0, 0.0, -1.0);
		}
		forward = forward.normalize();
		Vec3 rightDir = new Vec3(-forward.z, 0.0, forward.x);
		Vec3 eye = client.player.getEyePosition();
		Vec3 center = eye.add(forward.scale(6.0));
		double screenWidth = 4.8;
		double screenHeight = 2.7;

		origin = center.subtract(rightDir.scale(screenWidth / 2.0)).subtract(0.0, screenHeight / 2.0, 0.0);
		right = rightDir.scale(screenWidth);
		up = new Vec3(0.0, screenHeight, 0.0);
		normal = forward.scale(-1.0);

		// Projector lens: a little behind/above the player, aimed at the
		// screen center. The UVs come from a real view/projection matrix, so
		// an off-axis lens produces a keystoned image instead of a flat quad.
		lens = eye.add(forward.scale(1.2)).add(0.0, 1.0, 0.0);
		projectorView = new Matrix4f().lookAt(
			new Vector3f((float) lens.x, (float) lens.y, (float) lens.z),
			new Vector3f((float) center.x, (float) center.y, (float) center.z),
			new Vector3f(0.0f, 1.0f, 0.0f));
		float fovY = (float) (2.0 * Math.atan((screenHeight / 2.0) / (6.0 - 1.2)));
		projectorProjection = new Matrix4f().perspective(fovY, (float) screenWidth / (float) screenHeight,
			0.05f, 100.0f);

		Vec3 p0 = origin;
		Vec3 p1 = origin.add(right);
		Vec3 p2 = origin.add(right).add(up);
		Vec3 p3 = origin.add(up);
		projectUv(p0, 0);
		projectUv(p1, 2);
		projectUv(p2, 4);
		projectUv(p3, 6);

		frame = 0;
		shadowFrame = 0;
		enabled = true;
		FirstMod.LOGGER.info("cinema visual spike: on, eye={} lens={} origin={} normal={} look={} cameraLook={} uvs=[{}, {}, {}, {}, {}, {}, {}, {}]",
			eye, lens, origin, normal, look, client.gameRenderer.getMainCamera().getLookVector(),
			String.format("%.3f", UVS[0]), String.format("%.3f", UVS[1]),
			String.format("%.3f", UVS[2]), String.format("%.3f", UVS[3]),
			String.format("%.3f", UVS[4]), String.format("%.3f", UVS[5]),
			String.format("%.3f", UVS[6]), String.format("%.3f", UVS[7]));
	}

	public static void render(WorldRenderContext context) {
		if (!enabled) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || context.consumers() == null || context.matrixStack() == null) {
			return;
		}

		if (++frame % 3 == 0) {
			updateTexture(client);
		}

		Vec3 camera = context.camera().getPosition();
		PoseStack poseStack = context.matrixStack();
		poseStack.pushPose();
		poseStack.translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
		Matrix4f pose = poseStack.last().pose();

		// Local coordinates: the pose is translated to the screen origin.
		Vec3 p0 = Vec3.ZERO;
		Vec3 p1 = right;
		Vec3 p2 = right.add(up);
		Vec3 p3 = up;
		Vec3 lensLocal = lens.subtract(origin);

		// Beam: four quads from the lens to the screen edges. The custom render
		// type writes color but not depth, so the beam cannot hide the screen.
		VertexConsumer beam = context.consumers().getBuffer(BEAM);
		beamQuad(beam, pose, lensLocal, p0, p1);
		beamQuad(beam, pose, lensLocal, p1, p2);
		beamQuad(beam, pose, lensLocal, p2, p3);
		beamQuad(beam, pose, lensLocal, p3, p0);

		// Screen.
		VertexConsumer screen = context.consumers().getBuffer(RenderType.entitySolid(TEXTURE_ID));
		screenVertex(screen, pose, p0, UVS[0], UVS[1]);
		screenVertex(screen, pose, p1, UVS[2], UVS[3]);
		screenVertex(screen, pose, p2, UVS[4], UVS[5]);
		screenVertex(screen, pose, p3, UVS[6], UVS[7]);

		poseStack.popPose();
	}

	private static void beamQuad(VertexConsumer beam, Matrix4f pose, Vec3 lensRel, Vec3 a, Vec3 b) {
		beam.addVertex(pose, (float) lensRel.x, (float) lensRel.y, (float) lensRel.z)
			.setColor(0.035f, 0.065f, 0.12f, 1.0f);
		beam.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(0.004f, 0.008f, 0.015f, 1.0f);
		beam.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(0.004f, 0.008f, 0.015f, 1.0f);
		beam.addVertex(pose, (float) lensRel.x, (float) lensRel.y, (float) lensRel.z)
			.setColor(0.035f, 0.065f, 0.12f, 1.0f);
	}

	private static void screenVertex(VertexConsumer screen, Matrix4f pose, Vec3 p, float u, float v) {
		screen.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
			.setColor(255, 255, 255, 255)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
	}

	private static void projectUv(Vec3 point, int index) {
		Vector4f clip = new Vector4f(
			(float) point.x,
			(float) point.y,
			(float) point.z,
			1.0f);
		clip.mul(projectorView).mul(projectorProjection);
		float invW = 1.0f / clip.w;
		UVS[index] = (clip.x * invW + 1.0f) * 0.5f;
		UVS[index + 1] = 1.0f - (clip.y * invW + 1.0f) * 0.5f;
	}

	private static void ensureTexture(Minecraft client) {
		if (initialized) {
			return;
		}
		texture = new DynamicTexture(WIDTH, HEIGHT, true);
		client.getTextureManager().register(TEXTURE_ID, texture);
		updateTexture(client);
		initialized = true;
	}

	private static void updateTexture(Minecraft client) {
		if (texture == null) {
			return;
		}
		if (++shadowFrame % 20 == 0) {
			updateShadow(client);
		}

		NativeImage image = texture.getPixels();
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				image.setPixelRGBA(x, y, patternColor(x, y));
			}
		}
		texture.upload();
	}

	private static int patternColor(int x, int y) {
		// 4 px black border, then SMPTE-like bars and a grayscale ramp.
		if (x < 4 || y < 4 || x >= WIDTH - 4 || y >= HEIGHT - 4) {
			return 0xFF101010;
		}
		if (y < HEIGHT * 2 / 3) {
			int bar = Math.min(6, (x - 4) * 7 / (WIDTH - 8));
			int[] colors = {0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00,
				0xFFFF00FF, 0xFFFF0000, 0xFF0000FF};
			int color = colors[bar];
			// Moving diagonal marker makes dropped frames / stale textures visible.
			int marker = (frame * 3) % (WIDTH + HEIGHT);
			if (Math.abs((x + y) - marker) < 3) {
				color = 0xFF000000;
			}
			return color;
		}
		int step = Math.max(0, Math.min(15, (x - 4) * 16 / (WIDTH - 8)));
		int gray = step * 17;
		int color = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
		if (y > HEIGHT - 24) {
			int cellX = (x - 4) / 16;
			int cellY = (y - (HEIGHT - 24)) / 10;
			if (((cellX + cellY) & 1) == 0) {
				color = 0xFF202020;
			}
		}
		int col = (x - 4) * SHADOW_COLS / (WIDTH - 8);
		int row = (y - 4) * SHADOW_ROWS / (HEIGHT - 8);
		if (col >= 0 && col < SHADOW_COLS && row >= 0 && row < SHADOW_ROWS
			&& SHADOW[row * SHADOW_COLS + col]) {
			color = (color & 0xFF000000) | ((color & 0x00FEFEFE) >> 2);
		}
		return color;
	}

	private static void updateShadow(Minecraft client) {
		if (client.level == null || client.player == null) {
			return;
		}
		long start = System.nanoTime();
		Vec3 p0 = origin;
		for (int row = 0; row < SHADOW_ROWS; row++) {
			double v = (row + 0.5) / SHADOW_ROWS;
			for (int col = 0; col < SHADOW_COLS; col++) {
				double u = (col + 0.5) / SHADOW_COLS;
				Vec3 point = p0.add(right.scale(u)).add(up.scale(v));
				HitResult hit = client.level.clip(new ClipContext(lens, point,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
				double pointDistance = lens.distanceToSqr(point);
				SHADOW[row * SHADOW_COLS + col] = hit.getType() != HitResult.Type.MISS
					&& hit.getLocation().distanceToSqr(lens) < pointDistance - 0.01;
			}
		}
		lastRaycastMs = (System.nanoTime() - start) / 1e6;
		if (shadowFrame % 100 == 0) {
			FirstMod.LOGGER.info("cinema visual spike: {} shadow rays in {} ms",
				SHADOW_COLS * SHADOW_ROWS, String.format("%.2f", lastRaycastMs));
		}
	}

	public static void screenshot(Minecraft client) {
		Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(),
			message -> FirstMod.LOGGER.info("cinema visual spike: screenshot {}", message.getString()));
	}
}
