package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.CinemaLog;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.LongByReference;

import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.Locale;

/**
 * Optional NVIDIA CUDA implementation of the FIR backend.
 *
 * <p>All submitted direct and reflection streams are copied into one compact
 * staging block, convolved by a single kernel launch, and copied back. Filter
 * changes use the same 1024-frame old/new crossfade as {@link FirProcessor}.
 * The context/module is shared process-wide through {@link CudaContext}; each
 * engine only owns its device buffers, so play/stop does not recreate the
 * context. Enabled only with {@code -Dcinema.audio.cuda=true}.
 */
final class CudaFirBackend implements FirBackend {
	private static final int BLOCK_THREADS = 256;
	private static final int CROSSFADE_FRAMES = 1024;

	private final int capacity;
	private final int maxFrames;
	private final int taps;
	private final CudaContext.CudaDriver cuda;
	private final Pointer function;
	private final long dInput;
	private final long dTaps;
	private final long dOldTaps;
	private final long dOutput;
	private final long dFade;
	private final Memory hInput;
	private final Memory hTaps;
	private final Memory hOldTaps;
	private final Memory hOutput;
	private final Memory hFade;
	private final float[][] history;
	private final float[][] hostTaps;
	private final float[][] oldTaps;
	private final int[] fadeRemaining;
	private final boolean[] hasOutput;
	private final float[][] pendingOutputs;
	private final int[] pendingStreams;
	private int activeCount;
	private int currentFrames;
	private int currentStride;
	private boolean closed;

	CudaFirBackend(int capacity, int maxFrames, int taps) {
		if (capacity <= 0 || maxFrames <= 0 || taps <= 1) {
			throw new IllegalArgumentException("invalid CUDA FIR dimensions");
		}
		this.capacity = capacity;
		this.maxFrames = maxFrames;
		this.taps = taps;
		this.history = new float[capacity][taps - 1];
		this.hostTaps = new float[capacity][taps];
		this.oldTaps = new float[capacity][taps];
		this.fadeRemaining = new int[capacity];
		this.hasOutput = new boolean[capacity];
		this.pendingOutputs = new float[capacity][];
		this.pendingStreams = new int[capacity];

		if (!CudaContext.ensureInitialized()) {
			throw new IllegalStateException("CUDA context unavailable");
		}
		this.cuda = CudaContext.driver();
		this.function = CudaContext.function();

		long started = System.nanoTime();
		long inputPtr = 0L;
		long tapsPtr = 0L;
		long oldTapsPtr = 0L;
		long outputPtr = 0L;
		long fadePtr = 0L;
		Memory input = null;
		Memory tapMemory = null;
		Memory oldTapMemory = null;
		Memory output = null;
		Memory fade = null;
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			int stride = maxFrames + taps - 1;
			long inputBytes = (long) capacity * stride * Float.BYTES;
			long tapBytes = (long) capacity * taps * Float.BYTES;
			long outputBytes = (long) capacity * maxFrames * Float.BYTES;
			long fadeBytes = (long) capacity * Integer.BYTES;
			input = new Memory(inputBytes);
			tapMemory = new Memory(tapBytes);
			oldTapMemory = new Memory(tapBytes);
			output = new Memory(outputBytes);
			fade = new Memory(fadeBytes);

			LongByReference d = new LongByReference();
			CudaContext.check(cuda.cuMemAllocV2(d, inputBytes), "cuMemAlloc input");
			inputPtr = d.getValue();
			CudaContext.check(cuda.cuMemAllocV2(d, tapBytes), "cuMemAlloc taps");
			tapsPtr = d.getValue();
			CudaContext.check(cuda.cuMemAllocV2(d, tapBytes), "cuMemAlloc old taps");
			oldTapsPtr = d.getValue();
			CudaContext.check(cuda.cuMemAllocV2(d, outputBytes),
				"cuMemAlloc output");
			outputPtr = d.getValue();
			CudaContext.check(cuda.cuMemAllocV2(d, fadeBytes), "cuMemAlloc fade");
			fadePtr = d.getValue();
			CinemaLog.info(CinemaLog.AUDIO,
				"CUDA FIR buffers: {} streams, {} taps ({} ms)", capacity, taps,
				String.format(Locale.ROOT, "%.0f",
					(System.nanoTime() - started) / 1.0e6));
		} catch (Throwable t) {
			if (inputPtr != 0L) {
				cuda.cuMemFreeV2(inputPtr);
			}
			if (tapsPtr != 0L) {
				cuda.cuMemFreeV2(tapsPtr);
			}
			if (oldTapsPtr != 0L) {
				cuda.cuMemFreeV2(oldTapsPtr);
			}
			if (outputPtr != 0L) {
				cuda.cuMemFreeV2(outputPtr);
			}
			if (fadePtr != 0L) {
				cuda.cuMemFreeV2(fadePtr);
			}
			throw t instanceof RuntimeException runtime ? runtime
				: new IllegalStateException("CUDA buffer allocation failed: " + t, t);
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}

