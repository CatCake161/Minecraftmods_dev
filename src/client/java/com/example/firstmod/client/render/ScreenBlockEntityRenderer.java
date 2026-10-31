package com.example.firstmod.client.render;

import com.example.firstmod.block.ScreenBlock;
import com.example.firstmod.block.entity.ScreenBlockEntity;
import com.example.firstmod.client.media.CinemaPlayer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws one video quad for the whole rectangular screen.
 *
 * <p>Only the master block (viewer's left/bottom of the rectangle) renders, so
 * adjacent screen blocks do not create seams. The black block model acts as the
 * frame and letterbox; the video is contained inside it.
 */
public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
	public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(ScreenBlockEntity blockEntity, float tickDelta, PoseStack poseStack,
					   MultiBufferSource bufferSource, int light, int overlay) {
		CinemaPlayer player = CinemaPlayer.active();
		if (player == null || player.width() <= 0 || player.height() <= 0) {
			return;
		}
		ScreenGeometry geometry = ScreenGeometry.find(
			blockEntity.getLevel(), blockEntity.getBlockPos(), blockEntity.getBlockState());
		if (!blockEntity.getBlockPos().equals(geometry.master())) {
			return;
		}

		vertex(blockEntity, bufferSource, poseStack, geometry, player);
	}

	private static void vertex(ScreenBlockEntity blockEntity, MultiBufferSource bufferSource,
							   PoseStack poseStack, ScreenGeometry geometry, CinemaPlayer player) {
		float videoAspect = player.width() / (float) player.height();
		double width = geometry.width();
		double height = geometry.height();
		double contentWidth;
		double contentHeight;
		if (width / height >= videoAspect) {
			contentHeight = height;
			contentWidth = height * videoAspect;
		} else {
			contentWidth = width;
			contentHeight = width / videoAspect;
		}
		double offsetX = (width - contentWidth) / 2.0;
		double offsetY = (height - contentHeight) / 2.0;

		Vec3 right = geometry.right();
		Vec3 up = geometry.up();
		Direction facing = geometry.facing();
		Vec3 normal = new Vec3(facing.getStepX() * 0.001, 0.0, facing.getStepZ() * 0.001);

		Vec3 p0 = right.scale(offsetX).add(up.scale(offsetY)).add(normal);
		Vec3 p1 = right.scale(offsetX + contentWidth).add(up.scale(offsetY)).add(normal);
		Vec3 p2 = right.scale(offsetX + contentWidth).add(up.scale(offsetY + contentHeight)).add(normal);
		Vec3 p3 = right.scale(offsetX).add(up.scale(offsetY + contentHeight)).add(normal);

		VertexConsumer consumer = bufferSource.getBuffer(
			RenderType.entityCutoutNoCull(CinemaPlayer.TEXTURE_ID));
		Matrix4f pose = poseStack.last().pose();
		float nx = facing.getStepX();
		float nz = facing.getStepZ();
		consumer.addVertex(pose, (float) p0.x, (float) p0.y, (float) p0.z)
			.setColor(255, 255, 255, 255)
			.setUv(0.0f, 1.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
		consumer.addVertex(pose, (float) p1.x, (float) p1.y, (float) p1.z)
			.setColor(255, 255, 255, 255)
			.setUv(1.0f, 1.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
		consumer.addVertex(pose, (float) p2.x, (float) p2.y, (float) p2.z)
			.setColor(255, 255, 255, 255)
			.setUv(1.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
		consumer.addVertex(pose, (float) p3.x, (float) p3.y, (float) p3.z)
			.setColor(255, 255, 255, 255)
			.setUv(0.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(nx, 0.0f, nz);
	}
}
