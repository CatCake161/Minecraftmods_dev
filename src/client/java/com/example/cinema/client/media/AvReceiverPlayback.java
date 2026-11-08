package com.example.cinema.client.media;

import com.example.cinema.Cinema;
import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.block.entity.SpeakerRole;
import com.example.cinema.client.audio.acoustics.AcousticScene;
import com.example.cinema.client.audio.acoustics.AcousticRoom;
import com.example.cinema.client.audio.acoustics.AcousticSurface;
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
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityService;
import com.example.cinema.client.audio.environment.AirAbsorption;
import com.example.cinema.client.audio.fir.CommonAcousticFirDesigner;
import com.example.cinema.client.audio.fir.CommonAcousticFirProfile;
import com.example.cinema.client.audio.fir.CommonAcousticFirService;
import com.example.cinema.client.audio.reverb.LateReflectionBus;
import com.example.cinema.client.audio.reverb.LateReverbSettings;
import com.example.cinema.client.audio.reverb.LateReverbTarget;
import com.example.cinema.client.audio.speakerdata.SpeakerDataAssignments;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import com.example.cinema.client.audio.speakerdata.SpeakerDataRegistry;
import com.example.cinema.client.config.CinemaClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side playback proxy for one AV receiver.
 *
 * <p>The receiver block entity is authoritative for intent, position, volume
 * and mute. This proxy only reacts to the synced state, opens a local decoder
 * for the receiver's media key, and keeps the local player near the expected
 * position.
 */
public final class AvReceiverPlayback {
	/** Time after a seek during which another seek is merged or rejected. */
	public static final long SEEK_COOLDOWN_MILLIS = 3_000L;
	private static final double MAX_DRIFT_SECONDS = 0.75;
	private static final long FAILURE_RETRY_MILLIS = 5_000L;
	private static final long MISSING_LOG_INTERVAL_MILLIS = 5_000L;
	/** Reflection discovery/validation is much heavier than the direct layout. */
	private static final long REFLECTION_UPDATE_MILLIS = 200L;
	/**
	 * Keep the active tap count below the engine pool so appearing taps always
	 * have a spare source to fade in.
	 */
	private static final int MAX_FIRST_ORDER_REFLECTIONS = 24;
	private static final int MAX_PREMIUM_ORDER2_REFLECTIONS = 16;
	private static final int MAX_PREMIUM_ORDER3_REFLECTIONS = 8;
	private static final double MAX_PREMIUM_DELAY_SECONDS = 0.150;
	private static final int MAX_FAST_REFLECTION_PATHS = 512;
	private static final int MAX_DEEP_REFLECTION_PATHS = 512;
	private static final int LATE_PATH_FAST_ORDER = 3;
	private static final int LATE_PATH_DEEP_ORDER = 4;
	private static final long LATE_PATH_STEP_BUDGET_NANOS = 4_000_000L;
	private static final long LATE_PATH_MIN_STABLE_MILLIS = 150L;
	private static final long LATE_PATH_MAX_STABLE_MILLIS = 600L;
	private static final double LATE_PATH_STABLE_MS_PER_SPEED = 1_200.0;
	private static final double LATE_PATH_MOVE_THRESHOLD_SQR = 0.15 * 0.15;
	private static final double REFLECTION_MOVE_THRESHOLD_SQR = 0.5 * 0.5;
	private static final long LATE_REVERB_UPDATE_MILLIS = 50L;
	private static final double MAX_CHANNEL_GAIN = 2.0;
	private static final double MAX_LATE_SEND_GAIN = 8.0;

	private final BlockPos receiverPos;
	private final ResourceLocation textureId;
	private final CinemaVideoTexture texture;
	private CinemaAudioEngine audio;
	private boolean textureRegistered;
	private CinemaPlayer player;
	private String mediaKey = "";
	private long mediaSize = -1L;
	private String mediaSha256 = "";
	private String failedKey = "";
	private long failedAtMillis;
	private long openedAtMillis;
	private long lastMissingLogMillis;
	private double clockOffset;
	private boolean clockAligned;
	private double lastExpectedSeconds = Double.NaN;
	private long seekGraceUntilMillis;
	private long seekCooldownUntilMillis;
	private double pendingSeekSeconds = Double.NaN;
	private boolean missing;
	private int physicalChannels;
	private final MinimumPhaseFir[] channelFilters =
		new MinimumPhaseFir[CinemaAudioEngine.CHANNELS];
	private final MinimumPhaseFir[] channelEnvironmentFilters =
		new MinimumPhaseFir[CinemaAudioEngine.CHANNELS];
	private long lastReflectionUpdateMillis;
	private long lastLateReverbUpdateMillis;
	private long lastLateReverbLogMillis;
	private long lastReflectionRoomVersion = Long.MIN_VALUE;
	private Vec3 lastReflectionListener;
	private final Map<String, MinimumPhaseFir> reflectionFilters = new HashMap<>();
	private int lastReflectionLogCount = -1;
	private long lastReflectionLogMillis;
	private final LatePathRequest[] latePathRequests =
		new LatePathRequest[CinemaAudioEngine.CHANNELS];
	private final List<LatePathCandidate> latePathCandidates = new ArrayList<>();
	private int latePathFastNextChannel;
	private ImageSourcePathSolver.Session latePathFastSession;
	private LatePathRequest latePathFastRequest;
	private List<LateReflectionBus.Tap> latePathFastTaps = List.of();
	private final List<LateReflectionBus.Tap> latePathDeepTaps = new ArrayList<>();
	private int latePathDeepNextChannel;
	private ImageSourcePathSolver.Session latePathDeepSession;
	private LatePathRequest latePathDeepRequest;
	private List<LateReflectionBus.Tap> currentLateTaps = List.of();
	private List<ReflectionSource> premiumOrder2Sources = List.of();
	private List<ReflectionSource> premiumOrder3Sources = List.of();
	private final Map<String, MinimumPhaseFir> premiumReflectionFilters = new HashMap<>();
	private Vec3 lastLatePathListener;
	private Vec3 lastLatePathSample;
	private long lastLatePathSampleMillis;
	private long lastLatePathMoveMillis;
	private long latePathRequestRoomVersion = Long.MIN_VALUE;
	private boolean latePathBuildActive;
	private boolean latePathFastApplied;
	private boolean latePathDeepApplied;
	private long latePathBuildStartMillis;
	private long latePathBuildNanos;
	private long latePathDeepStartMillis;
	private long latePathDeepNanos;
	private double latePathMoveEma;

	private record LatePathRequest(SpeakerBlockEntity speaker, int channel,
								   List<MirrorPlane> planes, Vec3 source) {
	}

	private record LatePathCandidate(ReflectionPathTap path,
									 SpeakerBlockEntity speaker) {
	}

