package com.example.cinema.client.media.audio.dsp;

import com.example.cinema.CinemaLog;
import com.sun.jna.FunctionMapper;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Process-wide CUDA driver context shared by every {@link CudaFirBackend}.
 *
 * <p>Creating a context, loading the module and JIT-ing the PTX costs ~50-60 ms
 * on the client thread. Doing that for every receiver open/close produces
 * visible frame-time spikes. This holder creates the context once (optionally
 * on a background prewarm thread) and serializes GPU work with a lock. Each
 * backend still owns its own device buffers.
 *
 * <p>The context is intentionally not destroyed when an engine closes; the
 * driver releases it at process exit. This keeps play/stop cheap.
 */
final class CudaContext {
	private static final String PTX_RESOURCE = "/assets/cinema/audio/fir_conv.ptx";
	private static final String RT_PTX_RESOURCE = "/assets/cinema/audio/rt_trace.ptx";
	private static final Object INIT_LOCK = new Object();
	private static final ReentrantLock GPU_LOCK = new ReentrantLock();

	private static CudaDriver driver;
	private static PointerByReference context;
	private static Pointer function;
	private static boolean initialized;
	private static boolean available;
	private static Pointer rtFunction;
	private static boolean rtInitialized;
	private static boolean rtAvailable;

	/** CUDA Driver API used by this backend. */
	interface CudaDriver extends Library {
		int cuInit(int flags);

		int cuDeviceGetCount(IntByReference count);

		int cuDeviceGet(IntByReference device, int ordinal);

		int cuDeviceGetName(Pointer name, int len, int device);

		int cuCtxCreateV2(PointerByReference context, int flags, int device);

		int cuCtxDestroyV2(Pointer context);

		int cuCtxSetCurrent(Pointer context);

		int cuCtxSynchronize();

		int cuMemAllocV2(LongByReference devicePtr, long bytes);

		int cuMemFreeV2(long devicePtr);

		int cuMemcpyHtoDV2(long dstDevice, Pointer srcHost, long bytes);

		int cuMemcpyDtoHV2(Pointer dstHost, long srcDevice, long bytes);

		int cuModuleLoadData(PointerByReference module, Pointer image);

		int cuModuleGetFunction(PointerByReference function, Pointer module,
								String name);

		int cuLaunchKernel(Pointer function,
						   int gridX, int gridY, int gridZ,
						   int blockX, int blockY, int blockZ,
						   int sharedMemBytes, Pointer stream,
						   Pointer kernelParams, Pointer extra);

		int cuGetErrorString(int error, PointerByReference message);
	}

	private CudaContext() {
	}

	/** Creates the shared context/module once. Safe to call from any thread. */
	static boolean ensureInitialized() {
		synchronized (INIT_LOCK) {
			if (initialized) {
				return available;
			}
			initialized = true;
			long started = System.nanoTime();
			try {
				driver = loadDriver();
				check(driver.cuInit(0), "cuInit");
				IntByReference count = new IntByReference();
				check(driver.cuDeviceGetCount(count), "cuDeviceGetCount");
				if (count.getValue() <= 0) {
					throw new IllegalStateException("no CUDA devices");
				}
				IntByReference device = new IntByReference();
				check(driver.cuDeviceGet(device, 0), "cuDeviceGet");

				context = new PointerByReference();
				check(driver.cuCtxCreateV2(context, 0, device.getValue()),
					"cuCtxCreate");
				Memory ptx = loadPtx(PTX_RESOURCE);
				PointerByReference module = new PointerByReference();
				check(driver.cuModuleLoadData(module, ptx), "cuModuleLoadData");
				PointerByReference fn = new PointerByReference();
				check(driver.cuModuleGetFunction(fn, module.getValue(), "fir_conv"),
					"cuModuleGetFunction");
				function = fn.getValue();

				Memory deviceName = new Memory(256);
				driver.cuDeviceGetName(deviceName, 256, device.getValue());
				CinemaLog.info(CinemaLog.AUDIO,
					"CUDA context ready: {} ({} ms)",
					deviceName.getString(0), String.format(Locale.ROOT, "%.0f",
						(System.nanoTime() - started) / 1.0e6));
				available = true;
				driver.cuCtxSetCurrent(null);
			} catch (Throwable t) {
				available = false;
				CinemaLog.warn(CinemaLog.AUDIO,
					"CUDA context unavailable: {}", t.toString());
				if (driver != null && context != null) {
					driver.cuCtxDestroyV2(context.getValue());
				}
				context = null;
				function = null;
			}
			return available;
		}
	}

