package com.example.cinema.client.audio.acoustics;

import net.minecraft.world.phys.Vec3;

/**
 * Bounded flood-fill result for the connected air volume around the listener.
 *
 * <p>The fill measures the air actually reachable from the listener inside the
 * detected box. {@code surfaceArea} is the total air/boundary contact area and
 * {@code openArea} is the part of that boundary that leads back into open air
 * (a doorway, a missing wall, or an open side). Volume and area use one cubic
 * meter per block cell and one square meter per block face, so partial blocks
 * are approximated.
 */
public record RoomFill(double volume, double surfaceArea, double openArea,
					   double alphaLow, double alphaMid, double alphaHigh,
					   int cells, boolean complete, int openFaces,
					   Vec3 min, Vec3 max, long gameTime) {
	private static final int FACE_MASK = 0x3F;

	public enum Face {
		X_NEG("x-", 1),
		X_POS("x+", 1 << 1),
		Y_NEG("y-", 1 << 2),
		Y_POS("y+", 1 << 3),
		Z_NEG("z-", 1 << 4),
		Z_POS("z+", 1 << 5);

		private final String label;
		private final int bit;

		Face(String label, int bit) {
			this.label = label;
			this.bit = bit;
		}

		public String label() {
			return label;
		}

		public int bit() {
			return bit;
		}
	}

	public RoomFill {
		volume = Math.max(0.0, volume);
		surfaceArea = Math.max(0.0, surfaceArea);
		openArea = Math.max(0.0, openArea);
		alphaLow = clamp01(alphaLow);
		alphaMid = clamp01(alphaMid);
		alphaHigh = clamp01(alphaHigh);
		cells = Math.max(0, cells);
		openFaces &= FACE_MASK;
		min = min == null ? Vec3.ZERO : min;
		max = max == null ? min : max;
	}

	public boolean isOpen(Face face) {
		return (openFaces & face.bit()) != 0;
	}

	public String openFacesLabel() {
		StringBuilder builder = new StringBuilder();
		for (Face face : Face.values()) {
			if (!isOpen(face)) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append(',');
			}
			builder.append(face.label());
		}
		return builder.length() == 0 ? "-" : builder.toString();
	}

	public boolean contains(Vec3 point, double margin) {
		return point.x >= min.x - margin && point.x <= max.x + margin
			&& point.y >= min.y - margin && point.y <= max.y + margin
			&& point.z >= min.z - margin && point.z <= max.z + margin;
	}

	public double coverage(double boxVolume) {
		return boxVolume > 0.0 ? volume / boxVolume : 0.0;
	}

	private static double clamp01(double value) {
		if (!Double.isFinite(value)) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, value));
	}
}
