package com.example.cinema.client.media.audio.acoustics.room;

import com.example.cinema.block.AvReceiverBlock;
import com.example.cinema.block.ProjectorBlock;
import com.example.cinema.block.ScreenBlock;
import com.example.cinema.block.SeatBlock;
import com.example.cinema.block.SpeakerBlock;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionProfile;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Acoustic material lookup and built-in fallback table.
 *
 * <p>The live absorption/scattering coefficients are loaded from
 * {@code config/cinema-acoustic-materials.json}; the built-in table is used
 * for first launch, missing keys and invalid JSON. The values are engineering
 * estimates, not measured Minecraft-block data. Occlusion coefficients remain
 * in this class for now.
 */
public final class AcousticMaterials {
	/*
	 * Built-in fallback values. The live table is loaded from
	 * config/cinema-acoustic-materials.json when the client starts. The
	 * scattering values are engineering octave-band estimates inspired by
	 * ISO 17497-1 random-incidence data, not Minecraft-block measurements.
	 */
	private static final Map<String, AcousticMaterial> DEFAULTS = defaults();
	private static volatile Map<String, AcousticMaterial> table = DEFAULTS;

	/*
	 * Occlusion values are game tuning, not STC tables. Attenuation is per metre
	 * of actual collision-shape path, so a slab contributes about half a block.
	 * Stone is the reference strongest common wall; glass is thin and bright.
	 */
	private static final OcclusionProfile GLASS_OCCLUSION =
		new OcclusionProfile(5.0, 8_000.0, 1);
	private static final OcclusionProfile WOOD_OCCLUSION =
		new OcclusionProfile(8.0, 2_500.0, 2);
	private static final OcclusionProfile STONE_OCCLUSION =
		new OcclusionProfile(18.0, 800.0, 2);
	private static final OcclusionProfile METAL_OCCLUSION =
		new OcclusionProfile(24.0, 600.0, 2);
	private static final OcclusionProfile VEGETATION_OCCLUSION =
		new OcclusionProfile(4.0, 5_000.0, 1);
	private static final OcclusionProfile FABRIC_OCCLUSION =
		new OcclusionProfile(6.0, 3_000.0, 1);
	private static final OcclusionProfile POROUS_OCCLUSION =
		new OcclusionProfile(10.0, 1_500.0, 1);
	private static final OcclusionProfile SNOW_OCCLUSION =
		new OcclusionProfile(12.0, 1_200.0, 1);
	private static final OcclusionProfile WATER_OCCLUSION =
		new OcclusionProfile(2.0, 12_000.0, 1);
	private static final OcclusionProfile DEFAULT_OCCLUSION =
		new OcclusionProfile(8.0, 2_500.0, 2);

	private AcousticMaterials() {
	}

	private static Map<String, AcousticMaterial> defaults() {
		Map<String, AcousticMaterial> map = new LinkedHashMap<>();
		put(map, new AcousticMaterial("screen",
			0.05, 0.10, 0.15, 0.06, 0.10, 0.14));
		put(map, new AcousticMaterial("fabric",
			0.19, 0.62, 0.65, 0.15, 0.35, 0.45));
		put(map, new AcousticMaterial("carpet",
			0.04, 0.255, 0.625, 0.15, 0.35, 0.45));
		put(map, new AcousticMaterial("wood",
			0.25, 0.13, 0.105, 0.06, 0.15, 0.24));
		put(map, new AcousticMaterial("stone",
			0.015, 0.05, 0.09, 0.04, 0.10, 0.16));
		put(map, new AcousticMaterial("glass",
			0.12, 0.035, 0.02, 0.02, 0.05, 0.08));
		put(map, new AcousticMaterial("vegetation",
			0.10, 0.20, 0.30, 0.20, 0.40, 0.55));
		put(map, new AcousticMaterial("water",
			0.01, 0.01, 0.02, 0.00, 0.00, 0.01));
		put(map, new AcousticMaterial("snow",
			0.20, 0.40, 0.60, 0.12, 0.30, 0.40));
		put(map, new AcousticMaterial("porous",
			0.10, 0.20, 0.30, 0.12, 0.30, 0.40));
		put(map, new AcousticMaterial("metal",
			0.02, 0.03, 0.04, 0.02, 0.05, 0.08));
		put(map, new AcousticMaterial("default",
			0.05, 0.05, 0.05, 0.05, 0.10, 0.15));
		return Map.copyOf(map);
	}

