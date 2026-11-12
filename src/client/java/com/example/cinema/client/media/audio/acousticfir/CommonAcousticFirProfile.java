package com.example.cinema.client.media.audio.acousticfir;

import com.example.cinema.client.media.audio.acoustics.propagation.AirAbsorption;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.speaker.SpeakerDataProfile;
import com.example.cinema.client.media.audio.speaker.SpeakerDirectivityService;
import com.example.cinema.client.media.audio.speaker.SubwooferBand;

/**
 * One request for the common direct/reflection minimum-phase FIR.
 *
 * <p>The profile carries every frequency-shaping input: speaker on-axis and
 * directivity response, path air absorption, optional material reflection and
 * occlusion low-pass. First-order reflections remain full-band. Broadband
 * levels are not part of the profile; they are applied to the source gain.
 *
 * <p>Values are quantized in the compact constructor so the cache key is
 * bounded: angles in 5 degree steps, path length in 0.5 m steps, temperature
 * in 0.5 C steps, humidity in 2 % steps, pressure in 0.1 kPa steps and
 * occlusion cutoff in 100 Hz (direct) or 10 Hz (reflection) steps. The
 * occlusion field carries only cutoff/order; its broadband attenuation stays
 * in the source gain.
 */
public record CommonAcousticFirProfile(Role role,
									   SpeakerDataProfile speaker,
									   double horizontalDegrees,
									   double verticalDegrees,
									   double pathLengthMeters,
									   boolean airAbsorption,
									   double temperatureC,
									   double humidityPercent,
									   double pressureKpa,
									   AcousticMaterial material,
									   OcclusionResult occlusion,
									   SubwooferBand subwooferBand,
									   int taps,
									   int fftSize) {
	public enum Role {
		DIRECT,
		REFLECTION
	}

	public static final int DIRECT_TAPS = 4_096;
	public static final int DIRECT_FFT_SIZE = 8_192;
	public static final int REFLECTION_TAPS = 4_096;
	public static final int REFLECTION_FFT_SIZE = 8_192;

	public CommonAcousticFirProfile {
		role = role == null ? Role.DIRECT : role;
		horizontalDegrees = SpeakerDirectivityService.quantizeHorizontal(
			Double.isFinite(horizontalDegrees) ? horizontalDegrees : 0.0);
		verticalDegrees = SpeakerDirectivityService.quantizeVertical(
			Double.isFinite(verticalDegrees) ? verticalDegrees : 0.0);
		pathLengthMeters = quantize(pathLengthMeters, 0.5, 0.0, 256.0, 0.0);
		temperatureC = quantize(temperatureC, 0.5, AirAbsorption.MIN_TEMPERATURE_C,
			AirAbsorption.MAX_TEMPERATURE_C, 20.0);
		humidityPercent = quantize(humidityPercent, 2.0, 0.0, 100.0, 50.0);
		pressureKpa = quantize(pressureKpa, 0.1, 50.0, 110.0,
			AirAbsorption.REFERENCE_PRESSURE_KPA);
		occlusion = occlusion == null ? OcclusionResult.NONE : occlusion;
		// Broadband occlusion attenuation belongs to the source gain. The FIR
		// only needs the low-pass shape, so zero the attenuation here; otherwise
		// every small path change would create a new cache entry.
		double cutoffStep = role == Role.DIRECT ? 100.0 : 10.0;
		occlusion = new OcclusionResult(0.0,
			Math.round(occlusion.cutoffHz() / cutoffStep) * cutoffStep,
			occlusion.order());
		if (role == Role.DIRECT) {
			material = null;
		}
		if (speaker == null || !speaker.isSubwoofer()) {
			subwooferBand = null;
		}
		if (taps <= 0) {
			taps = role == Role.DIRECT ? DIRECT_TAPS : REFLECTION_TAPS;
		}
		if (fftSize <= 0) {
			fftSize = role == Role.DIRECT ? DIRECT_FFT_SIZE : REFLECTION_FFT_SIZE;
		}
		if ((fftSize & (fftSize - 1)) != 0 || taps > fftSize) {
			throw new IllegalArgumentException("invalid FIR size");
		}
	}

	public static CommonAcousticFirProfile direct(SpeakerDataProfile speaker,
												  double horizontalDegrees,
												  double verticalDegrees,
												  double distanceMeters,
												  boolean airAbsorption,
												  double temperatureC,
												  double humidityPercent,
												  double pressureKpa,
												  OcclusionResult occlusion) {
		return direct(speaker, horizontalDegrees, verticalDegrees, distanceMeters,
			airAbsorption, temperatureC, humidityPercent, pressureKpa, occlusion,
			null);
	}

	public static CommonAcousticFirProfile direct(SpeakerDataProfile speaker,
												  double horizontalDegrees,
												  double verticalDegrees,
												  double distanceMeters,
												  boolean airAbsorption,
												  double temperatureC,
												  double humidityPercent,
												  double pressureKpa,
												  OcclusionResult occlusion,
												  SubwooferBand subwooferBand) {
		return new CommonAcousticFirProfile(Role.DIRECT, speaker,
			horizontalDegrees, verticalDegrees, distanceMeters, airAbsorption,
			temperatureC, humidityPercent, pressureKpa, null, occlusion,
			subwooferBand, DIRECT_TAPS, DIRECT_FFT_SIZE);
	}

	public static CommonAcousticFirProfile reflection(SpeakerDataProfile speaker,
													 double horizontalDegrees,
													 double verticalDegrees,
													 double pathLengthMeters,
													 boolean airAbsorption,
													 double temperatureC,
													 double humidityPercent,
													 double pressureKpa,
													 AcousticMaterial material,
													 OcclusionResult occlusion) {
		return reflection(speaker, horizontalDegrees, verticalDegrees,
			pathLengthMeters, airAbsorption, temperatureC, humidityPercent,
			pressureKpa, material, occlusion, null);
	}

	public static CommonAcousticFirProfile reflection(SpeakerDataProfile speaker,
													 double horizontalDegrees,
													 double verticalDegrees,
													 double pathLengthMeters,
													 boolean airAbsorption,
													 double temperatureC,
													 double humidityPercent,
													 double pressureKpa,
													 AcousticMaterial material,
													 OcclusionResult occlusion,
													 SubwooferBand subwooferBand) {
		return new CommonAcousticFirProfile(Role.REFLECTION, speaker,
			horizontalDegrees, verticalDegrees, pathLengthMeters, airAbsorption,
			temperatureC, humidityPercent, pressureKpa, material, occlusion,
			subwooferBand, REFLECTION_TAPS, REFLECTION_FFT_SIZE);
	}

	public boolean isReflection() {
		return role == Role.REFLECTION;
	}

	public String id() {
		String speakerId = speaker == null ? "flat" : speaker.id();
		String base = role == Role.REFLECTION ? "reflection:" + speakerId
			: "direct:" + speakerId;
		if (subwooferBand == null) {
			return base;
		}
		return base + "@" + Math.round(subwooferBand.lowHz()) + "-"
			+ Math.round(subwooferBand.highHz());
	}

	private static double quantize(double value, double step, double min,
								   double max, double fallback) {
		if (!Double.isFinite(value)) {
			return fallback;
		}
		double clamped = Math.max(min, Math.min(max, value));
		return Math.round(clamped / step) * step;
	}
}
