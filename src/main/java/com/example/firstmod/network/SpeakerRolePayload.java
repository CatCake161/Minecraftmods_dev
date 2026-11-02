package com.example.firstmod.network;

import com.example.firstmod.FirstMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-to-server request to change one speaker's channel role. */
public record SpeakerRolePayload(BlockPos speakerPos, String role) implements CustomPacketPayload {
	public static final Type<SpeakerRolePayload> TYPE =
		new Type<>(FirstMod.id("speaker_role"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerRolePayload> CODEC =
		new StreamCodec<>() {
			@Override
			public SpeakerRolePayload decode(RegistryFriendlyByteBuf buffer) {
				return new SpeakerRolePayload(buffer.readBlockPos(), buffer.readUtf(16));
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, SpeakerRolePayload payload) {
				buffer.writeBlockPos(payload.speakerPos());
				buffer.writeUtf(payload.role(), 16);
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
