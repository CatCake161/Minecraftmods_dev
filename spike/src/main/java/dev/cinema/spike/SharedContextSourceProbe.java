package dev.cinema.spike;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTThreadLocalContext;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/** Verifies that a thread-local cinema context can coexist with Minecraft's context. */
final class SharedContextSourceProbe {
	private SharedContextSourceProbe() {
	}

	static void run() throws InterruptedException {
		long device = ALC10.alcOpenDevice((CharSequence) null);
		if (device == NULL) {
			throw new IllegalStateException("alcOpenDevice failed");
		}
		long minecraftContext = ALC10.alcCreateContext(device, (IntBuffer) null);
		long cinemaContext = ALC10.alcCreateContext(device, (IntBuffer) null);
		if (minecraftContext == NULL || cinemaContext == NULL) {
			throw new IllegalStateException("alcCreateContext failed");
		}
		ALC10.alcMakeContextCurrent(minecraftContext);
		ALCCapabilities deviceCaps = ALC.createCapabilities(device);
		ALCapabilities caps = AL.createCapabilities(deviceCaps);
		System.out.println("thread-local extension: "
			+ deviceCaps.ALC_EXT_thread_local_context + " / "
			+ ALC10.alcIsExtensionPresent(device, "ALC_EXT_thread_local_context"));
		int minecraftSources = allocate("minecraft", 64);
		System.out.println("minecraft before cinema: " + minecraftSources);

		Thread cinema = new Thread(() -> {
			boolean set = EXTThreadLocalContext.alcSetThreadContext(cinemaContext);
			AL.setCurrentThread(caps);
			System.out.println("cinema thread context set=" + set
				+ " thread=0x" + Long.toHexString(EXTThreadLocalContext.alcGetThreadContext())
				+ " process=0x" + Long.toHexString(ALC10.alcGetCurrentContext()));
			int allocated = allocate("cinema", 128);
			System.out.println("cinema allocated: " + allocated);
		}, "shared-context-probe");
		cinema.start();
		cinema.join();

		ALC10.alcMakeContextCurrent(minecraftContext);
		AL.setCurrentThread(caps);
		int after = allocate("minecraft-after", 128);
		System.out.println("minecraft after cinema: " + after);
		int error = AL10.alGetError();
		System.out.println("minecraft final alGetError=0x" + Integer.toHexString(error));

		ALC10.alcMakeContextCurrent(NULL);
		ALC10.alcDestroyContext(cinemaContext);
		ALC10.alcDestroyContext(minecraftContext);
		ALC10.alcCloseDevice(device);
	}

	private static int allocate(String label, int count) {
		int allocated = 0;
		for (int i = 0; i < count; i++) {
			int source = AL10.alGenSources();
			if (source == 0) {
				break;
			}
			allocated++;
		}
		int error = AL10.alGetError();
		System.out.println(label + ": allocated=" + allocated
			+ " error=0x" + Integer.toHexString(error));
		return allocated;
	}
}