	AvReceiverPlayback(BlockPos receiverPos) {
		this.receiverPos = receiverPos;
		this.textureId = AvReceiverPlaybackManager.nextTextureId();
		this.texture = new CinemaVideoTexture();
		Minecraft.getInstance().getTextureManager().register(textureId, texture);
		this.textureRegistered = true;
		Arrays.fill(channelFilters, MinimumPhaseFir.FLAT);
		Arrays.fill(channelEnvironmentFilters, MinimumPhaseFir.FLAT);
	}

	public BlockPos receiverPos() {
		return receiverPos;
	}

	public CinemaPlayer player() {
		return player;
	}

	/** Number of 5.1 channels currently routed to physical linked speakers. */
	public int physicalChannels() {
		return physicalChannels;
	}

	/** Remaining seek cooldown in milliseconds, or {@code 0} when seek is available. */
	public long seekCooldownRemainingMillis() {
		return Math.max(0L, seekCooldownUntilMillis - System.currentTimeMillis());
	}

	/**
	 * Reconciles the local player with the receiver state. Returns {@code false}
	 * when the receiver is stopped and this proxy should be removed.
	 */
	boolean sync(ClientLevel level, AvReceiverBlockEntity receiver) {
		if (receiver.state() == AvReceiverBlockEntity.State.STOPPED || receiver.mediaKey().isEmpty()) {
			close();
			return false;
		}
		boolean mediaChanged = !receiver.mediaKey().equals(mediaKey)
			|| receiver.mediaSize() != mediaSize
			|| !receiver.mediaSha256().equals(mediaSha256);
		if (mediaChanged) {
			closePlayerForRestart(expectedPosition(level, receiver));
			mediaKey = receiver.mediaKey();
			mediaSize = receiver.mediaSize();
			mediaSha256 = receiver.mediaSha256();
			failedKey = "";
			lastMissingLogMillis = 0L;
			missing = false;
			clockAligned = false;
			clockOffset = 0.0;
			lastExpectedSeconds = Double.NaN;
		}
		if (player == null && !open(level, receiver)) {
			return true;
		}
		updateChannelLayout(level, receiver);
		if (player.error() != null) {
			Cinema.LOGGER.warn("cinema receiver {}: playback failed for {}: {}",
				receiverPos.toShortString(), mediaKey, player.error().toString());
			closePlayer();
			failedKey = mediaKey;
			failedAtMillis = System.currentTimeMillis();
			return true;
		}
		if (Minecraft.getInstance().isPaused()) {
			// The integrated server (and therefore the receiver clock) is frozen
			// while the pause menu is open. Pause the local decoder too, otherwise
			// the drift check restarts the player in a loop.
			player.setPaused(true);
			return true;
		}
		if (receiver.state() == AvReceiverBlockEntity.State.PAUSED) {
			player.setPaused(true);
		} else {
			player.setPaused(false);
			// Always advance the expected-position tracker, even while a seek
			// is settling. Otherwise the stale value looks like a fresh server
			// seek when the grace period ends and creates a restart loop.
			double expected = expectedPosition(level, receiver);
			boolean jumped = !Double.isNaN(lastExpectedSeconds)
				&& Math.abs(expected - lastExpectedSeconds) > 0.5;
			lastExpectedSeconds = expected;
			long nowMillis = System.currentTimeMillis();
			if (!Double.isNaN(pendingSeekSeconds) && nowMillis >= seekCooldownUntilMillis
				&& !player.isSeeking()) {
				double target = pendingSeekSeconds;
				pendingSeekSeconds = Double.NaN;
				restartAt(level, receiver, target);
				if (player != null) {
					player.setVolume(receiver.volume(), receiver.isMuted());
				}
				return true;
			}
			if (jumped) {
				if (nowMillis < seekCooldownUntilMillis || player.isSeeking()) {
					// Coalesce a burst of seek clicks into one restart, or wait
					// for an in-flight seek worker to finish.
					pendingSeekSeconds = expected;
					return true;
				}
				// A new server seek can arrive while the previous restart is
				// still inside its settle window. Apply it now instead of
				// swallowing it and leaving the old audio playing until the
				// grace period expires.
				restartAt(level, receiver, expected);
				if (player != null) {
					player.setVolume(receiver.volume(), receiver.isMuted());
				}
				return true;
			}
			if (player.isSeeking() || nowMillis < seekGraceUntilMillis) {
				// The seek worker is still draining the old decoders/audio, or
				// the newly started decoder has not reached the target clock
				// yet. Do not run drift correction against an intermediate clock.
				player.setVolume(receiver.volume(), receiver.isMuted());
				return true;
			}
			boolean starting = player.presentedFrames() == 0
				&& System.currentTimeMillis() - openedAtMillis < 10_000L;
			if (!starting) {
				if (!clockAligned) {
					double offset = player.positionSeconds() - expected;
					if (Math.abs(offset) > 30.0) {
						restartAt(level, receiver, expected);
						if (player == null) {
							return true;
						}
					} else {
						clockOffset = offset;
						clockAligned = true;
					}
				}
				if (clockAligned
					&& Math.abs(player.positionSeconds() - (expected + clockOffset)) > MAX_DRIFT_SECONDS) {
					restartAt(level, receiver, expected);
					if (player == null) {
						return true;
					}
				}
			}
		}
		player.setVolume(receiver.volume(), receiver.isMuted());
		return true;
	}

	public void presentFrame() {
		if (player != null) {
			player.presentFrame();
		}
	}

