package com.example.cinema.client.command;

import com.example.cinema.block.entity.SpeakerType;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityProfile;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityRegistry;
import com.example.cinema.client.gui.SpeakerDirectivityConfigScreen;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Client-only commands for the speaker directivity spike. */
public final class ClientDirectivityCommand {
	private ClientDirectivityCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_directivity")
			.executes(context -> {
				Minecraft.getInstance().setScreen(new SpeakerDirectivityConfigScreen(null));
				return 1;
			})
			.then(ClientCommandManager.literal("reload")
				.executes(context -> {
					SpeakerDirectivityRegistry.ReloadResult result =
						SpeakerDirectivityRegistry.reloadProfiles();
					context.getSource().sendFeedback(Component.literal(
						"directivity: " + result.profileCount() + " profile(s) loaded"));
					for (String error : result.errors()) {
						context.getSource().sendError(Component.literal(error));
					}
					return 1;
				}))
			.then(ClientCommandManager.literal("info")
				.executes(context -> {
					SpeakerDirectivityRegistry.load();
					for (SpeakerType type : SpeakerType.values()) {
						SpeakerDirectivityRegistry.SpeakerSettings settings =
							SpeakerDirectivityRegistry.settings(type);
						if (settings == null) {
							continue;
						}
						SpeakerDirectivityProfile profile =
							SpeakerDirectivityRegistry.find(type, settings.profileId());
						if (profile == null) {
							continue;
						}
						context.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
							"%s: %s | 1 kHz 0° %+.1f / 90° %+.1f / 180° %+.1f dB | range %.0f-%.0f Hz | SPL gain %+.1f dB",
							type.id(), profile.name(),
							profile.gainDb(0, 0, 1000),
							profile.gainDb(90, 0, 1000),
							profile.gainDb(180, 0, 1000),
							settings.lowHz(), settings.highHz(), settings.gainDb())));
					}
					return 1;
				}))
			.then(ClientCommandManager.literal("test")
				.then(ClientCommandManager.argument("type", StringArgumentType.word())
					.suggests((context, builder) -> {
						for (SpeakerType type : SpeakerType.values()) {
							builder.suggest(type.id());
						}
						return builder.buildFuture();
					})
					.then(ClientCommandManager.argument("horizontal", DoubleArgumentType.doubleArg(-180.0, 180.0))
						.then(ClientCommandManager.argument("vertical", DoubleArgumentType.doubleArg(-90.0, 90.0))
							.then(ClientCommandManager.argument("hz", DoubleArgumentType.doubleArg(1.0, 24_000.0))
								.executes(context -> {
									SpeakerType type = typeById(
										StringArgumentType.getString(context, "type"));
									if (type == null) {
										context.getSource().sendError(Component.literal(
											"unknown speaker type"));
										return 0;
									}
									SpeakerDirectivityRegistry.SpeakerSettings settings =
										SpeakerDirectivityRegistry.settings(type);
									SpeakerDirectivityProfile profile = settings == null ? null
										: SpeakerDirectivityRegistry.find(type, settings.profileId());
									if (profile == null) {
										context.getSource().sendError(Component.literal(
											"no directivity profile selected"));
										return 0;
									}
									double horizontal = DoubleArgumentType.getDouble(context, "horizontal");
									double vertical = DoubleArgumentType.getDouble(context, "vertical");
									double hz = DoubleArgumentType.getDouble(context, "hz");
									context.getSource().sendFeedback(Component.literal(String.format(Locale.ROOT,
										"%s / %s: h=%.1f° v=%.1f° f=%.1f Hz -> %+.2f dB",
										type.id(), profile.name(), horizontal, vertical, hz,
										profile.gainDb(horizontal, vertical, hz))));
									return 1;
								}))))))
		);
	}

	private static SpeakerType typeById(String id) {
		for (SpeakerType type : SpeakerType.values()) {
			if (type.id().equalsIgnoreCase(id)) {
				return type;
			}
		}
		return null;
	}
}
