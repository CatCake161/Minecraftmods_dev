package com.example.cinema.client.audio.acoustics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * First-order image-source geometry and validation.
 *
 * <p>The pure {@link #geometry(MirrorPlane, Point3, Point3)} step mirrors the
 * speaker, intersects the image-to-listener line with the candidate plane and
 * returns the reflection point/path/delay. {@link #solve} then validates the
 * tap against a {@link World}: the reflection point must be a real solid face.
 * Both physical legs are scanned for occlusion; blocked taps are kept and
 * attenuated instead of being discarded, so a reflection can still arrive
 * around a corner or through a thin obstruction.
 */
public final class ImageSourceSolver {
	public static final double SPEED_OF_SOUND = 343.0;
	public static final double MIN_DELAY_SECONDS = 0.001;
	public static final double MAX_DELAY_SECONDS = 0.080;
	public static final int MAX_TAPS_PER_SPEAKER = 6;
	public static final int MAX_TAPS_PER_RECEIVER = 36;

	private static final double EPSILON = 1.0e-6;
	private static final double BOUNDS_MARGIN = 0.01;
	private static final double PATH_INSET = 0.05;
	private static final Comparator<ReflectionTap> TAP_ORDER =
		Comparator.comparingDouble(ReflectionTap::gain).reversed()
			.thenComparing(ReflectionTap::planeKey)
			.thenComparingInt(ReflectionTap::channel);

	private ImageSourceSolver() {
	}

	/** World-facing validation hooks; implemented by {@link ReflectionProbe}. */
	public interface World {
		/**
		 * Returns the material of a real solid face at {@code point} whose
		 * outward normal matches the plane, or {@code null} when there is no
		 * matching wall (doorway, air, furniture, wrong face).
		 */
		AcousticMaterial wallAt(MirrorPlane plane, Point3 point);

		/** Occlusion scan for one reflection path leg. */
		OcclusionResult occlusion(Point3 from, Point3 to);
	}

	public record Geometry(Point3 imageSource, Point3 reflectionPoint,
						   double pathLength, double directDistance,
						   double delaySeconds) {
		public double delayMilliseconds() {
			return delaySeconds * 1000.0;
		}
	}

	/**
	 * Pure mirror-source geometry. Returns empty when source/listener are not
	 * on the same side, the reflection point falls outside a bounded plane, or
	 * the path is degenerate.
	 */
	public static Optional<Geometry> geometry(MirrorPlane plane, Point3 source,
											  Point3 listener) {
		if (plane == null || source == null || listener == null) {
			return Optional.empty();
		}
		double dS = plane.signedDistance(source);
		double dL = plane.signedDistance(listener);
		if (!Double.isFinite(dS) || !Double.isFinite(dL)
			|| dS <= EPSILON || dL <= EPSILON) {
			return Optional.empty();
		}
		Point3 normal = plane.normalVector();
		Point3 imageSource = source.subtract(normal.scale(2.0 * dS));
		double t = dS / (dS + dL);
		Point3 reflectionPoint = imageSource.add(
			listener.subtract(imageSource).scale(t));
		if (!plane.contains(reflectionPoint, BOUNDS_MARGIN)) {
			return Optional.empty();
		}
		double pathLength = imageSource.distanceTo(listener);
		double directDistance = source.distanceTo(listener);
		if (!Double.isFinite(pathLength) || !Double.isFinite(directDistance)
			|| pathLength <= directDistance + EPSILON) {
			return Optional.empty();
		}
		double delaySeconds = (pathLength - directDistance) / SPEED_OF_SOUND;
		return Optional.of(new Geometry(imageSource, reflectionPoint, pathLength,
			directDistance, delaySeconds));
	}

	/**
	 * Validates every candidate plane for one physical speaker. LFE sources are
	 * excluded. Returns at most {@link #MAX_TAPS_PER_SPEAKER} strongest taps.
	 */
	public static List<ReflectionTap> solve(List<MirrorPlane> planes, int channel,
											Point3 source, Point3 listener,
											boolean lfe, World world) {
		if (lfe || planes == null || planes.isEmpty() || source == null
			|| listener == null || world == null) {
			return List.of();
		}
		List<ReflectionTap> taps = new ArrayList<>();
		for (MirrorPlane plane : planes) {
			Optional<Geometry> result = geometry(plane, source, listener);
			if (result.isEmpty()) {
				continue;
			}
			Geometry geometry = result.get();
			if (geometry.delaySeconds() < MIN_DELAY_SECONDS
				|| geometry.delaySeconds() > MAX_DELAY_SECONDS) {
				continue;
			}
			AcousticMaterial material = world.wallAt(plane, geometry.reflectionPoint());
			if (material == null) {
				continue;
			}
			OcclusionResult occlusion = OcclusionResult.combine(
				legOcclusion(world, source, geometry.reflectionPoint()),
				legOcclusion(world, geometry.reflectionPoint(), listener));
			taps.add(new ReflectionTap(channel, plane.key(), geometry.imageSource(),
				geometry.reflectionPoint(), geometry.pathLength(),
				geometry.delaySeconds(), material,
				reflectionGain(material, geometry.pathLength())
					* occlusion.linearGain(), occlusion));
		}
		return strongest(taps, MAX_TAPS_PER_SPEAKER);
	}

	/** Keeps the strongest taps across all speakers of one receiver. */
	public static List<ReflectionTap> strongest(List<ReflectionTap> taps, int max) {
		if (taps == null || taps.isEmpty() || max <= 0) {
			return List.of();
		}
		List<ReflectionTap> sorted = new ArrayList<>(taps);
		sorted.sort(TAP_ORDER);
		int limit = Math.min(max, sorted.size());
		return List.copyOf(sorted.subList(0, limit));
	}

	/**
	 * Base gain before directivity/air absorption: pressure reflection
	 * coefficient times the Java {@code 1/max(L,1)} distance rule.
	 */
	public static double reflectionGain(AcousticMaterial material, double pathLength) {
		if (material == null || !Double.isFinite(pathLength)) {
			return 0.0;
		}
		double reflection = Math.sqrt(Math.max(0.0, 1.0 - material.mid()));
		return reflection / Math.max(1.0, pathLength);
	}

	private static OcclusionResult legOcclusion(World world, Point3 from,
												Point3 to) {
		double distance = from.distanceTo(to);
		if (!Double.isFinite(distance) || distance <= EPSILON) {
			return OcclusionResult.NONE;
		}
		double inset = Math.min(PATH_INSET, distance * 0.25);
		Point3 direction = to.subtract(from).scale(1.0 / distance);
		return world.occlusion(from.add(direction.scale(inset)),
			to.subtract(direction.scale(inset)));
	}
}
