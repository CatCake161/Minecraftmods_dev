package com.example.cinema.client.media.audio.acoustics.geometry;

import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionResult;
import com.example.cinema.client.media.audio.acoustics.propagation.OcclusionScanner;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterials;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

/**
 * World-facing reflection validation.
 *
 * <p>Wall existence is intentionally separate from direct-path occlusion:
 * glass and screens are valid reflectors, while air, doorways and mod
 * furniture are not. Path occlusion uses the same scanner/transparency rules
 * as the direct path, so screens/furniture are transparent and glass/wood/stone
 * block; blocked taps are attenuated rather than rejected.
 */
public final class ReflectionProbe implements ImageSourceSolver.World {
	private static final double WALL_PROBE_INSET =
		MirrorPlane.COPLANAR_TOLERANCE + 0.02;
	private static final double WALL_PROBE_OUTSET =
		MirrorPlane.COPLANAR_TOLERANCE + 0.02;
	private static final double WALL_PROBE_MAX_DISTANCE =
		MirrorPlane.COPLANAR_TOLERANCE + 0.03;

	private final BlockGetter level;

	public ReflectionProbe(BlockGetter level) {
		this.level = level;
	}

	public static ReflectionProbe of(BlockGetter level) {
		return new ReflectionProbe(level);
	}

	@Override
	public AcousticMaterial wallAt(MirrorPlane plane, Point3 point) {
		if (level == null || plane == null || point == null) {
			return null;
		}
		Point3 normal = plane.normalVector();
		Vec3 from = toVec(point.add(normal.scale(WALL_PROBE_INSET)));
		Vec3 to = toVec(point.subtract(normal.scale(WALL_PROBE_OUTSET)));
		BlockHitResult hit = level.clip(new ClipContext(from, to,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
			CollisionContext.empty()));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		if (hit.getLocation().distanceTo(toVec(point)) > WALL_PROBE_MAX_DISTANCE) {
			return null;
		}
		BlockState state = level.getBlockState(hit.getBlockPos());
		if (state.isAir() || AcousticMaterials.isIgnoredObstacle(state)
			|| state.getCollisionShape(level, hit.getBlockPos()).isEmpty()) {
			return null;
		}
		if (!matches(plane, hit.getDirection())) {
			return null;
		}
		return AcousticMaterials.forState(state);
	}

	@Override
	public OcclusionResult occlusion(Point3 from, Point3 to) {
		return OcclusionScanner.scan(level, toVec(from), toVec(to));
	}

	private static boolean matches(MirrorPlane plane, Direction face) {
		int sign = plane.normal() > 0.0 ? 1 : -1;
		return switch (plane.axis()) {
			case X -> face.getAxis() == Direction.Axis.X && face.getStepX() == sign;
			case Y -> face.getAxis() == Direction.Axis.Y && face.getStepY() == sign;
			case Z -> face.getAxis() == Direction.Axis.Z && face.getStepZ() == sign;
		};
	}

	private static Vec3 toVec(Point3 point) {
		return new Vec3(point.x(), point.y(), point.z());
	}
}
