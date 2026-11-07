package com.example.cinema.client.media;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.directivity.FirProcessor;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.environment.AirAbsorption;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
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
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Six independent mono OpenAL sources in one cinema-owned context.
 *
 * <p>Each AV receiver playback proxy owns one engine/context, so multiple
 * receivers can play independently. The decoder submits interleaved 5.1 chunks.
 * The audio thread deinterleaves each chunk, queues one mono buffer per speaker,
 * and schedules all six sources at the same device-clock instant. It also
 * publishes the audible media clock so the render thread can choose video frames
 * without touching OpenAL.
 */
public final class CinemaAudioEngine implements AutoCloseable {
	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 6;
	private static final int SPEAKERS = 6;
	private static final int BUFFER_COUNT = 4;
	/** Small queue blocks keep directivity updates within ~170 ms of playback. */
	private static final int BLOCK_FRAMES = 2_048;
	private static final float BASE_GAIN = 0.65f;
	/** Time constant for smoothing listener-relative source positions. */
	private static final double POSITION_SMOOTHING_SECONDS = 0.03;
	/** Time constant for smoothing occlusion-driven source gain changes. */
	private static final double GAIN_SMOOTHING_SECONDS = 0.03;
	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final Set<CinemaAudioEngine> OPEN_ENGINES = ConcurrentHashMap.newKeySet();

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
	private final Deque<Integer>[] freeBuffers = new Deque[SPEAKERS];
	private final int[] sources = new int[SPEAKERS];
	private final int[][] buffers = new int[SPEAKERS][BUFFER_COUNT];
	private final ShortBuffer[] monoShortBuffers = new ShortBuffer[SPEAKERS];
	private final FloatBuffer[] monoFloatBuffers = new FloatBuffer[SPEAKERS];
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
	private final AtomicInteger layoutVersion = new AtomicInteger();
	private volatile float[][] channelPositions = copyDefaultPositions();
	private final float[][] renderedPositions = copyDefaultPositions();
	private final double[] slerpScratch = new double[3];
	private long lastPositionUpdateNanos;
	private volatile float[] channelGains = defaultChannelGains();
	private volatile MinimumPhaseFir[] channelFilters = defaultFilters();
	private volatile MinimumPhaseFir[] channelEnvironmentFilters = defaultFilters();
	private volatile float[] channelOcclusionGains = defaultChannelGains();
	private final FirProcessor[] firProcessors = new FirProcessor[SPEAKERS];
	private final FirProcessor[] environmentProcessors = new FirProcessor[SPEAKERS];
	private final float[] appliedGains = new float[SPEAKERS];
	private final float[] renderedOcclusionGains = defaultChannelGains();
	private final float[] environmentScratch = new float[BLOCK_FRAMES];
	private int appliedLayoutVersion = -1;
	private long lastGainUpdateNanos;
	private volatile double audiblePts;
	private volatile double latencySeconds;
	private volatile long scheduledStartSystemNanos;
	private double lastEndPts;
	private ALCapabilities previousProcessCaps;
	private boolean threadLocalContext;
	private long deviceHandle = NULL;
	private long contextHandle = NULL;
	private boolean ownsDevice;

