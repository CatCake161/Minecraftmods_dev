package com.example.cinema.client.audio.acoustics;

import java.util.List;

/**
 * One validated higher-order specular path.
 *
 * <p>This is the geometry-spike counterpart of {@link ReflectionTap}. It keeps
 * the full plane sequence, all reflection points and the material hit at each
 * bounce. The path is not connected to the audio engine yet; it exists to
 * measure how many second/third-order paths survive bounds, wall and
 * visibility checks before deciding whether a per-tap source pool or a late
 * reflection bus is the right renderer.
 */
public record ReflectionPathTap(int channel, String pathKey, int order,
								Point3 imageSource,
								List<Point3> reflectionPoints,
								List<AcousticMaterial> materials,
								double pathLength, double delaySeconds,
								double gain, OcclusionResult occlusion) {
	public ReflectionPathTap {
		pathKey = pathKey == null ? "" : pathKey;
		reflectionPoints = reflectionPoints == null
			? List.of() : List.copyOf(reflectionPoints);
		materials = materials == null ? List.of() : List.copyOf(materials);
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
		order = Math.max(0, order);
		gain = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
	}

	public double delayMilliseconds() {
		return delaySeconds * 1_000.0;
	}

	/** Product of the per-bounce pressure reflection coefficients. */
	public double reflectionCoefficient() {
		double product = 1.0;
		for (AcousticMaterial material : materials) {
			product *= Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
		}
		return product;
	}
}
