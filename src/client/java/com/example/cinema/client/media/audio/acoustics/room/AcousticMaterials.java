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

/**
 * Initial A1 material table.
 *
 * <p>The coefficients are rough engineering placeholders, not measurement
 * data. They are intentionally concentrated in one class so they can be
 * replaced by a data-driven table after in-game calibration.
 */
public final class AcousticMaterials {
	public static final AcousticMaterial SCREEN =
		new AcousticMaterial("screen", 0.05, 0.10, 0.15, 0.10);
	public static final AcousticMaterial FABRIC =
		new AcousticMaterial("fabric", 0.19, 0.62, 0.65, 0.35);
	public static final AcousticMaterial CARPET =
		new AcousticMaterial("carpet", 0.04, 0.255, 0.625, 0.35);
	public static final AcousticMaterial WOOD =
		new AcousticMaterial("wood", 0.25, 0.13, 0.105, 0.15);
	public static final AcousticMaterial STONE =
		new AcousticMaterial("stone", 0.015, 0.05, 0.09, 0.10);
	public static final AcousticMaterial GLASS =
		new AcousticMaterial("glass", 0.12, 0.035, 0.02, 0.05);
	public static final AcousticMaterial VEGETATION =
		new AcousticMaterial("vegetation", 0.10, 0.20, 0.30, 0.40);
	public static final AcousticMaterial WATER =
		new AcousticMaterial("water", 0.01, 0.01, 0.02, 0.00);
	public static final AcousticMaterial SNOW =
		new AcousticMaterial("snow", 0.20, 0.40, 0.60, 0.30);
	public static final AcousticMaterial POROUS =
		new AcousticMaterial("porous", 0.10, 0.20, 0.30, 0.30);
	public static final AcousticMaterial METAL =
		new AcousticMaterial("metal", 0.02, 0.03, 0.04, 0.05);
	public static final AcousticMaterial DEFAULT =
		new AcousticMaterial("default", 0.05, 0.05, 0.05, 0.10);

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

	public static AcousticMaterial forState(BlockState state) {
		if (state.getBlock() instanceof ScreenBlock) {
			return SCREEN;
		}
		if (state.getBlock() instanceof CarpetBlock
			|| state.is(BlockTags.WOOL_CARPETS)) {
			return CARPET;
		}
		if (state.is(BlockTags.WOOL)) {
			return FABRIC;
		}
		if (state.is(BlockTags.PLANKS)) {
			return WOOD;
		}
		if (state.is(BlockTags.LEAVES)) {
			return VEGETATION;
		}
		if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
			|| state.is(Blocks.GRAVEL)) {
			return POROUS;
		}
		if (state.is(BlockTags.STONE_BRICKS)) {
			return STONE;
		}
		if (state.is(BlockTags.IMPERMEABLE)) {
			return GLASS;
		}
		if (state.is(Blocks.WATER)) {
			return WATER;
		}
		if (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK)
			|| state.is(Blocks.POWDER_SNOW)) {
			return SNOW;
		}
		if (state.getSoundType() == SoundType.WOOL) {
			return FABRIC;
		}
		if (state.getSoundType() == SoundType.WOOD) {
			return WOOD;
		}
		if (state.getSoundType() == SoundType.STONE) {
			return STONE;
		}
		if (state.getSoundType() == SoundType.GLASS) {
			return GLASS;
		}
		if (state.getSoundType() == SoundType.GRAVEL
			|| state.getSoundType() == SoundType.SAND) {
			return POROUS;
		}
		if (state.getSoundType() == SoundType.GRASS
			|| state.getSoundType() == SoundType.WET_GRASS) {
			return VEGETATION;
		}
		if (state.getSoundType() == SoundType.SNOW
			|| state.getSoundType() == SoundType.POWDER_SNOW) {
			return SNOW;
		}
		if (state.getSoundType() == SoundType.METAL) {
			return METAL;
		}
		return DEFAULT;
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
