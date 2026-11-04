package com.example.cinema.client.render;

import com.example.cinema.entity.SeatEntity;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/** The seat vehicle is invisible; the seat's block model does all rendering. */
public final class SeatEntityRenderer extends EntityRenderer<SeatEntity> {
	private static final ResourceLocation TEXTURE =
		ResourceLocation.withDefaultNamespace("textures/misc/white.png");

	public SeatEntityRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public ResourceLocation getTextureLocation(SeatEntity entity) {
		return TEXTURE;
	}
}
