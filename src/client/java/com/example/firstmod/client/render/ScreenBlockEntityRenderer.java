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
import net.minecraft.resources.ResourceLocation;

/**
 * Paints the live video texture on the screen block's front face.
 *
 * <p>The first block slice uses a centered 16:9 image on a 1x1 block. The black
 * frame is the normal block model; multi-block UV splitting comes later.
 */
public final class ScreenBlockEntityRenderer implements BlockEntityRenderer<ScreenBlockEntity> {
	private static final ResourceLocation IDLE_TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/block/black_concrete.png");
	private static final float MIN = 0.05f;
	private static final float MAX = 0.95f;
	private static final float MIN_Y = 0.246875f;
	private static final float MAX_Y = 0.753125f;

	public ScreenBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(ScreenBlockEntity blockEntity, float tickDelta, PoseStack poseStack,
					   MultiBufferSource bufferSource, int light, int overlay) {
		Direction facing = blockEntity.getBlockState().getValue(ScreenBlock.FACING);
		ResourceLocation texture = CinemaPlayer.active() != null
			? CinemaPlayer.TEXTURE_ID : IDLE_TEXTURE;
		VertexConsumer consumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(texture));
		var pose = poseStack.last().pose();
		float normalX = facing.getStepX();
		float normalZ = facing.getStepZ();

		switch (facing) {
			case NORTH -> {
				quad(consumer, pose, MIN, MIN_Y, -0.001f, MAX, MAX_Y, -0.001f,
					normalX, normalZ);
			}
			case SOUTH -> {
				quad(consumer, pose, MAX, MIN_Y, 1.001f, MIN, MAX_Y, 1.001f,
					normalX, normalZ);
			}
			case WEST -> {
				quad(consumer, pose, -0.001f, MIN_Y, MIN, -0.001f, MAX_Y, MAX,
					normalX, normalZ);
			}
			case EAST -> {
				quad(consumer, pose, 1.001f, MIN_Y, MAX, 1.001f, MAX_Y, MIN,
					normalX, normalZ);
			}
			default -> {
			}
		}
	}

	private static void quad(VertexConsumer consumer, org.joml.Matrix4f pose,
							 float x0, float y0, float z0, float x1, float y1, float z1,
							 float normalX, float normalZ) {
		consumer.addVertex(pose, x0, y0, z0)
			.setColor(255, 255, 255, 255)
			.setUv(1.0f, 1.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(normalX, 0.0f, normalZ);
		consumer.addVertex(pose, x1, y0, z1)
			.setColor(255, 255, 255, 255)
			.setUv(0.0f, 1.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(normalX, 0.0f, normalZ);
		consumer.addVertex(pose, x1, y1, z1)
			.setColor(255, 255, 255, 255)
			.setUv(0.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(normalX, 0.0f, normalZ);
		consumer.addVertex(pose, x0, y1, z0)
			.setColor(255, 255, 255, 255)
			.setUv(1.0f, 0.0f)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(0xF000F0)
			.setNormal(normalX, 0.0f, normalZ);
	}
}
