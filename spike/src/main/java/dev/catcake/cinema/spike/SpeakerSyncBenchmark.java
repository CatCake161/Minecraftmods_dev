package dev.catcake.cinema.spike;

import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTSourceRadius;
import org.lwjgl.openal.SOFTDeviceClock;
import org.lwjgl.openal.SOFTSourceStartDelay;
import org.lwjgl.openal.SOFTSourceLatency;

import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * P0.5 speaker spike.
 *
 * <p>Creates six independent mono sources in one OpenAL context, places them
 * like a 5.1 room, and schedules all of them at the same device-clock time.
 * The test then reads the source offsets and verifies that the speakers are
 * sample-aligned. It also exercises the EFX reverb send, a direct low-pass
 * filter, source radius and source cone attenuation.
 *
 * <p>This is intentionally a standalone process. The Minecraft integration
 * case (sharing or opening a second device) is tested separately in-game.
 */
final class SpeakerSyncBenchmark {
	private static final int SAMPLE_RATE = 48_000;
	private static final int SPEAKERS = 6;
	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final double[] FREQUENCIES = {440, 554, 659, 80, 880, 988};
	// Standing at the origin, looking toward -Z: front speakers are in front.
	private static final float[][] POSITIONS = {
		{-3.0f, 0.0f, -4.0f},
		{3.0f, 0.0f, -4.0f},
		{0.0f, 0.0f, -4.5f},
		{0.0f, 0.0f, -2.5f},
		{-3.0f, 0.0f, 3.0f},
		{3.0f, 0.0f, 3.0f},
	};

	private SpeakerSyncBenchmark() {
	}

