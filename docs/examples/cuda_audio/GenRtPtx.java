import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline PTX generator for the CUDA ray tracer.
 *
 * <p>Not part of the mod build. Usage:
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp &lt;jna&gt; GenRtPtx.java \
 *     &lt;nvrtc dll&gt; &lt;rt_trace.cu&gt; &lt;rt_trace.ptx&gt;
 * </pre>
 */
public final class GenRtPtx {
	interface Nvrtc extends Library {
		int nvrtcVersion(IntByReference major, IntByReference minor);

		int nvrtcCreateProgram(PointerByReference program, String source,
							   String name, int numHeaders, Pointer[] headers,
							   String[] includeNames);

		int nvrtcCompileProgram(Pointer program, int numOptions, StringArray options);

		int nvrtcGetPTXSize(Pointer program, LongByReference size);

		int nvrtcGetPTX(Pointer program, Pointer ptx);

		int nvrtcGetProgramLogSize(Pointer program, LongByReference size);

		int nvrtcGetProgramLog(Pointer program, Pointer log);

		int nvrtcDestroyProgram(PointerByReference program);

		String nvrtcGetErrorString(int result);
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err.println("usage: GenRtPtx <nvrtc.dll> <in.cu> <out.ptx> [arch]");
			System.exit(2);
		}
		String arch = args.length > 3 ? args[3] : "compute_75";
		Nvrtc nvrtc = Native.load(args[0], Nvrtc.class);
		String source = Files.readString(Path.of(args[1]));
		PointerByReference program = new PointerByReference();
		check(nvrtc.nvrtcCreateProgram(program, source, "rt_trace.cu",
			0, null, null), "nvrtcCreateProgram");
		StringArray options = new StringArray(new String[]{
			"--gpu-architecture=" + arch, "--std=c++14", "-lineinfo"});
		int result = nvrtc.nvrtcCompileProgram(program.getValue(), 3, options);
		if (result != 0) {
			System.err.println("compile failed: " + nvrtc.nvrtcGetErrorString(result));
			LongByReference logSize = new LongByReference();
			nvrtc.nvrtcGetProgramLogSize(program.getValue(), logSize);
			if (logSize.getValue() > 0) {
				Memory log = new Memory(logSize.getValue());
				nvrtc.nvrtcGetProgramLog(program.getValue(), log);
				System.err.println(log.getString(0));
			}
			System.exit(1);
		}
		LongByReference ptxSize = new LongByReference();
		check(nvrtc.nvrtcGetPTXSize(program.getValue(), ptxSize), "nvrtcGetPTXSize");
		Memory ptx = new Memory(ptxSize.getValue());
		check(nvrtc.nvrtcGetPTX(program.getValue(), ptx), "nvrtcGetPTX");
		byte[] bytes = ptx.getByteArray(0, (int) ptxSize.getValue() - 1);
		Files.write(Path.of(args[2]), bytes);
		nvrtc.nvrtcDestroyProgram(program);
		System.out.println("wrote " + args[2] + " (" + bytes.length + " bytes)");
	}

	private static void check(int result, String call) {
		if (result != 0) {
			throw new IllegalStateException(call + " failed: " + result);
		}
	}
}
