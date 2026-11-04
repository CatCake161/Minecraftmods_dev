package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-to-server request to rename one physical speaker. */
public record SpeakerNamePayload(BlockPos speakerPos, String name) implements CustomPacketPayload {
	public static final Type<SpeakerNamePayload> TYPE =
		new Type<>(Cinema.id("speaker_name"));

	public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerNamePayload> CODEC =
		new StreamCodec<>() {
			@Override
			public SpeakerNamePayload decode(RegistryFriendlyByteBuf buffer) {
				return new SpeakerNamePayload(buffer.readBlockPos(),
					buffer.readUtf(SpeakerBlockEntity.MAX_NAME_LENGTH));
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, SpeakerNamePayload payload) {
				buffer.writeBlockPos(payload.speakerPos());
				buffer.writeUtf(payload.name(), SpeakerBlockEntity.MAX_NAME_LENGTH);
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
