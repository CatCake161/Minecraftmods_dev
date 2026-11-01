package com.example.firstmod.client.render;

import com.example.firstmod.block.ProjectorBlock;
import com.example.firstmod.block.entity.ProjectorBlockEntity;
import com.example.firstmod.block.entity.ScreenBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolved projector installation for one frame: lens pose, throw cone,
 * focus, keystone warp and ambient wash.
 *
 * <p>Geometry is recomputed on the render thread from the block entity and the
 * paired screen. Pairing searches loaded chunks around the screen, so moving or
 * replacing a screen is picked up without an explicit link item.
 */
public final class ProjectorOptics {
	private static final double MAX_RANGE = 48.0;
	private static final long PAIRING_CACHE_MS = 250L;
	private static final float MIN_FACING_DOT = 0.20f;
	private static final float BASE_IMAGE_ASPECT = 16.0f / 9.0f;

	private static final Map<PairingKey, PairingCache> PAIRING_CACHE = new HashMap<>();

	private final ProjectorBlockEntity projector;
	private final ScreenGeometry screen;
	private final Vec3 lens;
	private final Vec3 screenCenter;
	private final Matrix4f view;
	private final Matrix4f projection;
	private final double throwRatio;
	private final double focusDistance;
	private final double focusError;
	private final float blurLod;
	private final float brightness;
	private final float ambientWash;

	private ProjectorOptics(ProjectorBlockEntity projector, ScreenGeometry screen, Vec3 lens,
							Vec3 screenCenter, Matrix4f view, Matrix4f projection, double throwRatio,
							double focusDistance, double focusError, float blurLod, float brightness,
							float ambientWash) {
		this.projector = projector;
		this.screen = screen;
		this.lens = lens;
		this.screenCenter = screenCenter;
		this.view = view;
		this.projection = projection;
		this.throwRatio = throwRatio;
		this.focusDistance = focusDistance;
		this.focusError = focusError;
		this.blurLod = blurLod;
		this.brightness = brightness;
		this.ambientWash = ambientWash;
	}

