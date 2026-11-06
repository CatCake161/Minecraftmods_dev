package com.example.cinema.network;

import com.example.cinema.Cinema;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Server-to-client list of cinema devices found around the player. */
public record RemoteScanListPayload(List<Entry> entries) implements CustomPacketPayload {
	private static final int MAX_ENTRIES = 128;

	public static final Type<RemoteScanListPayload> TYPE =
		new Type<>(Cinema.id("remote_scan_list"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemoteScanListPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemoteScanListPayload decode(RegistryFriendlyByteBuf buffer) {
				int count = Math.min(buffer.readVarInt(), MAX_ENTRIES);
				List<Entry> entries = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					Kind kind = Kind.byOrdinal(buffer.readVarInt());
					BlockPos pos = buffer.readBlockPos();
					String name = buffer.readUtf(64);
					double distance = buffer.readDouble();
					entries.add(new Entry(kind, pos, name, distance));
				}
				return new RemoteScanListPayload(entries);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemoteScanListPayload payload) {
				List<Entry> entries = payload.entries();
				int count = Math.min(entries.size(), MAX_ENTRIES);
				buffer.writeVarInt(count);
				for (int i = 0; i < count; i++) {
					Entry entry = entries.get(i);
					buffer.writeVarInt(entry.kind().ordinal());
					buffer.writeBlockPos(entry.pos());
					buffer.writeUtf(entry.name(), 64);
					buffer.writeDouble(entry.distance());
				}
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public record Entry(Kind kind, BlockPos pos, String name, double distance) {
	}

	public enum Kind {
		RECEIVER,
		PROJECTOR,
		SPEAKER;

		public static Kind byOrdinal(int ordinal) {
			Kind[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : RECEIVER;
		}
	}
}
