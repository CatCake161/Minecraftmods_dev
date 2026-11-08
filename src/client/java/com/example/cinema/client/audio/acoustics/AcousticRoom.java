package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A1 room snapshot. The six-plane box is the detected bounding volume; the
 * optional {@link RoomFill} provides the connected air volume, effective
 * surface area, and openings measured inside that box. The three
 * {@link AxisAbsorption} values feed the Fitzroy RT60 formula.
 */
public record AcousticRoom(boolean valid, String fallbackReason,
						   Vec3 min, Vec3 max, double volume, double surfaceArea,
						   double alphaLow, double alphaMid, double alphaHigh,
						   double rt60Low, double rt60Mid, double rt60High,
						   AxisAbsorption xAxis, AxisAbsorption yAxis,
						   AxisAbsorption zAxis,
						   List<AcousticSurface> surfaces, RoomFill fill) {
	private static final double FITZROY = 0.161;
	private static final double SCHROEDER_CONSTANT = 2_000.0;
	public static final double MIN_SCHROEDER_HZ = 50.0;
	public static final double MAX_SCHROEDER_HZ = 2_000.0;
	public static final double DEFAULT_SCHROEDER_HZ = 1_000.0;

	public AcousticRoom {
		fallbackReason = fallbackReason == null ? "" : fallbackReason;
		xAxis = xAxis == null ? AxisAbsorption.EMPTY : xAxis;
		yAxis = yAxis == null ? AxisAbsorption.EMPTY : yAxis;
		zAxis = zAxis == null ? AxisAbsorption.EMPTY : zAxis;
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
	 * volume and per-axis boundary absorption for Fitzroy RT60. An incomplete
	 * fill keeps the geometric box values.
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
		double[] axisArea = {
			2.0 * dy * dz,
			2.0 * dx * dz,
			2.0 * dx * dy
		};
		double[] sampledArea = new double[3];
		double[] axisLow = new double[3];
		double[] axisMid = new double[3];
		double[] axisHigh = new double[3];
		for (AcousticSurface surface : surfaces) {
			double surfaceArea = surface.area();
			if (surfaceArea <= 0.0) {
				continue;
			}
			int axis = surface.axis().ordinal();
			weightedArea += surfaceArea;
			sampledArea[axis] += surfaceArea;
			alphaLow += surfaceArea * surface.material().low();
			alphaMid += surfaceArea * surface.material().mid();
			alphaHigh += surfaceArea * surface.material().high();
			axisLow[axis] += surfaceArea * surface.material().low();
			axisMid[axis] += surfaceArea * surface.material().mid();
			axisHigh[axis] += surfaceArea * surface.material().high();
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
		AxisAbsorption[] axes = new AxisAbsorption[3];
		for (int i = 0; i < axes.length; i++) {
			double sampled = sampledArea[i];
			double low = sampled > 0.0 ? axisLow[i] / sampled
				: AcousticMaterials.DEFAULT.low();
			double mid = sampled > 0.0 ? axisMid[i] / sampled
				: AcousticMaterials.DEFAULT.mid();
			double high = sampled > 0.0 ? axisHigh[i] / sampled
				: AcousticMaterials.DEFAULT.high();
			axes[i] = new AxisAbsorption(axisArea[i], low, mid, high);
		}

		double effectiveVolume = volume;
		AxisAbsorption effectiveX = axes[0];
		AxisAbsorption effectiveY = axes[1];
		AxisAbsorption effectiveZ = axes[2];
		if (fill != null && fill.complete() && fill.surfaceArea() > 0.0) {
			effectiveVolume = fill.volume();
			alphaLow = fill.alphaLow();
			alphaMid = fill.alphaMid();
			alphaHigh = fill.alphaHigh();
			effectiveX = fill.xAxis().area() > 0.0 ? fill.xAxis() : axes[0];
			effectiveY = fill.yAxis().area() > 0.0 ? fill.yAxis() : axes[1];
			effectiveZ = fill.zAxis().area() > 0.0 ? fill.zAxis() : axes[2];
		}
		return new AcousticRoom(valid, fallbackReason, min, max, volume, area,
			alphaLow, alphaMid, alphaHigh,
			rt60(effectiveVolume, effectiveX, effectiveY, effectiveZ, Band.LOW),
			rt60(effectiveVolume, effectiveX, effectiveY, effectiveZ, Band.MID),
			rt60(effectiveVolume, effectiveX, effectiveY, effectiveZ, Band.HIGH),
			effectiveX, effectiveY, effectiveZ,
			surfaces, fill);
	}

	/** Connected air volume used for RT60; box volume when fill is incomplete. */
	public double effectiveVolume() {
		return fill != null && fill.complete() ? fill.volume() : volume;
	}

	/** Connected boundary area; box area when fill is incomplete. */
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
	 * Stable geometry/material key for mirror discovery and reflection taps.
	 * The snapshot version advances at the room-detection rate even when the
	 * room did not change; using it as a discovery cache key would re-run plane
	 * discovery on every update and make tap keys churn.
	 */
	public long reflectionKey() {
		long hash = 17L;
		hash = 31L * hash + Double.doubleToLongBits(min.x);
		hash = 31L * hash + Double.doubleToLongBits(min.y);
		hash = 31L * hash + Double.doubleToLongBits(min.z);
		hash = 31L * hash + Double.doubleToLongBits(max.x);
		hash = 31L * hash + Double.doubleToLongBits(max.y);
		hash = 31L * hash + Double.doubleToLongBits(max.z);
		hash = 31L * hash + surfaces.size();
		long surfaceHash = 0L;
		for (AcousticSurface surface : surfaces) {
			surfaceHash += surface.hashCode();
		}
		hash = 31L * hash + surfaceHash;
		if (fill != null) {
			List<AcousticSurface> patches = fill.reflectionSurfaces();
			hash = 31L * hash + patches.size();
			long patchHash = 0L;
			for (AcousticSurface patch : patches) {
				patchHash += patch.hashCode();
			}
			hash = 31L * hash + patchHash;
		}
		return hash;
	}

	/**
	 * Schroeder frequency from the mid-band Fitzroy RT60 and connected air
	 * volume: {@code f_s = 2000 * sqrt(RT60_mid / V_eff)}. Returns the 1000 Hz
	 * fallback when either value is invalid, and clamps to [50, 2000] Hz.
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

	/**
	 * Fitzroy RT60: the area-weighted harmonic mean of the three axial Eyring
	 * decay times,
	 * {@code 0.161 V / S^2 * sum(S_i / -ln(1 - alpha_i))}. It reduces to
	 * Eyring when all three axes have the same absorption. A perfectly
	 * reflective axis pair ({@code alpha_i = 0}) gives an infinite decay time,
	 * matching the model's trapped axial field.
	 */
	private static double rt60(double volume, AxisAbsorption x, AxisAbsorption y,
							   AxisAbsorption z, Band band) {
		double totalArea = x.area() + y.area() + z.area();
		if (volume <= 0.0 || totalArea <= 0.0) {
			// A3 will clamp the value when mapping it to EAXReverb.
			return Double.POSITIVE_INFINITY;
		}
		double sum = fitzroyTerm(x.area(), band.alpha(x))
			+ fitzroyTerm(y.area(), band.alpha(y))
			+ fitzroyTerm(z.area(), band.alpha(z));
		if (!Double.isFinite(sum)) {
			return Double.POSITIVE_INFINITY;
		}
		return FITZROY * volume / (totalArea * totalArea) * sum;
	}

	private static double fitzroyTerm(double area, double alpha) {
		if (area <= 0.0) {
			return 0.0;
		}
		if (alpha <= 0.0) {
			return Double.POSITIVE_INFINITY;
		}
		if (alpha >= 1.0) {
			return 0.0;
		}
		return area / -Math.log(1.0 - alpha);
	}

	private enum Band {
		LOW,
		MID,
		HIGH;

		private double alpha(AxisAbsorption axis) {
			return switch (this) {
				case LOW -> axis.alphaLow();
				case MID -> axis.alphaMid();
				case HIGH -> axis.alphaHigh();
			};
		}
	}
}
