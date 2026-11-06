package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A1 room snapshot. Only the six-plane box is modeled; a failed box detection
 * produces a fallback box with {@code valid=false}.
 */
public record AcousticRoom(boolean valid, String fallbackReason,
						   Vec3 min, Vec3 max, double volume, double surfaceArea,
						   double alphaLow, double alphaMid, double alphaHigh,
						   double rt60Low, double rt60Mid, double rt60High,
						   List<AcousticSurface> surfaces) {
	private static final double MIN_RT60 = 0.3;
	private static final double MAX_RT60 = 2.5;
	private static final double SABINE = 0.161;

	public AcousticRoom {
		fallbackReason = fallbackReason == null ? "" : fallbackReason;
		surfaces = surfaces == null ? List.of() : List.copyOf(surfaces);
	}

	public double width() {
		return max.x - min.x;
	}

	public double height() {
		return max.y - min.y;
	}

	public double depth() {
		return max.z - min.z;
	}

	public String sizeLabel() {
		return String.format(java.util.Locale.ROOT, "%.2f x %.2f x %.2f m",
			width(), height(), depth());
	}

	/**
	 * Builds a rectangular room from six planes and their sampled materials.
	 */
	public static AcousticRoom box(Vec3 min, Vec3 max, List<AcousticSurface> surfaces,
								   boolean valid, String fallbackReason) {
		double dx = Math.max(0.0, max.x - min.x);
		double dy = Math.max(0.0, max.y - min.y);
		double dz = Math.max(0.0, max.z - min.z);
		double volume = dx * dy * dz;
		double area = 2.0 * (dx * dy + dx * dz + dy * dz);
		double alphaLow = 0.0;
		double alphaMid = 0.0;
		double alphaHigh = 0.0;
		double weightedArea = 0.0;
		for (AcousticSurface surface : surfaces) {
			double surfaceArea = surface.area();
			if (surfaceArea <= 0.0) {
				continue;
			}
			weightedArea += surfaceArea;
			alphaLow += surfaceArea * surface.material().low();
			alphaMid += surfaceArea * surface.material().mid();
			alphaHigh += surfaceArea * surface.material().high();
		}
		if (weightedArea <= 0.0) {
			alphaLow = AcousticMaterials.DEFAULT.low();
			alphaMid = AcousticMaterials.DEFAULT.mid();
			alphaHigh = AcousticMaterials.DEFAULT.high();
		} else {
			alphaLow /= weightedArea;
			alphaMid /= weightedArea;
			alphaHigh /= weightedArea;
		}
		return new AcousticRoom(valid, fallbackReason, min, max, volume, area,
			alphaLow, alphaMid, alphaHigh,
			rt60(volume, area, alphaLow),
			rt60(volume, area, alphaMid),
			rt60(volume, area, alphaHigh),
			surfaces);
	}

	private static double rt60(double volume, double area, double alpha) {
		if (volume <= 0.0 || area <= 0.0 || alpha <= 0.0) {
			return MAX_RT60;
		}
		double value = alpha < 0.2
			? SABINE * volume / (area * alpha)
			: SABINE * volume / (-area * Math.log(1.0 - alpha));
		return Math.max(MIN_RT60, Math.min(MAX_RT60, value));
	}
}
