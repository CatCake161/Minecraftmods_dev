package com.example.cinema.client.audio.acoustics;

import java.util.Locale;

/**
 * Candidate mirror plane for a first-order reflection.
 *
 * <p>{@code normal} is +1 or -1 and points toward the speaker/interior side.
 * Room-snapshot planes carry their tangential bounds; planes discovered by a
 * speaker ray are unbounded and must be validated at the reflection point.
 */
public record MirrorPlane(Axis axis, double coordinate, double normal,
						  boolean bounded, double minU, double maxU,
						  double minV, double maxV, Point3 seedPoint) {
	public enum Axis {
		X,
		Y,
		Z
	}

	public MirrorPlane {
		if (axis == null) {
			throw new IllegalArgumentException("axis");
		}
		normal = normal < 0.0 ? -1.0 : 1.0;
		if (seedPoint == null) {
			seedPoint = switch (axis) {
				case X -> new Point3(coordinate, 0.0, 0.0);
				case Y -> new Point3(0.0, coordinate, 0.0);
				case Z -> new Point3(0.0, 0.0, coordinate);
			};
		}
	}

	public static MirrorPlane unbounded(Axis axis, double coordinate, double normal,
										Point3 seedPoint) {
		return new MirrorPlane(axis, coordinate, normal, false,
			0.0, 0.0, 0.0, 0.0, seedPoint);
	}

	public static MirrorPlane bounded(Axis axis, double coordinate, double normal,
									  double minU, double maxU,
									  double minV, double maxV,
									  Point3 seedPoint) {
		return new MirrorPlane(axis, coordinate, normal, true,
			minU, maxU, minV, maxV, seedPoint);
	}

	public Point3 normalVector() {
		return switch (axis) {
			case X -> new Point3(normal, 0.0, 0.0);
			case Y -> new Point3(0.0, normal, 0.0);
			case Z -> new Point3(0.0, 0.0, normal);
		};
	}

	/** Signed distance from the plane; positive on the speaker/interior side. */
	public double signedDistance(Point3 point) {
		return (point.coordinate(axis) - coordinate) * normal;
	}

	/** True when the point lies inside the optional tangential bounds. */
	public boolean contains(Point3 point, double margin) {
		if (!bounded) {
			return true;
		}
		double u;
		double v;
		switch (axis) {
			case X -> {
				u = point.y();
				v = point.z();
			}
			case Y -> {
				u = point.x();
				v = point.z();
			}
			case Z -> {
				u = point.x();
				v = point.y();
			}
			default -> throw new IllegalStateException();
		}
		double m = Math.max(0.0, margin);
		return u >= minU - m && u <= maxU + m
			&& v >= minV - m && v <= maxV + m;
	}

	/** Stable identity used for dedupe and reflection-tap keys. */
	public String key() {
		long millimetres = Math.round(coordinate * 1000.0);
		return axis.name() + (normal > 0.0 ? "+" : "-") + millimetres;
	}

	/** Short room-style label, e.g. {@code x+} for a wall at max X. */
	public String label() {
		return axis.name().toLowerCase(Locale.ROOT)
			+ (normal > 0.0 ? "-" : "+");
	}
}
