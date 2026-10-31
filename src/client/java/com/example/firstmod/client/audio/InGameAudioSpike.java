package com.example.firstmod.client.audio;

import com.example.firstmod.FirstMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
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
import org.lwjgl.openal.SOFTSourceLatency;
import org.lwjgl.openal.SOFTSourceStartDelay;

import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.lwjgl.openal.AL10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * P0.5 in-game probe.
 *
 * <p>Opens an OpenAL device/context that is independent from Minecraft's
 * {@code SoundEngine}, plays six mono tones through six positioned sources,
 * and checks that the sources are sample-aligned. It deliberately does not
 * call {@code ALC.destroy()} because that would tear down the LWJGL function
 * pointers shared with Minecraft.
 *
 * <p>This is a probe, not production code. The result decides whether the
 * cinema should own a context or share Minecraft's.
 */
public final class InGameAudioSpike {
	private static final int SAMPLE_RATE = 48_000;
	private static final int SPEAKERS = 6;
	private static final String[] LABELS = {"FL", "FR", "FC", "LFE", "BL", "BR"};
	private static final double[] FREQUENCIES = {440, 554, 659, 80, 880, 988};
	private static final float[][] POSITIONS = {
		{-3.0f, 0.0f, -4.0f},
		{3.0f, 0.0f, -4.0f},
		{0.0f, 0.0f, -4.5f},
		{0.0f, 0.0f, -2.5f},
		{-3.0f, 0.0f, 3.0f},
		{3.0f, 0.0f, 3.0f},
	};

	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static volatile String lastResult = "not run";

	private InGameAudioSpike() {
	}

	public static String lastResult() {
		return lastResult;
	}

