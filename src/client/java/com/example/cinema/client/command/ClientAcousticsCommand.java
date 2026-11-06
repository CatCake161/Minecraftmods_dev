package com.example.cinema.client.command;

import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.AcousticRoom;
import com.example.cinema.client.audio.acoustics.AcousticScene;
import com.example.cinema.client.audio.acoustics.AcousticSpeaker;
import com.example.cinema.client.audio.acoustics.AcousticSurface;
import com.example.cinema.client.audio.acoustics.RoomAcousticSnapshot;
import com.example.cinema.client.audio.acoustics.RoomFill;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** A1 debug command: dump the client-side room/material/speaker snapshot. */
public final class ClientAcousticsCommand {
	private ClientAcousticsCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_acoustics")
			.executes(context -> dump(context.getSource(),
				AcousticScene.refresh(Minecraft.getInstance()))));
	}

	private static String rt60(double value) {
		return Double.isFinite(value)
			? String.format(Locale.ROOT, "%.2f", value) : "inf";
	}

	private static int dump(FabricClientCommandSource source, RoomAcousticSnapshot snapshot) {
		if (snapshot == null) {
			source.sendError(Component.literal("acoustics: no client world/player"));
			return 0;
		}
		AcousticRoom room = snapshot.room();
		RoomFill fill = room.fill();
		String fillState = fill == null ? "none"
			: fill.complete() ? "complete" : "incomplete";
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: valid=%s fallback=%s fill=%s version=%d",
			room.valid(), room.fallbackReason().isBlank() ? "-" : room.fallbackReason(),
			fillState, snapshot.version())));
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: size=%s boxVolume=%.1f m3 boxSurface=%.1f m2",
			room.sizeLabel(), room.volume(), room.surfaceArea())));
		if (fill == null) {
			source.sendFeedback(Component.literal("acoustics: fill=none"));
		} else {
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: fill volume=%.1f m3 surface=%.1f m2 open=%.1f m2 cells=%d faces=%s coverage=%.2f",
				fill.volume(), fill.surfaceArea(), fill.openArea(),
				fill.cells(), fill.openFacesLabel(), room.fillCoverage())));
		}
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: alpha L/M/H=%.3f/%.3f/%.3f RT60 L/M/H=%s/%s/%s s source=%s",
			room.alphaLow(), room.alphaMid(), room.alphaHigh(),
			rt60(room.rt60Low()), rt60(room.rt60Mid()), rt60(room.rt60High()),
			room.fillComplete() ? "fill" : "box")));
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: listener eye=(%.2f, %.2f, %.2f) look=(%.2f, %.2f, %.2f)",
			snapshot.listener().eye().x, snapshot.listener().eye().y,
			snapshot.listener().eye().z, snapshot.listener().look().x,
			snapshot.listener().look().y, snapshot.listener().look().z)));
		for (AcousticSurface surface : room.surfaces()) {
			AcousticMaterial material = surface.material();
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: plane %s @ %.2f area=%.1f material=%s alpha=%.2f/%.2f/%.2f samples=%d",
				surface.label(), surface.coordinate(), surface.area(), material.name(),
				material.low(), material.mid(), material.high(), surface.samples())));
		}
		source.sendFeedback(Component.literal(
			"acoustics: speakers=" + snapshot.speakers().size()));
		for (AcousticSpeaker speaker : snapshot.speakers()) {
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: %s %s pos=(%.2f, %.2f, %.2f) yaw=%.1f forward=(%.2f, %.2f) level=%+.1f dB profile=%s",
				speaker.role().label(), speaker.type().id(),
				speaker.position().x, speaker.position().y, speaker.position().z,
				speaker.yawDegrees(), speaker.forward().x, speaker.forward().z,
				speaker.levelDb(),
				speaker.profileId().isBlank() ? "-" : speaker.profileId())));
		}
		return 1;
	}
}
