package com.example.cinema.client.command;

import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.AcousticRoom;
import com.example.cinema.client.audio.acoustics.AcousticScene;
import com.example.cinema.client.audio.acoustics.AcousticSpeaker;
import com.example.cinema.client.audio.acoustics.AcousticSurface;
import com.example.cinema.client.audio.acoustics.OcclusionResult;
import com.example.cinema.client.audio.acoustics.OcclusionScanner;
import com.example.cinema.client.audio.acoustics.RoomAcousticSnapshot;
import com.example.cinema.client.audio.acoustics.RoomFill;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityService;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.environment.EnvironmentFirService;
import com.example.cinema.client.audio.environment.EnvironmentProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.config.CinemaClientConfig;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

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
		CinemaClientConfig config = CinemaClientConfig.get();
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: air=%s T=%.1f C RH=%.0f %% p=%.3f kPa earlyGain=%+.1f dB (debug)",
			config.airAbsorption() ? "ON" : "OFF", config.airTemperatureC(),
			config.airHumidityPercent(), AirAbsorption.REFERENCE_PRESSURE_KPA,
			config.earlyGainDb())));
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
			SpeakerDataProfile profile = SpeakerDataRegistry.find(speaker.type(),
				speaker.profileId());
			SpeakerDirectivityService.Angles angles = SpeakerDirectivityService.angles(
				speaker.position(), speaker.forward(), snapshot.listener().eye());
			MinimumPhaseFir filter = profile == null ? null
				: SpeakerDirectivityService.get(profile, angles.horizontal(),
				angles.vertical());
			String state = profile == null ? "none"
				: filter == null ? "pending" : filter.isFlat() ? "flat" : "ready";
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: directivity %s h=%.1f v=%.1f eq=%s taps=%s maxErr=%s offset=%s",
				speaker.role().label(), angles.horizontal(), angles.vertical(), state,
				filter == null ? "-" : Integer.toString(filter.length()),
				filter == null ? "-" : String.format(Locale.ROOT, "%.2f dB",
					filter.maxErrorDb()),
				filter == null ? "-" : String.format(Locale.ROOT, "%+.1f dB",
					filter.offsetDb()))));
			double distance = speaker.position().distanceTo(snapshot.listener().eye());
			OcclusionResult occlusion = scanOcclusion(speaker,
				snapshot.listener().eye());
			EnvironmentProfile environmentProfile = new EnvironmentProfile(distance,
				config.airAbsorption(), config.airTemperatureC(),
				config.airHumidityPercent(), AirAbsorption.REFERENCE_PRESSURE_KPA,
				occlusion.cutoffHz(), occlusion.order());
			MinimumPhaseFir environment = EnvironmentFirService.get(environmentProfile);
			String environmentState = environmentProfile.isFlat() ? "flat"
				: environment == null ? "pending"
				: environment.isFlat() ? "flat" : "ready";
			double distanceGainDb = -20.0 * Math.log10(Math.max(1.0, distance));
			double air8kDb = config.airAbsorption()
				? AirAbsorption.directPathAttenuationDb(distance, 8_000.0,
					config.airTemperatureC(), config.airHumidityPercent(),
					AirAbsorption.REFERENCE_PRESSURE_KPA)
				: 0.0;
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: path %s dist=%.2f m distanceGain=%+.1f dB air8k=%.2f dB "
					+ "occlusion=%.1f dB cutoff=%.0f Hz order=%d env=%s taps=%s",
				speaker.role().label(), distance, distanceGainDb, air8kDb,
				occlusion.attenuationDb(), occlusion.cutoffHz(), occlusion.order(),
				environmentState,
				environment == null ? "-" : Integer.toString(environment.length()))));
		}
		return 1;
	}

	private static OcclusionResult scanOcclusion(AcousticSpeaker speaker, Vec3 listener) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return OcclusionResult.NONE;
		}
		return OcclusionScanner.scan(level, speaker.position(), listener);
	}
}
