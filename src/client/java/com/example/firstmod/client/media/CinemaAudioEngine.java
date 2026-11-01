package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceLatency;
import org.lwjgl.openal.SOFTSourceStartDelay;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Six independent mono OpenAL sources in one cinema-owned context.
 *
 * <p>The decoder submits interleaved 5.1 chunks. The audio thread deinterleaves
 * each chunk, queues one mono buffer per speaker, and schedules all six sources
 * at the same device-clock instant. It also publishes the audible media clock
 * so the render thread can choose video frames without touching OpenAL.
 */
public final class CinemaAudioEngine implements AutoCloseable {
	public static final int SAMPLE_RATE = 48_000;
	public static final int CHANNELS = 6;
	private static final int SPEAKERS = 6;
	private static final int BUFFER_COUNT = 4;
	private static final float BASE_GAIN = 0.65f;
	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final float[][] POSITIONS = {
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
	private float appliedGain = Float.NaN;
	private volatile double audiblePts;
	private volatile double latencySeconds;
	private double lastEndPts;
	private ALCapabilities previousProcessCaps;
	private long deviceHandle = NULL;
	private long contextHandle = NULL;
	private boolean ownsDevice;

	public CinemaAudioEngine() {
		for (int i = 0; i < SPEAKERS; i++) {
			freeBuffers[i] = new ArrayDeque<>(BUFFER_COUNT);
		}
		thread = new Thread(this::run, "cinema-audio");
		thread.setDaemon(true);
		Runtime.getRuntime().addShutdownHook(new Thread(this::closeDevice, "cinema-audio-device"));
	}

	public void start() {
		if (threadStarted.compareAndSet(false, true)) {
			thread.start();
		}
	}

	/** Stops all current sources and prepares the same device/context for a new file. */
	public void reset() {
		if (!threadStarted.get()) {
			clearQueues();
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

	/** Receiver volume, 0..1, applied on top of the fixed source gain. */
	public void setMasterGain(float volume, boolean muted) {
		masterGain = Math.max(0.0f, Math.min(1.0f, volume));
		masterMuted = muted;
	}

	private void run() {
		long device = NULL;
		long context = NULL;
		try {
			device = MinecraftOpenAl.device();
			if (device == NULL) {
				FirstMod.LOGGER.warn("cinema audio: Minecraft OpenAL device unavailable; opening a fallback device");
				device = ALC10.alcOpenDevice((CharSequence) null);
				ownsDevice = true;
			}
			if (device == NULL) {
				throw new IllegalStateException("alcOpenDevice() failed");
			}
			deviceHandle = device;
			ALCCapabilities deviceCaps = ALC.createCapabilities(device);
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
			ALC10.alcMakeContextCurrent(context);
			if (previousProcessCaps != null) {
				// Reuse Minecraft's AL function pointers. Calling
				// AL.createCapabilities() here replaces LWJGL's process-wide ICD
				// with the cinema context and upsets SoundEngine at shutdown.
				AL.setCurrentThread(previousProcessCaps);
			} else {
				AL.createCapabilities(deviceCaps);
			}
			FirstMod.LOGGER.info("cinema audio: OpenAL {} on {} (sharedCaps={})", AL10.alGetString(AL10.AL_VERSION),
				ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER), previousProcessCaps != null);

			setupSources();
			while (running.get()) {
				pump();
				Thread.sleep(5);
			}
		} catch (InterruptedException ignored) {
			// stop() interrupts the pump loop.
		} catch (Throwable t) {
			FirstMod.LOGGER.error("cinema audio engine failed", t);
		} finally {
			cleanup();
			// Never replace Minecraft's process-wide OpenAL capabilities. Only
			// clear/replace this thread's capability binding.
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
			alSource3f(sources[i], AL_POSITION, POSITIONS[i][0], POSITIONS[i][1], POSITIONS[i][2]);
			float gain = masterMuted ? 0.0f : BASE_GAIN * masterGain;
			alSourcef(sources[i], AL_GAIN, gain);
			appliedGain = gain;
			alSourcef(sources[i], AL_ROLLOFF_FACTOR, 1.0f);
			alSourcef(sources[i], AL_MAX_DISTANCE, 64f);
		}
		checkError("setupSources");
	}

	private void applyMasterGain() {
		float target = masterMuted ? 0.0f : BASE_GAIN * masterGain;
		if (Math.abs(target - appliedGain) < 0.001f) {
			return;
		}
		appliedGain = target;
		for (int source : sources) {
			alSourcef(source, AL_GAIN, target);
		}
	}

	private void pump() {
		if (resetRequested) {
			performReset();
			return;
		}
		applyMasterGain();
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
		FirstMod.LOGGER.info("cinema audio: six sources scheduled in {} ms",
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

	private void closeDevice() {
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
	}

	private record ChunkMeta(double startPtsSeconds, double durationSeconds) {
	}
}
