package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.item.RemoteBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-to-client snapshot of devices that can be paired with the anchor.
 */
public record RemotePairingListPayload(BlockPos anchorPos, RemoteBinding.Kind anchorKind,
									   List<Entry> entries)
	implements CustomPacketPayload {
	private static final int MAX_ENTRIES = 128;

	public static final Type<RemotePairingListPayload> TYPE =
		new Type<>(Cinema.id("remote_pairing_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemotePairingListPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemotePairingListPayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos anchor = buffer.readBlockPos();
				RemoteBinding.Kind anchorKind = RemoteBinding.Kind.byId(buffer.readUtf(16));
				int count = Math.min(buffer.readVarInt(), MAX_ENTRIES);
				List<Entry> entries = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					TargetKind kind = TargetKind.byOrdinal(buffer.readVarInt());
					BlockPos pos = buffer.readBlockPos();
					String name = buffer.readUtf(64);
					boolean linked = buffer.readBoolean();
					double distance = buffer.readDouble();
					entries.add(new Entry(kind, pos, name, linked, distance));
				}
				return new RemotePairingListPayload(anchor,
					anchorKind == null ? RemoteBinding.Kind.RECEIVER : anchorKind, entries);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemotePairingListPayload payload) {
				buffer.writeBlockPos(payload.anchorPos());
				buffer.writeUtf(payload.anchorKind().id(), 16);
				List<Entry> entries = payload.entries();
				int count = Math.min(entries.size(), MAX_ENTRIES);
				buffer.writeVarInt(count);
				for (int i = 0; i < count; i++) {
					Entry entry = entries.get(i);
					buffer.writeVarInt(entry.kind().ordinal());
					buffer.writeBlockPos(entry.pos());
					buffer.writeUtf(entry.name(), 64);
					buffer.writeBoolean(entry.linked());
					buffer.writeDouble(entry.distance());
				}
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record Entry(TargetKind kind, BlockPos pos, String name, boolean linked,
						double distance) {
	}

	public enum TargetKind {
		PROJECTOR,
		SPEAKER,
		SCREEN;

		public static TargetKind byOrdinal(int ordinal) {
			TargetKind[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PROJECTOR;
		}
	}
}
