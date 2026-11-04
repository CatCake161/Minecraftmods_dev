package com.example.cinema.client.render;

import com.example.cinema.Cinema;
import com.example.cinema.block.ProjectorBlock;
import com.example.cinema.block.entity.ProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
 * <p>Geometry is recomputed on the render thread from the block entity and its
 * explicitly linked screen. Projectors no longer search for the nearest screen;
 * screens without a link stay dark until the Cinema Cable pairs them.
 */
public final class ProjectorOptics {
	private static final double MAX_RANGE = 48.0;
	private static final long PAIRING_CACHE_MS = 250L;
	private static final float BASE_IMAGE_ASPECT = 16.0f / 9.0f;

	private static final Map<PairingKey, PairingCache> pairingCache = new HashMap<>();

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

	/** Finds the projector explicitly linked to this screen, or null. */
	public static ProjectorOptics forScreen(Level level, ScreenGeometry screen, float videoAspect) {
		PairingKey key = new PairingKey(level.dimension(), screen.master(),
			Float.floatToIntBits(videoAspect));
		long now = System.nanoTime() / 1_000_000L;
		PairingCache cached = pairingCache.get(key);
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

		pairingCache.put(key, new PairingCache(now, best));
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
		double throwRatio = projector.throwRatio() > 0.0
			? projector.throwRatio()
			: autoThrowRatio(screen, view, aspect, distance);
		double tanHalfHorizontal = 0.5 / throwRatio;
		float fovY = (float) (2.0 * Math.atan(tanHalfHorizontal / aspect));
		Matrix4f projection = new Matrix4f().perspective(fovY, (float) aspect, 0.05f, 512.0f);

		double focusDistance = projector.focusDistance() > 0.0
			? projector.focusDistance()
			: distance;
		double focusError = Math.abs(distance - focusDistance);
		float blurLod = projector.isSimFocus()
			? (float) Math.min(3.0, Math.max(0.0, (focusError - 0.35) * 1.1))
			: 0.0f;
		float brightness = projector.opticalBrightness();
		float ambientWash = projector.isSimWash() ? (float) projector.ambientWash() : 0.0f;

		return new ProjectorOptics(projector, screen, lens, center, view, projection, throwRatio,
			focusDistance, focusError, blurLod, brightness, ambientWash);
	}

	/**
	 * Fits the complete screen rectangle inside the video frame, including when
	 * the screen aspect differs from the video aspect or the projector is
	 * installed obliquely. Uses the maximum angular extent of the four screen
	 * corners; the old width-only formula could crop the image.
	 */
	private static double autoThrowRatio(ScreenGeometry screen, Matrix4f view,
										 double aspect, double distance) {
		double[] extents = screenAngularExtents(screen, view);
		if (extents != null) {
			double tanHalfHorizontal = Math.min(extents[0], extents[1] * aspect) * 0.98;
			if (tanHalfHorizontal > 1.0e-4 && Double.isFinite(tanHalfHorizontal)) {
				return clamp(0.5 / tanHalfHorizontal,
					ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO);
			}
		}
		// Fallback for a corner behind the lens: fit width and height in block
		// space, still respecting the video aspect ratio.
		double fitWidth = Math.min(screen.width(), screen.height() * aspect);
		return clamp(distance / Math.max(0.1, fitWidth * 0.98),
			ProjectorBlockEntity.MIN_THROW_RATIO, ProjectorBlockEntity.MAX_THROW_RATIO);
	}

	private static double[] screenAngularExtents(ScreenGeometry screen, Matrix4f view) {
		Vec3 origin = screen.surfaceOrigin();
		Vec3 right = screen.right();
		Vec3 up = screen.up();
		double maxX = 0.0;
		double maxY = 0.0;
		for (int i = 0; i < 4; i++) {
			Vec3 corner = origin
				.add(right.scale((i & 1) == 0 ? 0.0 : screen.width()))
				.add(up.scale((i & 2) == 0 ? 0.0 : screen.height()));
			Vector3f point = new Vector3f((float) corner.x, (float) corner.y, (float) corner.z);
			view.transformPosition(point);
			double depth = -point.z;
			if (depth <= 0.05) {
				return null;
			}
			maxX = Math.max(maxX, Math.abs(point.x) / depth);
			maxY = Math.max(maxY, Math.abs(point.y) / depth);
		}
		return new double[]{maxX, maxY};
	}

	private static ScreenGeometry findScreen(Level level, ProjectorBlockEntity projector) {
		BlockPos explicit = projector.screenPos();
		if (explicit == null) {
			return null;
		}
		Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);
		Vec3 forward = new Vec3(facing.getStepX(), 0.0, facing.getStepZ());
		Vec3 lens = projector.lensPosition();
		BlockState explicitState = level.getBlockState(explicit);
		if (!explicitState.is(Cinema.SCREEN_BLOCK)) {
			return null;
		}
		ScreenGeometry geometry = ScreenGeometry.find(level, explicit, explicitState);
		Vec3 center = geometry.center();
		Vec3 toScreen = center.subtract(lens);
		double distance = toScreen.length();
		if (distance < 1.0 || distance > MAX_RANGE) {
			return null;
		}
		if (lens.subtract(center).dot(geometry.normal()) <= 0.05) {
			return null;
		}
		if (center.subtract(lens).dot(forward) <= 0.05) {
			return null;
		}
		return geometry;
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
		if (!projector.isSimKeystone()) {
			return new float[]{u, v};
		}

		double vertical = Math.tan(Math.toRadians(projector.keystoneVerticalDegrees()));
		double horizontal = Math.tan(Math.toRadians(projector.keystoneHorizontalDegrees()));
		float warpedU = (float) (0.5 + (u - 0.5) * (1.0 + vertical * (v - 0.5) * 2.0));
		float warpedV = (float) (0.5 + (v - 0.5) * (1.0 + horizontal * (u - 0.5) * 2.0));
		return new float[]{warpedU, warpedV};
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
		pairingCache.clear();
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
