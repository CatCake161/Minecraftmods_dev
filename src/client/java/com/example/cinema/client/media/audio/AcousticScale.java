package com.example.cinema.client.media.audio;

/**
 * Conversion between Minecraft block geometry and acoustic metres.
 *
 * <p>Room detection, image-source geometry and rendering all work in block
 * units. Acoustic formulas (RT60, air absorption, reflection delay, distance
 * attenuation) expect metres. This class is the single conversion point so the
 * DSP code does not scatter {@code * metersPerBlock} factors.
 *
 * <p>The default is {@code 1.00 m/block}, which preserves the previous
 * behaviour and matches the vanilla block-to-metre convention. The setting is
 * global and is updated by {@code CinemaClientConfig} after the config file is
 * loaded; pure unit tests therefore see {@code 1.00} unless they set it
 * explicitly.
 */
public final class AcousticScale {
	public static final double MIN_METERS_PER_BLOCK = 0.25;
	public static final double MAX_METERS_PER_BLOCK = 2.00;
	public static final double DEFAULT_METERS_PER_BLOCK = 1.00;

	private static volatile double metersPerBlock = DEFAULT_METERS_PER_BLOCK;

	private AcousticScale() {
	}

	public static double metersPerBlock() {
		return metersPerBlock;
	}

	public static void setMetersPerBlock(double value) {
		metersPerBlock = Double.isFinite(value)
			? Math.max(MIN_METERS_PER_BLOCK,
				Math.min(MAX_METERS_PER_BLOCK, value))
			: DEFAULT_METERS_PER_BLOCK;
	}

	/** Converts a block length to metres. */
	public static double blocksToMeters(double blocks) {
		return blocks * metersPerBlock;
	}

	/** Converts a metre length back to blocks. */
	public static double metersToBlocks(double meters) {
		return meters / metersPerBlock;
	}

	/** Converts a block-unit area to square metres. */
	public static double squareBlocksToSquareMeters(double areaBlocks) {
		return areaBlocks * metersPerBlock * metersPerBlock;
	}

	/** Converts a block-unit volume to cubic metres. */
	public static double cubicBlocksToCubicMeters(double volumeBlocks) {
		return volumeBlocks * metersPerBlock * metersPerBlock * metersPerBlock;
	}
}
