package com.example.cinema.client.command;

import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.client.audio.acoustics.AcousticMaterial;
import com.example.cinema.client.audio.acoustics.AcousticRoom;
import com.example.cinema.client.audio.acoustics.AcousticScene;
import com.example.cinema.client.audio.acoustics.AcousticSpeaker;
import com.example.cinema.client.audio.acoustics.AcousticSurface;
import com.example.cinema.client.audio.acoustics.AxisAbsorption;
import com.example.cinema.client.audio.acoustics.ImageSourcePathSolver;
import com.example.cinema.client.audio.acoustics.ImageSourceSolver;
import com.example.cinema.client.audio.acoustics.MirrorPlane;
import com.example.cinema.client.audio.acoustics.MirrorPlaneDiscovery;
import com.example.cinema.client.audio.acoustics.OcclusionResult;
import com.example.cinema.client.audio.acoustics.OcclusionScanner;
import com.example.cinema.client.audio.acoustics.Point3;
import com.example.cinema.client.audio.acoustics.ReflectionPathTap;
import com.example.cinema.client.audio.acoustics.ReflectionProbe;
import com.example.cinema.client.audio.acoustics.ReflectionTap;
import com.example.cinema.client.audio.acoustics.RoomAcousticSnapshot;
import com.example.cinema.client.audio.acoustics.RoomFill;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityService;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.fir.CommonAcousticFirDesigner;
import com.example.cinema.client.audio.fir.CommonAcousticFirProfile;
import com.example.cinema.client.audio.fir.CommonAcousticFirService;
import com.example.cinema.client.audio.reverb.LateReverbSettings;
import com.example.cinema.client.audio.reverb.LateReverbTarget;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.config.CinemaClientConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A1 debug command: dump the client-side room/material/speaker snapshot. */
public final class ClientAcousticsCommand {
	private static final int PATH_STAT_MAX = 256;