	public void close() {
		closePlayer();
		if (audio != null) {
			audio.close();
			audio = null;
		}
		if (textureRegistered) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			textureRegistered = false;
		}
		mediaKey = "";
		mediaSize = -1L;
		mediaSha256 = "";
		missing = false;
		clockAligned = false;
		clockOffset = 0.0;
		seekGraceUntilMillis = 0L;
		seekCooldownUntilMillis = 0L;
		pendingSeekSeconds = Double.NaN;
		lastExpectedSeconds = Double.NaN;
		physicalChannels = 0;
		lastReflectionUpdateMillis = 0L;
		lastLateReverbUpdateMillis = 0L;
		lastLateReverbLogMillis = 0L;
		lastReflectionRoomVersion = Long.MIN_VALUE;
		lastReflectionListener = null;
		reflectionFilters.clear();
		lastReflectionLogCount = -1;
		lastReflectionLogMillis = 0L;
		Arrays.fill(latePathRequests, null);
		latePathCandidates.clear();
		latePathFastNextChannel = 0;
		latePathFastSession = null;
		latePathFastRequest = null;
		latePathFastTaps = List.of();
		latePathDeepTaps.clear();
		latePathDeepNextChannel = 0;
		latePathDeepSession = null;
		latePathDeepRequest = null;
		premiumOrder2Sources = List.of();
		premiumOrder3Sources = List.of();
		premiumReflectionFilters.clear();
		currentLateTaps = List.of();
		lastLatePathListener = null;
		lastLatePathSample = null;
		lastLatePathSampleMillis = 0L;
		lastLatePathMoveMillis = 0L;
		latePathMoveEma = 0.0;
		latePathRequestRoomVersion = Long.MIN_VALUE;
		latePathBuildActive = false;
		latePathFastApplied = false;
		latePathDeepApplied = false;
		latePathBuildStartMillis = 0L;
		latePathBuildNanos = 0L;
		latePathDeepStartMillis = 0L;
		latePathDeepNanos = 0L;
		if (audio != null) {
			audio.setLateReflectionTaps(List.of());
		}
	}

	private void closePlayer() {
		if (player != null) {
			player.close();
			player = null;
		}
	}

	private void closePlayerForRestart(double nextStartSeconds) {
		if (player != null) {
			player.closeForRestart(nextStartSeconds);
			player = null;
		}
	}

	private boolean open(ClientLevel level, AvReceiverBlockEntity receiver) {
		if (mediaKey.equals(failedKey) && System.currentTimeMillis() - failedAtMillis < FAILURE_RETRY_MILLIS) {
			return false;
		}
		Path file;
		try {
			file = MediaLibrary.resolve(mediaKey);
		} catch (IOException e) {
			reportMissing(e.getMessage());
			return false;
		}
		if (!Files.isRegularFile(file)) {
			reportMissing("missing: " + mediaKey);
			return false;
		}
		double expected = expectedPosition(level, receiver);
		if (!openAt(file, receiver, expected)) {
			return false;
		}
		missing = false;
		Cinema.LOGGER.info("cinema receiver {}: opened {} at {} s",
			receiverPos.toShortString(), mediaKey, String.format("%.2f", expected));
		return true;
	}

	private void restartAt(ClientLevel level, AvReceiverBlockEntity receiver, double expected) {
		long seekStartedNanos = System.nanoTime();
		long now = System.currentTimeMillis();
		seekGraceUntilMillis = now + SEEK_COOLDOWN_MILLIS + 1_000L;
		seekCooldownUntilMillis = now + SEEK_COOLDOWN_MILLIS;
		pendingSeekSeconds = Double.NaN;
		Path file;
		try {
			file = MediaLibrary.resolve(mediaKey);
		} catch (IOException e) {
			reportMissing(e.getMessage());
			closePlayer();
			return;
		}
		// In-place seek is disabled until its decoder/clock state machine is
		// proven stable. Keep the full player restart, but retain the audio
		// engine's source pool so seek does not rebuild the audio backend.
		closePlayerForRestart(expected);
		if (openAt(file, receiver, expected)) {
			Cinema.LOGGER.info("cinema receiver {}: resynced {} to {} s in {} ms",
				receiverPos.toShortString(), mediaKey, String.format("%.2f", expected),
				(System.nanoTime() - seekStartedNanos) / 1_000_000L);
		}
	}

	private boolean openAt(Path file, AvReceiverBlockEntity receiver, double expected) {
		try {
			player = CinemaPlayer.open(Minecraft.getInstance(), file, expected,
				audioEngine(), textureId, texture);
		} catch (Exception e) {
			failedKey = mediaKey;
			failedAtMillis = System.currentTimeMillis();
			Cinema.LOGGER.warn("cinema receiver {}: could not open {}: {}",
				receiverPos.toShortString(), mediaKey, e.getMessage());
			return false;
		}
		player.setVolume(receiver.volume(), receiver.isMuted());
		player.setPaused(receiver.state() == AvReceiverBlockEntity.State.PAUSED);
		openedAtMillis = System.currentTimeMillis();
		clockAligned = false;
		clockOffset = 0.0;
		return true;
	}

	/** One OpenAL context per receiver; reused across seeks, closed with the proxy. */
	private CinemaAudioEngine audioEngine() {
		if (audio == null) {
			audio = new CinemaAudioEngine();
			audio.start();
		}
		return audio;
	}

	private void reportMissing(String reason) {
		missing = true;
		failedKey = mediaKey;
		long now = System.currentTimeMillis();
		if (lastMissingLogMillis == 0L || now - lastMissingLogMillis >= MISSING_LOG_INTERVAL_MILLIS) {
			lastMissingLogMillis = now;
			Cinema.LOGGER.warn("cinema receiver {}: media unavailable: {}",
				receiverPos.toShortString(), reason);
		}
	}

	/**
	 * The listener is always the local player's actual ears and head direction.
	 * There is no Seat/sweet-spot calibration and no screen-locked correction:
	 * walking or looking away therefore keeps the physical speaker directions
	 * correct instead of re-centering the sound field on a fixed seat.
	 */
	void updateChannelLayout(ClientLevel level, AvReceiverBlockEntity receiver) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Vec3 listener;
		Vec3 forward;
		if (client.player != null) {
			listener = client.player.getEyePosition();
			// Use the view yaw directly. The horizontal part of getLookAngle()
			// collapses near straight up/down, which used to snap the whole
			// listener frame to north while the player was pitching the view.
			float yaw = (float) Math.toRadians(client.player.getViewYRot(1.0f));
			forward = new Vec3(-Math.sin(yaw), 0.0, Math.cos(yaw));
		} else {
			listener = Vec3.atCenterOf(receiver.getBlockPos()).add(0.0, 1.5, 0.0);
			forward = new Vec3(0.0, 0.0, -1.0);
		}
		if (forward.lengthSqr() < 1.0e-4) {
			forward = new Vec3(0.0, 0.0, -1.0);
		} else {
			forward = forward.normalize();
		}

		float[][] positions = new float[CinemaAudioEngine.CHANNELS][];
		float[] gains = new float[CinemaAudioEngine.CHANNELS];
		MinimumPhaseFir[] filters = new MinimumPhaseFir[CinemaAudioEngine.CHANNELS];
		float[] occlusionGains = new float[CinemaAudioEngine.CHANNELS];
		float[] lateSendGains = new float[CinemaAudioEngine.CHANNELS];
		SpeakerBlockEntity[] routedSpeakers =
			new SpeakerBlockEntity[CinemaAudioEngine.CHANNELS];
		Arrays.fill(gains, 1.0f);
		Arrays.fill(filters, MinimumPhaseFir.FLAT);
		Arrays.fill(occlusionGains, 1.0f);
		Arrays.fill(lateSendGains, (float) Math.sqrt(4.0 * Math.PI));
		CinemaClientConfig config = CinemaClientConfig.get();
		boolean airAbsorption = config.airAbsorption();
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		double[] bestDistance = new double[CinemaAudioEngine.CHANNELS];
		Arrays.fill(bestDistance, Double.MAX_VALUE);
		for (BlockPos pos : receiver.speakerTargets()) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
				continue;
			}
			SpeakerRole role = speaker.role();
			int channel = role.ordinal() - 1;
			if (channel < 0 || channel >= positions.length) {
				continue;
			}
			Vec3 center = speaker.acousticFrontPosition();
			Vec3 relative = center.subtract(listener);
			double distanceSqr = relative.lengthSqr();
			if (distanceSqr < 0.01 || distanceSqr >= bestDistance[channel]) {
				continue;
			}
			bestDistance[channel] = distanceSqr;
			routedSpeakers[channel] = speaker;
			positions[channel] = toListenerSpace(relative, forward);
			SpeakerDirectivityService.Angles angles = SpeakerDirectivityService.angles(
				center, speaker.acousticForward(), listener);
			double distance = Math.sqrt(distanceSqr);
			OcclusionResult occlusion = OcclusionScanner.scan(level, center, listener);
			SpeakerDataProfile profile = speakerProfile(speaker);
			CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.direct(
				profile, angles.horizontal(), angles.vertical(),
				distance, airAbsorption, temperatureC, humidityPercent, pressureKpa,
				occlusion);
			MinimumPhaseFir filter = commonFilter(channel, firProfile);
			filters[channel] = filter;
			double directSourceGain = Math.min(MAX_CHANNEL_GAIN,
				Math.max(0.0, speakerGain(speaker) * overallGain(firProfile)));
			gains[channel] = (float) directSourceGain;
			occlusionGains[channel] = (float) occlusion.linearGain();
			double relativeDirectivity = profile == null ? 1.0
				: Math.pow(10.0, profile.gainDb(angles.horizontal(),
					angles.vertical(), CommonAcousticFirDesigner.REFERENCE_HZ)
					/ 20.0);
			double powerIntegral = profile == null ? 4.0 * Math.PI
				: profile.directivityPowerIntegral(
					CommonAcousticFirDesigner.REFERENCE_HZ);
			double lateSendGain = directSourceGain
				/ Math.max(1.0e-4, relativeDirectivity)
				* Math.sqrt(powerIntegral);
			lateSendGains[channel] = (float) Math.min(MAX_LATE_SEND_GAIN,
				Math.max(0.0, lateSendGain));
		}
		for (int channel = 0; channel < CinemaAudioEngine.CHANNELS; channel++) {
			// Unpaired channels fall back to the engine's virtual layout. They
			// still get distance and air absorption, but no occlusion because
			// there is no physical source position to trace from.
			if (positions[channel] != null) {
				continue;
			}
			double distance = CinemaAudioEngine.defaultSourceDistance(channel);
			CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.direct(
				null, 0.0, 0.0, distance, airAbsorption, temperatureC,
				humidityPercent, pressureKpa, OcclusionResult.NONE);
			filters[channel] = commonFilter(channel, firProfile);
		}

		int routed = 0;
		for (float[] position : positions) {
			if (position != null) {
				routed++;
			}
		}
		physicalChannels = routed;
		System.arraycopy(filters, 0, channelFilters, 0, filters.length);
		engine.setChannelLayout(positions, gains, filters, occlusionGains,
			lateSendGains);
		updateLateReverb(listener, positions, gains, occlusionGains,
			lateSendGains, routedSpeakers, config);
		updateReflections(level, listener, forward, routedSpeakers, config);
		stepLatePhase(level, listener, forward, config);
	}

	/**
	 * Computes the physical late-field target from the room constant and the
	 * actual source strengths. The FDN send uses source strength (no 1/r), so
	 * this target is comparable to the actual wet/dry energy at the listener.
	 */
	private void updateLateReverb(Vec3 listener, float[][] positions,
								  float[] gains, float[] occlusionGains,
								  float[] lateSendGains,
								  SpeakerBlockEntity[] speakers,
								  CinemaClientConfig config) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastLateReverbUpdateMillis < LATE_REVERB_UPDATE_MILLIS) {
			return;
		}
		lastLateReverbUpdateMillis = now;
		RoomAcousticSnapshot snapshot = AcousticScene.snapshot();
		AcousticRoom room = snapshot == null ? null : snapshot.room();
		List<LateReverbTarget.Source> sources = new ArrayList<>();
		for (int channel = 0; channel < CinemaAudioEngine.CHANNELS; channel++) {
			float[] position = positions[channel];
			double distance = position == null
				? CinemaAudioEngine.defaultSourceDistance(channel)
				: Math.sqrt(position[0] * position[0]
					+ position[1] * position[1] + position[2] * position[2]);
			double directGain = gains[channel] * occlusionGains[channel];
			double sourcePowerGain = lateSendGains[channel]
				* occlusionGains[channel];
			sources.add(new LateReverbTarget.Source(directGain,
				sourcePowerGain, distance));
		}
		LateReverbTarget.Result target = LateReverbTarget.compute(room, sources);
		LateReverbSettings settings = LateReverbSettings.forRoom(room,
			config.lateReverb(), target.targetWetGainDb(),
			target.sourceGainDb(), config.lateTrimDb(), target.roomConstant(),
			target.criticalDistanceMeters());
		engine.setLateReverb(settings);
		if (now - lastLateReverbLogMillis >= 5_000L) {
			lastLateReverbLogMillis = now;
			Cinema.LOGGER.info(
				"cinema receiver {}: late reverb R={} m2 rc={} m "
					+ "rt60L/M/H={}/{}/{} s target={} dB "
					+ "sourceGain={} dB trim={} dB effective={} dB wetDry={} dB",
				receiverPos.toShortString(),
				formatMetric(settings.roomConstant(), "%.1f"),
				formatMetric(settings.criticalDistanceMeters(), "%.2f"),
				formatMetric(settings.rt60LowSeconds(), "%.2f"),
				formatMetric(settings.rt60MidSeconds(), "%.2f"),
				formatMetric(settings.rt60HighSeconds(), "%.2f"),
				formatDb(settings.targetWetGainDb()),
				formatDb(settings.sourceGainDb()),
				formatDb(settings.trimDb()),
				formatDb(settings.wetGainDb()),
				formatDb(settings.targetWetGainDb() + settings.trimDb()));
		}
	}

	private static String formatMetric(double value, String format) {
		return Double.isFinite(value)
			? String.format(java.util.Locale.ROOT, format, value) : "-";
	}

	private static String formatDb(double value) {
		return String.format(java.util.Locale.ROOT, "%+.1f", value);
	}

	/**
	 * Recomputes first-order reflection taps at ~5 Hz from the receiver-linked
	 * physical speakers. Flood-fill boundary patches are optional seeds;
	 * speaker-centered rays work without a valid room box. The engine receives
	 * listener-relative image-source positions (not reflection points) and one
	 * 2048-tap FIR carrying the full reflection frequency shape.
	 *
	 * <p>The tap gain already carries {@code sqrt(1-alpha_mid)/max(L,1)} and the
	 * combined broadband occlusion gain. Speaker trim and the FIR's
	 * on-axis/directivity level at 1 kHz are applied here. Air absorption,
	 * material reflection and occlusion low-pass are all inside the same
	 * full-band FIR, so no term is applied twice.
	 */
	private void updateReflections(ClientLevel level, Vec3 listener, Vec3 forward,
								   SpeakerBlockEntity[] speakers,
								   CinemaClientConfig config) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		long now = System.currentTimeMillis();
		RoomAcousticSnapshot snapshot = AcousticScene.snapshot();
		AcousticRoom room = snapshot == null ? null : snapshot.room();
		long snapshotVersion = snapshot == null ? 0L : snapshot.version();
		long roomVersion = room == null ? 0L : room.reflectionKey();
		boolean moved = lastReflectionListener == null
			|| listener.distanceToSqr(lastReflectionListener)
				> REFLECTION_MOVE_THRESHOLD_SQR;
		if (now - lastReflectionUpdateMillis < REFLECTION_UPDATE_MILLIS
			&& roomVersion == lastReflectionRoomVersion && !moved) {
			return;
		}
		lastReflectionUpdateMillis = now;
		lastReflectionRoomVersion = roomVersion;
		lastReflectionListener = listener;
		Set<String> previousKeys = new HashSet<>(reflectionFilters.keySet());

		List<AcousticSurface> seeds = room == null
			? List.of() : room.reflectionSurfaces();
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		ReflectionProbe probe = ReflectionProbe.of(level);
		double earlyGain = Math.pow(10.0, config.earlyGainDb() / 20.0);
		List<ReflectionTap> taps = new ArrayList<>();
		List<LatePathCandidate> order2Candidates = new ArrayList<>();
		boolean lateOrder2Stable = config.lateReverb() && latePathFastApplied
			&& lastLatePathListener != null
			&& listener.distanceToSqr(lastLatePathListener)
				<= LATE_PATH_MOVE_THRESHOLD_SQR
			&& roomVersion == latePathRequestRoomVersion;
		Arrays.fill(latePathRequests, null);
		for (int channel = 0; channel < CinemaAudioEngine.CHANNELS; channel++) {
			SpeakerBlockEntity speaker = speakers[channel];
			if (speaker == null || channel == SpeakerRole.LFE.ordinal() - 1) {
				continue;
			}
			Vec3 center = speaker.acousticFrontPosition();
			List<MirrorPlane> planes = MirrorPlaneDiscovery.INSTANCE.discover(
				level, center, roomVersion, seeds);
			if (config.lateReverb()) {
				latePathRequests[channel] = new LatePathRequest(speaker, channel,
					planes, center);
			}
			taps.addAll(ImageSourceSolver.solve(planes, channel, pointOf(center),
				pointOf(listener), false, probe, previousKeys));
			if (config.lateReverb() && !lateOrder2Stable) {
				// Order-2 paths are published immediately. The order-3/4
				// upgrades run incrementally and replace this set only after
				// their phase completes.
				List<ReflectionPathTap> paths = ImageSourcePathSolver.solve(planes,
					channel, pointOf(center), pointOf(listener), false, probe, 2,
					MAX_FAST_REFLECTION_PATHS);
				for (ReflectionPathTap path : paths) {
					order2Candidates.add(new LatePathCandidate(path, speaker));
				}
			}
		}
		List<LateReflectionBus.Tap> order2BusTaps = List.of();
		if (!lateOrder2Stable) {
			List<LatePathCandidate> premium2 = selectPremium(order2Candidates, 2,
				MAX_PREMIUM_ORDER2_REFLECTIONS);
			Set<String> premium2Keys = pathKeys(premium2);
			premiumOrder2Sources = buildPremiumSources(premium2, listener, forward,
				config, temperatureC, humidityPercent, pressureKpa, earlyGain);
			order2BusTaps = busTaps(order2Candidates,
				premium2Keys, config, temperatureC, humidityPercent, pressureKpa);
		}
		updateLatePathBus(listener, config, order2BusTaps, roomVersion);
		List<ReflectionTap> limited = ImageSourceSolver.strongest(taps,
			MAX_FIRST_ORDER_REFLECTIONS, previousKeys);
		Set<String> currentKeys = new HashSet<>();
		List<ReflectionSource> sources = new ArrayList<>(limited.size());
		for (ReflectionTap tap : limited) {
			int channel = tap.channel();
			SpeakerBlockEntity speaker = speakers[channel];
			if (speaker == null) {
				continue;
			}
			String key = ImageSourceSolver.tapKey(tap);
			currentKeys.add(key);
			Vec3 imageRelative = new Vec3(
				tap.imageSource().x() - listener.x,
				tap.imageSource().y() - listener.y,
				tap.imageSource().z() - listener.z);
			float[] position = toListenerSpace(imageRelative, forward);
			Vec3 reflectionPoint = new Vec3(tap.reflectionPoint().x(),
				tap.reflectionPoint().y(), tap.reflectionPoint().z());
			SpeakerDirectivityService.Angles angles = SpeakerDirectivityService.angles(
				speaker.acousticFrontPosition(), speaker.acousticForward(),
				reflectionPoint);
			CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.reflection(
				speakerProfile(speaker), angles.horizontal(), angles.vertical(),
				tap.pathLength(), config.airAbsorption(), temperatureC,
				humidityPercent, pressureKpa, tap.material(), tap.occlusion());
			MinimumPhaseFir filter = reflectionFilters.get(key);
			MinimumPhaseFir design = CommonAcousticFirService.get(firProfile);
			if (design != null) {
				filter = design;
				reflectionFilters.put(key, design);
			}
			if (filter == null) {
				filter = MinimumPhaseFir.FLAT;
			}
			double gain = tap.gain() * speakerGain(speaker)
				* overallGain(firProfile) * earlyGain;
			sources.add(new ReflectionSource(key, channel, position[0],
				position[1], position[2], (float) gain, tap.delaySeconds(),
				filter));
		}
		reflectionFilters.keySet().retainAll(currentKeys);
		List<ReflectionSource> allSources = new ArrayList<>(
			sources.size() + premiumOrder2Sources.size()
				+ premiumOrder3Sources.size());
		allSources.addAll(sources);
		allSources.addAll(premiumOrder2Sources);
		allSources.addAll(premiumOrder3Sources);
		engine.setReflections(allSources);
		if (allSources.size() != lastReflectionLogCount
			&& now - lastReflectionLogMillis >= 5_000L) {
			lastReflectionLogCount = allSources.size();
			lastReflectionLogMillis = now;
			Cinema.LOGGER.info(
				"cinema receiver {}: {} reflection taps (first={}, premium2={}, "
					+ "premium3={}, candidates={}, surfaces={}, room={})",
				receiverPos.toShortString(), allSources.size(), sources.size(),
				premiumOrder2Sources.size(), premiumOrder3Sources.size(),
				taps.size(), seeds.size(),
				snapshot == null ? "none" : Long.toString(snapshotVersion));
		}
	}

	private LateReflectionBus.Tap lateTapFromPath(ReflectionPathTap path,
												 SpeakerBlockEntity speaker,
												 CinemaClientConfig config,
												 double temperatureC,
												 double humidityPercent,
												 double pressureKpa) {
		if (path == null || path.reflectionPoints().isEmpty()
			|| path.materials().isEmpty()) {
			return null;
		}
		Point3 firstPoint = path.reflectionPoints().get(0);
		Vec3 firstReflection = new Vec3(firstPoint.x(), firstPoint.y(),
			firstPoint.z());
		SpeakerDirectivityService.Angles angles =
			SpeakerDirectivityService.angles(speaker.acousticFrontPosition(),
				speaker.acousticForward(), firstReflection);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(speakerProfile(speaker),
				angles.horizontal(), angles.vertical(), path.pathLength(),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, path.materials().get(0), path.occlusion());
		// fromPath() already applies path.gain(); pass only the speaker/FIR
		// level so the total is path.gain * speakerGain * overallGain.
		double gain = speakerGain(speaker) * overallGain(firProfile);
		return LateReflectionBus.Tap.fromPath(path, gain);
	}

	private static List<LatePathCandidate> selectPremium(
		List<LatePathCandidate> candidates, int order, int max) {
		List<LatePathCandidate> selected = new ArrayList<>();
		for (LatePathCandidate candidate : candidates) {
			if (candidate.path().order() == order
				&& candidate.path().delaySeconds() <= MAX_PREMIUM_DELAY_SECONDS) {
				selected.add(candidate);
			}
		}
		selected.sort((first, second) -> Double.compare(
			second.path().gain(), first.path().gain()));
		if (selected.size() > max) {
			selected = new ArrayList<>(selected.subList(0, max));
		}
		return List.copyOf(selected);
	}

	private static Set<String> pathKeys(List<LatePathCandidate> candidates) {
		Set<String> keys = new HashSet<>();
		for (LatePathCandidate candidate : candidates) {
			keys.add(candidate.path().pathKey());
		}
		return keys;
	}

	private List<LateReflectionBus.Tap> busTaps(
		List<LatePathCandidate> candidates, Set<String> excluded,
		CinemaClientConfig config, double temperatureC, double humidityPercent,
		double pressureKpa) {
		List<LateReflectionBus.Tap> taps = new ArrayList<>();
		for (LatePathCandidate candidate : candidates) {
			if (excluded.contains(candidate.path().pathKey())) {
				continue;
			}
			LateReflectionBus.Tap tap = lateTapFromPath(candidate.path(),
				candidate.speaker(), config, temperatureC, humidityPercent,
				pressureKpa);
			if (tap != null) {
				taps.add(tap);
			}
		}
		return taps;
	}

	private List<ReflectionSource> buildPremiumSources(
		List<LatePathCandidate> candidates, Vec3 listener, Vec3 forward,
		CinemaClientConfig config, double temperatureC, double humidityPercent,
		double pressureKpa, double earlyGain) {
		if (candidates.isEmpty()) {
			premiumReflectionFilters.clear();
			return List.of();
		}
		List<ReflectionSource> sources = new ArrayList<>(candidates.size());
		Set<String> keys = new HashSet<>();
		for (LatePathCandidate candidate : candidates) {
			ReflectionSource source = premiumSource(candidate, listener, forward,
				config, temperatureC, humidityPercent, pressureKpa, earlyGain);
			if (source != null) {
				sources.add(source);
				keys.add(source.key());
			}
		}
		premiumReflectionFilters.keySet().retainAll(keys);
		return List.copyOf(sources);
	}

	private ReflectionSource premiumSource(LatePathCandidate candidate,
										   Vec3 listener, Vec3 forward,
										   CinemaClientConfig config,
										   double temperatureC,
										   double humidityPercent,
										   double pressureKpa,
										   double earlyGain) {
		ReflectionPathTap path = candidate.path();
		SpeakerBlockEntity speaker = candidate.speaker();
		if (path.reflectionPoints().isEmpty() || path.materials().isEmpty()) {
			return null;
		}
		Vec3 imageRelative = new Vec3(
			path.imageSource().x() - listener.x,
			path.imageSource().y() - listener.y,
			path.imageSource().z() - listener.z);
		float[] position = toListenerSpace(imageRelative, forward);
		Point3 firstPoint = path.reflectionPoints().get(0);
		Vec3 firstReflection = new Vec3(firstPoint.x(), firstPoint.y(),
			firstPoint.z());
		SpeakerDirectivityService.Angles angles =
			SpeakerDirectivityService.angles(speaker.acousticFrontPosition(),
				speaker.acousticForward(), firstReflection);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(speakerProfile(speaker),
				angles.horizontal(), angles.vertical(), path.pathLength(),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, path.materials().get(0), path.occlusion());
		String key = path.pathKey();
		MinimumPhaseFir filter = premiumReflectionFilters.get(key);
		MinimumPhaseFir design = CommonAcousticFirService.get(firProfile);
		if (design != null) {
			filter = design;
			premiumReflectionFilters.put(key, design);
		}
		if (filter == null) {
			filter = MinimumPhaseFir.FLAT;
		}
		double gain = path.gain() * speakerGain(speaker)
			* overallGain(firProfile) * earlyGain;
		return new ReflectionSource(key, path.channel(), position[0],
			position[1], position[2], (float) gain, path.delaySeconds(),
			filter);
	}

	/**
	 * Publishes order-2 taps immediately, then runs two incremental phases:
	 * order 3 in cost-aware speaker batches, followed by order 4 one speaker at a
	 * time. The order-3 bus stays active while order 4 is being computed.
	 *
	 * <p>This is deliberately not a background thread: the path solver reads the
	 * live client level ({@code wallAt}, occlusion scans), which is not safe
	 * off-thread.
	 */
	private void updateLatePathBus(Vec3 listener, CinemaClientConfig config,
								   List<LateReflectionBus.Tap> order2Taps,
								   long roomVersion) {
		if (audio == null) {
			return;
		}
		if (!config.lateReverb()) {
			resetLatePathBuild();
			currentLateTaps = List.of();
			engineTaps(currentLateTaps);
			return;
		}
		long now = System.currentTimeMillis();
		updateLatePathMovement(listener, now);
		boolean moved = lastLatePathListener == null
			|| listener.distanceToSqr(lastLatePathListener)
				> LATE_PATH_MOVE_THRESHOLD_SQR
			|| roomVersion != latePathRequestRoomVersion;
		if (moved) {
			lastLatePathListener = listener;
			lastLatePathMoveMillis = now;
			latePathRequestRoomVersion = roomVersion;
			resetLatePathBuild();
			currentLateTaps = List.copyOf(order2Taps);
			engineTaps(currentLateTaps);
		}
		// Fast/deep sessions are advanced in small per-tick slices by
		// stepLatePhase().
	}

	private void updateLatePathMovement(Vec3 listener, long now) {
		if (lastLatePathSample == null) {
			lastLatePathSample = listener;
			lastLatePathSampleMillis = now;
			return;
		}
		long dt = now - lastLatePathSampleMillis;
		if (dt > 0) {
			double speed = listener.distanceTo(lastLatePathSample)
				/ (dt / 1_000.0);
			latePathMoveEma += (speed - latePathMoveEma) * 0.35;
			if (speed > 0.03) {
				lastLatePathMoveMillis = now;
			}
		}
		lastLatePathSample = listener;
		lastLatePathSampleMillis = now;
	}

	private long requiredStableMillis() {
		double value = LATE_PATH_MIN_STABLE_MILLIS
			+ LATE_PATH_STABLE_MS_PER_SPEED * latePathMoveEma;
		return (long) Math.max(LATE_PATH_MIN_STABLE_MILLIS,
			Math.min(LATE_PATH_MAX_STABLE_MILLIS, value));
	}

	private void stepLatePhase(ClientLevel level, Vec3 listener, Vec3 forward,
							   CinemaClientConfig config) {
		if (audio == null || !config.lateReverb() || latePathDeepApplied) {
			return;
		}
		long now = System.currentTimeMillis();
		if (!latePathFastApplied) {
			stepFastPhase(level, listener, forward, config, now);
		} else {
			stepDeepPhase(level, listener, config);
		}
	}

	private void stepFastPhase(ClientLevel level, Vec3 listener, Vec3 forward,
							   CinemaClientConfig config, long now) {
		if (latePathFastSession == null) {
			if (now - lastLatePathMoveMillis < requiredStableMillis()) {
				return;
			}
			LatePathRequest request = nextFastRequest();
			if (request == null) {
				finishFastPhase(listener, forward, config, now);
				return;
			}
			if (!latePathBuildActive) {
				latePathBuildActive = true;
				latePathCandidates.clear();
				latePathBuildStartMillis = now;
				latePathBuildNanos = 0L;
			}
			latePathFastRequest = request;
			latePathFastSession = new ImageSourcePathSolver.Session(
				request.planes(), request.channel(), pointOf(request.source()),
				pointOf(listener), ReflectionProbe.of(level),
				LATE_PATH_FAST_ORDER, MAX_FAST_REFLECTION_PATHS, 2,
				ImageSourcePathSolver.MAX_VISITS);
		}
		long stepStart = System.nanoTime();
		boolean done = latePathFastSession.step(LATE_PATH_STEP_BUDGET_NANOS);
		latePathBuildNanos += System.nanoTime() - stepStart;
		if (!done) {
			return;
		}
		ImageSourcePathSolver.SolveStats stats = latePathFastSession.stats();
		LatePathRequest request = latePathFastRequest;
		for (ReflectionPathTap path : stats.paths()) {
			latePathCandidates.add(new LatePathCandidate(path,
				request.speaker()));
		}
		latePathFastSession = null;
		latePathFastRequest = null;
		if (latePathFastNextChannel >= CinemaAudioEngine.CHANNELS) {
			finishFastPhase(listener, forward, config, now);
		}
	}

	private LatePathRequest nextFastRequest() {
		while (latePathFastNextChannel < CinemaAudioEngine.CHANNELS) {
			LatePathRequest request = latePathRequests[latePathFastNextChannel++];
			if (request != null && !request.speaker().isRemoved()) {
				return request;
			}
		}
		return null;
	}

	private void finishFastPhase(Vec3 listener, Vec3 forward,
								 CinemaClientConfig config, long now) {
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		double earlyGain = Math.pow(10.0, config.earlyGainDb() / 20.0);
		List<LatePathCandidate> premium2 = selectPremium(latePathCandidates, 2,
			MAX_PREMIUM_ORDER2_REFLECTIONS);
		List<LatePathCandidate> premium3 = selectPremium(latePathCandidates, 3,
			MAX_PREMIUM_ORDER3_REFLECTIONS);
		Set<String> premium2Keys = pathKeys(premium2);
		Set<String> premium3Keys = pathKeys(premium3);
		Set<String> excluded = new HashSet<>(premium2Keys);
		excluded.addAll(premium3Keys);
		List<LatePathCandidate> premiumAll = new ArrayList<>(
			premium2.size() + premium3.size());
		premiumAll.addAll(premium2);
		premiumAll.addAll(premium3);
		List<ReflectionSource> premiumSources = buildPremiumSources(
			premiumAll, listener, forward, config, temperatureC,
			humidityPercent, pressureKpa, earlyGain);
		List<ReflectionSource> order2Sources = new ArrayList<>();
		List<ReflectionSource> order3Sources = new ArrayList<>();
		for (ReflectionSource source : premiumSources) {
			if (premium2Keys.contains(source.key())) {
				order2Sources.add(source);
			} else if (premium3Keys.contains(source.key())) {
				order3Sources.add(source);
			}
		}
		premiumOrder2Sources = List.copyOf(order2Sources);
		premiumOrder3Sources = List.copyOf(order3Sources);
		latePathFastTaps = List.copyOf(busTaps(latePathCandidates, excluded,
			config, temperatureC, humidityPercent, pressureKpa));
		currentLateTaps = latePathFastTaps;
		engineTaps(currentLateTaps);
		latePathBuildActive = false;
		latePathFastApplied = true;
		latePathDeepApplied = false;
		latePathDeepNextChannel = 0;
		latePathDeepTaps.clear();
		latePathDeepStartMillis = now;
		latePathDeepNanos = 0L;
		int order2Paths = 0;
		int order3Paths = 0;
		for (LatePathCandidate candidate : latePathCandidates) {
			if (candidate.path().order() == 2) {
				order2Paths++;
			} else if (candidate.path().order() == 3) {
				order3Paths++;
			}
		}
		Cinema.LOGGER.info(
			"cinema receiver {}: geometric late bus steady fast order=3 "
				+ "busTaps={} premium2={} premium3={} paths={} "
				+ "(order2={}, order3={}), stable={} ms, upgrade={} ms, "
				+ "solve={} ms",
			receiverPos.toShortString(), currentLateTaps.size(),
			premium2.size(), premium3.size(), latePathCandidates.size(),
			order2Paths, order3Paths, now - lastLatePathMoveMillis,
			now - latePathBuildStartMillis, latePathBuildNanos / 1.0e6);
		latePathCandidates.clear();
	}

	private void stepDeepPhase(ClientLevel level, Vec3 listener,
							   CinemaClientConfig config) {
		if (audio == null || !config.lateReverb() || !latePathFastApplied
			|| latePathDeepApplied) {
			return;
		}
		long now = System.currentTimeMillis();
		if (latePathDeepSession == null) {
			LatePathRequest request = nextDeepRequest();
			if (request == null) {
				finishDeepPhase(now);
				return;
			}
			latePathDeepRequest = request;
			latePathDeepSession = new ImageSourcePathSolver.Session(
				request.planes(), request.channel(), pointOf(request.source()),
				pointOf(listener), ReflectionProbe.of(level),
				LATE_PATH_DEEP_ORDER, MAX_DEEP_REFLECTION_PATHS,
				LATE_PATH_DEEP_ORDER, ImageSourcePathSolver.MAX_VISITS);
		}
		long stepStart = System.nanoTime();
		boolean done = latePathDeepSession.step(
			LATE_PATH_STEP_BUDGET_NANOS);
		latePathDeepNanos += System.nanoTime() - stepStart;
		if (!done) {
			return;
		}
		ImageSourcePathSolver.SolveStats stats = latePathDeepSession.stats();
		LatePathRequest request = latePathDeepRequest;
		for (ReflectionPathTap path : stats.paths()) {
			LateReflectionBus.Tap tap = lateTapFromPath(path,
				request.speaker(), config, config.airTemperatureC(),
				config.airHumidityPercent(),
				AirAbsorption.REFERENCE_PRESSURE_KPA);
			if (tap != null) {
				latePathDeepTaps.add(tap);
			}
		}
		latePathDeepSession = null;
		latePathDeepRequest = null;
		if (latePathDeepNextChannel >= CinemaAudioEngine.CHANNELS) {
			finishDeepPhase(now);
		}
	}

	private LatePathRequest nextDeepRequest() {
		while (latePathDeepNextChannel < CinemaAudioEngine.CHANNELS) {
			LatePathRequest request = latePathRequests[latePathDeepNextChannel++];
			if (request != null && !request.speaker().isRemoved()) {
				return request;
			}
		}
		return null;
	}

	private void finishDeepPhase(long now) {
		List<LateReflectionBus.Tap> combined = new ArrayList<>(
			latePathFastTaps.size() + latePathDeepTaps.size());
		combined.addAll(latePathFastTaps);
		combined.addAll(latePathDeepTaps);
		currentLateTaps = List.copyOf(combined);
		engineTaps(currentLateTaps);
		latePathDeepApplied = true;
		Cinema.LOGGER.info(
			"cinema receiver {}: geometric late bus steady deep order=4 "
				+ "busTaps={} fastTaps={} deepTaps={}, stable={} ms, "
				+ "deepUpgrade={} ms, solve={} ms",
			receiverPos.toShortString(), currentLateTaps.size(),
			latePathFastTaps.size(), latePathDeepTaps.size(),
			now - lastLatePathMoveMillis, now - latePathDeepStartMillis,
			latePathDeepNanos / 1.0e6);
		latePathDeepTaps.clear();
	}

	private void engineTaps(List<LateReflectionBus.Tap> taps) {
		CinemaAudioEngine engine = audio;
		if (engine != null) {
			engine.setLateReflectionTaps(taps);
		}
	}

	private void resetLatePathBuild() {
		latePathBuildActive = false;
		latePathFastApplied = false;
		latePathDeepApplied = false;
		latePathCandidates.clear();
		latePathFastNextChannel = 0;
		latePathFastSession = null;
		latePathFastRequest = null;
		latePathFastTaps = List.of();
		latePathDeepTaps.clear();
		latePathDeepNextChannel = 0;
		latePathDeepSession = null;
		latePathDeepRequest = null;
		premiumOrder3Sources = List.of();
		latePathBuildStartMillis = 0L;
		latePathBuildNanos = 0L;
		latePathDeepStartMillis = 0L;
		latePathDeepNanos = 0L;
	}

	private MinimumPhaseFir commonFilter(int channel,
										 CommonAcousticFirProfile profile) {
		MinimumPhaseFir design = CommonAcousticFirService.get(profile);
		if (design != null) {
			if (channel >= 0 && channel < channelFilters.length) {
				channelFilters[channel] = design;
			}
			return design;
		}
		if (channel >= 0 && channel < channelFilters.length) {
			return channelFilters[channel];
		}
		return MinimumPhaseFir.FLAT;
	}

	private static SpeakerDataProfile speakerProfile(SpeakerBlockEntity speaker) {
		SpeakerDataRegistry.SpeakerSettings settings =
			SpeakerDataAssignments.settingsFor(speaker);
		return SpeakerDataRegistry.find(speaker.type(), settings.profileId());
	}

	private static float speakerGain(SpeakerBlockEntity speaker) {
		double db = speaker.levelDb() + SpeakerDataAssignments.settingsFor(speaker).gainDb();
		double linear = Math.pow(10.0, db / 20.0);
		double max = Math.pow(10.0, SpeakerBlockEntity.MAX_LEVEL_DB / 20.0);
		return (float) Math.max(0.0, Math.min(max, linear));
	}

	private static float overallGain(CommonAcousticFirProfile profile) {
		double db = CommonAcousticFirDesigner.overallDb(profile);
		return (float) Math.pow(10.0, db / 20.0);
	}

	private static float[] toListenerSpace(Vec3 relative, Vec3 forward) {
		Vec3 up = new Vec3(0.0, 1.0, 0.0);
		Vec3 right = forward.cross(up);
		if (right.lengthSqr() < 1.0e-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		} else {
			right = right.normalize();
		}
		return new float[]{
			(float) relative.dot(right),
			(float) relative.y,
			(float) -relative.dot(forward),
		};
	}

	private static Point3 pointOf(Vec3 point) {
		return new Point3(point.x, point.y, point.z);
	}

	private static double expectedPosition(ClientLevel level, AvReceiverBlockEntity receiver) {
		double expected = receiver.positionSeconds();
		if (receiver.state() == AvReceiverBlockEntity.State.PLAYING) {
			long elapsed = Math.max(0L, level.getGameTime() - receiver.stateChangedGameTime());
			expected += elapsed / 20.0;
		}
		if (receiver.durationSeconds() > 0.0) {
			expected = Math.min(expected, receiver.durationSeconds());
		}
		return Math.max(0.0, expected);
	}
}