	private static void put(Map<String, AcousticMaterial> map,
							AcousticMaterial material) {
		map.put(material.name(), material);
	}

	/** Built-in table, useful for writing the initial JSON file and tests. */
	public static Map<String, AcousticMaterial> defaultsSnapshot() {
		return DEFAULTS;
	}

	public static Map<String, AcousticMaterial> snapshot() {
		return table;
	}

	public static AcousticMaterial get(String id) {
		AcousticMaterial material = table.get(id);
		return material == null ? table.get("default") : material;
	}

	/**
	 * Replaces the live coefficients. Missing keys keep their built-in value;
	 * {@code default} is always present.
	 */
	public static synchronized void apply(
		Map<String, AcousticMaterial> source) {
		Map<String, AcousticMaterial> merged = new LinkedHashMap<>(DEFAULTS);
		if (source != null) {
			for (Map.Entry<String, AcousticMaterial> entry : source.entrySet()) {
				if (entry.getKey() != null && entry.getValue() != null) {
					merged.put(entry.getKey(), entry.getValue());
				}
			}
		}
		table = Map.copyOf(merged);
	}

	public static synchronized void resetToDefaults() {
		table = DEFAULTS;
	}

	public static AcousticMaterial defaultMaterial() {
		return get("default");
	}

	/**
	 * Combines the materials of a multi-bounce path. Energy absorption is
	 * accumulated as {@code alpha_eff = 1 - product(1-alpha_i)} so the pressure
	 * coefficient is {@code product(sqrt(1-alpha_i))}. The returned material is
	 * suitable for {@code MaterialReflectionFilter} and the reflection FIR;
	 * broadband gain is still applied separately from the path's mid-band
	 * coefficient.
	 */
	public static AcousticMaterial combine(String name,
										   List<AcousticMaterial> materials) {
		if (materials == null || materials.isEmpty()) {
			return new AcousticMaterial(name, 0.0, 0.0, 0.0, 0.0);
		}
		double lowRemaining = 1.0;
		double midRemaining = 1.0;
		double highRemaining = 1.0;
		double scatterLowRemaining = 1.0;
		double scatterMidRemaining = 1.0;
		double scatterHighRemaining = 1.0;
		for (AcousticMaterial material : materials) {
			if (material == null) {
				continue;
			}
			lowRemaining *= 1.0 - material.low();
			midRemaining *= 1.0 - material.mid();
			highRemaining *= 1.0 - material.high();
			scatterLowRemaining *= 1.0 - material.scatteringLow();
			scatterMidRemaining *= 1.0 - material.scatteringMid();
			scatterHighRemaining *= 1.0 - material.scatteringHigh();
		}
		return new AcousticMaterial(name,
			1.0 - lowRemaining, 1.0 - midRemaining, 1.0 - highRemaining,
			1.0 - scatterLowRemaining, 1.0 - scatterMidRemaining,
			1.0 - scatterHighRemaining);
	}

	public static AcousticMaterial combine(List<AcousticMaterial> materials) {
		return combine("combined", materials);
	}

