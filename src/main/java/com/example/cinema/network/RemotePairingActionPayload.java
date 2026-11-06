package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.item.RemoteBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Requests a link or unlink between the anchor device and one nearby target.
 */
public record RemotePairingActionPayload(BlockPos anchorPos, RemoteBinding.Kind anchorKind,
										 RemotePairingListPayload.TargetKind targetKind,
										 BlockPos targetPos, boolean link)
	implements CustomPacketPayload {
	public static final Type<RemotePairingActionPayload> TYPE =
		new Type<>(Cinema.id("remote_pairing_action"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemotePairingActionPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemotePairingActionPayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos anchor = buffer.readBlockPos();
				RemoteBinding.Kind anchorKind = RemoteBinding.Kind.byId(buffer.readUtf(16));
				RemotePairingListPayload.TargetKind targetKind =
					RemotePairingListPayload.TargetKind.byOrdinal(buffer.readVarInt());
				BlockPos target = buffer.readBlockPos();
				boolean link = buffer.readBoolean();
				return new RemotePairingActionPayload(anchor,
					anchorKind == null ? RemoteBinding.Kind.RECEIVER : anchorKind,
					targetKind, target, link);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemotePairingActionPayload payload) {
				buffer.writeBlockPos(payload.anchorPos());
				buffer.writeUtf(payload.anchorKind().id(), 16);
				buffer.writeVarInt(payload.targetKind().ordinal());
				buffer.writeBlockPos(payload.targetPos());
				buffer.writeBoolean(payload.link());
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
