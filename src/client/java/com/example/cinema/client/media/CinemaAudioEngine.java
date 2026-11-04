package com.example.cinema.client.media;

import com.example.cinema.Cinema;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;
import org.lwjgl.openal.SOFTSourceStartDelay;

import java.nio.DoubleBuffer;
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
	private static final float BASE_GAIN = 0.65f;
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
	private volatile java.util.concurrent.CountDownLatch resetDone;

	@SuppressWarnings("unchecked")
	private final Deque<Integer>[] freeBuffers = new Deque[SPEAKERS];
	private final int[] sources = new int[SPEAKERS];
	private final int[][] buffers = new int[SPEAKERS][BUFFER_COUNT];
	private final ShortBuffer[] monoBuffers = new ShortBuffer[SPEAKERS];
	private final DoubleBuffer latencyQuery = BufferUtils.createDoubleBuffer(2);

	private AudioChunk pendingChunk;
	private volatile boolean startRequested;
	private volatile boolean started;
	private volatile boolean paused;
	private boolean appliedPaused;
	private volatile float masterGain = 1.0f;
	private volatile boolean masterMuted;
	private final AtomicInteger layoutVersion = new AtomicInteger();
	private volatile float[][] channelPositions = copyDefaultPositions();
	private volatile float[] channelGains = defaultChannelGains();
	private final float[] appliedGains = new float[SPEAKERS];
	private int appliedLayoutVersion = -1;
	private volatile double audiblePts;
	private volatile double latencySeconds;
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
		if (!running.get()) {
			return;
		}
		if (!threadStarted.get()) {
			clearQueues();
			return;
		}
		if (!thread.isAlive()) {
			return;
		}
		java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
		resetDone = latch;
		resetRequested = true;
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
	 * z forward is negative) and per-channel linear gains. Null entries keep the
	 * default virtual 5.1 layout for that channel. Channel gain is capped at
	 * +6 dB to match the receiver's level limit.
	 */
	public void setChannelLayout(float[][] positions, float[] gains) {
		float[][] nextPositions = new float[SPEAKERS][];
		float[] nextGains = new float[SPEAKERS];
		for (int i = 0; i < SPEAKERS; i++) {
			float[] position = positions != null && i < positions.length ? positions[i] : null;
			nextPositions[i] = isValidPosition(position)
				? position.clone() : DEFAULT_POSITIONS[i].clone();
			float gain = gains != null && i < gains.length ? gains[i] : 1.0f;
			nextGains[i] = Float.isFinite(gain)
				? Math.max(0.0f, Math.min(2.0f, gain)) : 1.0f;
		}
		if (sameLayout(nextPositions, nextGains)) {
			return;
		}
		channelPositions = nextPositions;
		channelGains = nextGains;
		layoutVersion.incrementAndGet();
	}

	private boolean sameLayout(float[][] positions, float[] gains) {
		float[][] currentPositions = channelPositions;
		float[] currentGains = channelGains;
		for (int i = 0; i < SPEAKERS; i++) {
			for (int axis = 0; axis < 3; axis++) {
				if (Math.abs(positions[i][axis] - currentPositions[i][axis]) > 1.0e-4f) {
					return false;
				}
			}
			if (Math.abs(gains[i] - currentGains[i]) > 1.0e-4f) {
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

	private static float[] defaultChannelGains() {
		float[] gains = new float[SPEAKERS];
		for (int i = 0; i < SPEAKERS; i++) {
			gains[i] = 1.0f;
		}
		return gains;
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
		AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);

		for (int i = 0; i < SPEAKERS; i++) {
			sources[i] = alGenSources();
			for (int j = 0; j < BUFFER_COUNT; j++) {
				buffers[i][j] = alGenBuffers();
				freeBuffers[i].addLast(buffers[i][j]);
			}
			monoBuffers[i] = BufferUtils.createShortBuffer(SAMPLE_RATE / 2);
			alSourcei(sources[i], AL_SOURCE_RELATIVE, AL_TRUE);
			alSourcef(sources[i], AL_ROLLOFF_FACTOR, 1.0f);
			alSourcef(sources[i], AL_MAX_DISTANCE, 64f);
			appliedGains[i] = Float.NaN;
		}
		applyChannelLayout();
		applyChannelGains();
		checkError("setupSources");
	}

	private void applyChannelLayout() {
		int version = layoutVersion.get();
		float[][] positions = channelPositions;
		for (int i = 0; i < SPEAKERS; i++) {
			float[] position = positions[i];
			alSource3f(sources[i], AL_POSITION, position[0], position[1], position[2]);
		}
		appliedLayoutVersion = version;
	}

	private void applyChannelGains() {
		float master = masterMuted ? 0.0f : BASE_GAIN * masterGain;
		float[] gains = channelGains;
		for (int i = 0; i < SPEAKERS; i++) {
			float target = master * gains[i];
			if (Float.isNaN(appliedGains[i]) || Math.abs(target - appliedGains[i]) >= 0.001f) {
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
		if (appliedLayoutVersion != layoutVersion.get()) {
			applyChannelLayout();
		}
		applyChannelGains();
		reclaimProcessed();
		if (pendingChunk == null) {
			pendingChunk = input.poll();
		}
		while (pendingChunk != null && allSpeakersFree()) {
			queueChunk(pendingChunk);
			pendingChunk = input.poll();
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

	private void queueChunk(AudioChunk chunk) {
		short[] samples = chunk.samples();
		int frames = samples.length / CHANNELS;
		for (int channel = 0; channel < SPEAKERS; channel++) {
			ShortBuffer mono = monoBuffers[channel];
			if (mono.capacity() < frames) {
				mono = BufferUtils.createShortBuffer(frames);
				monoBuffers[channel] = mono;
			}
			mono.clear();
			for (int frame = 0; frame < frames; frame++) {
				mono.put(samples[frame * CHANNELS + channel]);
			}
			mono.flip();
			int buffer = freeBuffers[channel].removeFirst();
			alBufferData(buffer, AL_FORMAT_MONO16, mono, SAMPLE_RATE);
			alSourceQueueBuffers(sources[channel], buffer);
		}
		double duration = frames / (double) SAMPLE_RATE;
		masterQueue.addLast(new ChunkMeta(chunk.startPtsSeconds(), duration));
		lastEndPts = chunk.startPtsSeconds() + duration;
		checkError("queueChunk");
	}

	private void startAll() {
		long now = SOFTDeviceClock.alcGetInteger64vSOFT(currentDevice(), SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
		long scheduled = now + 150_000_000L;
		for (int source : sources) {
			SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(source, scheduled);
		}
		started = true;
		Cinema.LOGGER.info("cinema audio: six sources scheduled in {} ms",
			String.format("%.1f", (scheduled - now) / 1e6));
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
		audiblePts = 0;
		latencySeconds = 0;
		lastEndPts = 0;
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