	public static AcousticMaterial forState(BlockState state) {
		if (state.getBlock() instanceof ScreenBlock) {
			return get("screen");
		}
		if (state.getBlock() instanceof CarpetBlock
			|| state.is(BlockTags.WOOL_CARPETS)) {
			return get("carpet");
		}
		if (state.is(BlockTags.WOOL)) {
			return get("fabric");
		}
		if (state.is(BlockTags.PLANKS)) {
			return get("wood");
		}
		if (state.is(BlockTags.LEAVES)) {
			return get("vegetation");
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL)) {
			return get("porous");
		}
		if (state.is(BlockTags.STONE_BRICKS)) {
			return get("stone");
		}
		if (state.is(BlockTags.IMPERMEABLE)) {
			return get("glass");
		}
		if (state.is(Blocks.WATER)) {
			return get("water");
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
			|| state.is(Blocks.POWDER_SNOW)) {
			return get("snow");
		}
		if (state.getSoundType() == SoundType.WOOL) {
			return get("fabric");
		}
		if (state.getSoundType() == SoundType.WOOD) {
			return get("wood");
		}
		if (state.getSoundType() == SoundType.STONE) {
			return get("stone");
		}
		if (state.getSoundType() == SoundType.GLASS) {
			return get("glass");
		}
		if (state.getSoundType() == SoundType.GRAVEL
			|| state.getSoundType() == SoundType.SAND) {
			return get("porous");
		}
		if (state.getSoundType() == SoundType.GRASS
			|| state.getSoundType() == SoundType.WET_GRASS) {
			return get("vegetation");
		}
		if (state.getSoundType() == SoundType.SNOW
			|| state.getSoundType() == SoundType.POWDER_SNOW) {
			return get("snow");
		}
		if (state.getSoundType() == SoundType.METAL) {
			return get("metal");
		}
		return get("default");
	}

	/**
	 * Direct-path occlusion properties. Screens are intentionally transparent
	 * here: they are part of the room for reverb, but they should not muffle a
	 * speaker placed behind them. The mod's own furniture is also transparent.
	 */
	public static OcclusionProfile occlusionForState(BlockState state) {
		if (state.getBlock() instanceof ScreenBlock || isIgnoredObstacle(state)) {
			return OcclusionProfile.TRANSPARENT;
		}
		if (state.is(BlockTags.IMPERMEABLE)) {
			return GLASS_OCCLUSION;
		}
		if (state.is(BlockTags.PLANKS)) {
			return WOOD_OCCLUSION;
		}
		if (state.is(BlockTags.STONE_BRICKS)) {
			return STONE_OCCLUSION;
		}
		if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)
			|| state.getBlock() instanceof CarpetBlock) {
			return FABRIC_OCCLUSION;
		}
		if (state.is(BlockTags.LEAVES)) {
			return VEGETATION_OCCLUSION;
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL)) {
			return POROUS_OCCLUSION;
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
			|| state.is(Blocks.POWDER_SNOW)) {
			return SNOW_OCCLUSION;
		}
		if (state.is(Blocks.WATER)) {
			return WATER_OCCLUSION;
		}
		SoundType sound = state.getSoundType();
		if (sound == SoundType.GLASS) {
			return GLASS_OCCLUSION;
		}
		if (sound == SoundType.WOOD) {
			return WOOD_OCCLUSION;
		}
		if (sound == SoundType.STONE) {
			return STONE_OCCLUSION;
		}
		if (sound == SoundType.METAL) {
			return METAL_OCCLUSION;
		}
		if (sound == SoundType.GRAVEL || sound == SoundType.SAND) {
			return POROUS_OCCLUSION;
		}
		if (sound == SoundType.GRASS || sound == SoundType.WET_GRASS) {
			return VEGETATION_OCCLUSION;
		}
		if (sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) {
			return SNOW_OCCLUSION;
		}
		if (sound == SoundType.WOOL) {
			return FABRIC_OCCLUSION;
		}
		return DEFAULT_OCCLUSION;
	}

	/**
	 * Furniture that should not become a room plane when a ray passes through
	 * it. Screens are intentionally not ignored: they are part of the room.
	 */
	public static boolean isIgnoredObstacle(BlockState state) {
		return state.getBlock() instanceof SpeakerBlock
			|| state.getBlock() instanceof SeatBlock
			|| state.getBlock() instanceof ProjectorBlock
			|| state.getBlock() instanceof AvReceiverBlock;
	}
}
