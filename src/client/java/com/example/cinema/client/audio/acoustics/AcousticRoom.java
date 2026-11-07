package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A1 room snapshot. The six-plane box is the detected bounding volume; the
 * optional {@link RoomFill} provides the connected air volume, effective
 * surface area, and openings measured inside that box.
 */
public record AcousticRoom(boolean valid, String fallbackReason,
						   Vec3 min, Vec3 max, double volume, double surfaceArea,
						   double alphaLow, double alphaMid, double alphaHigh,
						   double rt60Low, double rt60Mid, double rt60High,
						   List<AcousticSurface> surfaces, RoomFill fill) {
	private static final double EYRING = 0.161;
	private static final double SCHROEDER_CONSTANT = 2_000.0;
	public static final double MIN_SCHROEDER_HZ = 50.0;
	public static final double MAX_SCHROEDER_HZ = 2_000.0;
	public static final double DEFAULT_SCHROEDER_HZ = 1_000.0;

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
		return box(min, max, surfaces, valid, fallbackReason, null);
	}

	/**
	 * Builds a room and, when the flood fill is complete, uses its connected
	 * volume, boundary area and measured absorption for Eyring RT60. An
	 * incomplete fill keeps the geometric box values.
	 */
	public static AcousticRoom box(Vec3 min, Vec3 max, List<AcousticSurface> surfaces,
								   boolean valid, String fallbackReason, RoomFill fill) {
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

		double effectiveVolume = volume;
		double effectiveArea = area;
		if (fill != null && fill.complete() && fill.surfaceArea() > 0.0) {
			effectiveVolume = fill.volume();
			effectiveArea = fill.surfaceArea();
			alphaLow = fill.alphaLow();
			alphaMid = fill.alphaMid();
			alphaHigh = fill.alphaHigh();
		}
		return new AcousticRoom(valid, fallbackReason, min, max, volume, area,
			alphaLow, alphaMid, alphaHigh,
			rt60(effectiveVolume, effectiveArea, alphaLow),
			rt60(effectiveVolume, effectiveArea, alphaMid),
			rt60(effectiveVolume, effectiveArea, alphaHigh),
			surfaces, fill);
	}

	/** Connected air volume used for RT60; box volume when fill is incomplete. */
	public double effectiveVolume() {
		return fill != null && fill.complete() ? fill.volume() : volume;
	}

	/** Connected boundary area used for RT60; box area when fill is incomplete. */
	public double effectiveSurfaceArea() {
		return fill != null && fill.complete() ? fill.surfaceArea() : surfaceArea;
	}

	public double openArea() {
		return fill == null ? 0.0 : fill.openArea();
	}

	public boolean fillComplete() {
		return fill != null && fill.complete();
	}

	public double fillCoverage() {
		return fill == null ? 0.0 : fill.coverage(volume);
	}

	/**
	 * Bounded reflection seeds. Flood-fill boundary patches are preferred
	 * because they follow the actual room shape; the six box planes are the
	 * fallback when the fill is unavailable or found no solid faces.
	 */
	public List<AcousticSurface> reflectionSurfaces() {
		if (fill != null && !fill.reflectionSurfaces().isEmpty()) {
			return fill.reflectionSurfaces();
		}
		return surfaces;
	}

	/**
	 * Schroeder frequency from the mid-band RT60 and connected air volume:
	 * {@code f_s = 2000 * sqrt(RT60_mid / V_eff)}. Returns the 1000 Hz fallback
	 * when either value is invalid, and clamps to [50, 2000] Hz.
	 */
	public double schroederHz() {
		double effective = effectiveVolume();
		double rt60 = rt60Mid;
		if (!(effective > 0.0) || !Double.isFinite(rt60) || !(rt60 > 0.0)) {
			return DEFAULT_SCHROEDER_HZ;
		}
		double frequency = SCHROEDER_CONSTANT * Math.sqrt(rt60 / effective);
		if (!Double.isFinite(frequency)) {
			return DEFAULT_SCHROEDER_HZ;
		}
		return Math.max(MIN_SCHROEDER_HZ,
			Math.min(MAX_SCHROEDER_HZ, frequency));
	}

	private static double rt60(double volume, double area, double alpha) {
		if (volume <= 0.0 || area <= 0.0 || alpha <= 0.0) {
			// Zero absorption has no finite Sabine/Eyring decay time. A3 will
			// clamp the value when mapping it to EAXReverb.
			return Double.POSITIVE_INFINITY;
		}
		return EYRING * volume / (-area * Math.log(1.0 - alpha));
	}
}
