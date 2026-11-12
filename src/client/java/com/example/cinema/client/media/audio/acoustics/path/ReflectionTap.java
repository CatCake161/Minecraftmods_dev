package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

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
public record ReflectionTap(int channel, String sourceKey, String planeKey,
							Point3 imageSource, Point3 reflectionPoint,
							double pathLength, double delaySeconds,
							AcousticMaterial material, double gain,
							OcclusionResult occlusion) {
	public ReflectionTap {
		sourceKey = sourceKey == null ? "" : sourceKey;
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
	}

	/** Backward-compatible constructor for a single anchor speaker. */
	public ReflectionTap(int channel, String planeKey, Point3 imageSource,
						 Point3 reflectionPoint, double pathLength,
						 double delaySeconds, AcousticMaterial material,
						 double gain, OcclusionResult occlusion) {
		this(channel, "", planeKey, imageSource, reflectionPoint, pathLength,
			delaySeconds, material, gain, occlusion);
	}

	/** Convenience constructor for an unoccluded tap. */
	public ReflectionTap(int channel, String planeKey, Point3 imageSource,
						 Point3 reflectionPoint, double pathLength,
						 double delaySeconds, AcousticMaterial material,
						 double gain) {
		this(channel, "", planeKey, imageSource, reflectionPoint, pathLength,
			delaySeconds, material, gain, OcclusionResult.NONE);
	}

	/**
	 * Stable identity for source matching. Multiple physical speakers on the
	 * same 5.1 channel must not reuse each other's tap keys.
	 */
	public String key() {
		return (sourceKey.isEmpty() ? "" : sourceKey + ":")
			+ channel + ":" + planeKey;
	}

	public double delayMilliseconds() {
		return delaySeconds * 1000.0;
	}

	public double reflectionCoefficient() {
		return Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
	}
}
