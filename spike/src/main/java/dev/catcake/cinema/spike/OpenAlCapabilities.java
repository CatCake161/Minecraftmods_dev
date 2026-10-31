package dev.catcake.cinema.spike;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.SOFTDeviceClock;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Probes the OpenAL implementation and device exposed by LWJGL.
 *
 * <p>This is deliberately independent of Minecraft. Its job is to answer the
 * questions that decide whether the cinema can own its own OpenAL context:
 * which extensions exist, whether EFX/HRTF/latency are usable, and whether two
 * devices/contexts can coexist in one process (the Minecraft + cinema case).
 */
final class OpenAlCapabilities {
	private OpenAlCapabilities() {
	}

	static void run() {
		Probe first = probe("cinema-A");
		System.out.println("OpenAL vendor : " + safe(AL10.alGetString(AL10.AL_VENDOR)));
		System.out.println("OpenAL version: " + safe(AL10.alGetString(AL10.AL_VERSION)));
		System.out.println("OpenAL render : " + safe(AL10.alGetString(AL10.AL_RENDERER)));
		System.out.println("Default device: " + safe(ALC10.alcGetString(NULL, ALC10.ALC_DEFAULT_DEVICE_SPECIFIER)));

		first.print();

		// Simulate the second OpenAL consumer in the process (Minecraft's
		// SoundEngine) without touching Minecraft itself. Both are opened in
		// the same thread and each context is made current in turn.
		Probe second = probe("cinema-B");
		second.print();

		first.makeCurrent();
		first.printCurrentAgain();

		second.close();
		first.close();
	}

	private static Probe probe(String label) {
		long device = ALC10.alcOpenDevice((CharSequence) null);
		if (device == NULL) {
			throw new IllegalStateException(label + ": alcOpenDevice() failed");
		}
		ALCCapabilities deviceCaps = ALC.createCapabilities(device);
		long context = ALC10.alcCreateContext(device, (IntBuffer) null);
		if (context == NULL) {
			ALC10.alcCloseDevice(device);
			throw new IllegalStateException(label + ": alcCreateContext() failed");
		}
		ALC10.alcMakeContextCurrent(context);
		ALCapabilities alCaps = AL.createCapabilities(deviceCaps);

		Probe probe = new Probe(label, device, context, deviceCaps, alCaps);
		probe.collect();
		return probe;
	}

	private static String safe(String value) {
		return value == null ? "<null>" : value;
	}

	private static final class Probe implements AutoCloseable {
		private final String label;
		private final long device;
		private final long context;
		private final ALCCapabilities deviceCaps;
		private final ALCapabilities alCaps;

		private String alExtensions;
		private String alcExtensions;
		private boolean efx;
		private int efxMajor;
		private int efxMinor;
		private int maxAuxSends;
		private boolean hrtf;
		private boolean sourceRadius;
		private boolean sourceLatency;
		private boolean threadLocalContext;
		private boolean doublePrecision;
		private boolean bformat;
		private boolean sourceSpatialize;
		private boolean deviceClock;
		private boolean sourceStartDelay;
		private long deviceClockNanos;
		private long deviceLatencyNanos;
		private long deviceClockAtLatencyQuery;
		private long deviceLatencyAtClockQuery;

		private Probe(String label, long device, long context, ALCCapabilities deviceCaps, ALCapabilities alCaps) {
			this.label = label;
			this.device = device;
			this.context = context;
			this.deviceCaps = deviceCaps;
			this.alCaps = alCaps;
		}

