package com.example.cinema.client.audio.acoustics;

/**
 * One validated first-order reflection tap.
 *
 * <p>{@code imageSource} is the virtual source position used for OpenAL
 * panning; {@code reflectionPoint} is only the physical point on the wall.
 * {@code gain} is the base mid-band gain {@code sqrt(1 - alpha_mid) /
 * max(pathLength, 1)} times the combined broadband occlusion gain of both
 * legs; directivity, air absorption, the material band shape and the occlusion
 * low-pass shape are applied by the downstream reflection source stage.
 */
public record ReflectionTap(int channel, String planeKey, Point3 imageSource,
							Point3 reflectionPoint, double pathLength,
							double delaySeconds, AcousticMaterial material,
							double gain, OcclusionResult occlusion) {
	public ReflectionTap {
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
	}

	/** Convenience constructor for an unoccluded tap. */
	public ReflectionTap(int channel, String planeKey, Point3 imageSource,
						 Point3 reflectionPoint, double pathLength,
						 double delaySeconds, AcousticMaterial material,
						 double gain) {
		this(channel, planeKey, imageSource, reflectionPoint, pathLength,
			delaySeconds, material, gain, OcclusionResult.NONE);
	}

	public double delayMilliseconds() {
		return delaySeconds * 1000.0;
	}

	public double reflectionCoefficient() {
		return Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
	}
}
