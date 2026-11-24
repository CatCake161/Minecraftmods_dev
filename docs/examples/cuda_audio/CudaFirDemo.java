import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * Standalone JNA + CUDA Driver API demo.
 *
 * <p>Compiles a CUDA kernel at runtime with NVRTC (no CUDA Toolkit / nvcc
 * required at runtime), loads it with the CUDA driver API, runs 78 streams of
 * 4096-tap FIR convolution over 2048-frame blocks, and validates the result
 * against a CPU reference.
 *
 * <p>Build: javac -cp jna.jar CudaFirDemo.java
 * Run:   java -cp "jna.jar;." CudaFirDemo
 */
public final class CudaFirDemo {
	private static final int STREAMS = 78;
	private static final int FRAMES = 2048;
	private static final int TAPS = 4096;
	private static final int BLOCK_THREADS = 256;

	// CUDA Driver API. The _v2 entry points are the 64-bit-safe exports.
	public interface CudaDriver extends Library {
		int cuInit(int flags);

		int cuDeviceGetCount(IntByReference count);

		int cuDeviceGet(IntByReference device, int ordinal);

		int cuDeviceGetName(Pointer name, int len, int device);

		int cuDeviceGetAttribute(IntByReference value, int attribute, int device);

		int cuCtxCreate_v2(PointerByReference context, int flags, int device);

		int cuCtxDestroy_v2(Pointer context);

		int cuCtxSynchronize();

		int cuMemAlloc_v2(LongByReference devicePtr, long bytes);

		int cuMemFree_v2(long devicePtr);

		int cuMemcpyHtoD_v2(long dstDevice, Pointer srcHost, long bytes);

		int cuMemcpyDtoH_v2(Pointer dstHost, long srcDevice, long bytes);

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

	// NVRTC (runtime CUDA C++ -> PTX compiler). Loaded from the PyTorch
	// installation because the machine has no CUDA Toolkit in PATH.
	public interface Nvrtc extends Library {
		int nvrtcVersion(IntByReference major, IntByReference minor);

		int nvrtcCreateProgram(PointerByReference program, String source,
							   String name, int numHeaders,
							   Pointer headers, Pointer includeNames);

		int nvrtcCompileProgram(Pointer program, int numOptions,
								StringArray options);

		int nvrtcGetPTXSize(Pointer program, LongByReference size);

		int nvrtcGetPTX(Pointer program, Pointer ptx);

		int nvrtcGetProgramLogSize(Pointer program, LongByReference size);

		int nvrtcGetProgramLog(Pointer program, Pointer log);

		int nvrtcDestroyProgram(PointerByReference program);

		String nvrtcGetErrorString(int result);
	}

	private static final String KERNEL_SOURCE = """
		extern "C" __global__ void fir_conv(
			const float* __restrict__ input,
			const float* __restrict__ taps,
			float* __restrict__ output,
			int frames,
			int tapCount)
		{
			int stream = blockIdx.x;
			int n = blockIdx.y * blockDim.x + threadIdx.x;
			if (n >= frames) {
				return;
			}
			int inputLength = frames + tapCount - 1;
			const float* x = input + (long)stream * inputLength + (tapCount - 1);
			const float* h = taps + (long)stream * tapCount;
			float* y = output + (long)stream * frames;

			float acc = 0.0f;
			#pragma unroll 4
			for (int k = 0; k < tapCount; k++) {
				acc = fmaf(x[n - k], h[k], acc);
			}
			y[n] = acc;
		}
		""";

	private static CudaDriver cuda;
	private static Nvrtc nvrtc;
	private static PointerByReference context;
	private static String ptxDumpPath;
	private static String ptxArch = "compute_90";