	/**
	 * Finds the projector that currently feeds this screen. A short cache keeps
	 * the chunk scan out of the per-frame path while still reacting to block
	 * changes quickly.
	 */
	public static ProjectorOptics forScreen(Level level, ScreenGeometry screen, float videoAspect) {
		PairingKey key = new PairingKey(level.dimension(), screen.master(),
			Float.floatToIntBits(videoAspect));
		long now = System.nanoTime() / 1_000_000L;
		PairingCache cached = PAIRING_CACHE.get(key);
		if (cached != null && now - cached.atMs <= PAIRING_CACHE_MS) {
			return cached.optics;
		}

		ProjectorOptics best = null;
		double bestDistance = Double.MAX_VALUE;
		Vec3 center = screen.center();
		BlockPos centerPos = BlockPos.containing(center);
		ChunkPos centerChunk = new ChunkPos(centerPos);
		int radiusChunks = (int) Math.ceil(MAX_RANGE / 16.0);
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
					ProjectorOptics candidate = resolve(level, projector, videoAspect);
					if (candidate == null || !candidate.screen.master().equals(screen.master())) {
						continue;
					}
					double distance = candidate.lens.distanceToSqr(center);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = candidate;
					}
				}
			}
		}

		PAIRING_CACHE.put(key, new PairingCache(now, best));
		return best;
	}

	/** Resolves one projector to the screen it is aimed at, or null. */
	public static ProjectorOptics resolve(Level level, ProjectorBlockEntity projector, float videoAspect) {
		ScreenGeometry screen = findScreen(level, projector);
		if (screen == null) {
			return null;
		}
		Vec3 lens = projector.lensPosition();
		Vec3 center = screen.center();
		Vec3 toCenter = center.subtract(lens);
		double distance = toCenter.length();
		if (distance < 0.5) {
			return null;
		}

		Vec3 axis = toCenter.scale(1.0 / distance);
		Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);
		if (Math.abs(axis.dot(worldUp)) > 0.98) {
			worldUp = screen.up();
		}
		Matrix4f view = new Matrix4f().lookAt(
			new Vector3f((float) lens.x, (float) lens.y, (float) lens.z),
			new Vector3f((float) center.x, (float) center.y, (float) center.z),
			new Vector3f((float) worldUp.x, (float) worldUp.y, (float) worldUp.z));

		double aspect = videoAspect > 0.01f ? videoAspect : BASE_IMAGE_ASPECT;
		double throwRatio = projector.throwRatio();
		double tanHalfHorizontal = 0.5 / throwRatio;
		float fovY = (float) (2.0 * Math.atan(tanHalfHorizontal / aspect));
		Matrix4f projection = new Matrix4f().perspective(fovY, (float) aspect, 0.05f, 512.0f);

		double focusDistance = projector.focusDistance() > 0.0
			? projector.focusDistance()
			: distance;
		double focusError = Math.abs(distance - focusDistance);
		float blurLod = (float) Math.min(3.0, Math.max(0.0, (focusError - 0.35) * 1.1));
		float brightness = projector.opticalBrightness();
		float ambientWash = ambientWash(level, screen) / Math.max(1.0f, brightness);

		return new ProjectorOptics(projector, screen, lens, center, view, projection, throwRatio,
			focusDistance, focusError, blurLod, brightness, ambientWash);
	}

	private static ScreenGeometry findScreen(Level level, ProjectorBlockEntity projector) {
		Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);
		Vec3 forward = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
		Vec3 lens = projector.lensPosition();
		BlockPos projectorPos = projector.getBlockPos();
		ChunkPos centerChunk = new ChunkPos(projectorPos);
		int radiusChunks = (int) Math.ceil(MAX_RANGE / 16.0);

		ScreenGeometry best = null;
		double bestScore = Double.MAX_VALUE;
		for (int chunkX = centerChunk.x - radiusChunks; chunkX <= centerChunk.x + radiusChunks; chunkX++) {
			for (int chunkZ = centerChunk.z - radiusChunks; chunkZ <= centerChunk.z + radiusChunks; chunkZ++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
					if (!(blockEntity instanceof ScreenBlockEntity screenBlock)) {
						continue;
					}
					ScreenGeometry geometry = ScreenGeometry.find(level,
						screenBlock.getBlockPos(), screenBlock.getBlockState());
					if (!geometry.master().equals(screenBlock.getBlockPos())) {
						continue;
					}
					Vec3 center = geometry.center();
					Vec3 toScreen = center.subtract(lens);
					double distance = toScreen.length();
					if (distance < 1.0 || distance > MAX_RANGE) {
						continue;
					}
					Vec3 direction = toScreen.scale(1.0 / distance);
					double facingDot = direction.dot(forward);
					if (facingDot < MIN_FACING_DOT) {
						continue;
					}
					// The projector must be in front of the screen, not behind it.
					if (lens.subtract(center).dot(geometry.normal()) <= 0.05) {
						continue;
					}
					double score = distance / Math.max(0.1, facingDot);
					if (score < bestScore) {
						bestScore = score;
						best = geometry;
					}
				}
			}
		}
		return best;
	}

	/**
	 * Projects a world point through the lens and applies the keystone warp.
	 * Returns image-space UV; values outside 0..1 are clipped by the renderer.
	 */
	public float[] uv(Vec3 point) {
		Vector4f clip = new Vector4f((float) point.x, (float) point.y, (float) point.z, 1.0f);
		clip.mul(view).mul(projection);
		if (Math.abs(clip.w) < 1.0e-6f) {
			return new float[]{-1.0f, -1.0f};
		}
		float invW = 1.0f / clip.w;
		float u = (clip.x * invW + 1.0f) * 0.5f;
		float v = 1.0f - (clip.y * invW + 1.0f) * 0.5f;

		double vertical = Math.tan(Math.toRadians(projector.keystoneVerticalDegrees()));
		double horizontal = Math.tan(Math.toRadians(projector.keystoneHorizontalDegrees()));
		float warpedU = (float) (0.5 + (u - 0.5) * (1.0 + vertical * (v - 0.5) * 2.0));
		float warpedV = (float) (0.5 + (v - 0.5) * (1.0 + horizontal * (u - 0.5) * 2.0));
		return new float[]{warpedU, warpedV};
	}

	private static float ambientWash(Level level, ScreenGeometry screen) {
		BlockPos pos = BlockPos.containing(screen.center());
		int sky = level.getBrightness(LightLayer.SKY, pos);
		int block = level.getBrightness(LightLayer.BLOCK, pos);
		double daylight = Math.max(0.0, Math.min(1.0, 1.0 - level.getSkyDarken() / 11.0));
		double skyNorm = sky / 15.0;
		double blockNorm = block / 15.0;
		double wash = 0.02 + 0.16 * skyNorm * skyNorm * daylight + 0.08 * blockNorm * blockNorm;
		return (float) Math.min(0.28, wash);
	}

	public ProjectorBlockEntity projector() {
		return projector;
	}

	public ScreenGeometry screen() {
		return screen;
	}

	public Vec3 lens() {
		return lens;
	}

	public Vec3 screenCenter() {
		return screenCenter;
	}

	public double throwRatio() {
		return throwRatio;
	}

	public double focusDistance() {
		return focusDistance;
	}

	public double focusError() {
		return focusError;
	}

	public float blurLod() {
		return blurLod;
	}

	public float brightness() {
		return brightness;
	}

	public float ambientWash() {
		return ambientWash;
	}

	private record PairingKey(ResourceKey<Level> dimension, BlockPos master, int aspectBits) {
	}

	private record PairingCache(long atMs, ProjectorOptics optics) {
	}

	public static void clearCache() {
		PAIRING_CACHE.clear();
	}
}
