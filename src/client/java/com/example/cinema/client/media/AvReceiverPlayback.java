package com.example.cinema.client.media;

import com.example.cinema.block.entity.AvReceiverBlockEntity;
import com.example.cinema.block.entity.SpeakerBlockEntity;
import com.example.cinema.CinemaLog;
import com.example.cinema.client.config.CinemaClientConfig;
import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirDesigner;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirProfile;
import com.example.cinema.client.media.audio.acousticfir.CommonAcousticFirService;
import com.example.cinema.client.media.audio.acousticfir.MaterialReflectionFilter;
import com.example.cinema.client.media.audio.acoustics.geometry.ImageSourcePathSolver;
import com.example.cinema.client.media.audio.acoustics.geometry.ImageSourceSolver;
import com.example.cinema.client.media.audio.acoustics.geometry.MirrorPlane;
import com.example.cinema.client.media.audio.acoustics.geometry.MirrorPlaneDiscovery;
import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.geometry.ReflectionProbe;
import com.example.cinema.client.media.audio.acoustics.path.DiffuseRaySampler;
import com.example.cinema.client.media.audio.acoustics.path.DirectPropagationDelay;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayBudget;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionDelayLimit;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionPathTap;
import com.example.cinema.client.media.audio.acoustics.path.ReflectionTap;
import com.example.cinema.client.media.audio.acoustics.propagation.AirAbsorption;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionScanner;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;
import com.example.cinema.client.media.audio.acoustics.room.AcousticSurface;
import com.example.cinema.client.media.audio.debug.SourceDebugPoint;
import com.example.cinema.client.media.audio.acoustics.scene.AcousticScene;
import com.example.cinema.client.media.audio.acoustics.scene.RoomAcousticSnapshot;
import com.example.cinema.client.media.audio.dsp.MinimumPhaseFir;
import com.example.cinema.client.media.audio.playback.CinemaAudioEngine;
import com.example.cinema.client.media.audio.playback.ReflectionSource;
import com.example.cinema.client.media.audio.reflection.LateReflectionAudibility;
import com.example.cinema.client.media.audio.reflection.LateReflectionBus;
import com.example.cinema.client.media.audio.reflection.LateReflectionSettings;
import com.example.cinema.client.media.audio.reflection.LateReflectionTarget;
import com.example.cinema.client.media.audio.reflection.ScatterAccumulator;
import com.example.cinema.client.media.audio.reflection.ScatterRayBudget;
import com.example.cinema.client.media.audio.reflection.Vbap;
import com.example.cinema.client.media.audio.reflection.WetLayout;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
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
	private static final double LATE_PATH_STABLE_MS_PER_SPEED = 1_200.0;
	private static final int FIRST_ORDER_RAYS_PER_PATH = 2;
	private static final int MAX_FIRST_ORDER_RAY_PATHS_PER_CHANNEL = 3;
	private static final int MAX_ORDER_RAY_PATHS_PER_CHANNEL_ORDER = 2;
	private static final int HIGHER_ORDER_RAYS_PER_PATH = 1;
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
	private float[][] physicalSpeakerWorldPositions = new float[0][];
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
	private long lastWetLayoutLogMillis;
	private Vec3 debugListener;
	private Vec3 debugForward;
	private Vec3 tapListener;
	private Vec3 tapForward;
	private ClientLevel debugLevel;
	private volatile List<SourceDebugPoint> debugSources = List.of();
	private int lastWetPointCount;
	private int lastWetPhysicalCount;
	private int lastWetActiveCount;
	private boolean lastSourceDebugEnabled;
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
	private List<LateReflectionBus.Tap> scatterTaps = List.of();
	private List<LateReflectionBus.Tap> diffuseRayTaps = List.of();
	private List<LateReflectionBus.Tap> order2RayTaps = List.of();
	private List<LateReflectionBus.Tap> order3RayTaps = List.of();
	private List<LateReflectionBus.Tap> order4RayTaps = List.of();
	private final ScatterRayBudget scatterRayBudget = new ScatterRayBudget();
	private final WetLayout.Builder wetLayoutBuilder = new WetLayout.Builder();
	private List<ScatterContribution> firstOrderScatter = List.of();
	private List<ScatterContribution> order2Scatter = List.of();
	private List<ScatterContribution> order3Scatter = List.of();
	private final List<ScatterContribution> order4Scatter = new ArrayList<>();
	private final List<LatePathCandidate> order4ScatterCandidates =
		new ArrayList<>();
	private int scatterTapsBelowFloor;
	private int scatterTapsAboveDelay;
	private double scatterEnergy;
	private List<ReflectionSource> premiumOrder2Sources = List.of();
	private List<ReflectionSource> premiumOrder3Sources = List.of();
	private List<ReflectionSource> currentReflectionSources = List.of();
	private final Map<String, MinimumPhaseFir> premiumReflectionFilters = new HashMap<>();
	private Vec3 lastLatePathListener;
	private Vec3 lastLatePathSample;
	private long lastLatePathSampleMillis;
	private long lastLatePathMoveMillis;
	private long latePathRequestRoomVersion = Long.MIN_VALUE;
	private long lastLatePathResetMillis;
	private boolean latePathResetPending;
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
	private boolean lastScatteringEnabled;
	private boolean lastLateMaterialEq;
	private long lastSpeakerSettingsHash = Long.MIN_VALUE;
	/** Direct propagation delay per physical speaker key, in seconds. */
	private final Map<String, Double> directDelayBySource = new HashMap<>();
	private double directReferencePower;
	private double lateBusFloorPower = Double.POSITIVE_INFINITY;
	private double lateBusMaxDelaySeconds =
		CinemaAudioEngine.maxReflectionDelaySeconds();
	private double lateBusRt60Seconds;
	private double lastLateFloorDb = Double.NaN;
	private int lateTapsBelowFloor;
	private int lateTapsAboveDelay;

	private record LatePathRequest(SpeakerBlockEntity speaker, int channel,
								   List<MirrorPlane> planes, Vec3 source) {
	}

	private record SpeakerRoute(SpeakerBlockEntity speaker, int channel,
								float[] position, float gain,
								MinimumPhaseFir filter, float occlusionGain,
								float lateSendGain, double distanceSqr,
								double distanceMeters) {
	}

	private record LatePathCandidate(ReflectionPathTap path,
									 SpeakerBlockEntity speaker) {
	}

	/** Energy-weighted input for one diffuse send tap. */
	private record ScatterContribution(int channel, double energy,
									   double delaySeconds, double dampingHz,
									   AcousticMaterial material) {
	}

	private record PathScatterResult(List<ScatterContribution> contributions,
									 List<LateReflectionBus.Tap> rays) {
	}

	private record DirectionalPlan(Map<String, ScatterRayBudget.Budget> budgets,
								   Set<String> pathKeys) {
	}

	private record RayResult(List<LateReflectionBus.Tap> taps,
							 Set<String> pathKeys) {
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
		lastWetLayoutLogMillis = 0L;
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
		currentReflectionSources = List.of();
		premiumReflectionFilters.clear();
		currentLateTaps = List.of();
		scatterTaps = List.of();
		diffuseRayTaps = List.of();
		order2RayTaps = List.of();
		order3RayTaps = List.of();
		order4RayTaps = List.of();
		firstOrderScatter = List.of();
		order2Scatter = List.of();
		order3Scatter = List.of();
		order4Scatter.clear();
		order4ScatterCandidates.clear();
		scatterRayBudget.clear();
		wetLayoutBuilder.clear();
		scatterTapsBelowFloor = 0;
		scatterTapsAboveDelay = 0;
		scatterEnergy = 0.0;
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
		lastScatteringEnabled = false;
		lastLateMaterialEq = false;
		debugListener = null;
		debugForward = null;
		debugLevel = null;
		tapListener = null;
		tapForward = null;
		debugSources = List.of();
		if (audio != null) {
			engineTaps(List.of());
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
		engine.setLimiterSettings(config.limiterEnabled(),
			(float) config.limiterCeilingDb(),
			config.limiterReleaseSeconds());
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
		float[] directDelays = new float[directCount];
		double[] directAmplitudes = new double[directCount];
		int[] sourceChannels = new int[directCount];
		double minDistanceMeters = Double.POSITIVE_INFINITY;
		for (SpeakerRoute route : selected) {
			minDistanceMeters = Math.min(minDistanceMeters, route.distanceMeters());
		}
		if (!Double.isFinite(minDistanceMeters)) {
			minDistanceMeters = 0.0;
		}
		double maxDirectDelay = CinemaAudioEngine.historySafeDelaySeconds();
		Map<String, Double> delaysBySource = new HashMap<>();
		int index = 0;
		for (SpeakerRoute route : selected) {
			positions[index] = route.position();
			gains[index] = route.gain();
			filters[index] = route.filter();
			occlusionGains[index] = route.occlusionGain();
			lateSendGains[index] = route.lateSendGain();
			sourceChannels[index] = route.channel();
			// Extra flight time relative to the nearest selected speaker. The
			// nearest speaker keeps t=0, so the media/A-V offset is unchanged.
			double delay = DirectPropagationDelay.seconds(route.distanceMeters(),
				minDistanceMeters, DirectPropagationDelay.SPEED_OF_SOUND,
				maxDirectDelay);
			directDelays[index] = (float) delay;
			delaysBySource.put(sourceKey(route.speaker()), (double) directDelays[index]);
			directAmplitudes[index] = route.gain() * route.occlusionGain()
				/ Math.max(1.0, route.distanceMeters());
			index++;
		}
		directDelayBySource.clear();
		directDelayBySource.putAll(delaysBySource);
		directReferencePower = LateReflectionAudibility.directPower(
			directAmplitudes);
		lateBusFloorPower = quantizePowerDb(
			LateReflectionAudibility.floorPower(
				directReferencePower, config.lateFloorDb()), 0.25);

		float[][] speakerPositions = new float[selected.size()][];
		int[] speakerChannels = new int[selected.size()];
		float[][] speakerWorldPositions = new float[selected.size()][];
		for (int i = 0; i < selected.size(); i++) {
			SpeakerRoute route = selected.get(i);
			speakerPositions[i] = route.position();
			speakerChannels[i] = route.channel();
			Vec3 front = route.speaker().acousticFrontPosition();
			speakerWorldPositions[i] = new float[]{
				(float) front.x, (float) front.y, (float) front.z};
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
		physicalSpeakerWorldPositions = speakerWorldPositions;
		physicalSpeakerChannels = speakerChannels;
		engine.setChannelLayout(sourceChannels, positions, gains, filters,
			occlusionGains, lateSendGains, directDelays);
		updateLateReflection(selected, config);
		updateReflections(level, listener, forward, selected, config);
		stepLatePhase(level, listener, forward, config);
		updateReflectionOrientation(listener, forward);
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
			distanceSqr, distanceMeters);
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
			< config.lateReflectionUpdateMillis()) {
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
		LateReflectionSettings settings = new LateReflectionSettings(
			config.lateReverb(), target.sourceGainDb());
		engine.setLateReflection(settings);
		if (now - lastLateReflectionLogMillis >= 5_000L) {
			lastLateReflectionLogMillis = now;
			CinemaLog.info(CinemaLog.RECEIVER, "{}: order 2-4 reflections R={} m2 "
					+ "rc={} m target={} dB sourceGain={} dB effective={} dB",
				receiverPos.toShortString(),
				formatMetric(target.roomConstant(), "%.1f"),
				formatMetric(target.criticalDistanceMeters(), "%.2f"),
				formatDb(target.targetWetGainDb()),
				formatDb(target.sourceGainDb()),
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
		debugListener = listener;
		debugForward = forward;
		debugLevel = level;
		long now = System.currentTimeMillis();
		RoomAcousticSnapshot snapshot = AcousticScene.snapshot();
		AcousticRoom room = snapshot == null ? null : snapshot.room();
		long snapshotVersion = snapshot == null ? 0L : snapshot.version();
		long roomVersion = room == null ? 0L : room.reflectionKey();
		ReflectionDelayLimit reflectionLimit = delayLimit(room);
		lateBusRt60Seconds = room == null ? 0.0 : room.rt60MidMeters();
		lateBusMaxDelaySeconds = LateReflectionAudibility.maxDelaySeconds(
			lateBusRt60Seconds, config.lateFloorDb(),
			CinemaAudioEngine.maxReflectionDelaySeconds());
		lateBusMaxDelaySeconds = Math.round(lateBusMaxDelaySeconds * 1_000.0)
			/ 1_000.0;
		ReflectionDelayLimit lateDelayLimit =
			ReflectionDelayLimit.constant(lateBusMaxDelaySeconds);
		boolean floorChanged = config.lateFloorDb() != lastLateFloorDb;
		boolean moved = lastReflectionListener == null
			|| listener.distanceToSqr(lastReflectionListener)
				> thresholdSqrBlocks(config.reflectionMoveThresholdMeters());
		boolean speakerSettingsChanged = updateSpeakerSettingsHash(speakers);
		boolean lateReflectionToggled =
			config.lateReverb() != lastLateReflectionEnabled;
		boolean scatteringToggled =
			config.scatteringEnabled() != lastScatteringEnabled;
		boolean materialEqToggled =
			config.lateMaterialEq() != lastLateMaterialEq;
		boolean debugToggled = AvReceiverPlaybackManager.sourceDebugEnabled()
			!= lastSourceDebugEnabled;
		boolean force = roomVersion != lastReflectionRoomVersion
			|| speakerSettingsChanged || lateReflectionToggled
			|| scatteringToggled || materialEqToggled || floorChanged
			|| debugToggled;
		long interval = config.reflectionUpdateMillis();
		if (moved) {
			// Movement can react sooner than a slow user cadence, but never
			// faster than 5-10 Hz; otherwise walking rebuilds every tick.
			interval = Math.max(100L, Math.min(interval, 200L));
		}
		if (now - lastReflectionUpdateMillis < interval && !force) {
			return;
		}
		lastLateFloorDb = config.lateFloorDb();
		lateTapsBelowFloor = 0;
		lateTapsAboveDelay = 0;
		if (lateReflectionToggled) {
			// Rebuild immediately instead of waiting for the 5 Hz timer; this
			// also forces the order-2 taps to republish and avoids a stale or
			// empty late-bus state after the toggle.
			lastLateReflectionEnabled = config.lateReverb();
			lastLatePathListener = null;
			latePathRequestRoomVersion = Long.MIN_VALUE;
		}
		lastScatteringEnabled = config.scatteringEnabled();
		lastLateMaterialEq = config.lateMaterialEq();
		if (scatteringToggled || materialEqToggled) {
			// These switches change first-order taps, higher-order scatter,
			// premium FIR material and lazy tap filters, so discard the steady
			// late-path build and republish from order 2 in this update.
			resetLatePathBuild();
			premiumOrder2Sources = List.of();
			premiumOrder3Sources = List.of();
			lastLatePathListener = null;
			latePathRequestRoomVersion = Long.MIN_VALUE;
		}
		if (speakerSettingsChanged || floorChanged) {
			// Speaker level/profile gain and the listener-relative floor are
			// baked into the premium sources and late-bus taps. Invalidate the
			// steady-state order-2/3/4 build so the next update republishes
			// them with the new gain, floor and delay cap.
			resetLatePathBuild();
			premiumOrder2Sources = List.of();
			premiumOrder3Sources = List.of();
			latePathRequestRoomVersion = Long.MIN_VALUE;
			lastLatePathListener = null;
		}
		lastReflectionUpdateMillis = now;
		lastReflectionRoomVersion = roomVersion;
		lastReflectionListener = listener;
		lastSourceDebugEnabled = AvReceiverPlaybackManager.sourceDebugEnabled();
		tapListener = listener;
		tapForward = forward;
		Set<String> previousKeys = new HashSet<>(reflectionFilters.keySet());

		List<AcousticSurface> seeds = room == null
			? List.of() : room.reflectionSurfaces();
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		// S1a: first-order scattering only, and only while the late bus exists.
		// With lateReverb off the diffuse send has no renderer, so bypass the
		// split entirely and keep the pre-S1 specular coefficients. The
		// scatteringEnabled switch is a separate A/B control.
		boolean scatteringEnabled = config.lateReverb()
			&& config.scatteringEnabled();
		scatterTapsBelowFloor = 0;
		scatterTapsAboveDelay = 0;
		scatterEnergy = 0.0;
		ReflectionProbe probe = ReflectionProbe.of(level);
		List<ReflectionTap> taps = new ArrayList<>();
		List<LatePathCandidate> order2Candidates = new ArrayList<>();
		int order2DelayRejected = 0;
		boolean lateOrder2Stable = config.lateReverb() && latePathFastApplied
			&& lastLatePathListener != null
			&& listener.distanceToSqr(lastLatePathListener)
				<= thresholdSqrBlocks(config.latePathMoveThresholdMeters())
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
				reflectionLimit.maxDelaySeconds(1), scatteringEnabled));
			if (config.lateReverb() && !lateOrder2Stable) {
				// Order-2 paths are published immediately. The order-3/4
				// upgrades run incrementally and replace this set only after
				// their phase completes.
				ImageSourcePathSolver.SolveStats pathStats =
					ImageSourcePathSolver.solveWithStats(planes, channel, sourceKey,
						pointOf(center), pointOf(listener), probe, 2,
						MAX_FAST_REFLECTION_PATHS, lateDelayLimit,
						scatteringEnabled);
				order2DelayRejected += pathStats.delayRejected();
				for (ReflectionPathTap path : pathStats.paths()) {
					order2Candidates.add(new LatePathCandidate(path, speaker));
				}
			}
		}
		DirectionalPlan firstOrderPlan = planFirstOrderRays(taps, now);
		RayResult firstOrderRays = buildFirstOrderRays(taps, routesByKey, config,
			temperatureC, humidityPercent, pressureKpa, listener, forward,
			firstOrderPlan);
		diffuseRayTaps = firstOrderRays.taps();
		firstOrderScatter = buildFirstOrderScatter(taps, routesByKey, config,
			temperatureC, humidityPercent, pressureKpa, firstOrderPlan,
			firstOrderRays.pathKeys());
		if (!lateOrder2Stable) {
			PathScatterResult result = buildPathScatter(order2Candidates, config,
				temperatureC, humidityPercent, pressureKpa, listener, forward, now);
			order2Scatter = result.contributions();
			order2RayTaps = result.rays();
		}
		if (!lateScatteringEnabled(config)) {
			order2Scatter = List.of();
			order3Scatter = List.of();
			order4Scatter.clear();
			order2RayTaps = List.of();
			order3RayTaps = List.of();
			order4RayTaps = List.of();
			order4ScatterCandidates.clear();
		}
		rebuildScatterTaps(config.lateMaterialEq());
		List<LateReflectionBus.Tap> order2BusTaps = List.of();
		if (!lateOrder2Stable) {
			List<LatePathCandidate> premium2 = selectPremium(order2Candidates, 2,
				MAX_PREMIUM_ORDER2_REFLECTIONS, lateBusMaxDelaySeconds);
			Set<String> premium2Keys = pathKeys(premium2);
			premiumOrder2Sources = buildPremiumSources(premium2, listener, forward,
				config, temperatureC, humidityPercent, pressureKpa);
			order2BusTaps = busTaps(order2Candidates,
				premium2Keys, config, temperatureC, humidityPercent, pressureKpa,
				listener, forward, lateDelayLimit);
		}
		updateLatePathBus(listener, config, order2BusTaps, roomVersion,
			lateDelayLimit);
		refreshScatterTaps();
		List<ReflectionTap> limited = selectFirstOrderTaps(taps, previousKeys);
		Set<String> currentKeys = new HashSet<>();
		List<ReflectionSource> sources = new ArrayList<>(limited.size());
		for (ReflectionTap tap : limited) {
			SpeakerRoute route = routesByKey.get(tap.sourceKey());
			if (route == null || tap.gain() <= 0.0) {
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
				humidityPercent, pressureKpa, tap.specularMaterial(),
				tap.occlusion(), subwooferBand(speaker, profile));
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
				* overallGain(firProfile);
			sources.add(new ReflectionSource(key, channel, position[0],
				position[1], position[2], (float) gain,
				tap.delaySeconds() + directDelaySeconds(speaker), filter));
		}
		reflectionFilters.keySet().retainAll(currentKeys);
		List<ReflectionSource> allSources = new ArrayList<>(
			sources.size() + premiumOrder2Sources.size()
				+ premiumOrder3Sources.size());
		// The user-chosen budget priority is direct > premium > first-order
		// > wet ghosts. Publishing premium before first-order makes the audio
		// thread allocate premium slots first when the reflection pool shrinks.
		allSources.addAll(premiumOrder2Sources);
		allSources.addAll(premiumOrder3Sources);
		allSources.addAll(sources);
		currentReflectionSources = List.copyOf(allSources);
		engine.setReflections(allSources);
		if (allSources.size() != lastReflectionLogCount
			&& now - lastReflectionLogMillis >= 5_000L) {
			lastReflectionLogCount = allSources.size();
			lastReflectionLogMillis = now;
			CinemaLog.info(CinemaLog.RECEIVER, "{}: {} reflection taps (first={}, premium2={}, "
					+ "premium3={}, candidates={}, surfaces={}, room={}, "
					+ "size={}, lateMaxDelay={} ms, rt60={} s, direct={} dB, "
					+ "floor={} dB, busDroppedFloor={}, busDroppedDelay={}, "
					+ "order2Dropped={}, scatterTaps={}, scatterE={}, "
					+ "scatterDropFloor={}, scatterDropDelay={})",
				receiverPos.toShortString(), allSources.size(), sources.size(),
				premiumOrder2Sources.size(), premiumOrder3Sources.size(),
				taps.size(), seeds.size(),
				snapshot == null ? "none" : Long.toString(snapshotVersion),
				room == null ? "none" : room.sizeLabel(),
				String.format(java.util.Locale.ROOT, "%.1f",
					lateBusMaxDelaySeconds * 1_000.0),
				String.format(java.util.Locale.ROOT, "%.3f", lateBusRt60Seconds),
				String.format(java.util.Locale.ROOT, "%.1f",
					LateReflectionAudibility.amplitudeDb(
						Math.sqrt(directReferencePower))),
				String.format(java.util.Locale.ROOT, "%+.0f", config.lateFloorDb()),
				lateTapsBelowFloor, lateTapsAboveDelay, order2DelayRejected,
				scatterTaps.size(),
				String.format(java.util.Locale.ROOT, "%.4f", scatterEnergy),
				scatterTapsBelowFloor, scatterTapsAboveDelay);
		}
	}

	/** Chooses the first-order directional ray budget per source channel. */
	private DirectionalPlan planFirstOrderRays(List<ReflectionTap> taps, long now) {
		Map<Integer, double[]> energies = new HashMap<>();
		Map<Integer, List<ReflectionTap>> groups = new HashMap<>();
		for (ReflectionTap tap : taps) {
			if (tap == null || tap.scatteredGain() <= 0.0) {
				continue;
			}
			int channel = tap.channel();
			double[] sums = energies.computeIfAbsent(channel,
				ignored -> new double[2]);
			sums[0] += tap.gain() * tap.gain();
			sums[1] += tap.scatteredGain() * tap.scatteredGain();
			groups.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(tap);
		}
		Map<String, ScatterRayBudget.Budget> budgets = new HashMap<>();
		Set<String> selected = new HashSet<>();
		for (Map.Entry<Integer, double[]> entry : energies.entrySet()) {
			int channel = entry.getKey();
			double[] sums = entry.getValue();
			ScatterRayBudget.Budget budget = scatterRayBudget.update(
				"1:" + channel, sums[0], sums[1],
				MAX_FIRST_ORDER_RAY_PATHS_PER_CHANNEL, now);
			budgets.put("1:" + channel, budget);
			List<ReflectionTap> group = groups.get(channel);
			if (group == null || budget.rayPaths() <= 0) {
				continue;
			}
			group.sort(Comparator.comparingDouble(
				(ReflectionTap tap) -> tap.scatteredGain()).reversed());
			int count = Math.min(budget.rayPaths(), group.size());
			for (int i = 0; i < count; i++) {
				selected.add(group.get(i).key());
			}
		}
		return new DirectionalPlan(budgets, selected);
	}

	/**
	 * Builds the first-order diffuse contributions. Only active when the late
	 * bus and the A/B scattering switch are both enabled.
	 */
	private List<ScatterContribution> buildFirstOrderScatter(
		List<ReflectionTap> taps, Map<String, SpeakerRoute> routesByKey,
		CinemaClientConfig config, double temperatureC, double humidityPercent,
		double pressureKpa, DirectionalPlan plan, Set<String> producedRayKeys) {
		if (!lateScatteringEnabled(config) || taps == null || taps.isEmpty()) {
			return List.of();
		}
		List<ScatterContribution> contributions = new ArrayList<>();
		for (ReflectionTap tap : taps) {
			if (tap == null || tap.scatteredGain() <= 0.0) {
				continue;
			}
			SpeakerRoute route = routesByKey.get(tap.sourceKey());
			if (route == null) {
				continue;
			}
			SpeakerBlockEntity speaker = route.speaker();
			SpeakerDataProfile profile = speakerProfile(speaker);
			Vec3 reflectionPoint = new Vec3(tap.reflectionPoint().x(),
				tap.reflectionPoint().y(), tap.reflectionPoint().z());
			SpeakerDirectivityService.Angles angles =
				SpeakerDirectivityService.angles(speaker.acousticFrontPosition(),
					speaker.acousticForward(), reflectionPoint);
			AcousticMaterial scatteredMaterial = tap.scatteredMaterial();
			CommonAcousticFirProfile firProfile =
				CommonAcousticFirProfile.reflection(profile,
					angles.horizontal(), angles.vertical(),
					AcousticScale.blocksToMeters(tap.pathLength()),
					config.airAbsorption(), temperatureC, humidityPercent,
					pressureKpa, scatteredMaterial, tap.occlusion(),
					subwooferBand(speaker, profile));
			ScatterRayBudget.Budget budget =
				plan.budgets().get("1:" + route.channel());
			boolean directional = budget != null
				&& plan.pathKeys().contains(tap.key())
				&& producedRayKeys.contains(tap.key());
			double scale = directional
				? Math.sqrt(1.0 - budget.directionalShare()) : 1.0;
			double amplitude = tap.scatteredGain() * speakerGain(speaker)
				* overallGain(firProfile) * scale;
			ScatterContribution contribution = scatterContribution(
				route.channel(), amplitude,
				tap.delaySeconds() + directDelaySeconds(speaker),
				AcousticScale.blocksToMeters(tap.pathLength()),
				scatteredMaterial.high(), scatteredMaterial);
			if (contribution != null) {
				contributions.add(contribution);
			}
		}
		return List.copyOf(contributions);
	}

	/**
	 * S2a: deterministic cosine-weighted hemisphere taps for first-order
	 * scattered energy. The specular path delay is used as the arrival proxy;
	 * directions are seeded by the tap key so they do not flicker.
	 */
	private RayResult buildFirstOrderRays(
		List<ReflectionTap> taps, Map<String, SpeakerRoute> routesByKey,
		CinemaClientConfig config, double temperatureC, double humidityPercent,
		double pressureKpa, Vec3 listener, Vec3 forward,
		DirectionalPlan plan) {
		if (!lateScatteringEnabled(config) || taps == null || taps.isEmpty()) {
			return new RayResult(List.of(), Set.of());
		}
		List<LateReflectionBus.Tap> rays = new ArrayList<>();
		Set<String> produced = new HashSet<>();
		for (ReflectionTap tap : taps) {
			if (tap == null || tap.scatteredGain() <= 0.0
				|| tap.normal().lengthSqr() < 1.0e-12
				|| !plan.pathKeys().contains(tap.key())) {
				continue;
			}
			SpeakerRoute route = routesByKey.get(tap.sourceKey());
			if (route == null) {
				continue;
			}
			ScatterRayBudget.Budget budget =
				plan.budgets().get("1:" + route.channel());
			if (budget == null || budget.directionalShare() <= 0.0) {
				continue;
			}
			double rayScale = Math.sqrt(budget.directionalShare()
				/ FIRST_ORDER_RAYS_PER_PATH);
			SpeakerBlockEntity speaker = route.speaker();
			SpeakerDataProfile profile = speakerProfile(speaker);
			Vec3 reflectionPoint = new Vec3(tap.reflectionPoint().x(),
				tap.reflectionPoint().y(), tap.reflectionPoint().z());
			SpeakerDirectivityService.Angles angles =
				SpeakerDirectivityService.angles(speaker.acousticFrontPosition(),
					speaker.acousticForward(), reflectionPoint);
			AcousticMaterial scatteredMaterial = tap.scatteredMaterial();
			CommonAcousticFirProfile firProfile =
				CommonAcousticFirProfile.reflection(profile,
					angles.horizontal(), angles.vertical(),
					AcousticScale.blocksToMeters(tap.pathLength()),
					config.airAbsorption(), temperatureC, humidityPercent,
					pressureKpa, scatteredMaterial, tap.occlusion(),
					subwooferBand(speaker, profile));
			double amplitude = tap.scatteredGain() * speakerGain(speaker)
				* overallGain(firProfile) * rayScale;
			Vec3 toListener = listener.subtract(reflectionPoint);
			Point3 normal = tap.normal();
			if (normal.dot(new Point3(toListener.x, toListener.y,
				toListener.z)) < 0.0) {
				normal = normal.scale(-1.0);
			}
			List<Point3> directions = DiffuseRaySampler.directions(normal,
				tap.key(), FIRST_ORDER_RAYS_PER_PATH);
			MaterialReflectionFilter filter = config.lateMaterialEq()
				? MaterialReflectionFilter.of(scatteredMaterial,
					CinemaAudioEngine.SAMPLE_RATE)
				: MaterialReflectionFilter.FLAT;
			double cutoff = Math.max(2_000.0,
				12_000.0 - 6_000.0 * scatteredMaterial.high()
					- 150.0 * AcousticScale.blocksToMeters(tap.pathLength()));
			boolean rayAdded = false;
			for (int index = 0; index < directions.size(); index++) {
				double delay = tap.delaySeconds() + directDelaySeconds(speaker)
					+ index * 0.003;
				if (delay >= lateBusMaxDelaySeconds
					|| delay >= CinemaAudioEngine.lateBusMaxDelaySeconds()) {
					scatterTapsAboveDelay++;
					continue;
				}
				if (!LateReflectionAudibility.audible(amplitude,
					lateBusFloorPower)) {
					scatterTapsBelowFloor++;
					continue;
				}
				Point3 direction = directions.get(index);
				float[] listenerDirection = toListenerSpace(
					new Vec3(direction.x(), direction.y(), direction.z()),
					forward);
				Vbap.Weights weights = Vbap.pan(listenerDirection,
					physicalSpeakerPositions, physicalSpeakerChannels);
				rays.add(new LateReflectionBus.Tap(route.channel(), delay,
					amplitude, cutoff, listenerDirection,
					weights.channels(), weights.weights(),
					"diffuse:" + tap.key() + ":" + index, filter)
					.withOrder(1));
				rayAdded = true;
			}
			if (rayAdded) {
				produced.add(tap.key());
			}
		}
		return new RayResult(List.copyOf(rays), Set.copyOf(produced));
	}

	/**
	 * Builds diffuse aggregate contributions and selected higher-order
	 * directional rays. Selected paths split their scatter energy between the
	 * aggregate and {@code HIGHER_ORDER_RAYS_PER_PATH} direction taps.
	 */
	private PathScatterResult buildPathScatter(
		List<LatePathCandidate> candidates, CinemaClientConfig config,
		double temperatureC, double humidityPercent, double pressureKpa,
		Vec3 listener, Vec3 forward, long nowMillis) {
		if (!lateScatteringEnabled(config) || candidates == null
			|| candidates.isEmpty()) {
			return new PathScatterResult(List.of(), List.of());
		}
		Map<String, ScatterRayBudget.Budget> budgets =
			pathRayBudgets(candidates, nowMillis);
		Set<String> rayPaths = selectRayPaths(candidates, budgets);
		List<ScatterContribution> contributions = new ArrayList<>();
		List<LateReflectionBus.Tap> rays = new ArrayList<>();
		for (LatePathCandidate candidate : candidates) {
			ScatterContribution contribution = buildPathScatter(candidate,
				config, temperatureC, humidityPercent, pressureKpa);
			if (contribution == null) {
				continue;
			}
			String group = candidate.path().channel() + ":"
				+ candidate.path().order();
			ScatterRayBudget.Budget budget = budgets.get(group);
			boolean selected = rayPaths.contains(
				candidate.path().sourcePathKey());
			List<LateReflectionBus.Tap> candidateRays = selected
				&& budget != null
				? buildPathRayTaps(candidate, Math.sqrt(contribution.energy()),
					config, listener, forward, budget.directionalShare())
				: List.of();
			if (!candidateRays.isEmpty() && budget != null) {
				contributions.add(new ScatterContribution(contribution.channel(),
					contribution.energy() * (1.0 - budget.directionalShare()),
					contribution.delaySeconds(), contribution.dampingHz(),
					contribution.material()));
				rays.addAll(candidateRays);
			} else {
				contributions.add(contribution);
			}
		}
		return new PathScatterResult(List.copyOf(contributions),
			List.copyOf(rays));
	}

	private Map<String, ScatterRayBudget.Budget> pathRayBudgets(
		List<LatePathCandidate> candidates, long nowMillis) {
		Map<String, double[]> energies = new HashMap<>();
		for (LatePathCandidate candidate : candidates) {
			if (candidate == null || candidate.path() == null
				|| candidate.path().scatteredGain() <= 0.0) {
				continue;
			}
			String group = candidate.path().channel() + ":"
				+ candidate.path().order();
			double[] sums = energies.computeIfAbsent(group,
				ignored -> new double[2]);
			sums[0] += candidate.path().gain() * candidate.path().gain();
			sums[1] += candidate.path().scatteredGain()
				* candidate.path().scatteredGain();
		}
		Map<String, ScatterRayBudget.Budget> budgets = new HashMap<>();
		for (Map.Entry<String, double[]> entry : energies.entrySet()) {
			double[] sums = entry.getValue();
			budgets.put(entry.getKey(), scatterRayBudget.update(
				entry.getKey(), sums[0], sums[1],
				MAX_ORDER_RAY_PATHS_PER_CHANNEL_ORDER, nowMillis));
		}
		return budgets;
	}

	/** Selects the dynamic number of strongest paths per channel/order. */
	private static Set<String> selectRayPaths(
		List<LatePathCandidate> candidates,
		Map<String, ScatterRayBudget.Budget> budgets) {
		Map<String, List<LatePathCandidate>> groups = new HashMap<>();
		for (LatePathCandidate candidate : candidates) {
			if (candidate == null || candidate.path() == null
				|| candidate.path().scatteredGain() <= 0.0) {
				continue;
			}
			String group = candidate.path().channel() + ":"
				+ candidate.path().order();
			groups.computeIfAbsent(group, key -> new ArrayList<>())
				.add(candidate);
		}
		Set<String> selected = new HashSet<>();
		for (Map.Entry<String, List<LatePathCandidate>> entry
			: groups.entrySet()) {
			ScatterRayBudget.Budget budget = budgets.get(entry.getKey());
			if (budget == null || budget.rayPaths() <= 0) {
				continue;
			}
			List<LatePathCandidate> group = entry.getValue();
			group.sort(Comparator.comparingDouble(
				(LatePathCandidate candidate) ->
					candidate.path().scatteredGain()).reversed());
			int count = Math.min(budget.rayPaths(), group.size());
			for (int i = 0; i < count; i++) {
				selected.add(group.get(i).path().sourcePathKey());
			}
		}
		return selected;
	}

	private List<LateReflectionBus.Tap> buildPathRayTaps(
		LatePathCandidate candidate, double amplitude,
		CinemaClientConfig config, Vec3 listener, Vec3 forward,
		double directionalShare) {
		ReflectionPathTap path = candidate.path();
		SpeakerBlockEntity speaker = candidate.speaker();
		if (path == null || speaker == null || amplitude <= 0.0
			|| path.lastNormal().lengthSqr() < 1.0e-12
			|| path.reflectionPoints().isEmpty()) {
			return List.of();
		}
		Point3 last = path.reflectionPoints()
			.get(path.reflectionPoints().size() - 1);
		Vec3 lastPoint = new Vec3(last.x(), last.y(), last.z());
		Vec3 toListener = listener.subtract(lastPoint);
		Point3 normal = path.lastNormal();
		if (normal.dot(new Point3(toListener.x, toListener.y, toListener.z))
			< 0.0) {
			normal = normal.scale(-1.0);
		}
		List<Point3> directions = DiffuseRaySampler.directions(normal,
			path.sourcePathKey(), HIGHER_ORDER_RAYS_PER_PATH);
		AcousticMaterial scatteredMaterial = path.scatteredMaterial();
		MaterialReflectionFilter filter = config.lateMaterialEq()
			? MaterialReflectionFilter.of(scatteredMaterial,
				CinemaAudioEngine.SAMPLE_RATE)
			: MaterialReflectionFilter.FLAT;
		double cutoff = Math.max(2_000.0,
			12_000.0 - 6_000.0 * scatteredMaterial.high()
				- 150.0 * AcousticScale.blocksToMeters(path.pathLength()));
		double rayScale = Math.sqrt(Math.max(0.0, directionalShare)
			/ HIGHER_ORDER_RAYS_PER_PATH);
		List<LateReflectionBus.Tap> rays = new ArrayList<>();
		for (int index = 0; index < directions.size(); index++) {
			double delay = path.delaySeconds() + directDelaySeconds(speaker)
				+ index * 0.002;
			if (delay >= lateBusMaxDelaySeconds
				|| delay >= CinemaAudioEngine.lateBusMaxDelaySeconds()) {
				scatterTapsAboveDelay++;
				continue;
			}
			if (!LateReflectionAudibility.audible(amplitude * rayScale,
				lateBusFloorPower)) {
				scatterTapsBelowFloor++;
				continue;
			}
			Point3 direction = directions.get(index);
			float[] listenerDirection = toListenerSpace(
				new Vec3(direction.x(), direction.y(), direction.z()), forward);
			Vbap.Weights weights = Vbap.pan(listenerDirection,
				physicalSpeakerPositions, physicalSpeakerChannels);
			rays.add(new LateReflectionBus.Tap(path.channel(), delay,
				amplitude * rayScale, cutoff, listenerDirection,
				weights.channels(), weights.weights(),
				"diffuse:" + path.sourcePathKey() + ":" + index, filter)
				.withOrder(path.order()));
		}
		return List.copyOf(rays);
	}

	private ScatterContribution buildPathScatter(LatePathCandidate candidate,
												 CinemaClientConfig config,
												 double temperatureC,
												 double humidityPercent,
												 double pressureKpa) {
		if (candidate == null) {
			return null;
		}
		ReflectionPathTap path = candidate.path();
		SpeakerBlockEntity speaker = candidate.speaker();
		if (path == null || speaker == null || path.scatteredGain() <= 0.0
			|| path.reflectionPoints().isEmpty() || path.materials().isEmpty()) {
			return null;
		}
		SpeakerDataProfile profile = speakerProfile(speaker);
		Point3 first = path.reflectionPoints().get(0);
		Vec3 reflectionPoint = new Vec3(first.x(), first.y(), first.z());
		SpeakerDirectivityService.Angles angles =
			SpeakerDirectivityService.angles(speaker.acousticFrontPosition(),
				speaker.acousticForward(), reflectionPoint);
		AcousticMaterial scatteredMaterial = path.scatteredMaterial();
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(profile,
				angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(path.pathLength()),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, scatteredMaterial, path.occlusion(),
				subwooferBand(speaker, profile));
		double amplitude = path.scatteredGain() * speakerGain(speaker)
			* overallGain(firProfile);
		return scatterContribution(path.channel(), amplitude,
			path.delaySeconds() + directDelaySeconds(speaker),
			AcousticScale.blocksToMeters(path.pathLength()),
			scatteredMaterial.high(), scatteredMaterial);
	}

	private ScatterContribution scatterContribution(int channel,
													double amplitude,
													double delay,
													double distanceMeters,
													double meanHigh,
													AcousticMaterial material) {
		if (!(amplitude > 0.0) || !Double.isFinite(delay)) {
			return null;
		}
		if (delay >= lateBusMaxDelaySeconds
			|| delay >= CinemaAudioEngine.lateBusMaxDelaySeconds()) {
			scatterTapsAboveDelay++;
			return null;
		}
		if (!LateReflectionAudibility.audible(amplitude, lateBusFloorPower)) {
			scatterTapsBelowFloor++;
			return null;
		}
		double cutoff = Math.max(2_000.0,
			12_000.0 - 6_000.0 * meanHigh - 150.0 * distanceMeters);
		return new ScatterContribution(channel, amplitude * amplitude, delay,
			cutoff, material);
	}

	/**
	 * Rebuilds the six-channel diffuse send from first-order and all
	 * higher-order contributions. Called after any tier changes.
	 */
	private void rebuildScatterTaps(boolean materialEq) {
		ScatterAccumulator accumulator = new ScatterAccumulator();
		addScatter(accumulator, firstOrderScatter);
		addScatter(accumulator, order2Scatter);
		addScatter(accumulator, order3Scatter);
		addScatter(accumulator, order4Scatter);
		List<LateReflectionBus.Tap> aggregate = accumulator.build(
			physicalSpeakerPositions, physicalSpeakerChannels, "scatter:",
			materialEq, CinemaAudioEngine.SAMPLE_RATE);
		double rayEnergy = 0.0;
		int rayCount = diffuseRayTaps.size() + order2RayTaps.size()
			+ order3RayTaps.size() + order4RayTaps.size();
		List<LateReflectionBus.Tap> merged = new ArrayList<>(
			aggregate.size() + rayCount);
		merged.addAll(aggregate);
		rayEnergy += addRays(merged, diffuseRayTaps);
		rayEnergy += addRays(merged, order2RayTaps);
		rayEnergy += addRays(merged, order3RayTaps);
		rayEnergy += addRays(merged, order4RayTaps);
		scatterEnergy = accumulator.totalEnergy() + rayEnergy;
		scatterTaps = List.copyOf(merged);
	}

	private static double addRays(List<LateReflectionBus.Tap> target,
								  List<LateReflectionBus.Tap> rays) {
		double energy = 0.0;
		for (LateReflectionBus.Tap ray : rays) {
			target.add(ray);
			energy += ray.gain() * ray.gain();
		}
		return energy;
	}

	private static void addScatter(ScatterAccumulator accumulator,
								   List<ScatterContribution> contributions) {
		for (ScatterContribution contribution : contributions) {
			accumulator.add(contribution.channel(), contribution.energy(),
				contribution.delaySeconds(), contribution.dampingHz(),
				contribution.material());
		}
	}

	private static boolean lateScatteringEnabled(CinemaClientConfig config) {
		return config != null && config.lateReverb()
			&& config.scatteringEnabled();
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
		AcousticMaterial firMaterial =
			lateScatteringEnabled(config) || config.lateMaterialEq()
				? path.specularMaterial() : path.materials().get(0);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(profile,
				angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(path.pathLength()),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, firMaterial, path.occlusion(),
				subwooferBand(speaker, profile));
		// fromPath() already applies path.gain(); pass only the speaker/FIR
		// level so the total is path.gain * speakerGain * overallGain.
		double gain = speakerGain(speaker) * overallGain(firProfile);
		double tapGain = path.gain() * gain;
		double delay = path.delaySeconds() + directDelaySeconds(speaker);
		if (delay >= lateBusMaxDelaySeconds
			|| delay >= CinemaAudioEngine.lateBusMaxDelaySeconds()) {
			lateTapsAboveDelay++;
			return null;
		}
		if (!LateReflectionAudibility.audible(tapGain, lateBusFloorPower)) {
			lateTapsBelowFloor++;
			return null;
		}
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
		LateReflectionBus.Tap tap = LateReflectionBus.Tap.fromPath(path, gain,
			direction, weights.channels(), weights.weights(),
			config.lateMaterialEq(), CinemaAudioEngine.SAMPLE_RATE);
		if (delay == path.delaySeconds()) {
			return tap;
		}
		return new LateReflectionBus.Tap(tap.channel(), delay, tap.gain(),
			tap.dampingHz(), tap.direction(), tap.outputChannels(),
			tap.outputWeights(), tap.key(), tap.materialFilter(),
			tap.order());
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

	private List<LatePathCandidate> selectPremium(
		List<LatePathCandidate> candidates, int order, int max,
		double lateMaxDelaySeconds) {
		double limit = Math.min(MAX_PREMIUM_DELAY_SECONDS, lateMaxDelaySeconds);
		List<LatePathCandidate> selected = new ArrayList<>();
		for (LatePathCandidate candidate : candidates) {
			if (candidate.path().order() == order
				&& candidateDelaySeconds(candidate) <= limit) {
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

	/**
	 * Extra direct flight time of one speaker, relative to the nearest selected
	 * speaker. Late paths add the same offset so direct and reflected sound
	 * from the same speaker keep their physical relative timing.
	 */
	private double directDelaySeconds(SpeakerBlockEntity speaker) {
		Double delay = directDelayBySource.get(sourceKey(speaker));
		return delay == null ? 0.0 : delay;
	}

	private double candidateDelaySeconds(LatePathCandidate candidate) {
		return candidate.path().delaySeconds()
			+ directDelaySeconds(candidate.speaker());
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
			if (excluded.contains(candidate.path().pathKey())) {
				continue;
			}
			if (candidateDelaySeconds(candidate)
				> delayLimit.maxDelaySeconds(candidate.path().order())) {
				lateTapsAboveDelay++;
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
		double pressureKpa) {
		if (candidates.isEmpty()) {
			premiumReflectionFilters.clear();
			return List.of();
		}
		List<ReflectionSource> sources = new ArrayList<>(candidates.size());
		Set<String> keys = new HashSet<>();
		for (LatePathCandidate candidate : candidates) {
			ReflectionSource source = premiumSource(candidate, listener, forward,
				config, temperatureC, humidityPercent, pressureKpa);
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
										   double pressureKpa) {
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
		AcousticMaterial firMaterial =
			lateScatteringEnabled(config) || config.lateMaterialEq()
				? path.specularMaterial() : path.materials().get(0);
		CommonAcousticFirProfile firProfile =
			CommonAcousticFirProfile.reflection(profile,
				angles.horizontal(), angles.vertical(),
				AcousticScale.blocksToMeters(path.pathLength()),
				config.airAbsorption(), temperatureC, humidityPercent,
				pressureKpa, firMaterial, path.occlusion(),
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
			* overallGain(firProfile);
		return new ReflectionSource(key, path.channel(), position[0],
			position[1], position[2], (float) gain,
			path.delaySeconds() + directDelaySeconds(speaker), filter);
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
	private void updateLatePathBus(Vec3 listener,
								   CinemaClientConfig config,
								   List<LateReflectionBus.Tap> order2Taps,
								   long roomVersion,
								   ReflectionDelayLimit lateDelayLimit) {
		if (audio == null) {
			return;
		}
		if (!config.lateReverb()) {
			resetLatePathBuild();
			premiumOrder3Sources = List.of();
			latePathResetPending = false;
			scatterTaps = List.of();
			currentLateTaps = List.of();
			engineTaps(currentLateTaps);
			return;
		}
		long now = System.currentTimeMillis();
		updateLatePathMovement(listener, now);
		boolean roomChanged = roomVersion != latePathRequestRoomVersion;
		boolean moved = latePathResetPending
			|| lastLatePathListener == null
			|| listener.distanceToSqr(lastLatePathListener)
				> thresholdSqrBlocks(config.latePathMoveThresholdMeters())
			|| roomChanged;
		// Even when the threshold is smaller than one tick of walking, do not
		// rebuild the bus more often than this. The old taps keep playing until
		// the replacement is published and crossfaded.
		long minResetMillis = Math.max(200L, config.reflectionUpdateMillis());
		boolean canReset = roomChanged || lastLatePathResetMillis == 0L
			|| now - lastLatePathResetMillis >= minResetMillis;
		if (moved && canReset) {
			latePathResetPending = false;
			lastLatePathListener = listener;
			lastLatePathMoveMillis = now;
			lastLatePathResetMillis = now;
			latePathRequestRoomVersion = roomVersion;
			latePathDelayLimit = lateDelayLimit;
			resetLatePathBuild();
			currentLateTaps = withScatter(order2Taps);
			engineTaps(currentLateTaps);
		} else if (moved) {
			latePathResetPending = true;
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

	private long requiredStableMillis(CinemaClientConfig config) {
		long min = config.latePathMinStableMillis();
		long max = Math.max(min, config.latePathMaxStableMillis());
		double value = min + LATE_PATH_STABLE_MS_PER_SPEED * latePathMoveEma;
		return (long) Math.max(min, Math.min(max, value));
	}

	private void stepLatePhase(ClientLevel level, Vec3 listener, Vec3 forward,
							   CinemaClientConfig config) {
		if (audio == null || !config.lateReverb() || latePathDeepApplied) {
			return;
		}
		debugListener = listener;
		debugForward = forward;
		debugLevel = level;
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
			if (now - lastLatePathMoveMillis < requiredStableMillis(config)) {
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
				order3Scatter = List.of();
				order4Scatter.clear();
				latePathBuildStartMillis = now;
				latePathBuildNanos = 0L;
			}
			latePathFastRequest = request;
			latePathFastSession = new ImageSourcePathSolver.Session(
				request.planes(), request.channel(), sourceKey(request.speaker()),
				pointOf(request.source()), pointOf(listener),
				ReflectionProbe.of(level), LATE_PATH_FAST_ORDER,
				MAX_FAST_REFLECTION_PATHS, 2,
				ImageSourcePathSolver.MAX_VISITS, latePathDelayLimit,
				lateScatteringEnabled(config));
		}
		long stepStart = System.nanoTime();
		boolean done = latePathFastSession.step(
			config.latePathStepBudgetMillis() * 1_000_000L);
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
		List<LatePathCandidate> premium2 = selectPremium(latePathCandidates, 2,
			MAX_PREMIUM_ORDER2_REFLECTIONS,
			latePathDelayLimit.maxDelaySeconds(2));
		List<LatePathCandidate> premium3 = selectPremium(latePathCandidates, 3,
			MAX_PREMIUM_ORDER3_REFLECTIONS,
			latePathDelayLimit.maxDelaySeconds(3));
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
			humidityPercent, pressureKpa);
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
		List<LatePathCandidate> order2Candidates = new ArrayList<>();
		List<LatePathCandidate> order3Candidates = new ArrayList<>();
		for (LatePathCandidate candidate : latePathCandidates) {
			if (candidate.path().order() == 2) {
				order2Candidates.add(candidate);
			} else if (candidate.path().order() == 3) {
				order3Candidates.add(candidate);
			}
		}
		PathScatterResult order2Result = buildPathScatter(order2Candidates,
			config, temperatureC, humidityPercent, pressureKpa, listener,
			forward, now);
		order2Scatter = order2Result.contributions();
		order2RayTaps = order2Result.rays();
		PathScatterResult order3Result = buildPathScatter(order3Candidates,
			config, temperatureC, humidityPercent, pressureKpa, listener,
			forward, now);
		order3Scatter = order3Result.contributions();
		order3RayTaps = order3Result.rays();
		order4Scatter.clear();
		order4RayTaps = List.of();
		order4ScatterCandidates.clear();
		rebuildScatterTaps(config.lateMaterialEq());
		tapListener = listener;
		tapForward = forward;
		currentLateTaps = withScatter(latePathFastTaps);
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
				finishDeepPhase(now, config, listener, forward);
				return;
			}
			latePathDeepRequest = request;
			latePathDeepSession = new ImageSourcePathSolver.Session(
				request.planes(), request.channel(), sourceKey(request.speaker()),
				pointOf(request.source()), pointOf(listener),
				ReflectionProbe.of(level), LATE_PATH_DEEP_ORDER,
				MAX_DEEP_REFLECTION_PATHS,
				LATE_PATH_DEEP_ORDER, ImageSourcePathSolver.MAX_VISITS,
				latePathDelayLimit, lateScatteringEnabled(config));
		}
		long stepStart = System.nanoTime();
		boolean done = latePathDeepSession.step(
			config.latePathStepBudgetMillis() * 1_000_000L);
		latePathDeepNanos += System.nanoTime() - stepStart;
		if (!done) {
			return;
		}
		ImageSourcePathSolver.SolveStats stats = latePathDeepSession.stats();
		latePathDeepDelayRejected += stats.delayRejected();
		LatePathRequest request = latePathDeepRequest;
		double temperatureC = config.airTemperatureC();
		double humidityPercent = config.airHumidityPercent();
		double pressureKpa = AirAbsorption.REFERENCE_PRESSURE_KPA;
		for (ReflectionPathTap path : stats.paths()) {
			double delay = path.delaySeconds()
				+ directDelaySeconds(request.speaker());
			if (delay > latePathDelayLimit.maxDelaySeconds(path.order())) {
				lateTapsAboveDelay++;
				continue;
			}
			LateReflectionBus.Tap tap = lateTapFromPath(path,
				request.speaker(), config, temperatureC, humidityPercent,
				pressureKpa, listener, forward);
			if (tap != null) {
				latePathDeepTaps.add(tap);
			}
			order4ScatterCandidates.add(
				new LatePathCandidate(path, request.speaker()));
		}
		latePathDeepSession = null;
		latePathDeepRequest = null;
		if (latePathDeepNextSource >= latePathRequests.length) {
			finishDeepPhase(now, config, listener, forward);
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

	private void finishDeepPhase(long now, CinemaClientConfig config,
								  Vec3 listener, Vec3 forward) {
		List<LateReflectionBus.Tap> combined = new ArrayList<>(
			latePathFastTaps.size() + latePathDeepTaps.size());
		combined.addAll(latePathFastTaps);
		combined.addAll(latePathDeepTaps);
		PathScatterResult order4Result = buildPathScatter(
			order4ScatterCandidates, config, config.airTemperatureC(),
			config.airHumidityPercent(), AirAbsorption.REFERENCE_PRESSURE_KPA,
			listener, forward, now);
		order4Scatter.clear();
		order4Scatter.addAll(order4Result.contributions());
		order4RayTaps = order4Result.rays();
		order4ScatterCandidates.clear();
		rebuildScatterTaps(config.lateMaterialEq());
		tapListener = listener;
		tapForward = forward;
		currentLateTaps = withScatter(combined);
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
	private int effectiveBusTaps(List<LateReflectionBus.Tap> taps) {
		if (taps == null || taps.isEmpty()) {
			return 0;
		}
		double maxDelay = Math.min(lateBusMaxDelaySeconds,
			CinemaAudioEngine.lateBusMaxDelaySeconds());
		int valid = 0;
		for (LateReflectionBus.Tap tap : taps) {
			if (tap != null && LateReflectionAudibility.audible(tap.gain(),
				lateBusFloorPower) && tap.delaySeconds() < maxDelay) {
				valid++;
			}
		}
		return Math.min(valid, CinemaAudioEngine.LATE_BUS_MAX_TAPS);
	}

	/**
	 * Replaces any existing {@code scatter:*} taps with the latest diffuse
	 * send while preserving the order-2/3/4 taps.
	 */
	private List<LateReflectionBus.Tap> withScatter(
		List<LateReflectionBus.Tap> base) {
		List<LateReflectionBus.Tap> source = base == null ? List.of() : base;
		boolean hasScatter = false;
		for (LateReflectionBus.Tap tap : source) {
			if (isScatterTap(tap)) {
				hasScatter = true;
				break;
			}
		}
		if (scatterTaps.isEmpty() && !hasScatter) {
			return source;
		}
		List<LateReflectionBus.Tap> merged = new ArrayList<>(
			source.size() + scatterTaps.size());
		for (LateReflectionBus.Tap tap : source) {
			if (!isScatterTap(tap)) {
				merged.add(tap);
			}
		}
		merged.addAll(scatterTaps);
		return List.copyOf(merged);
	}

	private static boolean isScatterTap(LateReflectionBus.Tap tap) {
		return tap.key().startsWith("scatter:")
			|| tap.key().startsWith("diffuse:");
	}

	/**
	 * Refreshes the diffuse send even when the late path solver is in a steady
	 * state and does not republish order-2/3/4 taps.
	 */
	private void refreshScatterTaps() {
		if (audio == null) {
			return;
		}
		List<LateReflectionBus.Tap> merged = withScatter(currentLateTaps);
		if (merged.equals(currentLateTaps)) {
			return;
		}
		currentLateTaps = merged;
		engineTaps(currentLateTaps);
	}

	private void engineTaps(List<LateReflectionBus.Tap> taps) {
		CinemaAudioEngine engine = audio;
		if (engine == null) {
			return;
		}
		long now = System.currentTimeMillis();
		WetLayout.Layout layout = wetLayoutBuilder.build(taps,
			physicalSpeakerWorldPositions, physicalSpeakerChannels,
			wetFrame(), now);
		lastWetPointCount = layout.pointCount();
		lastWetPhysicalCount = layout.physicalCount();
		lastWetActiveCount = layout.activeCount();
		updateDebugSources(layout);
		if (now - lastWetLayoutLogMillis >= 5_000L) {
			lastWetLayoutLogMillis = now;
			CinemaLog.info(CinemaLog.RECEIVER,
				"{}: wet points={} physical={} active={} ghosts={} taps={}",
				receiverPos.toShortString(), lastWetPointCount,
				lastWetPhysicalCount, lastWetActiveCount,
				Math.max(0, lastWetActiveCount - lastWetPhysicalCount),
				taps.size());
		}
		List<LateReflectionBus.Tap> rendered = new ArrayList<>(taps.size());
		for (LateReflectionBus.Tap tap : taps) {
			if (tap == null) {
				continue;
			}
			if (tap.channel() == Vbap.LFE_CHANNEL
				&& tap.key().startsWith("scatter:")) {
				int[] lfe = layout.lfeIndices();
				if (lfe.length > 0) {
					float weight = (float) (1.0 / Math.sqrt(lfe.length));
					float[] weights = new float[lfe.length];
					java.util.Arrays.fill(weights, weight);
					rendered.add(tap.withOutput(lfe, weights));
				}
				continue;
			}
			boolean directional = isDirectional(tap.direction());
			Vbap.Weights weights = layout.pan(tap.direction(), directional);
			if (weights.isEmpty()) {
				continue;
			}
			rendered.add(tap.withOutput(weights.channels(),
				weights.weights()));
		}
		engine.setWetLayout(layout.pointCount(), layout.positions(),
			layout.lfeIndices());
		float floorAmplitude = Double.isFinite(lateBusFloorPower)
			? (float) Math.sqrt(lateBusFloorPower) : Float.MAX_VALUE;
		engine.setLateReflectionTaps(List.copyOf(rendered),
			lateBusMaxDelaySeconds, floorAmplitude);
	}

	private WetLayout.Frame wetFrame() {
		Vec3 listener = tapListener != null ? tapListener : debugListener;
		Vec3 forward = tapForward != null ? tapForward : debugForward;
		return frameFor(listener, forward);
	}

	private static WetLayout.Frame frameFor(Vec3 listener, Vec3 forward) {
		if (listener == null || forward == null) {
			return WetLayout.Frame.identity();
		}
		Vec3 right = forward.cross(new Vec3(0.0, 1.0, 0.0));
		if (right.lengthSqr() < 1.0e-6) {
			right = new Vec3(1.0, 0.0, 0.0);
		} else {
			right = right.normalize();
		}
		return new WetLayout.Frame(
			new float[]{(float) listener.x, (float) listener.y,
				(float) listener.z},
			new float[]{(float) right.x, (float) right.y, (float) right.z},
			new float[]{0.0f, 1.0f, 0.0f},
			new float[]{(float) forward.x, (float) forward.y,
				(float) forward.z});
	}

	/**
	 * Reprices only the listener-relative representation when the player turns.
	 * Reflection geometry, delays and gains are unchanged; no path solver runs.
	 * Taps and per-tap sources are rotated from the last published listener
	 * frame to the new one, then the wet layout is republished.
	 */
	private void updateReflectionOrientation(Vec3 listener, Vec3 forward) {
		if (tapListener == null || tapForward == null
			|| listener == null || forward == null) {
			return;
		}
		if (forward.dot(tapForward) >= 1.0 - 1.0e-5) {
			return;
		}
		WetLayout.Frame oldFrame = frameFor(tapListener, tapForward);
		WetLayout.Frame newFrame = frameFor(listener, forward);
		currentLateTaps = rotateTaps(currentLateTaps, oldFrame, newFrame);
		scatterTaps = rotateTaps(scatterTaps, oldFrame, newFrame);
		diffuseRayTaps = rotateTaps(diffuseRayTaps, oldFrame, newFrame);
		order2RayTaps = rotateTaps(order2RayTaps, oldFrame, newFrame);
		order3RayTaps = rotateTaps(order3RayTaps, oldFrame, newFrame);
		order4RayTaps = rotateTaps(order4RayTaps, oldFrame, newFrame);
		premiumOrder2Sources = rotateSources(premiumOrder2Sources,
			oldFrame, newFrame);
		premiumOrder3Sources = rotateSources(premiumOrder3Sources,
			oldFrame, newFrame);
		if (!currentReflectionSources.isEmpty()) {
			currentReflectionSources = rotateSources(currentReflectionSources,
				oldFrame, newFrame);
			if (audio != null) {
				audio.setReflections(currentReflectionSources);
			}
		}
		tapListener = listener;
		tapForward = forward;
		debugListener = listener;
		debugForward = forward;
		engineTaps(currentLateTaps);
	}

	private static List<LateReflectionBus.Tap> rotateTaps(
		List<LateReflectionBus.Tap> taps, WetLayout.Frame oldFrame,
		WetLayout.Frame newFrame) {
		if (taps == null || taps.isEmpty()) {
			return List.of();
		}
		List<LateReflectionBus.Tap> rotated = new ArrayList<>(taps.size());
		for (LateReflectionBus.Tap tap : taps) {
			if (tap == null) {
				continue;
			}
			float[] world = oldFrame.toWorldDirection(tap.direction());
			rotated.add(tap.withDirection(
				newFrame.toLocalDirection(world)));
		}
		return List.copyOf(rotated);
	}

	private static List<ReflectionSource> rotateSources(
		List<ReflectionSource> sources, WetLayout.Frame oldFrame,
		WetLayout.Frame newFrame) {
		if (sources == null || sources.isEmpty()) {
			return List.of();
		}
		List<ReflectionSource> rotated = new ArrayList<>(sources.size());
		for (ReflectionSource source : sources) {
			float[] world = oldFrame.toWorld(new float[]{
				source.x(), source.y(), source.z()});
			float[] local = newFrame.toLocal(world);
			rotated.add(new ReflectionSource(source.key(), source.channel(),
				local[0], local[1], local[2], source.gain(),
				source.delaySeconds(), source.filter()));
		}
		return List.copyOf(rotated);
	}

	private void updateDebugSources(WetLayout.Layout layout) {
		if (!AvReceiverPlaybackManager.sourceDebugEnabled()) {
			debugSources = List.of();
			return;
		}
		Vec3 listener = debugListener;
		Vec3 forward = debugForward;
		if (listener == null || forward == null) {
			debugSources = List.of();
			return;
		}
		WetLayout.Frame frame = wetFrame();
		List<SourceDebugPoint> points = new ArrayList<>(
			layout.pointCount() + 128);
		for (WetLayout.Point point : layout.points()) {
			Vec3 world = new Vec3(point.position()[0], point.position()[1],
				point.position()[2]);
			boolean physical = point.kind() == WetLayout.Kind.SPEAKER;
			boolean lfe = point.kind() == WetLayout.Kind.LFE;
			if (!physical && !lfe && point.active()) {
				world = clampDebugPoint(world);
			}
			SourceDebugPoint.Kind kind = physical
				? SourceDebugPoint.Kind.WET_PHYSICAL
				: lfe ? SourceDebugPoint.Kind.WET_LFE
					: SourceDebugPoint.Kind.WET_GHOST;
			points.add(new SourceDebugPoint(world, kind, point.order(),
				point.index(), point.active(), receiverPos,
				"wet:" + point.index()));
		}
		CinemaAudioEngine engine = audio;
		if (engine != null) {
			for (CinemaAudioEngine.DebugSource source : engine.debugSources()) {
				float[] world = frame.toWorld(source.position());
				Vec3 point = new Vec3(world[0], world[1], world[2]);
				SourceDebugPoint.Kind kind = source.direct()
					? SourceDebugPoint.Kind.DIRECT
					: SourceDebugPoint.Kind.REFLECTION;
				if (kind == SourceDebugPoint.Kind.REFLECTION) {
					point = clampDebugPoint(point);
				}
				points.add(new SourceDebugPoint(point, kind, source.order(),
					-1, true, receiverPos, source.key()));
			}
		}
		debugSources = List.copyOf(points);
	}

	/**
	 * Keeps a virtual ghost marker inside the first room boundary so it is not
	 * hidden inside or behind a wall. The marker still shows the correct
	 * arrival direction; only its display radius is shortened.
	 */
	private Vec3 clampDebugPoint(Vec3 point) {
		ClientLevel level = debugLevel;
		Vec3 origin = debugListener;
		Minecraft client = Minecraft.getInstance();
		if (level == null || origin == null || client.player == null) {
			return point;
		}
		Vec3 delta = point.subtract(origin);
		double length = delta.length();
		if (length < 0.3) {
			return point;
		}
		BlockHitResult hit = level.clip(new ClipContext(origin, point,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			client.player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return point;
		}
		double distance = origin.distanceTo(hit.getLocation());
		double display = Math.max(0.3, Math.min(length, distance - 0.2));
		return origin.add(delta.normalize().scale(display));
	}

	public List<SourceDebugPoint> debugSources() {
		return debugSources;
	}

	private static boolean isDirectional(float[] direction) {
		if (direction == null || direction.length < 3) {
			return false;
		}
		double x = direction[0];
		double y = direction[1];
		double z = direction[2];
		return x * x + y * y + z * z > 1.0e-12;
	}

	private void resetLatePathBuild() {
		latePathBuildActive = false;
		latePathFastApplied = false;
		latePathDeepApplied = false;
		latePathCandidates.clear();
		order2Scatter = List.of();
		order3Scatter = List.of();
		order4Scatter.clear();
		order2RayTaps = List.of();
		order3RayTaps = List.of();
		order4RayTaps = List.of();
		order4ScatterCandidates.clear();
		latePathFastNextSource = 0;
		latePathFastSession = null;
		latePathFastRequest = null;
		latePathFastTaps = List.of();
		latePathDeepTaps.clear();
		latePathDeepNextSource = 0;
		latePathDeepSession = null;
		latePathDeepRequest = null;
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

	private static double sq(double value) {
		return value * value;
	}

	/** Converts a metre threshold to the squared block distance used by Vec3. */
	private static double thresholdSqrBlocks(double meters) {
		return sq(AcousticScale.metersToBlocks(meters));
	}

	/** Quantises a power value to a dB grid to avoid limit-only republishes. */
	private static double quantizePowerDb(double power, double stepDb) {
		if (!(power > 0.0) || !Double.isFinite(power)
			|| !(stepDb > 0.0) || !Double.isFinite(stepDb)) {
			return power;
		}
		double db = 10.0 * Math.log10(power);
		return Math.pow(10.0, Math.round(db / stepDb) * stepDb / 10.0);
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