	public static void main(String[] args) throws Exception {
		String nvrtcPath = "C:\\Users\\Whuch\\AppData\\Local\\Programs\\Ultimate Vocal Remover"
			+ "\\torch\\lib\\nvrtc64_120_0.dll";
		for (int i = 0; i < args.length; i++) {
			if (args[i].startsWith("--arch=")) {
				ptxArch = args[i].substring("--arch=".length());
			} else if (args[i].startsWith("--dump-ptx=")) {
				ptxDumpPath = args[i].substring("--dump-ptx=".length());
			} else {
				nvrtcPath = args[i];
			}
		}

		cuda = Native.load("nvcuda", CudaDriver.class);
		nvrtc = Native.load(nvrtcPath, Nvrtc.class);

		int device = initCuda();
		printNvrtcVersion();
		Pointer module = loadKernel();
		Pointer function = getFunction(module, "fir_conv");

		int inputLength = FRAMES + TAPS - 1;
		long inputFloats = (long) STREAMS * inputLength;
		long tapsFloats = (long) STREAMS * TAPS;
		long outputFloats = (long) STREAMS * FRAMES;

		float[] input = new float[(int) inputFloats];
		float[] taps = new float[(int) tapsFloats];
		float[] output = new float[(int) outputFloats];
		fillInputs(input, taps);

		Memory hostInput = new Memory(inputFloats * Float.BYTES);
		Memory hostTaps = new Memory(tapsFloats * Float.BYTES);
		Memory hostOutput = new Memory(outputFloats * Float.BYTES);
		hostInput.write(0, input, 0, input.length);
		hostTaps.write(0, taps, 0, taps.length);

		LongByReference dInput = new LongByReference();
		LongByReference dTaps = new LongByReference();
		LongByReference dOutput = new LongByReference();
		check(cuda.cuMemAlloc_v2(dInput, inputFloats * Float.BYTES), "cuMemAlloc input");
		check(cuda.cuMemAlloc_v2(dTaps, tapsFloats * Float.BYTES), "cuMemAlloc taps");
		check(cuda.cuMemAlloc_v2(dOutput, outputFloats * Float.BYTES), "cuMemAlloc output");

		check(cuda.cuMemcpyHtoD_v2(dInput.getValue(), hostInput,
			inputFloats * Float.BYTES), "cuMemcpyHtoD input");
		check(cuda.cuMemcpyHtoD_v2(dTaps.getValue(), hostTaps,
			tapsFloats * Float.BYTES), "cuMemcpyHtoD taps");

		Memory kernelParams = kernelParams(dInput.getValue(), dTaps.getValue(),
			dOutput.getValue(), FRAMES, TAPS);
		int gridY = (FRAMES + BLOCK_THREADS - 1) / BLOCK_THREADS;
		Runnable launch = () -> check(cuda.cuLaunchKernel(function,
			STREAMS, gridY, 1,
			BLOCK_THREADS, 1, 1,
			0, null, kernelParams, null), "cuLaunchKernel");

		// Warm up JIT/launch path, then measure kernel-only throughput.
		for (int i = 0; i < 20; i++) {
			launch.run();
		}
		check(cuda.cuCtxSynchronize(), "warmup sync");

		int iterations = 200;
		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			launch.run();
		}
		check(cuda.cuCtxSynchronize(), "kernel sync");
		double kernelMs = (System.nanoTime() - start) / 1e6 / iterations;

		// Measure one full H2D + kernel + D2H round trip, with a breakdown.
		long h2dStart = System.nanoTime();
		check(cuda.cuMemcpyHtoD_v2(dInput.getValue(), hostInput,
			inputFloats * Float.BYTES), "cuMemcpyHtoD input");
		check(cuda.cuMemcpyHtoD_v2(dTaps.getValue(), hostTaps,
			tapsFloats * Float.BYTES), "cuMemcpyHtoD taps");
		check(cuda.cuCtxSynchronize(), "H2D sync");
		double h2dMs = (System.nanoTime() - h2dStart) / 1e6;

		long oneKernelStart = System.nanoTime();
		launch.run();
		check(cuda.cuCtxSynchronize(), "one-kernel sync");
		double oneKernelMs = (System.nanoTime() - oneKernelStart) / 1e6;

		long d2hStart = System.nanoTime();
		check(cuda.cuMemcpyDtoH_v2(hostOutput, dOutput.getValue(),
			outputFloats * Float.BYTES), "cuMemcpyDtoH output");
		check(cuda.cuCtxSynchronize(), "D2H sync");
		double d2hMs = (System.nanoTime() - d2hStart) / 1e6;
		double roundTripMs = h2dMs + oneKernelMs + d2hMs;

