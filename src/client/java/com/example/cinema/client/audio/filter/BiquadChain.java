package com.example.cinema.client.audio.filter;

import java.util.Arrays;

/**
 * Immutable cascade of biquad sections.
 *
 * <p>The returned section array is shared with the audio processor and must not
 * be modified. A flat chain contains no sections, so processing it is a plain
 * copy.
 */
public final class BiquadChain {
	public static final BiquadChain FLAT = new BiquadChain(new Biquad[0]);

	private final Biquad[] sections;

	private BiquadChain(Biquad[] sections) {
		this.sections = sections;
	}

	public static BiquadChain of(Biquad... sections) {
		if (sections == null || sections.length == 0) {
			return FLAT;
		}
		Biquad[] filtered = new Biquad[sections.length];
		int count = 0;
		for (Biquad section : sections) {
			if (section != null && !section.isFlat()) {
				filtered[count++] = section;
			}
		}
		if (count == 0) {
			return FLAT;
		}
		return new BiquadChain(count == filtered.length ? filtered
			: Arrays.copyOf(filtered, count));
	}

	public static BiquadChain concat(BiquadChain first, BiquadChain second) {
		BiquadChain a = first == null ? FLAT : first;
		BiquadChain b = second == null ? FLAT : second;
		if (a.isFlat()) {
			return b;
		}
		if (b.isFlat()) {
			return a;
		}
		Biquad[] combined = Arrays.copyOf(a.sections,
			a.sections.length + b.sections.length);
		System.arraycopy(b.sections, 0, combined, a.sections.length,
			b.sections.length);
		return new BiquadChain(combined);
	}

	/** Shared immutable section array; callers must not modify it. */
	public Biquad[] sections() {
		return sections;
	}

	public int sectionCount() {
		return sections.length;
	}

	public boolean isFlat() {
		return sections.length == 0;
	}

	/** Sum of all section magnitudes in dB. */
	public double magnitudeDb(double frequencyHz, double sampleRate) {
		double db = 0.0;
		for (Biquad section : sections) {
			db += section.magnitudeDb(frequencyHz, sampleRate);
		}
		return db;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BiquadChain chain)) {
			return false;
		}
		return Arrays.equals(sections, chain.sections);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(sections);
	}

	@Override
	public String toString() {
		return "BiquadChain[" + sections.length + " sections]";
	}
}
