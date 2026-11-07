package com.example.cinema.client.media;

import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.filter.BiquadChain;

/**
 * One early-reflection tap submitted to the audio engine.
 *
 * <p>{@code x/y/z} is the image-source position in listener-relative space
 * (x right, y up, z forward is negative). It is deliberately not the
 * reflection point: OpenAL uses the image source only for panning/HRTF.
 * {@code gain} is the physical source gain before the receiver master volume:
 * speaker trim, the reflection FIR's broadband trim at the room Schroeder
 * frequency, {@code sqrt(1 - alpha_mid)}, the Java {@code 1/max(L,1)}
 * distance rule and the combined broadband occlusion gain of both legs.
 * {@code filter} carries the reflection-angle directivity shape plus air
 * absorption; {@code eq} carries the material band shape and the occlusion
 * low-pass shape.
 *
 * <p>The {@code key} is {@code channel + ":" + planeKey}; it keeps a tap on the
 * same OpenAL source while the listener moves, so only delay/gain/position are
 * updated instead of restarting the source.
 */
public record ReflectionSource(String key, int channel,
							   float x, float y, float z,
							   float gain, double delaySeconds,
							   MinimumPhaseFir filter, BiquadChain eq) {
	public ReflectionSource {
		key = key == null ? "" : key;
		channel = Math.max(0, Math.min(CinemaAudioEngine.CHANNELS - 1, channel));
		x = Float.isFinite(x) ? x : 0.0f;
		y = Float.isFinite(y) ? y : 0.0f;
		z = Float.isFinite(z) ? z : 0.0f;
		gain = Float.isFinite(gain) ? Math.max(0.0f, gain) : 0.0f;
		delaySeconds = Double.isFinite(delaySeconds)
			? Math.max(0.0, Math.min(0.080, delaySeconds)) : 0.0;
		filter = filter == null ? MinimumPhaseFir.FLAT : filter;
		eq = eq == null ? BiquadChain.FLAT : eq;
	}

	/** Convenience constructor for a tap without material/occlusion EQ. */
	public ReflectionSource(String key, int channel, float x, float y, float z,
							float gain, double delaySeconds,
							MinimumPhaseFir filter) {
		this(key, channel, x, y, z, gain, delaySeconds, filter, BiquadChain.FLAT);
	}
}