		hostOutput.read(0, output, 0, output.length);

		// Validate stream 0 against a scalar CPU reference.
		double maxError = 0.0;
		for (int n = 0; n < FRAMES; n++) {
			float acc = 0.0f;
			for (int k = 0; k < TAPS; k++) {
				acc = Math.fma(input[n + TAPS - 1 - k], taps[k], acc);
			}
			maxError = Math.max(maxError, Math.abs(acc - output[n]));
		}

		System.out.printf("kernel (200x): %8.3f ms / block%n", kernelMs);
		System.out.printf("one kernel:    %8.3f ms%n", oneKernelMs);
		System.out.printf("H2D:           %8.3f ms%n", h2dMs);
		System.out.printf("D2H:           %8.3f ms%n", d2hMs);
		System.out.printf("round trip:    %8.3f ms / block%n", roundTripMs);
		System.out.printf("CPU reference: %8.3f ms / block (78 FIRs measured earlier)%n",
			78 * 0.25);
		System.out.printf("max |GPU-CPU|: %.6f (stream 0)%n", maxError);

		check(cuda.cuMemFree_v2(dInput.getValue()), "cuMemFree input");
		check(cuda.cuMemFree_v2(dTaps.getValue()), "cuMemFree taps");
		check(cuda.cuMemFree_v2(dOutput.getValue()), "cuMemFree output");
		check(cuda.cuCtxDestroy_v2(context.getValue()), "cuCtxDestroy");
	}

	private static int initCuda() {
		check(cuda.cuInit(0), "cuInit");
		IntByReference count = new IntByReference();
		check(cuda.cuDeviceGetCount(count), "cuDeviceGetCount");
		if (count.getValue() <= 0) {
			throw new IllegalStateException("no CUDA devices");
		}
		IntByReference device = new IntByReference();
		check(cuda.cuDeviceGet(device, 0), "cuDeviceGet");

		Memory name = new Memory(256);
		check(cuda.cuDeviceGetName(name, 256, device.getValue()), "cuDeviceGetName");
		IntByReference major = new IntByReference();
		IntByReference minor = new IntByReference();
		check(cuda.cuDeviceGetAttribute(major, 75, device.getValue()),
			"compute capability major");
		check(cuda.cuDeviceGetAttribute(minor, 76, device.getValue()),
			"compute capability minor");

		context = new PointerByReference();
		check(cuda.cuCtxCreate_v2(context, 0, device.getValue()), "cuCtxCreate");
		System.out.printf("device:        %s (sm_%d%d)%n",
			name.getString(0), major.getValue(), minor.getValue());
		return device.getValue();
	}

	private static void printNvrtcVersion() {
		IntByReference major = new IntByReference();
		IntByReference minor = new IntByReference();
		int result = nvrtc.nvrtcVersion(major, minor);
		if (result == 0) {
			System.out.printf("nvrtc:         %d.%d%n", major.getValue(), minor.getValue());
		}
	}

