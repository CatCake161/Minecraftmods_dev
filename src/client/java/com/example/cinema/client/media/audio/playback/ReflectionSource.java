package com.example.cinema.client.media.audio.playback;

import com.example.cinema.client.media.audio.dsp.MinimumPhaseFir;

/**
 * One early-reflection tap submitted to the audio engine.
 *
 * <p>{@code x/y/z} is the image-source position in listener-relative space
 * (x right, y up, z forward is negative). It is deliberately not the
 * reflection point: OpenAL uses the image source only for panning/HRTF.
 * {@code gain} is the physical source gain before the receiver master volume:
 * speaker trim, the common FIR's on-axis/directivity level at 1 kHz,
 * {@code sqrt(1 - alpha_mid)}, the Java {@code 1/max(L,1)} distance rule and
 * the combined broadband occlusion gain of both legs. {@code filter} carries
 * the remaining frequency shape: on-axis/directivity, air absorption, material
 * reflection and occlusion low-pass; first-order reflections remain full-band.
 *
 * <p>The {@code key} is {@code channel + ":" + planeKey}; it keeps a tap on the
 * same OpenAL source while the listener moves, so only delay/gain/position are
 * updated instead of restarting the source.
 */
public record ReflectionSource(String key, int channel,
							   float x, float y, float z,
							   float gain, double delaySeconds,
							   MinimumPhaseFir filter) {
	public ReflectionSource {
		key = key == null ? "" : key;
		channel = Math.max(0, Math.min(CinemaAudioEngine.CHANNELS - 1, channel));
		x = Float.isFinite(x) ? x : 0.0f;
		y = Float.isFinite(y) ? y : 0.0f;
		z = Float.isFinite(z) ? z : 0.0f;
		gain = Float.isFinite(gain) ? Math.max(0.0f, gain) : 0.0f;
		delaySeconds = Double.isFinite(delaySeconds)
			? Math.max(0.0, Math.min(
				CinemaAudioEngine.maxReflectionDelaySeconds(), delaySeconds))
			: 0.0;
		filter = filter == null ? MinimumPhaseFir.FLAT : filter;
	}
}
