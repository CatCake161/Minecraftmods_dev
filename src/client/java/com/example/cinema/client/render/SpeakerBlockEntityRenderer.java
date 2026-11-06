package com.example.cinema.client.render;

import com.example.cinema.block.FloorstandingSpeakerBlock;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Renders speaker models with the block entity's 36-step acoustic yaw.
 *
 * <p>The blockstate model already contains the four-way placement rotation.
 * We subtract that rotation before applying the acoustic yaw so the final
 * visual orientation equals the acoustic orientation exactly.
 */
public final class SpeakerBlockEntityRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {
	public SpeakerBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
	}

	@Override
	public void render(SpeakerBlockEntity speaker, float partialTick, PoseStack poseStack,
					   MultiBufferSource buffers, int packedLight, int packedOverlay) {
		Level level = speaker.getLevel();
		if (level == null) {
			return;
		}
		BlockState state = speaker.getBlockState();
		if (!(state.getBlock() instanceof SpeakerBlock)) {
			return;
		}
		// Positive acoustic yaw is counter-clockwise from above (0=N, 90=W).
		// Axis.YP positive rotation increases that yaw.
		float correction = speaker.acousticYawDegrees()
			- yawForFacing(state.getValue(SpeakerBlock.FACING));
		renderModel(state, correction, poseStack, buffers, packedLight, packedOverlay);

		if (state.getBlock() instanceof FloorstandingSpeakerBlock
			&& state.getValue(FloorstandingSpeakerBlock.HALF) == DoubleBlockHalf.LOWER) {
			BlockState above = level.getBlockState(speaker.getBlockPos().above());
			if (above.getBlock() instanceof FloorstandingSpeakerBlock
				&& above.getValue(FloorstandingSpeakerBlock.HALF) == DoubleBlockHalf.UPPER) {
				poseStack.pushPose();
				poseStack.translate(0.0, 1.0, 0.0);
				renderModel(above, correction, poseStack, buffers, packedLight, packedOverlay);
				poseStack.popPose();
			}
		}
	}

	private static void renderModel(BlockState state, float yawDegrees, PoseStack poseStack,
									MultiBufferSource buffers, int packedLight,
									int packedOverlay) {
		Minecraft minecraft = Minecraft.getInstance();
		BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
		BakedModel model = dispatcher.getBlockModel(state);
		RenderType renderType = ItemBlockRenderTypes.getChunkRenderType(state);
		if (renderType == null) {
			renderType = RenderType.solid();
		}
		VertexConsumer consumer = buffers.getBuffer(renderType);
		poseStack.pushPose();
		poseStack.translate(0.5, 0.0, 0.5);
		poseStack.mulPose(Axis.YP.rotationDegrees(yawDegrees));
		poseStack.translate(-0.5, 0.0, -0.5);
		dispatcher.getModelRenderer().renderModel(poseStack.last(), consumer, state, model,
			1.0f, 1.0f, 1.0f, packedLight, packedOverlay);
		poseStack.popPose();
	}

	private static float yawForFacing(Direction direction) {
		return switch (direction) {
			case NORTH -> 0.0f;
			case WEST -> 90.0f;
			case SOUTH -> 180.0f;
			case EAST -> 270.0f;
			default -> 0.0f;
		};
	}
}
