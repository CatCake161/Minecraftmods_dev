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
		/** Reused [entry, exit] distances; avoids per-box allocations. */
		private final double[] span = new double[2];

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
			// VoxelShape coordinates are block-local (usually 0..1) while the ray
			// is in world space. Shifting the origin by -pos is equivalent to
			// moving every collision box and avoids allocating one AABB per box.
			double localX = origin.x - pos.getX();
			double localY = origin.y - pos.getY();
			double localZ = origin.z - pos.getZ();
			for (AABB box : shape.toAabbs()) {
				if (!clipBox(box, localX, localY, localZ,
					direction.x, direction.y, direction.z, distance, span)) {
					continue;
				}
				double length = span[1] - span[0];
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
		/** Reused [entry, exit] distances; avoids per-box allocations. */
		private final double[] span = new double[2];

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
			// Shift the ray into block-local space instead of moving the boxes.
			double localX = origin.x - pos.getX();
			double localY = origin.y - pos.getY();
			double localZ = origin.z - pos.getZ();
			for (AABB box : shape.toAabbs()) {
				if (clipBox(box, localX, localY, localZ,
					direction.x, direction.y, direction.z, distance, span)
					&& span[1] - span[0] > EPSILON) {
					return true;
				}
			}
			return false;
		}
	}

	private static boolean clipBox(AABB box, double originX, double originY,
								   double originZ, double directionX,
								   double directionY, double directionZ,
								   double distance, double[] span) {
		span[0] = 0.0;
		span[1] = distance;
		if (!clipAxis(originX, directionX, box.minX, box.maxX, span)
			|| !clipAxis(originY, directionY, box.minY, box.maxY, span)
			|| !clipAxis(originZ, directionZ, box.minZ, box.maxZ, span)) {
			return false;
		}
		return span[1] - span[0] > EPSILON;
	}

	private static boolean clipAxis(double origin, double direction,
									double minBound, double maxBound,
									double[] span) {
		if (Math.abs(direction) < EPSILON) {
			return origin >= minBound && origin <= maxBound;
		}
		double t1 = (minBound - origin) / direction;
		double t2 = (maxBound - origin) / direction;
		if (t1 > t2) {
			double swap = t1;
			t1 = t2;
			t2 = swap;
		}
		double entry = Math.max(span[0], t1);
		double exit = Math.min(span[1], t2);
		if (entry > exit) {
			return false;
		}
		span[0] = entry;
		span[1] = exit;
		return true;
	}
}