		this.dInput = inputPtr;
		this.dTaps = tapsPtr;
		this.dOldTaps = oldTapsPtr;
		this.dOutput = outputPtr;
		this.dFade = fadePtr;
		this.hInput = input;
		this.hTaps = tapMemory;
		this.hOldTaps = oldTapMemory;
		this.hOutput = output;
		this.hFade = fade;
	}

	/** Initializes the shared context off the render thread during client start. */
	static void prewarmSharedAsync() {
		if (!Boolean.parseBoolean(System.getProperty(FirBackend.CUDA_PROPERTY,
			"false"))) {
			return;
		}
		Thread thread = new Thread(() -> {
			if (!CudaContext.ensureInitialized()) {
				return;
			}
			CudaContext.lock();
			try {
				CudaContext.setCurrent();
				CudaContext.driver().cuCtxSynchronize();
				CudaContext.releaseCurrent();
			} catch (Throwable t) {
				CinemaLog.warn(CinemaLog.AUDIO,
					"CUDA context prewarm failed: {}", t.toString());
			} finally {
				CudaContext.unlock();
			}
			CinemaLog.info(CinemaLog.AUDIO, "CUDA context prewarmed");
		}, "cinema-cuda-prewarm");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public void prewarm() {
		if (closed) {
			return;
		}
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			setFilter(0, MinimumPhaseFir.FLAT);
			float[] silence = new float[maxFrames];
			float[] output = new float[maxFrames];
			beginBlock(maxFrames);
			process(0, silence, 0, maxFrames, output);
			endBlock();
			reset(0);
			CinemaLog.info(CinemaLog.AUDIO, "CUDA FIR prewarmed");
		} catch (Throwable t) {
			CinemaLog.warn(CinemaLog.AUDIO,
				"CUDA FIR prewarm failed: {}", t.toString());
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}
	}

	@Override
	public void setFilter(int stream, MinimumPhaseFir filter) {
		if (stream < 0 || stream >= capacity) {
			return;
		}
		MinimumPhaseFir value = filter == null ? MinimumPhaseFir.FLAT : filter;
		float[] dst = hostTaps[stream];
		if (hasOutput[stream] && !sameTaps(dst, value)) {
			System.arraycopy(dst, 0, oldTaps[stream], 0, taps);
			fadeRemaining[stream] = CROSSFADE_FRAMES;
		}
		Arrays.fill(dst, 0.0f);
		float[] src = value.taps();
		System.arraycopy(src, 0, dst, 0, Math.min(src.length, taps));
	}

	@Override
	public void beginBlock(int frames) {
		if (closed) {
			throw new IllegalStateException("CUDA backend is closed");
		}
		if (frames <= 0 || frames > maxFrames) {
			throw new IllegalArgumentException("unsupported block size: " + frames);
		}
		currentFrames = frames;
		currentStride = frames + taps - 1;
		activeCount = 0;
	}

	@Override
	public void process(int stream, float[] input, int inputOffset, int frames,
						float[] output) {
		if (frames != currentFrames) {
			throw new IllegalArgumentException(
				"block size mismatch: " + frames + " != " + currentFrames);
		}
		if (stream < 0 || stream >= capacity) {
			throw new IllegalArgumentException("stream out of range: " + stream);
		}
		if (activeCount >= capacity) {
			throw new IllegalStateException("too many FIR streams");
		}
		int index = activeCount;
		long base = (long) index * currentStride;
		hInput.write(base * Float.BYTES, history[stream], 0, taps - 1);
		hInput.write((base + taps - 1) * Float.BYTES, input, inputOffset, frames);
		hTaps.write((long) index * taps * Float.BYTES, hostTaps[stream], 0, taps);
		hOldTaps.write((long) index * taps * Float.BYTES, oldTaps[stream], 0, taps);
		hFade.setInt((long) index * Integer.BYTES, fadeRemaining[stream]);
		pendingOutputs[index] = output;
		pendingStreams[index] = stream;
		activeCount++;
	}

	@Override
	public void endBlock() {
		if (activeCount == 0) {
			return;
		}
		int count = activeCount;
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			long inputBytes = (long) count * currentStride * Float.BYTES;
			long tapBytes = (long) count * taps * Float.BYTES;
			long outputBytes = (long) count * currentFrames * Float.BYTES;
			long fadeBytes = (long) count * Integer.BYTES;
			CudaContext.check(cuda.cuMemcpyHtoDV2(dInput, hInput, inputBytes),
				"cuMemcpyHtoD input");
			CudaContext.check(cuda.cuMemcpyHtoDV2(dTaps, hTaps, tapBytes),
				"cuMemcpyHtoD taps");
			CudaContext.check(cuda.cuMemcpyHtoDV2(dOldTaps, hOldTaps, tapBytes),
				"cuMemcpyHtoD old taps");
			CudaContext.check(cuda.cuMemcpyHtoDV2(dFade, hFade, fadeBytes),
				"cuMemcpyHtoD fade");
			KernelArgs args = kernelParams(dInput, dTaps, dOldTaps, dOutput,
				dFade, currentFrames, taps);
			int gridY = (currentFrames + BLOCK_THREADS - 1) / BLOCK_THREADS;
			try {
				CudaContext.check(cuda.cuLaunchKernel(function,
					count, gridY, 1,
					BLOCK_THREADS, 1, 1,
					0, null, args.pointers(), null), "cuLaunchKernel");
			} finally {
				Reference.reachabilityFence(args);
			}
			CudaContext.check(cuda.cuMemcpyDtoHV2(hOutput, dOutput, outputBytes),
				"cuMemcpyDtoH output");
			CudaContext.check(cuda.cuCtxSynchronize(), "cuCtxSynchronize");
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}

		for (int i = 0; i < count; i++) {
			int stream = pendingStreams[i];
			hOutput.read((long) i * currentFrames * Float.BYTES,
				pendingOutputs[i], 0, currentFrames);
			hInput.read(((long) i * currentStride + currentFrames) * Float.BYTES,
				history[stream], 0, taps - 1);
			fadeRemaining[stream] = Math.max(0,
				fadeRemaining[stream] - currentFrames);
			hasOutput[stream] = true;
			pendingOutputs[i] = null;
		}
		activeCount = 0;
	}

	@Override
	public void reset(int stream) {
		if (stream >= 0 && stream < capacity) {
			Arrays.fill(history[stream], 0.0f);
			Arrays.fill(oldTaps[stream], 0.0f);
			fadeRemaining[stream] = 0;
			hasOutput[stream] = false;
		}
	}

	@Override
	public void resetAll() {
		for (int stream = 0; stream < capacity; stream++) {
			reset(stream);
		}
		activeCount = 0;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		CudaContext.lock();
		try {
			CudaContext.setCurrent();
			if (dInput != 0L) {
				cuda.cuMemFreeV2(dInput);
			}
			if (dTaps != 0L) {
				cuda.cuMemFreeV2(dTaps);
			}
			if (dOldTaps != 0L) {
				cuda.cuMemFreeV2(dOldTaps);
			}
			if (dOutput != 0L) {
				cuda.cuMemFreeV2(dOutput);
			}
			if (dFade != 0L) {
				cuda.cuMemFreeV2(dFade);
			}
		} finally {
			CudaContext.releaseCurrent();
			CudaContext.unlock();
		}
	}

	private static boolean sameTaps(float[] current, MinimumPhaseFir value) {
		float[] next = value.taps();
		int length = Math.min(current.length, next.length);
		for (int i = 0; i < length; i++) {
			if (current[i] != next[i]) {
				return false;
			}
		}
		for (int i = next.length; i < current.length; i++) {
			if (current[i] != 0.0f) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Argument blocks and the driver's pointer table. The caller must keep this
	 * object reachable until after {@code cuLaunchKernel} returns.
	 */
	private record KernelArgs(Memory[] values, Memory pointers) {
	}

	private static KernelArgs kernelParams(long input, long taps, long oldTaps,
										  long output, long fade, int frames,
										  int tapCount) {
		Memory argInput = new Memory(Long.BYTES);
		Memory argTaps = new Memory(Long.BYTES);
		Memory argOldTaps = new Memory(Long.BYTES);
		Memory argOutput = new Memory(Long.BYTES);
		Memory argFade = new Memory(Long.BYTES);
		Memory argFrames = new Memory(Integer.BYTES);
		Memory argTapCount = new Memory(Integer.BYTES);
		argInput.setLong(0, input);
		argTaps.setLong(0, taps);
		argOldTaps.setLong(0, oldTaps);
		argOutput.setLong(0, output);
		argFade.setLong(0, fade);
		argFrames.setInt(0, frames);
		argTapCount.setInt(0, tapCount);

		int pointerSize = Native.POINTER_SIZE;
		Memory pointers = new Memory((long) pointerSize * 7);
		pointers.setPointer(0, argInput);
		pointers.setPointer(pointerSize, argTaps);
		pointers.setPointer(pointerSize * 2L, argOldTaps);
		pointers.setPointer(pointerSize * 3L, argOutput);
		pointers.setPointer(pointerSize * 4L, argFade);
		pointers.setPointer(pointerSize * 5L, argFrames);
		pointers.setPointer(pointerSize * 6L, argTapCount);
		return new KernelArgs(new Memory[]{argInput, argTaps, argOldTaps,
			argOutput, argFade, argFrames, argTapCount}, pointers);
	}
}
