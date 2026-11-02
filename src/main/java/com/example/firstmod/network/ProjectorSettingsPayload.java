package com.example.firstmod.network;

import com.example.firstmod.FirstMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server full projector settings snapshot. The GUI sends this on
 * change (button immediately, sliders debounced); the server validates the
 * values and the block before applying them.
 */
public record ProjectorSettingsPayload(BlockPos projectorPos, double throwRatio,
									   double focusDistance, double keystoneVertical,
									   double keystoneHorizontal, double brightness,
									   double ambientWash, boolean powered,
									   boolean simFocus, boolean simKeystone,
									   boolean simWash, boolean simWarmup)
	implements CustomPacketPayload {
	public static final Type<ProjectorSettingsPayload> TYPE =
		new Type<>(FirstMod.id("projector_settings"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ProjectorSettingsPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public ProjectorSettingsPayload decode(RegistryFriendlyByteBuf buffer) {
				return new ProjectorSettingsPayload(buffer.readBlockPos(),
					buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
					buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
					buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean(),
					buffer.readBoolean(), buffer.readBoolean());
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, ProjectorSettingsPayload payload) {
				buffer.writeBlockPos(payload.projectorPos());
				buffer.writeDouble(payload.throwRatio());
				buffer.writeDouble(payload.focusDistance());
				buffer.writeDouble(payload.keystoneVertical());
				buffer.writeDouble(payload.keystoneHorizontal());
				buffer.writeDouble(payload.brightness());
				buffer.writeDouble(payload.ambientWash());
				buffer.writeBoolean(payload.powered());
				buffer.writeBoolean(payload.simFocus());
				buffer.writeBoolean(payload.simKeystone());
				buffer.writeBoolean(payload.simWash());
				buffer.writeBoolean(payload.simWarmup());
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
