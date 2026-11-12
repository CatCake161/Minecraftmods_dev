package com.example.cinema.client.media.audio.playback;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acoustics.propagation.AirAbsorption;
import com.example.cinema.client.media.audio.AudioChunk;
import com.example.cinema.client.media.audio.dsp.FirProcessor;
import com.example.cinema.client.media.audio.dsp.MinimumPhaseFir;
import com.example.cinema.client.media.audio.reflection.LateReflectionBus;
import com.example.cinema.client.media.audio.reflection.LateReflectionSettings;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTFloat32;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;
import org.lwjgl.openal.SOFTSourceStartDelay;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Six independent mono OpenAL sources in one cinema-owned context.
 *
 * <p>Each AV receiver playback proxy owns one engine/context, so multiple
 * receivers can play independently. The decoder submits interleaved 5.1 chunks.
 * The audio thread deinterleaves each chunk, queues one mono buffer per speaker,
 * and schedules all six sources at the same device-clock instant. It also runs
 * the first-order reflection pool and the geometric late-reflection bus
 * (six fixed wet sources, including LFE), then publishes the audible media clock
 * so the render thread can choose video frames without touching OpenAL.
 */
public final class CinemaAudioEngine implements AutoCloseable {
	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 6;
	/**
	 * Direct sources present in the default virtual 5.1 layout. The actual
	 * count is dynamic and may be larger when one channel drives several
	 * physical speakers; see {@link #directSourceChannels}.
	 */
	private static final int DEFAULT_DIRECT_SOURCES = CHANNELS;
	private static final int BUFFER_COUNT = 4;
	/** Small queue blocks keep FIR updates within ~170 ms of playback. */
	private static final int BLOCK_FRAMES = 2_048;
	private static final float BASE_GAIN = 0.65f;
	/** Receiver master-volume ceiling: +18 dB. */
	private static final float MAX_MASTER_GAIN =
		(float) Math.pow(10.0, 18.0 / 20.0);
	/** Per-source channel-gain ceiling: +12 dB. */
	private static final float MAX_CHANNEL_SOURCE_GAIN =
		(float) Math.pow(10.0, 12.0 / 20.0);
	/** Time constant for smoothing listener-relative source positions. */
	private static final double POSITION_SMOOTHING_SECONDS = 0.03;
	/** Time constant for smoothing occlusion-driven source gain changes. */
	private static final double GAIN_SMOOTHING_SECONDS = 0.03;
	/** One extra source per output channel for the geometric late bus. */
	private static final int WET_SOURCES = LateReflectionBus.CHANNELS;
	/**
	 * Hard per-engine OpenAL source budget. Real speaker sources are reserved
	 * first, the lazy wet sources are part of the reservation, and the
	 * remainder is the reflection pool. With 5.1 and one speaker per channel
	 * this is 6 direct + 6 wet + 116 reflection = 128. If a channel drives
	 * multiple speakers, direct sources grow and the reflection pool shrinks.
	 *
	 * <p>Future channel expansion must keep this partition: direct speaker
	 * sources, then dynamically managed first-order reflections, premium
	 * second/third-order taps and the lazy wet bus. Do not raise the total
	 * beyond {@link #MAX_SOURCES} without revisiting the limiter peak budget.
	 */
	public static final int MAX_SOURCES = 128;
	/**
	 * Maximum direct sources reserved for physically linked speakers. A single
	 * 5.1 channel may drive several speakers, so direct sources are counted per
	 * speaker, not per channel. Direct sources always win the 128-source budget;
	 * the reflection pool is whatever remains after direct and wet sources.
	 */
	public static final int MAX_DIRECT_SOURCES = 64;
	/**
	 * Fixed reflection-source pool for the current one-speaker-per-channel
	 * layout. The planner caps active taps below this so appearing/disappearing
	 * taps have spare slots to fade through. Future multi-speaker layouts must
	 * shrink this pool with {@link #reflectionSourcesFor(int)}.
	 */
	public static final int MAX_REFLECTION_SOURCES =
		MAX_SOURCES - DEFAULT_DIRECT_SOURCES - WET_SOURCES;

