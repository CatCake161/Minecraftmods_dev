package com.example.cinema.client.audio.directivity;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side cache for per-direction minimum-phase FIRs.
 *
 * <p>Designing a 4096-tap filter needs several 16384-point FFTs, so it runs on
 * a single daemon worker. Callers get the cached filter immediately, or
 * {@code null} while the first design is pending; they keep the previous
 * filter in that case. Angles are quantised to 5 degrees to bound the cache.
 */
public final class SpeakerDirectivityService {
	private static final int ANGLE_BUCKET_DEGREES = 5;
	private static final int MAX_CACHE_ENTRIES = 1024;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
		runnable -> {
			Thread thread = new Thread(runnable, "cinema-directivity-fir");
			thread.setDaemon(true);
			return thread;
		});
	private static final ConcurrentHashMap<Key, CompletableFuture<MinimumPhaseFir>> CACHE =
		new ConcurrentHashMap<>();

	private SpeakerDirectivityService() {
	}

	/**
	 * CTA-2034-A-style angles from the speaker to the listener. Positive
	 * horizontal is listener-right when facing the speaker; positive vertical
	 * is up. The speaker forward vector is horizontal.
	 */
	public static Angles angles(Vec3 speakerPosition, Vec3 forward, Vec3 listener) {
		Vec3 toListener = listener.subtract(speakerPosition);
		double horizontalDistance = Math.hypot(toListener.x, toListener.z);
		double horizontal;
		if (horizontalDistance < 1.0e-6) {
			horizontal = 0.0;
		} else {
			Vec3 direction = new Vec3(toListener.x, 0.0, toListener.z)
				.scale(1.0 / horizontalDistance);
			Vec3 right = new Vec3(0.0, 1.0, 0.0).cross(forward);
			if (right.lengthSqr() < 1.0e-9) {
				right = new Vec3(1.0, 0.0, 0.0);
			} else {
				right = right.normalize();
			}
			horizontal = Math.toDegrees(Math.atan2(direction.dot(right),
				direction.dot(forward)));
		}
		double vertical = Math.toDegrees(Math.atan2(toListener.y, horizontalDistance));
		return new Angles(horizontal, vertical);
	}

	public record Angles(double horizontal, double vertical) {
	}

	/**
	 * Returns a cached FIR immediately, or {@code null} while the design is
	 * pending. The actual direction may be up to half the bucket size away.
	 */
	public static MinimumPhaseFir get(SpeakerDataProfile profile,
									  double horizontalDegrees,
									  double verticalDegrees) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		int h = quantizeHorizontal(horizontalDegrees);
		int v = quantizeVertical(verticalDegrees);
		Key key = new Key(profile.id(), System.identityHashCode(profile), h, v);
		if (CACHE.size() > MAX_CACHE_ENTRIES) {
			CACHE.clear();
		}
		CompletableFuture<MinimumPhaseFir> future = CACHE.computeIfAbsent(key,
			ignored -> CompletableFuture.supplyAsync(() -> {
				MinimumPhaseFir design = MinimumPhaseFirDesigner.design(profile, h, v);
				if (design.maxErrorDb() > 1.5) {
					Cinema.LOGGER.warn(
						"cinema directivity: {} h={} v={} FIR error {} dB exceeds 1.5 dB",
						profile.id(), h, v,
						String.format(java.util.Locale.ROOT, "%.2f", design.maxErrorDb()));
				}
				return design;
			}, EXECUTOR));
		try {
			return future.getNow(null);
		} catch (CompletionException e) {
			CACHE.remove(key);
			Cinema.LOGGER.warn("cinema directivity: FIR design failed for {} h={} v={}: {}",
				profile.id(), h, v, e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static int cacheSize() {
		return CACHE.size();
	}

	private static int quantizeHorizontal(double value) {
		double wrapped = value % 360.0;
		if (wrapped > 180.0) {
			wrapped -= 360.0;
		} else if (wrapped < -180.0) {
			wrapped += 360.0;
		}
		int quantized = (int) Math.round(wrapped / ANGLE_BUCKET_DEGREES)
			* ANGLE_BUCKET_DEGREES;
		if (quantized > 180) {
			quantized -= 360;
		}
		return quantized;
	}

	private static int quantizeVertical(double value) {
		int quantized = (int) Math.round(value / ANGLE_BUCKET_DEGREES)
			* ANGLE_BUCKET_DEGREES;
		return Math.max(-90, Math.min(90, quantized));
	}

	private record Key(String profileId, int profileIdentity, int horizontal,
					   int vertical) {
	}
}
