package com.example.cinema.network;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server request to change one linked speaker's channel level.
 * The receiver position is part of the request so the server can verify that
 * the player is operating the receiver that owns the speaker.
 */
public record SpeakerLevelPayload(BlockPos receiverPos, BlockPos speakerPos, double levelDb)
	implements CustomPacketPayload {
	public static final Type<SpeakerLevelPayload> TYPE =
		new Type<>(Cinema.id("speaker_level"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerLevelPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public SpeakerLevelPayload decode(RegistryFriendlyByteBuf buffer) {
				return new SpeakerLevelPayload(buffer.readBlockPos(), buffer.readBlockPos(),
					buffer.readDouble());
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, SpeakerLevelPayload payload) {
				buffer.writeBlockPos(payload.receiverPos());
				buffer.writeBlockPos(payload.speakerPos());
				buffer.writeDouble(payload.levelDb());
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