	/**
	 * Reflection sources left after reserving one direct source per linked
	 * physical speaker and one wet source per output channel.
	 */
	public static int reflectionSourcesFor(int directSources) {
		int direct = Math.max(0, Math.min(MAX_DIRECT_SOURCES, directSources));
		return Math.max(0, Math.min(MAX_REFLECTION_SOURCES,
			MAX_SOURCES - direct - WET_SOURCES));
	}
	/**
	 * OpenAL defaults AL_MAX_GAIN to 1.0. The extended receiver range and the
	 * reflection trim can exceed that, so every source raises its own ceiling.
	 */
	private static final float MAX_SOURCE_GAIN = 64.0f;
	/**
	 * Hard geometric-delay ceiling. The room-aware budget can be smaller; this
	 * keeps the raw history and late bus within their allocated capacity.
	 */
	public static final double MAX_GEOMETRIC_DELAY_SECONDS = 1.30;
	/**
	 * Raw per-channel history for per-tap reflections. 65536 frames is
	 * ~1.365 s; the block-sized guard leaves ~1.323 s of safe delay.
	 */
	private static final int RAW_HISTORY_FRAMES = 65_536;
	private static final int RAW_HISTORY_MASK = RAW_HISTORY_FRAMES - 1;
	/** Order-2/3/4 geometric late bus: ~1.365 s history and 512 taps. */
	private static final int LATE_BUS_HISTORY_FRAMES = 65_536;
	/** Maximum taps the geometric late bus renders. */
	public static final int LATE_BUS_MAX_TAPS = 512;
	/** 30 ms fade-in/out for reflection tap appearance and disappearance. */
	private static final int REFLECTION_FADE_FRAMES = 1_440;
	/** Delay changes glide instead of jumping when the listener moves. */
	private static final double REFLECTION_DELAY_SMOOTHING_SECONDS = 0.15;
	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final Set<CinemaAudioEngine> OPEN_ENGINES = ConcurrentHashMap.newKeySet();
	/** Last queried per-context OpenAL source limit; -1 until an engine opens. */
	private static volatile int openAlMonoSources = -1;
	private static volatile int openAlStereoSources = -1;

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			for (CinemaAudioEngine engine : OPEN_ENGINES.toArray(new CinemaAudioEngine[0])) {
				engine.close();
			}
		}, "cinema-audio-shutdown"));
	}

	private static final float[][] DEFAULT_POSITIONS = {
		{-3.0f, 0.0f, -4.0f},
		{3.0f, 0.0f, -4.0f},
		{0.0f, 0.0f, -4.5f},
		{0.0f, 0.0f, -2.5f},
		{-3.0f, 0.0f, 3.0f},
		{3.0f, 0.0f, 3.0f},
	};

	private final BlockingQueue<AudioChunk> input = new ArrayBlockingQueue<>(32);
	private final Deque<ChunkMeta> masterQueue = new ArrayDeque<>();
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final Thread thread;
	private final java.util.concurrent.atomic.AtomicBoolean threadStarted = new java.util.concurrent.atomic.AtomicBoolean();
	private volatile boolean resetRequested;
	private volatile boolean flushRequested;
	private volatile double resetStartPts;
	private volatile java.util.concurrent.CountDownLatch resetDone;

	@SuppressWarnings("unchecked")
	private final Deque<Integer>[] freeBuffers = new Deque[MAX_DIRECT_SOURCES];
	private final int[] sources = new int[MAX_DIRECT_SOURCES];
	private final int[][] buffers = new int[MAX_DIRECT_SOURCES][BUFFER_COUNT];
	private final ShortBuffer[] monoShortBuffers = new ShortBuffer[MAX_DIRECT_SOURCES];
	private final FloatBuffer[] monoFloatBuffers = new FloatBuffer[MAX_DIRECT_SOURCES];
	private int directSourceCount = DEFAULT_DIRECT_SOURCES;
	private final int[] wetSources = new int[WET_SOURCES];
	private final int[][] wetBuffers = new int[WET_SOURCES][BUFFER_COUNT];
	@SuppressWarnings("unchecked")
	private final Deque<Integer>[] wetFreeBuffers = new Deque[WET_SOURCES];
	private boolean wetSourcesAllocated;
	/** Geometric order-2/3/4 late path. Audio-thread only. */
	private final LateReflectionBus lateBus = new LateReflectionBus(SAMPLE_RATE,
		LATE_BUS_HISTORY_FRAMES, LATE_BUS_MAX_TAPS, 0.030);
	private final float[][] lateBusInput =
		new float[LateReflectionBus.CHANNELS][BLOCK_FRAMES];
	private final float[][] lateBusOutput =
		new float[LateReflectionBus.CHANNELS][BLOCK_FRAMES];
	private volatile List<LateReflectionBus.Tap> lateReflectionTargets = List.of();
	private final AtomicInteger lateReflectionVersion = new AtomicInteger();
	private int appliedLateReflectionVersion = -1;
	private volatile LateReflectionSettings lateSettings = LateReflectionSettings.OFF;
	private final AtomicInteger lateSettingsVersion = new AtomicInteger();
	private int appliedLateSettingsVersion = -1;
	private float wetTrimGain;
	private float appliedWetGain = Float.NaN;
	private final float[] blockScratch = new float[BLOCK_FRAMES];
	private boolean float32Buffers;
	private final DoubleBuffer latencyQuery = BufferUtils.createDoubleBuffer(2);

	private AudioChunk pendingChunk;
	private int pendingFrameOffset;
	private volatile boolean startRequested;
	private volatile boolean started;
	private volatile boolean paused;
	private boolean appliedPaused;
	private volatile float masterGain = 1.0f;
	private volatile boolean masterMuted;
	private final float[][] directPeakHistory = new float[MAX_DIRECT_SOURCES][BUFFER_COUNT];
	private int directPeakIndex;
	private final float[][] wetPeakHistory = new float[WET_SOURCES][BUFFER_COUNT];
	private int wetPeakIndex;
	/** Output channel fed by each direct source; set from the current layout. */
	private volatile int[] directSourceChannels = new int[0];
	private final float[] predictedPeaks = new float[CHANNELS];
	private final float[] directTargets = new float[MAX_DIRECT_SOURCES];
	private final boolean[] activeOutputChannels = new boolean[CHANNELS];
	private float wetNextTarget;
	private volatile float limiterGain = 1.0f;
	private float appliedLimiterGain = Float.NaN;
	private final AtomicInteger layoutVersion = new AtomicInteger();
	private volatile float[][] channelPositions = new float[0][];
	private final float[][] renderedPositions = defaultRenderedPositions();
	private final float[][] channelAnchorPositions = copyDefaultPositions();
	private final double[] slerpScratch = new double[3];
	private long lastPositionUpdateNanos;
	private volatile float[] channelGains = new float[0];
	private volatile float[] channelLateSendGains = new float[0];
	private volatile MinimumPhaseFir[] channelFilters = new MinimumPhaseFir[0];
	private volatile float[] channelOcclusionGains = new float[0];
	private final FirProcessor[] firProcessors = new FirProcessor[MAX_DIRECT_SOURCES];
	private final float[] appliedGains = new float[MAX_DIRECT_SOURCES];
	private final float[] renderedChannelGains = defaultMaxGains();
	private final float[] renderedLateSendGains = defaultMaxGains();
	private final float[] renderedOcclusionGains = defaultMaxGains();
	private int appliedLayoutVersion = -1;
	private long lastGainUpdateNanos;
	private final ReflectionSlot[] reflectionSlots =
		new ReflectionSlot[MAX_REFLECTION_SOURCES];
	private int reflectionSourceBudget = MAX_REFLECTION_SOURCES;
	private final float[][] rawHistory = new float[CHANNELS][RAW_HISTORY_FRAMES];
	private final float[] reflectionInputScratch = new float[BLOCK_FRAMES];
	private final float[] reflectionOutputScratch = new float[BLOCK_FRAMES];
	private final float[] silenceScratch = new float[BLOCK_FRAMES];
	private final IntBuffer reflectionReclaimScratch =
		BufferUtils.createIntBuffer(BUFFER_COUNT);
	private FloatBuffer reflectionFloatScratch;
	private ShortBuffer reflectionShortScratch;
	private long rawHistoryWriteFrame = Long.MIN_VALUE;
	private volatile ReflectionSource[] reflectionTargets = new ReflectionSource[0];
	private final AtomicInteger reflectionVersion = new AtomicInteger();
	private int appliedReflectionVersion = -1;
	private volatile double audiblePts;
	private volatile double latencySeconds;
	private volatile long scheduledStartSystemNanos;
	private double lastEndPts;
	private ALCapabilities previousProcessCaps;
	private boolean threadLocalContext;
	private long deviceHandle = NULL;
	private long contextHandle = NULL;
	private long previousGlobalContext = NULL;
	private boolean ownsDevice;

	public CinemaAudioEngine() {
		for (int i = 0; i < MAX_DIRECT_SOURCES; i++) {
			freeBuffers[i] = new ArrayDeque<>(BUFFER_COUNT);
			appliedGains[i] = Float.NaN;
			firProcessors[i] = new FirProcessor();
		}
		for (int i = 0; i < WET_SOURCES; i++) {
			wetFreeBuffers[i] = new ArrayDeque<>(BUFFER_COUNT);
		}
		thread = new Thread(this::run, "cinema-audio");
		thread.setDaemon(true);
		OPEN_ENGINES.add(this);
	}

	public void start() {
		if (threadStarted.compareAndSet(false, true)) {
			thread.start();
		}
	}

	/** Stops all current sources and prepares the same device/context for a new file. */
	public void reset() {
		reset(0.0);
	}

	/** Flushes queued audio and resets the media clock to {@code startPts}. */
	public void reset(double startPts) {
		reset(startPts, true);
	}

	/**
	 * Lightweight seek flush: stops/un-queues sources and resets the clock, but
	 * keeps the existing OpenAL sources and buffers allocated.
	 */
	public void flush(double startPts) {
		reset(startPts, false);
	}

	private void reset(double startPts, boolean fullReset) {
		if (!running.get()) {
			return;
		}
		resetStartPts = Math.max(0.0, startPts);
		if (!threadStarted.get()) {
			clearQueues();
			audiblePts = resetStartPts;
			lastEndPts = resetStartPts;
			return;
		}
		if (!thread.isAlive()) {
			return;
		}
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		resetDone = latch;
		if (fullReset) {
			resetRequested = true;
		} else {
			flushRequested = true;
		}
		try {
			latch.await(1, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public void submit(AudioChunk chunk) throws InterruptedException {
		while (running.get()) {
			if (input.offer(chunk, 100, TimeUnit.MILLISECONDS)) {
				return;
			}
		}
	}

	/** Drops queued decoder output; used to unblock a decoder being cancelled. */
	public void clearInput() {
		input.clear();
	}

	public void startPlayback() {
		startRequested = true;
	}

	public boolean isStarted() {
		return started;
	}

	public double audiblePtsSeconds() {
		return audiblePts;
	}

	public double latencySeconds() {
		return latencySeconds;
	}

	/** Current output-limiter gain reduction in dB, or {@code 0} when idle. */
	public float limiterReductionDb() {
		return OutputLimiter.reductionDb(limiterGain);
	}

	/**
	 * Wall-clock timestamp of the scheduled six-source start, or 0 before the
	 * first start. Used to align the first video frame with the first audio.
	 */
	public long scheduledStartSystemNanos() {
		return scheduledStartSystemNanos;
	}

	public void setPaused(boolean value) {
		paused = value;
	}

	/**
	 * Receiver volume as linear amplitude, on top of the fixed source gain.
	 * The receiver allows up to +18 dB (linear 7.94); the output limiter keeps
	 * the final mix below its ceiling instead of hard-clipping.
	 */
	public void setMasterGain(float volume, boolean muted) {
		masterGain = Math.max(0.0f, Math.min(MAX_MASTER_GAIN, volume));
		masterMuted = muted;
	}

	/**
	 * Updates the six mono source positions (listener-relative: x right, y up,
	 * z forward is negative), per-channel linear gains, combined direct FIRs
	 * and broadband occlusion gains. Null entries keep the default virtual 5.1
	 * layout for that channel. Channel gain is capped at +12 dB to match the
	 * receiver's level limit.
	 *
	 * <p>Distance attenuation is computed here from the smoothed source radius;
	 * the direct FIR already carries on-axis/directivity, air absorption and
	 * the occlusion low-pass shape. The occlusion gain is smoothed before it
	 * reaches AL_GAIN so a newly blocked path does not click.
	 */
	public void setChannelLayout(float[][] positions, float[] gains) {
		setChannelLayout(positions, gains, null, null);
	}

	public void setChannelLayout(float[][] positions, float[] gains,
								 MinimumPhaseFir[] filters) {
		setChannelLayout(positions, gains, filters, null);
	}

	public void setChannelLayout(float[][] positions, float[] gains,
								 MinimumPhaseFir[] filters,
								 float[] occlusionGains) {
		setChannelLayout(positions, gains, filters, occlusionGains, null);
	}

	/**
	 * Backward-compatible identity mapping for the default one-source-per-channel
	 * layout. Prefer the overload that also supplies {@code sourceChannels} for
	 * multiple physical speakers on the same channel.
	 */
	public void setChannelLayout(float[][] positions, float[] gains,
								 MinimumPhaseFir[] filters,
								 float[] occlusionGains,
								 float[] lateSendGains) {
		int count = positions == null ? DEFAULT_DIRECT_SOURCES : positions.length;
		setChannelLayout(identityChannels(count), positions, gains, filters,
			occlusionGains, lateSendGains);
	}

	/**
	 * Adds the source-strength gains used by the late-reflection send. They
	 * exclude distance and broadband occlusion; the engine combines them with
	 * the smoothed occlusion gain, so the late bus is driven by source power
	 * rather than by the direct sound level at the listener.
	 *
	 * <p>Each entry is one direct source. {@code sourceChannels[i]} is the 5.1
	 * channel that source reproduces, so several sources may map to the same
	 * channel. Null positions fall back to the default virtual position for
	 * that channel.
	 */
	public void setChannelLayout(int[] sourceChannels, float[][] positions,
								 float[] gains, MinimumPhaseFir[] filters,
								 float[] occlusionGains, float[] lateSendGains) {
		int count = sourceChannels == null ? DEFAULT_DIRECT_SOURCES
			: sourceChannels.length;
		count = Math.min(MAX_DIRECT_SOURCES, Math.max(0, count));
		float[][] nextPositions = new float[count][];
		float[] nextGains = new float[count];
		MinimumPhaseFir[] nextFilters = new MinimumPhaseFir[count];
		float[] nextOcclusionGains = new float[count];
		float[] nextLateSendGains = new float[count];
		int[] nextChannels = new int[count];
		for (int i = 0; i < count; i++) {
			int channel = sourceChannels != null && i < sourceChannels.length
				? sourceChannels[i] : i;
			channel = Math.max(0, Math.min(CHANNELS - 1, channel));
			nextChannels[i] = channel;
			float[] position = positions != null && i < positions.length
				? positions[i] : null;
			nextPositions[i] = isValidPosition(position)
				? position.clone() : DEFAULT_POSITIONS[channel].clone();
			float gain = gains != null && i < gains.length ? gains[i] : 1.0f;
			nextGains[i] = Float.isFinite(gain)
				? Math.max(0.0f, Math.min(MAX_CHANNEL_SOURCE_GAIN, gain)) : 1.0f;
			MinimumPhaseFir filter = filters != null && i < filters.length
				? filters[i] : null;
			nextFilters[i] = filter == null ? MinimumPhaseFir.FLAT : filter;
			float occlusion = occlusionGains != null && i < occlusionGains.length
				? occlusionGains[i] : 1.0f;
			nextOcclusionGains[i] = Float.isFinite(occlusion)
				? Math.max(0.0f, Math.min(1.0f, occlusion)) : 1.0f;
			float lateSend = lateSendGains != null && i < lateSendGains.length
				? lateSendGains[i] : 1.0f;
			nextLateSendGains[i] = Float.isFinite(lateSend)
				? Math.max(0.0f, Math.min(8.0f, lateSend)) : 1.0f;
		}
		if (sameLayout(nextChannels, nextPositions, nextGains, nextFilters,
			nextOcclusionGains, nextLateSendGains)) {
			return;
		}
		directSourceChannels = nextChannels;
		channelPositions = nextPositions;
		channelGains = nextGains;
		channelFilters = nextFilters;
		channelOcclusionGains = nextOcclusionGains;
		channelLateSendGains = nextLateSendGains;
		layoutVersion.incrementAndGet();
	}

	private static int[] identityChannels(int count) {
		int length = Math.min(MAX_DIRECT_SOURCES, Math.max(0, count));
		int[] channels = new int[length];
		for (int i = 0; i < length; i++) {
			channels[i] = i % CHANNELS;
		}
		return channels;
	}

	private boolean sameLayout(int[] sourceChannels, float[][] positions,
							   float[] gains, MinimumPhaseFir[] filters,
							   float[] occlusionGains, float[] lateSendGains) {
		float[][] currentPositions = channelPositions;
		float[] currentGains = channelGains;
		MinimumPhaseFir[] currentFilters = channelFilters;
		float[] currentOcclusionGains = channelOcclusionGains;
		float[] currentLateSendGains = channelLateSendGains;
		int[] currentChannels = directSourceChannels;
		if (sourceChannels.length != currentChannels.length
			|| positions.length != currentPositions.length) {
			return false;
		}
		for (int i = 0; i < sourceChannels.length; i++) {
			if (sourceChannels[i] != currentChannels[i]) {
				return false;
			}
			for (int axis = 0; axis < 3; axis++) {
				if (Math.abs(positions[i][axis] - currentPositions[i][axis]) > 1.0e-4f) {
					return false;
				}
			}
			if (Math.abs(gains[i] - currentGains[i]) > 1.0e-4f) {
				return false;
			}
			if (filters[i] != currentFilters[i]) {
				return false;
			}
			if (Math.abs(occlusionGains[i] - currentOcclusionGains[i]) > 1.0e-4f) {
				return false;
			}
			if (Math.abs(lateSendGains[i] - currentLateSendGains[i]) > 1.0e-4f) {
				return false;
			}
		}
		return true;
	}

	private static boolean isValidPosition(float[] position) {
		return position != null && position.length == 3
			&& Float.isFinite(position[0]) && Float.isFinite(position[1])
			&& Float.isFinite(position[2]);
	}

	/**
	 * Replaces the early-reflection tap set. The audio thread keeps a fixed
	 * source pool; taps are matched by stable key so listener movement only
	 * updates delay/gain/position, while appearing/disappearing taps fade.
	 */
	public void setReflections(List<ReflectionSource> sources) {
		ReflectionSource[] next = sources == null ? new ReflectionSource[0]
			: sources.toArray(new ReflectionSource[0]);
		if (sameReflections(next, reflectionTargets)) {
			return;
		}
		reflectionTargets = next;
		reflectionVersion.incrementAndGet();
	}

	/**
	 * Updates the geometric late-reflection bus settings. The gain is derived
	 * from the room snapshot by the client thread; the audio thread only swaps
	 * this immutable value and keeps the previous tail until it arrives.
	 */
	public void setLateReflection(LateReflectionSettings settings) {
		LateReflectionSettings next = settings == null
			? LateReflectionSettings.OFF : settings;
		if (next.equals(lateSettings)) {
			return;
		}
		lateSettings = next;
		lateSettingsVersion.incrementAndGet();
	}

	/**
	 * Publishes the immutable order-2 geometric tap set for the late bus. The
	 * client thread only swaps this list; the audio thread applies it on the
	 * next {@code pump()}. Taps already carry path gain, speaker trim and the
	 * reflection FIR level, so the bus only adds delay and damping.
	 */
	public void setLateReflectionTaps(List<LateReflectionBus.Tap> taps) {
		List<LateReflectionBus.Tap> next;
		if (taps == null || taps.isEmpty()) {
			next = List.of();
		} else {
			List<LateReflectionBus.Tap> copy = new ArrayList<>(taps.size());
			for (LateReflectionBus.Tap tap : taps) {
				if (tap != null) {
					copy.add(tap);
				}
			}
			next = List.copyOf(copy);
		}
		if (next.equals(lateReflectionTargets)) {
			return;
		}
		lateReflectionTargets = next;
		lateReflectionVersion.incrementAndGet();
	}

	/** Maximum geometric delay accepted by the late bus, in seconds. */
	public static double lateBusMaxDelaySeconds() {
		return LATE_BUS_HISTORY_FRAMES / (double) SAMPLE_RATE;
	}

	/**
	 * Safe delay for the per-tap raw history. One processing block is reserved
	 * because the current block is written before the delayed read starts.
	 */
	public static double rawHistoryMaxDelaySeconds() {
		return (RAW_HISTORY_FRAMES - BLOCK_FRAMES) / (double) SAMPLE_RATE;
	}

	/** Shared capacity limit for paths that may use either reflection path. */
	public static double historySafeDelaySeconds() {
		return Math.min(rawHistoryMaxDelaySeconds(), lateBusMaxDelaySeconds());
	}

	/**
	 * Per-context OpenAL source limit reported by the device, or {@code -1}
	 * before any engine opens. OpenAL Soft 1.23.1 defaults to 256 (255 mono +
	 * 1 stereo) per context; an {@code alsoft.ini} {@code [general] sources}
	 * value can lower this.
	 */
	public static int openAlSourceLimit() {
		int mono = openAlMonoSources;
		int stereo = openAlStereoSources;
		return mono < 0 || stereo < 0 ? -1 : mono + stereo;
	}

	/** Current allocated direct-source count (one per active physical speaker). */
	public int directSourceCount() {
		return directSourceCount;
	}

	/** Current allocated reflection-source count. */
	public int reflectionSourceCount() {
		return reflectionSourceBudget;
	}

	/** Hard playback cap; the room-aware budget is normally lower. */
	public static double maxReflectionDelaySeconds() {
		return Math.min(MAX_GEOMETRIC_DELAY_SECONDS,
			historySafeDelaySeconds());
	}

	private static boolean sameReflections(ReflectionSource[] first,
										   ReflectionSource[] second) {
		if (first.length != second.length) {
			return false;
		}
		for (int i = 0; i < first.length; i++) {
			ReflectionSource a = first[i];
			ReflectionSource b = second[i];
			if (a == null || b == null) {
				if (a != b) {
					return false;
				}
				continue;
			}
			if (!a.key().equals(b.key()) || a.channel() != b.channel()
				|| a.filter() != b.filter()
				|| Math.abs(a.gain() - b.gain()) > 1.0e-4f
				|| Math.abs(a.delaySeconds() - b.delaySeconds()) > 1.0e-6
				|| Math.abs(a.x() - b.x()) > 1.0e-4f
				|| Math.abs(a.y() - b.y()) > 1.0e-4f
				|| Math.abs(a.z() - b.z()) > 1.0e-4f) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Matches the latest tap set to the fixed source pool. Existing keys keep
	 * their source and only update targets; new keys fade in; removed keys fade
	 * out. The audio thread never allocates or deletes OpenAL objects here.
	 */
	private void applyReflectionTargets() {
		int version = reflectionVersion.get();
		if (version == appliedReflectionVersion) {
			return;
		}
		ReflectionSource[] targets = reflectionTargets;
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null) {
				slot.assigned = false;
			}
		}
		Set<String> seen = new HashSet<>();
		boolean retry = false;
		for (ReflectionSource target : targets) {
			if (target == null || target.key().isEmpty()
				|| !seen.add(target.key())) {
				continue;
			}
			ReflectionSlot slot = findReflectionSlot(target.key());
			if (slot == null) {
				slot = idleReflectionSlot();
			}
			if (slot == null) {
				// A fading slot may still have full-volume blocks queued. Do
				// not cut it short; retry this tap after the fade retires.
				retry = true;
				continue;
			}
			if (!target.key().equals(slot.key)) {
				// Fresh key: snap position/delay and fade the samples in. The
				// delay snap is hidden by the fade and avoids a long glide
				// from an unrelated tap's delay.
				slot.processor.reset();
				slot.key = target.key();
				slot.envelope = 0.0f;
				slot.envelopeStep = 1.0f / REFLECTION_FADE_FRAMES;
				slot.gain = target.gain();
				slot.appliedGain = Float.NaN;
				slot.delayFrames = reflectionDelayFrames(target.delaySeconds());
				slot.position[0] = target.x();
				slot.position[1] = target.y();
				slot.position[2] = target.z();
				alSource3f(slot.source, AL_POSITION, target.x(), target.y(),
					target.z());
			} else if (slot.fadingOut) {
				// The same tap reappeared while fading out; reverse the fade.
				slot.envelopeStep = 1.0f / REFLECTION_FADE_FRAMES;
			}
			slot.assigned = true;
			slot.fadingOut = false;
			slot.channel = target.channel();
			slot.targetPosition[0] = target.x();
			slot.targetPosition[1] = target.y();
			slot.targetPosition[2] = target.z();
			slot.targetGain = target.gain();
			slot.targetDelayFrames = reflectionDelayFrames(
				target.delaySeconds());
			if (slot.filter != target.filter()) {
				slot.filter = target.filter();
				slot.processor.setFilter(target.filter());
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null && !slot.key.isEmpty() && !slot.assigned
				&& !slot.fadingOut) {
				slot.fadingOut = true;
				slot.envelopeStep = -1.0f / REFLECTION_FADE_FRAMES;
			}
		}
		if (!retry) {
			appliedReflectionVersion = version;
		}
	}

	private ReflectionSlot findReflectionSlot(String key) {
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null && key.equals(slot.key)) {
				return slot;
			}
		}
		return null;
	}

	private ReflectionSlot idleReflectionSlot() {
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null && slot.key.isEmpty()) {
				return slot;
			}
		}
		return null;
	}

	private static double reflectionDelayFrames(double seconds) {
		return Math.max(0.0,
			Math.min(maxReflectionDelaySeconds(), seconds)) * SAMPLE_RATE;
	}

	private static float[][] copyDefaultPositions() {
		float[][] copy = new float[DEFAULT_DIRECT_SOURCES][];
		for (int i = 0; i < DEFAULT_DIRECT_SOURCES; i++) {
			copy[i] = DEFAULT_POSITIONS[i].clone();
		}
		return copy;
	}

	private static float[][] defaultRenderedPositions() {
		float[][] positions = new float[MAX_DIRECT_SOURCES][3];
		for (int i = 0; i < DEFAULT_DIRECT_SOURCES; i++) {
			System.arraycopy(DEFAULT_POSITIONS[i], 0, positions[i], 0, 3);
		}
		return positions;
	}

	private static float[] defaultMaxGains() {
		float[] gains = new float[MAX_DIRECT_SOURCES];
		Arrays.fill(gains, 1.0f);
		return gains;
	}

	/**
	 * Queries the per-context source limit. OpenAL Soft exposes the allocated
	 * mono/stereo counts as device attributes after the context is created.
	 */
	private static void querySourceLimit(long device) {
		int mono = ALC10.alcGetInteger(device, ALC11.ALC_MONO_SOURCES);
		int stereo = ALC10.alcGetInteger(device, ALC11.ALC_STEREO_SOURCES);
		openAlMonoSources = mono;
		openAlStereoSources = stereo;
		int limit = mono + stereo;
		if (mono < 0 || stereo < 0 || limit <= 0) {
			CinemaLog.warn(CinemaLog.AUDIO,
				"OpenAL source limit unavailable; engine budget is {}", MAX_SOURCES);
			return;
		}
		CinemaLog.info(CinemaLog.AUDIO,
			"OpenAL source limit: {} total ({} mono + {} stereo); engine budget {}",
			limit, mono, stereo, MAX_SOURCES);
		if (limit < MAX_SOURCES) {
			CinemaLog.warn(CinemaLog.AUDIO,
				"OpenAL source limit {} is below the {} source budget; "
					+ "reflection allocation may fail (check alsoft.ini [general] sources)",
				limit, MAX_SOURCES);
		}
	}

	private boolean ensureCinemaContext() {
		if (contextHandle == NULL) {
			return false;
		}
		if (threadLocalContext) {
			long current = EXTThreadLocalContext.alcGetThreadContext();
			if (current != contextHandle) {
				boolean set = EXTThreadLocalContext.alcSetThreadContext(contextHandle);
				long restored = EXTThreadLocalContext.alcGetThreadContext();
				if (!set || restored != contextHandle) {
					CinemaLog.warn(CinemaLog.AUDIO,
						"cinema OpenAL thread context unavailable (current=0x{}, expected=0x{})",
						Long.toHexString(restored), Long.toHexString(contextHandle));
					return false;
				}
				CinemaLog.warn(CinemaLog.AUDIO,
					"restored cinema OpenAL thread context on the audio thread");
			}
			return true;
		}
		if (ALC10.alcGetCurrentContext() == contextHandle) {
			return true;
		}
		CinemaLog.warn(CinemaLog.AUDIO,
			"cinema OpenAL context is not current; skipping audio work");
		return false;
	}

	private void run() {
		long device = NULL;
		long context = NULL;
		try {
			device = MinecraftOpenAl.device();
			if (device == NULL) {
				CinemaLog.warn(CinemaLog.AUDIO, "Minecraft OpenAL device unavailable; opening a fallback device");
				device = ALC10.alcOpenDevice((CharSequence) null);
				ownsDevice = true;
			}
			if (device == NULL) {
				throw new IllegalStateException("alcOpenDevice() failed");
			}
			deviceHandle = device;
			ALCCapabilities deviceCaps = ALC.createCapabilities(device);
			threadLocalContext = deviceCaps.ALC_EXT_thread_local_context
				|| ALC10.alcIsExtensionPresent(device,
					"ALC_EXT_thread_local_context");
			previousGlobalContext = ALC10.alcGetCurrentContext();
			try {
				previousProcessCaps = AL.getCapabilities();
			} catch (IllegalStateException ignored) {
				// No process-wide caps yet; fall back to creating them below.
			}
			context = ALC10.alcCreateContext(device, (IntBuffer) null);
			if (context == NULL) {
				throw new IllegalStateException("alcCreateContext() failed");
			}
			contextHandle = context;
			if (threadLocalContext) {
				EXTThreadLocalContext.alcSetThreadContext(context);
			} else if (previousGlobalContext == NULL) {
				CinemaLog.warn(CinemaLog.AUDIO,
					"ALC_EXT_thread_local_context unavailable; using process-wide context without Minecraft");
				ALC10.alcMakeContextCurrent(context);
			} else {
				throw new IllegalStateException(
					"ALC_EXT_thread_local_context unavailable while Minecraft owns "
						+ "the process-wide OpenAL context; refusing to disturb it");
			}
			if (previousProcessCaps != null) {
				// Reuse Minecraft's AL function pointers. Calling
				// AL.createCapabilities() here replaces LWJGL's process-wide ICD
				// with the cinema context and upsets SoundEngine at shutdown.
				AL.setCurrentThread(previousProcessCaps);
			} else {
				AL.createCapabilities(deviceCaps);
			}
			CinemaLog.info(CinemaLog.AUDIO, "OpenAL {} on {} (sharedCaps={}, threadLocal={})",
				AL10.alGetString(AL10.AL_VERSION),
				ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER),
				previousProcessCaps != null, threadLocalContext);
			if (threadLocalContext) {
				CinemaLog.info(CinemaLog.AUDIO,
					"OpenAL contexts: process=0x{} thread=0x{} cinema=0x{}",
					Long.toHexString(ALC10.alcGetCurrentContext()),
					Long.toHexString(EXTThreadLocalContext.alcGetThreadContext()),
					Long.toHexString(context));
			}
			querySourceLimit(device);
			ensureCinemaContext();

			setupSources();
			while (running.get()) {
				pump();
				Thread.sleep(5);
			}
		} catch (InterruptedException ignored) {
			// stop() interrupts the pump loop.
		} catch (Throwable t) {
			CinemaLog.error(CinemaLog.AUDIO, "engine failed", t);
		} finally {
			cleanup();
			// Never replace Minecraft's process-wide OpenAL context/capabilities.
			if (threadLocalContext) {
				EXTThreadLocalContext.alcSetThreadContext(NULL);
			} else if (previousGlobalContext != NULL) {
				ALC10.alcMakeContextCurrent(previousGlobalContext);
			} else {
				ALC10.alcMakeContextCurrent(NULL);
			}
			AL.setCurrentThread(previousProcessCaps);
		}
	}

	private void setupSources() {
		if (!ensureCinemaContext()) {
			throw new IllegalStateException(
				"cinema OpenAL context is not current on the audio thread");
		}
		AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
		AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{0f, 0f, -1f, 0f, 1f, 0f});
		// Distance and air absorption are applied in Java: source positions are
		// listener-relative, AL_GAIN carries 1/max(r,1), and the combined direct
		// FIR carries the frequency-dependent part. AL_NONE prevents double
		// attenuation.
		AL10.alDistanceModel(AL10.AL_NONE);
		float32Buffers = supportsFloat32();
		appliedLayoutVersion = -1;
		lastPositionUpdateNanos = 0L;
		lastGainUpdateNanos = 0L;

		directSourceCount = 0;
		ensureDirectSourceCount(0);
		CinemaLog.info(CinemaLog.AUDIO, "source buffers are {}; distance model AL_NONE",
			float32Buffers ? "32-bit float" : "16-bit integer");
		setupReflectionSources();
		if (lateSettings.enabled()) {
			setupWetSources();
		}
		applyChannelLayout();
		applyChannelGains();
		CinemaLog.info(CinemaLog.AUDIO,
			"audio sources ready: direct={} reflection={} wet={} threadLocal={} processCtx=0x{} threadCtx=0x{}",
			directSourceCount, reflectionSourceBudget, WET_SOURCES,
			threadLocalContext, Long.toHexString(ALC10.alcGetCurrentContext()),
			Long.toHexString(threadLocalContext
				? EXTThreadLocalContext.alcGetThreadContext() : 0L));
		checkError("setupSources");
	}

	private void ensureDirectSourceCount(int desired) {
		int target = Math.max(0, Math.min(MAX_DIRECT_SOURCES, desired));
		while (directSourceCount < target) {
			if (!allocateDirectSource(directSourceCount)) {
				CinemaLog.warn(CinemaLog.AUDIO,
					"could not allocate direct source {} of {}; OpenAL source limit {}",
					directSourceCount, target, openAlSourceLimit());
				break;
			}
			directSourceCount++;
		}
		while (directSourceCount > target) {
			directSourceCount--;
			releaseDirectSource(directSourceCount);
		}
	}

	private boolean allocateDirectSource(int index) {
		if (!ensureCinemaContext()) {
			return false;
		}
		sources[index] = alGenSources();
		if (sources[index] == 0) {
			return false;
		}
		freeBuffers[index].clear();
		for (int j = 0; j < BUFFER_COUNT; j++) {
			buffers[index][j] = alGenBuffers();
			freeBuffers[index].addLast(buffers[index][j]);
		}
		if (float32Buffers) {
			monoFloatBuffers[index] = BufferUtils.createFloatBuffer(BLOCK_FRAMES);
			monoShortBuffers[index] = null;
		} else {
			monoShortBuffers[index] = BufferUtils.createShortBuffer(BLOCK_FRAMES);
			monoFloatBuffers[index] = null;
		}
		alSourcei(sources[index], AL_SOURCE_RELATIVE, AL_TRUE);
		alSourcef(sources[index], AL_MAX_GAIN, MAX_SOURCE_GAIN);
		alSourcef(sources[index], AL_MIN_GAIN, 0.0f);
		alSourcef(sources[index], AL_GAIN, 0.0f);
		appliedGains[index] = Float.NaN;
		Arrays.fill(directPeakHistory[index], 0.0f);
		firProcessors[index].reset();
		return true;
	}

	private void releaseDirectSource(int index) {
		if (sources[index] != 0) {
			alSourceStop(sources[index]);
			alDeleteSources(sources[index]);
			sources[index] = 0;
		}
		for (int j = 0; j < BUFFER_COUNT; j++) {
			if (buffers[index][j] != 0) {
				alDeleteBuffers(buffers[index][j]);
				buffers[index][j] = 0;
			}
		}
		freeBuffers[index].clear();
		monoFloatBuffers[index] = null;
		monoShortBuffers[index] = null;
		appliedGains[index] = Float.NaN;
		Arrays.fill(directPeakHistory[index], 0.0f);
		firProcessors[index].reset();
	}

	private void setupReflectionSources() {
		reflectionFloatScratch = BufferUtils.createFloatBuffer(BLOCK_FRAMES);
		reflectionShortScratch = BufferUtils.createShortBuffer(BLOCK_FRAMES);
		for (int index = 0; index < reflectionSlots.length; index++) {
			releaseReflectionSlot(index);
		}
		reflectionSourceBudget = 0;
		resizeReflectionPool(computeReflectionSourceBudget(directSourceCount));
		appliedReflectionVersion = -1;
		clearRawHistory();
		CinemaLog.info(CinemaLog.AUDIO, "{} reflection sources allocated",
			reflectionSourceBudget);
		if (reflectionSourceBudget < MAX_REFLECTION_SOURCES) {
			CinemaLog.warn(CinemaLog.AUDIO,
				"reflection pool limited to {} of {} sources by OpenAL source limit {}; "
					+ "lower source counts also reduce premium reflection capacity",
				reflectionSourceBudget, MAX_REFLECTION_SOURCES,
				openAlSourceLimit());
		}
	}

	private boolean allocateReflectionSlot(int index) {
		if (!ensureCinemaContext()) {
			return false;
		}
		ReflectionSlot slot = new ReflectionSlot();
		slot.source = alGenSources();
		if (slot.source == 0) {
			return false;
		}
		for (int i = 0; i < BUFFER_COUNT; i++) {
			slot.buffers[i] = alGenBuffers();
			uploadSilence(slot.buffers[i], BLOCK_FRAMES);
			slot.freeBuffers.addLast(slot.buffers[i]);
		}
		alSourcei(slot.source, AL_SOURCE_RELATIVE, AL_TRUE);
		alSourcef(slot.source, AL_MAX_GAIN, MAX_SOURCE_GAIN);
		alSourcef(slot.source, AL_MIN_GAIN, 0.0f);
		alSourcef(slot.source, AL_GAIN, 0.0f);
		reflectionSlots[index] = slot;
		return true;
	}

	private void releaseReflectionSlot(int index) {
		ReflectionSlot slot = reflectionSlots[index];
		reflectionSlots[index] = null;
		if (slot == null) {
			return;
		}
		if (slot.source != 0) {
			alSourceStop(slot.source);
			alDeleteSources(slot.source);
			slot.source = 0;
		}
		for (int buffer : slot.buffers) {
			if (buffer != 0) {
				alDeleteBuffers(buffer);
			}
		}
		Arrays.fill(slot.buffers, 0);
		slot.freeBuffers.clear();
	}

	private void resizeReflectionPool(int desired) {
		int target = Math.max(0, Math.min(MAX_REFLECTION_SOURCES, desired));
		if (target == reflectionSourceBudget) {
			return;
		}
		if (target > reflectionSourceBudget) {
			for (int index = reflectionSourceBudget; index < target; index++) {
				if (!allocateReflectionSlot(index)) {
					target = index;
					break;
				}
			}
		} else {
			for (int index = reflectionSourceBudget - 1; index >= target; index--) {
				releaseReflectionSlot(index);
			}
		}
		reflectionSourceBudget = target;
		appliedReflectionVersion = -1;
	}

	private int computeReflectionSourceBudget(int directSources) {
		int limit = openAlSourceLimit();
		int effective = Math.min(MAX_SOURCES,
			limit > 0 ? limit : MAX_SOURCES);
		return Math.max(0, Math.min(MAX_REFLECTION_SOURCES,
			effective - directSources - WET_SOURCES));
	}

	private void setupWetSources() {
		if (wetSourcesAllocated) {
			return;
		}
		for (int channel = 0; channel < WET_SOURCES; channel++) {
			wetFreeBuffers[channel].clear();
			wetSources[channel] = alGenSources();
			for (int i = 0; i < BUFFER_COUNT; i++) {
				wetBuffers[channel][i] = alGenBuffers();
				uploadSilence(wetBuffers[channel][i], BLOCK_FRAMES);
				wetFreeBuffers[channel].addLast(wetBuffers[channel][i]);
			}
			alSourcei(wetSources[channel], AL_SOURCE_RELATIVE, AL_TRUE);
			alSourcef(wetSources[channel], AL_MAX_GAIN, MAX_SOURCE_GAIN);
			alSourcef(wetSources[channel], AL_MIN_GAIN, 0.0f);
			alSourcef(wetSources[channel], AL_GAIN, 0.0f);
			float[] position = DEFAULT_POSITIONS[channel];
			alSource3f(wetSources[channel], AL_POSITION,
				position[0], position[1], position[2]);
		}
		wetSourcesAllocated = true;
		resetLateReflection();
		CinemaLog.info(CinemaLog.AUDIO, "{} late-reflection wet sources allocated",
			WET_SOURCES);
	}

	/**
	 * Stops and releases the wet-source pool. The code stays available for a
	 * later enable, but a disabled late-reflection bus should not keep six
	 * sources allocated.
	 */
	private void releaseWetSources() {
		if (!wetSourcesAllocated) {
			return;
		}
		for (int channel = 0; channel < WET_SOURCES; channel++) {
			if (wetSources[channel] != 0) {
				alSourceStop(wetSources[channel]);
				alDeleteSources(wetSources[channel]);
				wetSources[channel] = 0;
			}
			for (int buffer : wetBuffers[channel]) {
				if (buffer != 0) {
					alDeleteBuffers(buffer);
				}
			}
			Arrays.fill(wetBuffers[channel], 0);
			wetFreeBuffers[channel].clear();
		}
		wetSourcesAllocated = false;
		wetTrimGain = 0.0f;
		appliedWetGain = Float.NaN;
		appliedLateReflectionVersion = -1;
		lateBus.clear();
		CinemaLog.info(CinemaLog.AUDIO, "late-reflection wet sources released");
	}

	private void clearRawHistory() {
		for (float[] history : rawHistory) {
			Arrays.fill(history, 0.0f);
		}
		rawHistoryWriteFrame = Long.MIN_VALUE;
	}

	private void uploadSilence(int buffer, int frames) {
		uploadSamples(buffer, silenceScratch, frames);
	}

	private void uploadSamples(int buffer, float[] samples, int frames) {
		if (float32Buffers) {
			FloatBuffer data = reflectionFloatScratch;
			data.clear();
			data.put(samples, 0, frames);
			data.flip();
			alBufferData(buffer, EXTFloat32.AL_FORMAT_MONO_FLOAT32, data,
				SAMPLE_RATE);
		} else {
			ShortBuffer data = reflectionShortScratch;
			data.clear();
			for (int i = 0; i < frames; i++) {
				float value = samples[i];
				if (!Float.isFinite(value)) {
					value = 0.0f;
				}
				value = Math.max(-1.0f, Math.min(1.0f, value));
				data.put((short) Math.round(value * 32767.0f));
			}
			data.flip();
			alBufferData(buffer, AL_FORMAT_MONO16, data, SAMPLE_RATE);
		}
	}

	private static boolean supportsFloat32() {
		try {
			ALCapabilities caps = AL.getCapabilities();
			return caps.AL_EXT_FLOAT32;
		} catch (IllegalStateException ignored) {
			return false;
		}
	}

	private void applyChannelLayout() {
		int version = layoutVersion.get();
		int[] mappings = directSourceChannels;
		float[][] positions = channelPositions;
		MinimumPhaseFir[] filters = channelFilters;
		float[] occlusionGains = channelOcclusionGains;
		float[] lateSendGains = channelLateSendGains;
		int desired = Math.min(MAX_DIRECT_SOURCES, Math.max(0,
			Math.min(mappings.length, positions.length)));
		int previousCount = directSourceCount;
		// Shrink the reflection pool before growing the direct pool so the
		// combined source count never exceeds the OpenAL budget.
		if (desired > previousCount) {
			resizeReflectionPool(computeReflectionSourceBudget(desired));
		}
		ensureDirectSourceCount(desired);
		if (desired < previousCount) {
			resizeReflectionPool(computeReflectionSourceBudget(directSourceCount));
		}
		int count = Math.min(directSourceCount, desired);
		boolean firstLayout = appliedLayoutVersion < 0
			|| lastPositionUpdateNanos == 0L;
		for (int i = 0; i < count; i++) {
			MinimumPhaseFir filter = i < filters.length
				? filters[i] : MinimumPhaseFir.FLAT;
			firProcessors[i].setFilter(filter == null
				? MinimumPhaseFir.FLAT : filter);
			if (firstLayout || i >= previousCount) {
				System.arraycopy(positions[i], 0, renderedPositions[i], 0, 3);
				alSource3f(sources[i], AL_POSITION,
					renderedPositions[i][0], renderedPositions[i][1],
					renderedPositions[i][2]);
				renderedOcclusionGains[i] = i < occlusionGains.length
					? occlusionGains[i] : 1.0f;
				renderedLateSendGains[i] = i < lateSendGains.length
					? lateSendGains[i] : 1.0f;
				appliedGains[i] = Float.NaN;
			}
		}
		updateChannelAnchors(count, mappings, positions);
		Arrays.fill(activeOutputChannels, false);
		for (int i = 0; i < count; i++) {
			activeOutputChannels[mappings[i]] = true;
		}
		appliedWetGain = Float.NaN;
		if (firstLayout) {
			snapSourcePositions();
			lastGainUpdateNanos = 0L;
		}
		if (started && count > previousCount) {
			// Force the next applyPauseState() to play the newly allocated
			// direct/reflection sources.
			appliedPaused = !paused;
		}
		if (count != previousCount) {
			CinemaLog.info(CinemaLog.AUDIO,
				"direct sources {} -> {} (total budget: {} direct + {} reflection + {} wet)",
				previousCount, count, directSourceCount, reflectionSourceBudget,
				WET_SOURCES);
		}
		appliedLayoutVersion = version;
	}

	private void updateChannelAnchors(int count, int[] mappings,
									  float[][] positions) {
		for (int channel = 0; channel < CHANNELS; channel++) {
			float[] anchor = null;
			for (int source = 0; source < count; source++) {
				if (source < mappings.length && mappings[source] == channel) {
					anchor = positions[source];
					break;
				}
			}
			if (anchor == null) {
				anchor = DEFAULT_POSITIONS[channel];
			}
			System.arraycopy(anchor, 0, channelAnchorPositions[channel], 0, 3);
		}
	}

	/**
	 * Keeps OpenAL source positions between the 20 Hz layout updates. Rotating
	 * the view changes every listener-relative source position at once; feeding
	 * those jumps directly to OpenAL is audible as zipper/tearing with HRTF.
	 *
	 * <p>Interpolating the three Cartesian axes independently is wrong for a
	 * fast turn: a 180-degree flick makes the straight line pass through the
	 * listener, so the source collapses to the centre and the inverse-distance
	 * gain spikes. Smooth the direction on the unit sphere and the radius
	 * separately instead, which keeps each source on its arc.
	 */
	private void updateSourcePositions() {
		long now = System.nanoTime();
		if (lastPositionUpdateNanos == 0L) {
			lastPositionUpdateNanos = now;
			return;
		}
		double dt = (now - lastPositionUpdateNanos) / 1.0e9;
		lastPositionUpdateNanos = now;
		if (!(dt > 0.0) || dt > 0.25) {
			dt = 0.005;
		}
		double alpha = 1.0 - Math.exp(-dt / POSITION_SMOOTHING_SECONDS);
		// A stalled audio thread can leave a large dt; never let one update
		// jump more than a quarter of the remaining arc/radius.
		alpha = Math.min(0.25, alpha);
		float[][] target = channelPositions;
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] == 0 || source >= target.length) {
				continue;
			}
			if (smoothPosition(renderedPositions[source], target[source], alpha)) {
				float[] position = renderedPositions[source];
				alSource3f(sources[source], AL_POSITION,
					position[0], position[1], position[2]);
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null || slot.key.isEmpty()) {
				continue;
			}
			if (smoothPosition(slot.position, slot.targetPosition, alpha)) {
				alSource3f(slot.source, AL_POSITION, slot.position[0],
					slot.position[1], slot.position[2]);
			}
		}
		if (wetSourcesAllocated) {
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				float[] position = channelAnchorPositions[channel];
				alSource3f(wetSources[channel], AL_POSITION,
					position[0], position[1], position[2]);
			}
		}
	}

	/**
	 * Smooths one listener-relative source on its arc/radius. Returns true when
	 * the rendered position moved enough to be worth sending to OpenAL.
	 */
	private boolean smoothPosition(float[] current, float[] destination,
								   double alpha) {
		double cx = current[0];
		double cy = current[1];
		double cz = current[2];
		double dx = destination[0];
		double dy = destination[1];
		double dz = destination[2];
		double currentRadius = Math.sqrt(cx * cx + cy * cy + cz * cz);
		double targetRadius = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double x;
		double y;
		double z;
		if (currentRadius < 1.0e-6 || targetRadius < 1.0e-6) {
			x = cx + (dx - cx) * alpha;
			y = cy + (dy - cy) * alpha;
			z = cz + (dz - cz) * alpha;
		} else {
			slerpDirection(cx / currentRadius, cy / currentRadius, cz / currentRadius,
				dx / targetRadius, dy / targetRadius, dz / targetRadius, alpha,
				slerpScratch);
			double radius = currentRadius + (targetRadius - currentRadius) * alpha;
			x = slerpScratch[0] * radius;
			y = slerpScratch[1] * radius;
			z = slerpScratch[2] * radius;
		}
		boolean moved = Math.abs(x - cx) > 1.0e-5
			|| Math.abs(y - cy) > 1.0e-5
			|| Math.abs(z - cz) > 1.0e-5;
		current[0] = (float) x;
		current[1] = (float) y;
		current[2] = (float) z;
		return moved;
	}

	/**
	 * Spherical linear interpolation between two unit vectors. The antipodal
	 * case has no unique great circle; pick the one around the world-up axis
	 * so a 180-degree view flip sweeps horizontally instead of through the
	 * listener.
	 */
	private static void slerpDirection(double ux, double uy, double uz,
									   double vx, double vy, double vz,
									   double t, double[] out) {
		double dot = ux * vx + uy * vy + uz * vz;
		dot = Math.max(-1.0, Math.min(1.0, dot));
		if (dot > 0.999999) {
			double x = ux + (vx - ux) * t;
			double y = uy + (vy - uy) * t;
			double z = uz + (vz - uz) * t;
			double length = Math.sqrt(x * x + y * y + z * z);
			if (length < 1.0e-9) {
				out[0] = ux;
				out[1] = uy;
				out[2] = uz;
			} else {
				out[0] = x / length;
				out[1] = y / length;
				out[2] = z / length;
			}
			return;
		}
		if (dot < -0.999999) {
			// axis = normalize(world up - u * (u . world up))
			double ax = -ux * uy;
			double ay = 1.0 - uy * uy;
			double az = -uz * uy;
			double length = Math.sqrt(ax * ax + ay * ay + az * az);
			if (length < 1.0e-6) {
				// u is vertical; use world X as the fallback reference.
				ax = 1.0 - ux * ux;
				ay = -uy * ux;
				az = -uz * ux;
				length = Math.sqrt(ax * ax + ay * ay + az * az);
				if (length < 1.0e-6) {
					out[0] = ux;
					out[1] = uy;
					out[2] = uz;
					return;
				}
			}
			ax /= length;
			ay /= length;
			az /= length;
			double angle = t * Math.PI;
			double cos = Math.cos(angle);
			double sin = Math.sin(angle);
			// Rodrigues rotation around an axis perpendicular to u.
			out[0] = ux * cos + (ay * uz - az * uy) * sin;
			out[1] = uy * cos + (az * ux - ax * uz) * sin;
			out[2] = uz * cos + (ax * uy - ay * ux) * sin;
			return;
		}
		double theta = Math.acos(dot);
		double sinTheta = Math.sin(theta);
		double a = Math.sin((1.0 - t) * theta) / sinTheta;
		double b = Math.sin(t * theta) / sinTheta;
		out[0] = ux * a + vx * b;
		out[1] = uy * a + vy * b;
		out[2] = uz * a + vz * b;
	}

	private void snapSourcePositions() {
		float[][] target = channelPositions;
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] == 0 || source >= target.length) {
				continue;
			}
			System.arraycopy(target[source], 0, renderedPositions[source], 0, 3);
			float[] position = renderedPositions[source];
			alSource3f(sources[source], AL_POSITION,
				position[0], position[1], position[2]);
		}
		if (wetSourcesAllocated) {
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				float[] position = channelAnchorPositions[channel];
				alSource3f(wetSources[channel], AL_POSITION,
					position[0], position[1], position[2]);
			}
		}
		lastPositionUpdateNanos = System.nanoTime();
	}

	/**
	 * Applies receiver volume, speaker trim/on-axis gain, the Java
	 * inverse-distance law, the smoothed occlusion gain and the output limiter.
	 * OpenAL's own distance model is AL_NONE, so this is the only distance
	 * attenuation in the chain.
	 *
	 * <p>The limiter needs the un-limited mix, so this method first computes
	 * every source target and the per-channel worst-case peak, then derives one
	 * global gain and applies it to all three source pools.
	 */
	private void applyChannelGains() {
		long now = System.nanoTime();
		if (lastGainUpdateNanos == 0L) {
			lastGainUpdateNanos = now;
		}
		double dt = (now - lastGainUpdateNanos) / 1.0e9;
		lastGainUpdateNanos = now;
		if (!(dt > 0.0) || dt > 0.25) {
			dt = 0.005;
		}
		double alpha = Math.min(0.25,
			1.0 - Math.exp(-dt / GAIN_SMOOTHING_SECONDS));
		float master = masterMuted ? 0.0f : BASE_GAIN * masterGain;
		float[] gains = channelGains;
		float[] lateSendGains = channelLateSendGains;
		float[] occlusionGains = channelOcclusionGains;
		int[] mappings = directSourceChannels;
		Arrays.fill(predictedPeaks, 0.0f);
		for (int i = 0; i < directSourceCount; i++) {
			if (sources[i] == 0 || i >= mappings.length) {
				continue;
			}
			float occlusionTarget = i < occlusionGains.length
				? occlusionGains[i] : 1.0f;
			renderedOcclusionGains[i] += (occlusionTarget - renderedOcclusionGains[i])
				* (float) alpha;
			float lateSendTarget = i < lateSendGains.length
				? lateSendGains[i] : 1.0f;
			renderedLateSendGains[i] += (lateSendTarget - renderedLateSendGains[i])
				* (float) alpha;
			float[] position = renderedPositions[i];
			double radius = Math.sqrt(position[0] * position[0]
				+ position[1] * position[1] + position[2] * position[2]);
			double distanceGain = 1.0
				/ Math.max(AirAbsorption.REFERENCE_DISTANCE_METERS,
					AcousticScale.blocksToMeters(radius));
			float gain = i < gains.length ? gains[i] : 1.0f;
			float sourceGain = (float) (gain * distanceGain
				* renderedOcclusionGains[i]);
			if (!Float.isFinite(sourceGain)) {
				sourceGain = 0.0f;
			}
			renderedChannelGains[i] = sourceGain;
			directTargets[i] = master * sourceGain;
			if (!Float.isFinite(directTargets[i])) {
				directTargets[i] = 0.0f;
			}
			int channel = mappings[i];
			predictedPeaks[channel] += directPeak(i) * directTargets[i];
		}
		// Reflection taps already carry their own 1/max(L,1), material,
		// speaker trim and on-axis EQ in targetGain. Only the receiver master
		// gain is added here; applying the direct-path distance gain again
		// would double-attenuate every tap.
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null) {
				continue;
			}
			if (slot.key.isEmpty()) {
				slot.nextTarget = 0.0f;
				continue;
			}
			slot.gain += (slot.targetGain - slot.gain) * (float) alpha;
			slot.nextTarget = master * slot.gain;
			if (!Float.isFinite(slot.nextTarget)) {
				slot.nextTarget = 0.0f;
			}
			predictedPeaks[slot.channel] += slot.peak() * slot.nextTarget;
		}
		// The send carries speaker trim/distance/occlusion but not the receiver
		// master. The smoothed late trim and receiver master are applied through
		// the wet source AL_GAIN so trim/volume/mute also affect already queued
		// blocks on the next mixer update. AL_MAX_GAIN is raised to 64 on every
		// source, so the wet path no longer bakes positive trim into samples and
		// the output limiter can scale it like the direct and reflection paths.
		LateReflectionSettings settings = lateSettings;
		float lateGain = settings.enabled()
			? (float) Math.pow(10.0, settings.wetGainDb() / 20.0) : 0.0f;
		wetTrimGain += (lateGain - wetTrimGain) * (float) alpha;
		if (!Float.isFinite(wetTrimGain)) {
			wetTrimGain = 0.0f;
		}
		float wetTarget = master * wetTrimGain;
		if (!Float.isFinite(wetTarget)) {
			wetTarget = 0.0f;
		}
		wetNextTarget = wetTarget;
		for (int channel = 0; channel < WET_SOURCES; channel++) {
			if (activeOutputChannels[channel]) {
				predictedPeaks[channel] += wetPeak(channel) * wetNextTarget;
			}
		}
		float predictedPeak = 0.0f;
		for (float peak : predictedPeaks) {
			if (peak > predictedPeak) {
				predictedPeak = peak;
			}
		}
		limiterGain = OutputLimiter.advance(limiterGain,
			OutputLimiter.gainFor(predictedPeak), dt);
		boolean limiterChanged = Float.isNaN(appliedLimiterGain)
			|| Math.abs(limiterGain - appliedLimiterGain) >= 0.0001f;
		float limiter = limiterGain;
		for (int i = 0; i < directSourceCount; i++) {
			if (sources[i] == 0) {
				continue;
			}
			float target = directTargets[i] * limiter;
			if (!Float.isFinite(target)) {
				target = 0.0f;
			}
			if (limiterChanged || Float.isNaN(appliedGains[i])
				|| Math.abs(target - appliedGains[i]) >= 0.0005f) {
				appliedGains[i] = target;
				alSourcef(sources[i], AL_GAIN, target);
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null) {
				continue;
			}
			float target = slot.nextTarget * limiter;
			if (!Float.isFinite(target)) {
				target = 0.0f;
			}
			if (limiterChanged || Float.isNaN(slot.appliedGain)
				|| Math.abs(target - slot.appliedGain) >= 0.0005f) {
				slot.appliedGain = target;
				alSourcef(slot.source, AL_GAIN, target);
			}
		}
		float wetApplied = wetNextTarget * limiter;
		if (!Float.isFinite(wetApplied)) {
			wetApplied = 0.0f;
		}
		if (wetSourcesAllocated && (limiterChanged || Float.isNaN(appliedWetGain)
			|| Math.abs(wetApplied - appliedWetGain) >= 0.0005f)) {
			appliedWetGain = wetApplied;
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				alSourcef(wetSources[channel], AL_GAIN,
					activeOutputChannels[channel] ? wetApplied : 0.0f);
			}
		}
		appliedLimiterGain = limiter;
	}

	private float directPeak(int source) {
		float peak = 0.0f;
		for (float value : directPeakHistory[source]) {
			if (value > peak) {
				peak = value;
			}
		}
		return peak;
	}

	private float wetPeak(int channel) {
		float peak = 0.0f;
		for (float value : wetPeakHistory[channel]) {
			if (value > peak) {
				peak = value;
			}
		}
		return peak;
	}

	private void clearPeakHistory() {
		for (float[] history : directPeakHistory) {
			Arrays.fill(history, 0.0f);
		}
		for (float[] history : wetPeakHistory) {
			Arrays.fill(history, 0.0f);
		}
		directPeakIndex = 0;
		wetPeakIndex = 0;
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null) {
				slot.clearPeaks();
			}
		}
		limiterGain = 1.0f;
		appliedLimiterGain = Float.NaN;
	}

	private void resetLateReflection() {
		int version = lateSettingsVersion.get();
		lateBus.clear();
		appliedLateReflectionVersion = -1;
		appliedLateSettingsVersion = version;
		wetTrimGain = 0.0f;
		appliedWetGain = Float.NaN;
		for (float[] history : wetPeakHistory) {
			Arrays.fill(history, 0.0f);
		}
		wetPeakIndex = 0;
	}

	private void applyLateReflectionSettings() {
		int version = lateSettingsVersion.get();
		boolean enabled = lateSettings.enabled();
		if (enabled && !wetSourcesAllocated) {
			setupWetSources();
		} else if (!enabled && wetSourcesAllocated) {
			releaseWetSources();
		}
		appliedLateSettingsVersion = version;
	}

	private void applyLateReflectionTaps() {
		int version = lateReflectionVersion.get();
		if (version == appliedLateReflectionVersion) {
			return;
		}
		lateBus.setTaps(lateReflectionTargets);
		appliedLateReflectionVersion = version;
	}

	private void pump() {
		if (!ensureCinemaContext()) {
			return;
		}
		if (resetRequested) {
			performReset();
			return;
		}
		if (flushRequested) {
			performFlush();
			return;
		}
		if (appliedLayoutVersion != layoutVersion.get()) {
			applyChannelLayout();
		}
		if (appliedReflectionVersion != reflectionVersion.get()) {
			applyReflectionTargets();
		}
		if (appliedLateSettingsVersion != lateSettingsVersion.get()) {
			applyLateReflectionSettings();
		}
		if (appliedLateReflectionVersion != lateReflectionVersion.get()) {
			applyLateReflectionTaps();
		}
		updateSourcePositions();
		reclaimProcessed();
		if (pendingChunk == null) {
			pendingChunk = input.poll();
			pendingFrameOffset = 0;
		}
		while (pendingChunk != null && allSpeakersFree() && allReflectionsFree()
			&& allWetSourcesFree()) {
			int totalFrames = pendingChunk.samples().length / CHANNELS;
			int frames = Math.min(BLOCK_FRAMES, totalFrames - pendingFrameOffset);
			if (frames <= 0) {
				pendingChunk = input.poll();
				pendingFrameOffset = 0;
				continue;
			}
			queueBlock(pendingChunk, pendingFrameOffset, frames);
			pendingFrameOffset += frames;
			if (pendingFrameOffset >= totalFrames) {
				pendingChunk = input.poll();
				pendingFrameOffset = 0;
			}
		}
		// Run after queueing so the limiter sees the peaks of the blocks that
		// were just queued. The queue is still several blocks deep, so a gain
		// reduction here applies before those blocks reach the mixer.
		applyChannelGains();
		if (!started && startRequested && masterQueue.size() >= 2) {
			startAll();
		}
		if (started) {
			applyPauseState();
			updateClock();
		}
	}

	private boolean allSpeakersFree() {
		for (int i = 0; i < directSourceCount; i++) {
			if (sources[i] != 0 && freeBuffers[i].isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private boolean allReflectionsFree() {
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null && slot.freeBuffers.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private boolean allWetSourcesFree() {
		if (!wetSourcesAllocated) {
			return true;
		}
		for (Deque<Integer> free : wetFreeBuffers) {
			if (free.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private void queueBlock(AudioChunk chunk, int frameOffset, int frames) {
		float[] samples = chunk.samples();
		if (blockScratch.length < frames) {
			throw new IllegalStateException("block scratch too small: " + frames);
		}
		// Reflection taps read the raw source channel before direct-path
		// directivity, air absorption and occlusion are applied. Copy it into
		// the delay ring before any direct processing mutates the scratch.
		writeRawHistory(samples, frameOffset, frames);
		boolean lateActive = wetSourcesAllocated && lateSettings.enabled();
		if (lateActive) {
			// The geometric bus reads the raw interleaved 5.1 source channels
			// directly, before the direct-path FIR. Each tap already carries its
			// path gain, speaker trim and reflection-FIR level from the client
			// thread; the bus only adds delay, gain and damping.
			for (int channel = 0; channel < LateReflectionBus.CHANNELS; channel++) {
				float[] busInput = lateBusInput[channel];
				for (int i = 0; i < frames; i++) {
					busInput[i] = samples[(frameOffset + i) * CHANNELS + channel];
				}
			}
		}
		int[] mappings = directSourceChannels;
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] == 0 || source >= mappings.length) {
				continue;
			}
			int channel = mappings[source];
			firProcessors[source].process(samples, CHANNELS, channel, frameOffset,
				frames, blockScratch);
			directPeakHistory[source][directPeakIndex] =
				OutputLimiter.blockPeak(blockScratch, frames, !float32Buffers);
			int buffer = freeBuffers[source].removeFirst();
			if (float32Buffers) {
				FloatBuffer mono = monoFloatBuffers[source];
				mono.clear();
				mono.put(blockScratch, 0, frames);
				mono.flip();
				alBufferData(buffer, EXTFloat32.AL_FORMAT_MONO_FLOAT32, mono, SAMPLE_RATE);
			} else {
				ShortBuffer mono = monoShortBuffers[source];
				mono.clear();
				for (int i = 0; i < frames; i++) {
					float value = blockScratch[i];
					if (!Float.isFinite(value)) {
						value = 0.0f;
					}
					value = Math.max(-1.0f, Math.min(1.0f, value));
					mono.put((short) Math.round(value * 32767.0f));
				}
				mono.flip();
				alBufferData(buffer, AL_FORMAT_MONO16, mono, SAMPLE_RATE);
			}
			alSourceQueueBuffers(sources[source], buffer);
			ensureSourcePlaying(sources[source]);
		}
		directPeakIndex = (directPeakIndex + 1) % BUFFER_COUNT;
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null) {
				queueReflectionBlock(slot, frames);
			}
		}
		if (lateActive) {
			lateBus.process(lateBusInput, frames, lateBusOutput);
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				float[] wet = lateBusOutput[channel];
				wetPeakHistory[channel][wetPeakIndex] =
					OutputLimiter.blockPeak(wet, frames, !float32Buffers);
				int buffer = wetFreeBuffers[channel].removeFirst();
				uploadSamples(buffer, wet, frames);
				alSourceQueueBuffers(wetSources[channel], buffer);
				// Wet sources are created lazily when the late bus is enabled
				// during playback; queuing a buffer does not start them.
				if (started && !paused
					&& alGetSourcei(wetSources[channel], AL_SOURCE_STATE)
						!= AL_PLAYING) {
					alSourcePlay(wetSources[channel]);
				}
			}
			wetPeakIndex = (wetPeakIndex + 1) % BUFFER_COUNT;
		}
		double start = chunk.startPtsSeconds() + frameOffset / (double) SAMPLE_RATE;
		double duration = frames / (double) SAMPLE_RATE;
		masterQueue.addLast(new ChunkMeta(start, duration));
		lastEndPts = start + duration;
		checkError("queueBlock");
	}

	/** Appends one deinterleaved block to the raw-channel delay ring. */
	private void writeRawHistory(float[] samples, int frameOffset, int frames) {
		if (rawHistoryWriteFrame == Long.MIN_VALUE) {
			rawHistoryWriteFrame = 0L;
		}
		int writeIndex = (int) (rawHistoryWriteFrame & RAW_HISTORY_MASK);
		for (int channel = 0; channel < CHANNELS; channel++) {
			float[] history = rawHistory[channel];
			int index = writeIndex;
			for (int i = 0; i < frames; i++) {
				history[index] = samples[(frameOffset + i) * CHANNELS + channel];
				index = (index + 1) & RAW_HISTORY_MASK;
			}
		}
		rawHistoryWriteFrame += frames;
	}

	/**
	 * Linear-interpolated read from the raw ring. Frames before the media
	 * start or older than the ring are silence; a read at the write head is
	 * clamped to the newest sample.
	 */
	private float rawSample(int channel, double position) {
		if (rawHistoryWriteFrame == Long.MIN_VALUE || !Double.isFinite(position)) {
			return 0.0f;
		}
		long floor = (long) Math.floor(position);
		if (floor < 0 || floor < rawHistoryWriteFrame - RAW_HISTORY_FRAMES) {
			return 0.0f;
		}
		float[] history = rawHistory[channel];
		if (floor >= rawHistoryWriteFrame) {
			return history[(int) ((rawHistoryWriteFrame - 1) & RAW_HISTORY_MASK)];
		}
		float first = history[(int) (floor & RAW_HISTORY_MASK)];
		double fraction = position - floor;
		if (fraction <= 0.0) {
			return first;
		}
		long next = floor + 1;
		float second = next < rawHistoryWriteFrame
			? history[(int) (next & RAW_HISTORY_MASK)] : first;
		return (float) (first + (second - first) * fraction);
	}

	/**
	 * Feeds one block to one reflection slot. Every slot receives exactly one
	 * buffer per direct block, including idle slots (silence), so all sources
	 * keep the same queue depth and media-time alignment. The tap delay is
	 * read from the raw ring; the per-tap FIR carries the full reflection
	 * frequency shape: on-axis/directivity, air absorption, material reflection,
	 * occlusion low-pass; first-order reflections remain full-band.
	 */
	private void queueReflectionBlock(ReflectionSlot slot, int frames) {
		int buffer = slot.freeBuffers.removeFirst();
		if (slot.key.isEmpty()) {
			slot.pushPeak(0.0f);
			uploadSilence(buffer, frames);
			alSourceQueueBuffers(slot.source, buffer);
			ensureSourcePlaying(slot.source);
			return;
		}
		double startDelay = slot.delayFrames;
		double endDelay = startDelay
			+ (slot.targetDelayFrames - startDelay) * delaySmoothing(frames);
		slot.delayFrames = endDelay;
		long startFrame = rawHistoryWriteFrame - frames;
		float[] input = reflectionInputScratch;
		for (int i = 0; i < frames; i++) {
			// Glide the delay within the block so the read pointer stays
			// continuous at block boundaries (no per-block delay jump).
			double delay = startDelay
				+ (endDelay - startDelay) * ((i + 1.0) / frames);
			input[i] = rawSample(slot.channel, startFrame + i - delay);
		}
		slot.processor.process(input, 1, 0, 0, frames,
			reflectionOutputScratch);
		applyReflectionEnvelope(slot, reflectionOutputScratch, frames);
		slot.pushPeak(OutputLimiter.blockPeak(reflectionOutputScratch, frames,
			!float32Buffers));
		uploadSamples(buffer, reflectionOutputScratch, frames);
		alSourceQueueBuffers(slot.source, buffer);
		ensureSourcePlaying(slot.source);
		if (slot.fadingOut && slot.envelope <= 0.0f) {
			retireReflectionSlot(slot);
		}
	}

	private static double delaySmoothing(int frames) {
		return 1.0 - Math.exp(-frames
			/ (REFLECTION_DELAY_SMOOTHING_SECONDS * SAMPLE_RATE));
	}

	/** 30 ms sample-domain fade for tap appearance/disappearance. */
	private static void applyReflectionEnvelope(ReflectionSlot slot,
												float[] samples, int frames) {
		float envelope = slot.envelope;
		float step = slot.envelopeStep;
		for (int i = 0; i < frames; i++) {
			if (envelope > 1.0f) {
				envelope = 1.0f;
				if (step > 0.0f) {
					step = 0.0f;
				}
			} else if (envelope < 0.0f) {
				envelope = 0.0f;
				if (step < 0.0f) {
					step = 0.0f;
				}
			}
			samples[i] *= envelope;
			envelope += step;
		}
		slot.envelope = Math.max(0.0f, Math.min(1.0f, envelope));
		slot.envelopeStep = step;
	}

	private void retireReflectionSlot(ReflectionSlot slot) {
		slot.key = "";
		slot.assigned = false;
		slot.fadingOut = false;
		slot.envelope = 0.0f;
		slot.envelopeStep = 0.0f;
		slot.gain = 0.0f;
		slot.targetGain = 0.0f;
		slot.appliedGain = 0.0f;
		slot.nextTarget = 0.0f;
		slot.clearPeaks();
		slot.processor.reset();
		alSourcef(slot.source, AL_GAIN, 0.0f);
	}

	private void ensureSourcePlaying(int source) {
		if (source == 0 || !started || paused) {
			return;
		}
		if (alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING) {
			alSourcePlay(source);
		}
	}

	private void startAll() {
		long now = SOFTDeviceClock.alcGetInteger64vSOFT(currentDevice(), SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
		long scheduled = now + 150_000_000L;
		scheduledStartSystemNanos = System.nanoTime() + 150_000_000L;
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] != 0) {
				SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(sources[source], scheduled);
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null) {
				SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(slot.source, scheduled);
			}
		}
		for (int source : wetSources) {
			if (source != 0) {
				SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, scheduled);
			}
		}
		started = true;
		CinemaLog.info(CinemaLog.AUDIO, "six sources scheduled in {} ms (src0 state={}, gain={}, queued={}, muted={})",
			String.format("%.1f", (scheduled - now) / 1e6),
			alGetSourcei(sources[0], AL_SOURCE_STATE),
			String.format("%.3f", alGetSourcef(sources[0], AL_GAIN)),
			alGetSourcei(sources[0], AL_BUFFERS_QUEUED),
			masterMuted);
		checkError("startAll");
	}

	private long currentDevice() {
		long context = ALC10.alcGetCurrentContext();
		if (context == NULL) {
			return NULL;
		}
		return ALC10.alcGetContextsDevice(context);
	}

	private void applyPauseState() {
		if (paused == appliedPaused) {
			return;
		}
		appliedPaused = paused;
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] == 0) {
				continue;
			}
			if (paused) {
				alSourcePause(sources[source]);
			} else {
				alSourcePlay(sources[source]);
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null) {
				continue;
			}
			if (paused) {
				alSourcePause(slot.source);
			} else {
				alSourcePlay(slot.source);
			}
		}
		for (int source : wetSources) {
			if (source == 0) {
				continue;
			}
			if (paused) {
				alSourcePause(source);
			} else {
				alSourcePlay(source);
			}
		}
	}

	private void updateClock() {
		if (masterQueue.isEmpty()) {
			audiblePts = lastEndPts;
			return;
		}
		if (directSourceCount == 0) {
			// No physical channels: advance the media clock from the scheduled
			// start instead of querying a non-existent OpenAL source.
			if (!started || paused || scheduledStartSystemNanos == 0L) {
				return;
			}
			double elapsed = (System.nanoTime() - scheduledStartSystemNanos) / 1.0e9;
			audiblePts = Math.max(0.0, resetStartPts + Math.max(0.0, elapsed));
			while (!masterQueue.isEmpty()) {
				ChunkMeta meta = masterQueue.peekFirst();
				if (meta.startPtsSeconds + meta.durationSeconds
					> audiblePts + 0.001) {
					break;
				}
				masterQueue.removeFirst();
			}
			latencySeconds = 0.0;
			return;
		}
		int state = alGetSourcei(sources[0], AL_SOURCE_STATE);
		if (state != AL_PLAYING && state != AL_PAUSED) {
			audiblePts = lastEndPts;
			return;
		}
		latencyQuery.clear();
		SOFTSourceLatency.alGetSourcedvSOFT(sources[0],
			SOFTSourceLatency.AL_SEC_OFFSET_LATENCY_SOFT, latencyQuery);
		double offset = latencyQuery.get(0);
		double latency = latencyQuery.get(1);
		latencySeconds = latency;
		double pts = masterQueue.peekFirst().startPtsSeconds + offset;
		if (state == AL_PLAYING) {
			pts -= latency;
		}
		audiblePts = Math.max(0.0, pts);
	}

	private void reclaimProcessed() {
		for (int source = 0; source < directSourceCount; source++) {
			if (sources[source] == 0) {
				continue;
			}
			int processed = alGetSourcei(sources[source], AL_BUFFERS_PROCESSED);
			if (processed <= 0) {
				continue;
			}
			IntBuffer released = BufferUtils.createIntBuffer(processed);
			alSourceUnqueueBuffers(sources[source], released);
			for (int i = 0; i < processed; i++) {
				freeBuffers[source].addLast(released.get(i));
			}
			if (source == 0) {
				for (int i = 0; i < processed && !masterQueue.isEmpty(); i++) {
					masterQueue.removeFirst();
				}
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null) {
				continue;
			}
			int processed = alGetSourcei(slot.source, AL_BUFFERS_PROCESSED);
			if (processed <= 0) {
				continue;
			}
			reflectionReclaimScratch.clear();
			int count = Math.min(processed, reflectionReclaimScratch.capacity());
			reflectionReclaimScratch.limit(count);
			alSourceUnqueueBuffers(slot.source, reflectionReclaimScratch);
			for (int i = 0; i < count; i++) {
				slot.freeBuffers.addLast(reflectionReclaimScratch.get(i));
			}
			reflectionReclaimScratch.limit(reflectionReclaimScratch.capacity());
		}
		if (wetSourcesAllocated) {
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				int processed = alGetSourcei(wetSources[channel],
					AL_BUFFERS_PROCESSED);
				if (processed <= 0) {
					continue;
				}
				reflectionReclaimScratch.clear();
				int count = Math.min(processed, reflectionReclaimScratch.capacity());
				reflectionReclaimScratch.limit(count);
				alSourceUnqueueBuffers(wetSources[channel], reflectionReclaimScratch);
				for (int i = 0; i < count; i++) {
					wetFreeBuffers[channel].addLast(reflectionReclaimScratch.get(i));
				}
				reflectionReclaimScratch.limit(reflectionReclaimScratch.capacity());
			}
		}
	}

	private void cleanup() {
		if (!ensureCinemaContext()) {
			directSourceCount = 0;
			reflectionSourceBudget = 0;
			wetSourcesAllocated = false;
			appliedLateReflectionVersion = -1;
			lateBus.clear();
			return;
		}
		for (int i = 0; i < MAX_DIRECT_SOURCES; i++) {
			releaseDirectSource(i);
		}
		directSourceCount = 0;
		for (int index = 0; index < reflectionSlots.length; index++) {
			releaseReflectionSlot(index);
		}
		reflectionSourceBudget = 0;
		for (int channel = 0; channel < WET_SOURCES; channel++) {
			if (wetSources[channel] != 0) {
				alSourceStop(wetSources[channel]);
				alDeleteSources(wetSources[channel]);
				wetSources[channel] = 0;
			}
			for (int buffer : wetBuffers[channel]) {
				if (buffer != 0) {
					alDeleteBuffers(buffer);
				}
			}
			Arrays.fill(wetBuffers[channel], 0);
			wetFreeBuffers[channel].clear();
		}
		wetSourcesAllocated = false;
		appliedLateReflectionVersion = -1;
		lateBus.clear();
	}

	private static void checkError(String where) {
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " at " + where);
		}
	}

	private synchronized void closeDevice() {
		long context = contextHandle;
		long device = deviceHandle;
		contextHandle = NULL;
		deviceHandle = NULL;
		if (context != NULL) {
			if (ALC10.alcGetCurrentContext() == context) {
				ALC10.alcMakeContextCurrent(previousGlobalContext != NULL
					? previousGlobalContext : NULL);
			}
			ALC10.alcDestroyContext(context);
		}
		if (device != NULL && ownsDevice) {
			ALC10.alcCloseDevice(device);
		}
	}

	private void performFlush() {
		boolean valid = true;
		for (int i = 0; i < directSourceCount; i++) {
			if (sources[i] == 0) {
				continue;
			}
			alSourceStop(sources[i]);
			int queued = alGetSourcei(sources[i], AL_BUFFERS_QUEUED);
			if (queued > 0) {
				IntBuffer released = BufferUtils.createIntBuffer(queued);
				alSourceUnqueueBuffers(sources[i], released);
				for (int j = 0; j < queued; j++) {
					int buffer = released.get(j);
					if (buffer == 0) {
						valid = false;
					} else {
						freeBuffers[i].addLast(buffer);
					}
				}
			}
			alSourcei(sources[i], AL_BUFFER, 0);
			// alSourceStop keeps the old playback offset. Without rewinding,
			// newly queued buffers can start past their end, which is heard as
			// silence after a seek/restart even though the clock keeps moving.
			alSourceRewind(sources[i]);
			appliedGains[i] = Float.NaN;
		}
		appliedLayoutVersion = -1;
		for (FirProcessor processor : firProcessors) {
			processor.reset();
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot == null) {
				continue;
			}
			alSourceStop(slot.source);
			int queued = alGetSourcei(slot.source, AL_BUFFERS_QUEUED);
			if (queued > 0) {
				IntBuffer released = BufferUtils.createIntBuffer(queued);
				alSourceUnqueueBuffers(slot.source, released);
				for (int j = 0; j < queued; j++) {
					int buffer = released.get(j);
					if (buffer == 0) {
						valid = false;
					} else {
						slot.freeBuffers.addLast(buffer);
					}
				}
			}
			alSourcei(slot.source, AL_BUFFER, 0);
			alSourceRewind(slot.source);
			slot.processor.reset();
			slot.key = "";
			slot.assigned = false;
			slot.fadingOut = false;
			slot.envelope = 0.0f;
			slot.envelopeStep = 0.0f;
			slot.gain = 0.0f;
			slot.targetGain = 0.0f;
			slot.appliedGain = 0.0f;
			alSourcef(slot.source, AL_GAIN, 0.0f);
		}
		if (wetSourcesAllocated) {
			for (int channel = 0; channel < WET_SOURCES; channel++) {
				alSourceStop(wetSources[channel]);
				int queued = alGetSourcei(wetSources[channel], AL_BUFFERS_QUEUED);
				if (queued > 0) {
					IntBuffer released = BufferUtils.createIntBuffer(queued);
					alSourceUnqueueBuffers(wetSources[channel], released);
					for (int i = 0; i < queued; i++) {
						int buffer = released.get(i);
						if (buffer == 0) {
							valid = false;
						} else {
							wetFreeBuffers[channel].addLast(buffer);
						}
					}
				}
				alSourcei(wetSources[channel], AL_BUFFER, 0);
				alSourceRewind(wetSources[channel]);
				alSourcef(wetSources[channel], AL_GAIN, 0.0f);
			}
		}
		resetLateReflection();
		clearRawHistory();
		appliedReflectionVersion = -1;
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			CinemaLog.warn(CinemaLog.AUDIO, "flush failed with OpenAL error 0x{}; rebuilding sources",
				Integer.toHexString(error));
			valid = false;
		}
		if (!valid || !allBuffersFree()) {
			// Last-resort recovery for an inconsistent source pool. This is the
			// only path in flush that recreates sources.
			cleanup();
			for (Deque<Integer> free : freeBuffers) {
				free.clear();
			}
			setupSources();
		}
		clearQueues();
		started = false;
		startRequested = false;
		paused = false;
		appliedPaused = false;
		audiblePts = resetStartPts;
		latencySeconds = 0;
		scheduledStartSystemNanos = 0;
		lastEndPts = resetStartPts;
		flushRequested = false;
		java.util.concurrent.CountDownLatch latch = resetDone;
		if (latch != null) {
			latch.countDown();
		}
	}

	private boolean allBuffersFree() {
		for (int i = 0; i < directSourceCount; i++) {
			if (sources[i] != 0
				&& freeBuffers[i].size() != BUFFER_COUNT) {
				return false;
			}
		}
		for (ReflectionSlot slot : reflectionSlots) {
			if (slot != null && slot.freeBuffers.size() != BUFFER_COUNT) {
				return false;
			}
		}
		for (Deque<Integer> free : wetFreeBuffers) {
			if (wetSourcesAllocated && free.size() != BUFFER_COUNT) {
				return false;
			}
		}
		return true;
	}

	private void performReset() {
		cleanup();
		for (Deque<Integer> free : freeBuffers) {
			free.clear();
		}
		clearQueues();
		started = false;
		startRequested = false;
		paused = false;
		appliedPaused = false;
		audiblePts = resetStartPts;
		latencySeconds = 0;
		scheduledStartSystemNanos = 0;
		lastEndPts = resetStartPts;
		setupSources();
		resetRequested = false;
		java.util.concurrent.CountDownLatch latch = resetDone;
		if (latch != null) {
			latch.countDown();
		}
	}

	private void clearQueues() {
		input.clear();
		masterQueue.clear();
		pendingChunk = null;
		pendingFrameOffset = 0;
		clearPeakHistory();
	}

	@Override
	public void close() {
		running.set(false);
		thread.interrupt();
		try {
			thread.join(1_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		OPEN_ENGINES.remove(this);
		if (!thread.isAlive()) {
			closeDevice();
		}
	}

	private record ChunkMeta(double startPtsSeconds, double durationSeconds) {
	}

	/** One fixed reflection source plus its delay/fade/filter state. */
	private static final class ReflectionSlot {
		private int source;
		private final int[] buffers = new int[BUFFER_COUNT];
		private final Deque<Integer> freeBuffers = new ArrayDeque<>(BUFFER_COUNT);
		private final FirProcessor processor = new FirProcessor();
		private final float[] position = new float[3];
		private final float[] targetPosition = new float[3];
		private String key = "";
		private int channel;
		private float gain;
		private float targetGain;
		private float appliedGain;
		private float envelope;
		private float envelopeStep;
		private boolean assigned;
		private boolean fadingOut;
		private double delayFrames;
		private double targetDelayFrames;
		private MinimumPhaseFir filter = MinimumPhaseFir.FLAT;
		private float nextTarget;
		private final float[] peakHistory = new float[BUFFER_COUNT];
		private int peakIndex;

		private void pushPeak(float peak) {
			peakHistory[peakIndex] = peak;
			peakIndex = (peakIndex + 1) % BUFFER_COUNT;
		}

		private float peak() {
			float peak = 0.0f;
			for (float value : peakHistory) {
				if (value > peak) {
					peak = value;
				}
			}
			return peak;
		}

		private void clearPeaks() {
			Arrays.fill(peakHistory, 0.0f);
			peakIndex = 0;
		}
	}
}
