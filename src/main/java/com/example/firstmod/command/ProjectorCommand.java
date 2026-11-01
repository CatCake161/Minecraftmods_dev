package com.example.firstmod.command;

import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * Tuning commands for the projector that will later become its GUI.
 *
 * <p>Settings live on the server-side block entity and are pushed to the
 * client with the normal block-entity update packet, so a LAN client sees the
 * same optics as the host.
 */
public final class ProjectorCommand {
	private static final SimpleCommandExceptionType NO_PROJECTOR =
		new SimpleCommandExceptionType(Component.literal("No cinema projector within 48 blocks"));
	private static final double SEARCH_RADIUS = 48.0;

	private ProjectorCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("cinema_projector")
			.then(Commands.literal("info")
				.executes(context -> {
					ProjectorBlockEntity projector = nearest(context.getSource());
					context.getSource().sendSuccess(() -> Component.literal(String.format(
						"projector %s: throw=%.2f focus=%s keystone=%.1f/%.1f brightness=%.2f wash=%.2f power=%s lamp=%.2f",
						projector.getBlockPos(),
						projector.throwRatio(),
						projector.focusDistance() < 0.0
							? "auto"
							: String.format("%.2f m", projector.focusDistance()),
						projector.keystoneVerticalDegrees(),
						projector.keystoneHorizontalDegrees(),
						projector.brightness(),
						projector.ambientWash(),
						projector.isPowered() ? "on" : "off",
						projector.lampLevel())), false);
					return 1;
				}))
			.then(Commands.literal("throw")
				.then(Commands.argument("ratio", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO))
					.executes(context -> {
						double ratio = DoubleArgumentType.getDouble(context, "ratio");
						ProjectorBlockEntity projector = nearest(context.getSource());
						projector.setThrowRatio(ratio);
						context.getSource().sendSuccess(() -> Component.literal(
							String.format("projector throw ratio = %.2f", projector.throwRatio())), false);
						return 1;
					})))
			.then(Commands.literal("focus")
				.then(Commands.literal("auto")
					.executes(context -> {
						ProjectorBlockEntity projector = nearest(context.getSource());
						projector.setFocusDistance(-1.0);
						context.getSource().sendSuccess(() -> Component.literal(
							"projector focus = auto (paired screen)"), false);
						return 1;
					}))
				.then(Commands.argument("meters", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_FOCUS_DISTANCE, ProjectorBlockEntity.MAX_FOCUS_DISTANCE))
					.executes(context -> {
						double meters = DoubleArgumentType.getDouble(context, "meters");
						ProjectorBlockEntity projector = nearest(context.getSource());
						projector.setFocusDistance(meters);
						context.getSource().sendSuccess(() -> Component.literal(
							String.format("projector focus = %.2f m", projector.focusDistance())), false);
						return 1;
					})))
			.then(Commands.literal("keystone")
				.then(Commands.argument("verticalDegrees", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES))
					.then(Commands.argument("horizontalDegrees", DoubleArgumentType.doubleArg(
							ProjectorBlockEntity.MIN_KEYSTONE_DEGREES, ProjectorBlockEntity.MAX_KEYSTONE_DEGREES))
						.executes(context -> {
							double vertical = DoubleArgumentType.getDouble(context, "verticalDegrees");
							double horizontal = DoubleArgumentType.getDouble(context, "horizontalDegrees");
							ProjectorBlockEntity projector = nearest(context.getSource());
							projector.setKeystone(vertical, horizontal);
							context.getSource().sendSuccess(() -> Component.literal(String.format(
								"projector keystone = %.1f° / %.1f°", vertical, horizontal)), false);
							return 1;
						}))))
			.then(Commands.literal("brightness")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_BRIGHTNESS, ProjectorBlockEntity.MAX_BRIGHTNESS))
					.executes(context -> {
						double value = DoubleArgumentType.getDouble(context, "value");
						ProjectorBlockEntity projector = nearest(context.getSource());
						projector.setBrightness(value);
						context.getSource().sendSuccess(() -> Component.literal(
							String.format("projector brightness = %.2f", projector.brightness())), false);
						return 1;
					})))
			.then(Commands.literal("wash")
				.then(Commands.argument("value", DoubleArgumentType.doubleArg(
						ProjectorBlockEntity.MIN_AMBIENT_WASH, ProjectorBlockEntity.MAX_AMBIENT_WASH))
					.executes(context -> {
						double value = DoubleArgumentType.getDouble(context, "value");
						ProjectorBlockEntity projector = nearest(context.getSource());
						projector.setAmbientWash(value);
						context.getSource().sendSuccess(() -> Component.literal(
							String.format("projector ambient wash = %.2f", projector.ambientWash())), false);
						return 1;
					})))
			.then(Commands.literal("power")
				.executes(context -> {
					ProjectorBlockEntity projector = nearest(context.getSource());
					projector.setPowered(!projector.isPowered());
					context.getSource().sendSuccess(() -> Component.literal(
						"projector power = " + (projector.isPowered() ? "on" : "off")), false);
					return 1;
				})));
	}

	private static ProjectorBlockEntity nearest(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerLevel level = source.getLevel();
		Vec3 origin = source.getPosition();
		BlockPos originPos = BlockPos.containing(origin);
		ChunkPos centerChunk = new ChunkPos(originPos);
		int radiusChunks = (int) Math.ceil(SEARCH_RADIUS / 16.0);

		ProjectorBlockEntity best = null;
		double bestDistanceSqr = SEARCH_RADIUS * SEARCH_RADIUS;
		for (int chunkX = centerChunk.x - radiusChunks; chunkX <= centerChunk.x + radiusChunks; chunkX++) {
			for (int chunkZ = centerChunk.z - radiusChunks; chunkZ <= centerChunk.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof ProjectorBlockEntity projector)) {
						continue;
					}
					double distanceSqr = Vec3.atCenterOf(projector.getBlockPos()).distanceToSqr(origin);
					if (distanceSqr < bestDistanceSqr) {
						bestDistanceSqr = distanceSqr;
						best = projector;
					}
				}
			}
		}
		if (best == null) {
			throw NO_PROJECTOR.create();
		}
		return best;
	}
}