	public CinemaAudioEngine() {
		for (int i = 0; i < SPEAKERS; i++) {
			freeBuffers[i] = new ArrayDeque<>(BUFFER_COUNT);
			appliedGains[i] = Float.NaN;
			firProcessors[i] = new FirProcessor();
			environmentProcessors[i] = new FirProcessor();
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
	 * The receiver allows up to +6 dB (linear 1.995), so this is intentionally
	 * not clamped to 1.0; loud material may clip above 0 dBFS.
	 */
	public void setMasterGain(float volume, boolean muted) {
		masterGain = Math.max(0.0f, Math.min(2.0f, volume));
		masterMuted = muted;
	}

	/**
	 * Updates the six mono source positions (listener-relative: x right, y up,
	 * z forward is negative), per-channel linear gains, directivity filters,
	 * environment filters and broadband occlusion gains. Null entries keep the
	 * default virtual 5.1 layout for that channel. Channel gain is capped at
	 * +6 dB to match the receiver's level limit.
	 *
	 * <p>Distance attenuation is computed here from the smoothed source radius;
	 * the environment FIR carries only air absorption and the occlusion
	 * low-pass shape. The occlusion gain is smoothed before it reaches AL_GAIN
	 * so a newly blocked path does not click.
	 */
	public void setChannelLayout(float[][] positions, float[] gains) {
		setChannelLayout(positions, gains, null, null, null);
	}

	public void setChannelLayout(float[][] positions, float[] gains,
								 MinimumPhaseFir[] filters) {
		setChannelLayout(positions, gains, filters, null, null);
	}

	public void setChannelLayout(float[][] positions, float[] gains,
								 MinimumPhaseFir[] filters,
								 MinimumPhaseFir[] environmentFilters,
								 float[] occlusionGains) {
		float[][] nextPositions = new float[SPEAKERS][];
		float[] nextGains = new float[SPEAKERS];
		MinimumPhaseFir[] nextFilters = new MinimumPhaseFir[SPEAKERS];
		MinimumPhaseFir[] nextEnvironmentFilters = new MinimumPhaseFir[SPEAKERS];
		float[] nextOcclusionGains = new float[SPEAKERS];
		for (int i = 0; i < SPEAKERS; i++) {
			float[] position = positions != null && i < positions.length ? positions[i] : null;
			nextPositions[i] = isValidPosition(position)
				? position.clone() : DEFAULT_POSITIONS[i].clone();
			float gain = gains != null && i < gains.length ? gains[i] : 1.0f;
			nextGains[i] = Float.isFinite(gain)
				? Math.max(0.0f, Math.min(2.0f, gain)) : 1.0f;
			MinimumPhaseFir filter = filters != null && i < filters.length ? filters[i] : null;
			nextFilters[i] = filter == null ? MinimumPhaseFir.FLAT : filter;
			MinimumPhaseFir environment = environmentFilters != null
				&& i < environmentFilters.length ? environmentFilters[i] : null;
			nextEnvironmentFilters[i] = environment == null
				? MinimumPhaseFir.FLAT : environment;
			float occlusion = occlusionGains != null && i < occlusionGains.length
				? occlusionGains[i] : 1.0f;
			nextOcclusionGains[i] = Float.isFinite(occlusion)
				? Math.max(0.0f, Math.min(1.0f, occlusion)) : 1.0f;
		}
		if (sameLayout(nextPositions, nextGains, nextFilters,
			nextEnvironmentFilters, nextOcclusionGains)) {
			return;
		}
		channelPositions = nextPositions;
		channelGains = nextGains;
		channelFilters = nextFilters;
		channelEnvironmentFilters = nextEnvironmentFilters;
		channelOcclusionGains = nextOcclusionGains;
		layoutVersion.incrementAndGet();
	}

	private boolean sameLayout(float[][] positions, float[] gains,
							   MinimumPhaseFir[] filters,
							   MinimumPhaseFir[] environmentFilters,
							   float[] occlusionGains) {
		float[][] currentPositions = channelPositions;
		float[] currentGains = channelGains;
		MinimumPhaseFir[] currentFilters = channelFilters;
		MinimumPhaseFir[] currentEnvironmentFilters = channelEnvironmentFilters;
		float[] currentOcclusionGains = channelOcclusionGains;
		for (int i = 0; i < SPEAKERS; i++) {
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
			if (environmentFilters[i] != currentEnvironmentFilters[i]) {
				return false;
			}
			if (Math.abs(occlusionGains[i] - currentOcclusionGains[i]) > 1.0e-4f) {
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

	private static float[][] copyDefaultPositions() {
		float[][] copy = new float[SPEAKERS][];
		for (int i = 0; i < SPEAKERS; i++) {
			copy[i] = DEFAULT_POSITIONS[i].clone();
		}
		return copy;
	}

	/**
	 * Distance of a fallback virtual channel from the listener, used by the
	 * client to design its air-absorption filter. The engine still owns the
	 * actual distance gain from the smoothed rendered position.
	 */
	public static double defaultSourceDistance(int channel) {
		if (channel < 0 || channel >= SPEAKERS) {
			return 1.0;
		}
		float[] position = DEFAULT_POSITIONS[channel];
		return Math.sqrt(position[0] * position[0] + position[1] * position[1]
			+ position[2] * position[2]);
	}

	private static float[] defaultChannelGains() {
		float[] gains = new float[SPEAKERS];
		for (int i = 0; i < SPEAKERS; i++) {
			gains[i] = 1.0f;
		}
		return gains;
	}

	private static MinimumPhaseFir[] defaultFilters() {
		MinimumPhaseFir[] filters = new MinimumPhaseFir[SPEAKERS];
		for (int i = 0; i < SPEAKERS; i++) {
			filters[i] = MinimumPhaseFir.FLAT;
		}
		return filters;
	}

	private void run() {
		long device = NULL;
		long context = NULL;
		try {
			device = MinecraftOpenAl.device();
			if (device == NULL) {
				Cinema.LOGGER.warn("cinema audio: Minecraft OpenAL device unavailable; opening a fallback device");
				device = ALC10.alcOpenDevice((CharSequence) null);
				ownsDevice = true;
			}
			if (device == NULL) {
				throw new IllegalStateException("alcOpenDevice() failed");
			}
			deviceHandle = device;
			ALCCapabilities deviceCaps = ALC.createCapabilities(device);
			threadLocalContext = deviceCaps.ALC_EXT_thread_local_context;
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
			} else {
				ALC10.alcMakeContextCurrent(context);
			}
			if (previousProcessCaps != null) {
				// Reuse Minecraft's AL function pointers. Calling
				// AL.createCapabilities() here replaces LWJGL's process-wide ICD
				// with the cinema context and upsets SoundEngine at shutdown.
				AL.setCurrentThread(previousProcessCaps);
			} else {
				AL.createCapabilities(deviceCaps);
			}
			Cinema.LOGGER.info("cinema audio: OpenAL {} on {} (sharedCaps={})", AL10.alGetString(AL10.AL_VERSION),
				ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER), previousProcessCaps != null);

			setupSources();
			while (running.get()) {
				pump();
				Thread.sleep(5);
			}
		} catch (InterruptedException ignored) {
			// stop() interrupts the pump loop.
		} catch (Throwable t) {
			Cinema.LOGGER.error("cinema audio engine failed", t);
		} finally {
			cleanup();
			// Never replace Minecraft's process-wide OpenAL context/capabilities.
			// Clear only this thread's context.
			if (threadLocalContext) {
				EXTThreadLocalContext.alcSetThreadContext(NULL);
			} else {
				ALC10.alcMakeContextCurrent(NULL);
			}
			AL.setCurrentThread(previousProcessCaps);
		}
	}

	private void setupSources() {
		AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
		AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{0f, 0f, -1f, 0f, 1f, 0f});
		// Distance and air absorption are applied in Java: source positions are
		// listener-relative, AL_GAIN carries 1/max(r,1), and the environment FIR
		// carries the frequency-dependent part. AL_NONE prevents double
		// attenuation.
		AL10.alDistanceModel(AL10.AL_NONE);
		float32Buffers = supportsFloat32();
		appliedLayoutVersion = -1;
		lastPositionUpdateNanos = 0L;
		lastGainUpdateNanos = 0L;

		for (int i = 0; i < SPEAKERS; i++) {
			sources[i] = alGenSources();
			for (int j = 0; j < BUFFER_COUNT; j++) {
				buffers[i][j] = alGenBuffers();
				freeBuffers[i].addLast(buffers[i][j]);
			}
			if (float32Buffers) {
				monoFloatBuffers[i] = BufferUtils.createFloatBuffer(BLOCK_FRAMES);
			} else {
				monoShortBuffers[i] = BufferUtils.createShortBuffer(BLOCK_FRAMES);
			}
			alSourcei(sources[i], AL_SOURCE_RELATIVE, AL_TRUE);
			appliedGains[i] = Float.NaN;
			firProcessors[i].reset();
			environmentProcessors[i].reset();
		}
		Cinema.LOGGER.info("cinema audio: source buffers are {}; distance model AL_NONE",
			float32Buffers ? "32-bit float" : "16-bit integer");
		applyChannelLayout();
		applyChannelGains();
		checkError("setupSources");
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
		MinimumPhaseFir[] filters = channelFilters;
		MinimumPhaseFir[] environmentFilters = channelEnvironmentFilters;
		for (int i = 0; i < SPEAKERS; i++) {
			firProcessors[i].setFilter(filters[i]);
			environmentProcessors[i].setFilter(environmentFilters[i]);
		}
		if (appliedLayoutVersion < 0 || lastPositionUpdateNanos == 0L) {
			snapSourcePositions();
			System.arraycopy(channelOcclusionGains, 0, renderedOcclusionGains, 0,
				SPEAKERS);
			lastGainUpdateNanos = 0L;
		}
		appliedLayoutVersion = version;
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
		for (int channel = 0; channel < SPEAKERS; channel++) {
			float[] current = renderedPositions[channel];
			float[] destination = target[channel];
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
			if (moved) {
				alSource3f(sources[channel], AL_POSITION,
					current[0], current[1], current[2]);
			}
		}
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
		for (int channel = 0; channel < SPEAKERS; channel++) {
			System.arraycopy(target[channel], 0, renderedPositions[channel], 0, 3);
			float[] position = renderedPositions[channel];
			alSource3f(sources[channel], AL_POSITION,
				position[0], position[1], position[2]);
		}
		lastPositionUpdateNanos = System.nanoTime();
	}

	/**
	 * Applies receiver volume, speaker trim, directivity gain, the Java
	 * inverse-distance law and the smoothed occlusion gain. OpenAL's own
	 * distance model is AL_NONE, so this is the only distance attenuation in the
	 * chain.
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
		float[] occlusionGains = channelOcclusionGains;
		for (int i = 0; i < SPEAKERS; i++) {
			float occlusionTarget = occlusionGains[i];
			renderedOcclusionGains[i] += (occlusionTarget - renderedOcclusionGains[i])
				* (float) alpha;
			float[] position = renderedPositions[i];
			double radius = Math.sqrt(position[0] * position[0]
				+ position[1] * position[1] + position[2] * position[2]);
			double distanceGain = 1.0
				/ Math.max(AirAbsorption.REFERENCE_DISTANCE_METERS, radius);
			float target = (float) (master * gains[i] * distanceGain
				* renderedOcclusionGains[i]);
			if (!Float.isFinite(target)) {
				target = 0.0f;
			}
			if (Float.isNaN(appliedGains[i])
				|| Math.abs(target - appliedGains[i]) >= 0.0005f) {
				appliedGains[i] = target;
				alSourcef(sources[i], AL_GAIN, target);
			}
		}
	}

	private void pump() {
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
		updateSourcePositions();
		applyChannelGains();
		reclaimProcessed();
		if (pendingChunk == null) {
			pendingChunk = input.poll();
			pendingFrameOffset = 0;
		}
		while (pendingChunk != null && allSpeakersFree()) {
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
		if (!started && startRequested && masterQueue.size() >= 2) {
			startAll();
		}
		if (started) {
			applyPauseState();
			updateClock();
		}
	}

	private boolean allSpeakersFree() {
		for (Deque<Integer> free : freeBuffers) {
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
		for (int channel = 0; channel < SPEAKERS; channel++) {
			firProcessors[channel].process(samples, CHANNELS, channel, frameOffset,
				frames, blockScratch);
			// Second stage: air absorption + occlusion low-pass. It operates on
			// the already directivity-filtered mono signal and its own 1024-frame
			// crossfade keeps filter changes click-free.
			environmentProcessors[channel].process(blockScratch, 1, 0, 0, frames,
				environmentScratch);
			int buffer = freeBuffers[channel].removeFirst();
			if (float32Buffers) {
				FloatBuffer mono = monoFloatBuffers[channel];
				mono.clear();
				mono.put(environmentScratch, 0, frames);
				mono.flip();
				alBufferData(buffer, EXTFloat32.AL_FORMAT_MONO_FLOAT32, mono, SAMPLE_RATE);
			} else {
				ShortBuffer mono = monoShortBuffers[channel];
				mono.clear();
				for (int i = 0; i < frames; i++) {
					float value = environmentScratch[i];
					if (!Float.isFinite(value)) {
						value = 0.0f;
					}
					value = Math.max(-1.0f, Math.min(1.0f, value));
					mono.put((short) Math.round(value * 32767.0f));
				}
				mono.flip();
				alBufferData(buffer, AL_FORMAT_MONO16, mono, SAMPLE_RATE);
			}
			alSourceQueueBuffers(sources[channel], buffer);
		}
		double start = chunk.startPtsSeconds() + frameOffset / (double) SAMPLE_RATE;
		double duration = frames / (double) SAMPLE_RATE;
		masterQueue.addLast(new ChunkMeta(start, duration));
		lastEndPts = start + duration;
		checkError("queueBlock");
	}

	private void startAll() {
		long now = SOFTDeviceClock.alcGetInteger64vSOFT(currentDevice(), SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
		long scheduled = now + 150_000_000L;
		scheduledStartSystemNanos = System.nanoTime() + 150_000_000L;
		for (int source : sources) {
			SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, scheduled);
		}
		started = true;
		Cinema.LOGGER.info("cinema audio: six sources scheduled in {} ms (src0 state={}, gain={}, queued={}, muted={})",
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
		for (int source : sources) {
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
		for (int channel = 0; channel < SPEAKERS; channel++) {
			int processed = alGetSourcei(sources[channel], AL_BUFFERS_PROCESSED);
			if (processed <= 0) {
				continue;
			}
			IntBuffer released = BufferUtils.createIntBuffer(processed);
			alSourceUnqueueBuffers(sources[channel], released);
			for (int i = 0; i < processed; i++) {
				freeBuffers[channel].addLast(released.get(i));
			}
			if (channel == 0) {
				for (int i = 0; i < processed && !masterQueue.isEmpty(); i++) {
					masterQueue.removeFirst();
				}
			}
		}
	}

	private void cleanup() {
		for (int source : sources) {
			if (source != 0) {
				alSourceStop(source);
				alDeleteSources(source);
			}
		}
		for (int[] channelBuffers : buffers) {
			for (int buffer : channelBuffers) {
				if (buffer != 0) {
					alDeleteBuffers(buffer);
				}
			}
		}
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
			ALC10.alcDestroyContext(context);
		}
		if (device != NULL && ownsDevice) {
			ALC10.alcCloseDevice(device);
		}
	}

	private void performFlush() {
		boolean valid = true;
		for (int i = 0; i < SPEAKERS; i++) {
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
		for (FirProcessor processor : environmentProcessors) {
			processor.reset();
		}
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			Cinema.LOGGER.warn("cinema audio: flush failed with OpenAL error 0x{}; rebuilding sources",
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
		for (Deque<Integer> free : freeBuffers) {
			if (free.size() != BUFFER_COUNT) {
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
}
