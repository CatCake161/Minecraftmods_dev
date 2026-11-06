package com.example.cinema.network;

import com.example.cinema.Cinema;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Requests a scan of nearby receiver/projector/speaker devices. */
public record RemoteScanRequestPayload() implements CustomPacketPayload {
	public static final Type<RemoteScanRequestPayload> TYPE =
		new Type<>(Cinema.id("remote_scan_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, RemoteScanRequestPayload> CODEC =
		new StreamCodec<>() {
			@Override
			public RemoteScanRequestPayload decode(RegistryFriendlyByteBuf buffer) {
				return new RemoteScanRequestPayload();
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, RemoteScanRequestPayload payload) {
			}
		};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
