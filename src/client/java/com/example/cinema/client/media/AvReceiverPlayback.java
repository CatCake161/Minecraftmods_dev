package com.example.cinema.client.media;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.CinemaLog;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirDesigner;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirProfile;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirService;
import com.example.cinema.client.media.audio.acoustics.geometry.ImageSourcePathSolver;
import com.example.cinema.client.media.audio.acoustics.geometry.ImageSourceSolver;
import com.example.cinema.client.media.audio.acoustics.geometry.MirrorPlane;
import com.example.cinema.client.media.audio.acoustics.geometry.MirrorPlaneDiscovery;
import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.geometry.ReflectionProbe;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayBudget;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayLimit;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionPathTap;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionTap;
import com.example.cinema.client.media.audio.acoustics.propagation.AirAbsorption;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionScanner;
import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;
import com.example.cinema.client.media.audio.acoustics.room.AcousticSurface;
import com.example.cinema.client.media.audio.acoustics.scene.AcousticScene;
import com.example.cinema.client.media.audio.acoustics.scene.RoomAcousticSnapshot;
import com.example.cinema.client.media.audio.dsp.MinimumPhaseFir;
import com.example.cinema.client.media.audio.playback.CinemaAudioEngine;
import com.example.cinema.client.media.audio.playback.ReflectionSource;
import com.example.cinema.client.media.audio.reflection.LateReflectionBus;
import com.example.cinema.client.media.audio.reflection.LateReflectionSettings;
import com.example.cinema.client.media.audio.reflection.LateReflectionTarget;
import com.example.cinema.client.media.audio.reflection.Vbap;
import com.example.cinema.client.media.audio.speaker.SpeakerDataAssignments;
import com.example.cinema.client.media.audio.speaker.SpeakerDataProfile;
import com.example.cinema.client.media.audio.speaker.SpeakerDataRegistry;
import com.example.cinema.client.media.audio.speaker.SpeakerDirectivityService;
import com.example.cinema.client.media.audio.speaker.SubwooferBand;
import com.example.cinema.client.media.video.CinemaVideoTexture;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
	private static final int MAX_FIRST_ORDER_REFLECTIONS = 64;
	private static final int FIRST_ORDER_REFLECTIONS_PER_SPEAKER = 5;
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
	private static final long LATE_REFLECTION_UPDATE_MILLIS = 50L;
	private static final double MAX_CHANNEL_GAIN =
		Math.pow(10.0, 12.0 / 20.0);
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
	private int physicalSpeakers;
	private float[][] physicalSpeakerPositions =
		new float[CinemaAudioEngine.CHANNELS][];
	private int[] physicalSpeakerChannels = new int[0];
	private final Map<BlockPos, MinimumPhaseFir> sourceFilterCache = new HashMap<>();
	private long lastReflectionUpdateMillis;
	private long lastLateReflectionUpdateMillis;
	private long lastLateReflectionLogMillis;
	private long lastReflectionRoomVersion = Long.MIN_VALUE;
	private Vec3 lastReflectionListener;
	private final Map<String, MinimumPhaseFir> reflectionFilters = new HashMap<>();
	private int lastReflectionLogCount = -1;
	private long lastReflectionLogMillis;
	private final LatePathRequest[] latePathRequests =
		new LatePathRequest[CinemaAudioEngine.MAX_DIRECT_SOURCES];
	private final List<LatePathCandidate> latePathCandidates = new ArrayList<>();
	private int latePathFastNextSource;
	private ImageSourcePathSolver.Session latePathFastSession;
	private LatePathRequest latePathFastRequest;
	private List<LateReflectionBus.Tap> latePathFastTaps = List.of();
	private final List<LateReflectionBus.Tap> latePathDeepTaps = new ArrayList<>();
	private int latePathDeepNextSource;
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
	private ReflectionDelayLimit latePathDelayLimit =
		ImageSourcePathSolver.DEFAULT_DELAY_LIMIT;
	private boolean latePathBuildActive;
	private boolean latePathFastApplied;
	private boolean latePathDeepApplied;
	private long latePathBuildStartMillis;
	private long latePathBuildNanos;
	private long latePathDeepStartMillis;
	private long latePathDeepNanos;
	private int latePathFastDelayRejected;
	private int latePathDeepDelayRejected;
	private double latePathMoveEma;
	private boolean lastLateReflectionEnabled;
	private long lastSpeakerSettingsHash = Long.MIN_VALUE;

	private record LatePathRequest(SpeakerBlockEntity speaker, int channel,
								   List<MirrorPlane> planes, Vec3 source) {
	}

	private record SpeakerRoute(SpeakerBlockEntity speaker, int channel,
								float[] position, float gain,
								MinimumPhaseFir filter, float occlusionGain,
								float lateSendGain, double distanceSqr) {
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

	/** Number of physical linked speakers currently driving direct sources. */
	public int physicalSpeakers() {
		return physicalSpeakers;
	}

	/** Current direct-source count in the engine. */
	public int directSources() {
		CinemaAudioEngine engine = audio;
		return engine == null ? 0 : engine.directSourceCount();
	}

	/** Current reflection-source count in the engine. */
	public int reflectionSources() {
		CinemaAudioEngine engine = audio;
		return engine == null ? 0 : engine.reflectionSourceCount();
	}

	/** Current output-limiter gain reduction in dB, or {@code 0} when idle. */
	public float limiterReductionDb() {
		CinemaAudioEngine engine = audio;
		return engine == null ? 0.0f : engine.limiterReductionDb();
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
			CinemaLog.warn(CinemaLog.RECEIVER, "{}: playback failed for {}: {}",
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
		physicalSpeakers = 0;
		lastReflectionUpdateMillis = 0L;
		lastLateReflectionUpdateMillis = 0L;
		lastLateReflectionLogMillis = 0L;
		lastReflectionRoomVersion = Long.MIN_VALUE;
		lastReflectionListener = null;
		reflectionFilters.clear();
		sourceFilterCache.clear();
		lastReflectionLogCount = -1;
		lastReflectionLogMillis = 0L;
		Arrays.fill(latePathRequests, null);
		latePathCandidates.clear();
		latePathFastNextSource = 0;
		latePathFastSession = null;
		latePathFastRequest = null;
		latePathFastTaps = List.of();
		latePathDeepTaps.clear();
		latePathDeepNextSource = 0;
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
		latePathFastDelayRejected = 0;
		latePathDeepDelayRejected = 0;
		lastLateReflectionEnabled = false;
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
		CinemaLog.info(CinemaLog.RECEIVER, "{}: opened {} at {} s",
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
			CinemaLog.info(CinemaLog.RECEIVER, "{}: resynced {} to {} s in {} ms",
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
			CinemaLog.warn(CinemaLog.RECEIVER, "{}: could not open {}: {}",
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
			CinemaLog.warn(CinemaLog.RECEIVER, "{}: media unavailable: {}",
				receiverPos.toShortString(), reason);
		}
	}

	/**
	 * The listener is always the local player's actual ears and head direction.
	 * There is no Seat/sweet-spot calibration and no screen-locked correction:
	 * walking or looking away therefore keeps the physical speaker directions
	 * correct instead of re-centering the sound field on a fixed seat.
	 *
	 * <p>Every linked speaker gets its own direct source, so one 5.1 channel can
	 * drive several physical speakers. Reflections and the late bus currently
	 * use the nearest anchor speaker per channel.
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

		CinemaClientConfig config = CinemaClientConfig.get();
		boolean airAbsorption = config.airAbsorption();
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		List<SpeakerRoute> candidates = new ArrayList<>();
		for (BlockPos pos : receiver.speakerTargets()) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockEntity blockEntity = level.getBlockEntity(pos);
			if (!(blockEntity instanceof SpeakerBlockEntity speaker)) {
				continue;
			}
			int channel = speaker.role().ordinal() - 1;
			if (channel < 0 || channel >= CinemaAudioEngine.CHANNELS) {
				continue;
			}
			Vec3 center = speaker.acousticFrontPosition();
			Vec3 relative = center.subtract(listener);
			double distanceSqr = relative.lengthSqr();
			if (distanceSqr < 0.01) {
				continue;
			}
			candidates.add(buildSpeakerRoute(level, speaker, channel, listener,
				forward, config, airAbsorption, temperatureC, humidityPercent,
				pressureKpa, center, relative, distanceSqr));
		}

		SpeakerRoute[] anchors = selectAnchors(candidates);
		int physicalCap = CinemaAudioEngine.MAX_DIRECT_SOURCES;
		List<SpeakerRoute> selected = selectRoutes(candidates, anchors, physicalCap);
		int directCount = selected.size();
		float[][] positions = new float[directCount][];
		float[] gains = new float[directCount];
		MinimumPhaseFir[] filters = new MinimumPhaseFir[directCount];
		float[] occlusionGains = new float[directCount];
		float[] lateSendGains = new float[directCount];
		int[] sourceChannels = new int[directCount];
		int index = 0;
		for (SpeakerRoute route : selected) {
			positions[index] = route.position();
			gains[index] = route.gain();
			filters[index] = route.filter();
			occlusionGains[index] = route.occlusionGain();
			lateSendGains[index] = route.lateSendGain();
			sourceChannels[index] = route.channel();
			index++;
		}

		float[][] speakerPositions = new float[selected.size()][];
		int[] speakerChannels = new int[selected.size()];
		for (int i = 0; i < selected.size(); i++) {
			SpeakerRoute route = selected.get(i);
			speakerPositions[i] = route.position();
			speakerChannels[i] = route.channel();
		}
		int routed = 0;
		for (SpeakerRoute anchor : anchors) {
			if (anchor != null) {
				routed++;
			}
		}
		physicalChannels = routed;
		physicalSpeakers = selected.size();
		physicalSpeakerPositions = speakerPositions;
		physicalSpeakerChannels = speakerChannels;
		engine.setChannelLayout(sourceChannels, positions, gains, filters,
			occlusionGains, lateSendGains);
		updateLateReflection(selected, config);
		updateReflections(level, listener, forward, selected, config);
		stepLatePhase(level, listener, forward, config);
	}

	private SpeakerRoute buildSpeakerRoute(ClientLevel level,
										   SpeakerBlockEntity speaker,
										   int channel, Vec3 listener,
										   Vec3 forward,
										   CinemaClientConfig config,
										   boolean airAbsorption,
										   double temperatureC,
										   double humidityPercent,
										   double pressureKpa, Vec3 center,
										   Vec3 relative,
										   double distanceSqr) {
		float[] position = toListenerSpace(relative, forward);
		SpeakerDirectivityService.Angles angles = SpeakerDirectivityService.angles(
			center, speaker.acousticForward(), listener);
		double distanceMeters = AcousticScale.blocksToMeters(
			Math.sqrt(distanceSqr));
		OcclusionResult occlusion = OcclusionScanner.scan(level, center, listener);
		SpeakerDataProfile profile = speakerProfile(speaker);
		SubwooferBand subwooferBand = subwooferBand(speaker, profile);
		CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.direct(
			profile, angles.horizontal(), angles.vertical(), distanceMeters,
			airAbsorption, temperatureC, humidityPercent, pressureKpa,
			occlusion, subwooferBand);
		MinimumPhaseFir filter = sourceFilter(speaker.getBlockPos(), firProfile);
		double directSourceGain = Math.min(MAX_CHANNEL_GAIN,
			Math.max(0.0, speakerGain(speaker) * overallGain(firProfile)));
		double referenceHz = CommonAcousticFirDesigner.referenceHz(firProfile);
		double relativeDirectivity = profile == null ? 1.0
			: Math.pow(10.0, profile.gainDb(angles.horizontal(),
				angles.vertical(), referenceHz) / 20.0);
		double powerIntegral = profile == null ? 4.0 * Math.PI
			: profile.directivityPowerIntegral(referenceHz);
		double lateSendGain = directSourceGain
			/ Math.max(1.0e-4, relativeDirectivity)
			* Math.sqrt(powerIntegral);
		return new SpeakerRoute(speaker, channel, position,
			(float) directSourceGain, filter, (float) occlusion.linearGain(),
			(float) Math.min(MAX_LATE_SEND_GAIN, Math.max(0.0, lateSendGain)),
			distanceSqr);
	}

	private MinimumPhaseFir sourceFilter(BlockPos key,
										 CommonAcousticFirProfile profile) {
		MinimumPhaseFir design = CommonAcousticFirService.get(profile);
		if (design != null) {
			if (key != null) {
				sourceFilterCache.put(key, design);
			}
			return design;
		}
		return key == null ? MinimumPhaseFir.FLAT
			: sourceFilterCache.getOrDefault(key, MinimumPhaseFir.FLAT);
	}

	private static SpeakerRoute[] selectAnchors(List<SpeakerRoute> candidates) {
		SpeakerRoute[] anchors = new SpeakerRoute[CinemaAudioEngine.CHANNELS];
		for (SpeakerRoute route : candidates) {
			SpeakerRoute current = anchors[route.channel()];
			if (current == null || route.distanceSqr() < current.distanceSqr()) {
				anchors[route.channel()] = route;
			}
		}
		return anchors;
	}

	private static List<SpeakerRoute> selectRoutes(
		List<SpeakerRoute> candidates, SpeakerRoute[] anchors, int cap) {
		if (candidates.size() <= cap) {
			return candidates;
		}
		List<SpeakerRoute> selected = new ArrayList<>(cap);
		Set<SpeakerRoute> chosen = new HashSet<>();
		// Put one anchor per channel first. This also gives the engine a stable
		// per-channel reference position for the wet sources.
		for (SpeakerRoute anchor : anchors) {
			if (anchor != null && selected.size() < cap && chosen.add(anchor)) {
				selected.add(anchor);
			}
		}
		List<SpeakerRoute> rest = new ArrayList<>(candidates);
		rest.sort(Comparator.comparingDouble(SpeakerRoute::distanceSqr));
		for (SpeakerRoute route : rest) {
			if (selected.size() >= cap) {
				break;
			}
			if (chosen.add(route)) {
				selected.add(route);
			}
		}
		return selected;
	}

	/**
	 * Computes the room-constant calibration for the geometric late-reflection
	 * bus. The bus taps already carry path gain, speaker trim and reflection
	 * FIR level; this method only supplies the wet-source gain offset. The
	 * source-power calibration is retained from the former FDN path so removing
	 * the FDN does not change the geometric late-bus loudness.
	 */
	private void updateLateReflection(List<SpeakerRoute> routes,
									  CinemaClientConfig config) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastLateReflectionUpdateMillis
			< LATE_REFLECTION_UPDATE_MILLIS) {
			return;
		}
		lastLateReflectionUpdateMillis = now;
		RoomAcousticSnapshot snapshot = AcousticScene.snapshot();
		AcousticRoom room = snapshot == null ? null : snapshot.room();
		double[] directGains = new double[CinemaAudioEngine.CHANNELS];
		double[] powerGains = new double[CinemaAudioEngine.CHANNELS];
		double[] distances = new double[CinemaAudioEngine.CHANNELS];
		Arrays.fill(distances, Double.MAX_VALUE);
		for (SpeakerRoute route : routes) {
			int channel = route.channel();
			directGains[channel] += route.gain() * route.occlusionGain();
			powerGains[channel] += route.lateSendGain() * route.occlusionGain();
			double distance = AcousticScale.blocksToMeters(
				Math.sqrt(route.distanceSqr()));
			if (distance < distances[channel]) {
				distances[channel] = distance;
			}
		}
		List<LateReflectionTarget.Source> sources = new ArrayList<>();
		for (int channel = 0; channel < CinemaAudioEngine.CHANNELS; channel++) {
			if (distances[channel] == Double.MAX_VALUE) {
				continue;
			}
			sources.add(new LateReflectionTarget.Source(directGains[channel],
				powerGains[channel], distances[channel]));
		}
		LateReflectionTarget.Result target =
			LateReflectionTarget.compute(room, sources);
		double trim = config.lateTrimDb();
		LateReflectionSettings settings = new LateReflectionSettings(
			config.lateReverb(), target.sourceGainDb() + trim);
		engine.setLateReflection(settings);
		if (now - lastLateReflectionLogMillis >= 5_000L) {
			lastLateReflectionLogMillis = now;
			CinemaLog.info(CinemaLog.RECEIVER, "{}: late reflections R={} m2 rc={} m "
					+ "target={} dB sourceGain={} dB trim={} dB effective={} dB",
				receiverPos.toShortString(),
				formatMetric(target.roomConstant(), "%.1f"),
				formatMetric(target.criticalDistanceMeters(), "%.2f"),
				formatDb(target.targetWetGainDb()),
				formatDb(target.sourceGainDb()),
				formatDb(trim),
				formatDb(settings.wetGainDb()));
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
	 * Room/order-aware geometric-delay budget. The path bound is
	 * {@code (order + 1) * roomDiagonal / c}; it is clamped by the engine's
	 * history capacity and hard ceiling. Without a usable room box, fall back
	 * to that same hard/history cap instead of rejecting every path.
	 */
	private static ReflectionDelayLimit delayLimit(AcousticRoom room) {
		return ReflectionDelayBudget.forRoom(room,
			CinemaAudioEngine.maxReflectionDelaySeconds(),
			CinemaAudioEngine.historySafeDelaySeconds());
	}

	/**
	 * Recomputes first-order reflection taps at ~5 Hz from the receiver-linked
	 * physical speakers. Flood-fill boundary patches are optional seeds;
	 * speaker-centered rays work without a valid room box. The engine receives
	 * listener-relative image-source positions (not reflection points) and one
	 * 4096-tap FIR carrying the full reflection frequency shape.
	 *
	 * <p>The tap gain already carries {@code sqrt(1-alpha_mid)/max(L,1)} and the
	 * combined broadband occlusion gain. Speaker trim and the FIR's
	 * on-axis/directivity level at 1 kHz are applied here. Air absorption,
	 * material reflection and occlusion low-pass are all inside the same
	 * full-band FIR, so no term is applied twice.
	 */
	private void updateReflections(ClientLevel level, Vec3 listener, Vec3 forward,
								   List<SpeakerRoute> speakers,
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
		ReflectionDelayLimit reflectionLimit = delayLimit(room);
		boolean moved = lastReflectionListener == null
			|| listener.distanceToSqr(lastReflectionListener)
				> REFLECTION_MOVE_THRESHOLD_SQR;
		boolean speakerSettingsChanged = updateSpeakerSettingsHash(speakers);
		boolean lateReflectionToggled =
			config.lateReverb() != lastLateReflectionEnabled;
		if (now - lastReflectionUpdateMillis < REFLECTION_UPDATE_MILLIS
			&& roomVersion == lastReflectionRoomVersion && !moved
			&& !speakerSettingsChanged && !lateReflectionToggled) {
			return;
		}
		if (lateReflectionToggled) {
			// Rebuild immediately instead of waiting for the 5 Hz timer; this
			// also forces the order-2 taps to republish and avoids a stale or
			// empty late-bus state after the toggle.
			lastLateReflectionEnabled = config.lateReverb();
			lastLatePathListener = null;
			latePathRequestRoomVersion = Long.MIN_VALUE;
		}
		if (speakerSettingsChanged) {
			// Speaker level/profile gain is baked into the premium sources and
			// late-bus taps. Invalidate the steady-state order-2/3/4 build so
			// the next update republishes them with the new gain and profile.
			resetLatePathBuild();
			premiumOrder2Sources = List.of();
			premiumOrder3Sources = List.of();
			latePathRequestRoomVersion = Long.MIN_VALUE;
			lastLatePathListener = null;
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
		int order2DelayRejected = 0;
		boolean lateOrder2Stable = config.lateReverb() && latePathFastApplied
			&& lastLatePathListener != null
			&& listener.distanceToSqr(lastLatePathListener)
				<= LATE_PATH_MOVE_THRESHOLD_SQR
			&& roomVersion == latePathRequestRoomVersion;
		Arrays.fill(latePathRequests, null);
		Map<String, SpeakerRoute> routesByKey = new HashMap<>();
		for (int sourceIndex = 0; sourceIndex < speakers.size(); sourceIndex++) {
			SpeakerRoute route = speakers.get(sourceIndex);
			SpeakerBlockEntity speaker = route.speaker();
			int channel = route.channel();
			String sourceKey = sourceKey(speaker);
			routesByKey.put(sourceKey, route);
			Vec3 center = speaker.acousticFrontPosition();
			List<MirrorPlane> planes = MirrorPlaneDiscovery.INSTANCE.discover(
				level, center, roomVersion, seeds);
			if (config.lateReverb()) {
				latePathRequests[sourceIndex] = new LatePathRequest(speaker,
					channel, planes, center);
			}
			taps.addAll(ImageSourceSolver.solve(planes, channel, sourceKey,
				pointOf(center), pointOf(listener), probe, previousKeys,
				reflectionLimit.maxDelaySeconds(1)));
			if (config.lateReverb() && !lateOrder2Stable) {
				// Order-2 paths are published immediately. The order-3/4
				// upgrades run incrementally and replace this set only after
				// their phase completes.
				ImageSourcePathSolver.SolveStats pathStats =
					ImageSourcePathSolver.solveWithStats(planes, channel, sourceKey,
						pointOf(center), pointOf(listener), probe, 2,
						MAX_FAST_REFLECTION_PATHS, reflectionLimit);
				order2DelayRejected += pathStats.delayRejected();
				for (ReflectionPathTap path : pathStats.paths()) {
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
				premium2Keys, config, temperatureC, humidityPercent, pressureKpa,
				listener, forward, reflectionLimit);
		}
		updateLatePathBus(listener, config, order2BusTaps, roomVersion,
			reflectionLimit);
		List<ReflectionTap> limited = selectFirstOrderTaps(taps, previousKeys);
		Set<String> currentKeys = new HashSet<>();
		List<ReflectionSource> sources = new ArrayList<>(limited.size());
		for (ReflectionTap tap : limited) {
			SpeakerRoute route = routesByKey.get(tap.sourceKey());
			if (route == null) {
				continue;
			}
			SpeakerBlockEntity speaker = route.speaker();
			int channel = route.channel();
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
			SpeakerDataProfile profile = speakerProfile(speaker);
			CommonAcousticFirProfile firProfile = CommonAcousticFirProfile.reflection(
				profile, angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(tap.pathLength()),
				config.airAbsorption(), temperatureC,
				humidityPercent, pressureKpa, tap.material(), tap.occlusion(),
				subwooferBand(speaker, profile));
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
			CinemaLog.info(CinemaLog.RECEIVER, "{}: {} reflection taps (first={}, premium2={}, "
					+ "premium3={}, candidates={}, surfaces={}, room={}, "
					+ "size={}, maxDelay={} ms, order2Dropped={})",
				receiverPos.toShortString(), allSources.size(), sources.size(),
				premiumOrder2Sources.size(), premiumOrder3Sources.size(),
				taps.size(), seeds.size(),
				snapshot == null ? "none" : Long.toString(snapshotVersion),
				room == null ? "none" : room.sizeLabel(),
				String.format(java.util.Locale.ROOT, "%.1f",
					reflectionLimit.maxDelaySeconds(4) * 1_000.0),
				order2DelayRejected);
		}
	}

	private LateReflectionBus.Tap lateTapFromPath(ReflectionPathTap path,
												 SpeakerBlockEntity speaker,
												 CinemaClientConfig config,
												 double temperatureC,
												 double humidityPercent,
												 double pressureKpa,
												 Vec3 listener,
												 Vec3 forward) {
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
		SpeakerDataProfile profile = speakerProfile(speaker);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(profile,
				angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(path.pathLength()),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, path.materials().get(0), path.occlusion(),
				subwooferBand(speaker, profile));
		// fromPath() already applies path.gain(); pass only the speaker/FIR
		// level so the total is path.gain * speakerGain * overallGain.
		double gain = speakerGain(speaker) * overallGain(firProfile);
		Point3 lastPoint = path.reflectionPoints()
			.get(path.reflectionPoints().size() - 1);
		// VBAP needs the direction from the listener to the virtual source.
		// The propagation direction (last reflection point -> listener) is the
		// negative of this vector and would mirror left/right and front/back.
		Vec3 arrival = new Vec3(lastPoint.x() - listener.x,
			lastPoint.y() - listener.y, lastPoint.z() - listener.z);
		float[] direction = toListenerSpace(arrival, forward);
		Vbap.Weights weights = Vbap.pan(direction, physicalSpeakerPositions,
			physicalSpeakerChannels);
		return LateReflectionBus.Tap.fromPath(path, gain, direction,
			weights.channels(), weights.weights());
	}

	private static List<ReflectionTap> selectFirstOrderTaps(
		List<ReflectionTap> taps, Set<String> previousKeys) {
		Map<String, List<ReflectionTap>> bySource = new LinkedHashMap<>();
		for (ReflectionTap tap : taps) {
			bySource.computeIfAbsent(tap.sourceKey(), key -> new ArrayList<>())
				.add(tap);
		}
		List<ReflectionTap> selected = new ArrayList<>();
		Set<String> chosen = new HashSet<>();
		for (List<ReflectionTap> group : bySource.values()) {
			for (ReflectionTap tap : ImageSourceSolver.strongest(group,
				FIRST_ORDER_REFLECTIONS_PER_SPEAKER, previousKeys)) {
				if (selected.size() >= MAX_FIRST_ORDER_REFLECTIONS) {
					return selected;
				}
				if (chosen.add(tap.key())) {
					selected.add(tap);
				}
			}
		}
		for (ReflectionTap tap : ImageSourceSolver.strongest(taps,
			MAX_FIRST_ORDER_REFLECTIONS, previousKeys)) {
			if (selected.size() >= MAX_FIRST_ORDER_REFLECTIONS) {
				break;
			}
			if (chosen.add(tap.key())) {
				selected.add(tap);
			}
		}
		return selected;
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
		double pressureKpa, Vec3 listener, Vec3 forward,
		ReflectionDelayLimit delayLimit) {
		List<LateReflectionBus.Tap> taps = new ArrayList<>();
		for (LatePathCandidate candidate : candidates) {
			if (excluded.contains(candidate.path().pathKey())
				|| candidate.path().delaySeconds()
					> delayLimit.maxDelaySeconds(candidate.path().order())) {
				continue;
			}
			LateReflectionBus.Tap tap = lateTapFromPath(candidate.path(),
				candidate.speaker(), config, temperatureC, humidityPercent,
				pressureKpa, listener, forward);
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
		SpeakerDataProfile profile = speakerProfile(speaker);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(profile,
				angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(path.pathLength()),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, path.materials().get(0), path.occlusion(),
				subwooferBand(speaker, profile));
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
								   long roomVersion,
								   ReflectionDelayLimit reflectionLimit) {
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
			latePathDelayLimit = reflectionLimit;
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
			stepDeepPhase(level, listener, forward, config);
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
				request.planes(), request.channel(), sourceKey(request.speaker()),
				pointOf(request.source()), pointOf(listener),
				ReflectionProbe.of(level), LATE_PATH_FAST_ORDER,
				MAX_FAST_REFLECTION_PATHS, 2,
				ImageSourcePathSolver.MAX_VISITS, latePathDelayLimit);
		}
		long stepStart = System.nanoTime();
		boolean done = latePathFastSession.step(LATE_PATH_STEP_BUDGET_NANOS);
		latePathBuildNanos += System.nanoTime() - stepStart;
		if (!done) {
			return;
		}
		ImageSourcePathSolver.SolveStats stats = latePathFastSession.stats();
		latePathFastDelayRejected += stats.delayRejected();
		LatePathRequest request = latePathFastRequest;
		for (ReflectionPathTap path : stats.paths()) {
			latePathCandidates.add(new LatePathCandidate(path,
				request.speaker()));
		}
		latePathFastSession = null;
		latePathFastRequest = null;
		if (latePathFastNextSource >= latePathRequests.length) {
			finishFastPhase(listener, forward, config, now);
		}
	}

	private LatePathRequest nextFastRequest() {
		while (latePathFastNextSource < latePathRequests.length) {
			LatePathRequest request = latePathRequests[latePathFastNextSource++];
			if (request != null && !request.speaker().isRemoved()) {
				return request;
			}
		}
		return null;
	}

	private void finishFastPhase(Vec3 listener, Vec3 forward,
								 CinemaClientConfig config, long now) {
		if (!latePathBuildActive && latePathCandidates.isEmpty()) {
			// lateReverb can be toggled on between reflection updates. Until the
			// next update publishes latePathRequests there is nothing to solve;
			// finishing here would publish an empty "steady" state and compute
			// upgrade against the zero epoch sentinel.
			return;
		}
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
			config, temperatureC, humidityPercent, pressureKpa, listener,
			forward, latePathDelayLimit));
		currentLateTaps = latePathFastTaps;
		engineTaps(currentLateTaps);
		latePathBuildActive = false;
		latePathFastApplied = true;
		latePathDeepApplied = false;
		latePathDeepNextSource = 0;
		latePathDeepTaps.clear();
		latePathDeepStartMillis = now;
		latePathDeepNanos = 0L;
		latePathDeepDelayRejected = 0;
		int order2Paths = 0;
		int order3Paths = 0;
		for (LatePathCandidate candidate : latePathCandidates) {
			if (candidate.path().order() == 2) {
				order2Paths++;
			} else if (candidate.path().order() == 3) {
				order3Paths++;
			}
		}
		CinemaLog.info(CinemaLog.RECEIVER, "{}: geometric late bus steady fast order=3 "
				+ "busTaps={} requested={} premium2={} premium3={} paths={} "
				+ "(order2={}, order3={}), maxDelay={} ms, dropped={}, "
				+ "stable={} ms, upgrade={} ms, solve={} ms",
			receiverPos.toShortString(), effectiveBusTaps(currentLateTaps),
			currentLateTaps.size(), premium2.size(), premium3.size(),
			latePathCandidates.size(), order2Paths, order3Paths,
			String.format(java.util.Locale.ROOT, "%.1f",
				latePathDelayLimit.maxDelaySeconds(3) * 1_000.0),
			latePathFastDelayRejected,
			now - lastLatePathMoveMillis,
			latePathBuildStartMillis > 0L ? now - latePathBuildStartMillis : 0L,
			latePathBuildNanos / 1.0e6);
		latePathCandidates.clear();
	}

	private void stepDeepPhase(ClientLevel level, Vec3 listener, Vec3 forward,
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
				request.planes(), request.channel(), sourceKey(request.speaker()),
				pointOf(request.source()), pointOf(listener),
				ReflectionProbe.of(level), LATE_PATH_DEEP_ORDER,
				MAX_DEEP_REFLECTION_PATHS,
				LATE_PATH_DEEP_ORDER, ImageSourcePathSolver.MAX_VISITS,
				latePathDelayLimit);
		}
		long stepStart = System.nanoTime();
		boolean done = latePathDeepSession.step(
			LATE_PATH_STEP_BUDGET_NANOS);
		latePathDeepNanos += System.nanoTime() - stepStart;
		if (!done) {
			return;
		}
		ImageSourcePathSolver.SolveStats stats = latePathDeepSession.stats();
		latePathDeepDelayRejected += stats.delayRejected();
		LatePathRequest request = latePathDeepRequest;
		for (ReflectionPathTap path : stats.paths()) {
			if (path.delaySeconds()
				> latePathDelayLimit.maxDelaySeconds(path.order())) {
				continue;
			}
			LateReflectionBus.Tap tap = lateTapFromPath(path,
				request.speaker(), config, config.airTemperatureC(),
				config.airHumidityPercent(),
				AirAbsorption.REFERENCE_PRESSURE_KPA, listener, forward);
			if (tap != null) {
				latePathDeepTaps.add(tap);
			}
		}
		latePathDeepSession = null;
		latePathDeepRequest = null;
		if (latePathDeepNextSource >= latePathRequests.length) {
			finishDeepPhase(now);
		}
	}

	private LatePathRequest nextDeepRequest() {
		while (latePathDeepNextSource < latePathRequests.length) {
			LatePathRequest request = latePathRequests[latePathDeepNextSource++];
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
		CinemaLog.info(CinemaLog.RECEIVER, "{}: geometric late bus steady deep order=4 "
				+ "busTaps={} requested={} fastTaps={} deepTaps={}, "
				+ "maxDelay={} ms, dropped={}, "
				+ "stable={} ms, deepUpgrade={} ms, solve={} ms",
			receiverPos.toShortString(), effectiveBusTaps(currentLateTaps),
			currentLateTaps.size(), latePathFastTaps.size(),
			latePathDeepTaps.size(),
			String.format(java.util.Locale.ROOT, "%.1f",
				latePathDelayLimit.maxDelaySeconds(4) * 1_000.0),
			latePathDeepDelayRejected,
			now - lastLatePathMoveMillis,
			latePathDeepStartMillis > 0L
				? now - latePathDeepStartMillis : 0L,
			latePathDeepNanos / 1.0e6);
		latePathDeepTaps.clear();
	}

	/**
	 * Mirrors {@code LateReflectionBus.setTaps}: invalid taps are discarded and
	 * the bus keeps only its strongest {@code LATE_BUS_MAX_TAPS}. Reporting the
	 * published list size alone hides deep taps that never reach the bus.
	 */
	private static int effectiveBusTaps(List<LateReflectionBus.Tap> taps) {
		if (taps == null || taps.isEmpty()) {
			return 0;
		}
		double maxDelay = CinemaAudioEngine.lateBusMaxDelaySeconds();
		int valid = 0;
		for (LateReflectionBus.Tap tap : taps) {
			if (tap != null && tap.gain() > 0.0
				&& tap.delaySeconds() < maxDelay) {
				valid++;
			}
		}
		return Math.min(valid, CinemaAudioEngine.LATE_BUS_MAX_TAPS);
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
		latePathFastNextSource = 0;
		latePathFastSession = null;
		latePathFastRequest = null;
		latePathFastTaps = List.of();
		latePathDeepTaps.clear();
		latePathDeepNextSource = 0;
		latePathDeepSession = null;
		latePathDeepRequest = null;
		premiumOrder3Sources = List.of();
		latePathBuildStartMillis = 0L;
		latePathBuildNanos = 0L;
		latePathDeepStartMillis = 0L;
		latePathDeepNanos = 0L;
	}

	private static String sourceKey(SpeakerBlockEntity speaker) {
		return speaker == null ? "" : Long.toString(speaker.getBlockPos().asLong());
	}

	private static SpeakerDataProfile speakerProfile(SpeakerBlockEntity speaker) {
		SpeakerDataRegistry.SpeakerSettings settings =
			SpeakerDataAssignments.settingsFor(speaker);
		return SpeakerDataRegistry.find(speaker.type(), settings.profileId());
	}

	private static SubwooferBand subwooferBand(SpeakerBlockEntity speaker,
											   SpeakerDataProfile profile) {
		if (speaker == null || profile == null || !profile.isSubwoofer()) {
			return null;
		}
		SpeakerDataRegistry.SpeakerSettings settings =
			SpeakerDataAssignments.settingsFor(speaker);
		return new SubwooferBand(settings.lowHz(), settings.highHz());
	}

	private static float speakerGain(SpeakerBlockEntity speaker) {
		double db = speaker.levelDb() + SpeakerDataAssignments.settingsFor(speaker).gainDb();
		double linear = Math.pow(10.0, db / 20.0);
		double max = Math.pow(10.0, SpeakerBlockEntity.MAX_LEVEL_DB / 20.0);
		return (float) Math.max(0.0, Math.min(max, linear));
	}

	/**
	 * Detects effective speaker level/profile changes. The late-bus and premium
	 * sources bake this gain into their published taps, so a steady-state
	 * listener would otherwise keep the old level until it moved.
	 */
	private boolean updateSpeakerSettingsHash(List<SpeakerRoute> speakers) {
		long hash = 0x9E3779B97F4A7C15L;
		for (SpeakerRoute route : speakers) {
			SpeakerBlockEntity speaker = route.speaker();
			long part = route.channel() + 1L;
			part = part * 31L + speaker.getBlockPos().asLong();
			SpeakerDataRegistry.SpeakerSettings settings =
				SpeakerDataAssignments.settingsFor(speaker);
			SpeakerDataProfile profile = SpeakerDataRegistry.find(
				speaker.type(), settings.profileId());
			Vec3 position = speaker.acousticFrontPosition();
			Vec3 forward = speaker.acousticForward();
			part = part * 31L + Double.doubleToLongBits(speaker.levelDb());
			part = part * 31L + settings.profileId().hashCode();
			part = part * 31L + (profile == null ? 0
				: System.identityHashCode(profile));
			part = part * 31L + Double.doubleToLongBits(settings.gainDb());
			part = part * 31L + Double.doubleToLongBits(settings.lowHz());
			part = part * 31L + Double.doubleToLongBits(settings.highHz());
			part = part * 31L + Double.doubleToLongBits(position.x);
			part = part * 31L + Double.doubleToLongBits(position.y);
			part = part * 31L + Double.doubleToLongBits(position.z);
			part = part * 31L + Double.doubleToLongBits(forward.x);
			part = part * 31L + Double.doubleToLongBits(forward.y);
			part = part * 31L + Double.doubleToLongBits(forward.z);
			hash = Long.rotateLeft(hash ^ part, 27) * 0x9E3779B97F4A7C15L;
		}
		if (hash == lastSpeakerSettingsHash) {
			return false;
		}
		lastSpeakerSettingsHash = hash;
		return true;
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
