package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

/**
 * One of the six room planes. {@code axis} is the plane normal axis;
 * {@code normal} is +1 or -1 toward the room interior. The u/v tangential
 * bounds are in the other two axes, in world meters.
 */
public record AcousticSurface(Axis axis, double coordinate, double normal,
							  double minU, double maxU, double minV, double maxV,
							  AcousticMaterial material, int samples) {
	public enum Axis {
		X,
		Y,
		Z
	}

	public AcousticSurface {
		material = material == null ? AcousticMaterials.DEFAULT : material;
	}

	public double area() {
		return Math.max(0.0, maxU - minU) * Math.max(0.0, maxV - minV);
	}

	public Vec3 normalVector() {
		return switch (axis) {
			case X -> new Vec3(normal, 0.0, 0.0);
			case Y -> new Vec3(0.0, normal, 0.0);
			case Z -> new Vec3(0.0, 0.0, normal);
		};
	}

	public Vec3 center() {
		double u = (minU + maxU) * 0.5;
		double v = (minV + maxV) * 0.5;
		return switch (axis) {
			case X -> new Vec3(coordinate, u, v);
			case Y -> new Vec3(u, coordinate, v);
			case Z -> new Vec3(u, v, coordinate);
		};
	}

	public String label() {
		return switch (axis) {
			case X -> normal > 0 ? "x-" : "x+";
			case Y -> normal > 0 ? "y-" : "y+";
			case Z -> normal > 0 ? "z-" : "z+";
		};
	}
}
