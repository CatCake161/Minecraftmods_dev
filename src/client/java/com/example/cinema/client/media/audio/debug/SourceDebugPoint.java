package com.example.cinema.client.media.audio.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One world-space audio source for the optional debug overlay.
 *
 * <p>{@code DIRECT} is a physical speaker source. {@code REFLECTION} is a
 * first-order or premium per-tap image source. {@code WET_PHYSICAL} and
 * {@code WET_GHOST} are the dynamic late-bus output points.
 */
public record SourceDebugPoint(Vec3 position, Kind kind, int order, int index,
							   boolean active, BlockPos receiver, String key) {
	public static final int MAX_ORDER = 4;

	public enum Kind {
		DIRECT,
		REFLECTION,
		WET_PHYSICAL,
		WET_LFE,
		WET_GHOST
	}
}
