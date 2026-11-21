package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;

import java.util.List;

/**
 * One validated first-order reflection tap.
 *
 * <p>{@code imageSource} is the virtual source position used for OpenAL
 * panning; {@code reflectionPoint} is only the physical point on the wall.
 * When scattering is enabled, {@code gain} is the base mid-band specular gain
 * {@code sqrt((1 - alpha_mid) * (1 - scattering)) / max(pathLength, 1)}
 * times the combined broadband occlusion gain of both legs;
 * {@code scatteredGain} uses {@code sqrt((1 - alpha_mid) * scattering)} in the
 * same distance/occlusion domain. With scattering disabled, {@code gain} is
 * the absorption-only coefficient and {@code scatteredGain} is zero. Directivity, air absorption, the material
 * band shape and the occlusion low-pass shape are applied by the downstream
 * reflection source stage.
 *
 * <p>{@code scatteredGain} is consumed by the diffuse late-bus send; the
 * direction of the specular tap is not used for scattered energy.
 */
public record ReflectionTap(int channel, String sourceKey, String planeKey,
							Point3 imageSource, Point3 reflectionPoint,
							double pathLength, double delaySeconds,
							AcousticMaterial material, double gain,
							double scatteredGain, OcclusionResult occlusion,
							Point3 normal) {
	public ReflectionTap {
		sourceKey = sourceKey == null ? "" : sourceKey;
		scatteredGain = Double.isFinite(scatteredGain)
			? Math.max(0.0, scatteredGain) : 0.0;
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
		normal = normal == null ? Point3.ZERO : normal;
	}

	/** Backward-compatible constructor without a surface normal. */
	public ReflectionTap(int channel, String sourceKey, String planeKey,
						 Point3 imageSource, Point3 reflectionPoint,
						 double pathLength, double delaySeconds,
						 AcousticMaterial material, double gain,
						 double scatteredGain, OcclusionResult occlusion) {
		this(channel, sourceKey, planeKey, imageSource, reflectionPoint,
			pathLength, delaySeconds, material, gain, scatteredGain, occlusion,
			Point3.ZERO);
	}

	/** Backward-compatible constructor without a scattered component. */
	public ReflectionTap(int channel, String sourceKey, String planeKey,
						 Point3 imageSource, Point3 reflectionPoint,
						 double pathLength, double delaySeconds,
						 AcousticMaterial material, double gain,
						 OcclusionResult occlusion) {
		this(channel, sourceKey, planeKey, imageSource, reflectionPoint,
			pathLength, delaySeconds, material, gain, 0.0, occlusion,
			Point3.ZERO);
	}

	/** Backward-compatible constructor for a single anchor speaker. */
	public ReflectionTap(int channel, String planeKey, Point3 imageSource,
						 Point3 reflectionPoint, double pathLength,
						 double delaySeconds, AcousticMaterial material,
						 double gain, OcclusionResult occlusion) {
		this(channel, "", planeKey, imageSource, reflectionPoint, pathLength,
			delaySeconds, material, gain, 0.0, occlusion, Point3.ZERO);
	}

	/** Convenience constructor for an unoccluded tap. */
	public ReflectionTap(int channel, String planeKey, Point3 imageSource,
						 Point3 reflectionPoint, double pathLength,
						 double delaySeconds, AcousticMaterial material,
						 double gain) {
		this(channel, "", planeKey, imageSource, reflectionPoint, pathLength,
			delaySeconds, material, gain, 0.0, OcclusionResult.NONE,
			Point3.ZERO);
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

	/** Absorption-only pressure coefficient, without the scattering split. */
	public double reflectionCoefficient() {
		return Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
	}

	/** Specular pressure coefficient after the scattering split. */
	public double specularReflectionCoefficient() {
		return AcousticScattering.specularPressure(material.mid(),
			material.scattering());
	}

	/** Scattered pressure coefficient after the scattering split. */
	public double scatteredReflectionCoefficient() {
		return AcousticScattering.scatteredPressure(material.mid(),
			material.scattering());
	}

	/** Frequency-dependent specular material for the reflection FIR/PEQ. */
	public AcousticMaterial specularMaterial() {
		return AcousticScattering.specularMaterial("specular-" + material.name(),
			List.of(material));
	}

	/** Frequency-dependent scattered material for the diffuse send. */
	public AcousticMaterial scatteredMaterial() {
		return AcousticScattering.scatteredMaterial("scattered-" + material.name(),
			List.of(material));
	}
}
