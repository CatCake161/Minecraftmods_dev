package com.example.cinema.network;

import com.example.cinema.Cinema;
import com.example.cinema.util.CinemaNames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Renames a projector, receiver, or the screen linked to a projector.
 * For screens, {@code anchorPos} is the projector position so the server can
 * validate the operator at the projector and then update the linked screen.
 */
public record DeviceNamePayload(BlockPos anchorPos, Target target, String name)
	implements CustomPacketPayload {
	public static final Type<DeviceNamePayload> TYPE =
		new Type<>(Cinema.id("device_name"));

	public static final StreamCodec<RegistryFriendlyByteBuf, DeviceNamePayload> CODEC =
		new StreamCodec<>() {
			@Override
			public DeviceNamePayload decode(RegistryFriendlyByteBuf buffer) {
				BlockPos anchor = buffer.readBlockPos();
				Target target = Target.byOrdinal(buffer.readVarInt());
				return new DeviceNamePayload(anchor, target,
					buffer.readUtf(CinemaNames.MAX_LENGTH));
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, DeviceNamePayload payload) {
				buffer.writeBlockPos(payload.anchorPos());
				buffer.writeVarInt(payload.target().ordinal());
				buffer.writeUtf(payload.name(), CinemaNames.MAX_LENGTH);
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	public enum Target {
		PROJECTOR,
		RECEIVER,
		SCREEN;

		public static Target byOrdinal(int ordinal) {
			Target[] values = values();
			return ordinal >= 0 && ordinal < values.length ? values[ordinal] : PROJECTOR;
		}
	}
}