		private void collect() {
			alExtensions = safe(AL10.alGetString(AL10.AL_EXTENSIONS));
			alcExtensions = safe(ALC10.alcGetString(device, ALC10.ALC_EXTENSIONS));
			efx = ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
			if (efx) {
				IntBuffer major = org.lwjgl.BufferUtils.createIntBuffer(1);
				IntBuffer minor = org.lwjgl.BufferUtils.createIntBuffer(1);
				ALC10.alcGetIntegerv(device, EXTEfx.ALC_EFX_MAJOR_VERSION, major);
				ALC10.alcGetIntegerv(device, EXTEfx.ALC_EFX_MINOR_VERSION, minor);
				efxMajor = major.get(0);
				efxMinor = minor.get(0);
				maxAuxSends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
			}
			hrtf = ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF");
			sourceRadius = alExtensions.contains("AL_EXT_SOURCE_RADIUS");
			sourceLatency = alExtensions.contains("AL_SOFT_source_latency");
			threadLocalContext = alcExtensions.contains("ALC_EXT_thread_local_context");
			doublePrecision = alExtensions.contains("AL_EXT_DOUBLE");
			bformat = alExtensions.contains("AL_EXT_BFORMAT");
			sourceSpatialize = alExtensions.contains("AL_SOFT_source_spatialize");
			deviceClock = alcExtensions.contains("ALC_SOFT_device_clock");
			sourceStartDelay = alExtensions.contains("AL_SOFT_source_start_delay");
			if (deviceClock) {
				deviceClockNanos = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
				deviceLatencyNanos = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_LATENCY_SOFT);
				LongBuffer clockLatency = org.lwjgl.BufferUtils.createLongBuffer(2);
				SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_LATENCY_SOFT, clockLatency);
				deviceClockAtLatencyQuery = clockLatency.get(0);
				deviceLatencyAtClockQuery = clockLatency.get(1);
			}
		}

		private void print() {
			System.out.println();
			System.out.println("[" + label + "] device=0x" + Long.toHexString(device)
				+ " context=0x" + Long.toHexString(context));
			System.out.println("  ALC version       : " + alcVersion());
			System.out.println("  EFX               : " + efx
				+ (efx ? " (" + efxMajor + "." + efxMinor + ", aux sends=" + maxAuxSends + ")" : ""));
			System.out.println("  HRTF (ALC_SOFT)   : " + hrtf);
			System.out.println("  source radius     : " + sourceRadius);
			System.out.println("  source latency    : " + sourceLatency);
			System.out.println("  source start delay: " + sourceStartDelay);
			System.out.println("  device clock      : " + deviceClock
				+ (deviceClock ? " (clock=" + deviceClockNanos + " ns, latency=" + deviceLatencyNanos
					+ " ns, atomic clock=" + deviceClockAtLatencyQuery + " ns, latency="
					+ deviceLatencyAtClockQuery + " ns)" : ""));
			System.out.println("  thread-local ctx  : " + threadLocalContext);
			System.out.println("  double precision  : " + doublePrecision);
			System.out.println("  B-format          : " + bformat);
			System.out.println("  source spatialize : " + sourceSpatialize);
			System.out.println("  AL extensions     : " + alExtensions);
			System.out.println("  ALC extensions    : " + alcExtensions);
		}

		private String alcVersion() {
			IntBuffer major = org.lwjgl.BufferUtils.createIntBuffer(1);
			IntBuffer minor = org.lwjgl.BufferUtils.createIntBuffer(1);
			ALC10.alcGetIntegerv(device, ALC10.ALC_MAJOR_VERSION, major);
			ALC10.alcGetIntegerv(device, ALC10.ALC_MINOR_VERSION, minor);
			return major.get(0) + "." + minor.get(0)
				+ " (ALC10=" + deviceCaps.OpenALC10 + ", ALC11=" + deviceCaps.OpenALC11 + ")";
		}

		private void printCurrentAgain() {
			makeCurrent();
			String renderer = safe(AL10.alGetString(AL10.AL_RENDERER));
			int error = AL10.alGetError();
			System.out.println();
			System.out.println("[" + label + "] after switching back: renderer=" + renderer
				+ ", alGetError=0x" + Integer.toHexString(error));
		}

		private void makeCurrent() {
			ALC10.alcMakeContextCurrent(context);
			AL.setCurrentThread(alCaps);
		}

		@Override
		public void close() {
			ALC10.alcMakeContextCurrent(NULL);
			ALC10.alcDestroyContext(context);
			ALC10.alcCloseDevice(device);
			AL.setCurrentThread(null);
			ALC.destroy();
		}
	}
}