	static boolean available() {
		return available;
	}

	/** Loads the ray-tracing module into the shared context (once). */
	static boolean ensureRtInitialized() {
		synchronized (INIT_LOCK) {
			if (!ensureInitialized()) {
				return false;
			}
			if (rtInitialized) {
				return rtAvailable;
			}
			rtInitialized = true;
			try {
				driver.cuCtxSetCurrent(context.getValue());
				Memory ptx = loadPtx(RT_PTX_RESOURCE);
				PointerByReference module = new PointerByReference();
				check(driver.cuModuleLoadData(module, ptx), "cuModuleLoadData(rt)");
				PointerByReference fn = new PointerByReference();
				check(driver.cuModuleGetFunction(fn, module.getValue(), "rt_trace"),
					"cuModuleGetFunction(rt)");
				rtFunction = fn.getValue();
				rtAvailable = true;
				CinemaLog.info(CinemaLog.AUDIO, "CUDA ray tracer ready");
			} catch (Throwable t) {
				rtAvailable = false;
				CinemaLog.warn(CinemaLog.AUDIO,
					"CUDA ray tracer unavailable: {}", t.toString());
			} finally {
				driver.cuCtxSetCurrent(null);
			}
			return rtAvailable;
		}
	}

	static Pointer rtFunction() {
		return rtFunction;
	}

	static CudaDriver driver() {
		return driver;
	}

	static Pointer function() {
		return function;
	}

	static void lock() {
		GPU_LOCK.lock();
	}

	static void unlock() {
		GPU_LOCK.unlock();
	}

	static void setCurrent() {
		check(driver.cuCtxSetCurrent(context.getValue()), "cuCtxSetCurrent");
	}

	static void releaseCurrent() {
		if (driver != null) {
			driver.cuCtxSetCurrent(null);
		}
	}

	static void check(int result, String call) {
		if (result == 0) {
			return;
		}
		String message = "error " + result;
		try {
			PointerByReference text = new PointerByReference();
			if (driver != null && driver.cuGetErrorString(result, text) == 0
				&& text.getValue() != null) {
				message = text.getValue().getString(0);
			}
		} catch (Throwable ignored) {
			// Keep the numeric error.
		}
		throw new IllegalStateException("CUDA " + call + " failed: " + message);
	}

	private static final Map<String, String> NATIVE_NAMES = Map.of(
		"cuCtxCreateV2", "cuCtxCreate_v2",
		"cuCtxDestroyV2", "cuCtxDestroy_v2",
		"cuMemAllocV2", "cuMemAlloc_v2",
		"cuMemFreeV2", "cuMemFree_v2",
		"cuMemcpyHtoDV2", "cuMemcpyHtoD_v2",
		"cuMemcpyDtoHV2", "cuMemcpyDtoH_v2");

	private static CudaDriver loadDriver() {
		FunctionMapper mapper = (library, method) ->
			NATIVE_NAMES.getOrDefault(method.getName(), method.getName());
		Map<String, Object> options = Map.of(Library.OPTION_FUNCTION_MAPPER, mapper);
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return Native.load("nvcuda", CudaDriver.class, options);
		}
		try {
			return Native.load("cuda", CudaDriver.class, options);
		} catch (UnsatisfiedLinkError first) {
			return Native.load("libcuda.so.1", CudaDriver.class, options);
		}
	}

	private static Memory loadPtx(String resource) throws IOException {
		try (InputStream input = CudaContext.class
			.getResourceAsStream(resource)) {
			if (input == null) {
				throw new IOException("missing resource " + resource);
			}
			byte[] bytes = input.readAllBytes();
			Memory ptx = new Memory(bytes.length + 1L);
			ptx.write(0, bytes, 0, bytes.length);
			ptx.setByte(bytes.length, (byte) 0);
			return ptx;
		}
	}
}
