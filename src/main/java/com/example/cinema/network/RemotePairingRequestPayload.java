package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.item.RemoteBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Requests the nearby compatible devices for remote pairing around one anchor.
 */
public record RemotePairingRequestPayload(BlockPos anchorPos, RemoteBinding.Kind anchorKind)
	implements CustomPacketPayload {
	public static final Type<RemotePairingRequestPayload> TYPE =
		new Type<>(Cinema.id("remote_pairing_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemotePairingRequestPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemotePairingRequestPayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos anchor = buffer.readBlockPos();
				RemoteBinding.Kind kind = RemoteBinding.Kind.byId(buffer.readUtf(16));
				return new RemotePairingRequestPayload(anchor,
					kind == null ? RemoteBinding.Kind.RECEIVER : kind);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemotePairingRequestPayload payload) {
				buffer.writeBlockPos(payload.anchorPos());
				buffer.writeUtf(payload.anchorKind().id(), 16);
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