	private static Pointer loadKernel() throws Exception {
		PointerByReference program = new PointerByReference();
		checkNvrtc(nvrtc.nvrtcCreateProgram(program, KERNEL_SOURCE, "fir.cu",
			0, null, null), "nvrtcCreateProgram");

		// RTX 5070 is sm_120, but the available NVRTC is 12.0. Compile for an
		// older PTX target and let the CUDA 13.x driver JIT it forward.
		String[][] optionSets = {
			{"--gpu-architecture=" + ptxArch, "--std=c++14"},
			{"--std=c++14"},
		};
		boolean compiled = false;
		for (String[] options : optionSets) {
			int result = nvrtc.nvrtcCompileProgram(program.getValue(),
				options.length, new StringArray(options));
			if (result == 0) {
				System.out.println("nvrtc options: " + String.join(" ", options));
				compiled = true;
				break;
			}
			System.out.println("nvrtc compile failed: "
				+ nvrtc.nvrtcGetErrorString(result));
			printProgramLog(program.getValue());
			// Recreate the program for the next option set.
			nvrtc.nvrtcDestroyProgram(program);
			checkNvrtc(nvrtc.nvrtcCreateProgram(program, KERNEL_SOURCE,
				"fir.cu", 0, null, null), "nvrtcCreateProgram retry");
		}
		if (!compiled) {
			throw new IllegalStateException("NVRTC could not compile the kernel");
		}

		LongByReference ptxSize = new LongByReference();
		checkNvrtc(nvrtc.nvrtcGetPTXSize(program.getValue(), ptxSize),
			"nvrtcGetPTXSize");
		System.out.printf("ptx size:      %d bytes%n", ptxSize.getValue());
		Memory ptx = new Memory(ptxSize.getValue());
		checkNvrtc(nvrtc.nvrtcGetPTX(program.getValue(), ptx), "nvrtcGetPTX");
		if (ptxDumpPath != null) {
			// Drop the trailing NUL so the file is plain UTF-8 text. The
			// loader appends a NUL before cuModuleLoadData.
			byte[] bytes = ptx.getByteArray(0, (int) ptxSize.getValue() - 1);
			java.nio.file.Files.write(java.nio.file.Path.of(ptxDumpPath), bytes);
			System.out.println("wrote PTX:     " + ptxDumpPath);
		}
		nvrtc.nvrtcDestroyProgram(program);

		PointerByReference module = new PointerByReference();
		check(cuda.cuModuleLoadData(module, ptx), "cuModuleLoadData");
		return module.getValue();
	}

	private static Pointer getFunction(Pointer module, String name) {
		PointerByReference function = new PointerByReference();
		check(cuda.cuModuleGetFunction(function, module, name),
			"cuModuleGetFunction " + name);
		return function.getValue();
	}

	private static Memory kernelParams(long input, long taps, long output,
									   int frames, int tapCount) {
		Memory argInput = new Memory(Long.BYTES);
		Memory argTaps = new Memory(Long.BYTES);
		Memory argOutput = new Memory(Long.BYTES);
		Memory argFrames = new Memory(Integer.BYTES);
		Memory argTapsCount = new Memory(Integer.BYTES);
		argInput.setLong(0, input);
		argTaps.setLong(0, taps);
		argOutput.setLong(0, output);
		argFrames.setInt(0, frames);
		argTapsCount.setInt(0, tapCount);

		int pointerSize = Native.POINTER_SIZE;
		Memory params = new Memory((long) pointerSize * 5);
		params.setPointer(0, argInput);
		params.setPointer(pointerSize, argTaps);
		params.setPointer(pointerSize * 2L, argOutput);
		params.setPointer(pointerSize * 3L, argFrames);
		params.setPointer(pointerSize * 4L, argTapsCount);
		return params;
	}

	private static void fillInputs(float[] input, float[] taps) {
		java.util.Random random = new java.util.Random(42);
		for (int i = 0; i < input.length; i++) {
			input[i] = (float) (random.nextDouble() * 0.5 - 0.25);
		}
		for (int stream = 0; stream < STREAMS; stream++) {
			int offset = stream * TAPS;
			for (int k = 0; k < TAPS; k++) {
				taps[offset + k] = (float) ((random.nextDouble() - 0.5) * 0.002);
			}
			taps[offset] = 0.5f;
		}
	}

	private static void printProgramLog(Pointer program) {
		LongByReference size = new LongByReference();
		if (nvrtc.nvrtcGetProgramLogSize(program, size) != 0
			|| size.getValue() <= 1) {
			return;
		}
		Memory log = new Memory(size.getValue());
		if (nvrtc.nvrtcGetProgramLog(program, log) == 0) {
			System.out.println(log.getString(0));
		}
	}

	private static void check(int result, String call) {
		if (result == 0) {
			return;
		}
		String message = "error " + result;
		try {
			PointerByReference text = new PointerByReference();
			if (cuda.cuGetErrorString(result, text) == 0
				&& text.getValue() != null) {
				message = text.getValue().getString(0);
			}
		} catch (Throwable ignored) {
			// Keep the numeric error if the string lookup fails.
		}
		throw new IllegalStateException(call + " failed: " + message);
	}

	private static void checkNvrtc(int result, String call) {
		if (result != 0) {
			throw new IllegalStateException(call + " failed: "
				+ nvrtc.nvrtcGetErrorString(result));
		}
	}
}
