package com.example.cinema.client.media.audio.acoustics.geometry;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable voxel snapshot for the CUDA ray tracer.
 *
 * <p>One cell per block, built on the client thread from live
 * {@link BlockGetter} data. The grid is rebuilt when the room version changes,
 * but it is deliberately small (room bounds + margin), so a rebuild costs the
 * same order as one mirror-plane scan. It is not a replacement for the live
 * world probe used by the exact image solver.
 */
public final class AcousticVoxelGrid {
	/** Hard cap so a pathological selection cannot allocate gigabytes. */
	public static final int MAX_CELLS = 4_000_000;

	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	public final float originX;
	public final float originY;
	public final float originZ;
	/** 0 = air, 1 = solid. */
	public final byte[] solid;
	/** Reflection amplitude for solid cells, 0 for air. */
	public final float[] reflect;
	/** Diffuse scattering coefficient for solid cells, 0 for air. */
	public final float[] scatter;
	/** Mid/high-band material coefficient, used for tap damping. */
	public final float[] high;
	/** Per-cell material index into {@link #materials}; -1 for air. */
	public final int[] materialId;
	public final List<AcousticMaterial> materials;

	private AcousticVoxelGrid(int sizeX, int sizeY, int sizeZ,
							  float originX, float originY, float originZ,
							  byte[] solid, float[] reflect, float[] scatter, float[] high,
						  int[] materialId, List<AcousticMaterial> materials) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.originX = originX;
		this.originY = originY;
		this.originZ = originZ;
		this.solid = solid;
		this.reflect = reflect;
		this.scatter = scatter;
		this.high = high;
		this.materialId = materialId;
		this.materials = materials;
	}

	public int cellCount() {
		return sizeX * sizeY * sizeZ;
	}

	/** Builds a grid from precomputed arrays (tests and synthetic rooms). */
	public static AcousticVoxelGrid of(byte[] solid, float[] reflect,
									   float[] scatter, float[] high,
									   int[] materialId,
									   List<AcousticMaterial> materials,
									   int sizeX, int sizeY,
									   int sizeZ, float originX,
									   float originY, float originZ) {
		int cells = sizeX * sizeY * sizeZ;
		if (solid == null || reflect == null || scatter == null
			|| high == null || materialId == null || solid.length < cells
			|| reflect.length < cells || scatter.length < cells
			|| high.length < cells || materialId.length < cells) {
			throw new IllegalArgumentException("invalid voxel arrays");
		}
		return new AcousticVoxelGrid(sizeX, sizeY, sizeZ, originX, originY,
			originZ, solid, reflect, scatter, high, materialId, materials);
	}

	public static AcousticVoxelGrid from(BlockGetter level, int minX, int minY,
										 int minZ, int maxX, int maxY, int maxZ) {
		if (level == null) {
			throw new IllegalArgumentException("level");
		}
		int x0 = Math.min(minX, maxX);
		int y0 = Math.min(minY, maxY);
		int z0 = Math.min(minZ, maxZ);
		int x1 = Math.max(minX, maxX);
		int y1 = Math.max(minY, maxY);
		int z1 = Math.max(minZ, maxZ);
		int sizeX = x1 - x0 + 1;
		int sizeY = y1 - y0 + 1;
		int sizeZ = z1 - z0 + 1;
		long cells = (long) sizeX * sizeY * sizeZ;
		if (cells <= 0 || cells > MAX_CELLS) {
			throw new IllegalArgumentException("voxel grid too large: " + cells);
		}
		byte[] solid = new byte[(int) cells];
		float[] reflect = new float[(int) cells];
		float[] scatter = new float[(int) cells];
		float[] high = new float[(int) cells];
		int[] materialId = new int[(int) cells];
		java.util.Arrays.fill(materialId, -1);
		Map<String, Integer> materialIds = new HashMap<>();
		List<AcousticMaterial> materials = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = 0; y < sizeY; y++) {
			for (int z = 0; z < sizeZ; z++) {
				for (int x = 0; x < sizeX; x++) {
					cursor.set(x0 + x, y0 + y, z0 + z);
					BlockState state = level.getBlockState(cursor);
					if (state.isAir() || AcousticMaterials.isIgnoredObstacle(state)) {
						continue;
					}
					if (state.getCollisionShape(level, cursor).isEmpty()) {
						continue;
					}
					AcousticMaterial material = AcousticMaterials.forState(state);
					double absorption = material == null ? 0.5 : material.mid();
					double scattering = material == null ? 0.2
						: material.scatteringMid();
					float amplitude = (float) Math.sqrt(Math.max(0.0,
						Math.min(1.0, 1.0 - absorption)));
					int index = (z * sizeY + y) * sizeX + x;
					solid[index] = 1;
					reflect[index] = Math.max(0.02f, Math.min(1.0f, amplitude));
					scatter[index] = (float) Math.max(0.0, Math.min(1.0,
						scattering));
					Integer mid = material == null ? null : materialIds.get(material.name());
					if (mid == null) {
						mid = materials.size();
						materialIds.put(material.name(), mid);
								materials.add(material);
						}
						materialId[index] = mid;
							high[index] = (float) Math.max(0.0, Math.min(1.0,
						material == null ? 0.5 : material.high()));
				}
			}
		}
		return new AcousticVoxelGrid(sizeX, sizeY, sizeZ,
			x0, y0, z0, solid, reflect, scatter, high, materialId, materials);
	}
}
