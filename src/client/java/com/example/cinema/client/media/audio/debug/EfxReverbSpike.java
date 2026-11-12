package com.example.cinema.client.media.audio.debug;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.audio.playback.MinecraftOpenAl;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.lwjgl.openal.SOFTHRTF;
import org.lwjgl.openal.SOFTLoopback;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_MAX_DISTANCE;
import static org.lwjgl.openal.AL10.AL_NO_ERROR;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR;
import static org.lwjgl.openal.AL10.AL_SOURCE_RELATIVE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A0 EFX/EAXReverb capability spike.
 *
 * <p>This probe opens an OpenAL Soft loopback device, so it can render samples
 * without touching the real output device. It checks:
 *
 * <ul>
 *   <li>EFX availability with the shared Minecraft function pointers;</li>
 *   <li>ALC_MAX_AUXILIARY_SENDS and per-source auxiliary sends;</li>
 *   <li>EAXReverb creation with early reflections/echo disabled;</li>
 *   <li>wet return channel layout for 5.1 and HRTF stereo;</li>
 *   <li>reverb CPU cost; and</li>
 *   <li>context reset/recreate with EFX objects.</li>
 * </ul>
 *
 * <p>The result is written to the game log and shown in chat. It is a spike,
 * not production mixing code.
 */
public final class EfxReverbSpike {
	private static final int SAMPLE_RATE = 48_000;
	private static final int BLOCK_FRAMES = 512;
	private static final int PRE_ROLL_FRAMES = SAMPLE_RATE * 2;
	private static final int BENCHMARK_SECONDS = 4;
	private static final AtomicBoolean RUNNING = new AtomicBoolean();
	private static volatile String lastResult = "not run";

	private EfxReverbSpike() {
	}

	public static String lastResult() {
		return lastResult;
	}

