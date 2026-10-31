package com.example.firstmod.client.render;

import com.example.firstmod.block.entity.ScreenBlockEntity;
import com.example.firstmod.client.media.CinemaPlayer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the video surface projected by the paired projector.
 *
 * <p>Each screen block is one quad. Its corners are projected through the
 * projector lens, keystone-warped, then clipped to the 0..1 image rectangle.
 * Doing the mapping per block keeps the per-quad distortion small and gives
 * correct black borders when the throw cone is smaller or larger than the
 * screen. A screen without a paired projector stays dark; there is no flat
 * fallback, so breaking the projector removes the picture immediately.
 */
public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
	private static final ResourceLocation WHITE =
		ResourceLocation.withDefaultNamespace("textures/misc/white.png");
	private static final float VIDEO_SURFACE_OFFSET = 0.002f;
	private static final float OVERLAY_SURFACE_OFFSET = 0.004f;
	private static long lastDebugLog;

	public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(ScreenBlockEntity blockEntity, float tickDelta, PoseStack poseStack,
					   MultiBufferSource bufferSource, int light, int overlay) {
		CinemaPlayer player = CinemaPlayer.active();
		if (player == null || player.width() <= 0 || player.height() <= 0) {
			return;
		}
		Level level = blockEntity.getLevel();
		if (level == null) {
			return;
		}
		ScreenGeometry geometry = ScreenGeometry.find(
			level, blockEntity.getBlockPos(), blockEntity.getBlockState());
		if (!blockEntity.getBlockPos().equals(geometry.master())) {
			return;
		}

		ProjectorOptics optics = ProjectorOptics.forScreen(level, geometry, player.aspect());
		if (optics == null) {
			// No paired projector means the screen is dark. Do not fall back to a
			// flat video surface: that made the picture linger after the projector
			// was broken and appeared on screens that were never being projected.
			return;
		}
		if (System.getProperty("firstmod.spike.projector.debug") != null) {
			long now = System.nanoTime();
			if (now - lastDebugLog > 1_000_000_000L) {
				lastDebugLog = now;
				com.example.firstmod.FirstMod.LOGGER.info(
					"cinema projector debug: screen={} aspect={} throw={} focus={} error={} blur={} wash={} lamp={}",
					geometry.master(), player.aspect(), optics.throwRatio(), optics.focusDistance(),
					optics.focusError(), optics.blurLod(), optics.ambientWash(), optics.brightness());
			}
		}

		if (optics.brightness() > 0.01f) {
			player.setFocusBlur(optics.blurLod());
			drawProjected(bufferSource, poseStack, geometry, optics);
		}
		ProjectorShadow.render(level, poseStack, bufferSource, optics);
		drawAmbientWash(bufferSource, poseStack, geometry, optics.ambientWash());
	}

	private static void drawProjected(MultiBufferSource bufferSource, PoseStack poseStack,
									  ScreenGeometry geometry, ProjectorOptics optics) {
		Vec3 right = geometry.right();
		Vec3 up = geometry.up();
		Vec3 normal = geometry.normal();
		Vec3 master = Vec3.atLowerCornerOf(geometry.master());
		Vec3 base = geometry.localSurfaceOrigin().add(normal.scale(VIDEO_SURFACE_OFFSET));
		Matrix4f pose = poseStack.last().pose();
		float gain = optics.brightness();
		int brightness = Math.round(255.0f * Math.max(0.0f, Math.min(1.0f, gain)));
		boolean overdrive = gain > 1.001f;
		float nx = (float) normal.x;
		float nz = (float) normal.z;
		RenderType renderType = RenderType.entityCutoutNoCull(CinemaPlayer.TEXTURE_ID);
		VertexConsumer consumer = bufferSource.getBuffer(renderType);
		if (overdrive) {
			// Vertex colors are unsigned bytes and cannot exceed 1.0. Use the
			// shader's float ColorModulator for the >1 "high lamp" range, then
			// flush immediately so the multiplier does not leak to other draws.
			RenderSystem.setShaderColor(gain, gain, gain, 1.0f);
		}

		try {
			for (int row = 0; row < geometry.height(); row++) {
				for (int col = 0; col < geometry.width(); col++) {
					Vec3 p0 = base.add(right.scale(col)).add(up.scale(row));
					Vec3 p1 = p0.add(right);
					Vec3 p2 = p1.add(up);
					Vec3 p3 = p0.add(up);

					List<ProjectedPoint> polygon = new ArrayList<>(4);
					addPoint(polygon, p0, master, optics);
					addPoint(polygon, p1, master, optics);
					addPoint(polygon, p2, master, optics);
					addPoint(polygon, p3, master, optics);

					polygon = clip(polygon, false, true, 0.0f);
					polygon = clip(polygon, false, false, 1.0f);
					polygon = clip(polygon, true, true, 0.0f);
					polygon = clip(polygon, true, false, 1.0f);
					emitPolygon(consumer, pose, polygon, brightness, nx, nz);
				}
			}
		} finally {
			if (overdrive) {
				if (bufferSource instanceof MultiBufferSource.BufferSource batch) {
					batch.endBatch(renderType);
				}
				RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
			}
		}
	}

	private static void addPoint(List<ProjectedPoint> polygon, Vec3 local, Vec3 master,
								 ProjectorOptics optics) {
		Vec3 world = master.add(local);
		float[] uv = optics.uv(world);
		polygon.add(new ProjectedPoint(local, uv[0], uv[1]));
	}

	private static List<ProjectedPoint> clip(List<ProjectedPoint> input, boolean useV,
											 boolean keepGreater, float bound) {
		if (input.isEmpty()) {
			return input;
		}
		List<ProjectedPoint> output = new ArrayList<>(input.size() + 2);
		ProjectedPoint previous = input.get(input.size() - 1);
		boolean previousInside = inside(previous, useV, keepGreater, bound);
		for (ProjectedPoint current : input) {
			boolean currentInside = inside(current, useV, keepGreater, bound);
			if (currentInside != previousInside) {
				double previousCoord = coordinate(previous, useV);
				double currentCoord = coordinate(current, useV);
				double denominator = currentCoord - previousCoord;
				float t = Math.abs(denominator) < 1.0e-9
					? 0.0f
					: (float) ((bound - previousCoord) / denominator);
				double dx = current.local.x - previous.local.x;
				double dy = current.local.y - previous.local.y;
				double dz = current.local.z - previous.local.z;
				Vec3 local = new Vec3(
					previous.local.x + dx * t,
					previous.local.y + dy * t,
					previous.local.z + dz * t);
				float u = previous.u + (current.u - previous.u) * t;
				float v = previous.v + (current.v - previous.v) * t;
				output.add(new ProjectedPoint(local, u, v));
			}
			if (currentInside) {
				output.add(current);
			}
			previous = current;
			previousInside = currentInside;
		}
		return output;
	}

	private static boolean inside(ProjectedPoint point, boolean useV, boolean keepGreater, float bound) {
		double coordinate = coordinate(point, useV);
		double epsilon = 1.0e-5;
		return keepGreater ? coordinate >= bound - epsilon : coordinate <= bound + epsilon;
	}

	private static double coordinate(ProjectedPoint point, boolean useV) {
		return useV ? point.v : point.u;
	}

	private static void emitPolygon(VertexConsumer consumer, Matrix4f pose,
									List<ProjectedPoint> polygon, int brightness, float nx, float nz) {
		if (polygon.size() < 3) {
			return;
		}
		ProjectedPoint first = polygon.get(0);
		for (int i = 1; i < polygon.size() - 1; i++) {
			ProjectedPoint b = polygon.get(i);
			ProjectedPoint c = polygon.get(i + 1);
			// Minecraft's entity render types use QUADS; duplicate the last
			// vertex so a triangle fan can be submitted through the same path.
			vertex(consumer, pose, first, brightness, nx, nz);
			vertex(consumer, pose, b, brightness, nx, nz);
			vertex(consumer, pose, c, brightness, nx, nz);
			vertex(consumer, pose, c, brightness, nx, nz);
		}
	}

	private static void vertex(VertexConsumer consumer, Matrix4f pose, ProjectedPoint point,
							   int brightness, float nx, float nz) {
		consumer.addVertex(pose, (float) point.local.x, (float) point.local.y, (float) point.local.z)
			.setColor(brightness, brightness, brightness, 255)
			.setUv(point.u, point.v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
	}

	private static void drawAmbientWash(MultiBufferSource bufferSource, PoseStack poseStack,
										ScreenGeometry geometry, float wash) {
		if (wash <= 0.005f) {
			return;
		}
		Vec3 right = geometry.right();
		Vec3 up = geometry.up();
		Vec3 normal = geometry.normal();
		Vec3 base = geometry.localSurfaceOrigin().add(normal.scale(OVERLAY_SURFACE_OFFSET));
		Vec3 p0 = base;
		Vec3 p1 = base.add(right.scale(geometry.width()));
		Vec3 p2 = p1.add(up.scale(geometry.height()));
		Vec3 p3 = base.add(up.scale(geometry.height()));

		int alpha = Math.round(255.0f * Math.max(0.0f, Math.min(1.0f, wash)));
		Matrix4f pose = poseStack.last().pose();
		float nx = (float) normal.x;
		float nz = (float) normal.z;
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityTranslucent(WHITE));
		washVertex(consumer, pose, p0, alpha, nx, nz);
		washVertex(consumer, pose, p1, alpha, nx, nz);
		washVertex(consumer, pose, p2, alpha, nx, nz);
		washVertex(consumer, pose, p3, alpha, nx, nz);
	}

	private static void washVertex(VertexConsumer consumer, Matrix4f pose, Vec3 point,
								   int alpha, float nx, float nz) {
		consumer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
			.setColor(255, 255, 255, alpha)
			.setUv(0.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
	}

	private record ProjectedPoint(Vec3 local, float u, float v) {
	}
}
