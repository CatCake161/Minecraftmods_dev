package com.example.firstmod.client.render;

import com.example.firstmod.FirstMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Low-resolution projector shadow mask.
 *
 * <p>Rays are cast from the lens to a 32x18 grid on the screen surface. Cells
 * that hit a collider before reaching the screen are drawn as a translucent
 * black overlay. The mask updates every 500 ms; the 32x18 baseline is the one
 * measured in P0.5 (about 0.5-1.1 ms with 576 rays).
 */
public final class ProjectorShadow {
	private static final int COLS = 32;
	private static final int ROWS = 18;
	private static final long UPDATE_MS = 500L;
	private static final double SHADOW_THRESHOLD = 0.05;
	private static final int SHADOW_ALPHA = 175;
	private static final ResourceLocation WHITE =
		ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	private static final Map<MaskKey, Mask> CACHE = new HashMap<>();
	private static int raycasts;

	private ProjectorShadow() {
	}

	/** Returns the current mask, recomputing it if the cache is stale. */
	public static boolean[] mask(Level level, ProjectorOptics optics) {
		long now = System.nanoTime() / 1_000_000L;
		MaskKey key = new MaskKey(optics.projector().getBlockPos(), optics.screen().master());
		Mask cached = CACHE.get(key);
		if (cached != null && now - cached.atMs <= UPDATE_MS) {
			return cached.cells;
		}

		boolean[] cells = new boolean[COLS * ROWS];
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || optics.brightness() <= 0.01f) {
			CACHE.put(key, new Mask(now, cells));
			return cells;
		}

		long start = System.nanoTime();
		ScreenGeometry screen = optics.screen();
		Vec3 origin = screen.surfaceOrigin();
		Vec3 right = screen.right();
		Vec3 up = screen.up();
		Vec3 lens = optics.lens();
		Vec3 normal = screen.normal();
		// Pull the sample points slightly out of the screen plane so the ray does
		// not stop on the screen block itself.
		Vec3 sampleOrigin = origin.add(normal.scale(0.005));
		int blocked = 0;

		for (int row = 0; row < ROWS; row++) {
			double v = 1.0 - (row + 0.5) / ROWS;
			for (int col = 0; col < COLS; col++) {
				double u = (col + 0.5) / COLS;
				Vec3 point = sampleOrigin
					.add(right.scale(u * screen.width()))
					.add(up.scale(v * screen.height()));
				HitResult hit = level.clip(new ClipContext(lens, point,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
				boolean shadowed = hit.getType() != HitResult.Type.MISS
					&& hit.getLocation().distanceToSqr(lens) < point.distanceToSqr(lens) - SHADOW_THRESHOLD;
				cells[row * COLS + col] = shadowed;
				if (shadowed) {
					blocked++;
				}
			}
		}

		double elapsedMs = (System.nanoTime() - start) / 1e6;
		raycasts++;
		if (raycasts % 20 == 1) {
			FirstMod.LOGGER.info("cinema projector: {} shadow rays, {} blocked, {} ms",
				COLS * ROWS, blocked, String.format("%.2f", elapsedMs));
		}
		if (CACHE.size() > 64) {
			CACHE.clear();
		}
		CACHE.put(key, new Mask(now, cells));
		return cells;
	}

	/** Draws the shadow cells as a translucent black overlay on the screen. */
	public static void render(Level level, PoseStack poseStack, MultiBufferSource bufferSource,
							  ProjectorOptics optics) {
		if (level == null) {
			return;
		}
		boolean[] cells = mask(level, optics);
		boolean any = false;
		for (boolean cell : cells) {
			if (cell) {
				any = true;
				break;
			}
		}
		if (!any) {
			return;
		}

		ScreenGeometry screen = optics.screen();
		double cellWidth = screen.width() / (double) COLS;
		double cellHeight = screen.height() / (double) ROWS;
		Vec3 right = screen.right().scale(cellWidth);
		Vec3 up = screen.up().scale(cellHeight);
		Vec3 base = screen.localSurfaceOrigin().add(screen.normal().scale(0.004));
		float nx = (float) screen.normal().x;
		float nz = (float) screen.normal().z;
		Matrix4f pose = poseStack.last().pose();
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(WHITE));

		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				if (!cells[row * COLS + col]) {
					continue;
				}
				Vec3 p0 = base.add(right.scale(col)).add(up.scale(ROWS - 1 - row));
				Vec3 p1 = p0.add(right);
				Vec3 p2 = p1.add(up);
				Vec3 p3 = p0.add(up);
				addQuad(consumer, pose, p0, p1, p2, p3, nx, nz);
			}
		}
	}

	private static void addQuad(VertexConsumer consumer, Matrix4f pose, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3,
								float nx, float nz) {
		vertex(consumer, pose, p0, nx, nz);
		vertex(consumer, pose, p1, nx, nz);
		vertex(consumer, pose, p2, nx, nz);
		vertex(consumer, pose, p3, nx, nz);
	}

	private static void vertex(VertexConsumer consumer, Matrix4f pose, Vec3 point, float nx, float nz) {
		consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor(0, 0, 0, SHADOW_ALPHA)
			.setUv(0.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
	}

	public static void clearCache() {
		CACHE.clear();
		raycasts = 0;
	}

	private record MaskKey(net.minecraft.core.BlockPos projector, net.minecraft.core.BlockPos screen) {
	}

	private record Mask(long atMs, boolean[] cells) {
	}
}