	private ClientAcousticsCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("cinema_acoustics")
			.executes(context -> dump(context.getSource(),
				AcousticScene.refresh(Minecraft.getInstance())))
			.then(ClientCommandManager.literal("paths")
				.executes(context -> dumpPaths(context.getSource(), 3))
				.then(ClientCommandManager.argument("order",
						IntegerArgumentType.integer(2, 5))
					.executes(context -> dumpPaths(context.getSource(),
						IntegerArgumentType.getInteger(context, "order"))))));
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
		LateReverbSettings late = lateSettings(snapshot, config);
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: late reverb=%s target=%+.1f dB sourceGain=%+.1f dB "
				+ "trim=%+.1f dB effective=%+.1f dB wetDry=%+.1f dB "
				+ "R=%.1f m2 rc=%.2f m",
			late.enabled() ? "ON" : "OFF", late.targetWetGainDb(),
			late.sourceGainDb(), late.trimDb(), late.wetGainDb(),
			late.targetWetGainDb() + late.trimDb(),
			late.roomConstant(), late.criticalDistanceMeters())));
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: late rt60L/M/H=%.2f/%.2f/%.2f s dcHighPass=%.0f Hz "
				+ "preDelay=%.0f ms",
			late.rt60LowSeconds(), late.rt60MidSeconds(),
			late.rt60HighSeconds(), late.highPassHz(),
			late.preDelaySeconds() * 1_000.0)));
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
				"acoustics: fill volume=%.1f m3 surface=%.1f m2 open=%.1f m2 cells=%d faces=%s coverage=%.2f patches=%d",
				fill.volume(), fill.surfaceArea(), fill.openArea(),
				fill.cells(), fill.openFacesLabel(), room.fillCoverage(),
				fill.reflectionSurfaceCount())));
		}
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: alpha L/M/H=%.3f/%.3f/%.3f RT60 L/M/H=%s/%s/%s s (Fitzroy) fs=%.0f Hz source=%s",
			room.alphaLow(), room.alphaMid(), room.alphaHigh(),
			rt60(room.rt60Low()), rt60(room.rt60Mid()), rt60(room.rt60High()),
			room.schroederHz(),
			room.fillComplete() ? "fill" : "box")));
		AxisAbsorption xAxis = room.xAxis();
		AxisAbsorption yAxis = room.yAxis();
		AxisAbsorption zAxis = room.zAxis();
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: fitzroy axisArea X/Y/Z=%.1f/%.1f/%.1f m2",
			xAxis.area(), yAxis.area(), zAxis.area())));
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: fitzroy alpha X=%.3f/%.3f/%.3f Y=%.3f/%.3f/%.3f Z=%.3f/%.3f/%.3f",
			xAxis.alphaLow(), xAxis.alphaMid(), xAxis.alphaHigh(),
			yAxis.alphaLow(), yAxis.alphaMid(), yAxis.alphaHigh(),
			zAxis.alphaLow(), zAxis.alphaMid(), zAxis.alphaHigh())));
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
		int patchShown = 0;
		for (AcousticSurface surface : room.reflectionSurfaces()) {
			if (patchShown++ >= 8) {
				break;
			}
			AcousticMaterial material = surface.material();
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: patch %s @ %.2f area=%.1f material=%s samples=%d",
				surface.label(), surface.coordinate(), surface.area(),
				material.name(), surface.samples())));
		}
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: reflection seeds=%d (showing %d)",
			room.reflectionSurfaces().size(), Math.min(8, patchShown))));
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
			double distance = speaker.position().distanceTo(snapshot.listener().eye());
			OcclusionResult occlusion = scanOcclusion(speaker,
				snapshot.listener().eye());
			CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.direct(
				profile, angles.horizontal(), angles.vertical(), distance,
				config.airAbsorption(), config.airTemperatureC(),
				config.airHumidityPercent(), AirAbsorption.REFERENCE_PRESSURE_KPA,
				occlusion);
			MinimumPhaseFir filter = CommonAcousticFirService.get(firProfile);
			String state = filter == null ? "pending"
				: filter.isFlat() ? "flat" : "ready";
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: direct fir %s h=%.1f v=%.1f state=%s taps=%s "
					+ "maxErr=%s overall=%+.1f dB",
				speaker.role().label(), angles.horizontal(), angles.vertical(), state,
				filter == null ? "-" : Integer.toString(filter.length()),
				filter == null ? "-" : String.format(Locale.ROOT, "%.2f dB",
					filter.maxErrorDb()),
				CommonAcousticFirDesigner.overallDb(firProfile))));
			double distanceGainDb = -20.0 * Math.log10(Math.max(1.0, distance));
			double air8kDb = config.airAbsorption()
				? AirAbsorption.directPathAttenuationDb(distance, 8_000.0,
					config.airTemperatureC(), config.airHumidityPercent(),
					AirAbsorption.REFERENCE_PRESSURE_KPA)
				: 0.0;
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: path %s dist=%.2f m distanceGain=%+.1f dB air8k=%.2f dB "
					+ "occlusion=%.1f dB cutoff=%.0f Hz order=%d",
				speaker.role().label(), distance, distanceGainDb, air8kDb,
				occlusion.attenuationDb(), occlusion.cutoffHz(), occlusion.order())));
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			ReflectionProbe probe = new ReflectionProbe(level);
			List<ReflectionTap> allTaps = new ArrayList<>();
			for (AcousticSpeaker speaker : snapshot.speakers()) {
				if (speaker.role() == SpeakerRole.NONE
					|| speaker.role() == SpeakerRole.LFE) {
					continue;
				}
				List<MirrorPlane> planes = MirrorPlaneDiscovery.INSTANCE.discover(level,
					speaker.position(), room.reflectionKey(), room.reflectionSurfaces());
				int channel = speaker.role().ordinal() - 1;
				List<ReflectionTap> taps = ImageSourceSolver.solve(planes, channel,
					pointOf(speaker.position()), pointOf(snapshot.listener().eye()),
					false, probe);
				allTaps.addAll(taps);
				source.sendFeedback(Component.literal(String.format(Locale.ROOT,
					"acoustics: reflection %s planes=%d taps=%d",
					speaker.role().label(), planes.size(), taps.size())));
				SpeakerDataProfile profile = SpeakerDataRegistry.find(speaker.type(),
					speaker.profileId());
				for (ReflectionTap tap : taps) {
					source.sendFeedback(Component.literal(String.format(Locale.ROOT,
						"acoustics: tap %s %s R=(%.2f, %.2f, %.2f) L=%.2f m "
							+ "delay=%.1f ms material=%s gain=%.4f "
							+ "occ=%.1f dB cutoff=%.0f Hz order=%d",
						speaker.role().label(), tap.planeKey(),
						tap.reflectionPoint().x(), tap.reflectionPoint().y(),
						tap.reflectionPoint().z(), tap.pathLength(),
						tap.delayMilliseconds(), tap.material().name(), tap.gain(),
						tap.occlusion().attenuationDb(), tap.occlusion().cutoffHz(),
						tap.occlusion().order())));
					Vec3 reflectionPoint = new Vec3(tap.reflectionPoint().x(),
						tap.reflectionPoint().y(), tap.reflectionPoint().z());
					SpeakerDirectivityService.Angles angles =
						SpeakerDirectivityService.angles(speaker.position(),
							speaker.forward(), reflectionPoint);
					CommonAcousticFirProfile firProfile =
						CommonAcousticFirProfile.reflection(profile,
							angles.horizontal(), angles.vertical(), tap.pathLength(),
							config.airAbsorption(), config.airTemperatureC(),
							config.airHumidityPercent(),
							AirAbsorption.REFERENCE_PRESSURE_KPA,
							tap.material(), tap.occlusion());
					MinimumPhaseFir fir = CommonAcousticFirService.get(firProfile);
					String firState = fir == null ? "pending"
						: fir.isFlat() ? "flat" : "ready";
					source.sendFeedback(Component.literal(String.format(Locale.ROOT,
						"acoustics: reflection fir %s %s h=%.1f v=%.1f "
							+ "state=%s taps=%s maxErr=%s overall=%+.1f dB",
						speaker.role().label(), tap.planeKey(), angles.horizontal(),
						angles.vertical(), firState,
						fir == null ? "-" : Integer.toString(fir.length()),
						fir == null ? "-" : String.format(Locale.ROOT, "%.2f dB",
							fir.maxErrorDb()),
						CommonAcousticFirDesigner.overallDb(firProfile))));
				}
			}
			List<ReflectionTap> limited = ImageSourceSolver.strongest(allTaps,
				ImageSourceSolver.MAX_TAPS_PER_RECEIVER);
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: reflection total=%d cap=%d strongest=%d seeds=%d",
				allTaps.size(), ImageSourceSolver.MAX_TAPS_PER_RECEIVER,
				limited.size(), room.reflectionSurfaces().size())));
		}
		return 1;
	}

	/**
	 * Diagnostic-only higher-order image-source statistics. This runs on the
	 * client thread, uses the real room probe/occlusion scanner and does not
	 * touch the audio engine.
	 */
	private static int dumpPaths(FabricClientCommandSource source, int maxOrder) {
		RoomAcousticSnapshot snapshot = AcousticScene.refresh(Minecraft.getInstance());
		if (snapshot == null) {
			source.sendError(Component.literal("acoustics: no client world/player"));
			return 0;
		}
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			source.sendError(Component.literal("acoustics: no client level"));
			return 0;
		}
		AcousticRoom room = snapshot.room();
		ReflectionProbe probe = new ReflectionProbe(level);
		Point3 listener = pointOf(snapshot.listener().eye());
		int total = 0;
		long totalNanos = 0L;
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: higher-order paths maxOrder=%d capPerSpeaker=%d "
				+ "visitCap=%d (specular only, no scattering, not wired to audio)",
			maxOrder, PATH_STAT_MAX, ImageSourcePathSolver.MAX_VISITS)));
		List<AcousticSpeaker> speakers = nearestSpeakers(snapshot);
		for (AcousticSpeaker speaker : speakers) {
			if (speaker.role() == SpeakerRole.NONE
				|| speaker.role() == SpeakerRole.LFE) {
				continue;
			}
			int channel = speaker.role().ordinal() - 1;
			long start = System.nanoTime();
			List<MirrorPlane> planes = MirrorPlaneDiscovery.INSTANCE.discover(
				level, speaker.position(), room.reflectionKey(),
				room.reflectionSurfaces());
			ImageSourcePathSolver.SolveStats stats = ImageSourcePathSolver.solveWithStats(
				planes, channel, pointOf(speaker.position()), listener, false,
				probe, maxOrder, PATH_STAT_MAX);
			List<ReflectionPathTap> paths = stats.paths();
			long elapsed = System.nanoTime() - start;
			totalNanos += elapsed;
			total += paths.size();

			int[] orderCounts = new int[maxOrder + 1];
			double maxDelayMs = 0.0;
			double strongest = 0.0;
			for (ReflectionPathTap path : paths) {
				if (path.order() >= 0 && path.order() < orderCounts.length) {
					orderCounts[path.order()]++;
				}
				maxDelayMs = Math.max(maxDelayMs, path.delayMilliseconds());
				strongest = Math.max(strongest, path.gain());
			}
			StringBuilder breakdown = new StringBuilder();
			for (int order = 2; order <= maxOrder; order++) {
				if (order > 2) {
					breakdown.append('/');
				}
				breakdown.append(orderCounts[order]);
			}
			String capped = stats.truncated() ? " (truncated)" : "";
			source.sendFeedback(Component.literal(String.format(Locale.ROOT,
				"acoustics: paths %s planes=%d orders(2..%d)=%s total=%d "
					+ "maxDelay=%.1f ms strongest=%.4f visits=%d time=%.1f ms%s",
				speaker.role().label(), planes.size(), maxOrder, breakdown,
				paths.size(), maxDelayMs, strongest, stats.visits(),
				elapsed / 1.0e6, capped)));
		}
		source.sendFeedback(Component.literal(String.format(Locale.ROOT,
			"acoustics: paths total=%d speakers=%d capPerSpeaker=%d time=%.1f ms",
			total, speakers.size(), PATH_STAT_MAX, totalNanos / 1.0e6)));
		return 1;
	}

	private static List<AcousticSpeaker> nearestSpeakers(RoomAcousticSnapshot snapshot) {
		Map<SpeakerRole, AcousticSpeaker> nearest = new EnumMap<>(SpeakerRole.class);
		Vec3 eye = snapshot.listener().eye();
		for (AcousticSpeaker speaker : snapshot.speakers()) {
			if (speaker.role() == SpeakerRole.NONE
				|| speaker.role() == SpeakerRole.LFE) {
				continue;
			}
			AcousticSpeaker current = nearest.get(speaker.role());
			if (current == null || speaker.position().distanceToSqr(eye)
				< current.position().distanceToSqr(eye)) {
				nearest.put(speaker.role(), speaker);
			}
		}
		List<AcousticSpeaker> result = new ArrayList<>(nearest.values());
		result.sort(Comparator.comparingInt(speaker -> speaker.role().ordinal()));
		return result;
	}

	private static LateReverbSettings lateSettings(RoomAcousticSnapshot snapshot,
												   CinemaClientConfig config) {
		AcousticRoom room = snapshot.room();
		List<LateReverbTarget.Source> sources = new ArrayList<>();
		for (AcousticSpeaker speaker : snapshot.speakers()) {
			if (speaker.role() == SpeakerRole.NONE) {
				continue;
			}
			SpeakerDataProfile profile = SpeakerDataRegistry.find(speaker.type(),
				speaker.profileId());
			SpeakerDirectivityService.Angles angles = SpeakerDirectivityService.angles(
				speaker.position(), speaker.forward(), snapshot.listener().eye());
			double directivity = 1.0;
			double powerIntegral = 4.0 * Math.PI;
			if (profile != null) {
				directivity = Math.pow(10.0, profile.gainDb(angles.horizontal(),
					angles.vertical(), CommonAcousticFirDesigner.REFERENCE_HZ)
					/ 20.0);
				powerIntegral = profile.directivityPowerIntegral(
					CommonAcousticFirDesigner.REFERENCE_HZ);
			}
			double overallDb = profile == null ? 0.0
				: profile.onAxisEqGainDb(CommonAcousticFirDesigner.REFERENCE_HZ)
					+ profile.gainDb(angles.horizontal(), angles.vertical(),
						CommonAcousticFirDesigner.REFERENCE_HZ);
			double directGain = Math.min(2.0,
				Math.pow(10.0, speaker.levelDb() / 20.0)
					* Math.pow(10.0, overallDb / 20.0));
			double onAxisSourceGain = directGain
				/ Math.max(1.0e-4, directivity);
			double sourcePowerGain = Math.min(8.0, onAxisSourceGain
				* Math.sqrt(powerIntegral));
			double distance = speaker.position().distanceTo(
				snapshot.listener().eye());
			sources.add(new LateReverbTarget.Source(directGain,
				sourcePowerGain, distance));
		}
		LateReverbTarget.Result target = LateReverbTarget.compute(room, sources);
		return LateReverbSettings.forRoom(room, config.lateReverb(),
			target.targetWetGainDb(), target.sourceGainDb(),
			config.lateTrimDb(), target.roomConstant(),
			target.criticalDistanceMeters());
	}

	private static Point3 pointOf(Vec3 point) {
		return new Point3(point.x, point.y, point.z);
	}

	private static OcclusionResult scanOcclusion(AcousticSpeaker speaker, Vec3 listener) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return OcclusionResult.NONE;
		}
		return OcclusionScanner.scan(level, speaker.position(), listener);
	}
}
