package com.example.firstmod.network;

import com.example.firstmod.FirstMod;
import com.example.firstmod.media.MediaKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client-to-server control message for one AV receiver.
 *
 * <p>The server owns playback intent and media identity. The client only sends
 * a validated library key plus the local file metadata; the server never reads
 * client files.
 */
public record ReceiverControlPayload(BlockPos receiverPos, Action action, String mediaKey,
									 long mediaSize, String mediaSha256, double durationSeconds,
									 double value) implements CustomPacketPayload {
	public static final Type<ReceiverControlPayload> TYPE =
		new Type<>(FirstMod.id("receiver_control"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ReceiverControlPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public ReceiverControlPayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos receiverPos = buffer.readBlockPos();
				Action action = Action.byOrdinal(buffer.readVarInt());
				String mediaKey = buffer.readUtf(MediaKey.MAX_LENGTH);
				long mediaSize = buffer.readVarLong();
				String mediaSha256 = buffer.readUtf(128);
				double durationSeconds = buffer.readDouble();
				double value = buffer.readDouble();
				return new ReceiverControlPayload(receiverPos, action, mediaKey, mediaSize,
					mediaSha256, durationSeconds, value);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, ReceiverControlPayload payload) {
				buffer.writeBlockPos(payload.receiverPos());
				buffer.writeVarInt(payload.action().ordinal());
				buffer.writeUtf(payload.mediaKey(), MediaKey.MAX_LENGTH);
				buffer.writeVarLong(payload.mediaSize());
				buffer.writeUtf(payload.mediaSha256(), 128);
				buffer.writeDouble(payload.durationSeconds());
				buffer.writeDouble(payload.value());
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Action {
		SELECT_MEDIA,
		PLAY,
		PAUSE,
		STOP,
		SEEK,
		SET_VOLUME,
		SET_MUTED,
		LINK_PROJECTOR;

		public static Action byOrdinal(int ordinal) {
			Action[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : STOP;
		}
	}
}
