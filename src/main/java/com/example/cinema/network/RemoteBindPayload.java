package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.item.RemoteBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

/**
 * Client-to-server request to bind or unbind a cinema remote.
 *
 * <p>Binding is server-authoritative and stored on the held item stack. The
 * client only supplies the clicked device; the server validates distance and
 * block type before writing the binding.
 */
public record RemoteBindPayload(BlockPos pos, RemoteBinding.Kind kind,
								InteractionHand hand, Action action)
	implements CustomPacketPayload {
	public static final Type<RemoteBindPayload> TYPE =
		new Type<>(Cinema.id("remote_bind"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemoteBindPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemoteBindPayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos pos = buffer.readBlockPos();
				RemoteBinding.Kind kind = RemoteBinding.Kind.byId(buffer.readUtf(16));
				int handOrdinal = buffer.readVarInt();
				InteractionHand hand = handOrdinal == InteractionHand.OFF_HAND.ordinal()
					? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
				Action action = Action.byOrdinal(buffer.readVarInt());
				return new RemoteBindPayload(pos, kind == null ? RemoteBinding.Kind.PROJECTOR : kind,
					hand, action);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemoteBindPayload payload) {
				buffer.writeBlockPos(payload.pos());
				buffer.writeUtf(payload.kind().id(), 16);
				buffer.writeVarInt(payload.hand().ordinal());
				buffer.writeVarInt(payload.action().ordinal());
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Action {
		BIND,
		UNBIND;

		public static Action byOrdinal(int ordinal) {
			Action[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BIND;
		}
	}
}