	public static void runAsync() {
		if (!RUNNING.compareAndSet(false, true)) {
			FirstMod.LOGGER.warn("cinema audio spike is already running");
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				lastResult = runOnOpenAlThread();
			} catch (Throwable t) {
				lastResult = "FAILED: " + t;
				FirstMod.LOGGER.error("cinema audio spike failed", t);
			} finally {
				RUNNING.set(false);
			}
			// Verify that Minecraft's own SoundEngine can still reach OpenAL
			// after the independent context has been closed.
			Minecraft.getInstance().execute(() -> {
				Minecraft.getInstance().getSoundManager().play(
					SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				FirstMod.LOGGER.info("cinema audio spike: vanilla UI sound requested after probe");
			});
		}, "cinema-openal-spike");
		thread.setDaemon(true);
		thread.start();
	}

	private static String runOnOpenAlThread() throws Exception {
		FirstMod.LOGGER.info("cinema audio spike: opening independent OpenAL device");
		long device = ALC10.alcOpenDevice((CharSequence) null);
		if (device == NULL) {
			return "FAILED: alcOpenDevice() returned NULL";
		}
		ALCCapabilities deviceCaps = ALC.createCapabilities(device);
		long context = ALC10.alcCreateContext(device, (java.nio.IntBuffer) null);
		if (context == NULL) {
			ALC10.alcCloseDevice(device);
			return "FAILED: alcCreateContext() returned NULL";
		}
		ALC10.alcMakeContextCurrent(context);
		AL.createCapabilities(deviceCaps);

		FirstMod.LOGGER.info("cinema audio spike: renderer={} version={}",
			AL10.alGetString(AL10.AL_RENDERER), AL10.alGetString(AL10.AL_VERSION));
		FirstMod.LOGGER.info("cinema audio spike: EFX={} deviceClock={} startDelay={}",
			ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX"),
			ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock"),
			AL10.alIsExtensionPresent("AL_SOFT_source_start_delay"));

		int[] sources = new int[SPEAKERS];
		int[] buffers = new int[SPEAKERS];
		int reverb = 0;
		int slot = 0;
		int lowpass = 0;
		try {
			if (ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
				reverb = EXTEfx.alGenEffects();
				EXTEfx.alEffecti(reverb, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
				EXTEfx.alEffectf(reverb, EXTEfx.AL_EAXREVERB_DECAY_TIME, 0.7f);
				slot = EXTEfx.alGenAuxiliaryEffectSlots();
				EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, reverb);
				lowpass = EXTEfx.alGenFilters();
				EXTEfx.alFilteri(lowpass, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
				EXTEfx.alFilterf(lowpass, EXTEfx.AL_LOWPASS_GAINHF, 0.5f);
			}

			AL10.alListener3f(AL10.AL_POSITION, 0f, 0f, 0f);
			AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{0f, 0f, -1f, 0f, 1f, 0f});
			AL10.alDistanceModel(AL10.AL_INVERSE_DISTANCE_CLAMPED);

			for (int i = 0; i < SPEAKERS; i++) {
				sources[i] = alGenSources();
				buffers[i] = alGenBuffers();
				short[] tone = tone(FREQUENCIES[i], 1.0);
				ShortBuffer data = BufferUtils.createShortBuffer(tone.length);
				data.put(tone).flip();
				alBufferData(buffers[i], AL_FORMAT_MONO16, data, SAMPLE_RATE);
				alSourcei(sources[i], AL_BUFFER, buffers[i]);
				alSource3f(sources[i], AL_POSITION, POSITIONS[i][0], POSITIONS[i][1], POSITIONS[i][2]);
				alSourcef(sources[i], AL_GAIN, 0.03f);
				alSourcef(sources[i], AL_ROLLOFF_FACTOR, 1.0f);
				alSourcef(sources[i], AL_MAX_DISTANCE, 64f);
				alSourcef(sources[i], EXTSourceRadius.AL_SOURCE_RADIUS, 0.25f);
				alSourcef(sources[i], AL_CONE_INNER_ANGLE, 70f);
				alSourcef(sources[i], AL_CONE_OUTER_ANGLE, 130f);
				alSourcef(sources[i], AL_CONE_OUTER_GAIN, 0.25f);
				if (lowpass != 0) {
					AL11.alSource3i(sources[i], EXTEfx.AL_AUXILIARY_SEND_FILTER, slot, 0, EXTEfx.AL_FILTER_NULL);
					alSourcei(sources[i], EXTEfx.AL_DIRECT_FILTER, lowpass);
				}
			}
			check("speaker setup");

			long now = SOFTDeviceClock.alcGetInteger64vSOFT(device, SOFTDeviceClock.ALC_DEVICE_CLOCK_SOFT);
			long scheduled = now + 300_000_000L;
			for (int i = 0; i < SPEAKERS; i++) {
				SOFTSourceStartDelay.alSourcePlayAtTimeSOFT(sources[i], scheduled);
			}
			check("scheduled start");
			Thread.sleep(700);

			long[] offsetClock = new long[2];
			double[] offsets = new double[SPEAKERS];
			for (int i = 0; i < SPEAKERS; i++) {
				Arrays.fill(offsetClock, 0);
				SOFTSourceLatency.alGetSourcei64vSOFT(sources[i],
					SOFTDeviceClock.AL_SAMPLE_OFFSET_CLOCK_SOFT, offsetClock);
				offsets[i] = offsetClock[0] / (double) (1L << 32);
				FirstMod.LOGGER.info("cinema audio spike: {} offset={} samples state={}",
					LABELS[i], String.format("%.3f", offsets[i]),
					alGetSourcei(sources[i], AL_SOURCE_STATE));
			}
			double min = Arrays.stream(offsets).min().orElse(0);
			double max = Arrays.stream(offsets).max().orElse(0);
			double skewMs = (max - min) * 1000;
			FirstMod.LOGGER.info("cinema audio spike: inter-speaker skew={} ms", String.format("%.3f", skewMs));
			check("offset query");
			return "OK: 6 sources, skew=" + String.format("%.3f", skewMs) + " ms";
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
			// Do NOT call ALC.destroy(): Minecraft's SoundEngine shares the
			// same LWJGL OpenAL function pointers.
		}
	}

	private static short[] tone(double frequency, double seconds) {
		int samples = (int) (SAMPLE_RATE * seconds);
		short[] result = new short[samples];
		for (int i = 0; i < samples; i++) {
			result[i] = (short) (Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE) * 6_000);
		}
		return result;
	}

	private static void check(String where) {
		int error = alGetError();
		if (error != AL_NO_ERROR) {
			throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " at " + where);
		}
	}
}
