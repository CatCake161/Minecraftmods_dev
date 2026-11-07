package com.example.cinema.client.audio.acoustics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects solid boundary faces from the flood fill and greedily merges them
 * into bounded coplanar patches.
 *
 * <p>A flood-fill room can contain partial walls, pillars, steps and furniture
 * cut-outs. Feeding every unit face to mirror discovery would create thousands
 * of candidates, so faces are grouped by {@code (axis, plane, normal)} and
 * merged into maximal rectangles. The resulting patches are the optional
 * bounded seeds consumed by {@link MirrorPlaneDiscovery}; they are still
 * validated at the actual reflection point by {@link ReflectionProbe}.
 */
final class ReflectionSurfaceMerger {
	/** Bound the per-scan face count so a complex room cannot stall a frame. */
	static final int MAX_FACES = 4_096;
	/** Bound the seed count handed to mirror discovery. */
	static final int MAX_PATCHES = 128;

	private final Map<Key, Grid> grids = new LinkedHashMap<>();
	private int faces;

	void add(Direction direction, BlockPos solid, AcousticMaterial material) {
		if (direction == null || solid == null || faces >= MAX_FACES) {
			return;
		}
		AcousticSurface.Axis axis = switch (direction.getAxis()) {
			case X -> AcousticSurface.Axis.X;
			case Y -> AcousticSurface.Axis.Y;
			case Z -> AcousticSurface.Axis.Z;
		};
		int step = switch (axis) {
			case X -> direction.getStepX();
			case Y -> direction.getStepY();
			case Z -> direction.getStepZ();
		};
		double coordinate = switch (axis) {
			case X -> step > 0 ? solid.getX() : solid.getX() + 1.0;
			case Y -> step > 0 ? solid.getY() : solid.getY() + 1.0;
			case Z -> step > 0 ? solid.getZ() : solid.getZ() + 1.0;
		};
		int normal = -step;
		int u = switch (axis) {
			case X -> solid.getY();
			case Y -> solid.getX();
			case Z -> solid.getX();
		};
		int v = switch (axis) {
			case X -> solid.getZ();
			case Y -> solid.getZ();
			case Z -> solid.getY();
		};
		Key key = new Key(axis, Math.round(coordinate * 1000.0), normal);
		Grid grid = grids.computeIfAbsent(key, ignored -> new Grid(axis, coordinate,
			normal));
		if (grid.put(u, v, material == null ? AcousticMaterials.DEFAULT : material)) {
			faces++;
		}
	}

	List<AcousticSurface> merge() {
		List<AcousticSurface> patches = new ArrayList<>();
		for (Grid grid : grids.values()) {
			grid.merge(patches);
		}
		patches.sort(Comparator.comparingDouble(AcousticSurface::area).reversed());
		if (patches.size() > MAX_PATCHES) {
			return List.copyOf(patches.subList(0, MAX_PATCHES));
		}
		return List.copyOf(patches);
	}

	int faceCount() {
		return faces;
	}

	private record Key(AcousticSurface.Axis axis, long coordinateMm, int normal) {
	}

	private record Cell(int u, int v) {
	}

	private static final class Grid {
		private final AcousticSurface.Axis axis;
		private final double coordinate;
		private final int normal;
		private final Long2ObjectOpenHashMap<AcousticMaterial> materials =
			new Long2ObjectOpenHashMap<>();

		private Grid(AcousticSurface.Axis axis, double coordinate, int normal) {
			this.axis = axis;
			this.coordinate = coordinate;
			this.normal = normal;
		}

		private boolean put(int u, int v, AcousticMaterial material) {
			long key = encode(u, v);
			boolean added = !materials.containsKey(key);
			materials.put(key, material);
			return added;
		}

		private void merge(List<AcousticSurface> patches) {
			List<Cell> cells = new ArrayList<>(materials.size());
			for (long key : materials.keySet()) {
				cells.add(new Cell((int) (key >> 32), (int) key));
			}
			cells.sort(Comparator.comparingInt(Cell::u).thenComparingInt(Cell::v));
			LongOpenHashSet used = new LongOpenHashSet(materials.size());
			for (Cell cell : cells) {
				if (used.contains(encode(cell.u(), cell.v()))) {
					continue;
				}
				int width = 1;
				while (contains(cell.u() + width, cell.v())
					&& !used.contains(encode(cell.u() + width, cell.v()))) {
					width++;
				}
				int height = 1;
				while (rowFree(cell.u(), cell.v() + height, width, used)) {
					height++;
				}
				int count = width * height;
				double low = 0.0;
				double mid = 0.0;
				double high = 0.0;
				double scattering = 0.0;
				for (int du = 0; du < width; du++) {
					for (int dv = 0; dv < height; dv++) {
						int u = cell.u() + du;
						int v = cell.v() + dv;
						used.add(encode(u, v));
						AcousticMaterial material = materials.get(encode(u, v));
						if (material == null) {
							continue;
						}
						low += material.low();
						mid += material.mid();
						high += material.high();
						scattering += material.scattering();
					}
				}
				patches.add(new AcousticSurface(axis, coordinate, normal,
					cell.u(), cell.u() + width, cell.v(), cell.v() + height,
					AcousticMaterial.mix("reflection", low, mid, high, scattering,
						count), count));
			}
		}

		private boolean rowFree(int u, int v, int width, LongOpenHashSet used) {
			for (int du = 0; du < width; du++) {
				int cellU = u + du;
				if (!contains(cellU, v) || used.contains(encode(cellU, v))) {
					return false;
				}
			}
			return true;
		}

		private boolean contains(int u, int v) {
			return materials.containsKey(encode(u, v));
		}

		private static long encode(int u, int v) {
			return ((long) u << 32) | (v & 0xFFFF_FFFFL);
		}
	}
}
