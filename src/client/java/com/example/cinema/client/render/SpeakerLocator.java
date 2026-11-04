package com.example.cinema.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Temporary client-side marker for identifying a physical speaker block.
 * Emits a short vertical particle column so the user can see which speaker a
 * GUI row belongs to.
 */
public final class SpeakerLocator {
	private static final long DURATION_MILLIS = 8_000L;
	private static BlockPos position;
	private static ResourceKey<Level> dimension;
	private static long expiresAtMillis;
	private static int tickCounter;

	private SpeakerLocator() {
	}

	public static void highlight(Minecraft client, BlockPos pos) {
		if (client.level == null) {
			return;
		}
		position = pos;
		dimension = client.level.dimension();
		expiresAtMillis = System.currentTimeMillis() + DURATION_MILLIS;
		tickCounter = 0;
		emit(client.level, pos, 18);
	}

	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (position == null || level == null || client.player == null
			|| System.currentTimeMillis() > expiresAtMillis
			|| !level.dimension().equals(dimension)) {
			position = null;
			return;
		}
		if (++tickCounter % 2 != 0) {
			return;
		}
		emit(level, position, 3);
	}

	public static void clear() {
		position = null;
		dimension = null;
		expiresAtMillis = 0L;
	}

	private static void emit(ClientLevel level, BlockPos pos, int count) {
		double centerX = pos.getX() + 0.5;
		double centerZ = pos.getZ() + 0.5;
		for (int i = 0; i < count; i++) {
			double spread = (level.random.nextDouble() - 0.5) * 0.55;
			double height = 0.15 + level.random.nextDouble() * 2.4;
			level.addParticle(ParticleTypes.END_ROD,
				centerX + spread, pos.getY() + height, centerZ + spread,
				0.0, 0.015, 0.0);
		}
	}
}
