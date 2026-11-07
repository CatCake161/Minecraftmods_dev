package com.example.cinema.client.audio.acoustics;

/**
 * Small immutable world-space point used by the pure reflection geometry.
 *
 * <p>Keeping this type free of Minecraft classes lets the mirror/visibility
 * math run in plain JUnit tests; the world-facing probe converts to and from
 * {@link net.minecraft.world.phys.Vec3} at the boundary.
 */
public record Point3(double x, double y, double z) {
	public static final Point3 ZERO = new Point3(0.0, 0.0, 0.0);

	public static Point3 of(double x, double y, double z) {
		return new Point3(x, y, z);
	}

	public Point3 add(Point3 other) {
		return new Point3(x + other.x, y + other.y, z + other.z);
	}

	public Point3 subtract(Point3 other) {
		return new Point3(x - other.x, y - other.y, z - other.z);
	}

	public Point3 scale(double factor) {
		return new Point3(x * factor, y * factor, z * factor);
	}

	public double dot(Point3 other) {
		return x * other.x + y * other.y + z * other.z;
	}

	public double lengthSqr() {
		return x * x + y * y + z * z;
	}

	public double length() {
		return Math.sqrt(lengthSqr());
	}

	public double distanceTo(Point3 other) {
		return subtract(other).length();
	}

	public Point3 normalize() {
		double length = length();
		return length < 1.0e-12 ? ZERO : scale(1.0 / length);
	}

	public double coordinate(MirrorPlane.Axis axis) {
		return switch (axis) {
			case X -> x;
			case Y -> y;
			case Z -> z;
		};
	}
}
