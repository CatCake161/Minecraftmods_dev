package dev.catcake.cinema.spike;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Minimal OpenAL streaming player: a single source fed from a small pool of
 * queued buffers. Playback position is derived from completed buffers plus
 * {@code AL_SEC_OFFSET}, which makes it suitable as the master clock.
 */
final class OpenAlAudioPlayer implements AutoCloseable {
	private static final int BUFFER_COUNT = 4;

	private final int sampleRate;
	private final int channels;
	private final int format;

	private final long device;
	private final long context;
	private final int source;
	private final int[] buffers = new int[BUFFER_COUNT];
	private final Deque<Integer> freeBuffers = new ArrayDeque<>();

	private final Deque<AudioChunk> pendingChunks = new ArrayDeque<>();
	private final Deque<QueuedBuffer> queued = new ArrayDeque<>();

	private ShortBuffer directSamples = ShortBuffer.allocate(0);

	private double consumedSeconds;
	private double lastEndPtsSeconds;
	private long startedAtNanos;

	public OpenAlAudioPlayer(int sampleRate, int channels) {
		this.sampleRate = sampleRate;
		this.channels = channels;
		this.format = channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;

		device = ALC10.alcOpenDevice((CharSequence) null);
		if (device == NULL) {
			throw new IllegalStateException("alcOpenDevice() failed");
		}
		ALCCapabilities deviceCaps = ALC.createCapabilities(device);
		context = ALC10.alcCreateContext(device, (IntBuffer) null);
		if (context == NULL) {
			ALC10.alcCloseDevice(device);
			throw new IllegalStateException("alcCreateContext() failed");
		}
		ALC10.alcMakeContextCurrent(context);
		AL.createCapabilities(deviceCaps);

		source = alGenSources();
		for (int i = 0; i < BUFFER_COUNT; i++) {
			buffers[i] = alGenBuffers();
			freeBuffers.addLast(buffers[i]);
		}
		alSourcef(source, AL_GAIN, 1.0f);
		checkError("init");
	}

	public void submit(AudioChunk chunk) {
		pendingChunks.addLast(chunk);
		lastEndPtsSeconds = chunk.startPtsSeconds()
			+ chunk.samples().length / (double) channels / sampleRate;
	}

	public void start() {
		pump();
		checkError("start");
	}

	/** Queues as many decoded chunks as free buffers allow and reclaims played buffers. */
	public void pump() {
		reclaimProcessed();

		while (!pendingChunks.isEmpty() && !freeBuffers.isEmpty()) {
			AudioChunk chunk = pendingChunks.removeFirst();
			int buffer = freeBuffers.removeFirst();
			short[] samples = chunk.samples();
			if (directSamples.capacity() < samples.length) {
				directSamples = org.lwjgl.BufferUtils.createShortBuffer(samples.length);
			}
			directSamples.clear();
			directSamples.put(samples);
			directSamples.flip();
			alBufferData(buffer, format, directSamples, sampleRate);
			alSourceQueueBuffers(source, buffer);
			double duration = samples.length / (double) channels / sampleRate;
			queued.addLast(new QueuedBuffer(buffer, chunk.startPtsSeconds(), duration));
		}

		int state = alGetSourcei(source, AL_SOURCE_STATE);
		if (state != AL_PLAYING && !queued.isEmpty() && startedAtNanos != 0) {
			// Starved: restart the source so queued buffers continue to play.
			alSourcePlay(source);
		} else if (state != AL_PLAYING && !queued.isEmpty() && startedAtNanos == 0) {
			alSourcePlay(source);
			startedAtNanos = System.nanoTime();
		}
		checkError("pump");
	}

	private void reclaimProcessed() {
		int processed = alGetSourcei(source, AL_BUFFERS_PROCESSED);
		if (processed <= 0) {
			return;
		}
		IntBuffer released = org.lwjgl.BufferUtils.createIntBuffer(processed);
		alSourceUnqueueBuffers(source, released);
		// OpenAL writes the names without advancing the buffer position.
		for (int i = 0; i < processed; i++) {
			int buffer = released.get(i);
			QueuedBuffer meta = queued.pollFirst();
			if (meta != null) {
				consumedSeconds += meta.durationSeconds();
			}
			freeBuffers.addLast(buffer);
		}
	}

	public boolean finished() {
		pump();
		return pendingChunks.isEmpty() && queued.isEmpty() && alGetSourcei(source, AL_SOURCE_STATE) != AL_PLAYING;
	}

	/** Seconds of audio that actually left the player. */
	public double clockSeconds() {
		if (queued.isEmpty()) {
			return consumedSeconds;
		}
		int state = alGetSourcei(source, AL_SOURCE_STATE);
		double offset = state == AL_PLAYING || state == AL_PAUSED ? alGetSourcef(source, AL11.AL_SEC_OFFSET) : 0.0;
		return consumedSeconds + Math.max(0.0, offset);
	}

	/** Media timestamp currently audible, based on the front buffer's PTS. */
	public double mediaPtsSeconds() {
		if (queued.isEmpty()) {
			return lastEndPtsSeconds;
		}
		int state = alGetSourcei(source, AL_SOURCE_STATE);
		double offset = state == AL_PLAYING || state == AL_PAUSED ? alGetSourcef(source, AL11.AL_SEC_OFFSET) : 0.0;
		return queued.peekFirst().startPtsSeconds() + Math.max(0.0, offset);
	}

	public long startedAtNanos() {
		return startedAtNanos;
	}

	public int sampleRate() {
		return sampleRate;
	}

	public int channels() {
		return channels;
	}

	private static void checkError(String where) {
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " at " + where);
		}
	}

	@Override
	public void close() {
		alSourceStop(source);
		alDeleteSources(source);
		alDeleteBuffers(buffers);
		ALC10.alcMakeContextCurrent(NULL);
		ALC10.alcDestroyContext(context);
		ALC10.alcCloseDevice(device);
		AL.setCurrentThread(null);
		ALC.destroy();
	}

	private record QueuedBuffer(int buffer, double startPtsSeconds, double durationSeconds) {
	}
}
