package com.example.cinema.client.media.audio.acoustics.propagation;

import com.example.cinema.client.media.audio.AcousticScale;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.Vec3;

/**
 * Direct-path occlusion scan.
 *
 * <p>{@link BlockGetter#traverseBlocks} walks every block along the segment.
 * For each blocking block we intersect the segment with the block's collision
 * shape and accumulate the actual path length, so a slab or a diagonal crossing
 * contributes proportionally instead of a fixed per-block value.
 *
 * <p>Screens are acoustically transparent for the direct path, and the mod's
 * own furniture (speakers, seats, projector, receiver) is skipped. Runs on the
 * client thread at the speaker-layout update rate, never on the audio thread.
 */
public final class OcclusionScanner {
	private static final double EPSILON = 1.0e-9;

	private OcclusionScanner() {
	}

	public static OcclusionResult scan(BlockGetter level, Vec3 source, Vec3 listener) {
		if (level == null || source == null || listener == null) {
			return OcclusionResult.NONE;
		}
		Vec3 offset = listener.subtract(source);
		double distance = offset.length();
		if (distance < 1.0e-3) {
			return OcclusionResult.NONE;
		}
		Vec3 direction = offset.scale(1.0 / distance);
		Trace trace = new Trace(level, source, direction, distance);
		OcclusionResult result = BlockGetter.traverseBlocks(source, listener, trace,
			(context, pos) -> {
				context.visit(pos);
				return null;
			},
			Trace::result);
		return result == null ? OcclusionResult.NONE : result;
	}

	/**
	 * Short-circuit visibility test for reflection path legs. Uses the same
	 * transparency rules as {@link #scan}: screens and mod furniture are
	 * transparent; any non-transparent collision shape blocks the segment.
	 */
	public static boolean isClear(BlockGetter level, Vec3 from, Vec3 to) {
		if (level == null || from == null || to == null) {
			return false;
		}
		Vec3 offset = to.subtract(from);
		double distance = offset.length();
		if (distance < 1.0e-3) {
			return true;
		}
		Vec3 direction = offset.scale(1.0 / distance);
		ClearTrace trace = new ClearTrace(level, from, direction, distance);
		Boolean clear = BlockGetter.traverseBlocks(from, to, trace,
			(context, pos) -> context.visit(pos) ? Boolean.FALSE : null,
			context -> Boolean.TRUE);
		return Boolean.TRUE.equals(clear);
	}

	private static final class Trace {
		private final BlockGetter level;
		private final Vec3 origin;
		private final Vec3 direction;
		private final double distance;
		private double pathLength;
		private double attenuationDb;
		private double cutoffWeight;
		private int order;

		private Trace(BlockGetter level, Vec3 origin, Vec3 direction,
					  double distance) {
			this.level = level;
			this.origin = origin;
			this.direction = direction;
			this.distance = distance;
		}

		private void visit(BlockPos pos) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				return;
			}
			OcclusionProfile profile = AcousticMaterials.occlusionForState(state);
			if (profile.isTransparent()) {
				return;
			}
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (shape.isEmpty()) {
				return;
			}
			for (AABB box : shape.toAabbs()) {
				// VoxelShape coordinates are block-local (usually 0..1). The ray
				// is in world space, so the collision box must be moved by the
				// block position before clipping. Without this offset every
				// wall outside the block at the world origin is missed.
				double[] segment = clip(box.move(pos.getX(), pos.getY(),
					pos.getZ()));
				if (segment == null) {
					continue;
				}
				double length = segment[1] - segment[0];
				if (length <= EPSILON) {
					continue;
				}
				pathLength += length;
				attenuationDb += profile.attenuationDbPerMeter()
					* AcousticScale.blocksToMeters(length);
				// The cutoff is a length-weighted average, so the block-unit
				// length cancels; no metre conversion is needed here.
				cutoffWeight += length / profile.cutoffHz();
				order = Math.max(order, profile.order());
			}
		}

		/**
		 * Returns the entry/exit distance along the normalized ray for one
		 * collision box, or {@code null} when the segment misses it.
		 */
		private double[] clip(AABB box) {
			return clipBox(box, origin, direction, distance);
		}

		private OcclusionResult result() {
			if (pathLength <= EPSILON || attenuationDb <= 0.0 || order <= 0) {
				return OcclusionResult.NONE;
			}
			double cutoff = pathLength / cutoffWeight;
			return new OcclusionResult(attenuationDb, cutoff, order);
		}
	}

	/** Short-circuit variant used by reflection path legs. */
	private static final class ClearTrace {
		private final BlockGetter level;
		private final Vec3 origin;
		private final Vec3 direction;
		private final double distance;

		private ClearTrace(BlockGetter level, Vec3 origin, Vec3 direction,
						   double distance) {
			this.level = level;
			this.origin = origin;
			this.direction = direction;
			this.distance = distance;
		}

		private boolean visit(BlockPos pos) {
			BlockState state = level.getBlockState(pos);
			if (state.isAir()) {
				return false;
			}
			OcclusionProfile profile = AcousticMaterials.occlusionForState(state);
			if (profile.isTransparent()) {
				return false;
			}
			VoxelShape shape = state.getCollisionShape(level, pos);
			if (shape.isEmpty()) {
				return false;
			}
			for (AABB box : shape.toAabbs()) {
				double[] segment = clipBox(box.move(pos.getX(), pos.getY(),
					pos.getZ()), origin, direction, distance);
				if (segment != null && segment[1] - segment[0] > EPSILON) {
					return true;
				}
			}
			return false;
		}
	}

	private static double[] clipBox(AABB box, Vec3 origin, Vec3 direction,
									double distance) {
		double min = 0.0;
		double max = distance;
		double[] span = clipAxis(origin.x, direction.x, box.minX, box.maxX,
			min, max);
		if (span == null) {
			return null;
		}
		min = span[0];
		max = span[1];
		span = clipAxis(origin.y, direction.y, box.minY, box.maxY, min, max);
		if (span == null) {
			return null;
		}
		min = span[0];
		max = span[1];
		span = clipAxis(origin.z, direction.z, box.minZ, box.maxZ, min, max);
		if (span == null) {
			return null;
		}
		min = span[0];
		max = span[1];
		if (max - min <= EPSILON) {
			return null;
		}
		return new double[]{min, max};
	}

	private static double[] clipAxis(double origin, double direction,
									 double minBound, double maxBound,
									 double min, double max) {
		if (Math.abs(direction) < EPSILON) {
			if (origin < minBound || origin > maxBound) {
				return null;
			}
			return new double[]{min, max};
		}
		double t1 = (minBound - origin) / direction;
		double t2 = (maxBound - origin) / direction;
		if (t1 > t2) {
			double swap = t1;
			t1 = t2;
			t2 = swap;
		}
		double entry = Math.max(min, t1);
		double exit = Math.min(max, t2);
		if (entry > exit) {
			return null;
		}
		return new double[]{entry, exit};
	}
}
