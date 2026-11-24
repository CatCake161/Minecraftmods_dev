package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.CinemaLog;

/**
 * Direct and reflection FIR backend used by {@code CinemaAudioEngine}.
 *
 * <p>Direct sources use stream ids {@code 0..MAX_DIRECT_SOURCES-1}; reflection
 * slots use the following ids. The CPU implementation delegates to one
 * {@link FirProcessor} per stream (including its 1024-frame crossfade). The
 * optional CUDA implementation batches every submitted stream into one kernel
 * launch per block and implements the same crossfade on the GPU.
 *
 * <p>Callers must call {@link #beginBlock(int)}, submit every stream for the
 * block, call {@link #endBlock()}, and only then read the output arrays.
 */
public interface FirBackend extends AutoCloseable {
	String CUDA_PROPERTY = "cinema.audio.cuda";

	/** Selects CUDA only when explicitly requested; CPU is always the fallback. */
	static FirBackend create(int capacity, int maxFrames, int taps) {
		if (Boolean.parseBoolean(System.getProperty(CUDA_PROPERTY, "false"))) {
			try {
				return new CudaFirBackend(capacity, maxFrames, taps);
			} catch (Throwable t) {
				CinemaLog.warn(CinemaLog.AUDIO,
					"CUDA FIR unavailable, using CPU: {}", t.toString());
			}
		}
		return new CpuFirBackend(capacity);
	}

	/** Starts the process-wide CUDA context prewarm off the render thread. */
	static void prewarmCudaAsync() {
		CudaFirBackend.prewarmSharedAsync();
	}

	/** Initializes a GPU context on the audio thread, if needed. */
	void prewarm();

	void setFilter(int stream, MinimumPhaseFir filter);

	void beginBlock(int frames);

	/**
	 * Queues one stream for this block. The input is copied; the output array is
	 * not valid until {@link #endBlock()}.
	 */
	void process(int stream, float[] input, int inputOffset, int frames,
				 float[] output);

	void endBlock();

	void reset(int stream);

	void resetAll();

	@Override
	void close();
}