	static void run() throws Exception {
		long device = ALC10.alcOpenDevice((CharSequence) null);
		if (device == NULL) {
			throw new IllegalStateException("alcOpenDevice() failed");
		}
		ALCCapabilities deviceCaps = ALC.createCapabilities(device);
		long context = ALC10.alcCreateContext(device, (java.nio.IntBuffer) null);
		if (context == NULL) {
			ALC10.alcCloseDevice(device);
			throw new IllegalStateException("alcCreateContext() failed");
		}
		ALC10.alcMakeContextCurrent(context);
		AL.createCapabilities(deviceCaps);

		int[] sources = new int[SPEAKERS];
		int[] buffers = new int[SPEAKERS];
		int reverb = 0;
		int slot = 0;
		int lowpass = 0;

		try {
			reverb = EXTEfx.alGenEffects();
			EXTEfx.alEffecti(reverb, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
			EXTEfx.alEffectf(reverb, EXTEfx.AL_EAXREVERB_DECAY_TIME, 0.7f);
			EXTEfx.alEffectf(reverb, EXTEfx.AL_EAXREVERB_GAIN, 0.25f);
			slot = EXTEfx.alGenAuxiliaryEffectSlots();
			EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverb);
			check("EFX reverb");

			lowpass = EXTEfx.alGenFilters();
			EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
			EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, 0.4f);

			AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
			AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{0f, 0f, -1f, 0f, 1f, 0f});
			AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);

			for (int i = 0; i < SPEAKERS; i++) {
				sources[i] = alGenSources();
				buffers[i] = alGenBuffers();
				short[] tone = tone(FREQUENCIES[i], 2.0);
				ShortBuffer data = BufferUtils.createShortBuffer(tone.length);
				data.put(tone).flip();
				alBufferData(buffers[i], AL_FORMAT_MONO16, data, SAMPLE_RATE);
				alSourcei(sources[i], AL_BUFFER, buffers[i]);
				alSource3f(sources[i], AL_POSITION, POSITIONS[i][0], POSITIONS[i][1], POSITIONS[i][2]);
				alSourcef(sources[i], AL_GAIN, 0.05f);
				alSourcef(sources[i], AL_ROLLOFF_FACTOR, 1.0f);
				alSourcef(sources[i], AL_MAX_DISTANCE, 64f);
				alSourcef(sources[i], EXTSourceRadius.AL_SOURCE_RADIUS, 0.25f);

				// Aim the cabinet at the listening position.
				float dx = -POSITIONS[i][0];
				float dy = -POSITIONS[i][1];
				float dz = -POSITIONS[i][2];
				float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
				alSource3f(sources[i], AL_DIRECTION, dx / length, dy / length, dz / length);
				alSourcef(sources[i], AL_CONE_INNER_ANGLE, 70f);
				alSourcef(sources[i], AL_CONE_OUTER_ANGLE, 130f);
				alSourcef(sources[i], AL_CONE_OUTER_GAIN, 0.25f);

				// One reverb send plus a direct low-pass filter.
				AL11.alSource3i(sources[i], EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
				alSourcei(sources[i], EXTEfx.AL_DIRECT_FILTER, lowpass);
			}
			check("speaker setup");

			long now = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
			long scheduled = now + 400_000_000L;
			LongBuffer clockLatency = BufferUtils.createLongBuffer(2);
			SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_LATENCY_SOFT, clockLatency);
			System.out.printf("device clock : now=%d ns, scheduled start=%d ns (in %.1f ms), latency=%d ms%n",
				now, scheduled, (scheduled - now) / 1e6, clockLatency.get(1) / 1_000_000);

			// Schedule every speaker at exactly the same device-clock instant.
			for (int i = 0; i < SPEAKERS; i++) {
				SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(sources[i], scheduled);
			}
			check("scheduled start");

			Thread.sleep(1_000);

			long[] offsetClock = new long[2];
			double[] offsets = new double[SPEAKERS];
			System.out.println();
			System.out.println("speaker  state    sample offset    device clock (ms)");
			for (int i = 0; i < SPEAKERS; i++) {
				Arrays.fill(offsetClock, 0);
				SOFTSourceLatency.alGetSourcei64vSOFT(sources[i], SOFTDeviceClock.AL_SAMPLE_OFFSET_CLOCK_SOFT, offsetClock);
				double offset = offsetClock[0] / (double) (1L << 32);
				offsets[i] = offset;
				System.out.printf("%-8s %-8s %14.3f   %12.3f%n",
					LABELS[i], stateName(alGetSourcei(sources[i], AL_SOURCE_STATE)),
					offset, offsetClock[1] / 1e6);
			}
			double min = Arrays.stream(offsets).min().orElse(0);
			double max = Arrays.stream(offsets).max().orElse(0);
			System.out.printf("%ninter-speaker skew: %.3f ms (%.0f samples)%n",
				(max - min) * 1000, (max - min) * SAMPLE_RATE);
			System.out.println("result: " + ((max - min) * 1000 < 1.0 ? "SAMPLE-ALIGNED" : "MISALIGNED"));

			check("offset query");
		} finally {
			for (int source : sources) {
				if (source != 0) {
					alSourceStop(source);
					alDeleteSources(source);
				}
			}
			for (int buffer : buffers) {
				if (buffer != 0) {
					alDeleteBuffers(buffer);
				}
			}
			if (lowpass != 0) {
				EXTEfx.alDeleteFilters(lowpass);
			}
			if (slot != 0) {
				EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
			}
			if (reverb != 0) {
				EXTEfx.alDeleteEffects(reverb);
			}
			ALC10.alcMakeContextCurrent(NULL);
			ALC10.alcDestroyContext(context);
			ALC10.alcCloseDevice(device);
			AL.setCurrentThread(null);
			ALC.destroy();
		}
	}

	private static short[] tone(double frequency, double seconds) {
		int samples = (int) (SAMPLE_RATE * seconds);
		short[] result = new short[samples];
		for (int i = 0; i < samples; i++) {
			result[i] = (short) (Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE) * 8_000);
		}
		return result;
	}

	private static String stateName(int state) {
		return switch (state) {
			case AL_INITIAL -> "INITIAL";
			case AL_PLAYING -> "PLAYING";
			case AL_PAUSED -> "PAUSED";
			case AL_STOPPED -> "STOPPED";
			default -> Integer.toString(state);
		};
	}

	private static void check(String where) {
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " at " + where);
		}
	}
}
