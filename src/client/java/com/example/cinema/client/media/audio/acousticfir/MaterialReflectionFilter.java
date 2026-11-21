package com.example.cinema.client.media.audio.acousticfir;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.dsp.Biquad;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime three-section material PEQ used by the lazy late bus.
 *
 * <p>The FIR path merges the same low-shelf/peak/high-shelf target into its
 * magnitude response. This class exposes the sections for per-tap biquad
 * processing when no per-tap FIR exists. The mid section is the 0 dB
 * reference, so the tap's scalar gain still carries the mid-band coefficient.
 */
public record MaterialReflectionFilter(Biquad low, Biquad peak, Biquad high) {
	public static final double DEFAULT_SAMPLE_RATE = 48_000.0;
	private static final ConcurrentHashMap<Key, MaterialReflectionFilter> CACHE =
		new ConcurrentHashMap<>();
	public static final MaterialReflectionFilter FLAT =
		new MaterialReflectionFilter(null, null, null);

	private record Key(AcousticMaterial material, double sampleRate) {
	}

	public static MaterialReflectionFilter of(AcousticMaterial material,
											  double sampleRate) {
		if (material == null) {
			return FLAT;
		}
		double rate = Double.isFinite(sampleRate) && sampleRate >= 8_000.0
			? sampleRate : DEFAULT_SAMPLE_RATE;
		return CACHE.computeIfAbsent(new Key(material, rate),
			key -> design(key.material(), rate));
	}

	public static MaterialReflectionFilter of(AcousticMaterial material) {
		return of(material, DEFAULT_SAMPLE_RATE);
	}

	public boolean isFlat() {
		return low == null && peak == null && high == null;
	}

	/** Returns a fresh array for callers that need indexed access. */
	public Biquad[] sections() {
		return new Biquad[]{low, peak, high};
	}

	public double magnitudeDb(double frequencyHz, double sampleRate) {
		double db = 0.0;
		if (low != null) {
			db += low.magnitudeDb(frequencyHz, sampleRate);
		}
		if (peak != null) {
			db += peak.magnitudeDb(frequencyHz, sampleRate);
		}
		if (high != null) {
			db += high.magnitudeDb(frequencyHz, sampleRate);
		}
		return db;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	private static MaterialReflectionFilter design(AcousticMaterial material,
												   double sampleRate) {
		double[] gains = MaterialReflectionCurve.sectionGains(material);
		if (gains == null) {
			return FLAT;
		}
		return new MaterialReflectionFilter(
			Biquad.lowShelf(MaterialReflectionCurve.LOW_SHELF_HZ,
				MaterialReflectionCurve.SHELF_Q, gains[0], sampleRate),
			Biquad.peaking(MaterialReflectionCurve.MID_BAND_HZ,
				MaterialReflectionCurve.PEAK_Q, gains[1], sampleRate),
			Biquad.highShelf(MaterialReflectionCurve.HIGH_SHELF_HZ,
				MaterialReflectionCurve.SHELF_Q, gains[2], sampleRate));
	}
}
