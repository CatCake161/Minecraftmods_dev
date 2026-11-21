package com.example.cinema.client.render;

import com.example.cinema.client.media.AvReceiverPlaybackManager;
import com.example.cinema.client.media.audio.debug.SourceDebugPoint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Optional world-space debug view of every active audio source.
 *
 * <p>Direct speakers are blue, per-tap reflections red, physical wet points
 * green, and ghost wet points coloured by reflection order. Inactive wet slots
 * are dim grey. Reflection and ghost markers are clamped in front of the first
 * wall by the playback layer so they stay visible inside the room.
 */
public final class SourceDebugRenderer {
	private static final int DIRECT = 0xFF4AA3FF;
	private static final int REFLECTION = 0xFFFF5555;
	private static final int WET_PHYSICAL = 0xFF33E85A;
	private static final int WET_LFE = 0xFFB08CFF;
	private static final int INACTIVE = 0x664A4A4A;
	private static final int[] GHOST_COLORS = {
		0x00000000, 0xFFFFD633, 0xFFFF8A1F, 0xFFFF3DBE, 0xFF33C8FF,
	};

	private SourceDebugRenderer() {
	}

	public static boolean isEnabled() {
		return AvReceiverPlaybackManager.sourceDebugEnabled();
	}

	public static void setEnabled(boolean value) {
		AvReceiverPlaybackManager.setSourceDebugEnabled(value);
	}

	public static boolean toggle() {
		boolean next = !AvReceiverPlaybackManager.sourceDebugEnabled();
		AvReceiverPlaybackManager.setSourceDebugEnabled(next);
		return next;
	}

	public static void render(WorldRenderContext context) {
		if (!AvReceiverPlaybackManager.sourceDebugEnabled()) {
			return;
		}
		List<SourceDebugPoint> points = AvReceiverPlaybackManager.debugSources();
		if (points.isEmpty()) {
			return;
		}
		PoseStack matrices = context.matrixStack();
		MultiBufferSource consumers = context.consumers();
		if (matrices == null || consumers == null) {
			return;
		}
		Vec3 camera = context.camera().getPosition();
		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);
		VertexConsumer lines = consumers.getBuffer(RenderType.lines());
		Matrix4f pose = matrices.last().pose();
		for (SourceDebugPoint point : points) {
			int color = color(point);
			double half = halfSize(point);
			if (!point.active()) {
				half *= 0.65;
			}
			box(lines, pose, point.position(), half, color);
			if (point.active() && shouldDrawLine(point)) {
				line(lines, pose, camera, point.position(),
					(color & 0x00FFFFFF) | 0x4D000000);
			}
		}
		matrices.popPose();
	}

	private static boolean shouldDrawLine(SourceDebugPoint point) {
		return point.kind() == SourceDebugPoint.Kind.REFLECTION
			|| point.kind() == SourceDebugPoint.Kind.WET_GHOST;
	}

	private static double halfSize(SourceDebugPoint point) {
		return switch (point.kind()) {
			case DIRECT -> 0.14;
			case REFLECTION -> 0.11;
			case WET_PHYSICAL -> 0.10;
			case WET_LFE -> 0.14;
			case WET_GHOST -> 0.16;
		};
	}

	private static int color(SourceDebugPoint point) {
		if (!point.active()) {
			return INACTIVE;
		}
		return switch (point.kind()) {
			case DIRECT -> DIRECT;
			case REFLECTION -> REFLECTION;
			case WET_PHYSICAL -> WET_PHYSICAL;
			case WET_LFE -> WET_LFE;
			case WET_GHOST -> {
				int order = Math.max(1,
					Math.min(SourceDebugPoint.MAX_ORDER, point.order()));
				yield GHOST_COLORS[order];
			}
		};
	}

	private static void box(VertexConsumer consumer, Matrix4f pose,
							Vec3 center, double half, int color) {
		Vec3 min = center.add(-half, -half, -half);
		Vec3 max = center.add(half, half, half);
		Vec3[] corners = {
			new Vec3(min.x, min.y, min.z),
			new Vec3(max.x, min.y, min.z),
			new Vec3(max.x, max.y, min.z),
			new Vec3(min.x, max.y, min.z),
			new Vec3(min.x, min.y, max.z),
			new Vec3(max.x, min.y, max.z),
			new Vec3(max.x, max.y, max.z),
			new Vec3(min.x, max.y, max.z),
		};
		int[] edges = {
			0, 1, 1, 2, 2, 3, 3, 0,
			4, 5, 5, 6, 6, 7, 7, 4,
			0, 4, 1, 5, 2, 6, 3, 7,
		};
		for (int index = 0; index < edges.length; index += 2) {
			line(consumer, pose, corners[edges[index]],
				corners[edges[index + 1]], color);
		}
	}

	private static void line(VertexConsumer consumer, Matrix4f pose,
							 Vec3 from, Vec3 to, int color) {
		double dx = to.x - from.x;
		double dy = to.y - from.y;
		double dz = to.z - from.z;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float nx = length < 1.0e-6 ? 0.0f : (float) (dx / length);
		float ny = length < 1.0e-6 ? 1.0f : (float) (dy / length);
		float nz = length < 1.0e-6 ? 0.0f : (float) (dz / length);
		int alpha = (color >>> 24) & 0xFF;
		int red = (color >> 16) & 0xFF;
		int green = (color >> 8) & 0xFF;
		int blue = color & 0xFF;
		consumer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
			.setColor(red, green, blue, alpha)
			.setNormal(nx, ny, nz);
		consumer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
			.setColor(red, green, blue, alpha)
			.setNormal(nx, ny, nz);
	}
}
