package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

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
 *
 * <p>{@code gain} is the specular pressure product including the scattering
 * attenuation when enabled; {@code scatteredGain} is the lumped diffuse
 * pressure product in the same distance/occlusion domain. With scattering
 * disabled, {@code gain} keeps the pre-S1 behaviour and
 * {@code scatteredGain} is zero.
 */
public record ReflectionPathTap(int channel, String sourceKey, String pathKey,
								int order, Point3 imageSource,
								List<Point3> reflectionPoints,
								List<AcousticMaterial> materials,
								double pathLength, double delaySeconds,
								double gain, double scatteredGain,
								OcclusionResult occlusion, Point3 lastNormal) {
	public ReflectionPathTap {
		sourceKey = sourceKey == null ? "" : sourceKey;
		pathKey = pathKey == null ? "" : pathKey;
		reflectionPoints = reflectionPoints == null
			? List.of() : List.copyOf(reflectionPoints);
		materials = materials == null ? List.of() : List.copyOf(materials);
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
		lastNormal = lastNormal == null ? Point3.ZERO : lastNormal;
		order = Math.max(0, order);
		gain = Double.isFinite(gain) ? Math.max(0.0, gain) : 0.0;
		scatteredGain = Double.isFinite(scatteredGain)
			? Math.max(0.0, scatteredGain) : 0.0;
	}

	/** Backward-compatible constructor without a scattered component. */
	public ReflectionPathTap(int channel, String sourceKey, String pathKey,
							 int order, Point3 imageSource,
							 List<Point3> reflectionPoints,
							 List<AcousticMaterial> materials,
							 double pathLength, double delaySeconds,
							 double gain, OcclusionResult occlusion) {
		this(channel, sourceKey, pathKey, order, imageSource, reflectionPoints,
			materials, pathLength, delaySeconds, gain, 0.0, occlusion,
			Point3.ZERO);
	}

	/** Backward-compatible constructor for a single anchor speaker. */
	public ReflectionPathTap(int channel, String pathKey, int order,
							 Point3 imageSource,
							 List<Point3> reflectionPoints,
							 List<AcousticMaterial> materials,
							 double pathLength, double delaySeconds,
							 double gain, OcclusionResult occlusion) {
		this(channel, "", pathKey, order, imageSource, reflectionPoints,
			materials, pathLength, delaySeconds, gain, 0.0, occlusion,
			Point3.ZERO);
	}

	public String sourcePathKey() {
		return (sourceKey.isEmpty() ? "" : sourceKey + ":") + pathKey;
	}

	public double delayMilliseconds() {
		return delaySeconds * 1_000.0;
	}

	/** Absorption-only pressure coefficient, without the scattering split. */
	public double reflectionCoefficient() {
		double product = 1.0;
		for (AcousticMaterial material : materials) {
			product *= Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
		}
		return product;
	}

	/** Product of the per-bounce specular pressure coefficients. */
	public double specularReflectionCoefficient() {
		return AcousticScattering.specularPressure(materials);
	}

	/** Lumped diffuse pressure coefficient over all bounces. */
	public double scatteredReflectionCoefficient() {
		return AcousticScattering.scatteredPressure(materials);
	}

	/** Frequency-dependent specular material over all bounces. */
	public AcousticMaterial specularMaterial() {
		return AcousticScattering.specularMaterial("specular-path", materials);
	}

	/** Frequency-dependent scattered material over all bounces. */
	public AcousticMaterial scatteredMaterial() {
		return AcousticScattering.scatteredMaterial("scattered-path", materials);
	}
}
