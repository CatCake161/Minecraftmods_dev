package com.example.cinema.client.media.audio.acoustics.path;

import com.example.cinema.client.media.audio.acoustics.geometry.Point3;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic cosine-weighted hemisphere directions for the A3-S1b/S2
 * diffuse early taps.
 *
 * <p>The sequence is seeded by the reflection tap key, so the same tap gives
 * stable directions across updates. The caller must orient {@code normal}
 * toward the room/listener side. No world access happens here.
 */
public final class DiffuseRaySampler {
	private static final double GOLDEN_FRACTION = 0.6180339887498949;
	private static final double PLASTIC_FRACTION = 0.7548776662466927;
	private static final double TWO_PI = 2.0 * Math.PI;

	private DiffuseRaySampler() {
	}

	public static List<Point3> directions(Point3 normal, String key, int count) {
		if (normal == null || count <= 0 || normal.lengthSqr() < 1.0e-12) {
			return List.of();
		}
		Point3 n = normal.normalize();
		Point3 helper = Math.abs(n.y()) < 0.9
			? new Point3(0.0, 1.0, 0.0) : new Point3(1.0, 0.0, 0.0);
		Point3 tangent = cross(n, helper).normalize();
		Point3 bitangent = cross(n, tangent).normalize();
		long seed = key == null ? 0L : key.hashCode();
		List<Point3> result = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			double u1 = fract(0.5 + GOLDEN_FRACTION * (i + 1)
				+ unit(seed, 0x9E3779B9));
			double u2 = fract(0.5 + PLASTIC_FRACTION * (i + 1)
				+ unit(seed, 0x85EBCA6B));
			double radius = Math.sqrt(u1);
			double theta = TWO_PI * u2;
			double height = Math.sqrt(Math.max(0.0, 1.0 - u1));
			Point3 direction = tangent.scale(radius * Math.cos(theta))
				.add(bitangent.scale(radius * Math.sin(theta)))
				.add(n.scale(height));
			result.add(direction.normalize());
		}
		return List.copyOf(result);
	}

	private static Point3 cross(Point3 first, Point3 second) {
		return new Point3(
			first.y() * second.z() - first.z() * second.y(),
			first.z() * second.x() - first.x() * second.z(),
			first.x() * second.y() - first.y() * second.x());
	}

	private static double fract(double value) {
		return value - Math.floor(value);
	}

	private static double unit(long seed, int salt) {
		long value = seed + salt;
		value ^= value >>> 30;
		value *= 0xBF58476D1CE4E5B9L;
		value ^= value >>> 27;
		value *= 0x94D049BB133111EBL;
		value ^= value >>> 31;
		return (value >>> 11) * 0x1.0p-53;
	}
}