	public static void runAsync() {
		if (!RUNNING.compareAndSet(false, true)) {
			CinemaLog.warn(CinemaLog.EFX_SPIKE, "is already running");
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				lastResult = run();
			} catch (Throwable t) {
				lastResult = "FAILED: " + t;
				CinemaLog.error(CinemaLog.EFX_SPIKE, "failed", t);
			} finally {
				RUNNING.set(false);
			}
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				minecraft.execute(() -> {
					if (minecraft.player != null) {
						minecraft.player.displayClientMessage(
							Component.literal("cinema EFX spike: " + lastResult), false);
					}
					// Verify Minecraft's own SoundEngine still works after the
					// temporary loopback capabilities were restored.
					minecraft.getSoundManager().play(
						net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
							net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
					CinemaLog.info(CinemaLog.EFX_SPIKE, "vanilla UI sound requested after probe");
				});
			}
		}, "cinema-efx-spike");
		thread.setDaemon(true);
		thread.start();
	}

	private static String run() {
		Report report = new Report();
		queryRealDevice(report);
		testRealDeviceContext(report);
		testFivePointOne(report);
		testHrtfStereo(report);
		testReset(report);
		report.line("done");
		return report.summary();
	}

	private static void queryRealDevice(Report report) {
		long device = MinecraftOpenAl.device();
		if (device == NULL) {
			report.line("real device: unavailable (SoundEngine not ready)");
			return;
		}
		report.line("real device: " + ALC10.alcGetString(device, ALC10.ALC_DEVICE_SPECIFIER));
		report.line("real device extensions: EFX=" + ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")
			+ " HRTF=" + ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF")
			+ " loopback=" + ALC10.alcIsExtensionPresent(device, "ALC_SOFT_loopback")
			+ " reopen=" + ALC10.alcIsExtensionPresent(device, "ALC_SOFT_reopen_device")
			+ " deviceClock=" + ALC10.alcIsExtensionPresent(device, "ALC_SOFT_device_clock"));
		if (ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
			try {
				IntBuffer sends = BufferUtils.createIntBuffer(1);
				ALC10.alcGetIntegerv(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS, sends);
				report.key("realMaxAuxSends=" + sends.get(0));
				alcCheck(device, "real ALC_MAX_AUXILIARY_SENDS");
			} catch (Throwable t) {
				report.line("real maxAuxSends query failed: " + t);
			}
		}
		if (ALC10.alcIsExtensionPresent(device, "ALC_SOFT_HRTF")) {
			try {
				IntBuffer status = BufferUtils.createIntBuffer(1);
				ALC10.alcGetIntegerv(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT, status);
				report.line("real HRTF status: " + hrtfStatus(status.get(0)));
			} catch (Throwable t) {
				report.line("real HRTF status query failed: " + t);
			}
		}
		report.line("real reopen extension: "
			+ ALC10.alcIsExtensionPresent(device, "ALC_SOFT_reopen_device")
			+ " (physical device switch not exercised)");
	}

	/**
	 * Creates a short-lived context on Minecraft's real device and checks EFX
	 * there. It never plays audio and never closes/disrupts Minecraft's device.
	 * If thread-local contexts are unsupported, it skips instead of making the
	 * context process-current.
	 */
	private static void testRealDeviceContext(Report report) {
		long device = MinecraftOpenAl.device();
		if (device == NULL) {
			report.key("realEFX=NO_DEVICE");
			return;
		}
		long context = NULL;
		ALCapabilities previous = null;
		boolean threadLocal = false;
		try {
			ALCCapabilities deviceCaps = ALC.createCapabilities(device);
			threadLocal = deviceCaps.ALC_EXT_thread_local_context;
			if (!threadLocal) {
				report.key("realEFX=SKIPPED_NO_THREAD_LOCAL_CONTEXT");
				return;
			}
			context = ALC10.alcCreateContext(device, (IntBuffer) null);
			if (context == NULL) {
				report.key("realEFX=CONTEXT_FAILED");
				return;
			}
			EXTThreadLocalContext.alcSetThreadContext(context);
			try {
				previous = AL.getCapabilities();
			} catch (IllegalStateException ignored) {
				// No process-wide caps; create fresh caps below.
			}
			boolean sharedEfx = false;
			if (previous != null) {
				AL.setCurrentThread(previous);
				if (previous.ALC_EXT_EFX) {
					try {
						int probe = EXTEfx.alGenEffects();
						EXTEfx.alDeleteEffects(probe);
						sharedEfx = true;
						report.line("real shared caps EFX call=OK");
					} catch (Throwable t) {
						report.line("real shared caps EFX call=FAILED: " + t);
					}
				}
			}
			if (!sharedEfx) {
				AL.createCapabilities(deviceCaps);
			}
			AL10.alGetError();
			ALC10.alcGetError(device);
			ALCapabilities caps = AL.getCapabilities();
			if (!caps.ALC_EXT_EFX) {
				report.key("realEFX=MISSING");
				return;
			}
			IntBuffer sends = BufferUtils.createIntBuffer(1);
			ALC10.alcGetIntegerv(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS, sends);
			report.key("realMaxAuxSends(context)=" + sends.get(0));
			Effects effects = Effects.create(device, report);
			if (effects == null) {
				report.key("realEFX=EFFECT_CREATE_FAILED");
				return;
			}
			effects.close();
			report.key("realEFX=OK");
		} catch (Throwable t) {
			report.line("real device context failed: " + t);
			report.key("realEFX=FAILED");
		} finally {
			if (threadLocal) {
				EXTThreadLocalContext.alcSetThreadContext(NULL);
			}
			if (context != NULL) {
				ALC10.alcDestroyContext(context);
			}
			AL.setCurrentThread(previous);
		}
	}

	private static void testFivePointOne(Report report) {
		report.line("== 5.1 loopback ==");
		try (Loopback loopback = Loopback.open(SOFTLoopback.ALC_5POINT1_SOFT, 6, false, report)) {
			if (!loopback.efx) {
				report.key("5.1 EFX=unavailable");
				return;
			}
			Effects effects = Effects.create(loopback.device, report);
			if (effects == null) {
				report.key("5.1 EAXReverb=create-failed");
				return;
			}
			Source source = Source.create(loopback, 0.0f, 0.0f, -1.0f, report);
			try {
				AL11.alSource3i(source.source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
					effects.slot, 0, EXTEfx.AL_FILTER_NULL);
				checkAl("5.1 assign aux send");

				double[] dry = measure(loopback, effects, source, false, false);
				double[] wet = measure(loopback, effects, source, true, true);
				double[] both = measure(loopback, effects, source, false, true);
				report.line("5.1 dry dBFS=" + dbArray(dry));
				report.line("5.1 wet dBFS=" + dbArray(wet) + " wetChannels=" + audibleChannels(wet));
				report.line("5.1 both dBFS=" + dbArray(both));
				report.key("5.1 wetCh=" + audibleChannels(wet)
					+ " wetMax=" + db(max(wet)) + "dBFS");

				long wetNanos = benchmark(loopback, effects, source, true);
				long dryNanos = benchmark(loopback, effects, source, false);
				double seconds = BENCHMARK_SECONDS;
				report.line("5.1 benchmark wet=" + wetNanos / 1_000_000 + " ms dry="
					+ dryNanos / 1_000_000 + " ms");
				report.key("5.1 CPU wet=" + percent(wetNanos, seconds)
					+ "% dry=" + percent(dryNanos, seconds) + "%");
				report.key("5.1 xRealtime=" + String.format(Locale.ROOT, "%.1f",
					seconds / (wetNanos / 1_000_000_000.0)));
			} finally {
				source.close();
				effects.close();
			}
		} catch (Throwable t) {
			report.line("5.1 loopback failed: " + t);
			report.key("5.1=FAILED");
		}
	}

	private static void testHrtfStereo(Report report) {
		report.line("== stereo/HRTF loopback ==");
		try (Loopback loopback = Loopback.open(SOFTLoopback.ALC_STEREO_SOFT, 2, true, report)) {
			if (!loopback.efx) {
				report.key("HRTF EFX=unavailable");
				return;
			}
			Effects effects = Effects.create(loopback.device, report);
			if (effects == null) {
				report.key("HRTF EAXReverb=create-failed");
				return;
			}
			Source source = Source.create(loopback, -1.5f, 0.0f, -1.5f, report);
			try {
				AL11.alSource3i(source.source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
					effects.slot, 0, EXTEfx.AL_FILTER_NULL);
				double[] dry = measure(loopback, effects, source, false, false);
				double[] wet = measure(loopback, effects, source, true, true);
				report.line("HRTF dry dBFS=" + dbArray(dry));
				report.line("HRTF wet dBFS=" + dbArray(wet));
				report.key("HRTF=" + hrtfStatus(loopback.hrtfStatus)
					+ " dryL/R=" + db(dry[0]) + "/" + db(dry[1])
					+ " wetL/R=" + db(wet[0]) + "/" + db(wet[1]));
			} finally {
				source.close();
				effects.close();
			}
		} catch (Throwable t) {
			report.line("stereo/HRTF loopback failed: " + t);
			report.key("HRTF=FAILED");
		}
	}

	private static void testReset(Report report) {
		report.line("== reset/recreate ==");
		try (Loopback loopback = Loopback.open(SOFTLoopback.ALC_5POINT1_SOFT, 6, false, report)) {
			if (!loopback.efx) {
				report.key("reset EFX=unavailable");
				return;
			}
			Effects first = Effects.create(loopback.device, report);
			if (first == null) {
				report.key("reset effect=create-failed");
				return;
			}
			first.close();
			Effects second = Effects.create(loopback.device, report);
			if (second == null) {
				report.key("reset effect=recreate-failed");
				return;
			}
			second.close();
			report.key("reset=OK");
		} catch (Throwable t) {
			report.line("reset failed: " + t);
			report.key("reset=FAILED");
		}
	}

	private static double[] measure(Loopback loopback, Effects effects, Source source,
									boolean wetOnly, boolean effectOn) {
		EXTEfx.alAuxiliaryEffectSlotf(effects.slot, EXTEfx.AL_EFFECTSLOT_GAIN,
			effectOn ? 1.0f : 0.0f);
		if (wetOnly) {
			AL10.alSourcei(source.source, EXTEfx.AL_DIRECT_FILTER, effects.muteFilter);
		} else {
			AL10.alSourcei(source.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
		}
		checkAl("measure setup");
		renderFrames(loopback, PRE_ROLL_FRAMES);
		double[] result = renderRms(loopback, BLOCK_FRAMES);
		AL10.alSourcei(source.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
		checkAl("measure cleanup");
		return result;
	}

	private static long benchmark(Loopback loopback, Effects effects, Source source,
								  boolean reverb) {
		EXTEfx.alAuxiliaryEffectSlotf(effects.slot, EXTEfx.AL_EFFECTSLOT_GAIN,
			reverb ? 1.0f : 0.0f);
		AL11.alSource3i(source.source, EXTEfx.AL_AUXILIARY_SEND_FILTER,
			reverb ? effects.slot : EXTEfx.AL_EFFECTSLOT_NULL,
			0, EXTEfx.AL_FILTER_NULL);
		AL10.alSourcei(source.source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
		checkAl("benchmark send");
		renderFrames(loopback, PRE_ROLL_FRAMES);
		long start = System.nanoTime();
		int frames = SAMPLE_RATE * BENCHMARK_SECONDS;
		ShortBuffer buffer = BufferUtils.createShortBuffer(
			Math.min(BLOCK_FRAMES, frames) * loopback.channels);
		int remaining = frames;
		while (remaining > 0) {
			int block = Math.min(BLOCK_FRAMES, remaining);
			buffer.clear();
			SOFTLoopback.alcRenderSamplesSOFT(loopback.device, buffer, block);
			remaining -= block;
		}
		long elapsed = System.nanoTime() - start;
		checkAl("benchmark");
		return elapsed;
	}

	private static void renderFrames(Loopback loopback, int totalFrames) {
		ShortBuffer buffer = BufferUtils.createShortBuffer(BLOCK_FRAMES * loopback.channels);
		int remaining = totalFrames;
		while (remaining > 0) {
			int block = Math.min(BLOCK_FRAMES, remaining);
			buffer.clear();
			SOFTLoopback.alcRenderSamplesSOFT(loopback.device, buffer, block);
			remaining -= block;
		}
		checkAl("render pre-roll");
	}

	private static double[] renderRms(Loopback loopback, int frames) {
		ShortBuffer buffer = BufferUtils.createShortBuffer(frames * loopback.channels);
		buffer.clear();
		SOFTLoopback.alcRenderSamplesSOFT(loopback.device, buffer, frames);
		checkAl("render RMS");
		double[] sums = new double[loopback.channels];
		for (int frame = 0; frame < frames; frame++) {
			for (int channel = 0; channel < loopback.channels; channel++) {
				double value = buffer.get(frame * loopback.channels + channel) / 32768.0;
				sums[channel] += value * value;
			}
		}
		double[] rms = new double[loopback.channels];
		for (int channel = 0; channel < loopback.channels; channel++) {
			rms[channel] = Math.sqrt(sums[channel] / frames);
		}
		return rms;
	}

	private static int audibleChannels(double[] rms) {
		int count = 0;
		for (double value : rms) {
			if (value > 1.0e-4) {
				count++;
			}
		}
		return count;
	}

	private static double max(double[] values) {
		double max = 0.0;
		for (double value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	private static String dbArray(double[] values) {
		StringBuilder builder = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) {
				builder.append(' ');
			}
			builder.append(db(values[i]));
		}
		return builder.append(']').toString();
	}

	private static String db(double value) {
		double db = 20.0 * Math.log10(Math.max(1.0e-6, value));
		return String.format(Locale.ROOT, "%.1f", db);
	}

	private static String percent(long nanos, double seconds) {
		return String.format(Locale.ROOT, "%.2f",
			nanos / 1_000_000_000.0 / seconds * 100.0);
	}

	private static String hrtfStatus(int status) {
		return switch (status) {
			case SOFTHRTF.ALC_HRTF_DISABLED_SOFT -> "disabled";
			case SOFTHRTF.ALC_HRTF_ENABLED_SOFT -> "enabled";
			case SOFTHRTF.ALC_HRTF_DENIED_SOFT -> "denied";
			case SOFTHRTF.ALC_HRTF_REQUIRED_SOFT -> "required";
			case SOFTHRTF.ALC_HRTF_HEADPHONES_DETECTED_SOFT -> "headphones";
			case SOFTHRTF.ALC_HRTF_UNSUPPORTED_FORMAT_SOFT -> "unsupported-format";
			default -> "unknown(" + status + ")";
		};
	}

	private static void checkAl(String where) {
		int error = AL10.alGetError();
		if (error != AL_NO_ERROR) {
			throw new IllegalStateException("OpenAL error 0x"
				+ Integer.toHexString(error) + " at " + where);
		}
	}

	private static void alcCheck(long device, String where) {
		int error = ALC10.alcGetError(device);
		if (error != ALC10.ALC_NO_ERROR) {
			throw new IllegalStateException("ALC error 0x"
				+ Integer.toHexString(error) + " at " + where);
		}
	}

	private static final class Report {
		private final List<String> summary = new ArrayList<>();

		void line(String message) {
			CinemaLog.info(CinemaLog.EFX_SPIKE, "{}", message);
		}

		void key(String message) {
			if (!summary.contains(message)) {
				summary.add(message);
			}
			line(message);
		}

		String summary() {
			return summary.isEmpty() ? "no result" : String.join(" | ", summary);
		}
	}

	private static final class Loopback implements AutoCloseable {
		final long device;
		final long context;
		final int channels;
		final boolean efx;
		final int hrtfStatus;
		private final boolean threadLocal;
		private final ALCapabilities previousCaps;

		private Loopback(long device, long context, int channels, boolean efx,
						 int hrtfStatus, boolean threadLocal, ALCapabilities previousCaps) {
			this.device = device;
			this.context = context;
			this.channels = channels;
			this.efx = efx;
			this.hrtfStatus = hrtfStatus;
			this.threadLocal = threadLocal;
			this.previousCaps = previousCaps;
		}

		static Loopback open(int formatChannels, int channels, boolean hrtf, Report report) {
			long device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
			if (device == NULL) {
				throw new IllegalStateException("alcLoopbackOpenDeviceSOFT() failed");
			}
			ALCCapabilities deviceCaps = ALC.createCapabilities(device);
			if (!deviceCaps.ALC_SOFT_loopback) {
				ALC10.alcCloseDevice(device);
				throw new IllegalStateException("ALC_SOFT_loopback missing");
			}
			boolean supported = SOFTLoopback.alcIsRenderFormatSupportedSOFT(
				device, SAMPLE_RATE, formatChannels, SOFTLoopback.ALC_SHORT_SOFT);
			report.line("loopback channels=" + channels + " supported=" + supported
				+ " hrtfRequested=" + hrtf);
			IntBuffer attributes = BufferUtils.createIntBuffer(16);
			attributes.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(formatChannels);
			attributes.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
			attributes.put(ALC10.ALC_FREQUENCY).put(SAMPLE_RATE);
			if (hrtf) {
				attributes.put(SOFTHRTF.ALC_HRTF_SOFT).put(ALC10.ALC_TRUE);
			}
			attributes.put(0).flip();
			long context = ALC10.alcCreateContext(device, attributes);
			if (context == NULL) {
				ALC10.alcCloseDevice(device);
				throw new IllegalStateException("alcCreateContext() failed");
			}
			boolean threadLocal = deviceCaps.ALC_EXT_thread_local_context;
			if (threadLocal) {
				EXTThreadLocalContext.alcSetThreadContext(context);
			} else {
				ALC10.alcMakeContextCurrent(context);
			}
			ALCapabilities previous = null;
			try {
				previous = AL.getCapabilities();
			} catch (IllegalStateException ignored) {
				// No process-wide caps yet.
			}
			boolean sharedEfx = false;
			if (previous != null) {
				AL.setCurrentThread(previous);
				report.line("shared caps: ALC_EXT_EFX=" + previous.ALC_EXT_EFX);
				if (previous.ALC_EXT_EFX) {
					try {
						int probe = EXTEfx.alGenEffects();
						EXTEfx.alDeleteEffects(probe);
						sharedEfx = true;
						report.line("shared caps EFX call=OK");
					} catch (Throwable t) {
						report.line("shared caps EFX call=FAILED: " + t);
					}
				}
				report.key("sharedCapsEFX=" + (previous.ALC_EXT_EFX
					? (sharedEfx ? "OK" : "CALL_FAILED") : "MISSING"));
			} else {
				report.key("sharedCapsEFX=NO_PROCESS_CAPS");
			}
			if (!sharedEfx) {
				report.line("using fresh loopback capabilities (shared caps EFX unavailable)");
				AL.createCapabilities(deviceCaps);
			}
			AL10.alGetError();
			ALC10.alcGetError(device);
			ALCapabilities caps = AL.getCapabilities();
			int hrtfStatus = -1;
			if (deviceCaps.ALC_SOFT_HRTF) {
				IntBuffer status = BufferUtils.createIntBuffer(1);
				ALC10.alcGetIntegerv(device, SOFTHRTF.ALC_HRTF_STATUS_SOFT, status);
				hrtfStatus = status.get(0);
				report.line("loopback HRTF status: " + hrtfStatus(hrtfStatus));
			}
			if (caps.ALC_EXT_EFX) {
				IntBuffer sends = BufferUtils.createIntBuffer(1);
				ALC10.alcGetIntegerv(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS, sends);
				report.line("loopback maxAuxSends=" + sends.get(0));
				report.key("loopbackMaxAuxSends=" + sends.get(0));
			}
			checkAl("loopback capabilities");
			return new Loopback(device, context, channels, caps.ALC_EXT_EFX,
				hrtfStatus, threadLocal, previous);
		}

		@Override
		public void close() {
			if (threadLocal) {
				EXTThreadLocalContext.alcSetThreadContext(NULL);
			} else {
				ALC10.alcMakeContextCurrent(NULL);
			}
			if (context != NULL) {
				ALC10.alcDestroyContext(context);
			}
			if (device != NULL) {
				ALC10.alcCloseDevice(device);
			}
			AL.setCurrentThread(previousCaps);
		}
	}

	private static final class Effects implements AutoCloseable {
		final int effect;
		final int slot;
		final int muteFilter;

		private Effects(int effect, int slot, int muteFilter) {
			this.effect = effect;
			this.slot = slot;
			this.muteFilter = muteFilter;
		}

		static Effects create(long device, Report report) {
			int effect = EXTEfx.alGenEffects();
			int slot = EXTEfx.alGenAuxiliaryEffectSlots();
			int filter = EXTEfx.alGenFilters();
			try {
				EXTEfx.alEffecti(effect, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_EAXREVERB);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_TIME, 1.2f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_HFRATIO, 0.7f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DECAY_LFRATIO, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAIN, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINHF, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_GAINLF, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DENSITY, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_DIFFUSION, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN, 0.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_LATE_REVERB_GAIN, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ECHO_DEPTH, 0.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_MODULATION_TIME, 0.25f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_MODULATION_DEPTH, 0.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_AIR_ABSORPTION_GAINHF, 1.0f);
				EXTEfx.alEffectf(effect, EXTEfx.AL_EAXREVERB_ROOM_ROLLOFF_FACTOR, 0.0f);
				EXTEfx.alAuxiliaryEffectSloti(slot, EXTEfx.AL_EFFECTSLOT_EFFECT, effect);
				EXTEfx.alAuxiliaryEffectSlotf(slot, EXTEfx.AL_EFFECTSLOT_GAIN, 1.0f);
				EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
				EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 0.0f);
				EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, 0.0f);
				checkAl("create EAXReverb");
				alcCheck(device, "create EAXReverb ALC");
				report.line("EAXReverb created; reflectionGain="
					+ EXTEfx.alGetEffectf(effect, EXTEfx.AL_EAXREVERB_REFLECTIONS_GAIN)
					+ " echoDepth="
					+ EXTEfx.alGetEffectf(effect, EXTEfx.AL_EAXREVERB_ECHO_DEPTH));
				return new Effects(effect, slot, filter);
			} catch (Throwable t) {
				if (filter != 0) {
					EXTEfx.alDeleteFilters(filter);
				}
				if (slot != 0) {
					EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
				}
				if (effect != 0) {
					EXTEfx.alDeleteEffects(effect);
				}
				report.line("EAXReverb create failed: " + t);
				return null;
			}
		}

		@Override
		public void close() {
			if (muteFilter != 0) {
				EXTEfx.alDeleteFilters(muteFilter);
			}
			if (slot != 0) {
				EXTEfx.alDeleteAuxiliaryEffectSlots(slot);
			}
			if (effect != 0) {
				EXTEfx.alDeleteEffects(effect);
			}
		}
	}

	private static final class Source implements AutoCloseable {
		final int source;
		final int buffer;

		private Source(int source, int buffer) {
			this.source = source;
			this.buffer = buffer;
		}

		static Source create(Loopback loopback, float x, float y, float z, Report report) {
			int source = AL10.alGenSources();
			int buffer = AL10.alGenBuffers();
			ShortBuffer data = BufferUtils.createShortBuffer(SAMPLE_RATE);
			for (int i = 0; i < SAMPLE_RATE; i++) {
				double sample = Math.sin(2.0 * Math.PI * 440.0 * i / SAMPLE_RATE);
				data.put(i, (short) (sample * 8_000.0));
			}
			AL10.alBufferData(buffer, AL_FORMAT_MONO16, data, SAMPLE_RATE);
			AL10.alSourcei(source, AL_BUFFER, buffer);
			AL10.alSourcei(source, AL_LOOPING, AL_TRUE);
			AL10.alSourcef(source, AL_GAIN, 1.0f);
			AL10.alSourcef(source, AL_ROLLOFF_FACTOR, 0.0f);
			AL10.alSourcef(source, AL_MAX_DISTANCE, 64.0f);
			AL10.alSourcei(source, AL_SOURCE_RELATIVE, AL10.AL_FALSE);
			AL10.alSource3f(source, AL_POSITION, x, y, z);
			AL10.alListener3f(AL_POSITION, 0.0f, 0.0f, 0.0f);
			AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f});
			AL10.alSourcePlay(source);
			checkAl("source setup");
			report.line("source state=" + AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE));
			return new Source(source, buffer);
		}

		@Override
		public void close() {
			if (source != 0) {
				AL10.alSourceStop(source);
				AL10.alDeleteSources(source);
			}
			if (buffer != 0) {
				AL10.alDeleteBuffers(buffer);
			}
		}
	}
}



