package com.example.cinema.client.audio.acoustics;

import com.example.cinema.client.audio.filter.Biquad;
import com.example.cinema.client.audio.filter.BiquadChain;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Biquad low-pass cascade for reflection-path occlusion.
 *
 * <p>Each reflection leg is scanned with the same {@link OcclusionScanner} as
 * the direct path. The two legs are combined by summing broadband attenuation,
 * taking the lower cutoff and the higher order. The broadband part is applied
 * to {@link ReflectionTap#gain()}; this chain carries only the low-pass shape.
 * The cascade matches the direct path's Butterworth magnitude
 * {@code -10 log10(1 + (f/fc)^(2n))} while keeping the per-tap cost bounded.
 */
public final class OcclusionEq {
	public static final double SAMPLE_RATE = 48_000.0;
	public static final double BUTTERWORTH_Q = Biquad.DEFAULT_Q;
	private static final double CUTOFF_BUCKET_HZ = 10.0;
	private static final double Q3 = 1.0;
	private static final double Q4_LOW = 0.541196100146197;
	private static final double Q4_HIGH = 1.3065629648763766;
	private static final ConcurrentHashMap<Key, BiquadChain> CACHE =
		new ConcurrentHashMap<>();

	private OcclusionEq() {
	}

	public static BiquadChain chain(OcclusionResult occlusion) {
		if (occlusion == null || !occlusion.isBlocked()) {
			return BiquadChain.FLAT;
		}
		Key key = Key.of(occlusion);
		return CACHE.computeIfAbsent(key, OcclusionEq::design);
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static int cacheSize() {
		return CACHE.size();
	}

	private static BiquadChain design(Key key) {
		double cutoff = key.cutoffHz();
		return switch (key.order()) {
			case 1 -> BiquadChain.of(
				Biquad.firstOrderLowPass(cutoff, SAMPLE_RATE));
			case 2 -> BiquadChain.of(
				Biquad.lowPass(cutoff, BUTTERWORTH_Q, SAMPLE_RATE));
			case 3 -> BiquadChain.of(
				Biquad.firstOrderLowPass(cutoff, SAMPLE_RATE),
				Biquad.lowPass(cutoff, Q3, SAMPLE_RATE));
			case 4 -> BiquadChain.of(
				Biquad.lowPass(cutoff, Q4_LOW, SAMPLE_RATE),
				Biquad.lowPass(cutoff, Q4_HIGH, SAMPLE_RATE));
			default -> BiquadChain.FLAT;
		};
	}

	private record Key(double cutoffHz, int order) {
		static Key of(OcclusionResult occlusion) {
			double cutoff = Math.round(occlusion.cutoffHz() / CUTOFF_BUCKET_HZ)
				* CUTOFF_BUCKET_HZ;
			return new Key(cutoff, occlusion.order());
		}
	}
}
