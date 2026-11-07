package com.example.cinema.client.audio.environment;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side cache for direct-path environment FIRs.
 *
 * <p>Designing a 2048-tap filter with 8192-point FFTs runs on a single daemon
 * worker, never on the audio thread. Requests are quantised before lookup:
 * distance in 0.5 m steps, temperature in 0.5 C steps, humidity in 2 % steps
 * and occlusion cutoff in 100 Hz steps. Callers get the cached filter
 * immediately, or {@code null} while the first design is pending; they keep the
 * previous filter in that case.
 */
public final class EnvironmentFirService {
	private static final double DISTANCE_BUCKET_METERS = 0.5;
	private static final double TEMPERATURE_BUCKET_C = 0.5;
	private static final double HUMIDITY_BUCKET_PERCENT = 2.0;
	private static final int CUTOFF_BUCKET_HZ = 100;
	private static final int MAX_CACHE_ENTRIES = 512;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
		runnable -> {
			Thread thread = new Thread(runnable, "cinema-environment-fir");
			thread.setDaemon(true);
			return thread;
		});
	private static final ConcurrentHashMap<Key, CompletableFuture<MinimumPhaseFir>> CACHE =
		new ConcurrentHashMap<>();

	private EnvironmentFirService() {
	}

	/**
	 * Returns a cached FIR immediately, or {@code null} while the design is
	 * pending. The profile is quantised internally, so callers may pass the raw
	 * per-tick distance and weather values.
	 */
	public static MinimumPhaseFir get(EnvironmentProfile profile) {
		if (profile == null || profile.isFlat()) {
			return MinimumPhaseFir.FLAT;
		}
		Key key = Key.of(profile);
		if (CACHE.size() > MAX_CACHE_ENTRIES) {
			CACHE.clear();
		}
		CompletableFuture<MinimumPhaseFir> future = CACHE.computeIfAbsent(key,
			ignored -> CompletableFuture.supplyAsync(() -> design(key), EXECUTOR));
		try {
			return future.getNow(null);
		} catch (CompletionException e) {
			CACHE.remove(key);
			Cinema.LOGGER.warn("cinema environment: FIR design failed for {}: {}",
				key, e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	private static MinimumPhaseFir design(Key key) {
		long started = System.nanoTime();
		MinimumPhaseFir fir = EnvironmentFirDesigner.design(key.toProfile());
		long elapsedMicros = (System.nanoTime() - started) / 1_000L;
		if (elapsedMicros > 20_000L) {
			Cinema.LOGGER.warn("cinema environment: FIR design took {} ms for {}",
				String.format(Locale.ROOT, "%.1f", elapsedMicros / 1_000.0), key);
		}
		return fir;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static int cacheSize() {
		return CACHE.size();
	}

	private record Key(int distanceBucket, boolean airAbsorption,
					   int temperatureBucket, int humidityBucket,
					   int pressureBucket, int cutoffBucket, int order) {
		static Key of(EnvironmentProfile profile) {
			return new Key(
				bucket(profile.distanceMeters(), DISTANCE_BUCKET_METERS),
				profile.airAbsorption(),
				bucket(profile.temperatureC(), TEMPERATURE_BUCKET_C),
				bucket(profile.humidityPercent(), HUMIDITY_BUCKET_PERCENT),
				bucket(profile.pressureKpa(), 0.1),
				bucket(profile.occlusionCutoffHz(), CUTOFF_BUCKET_HZ),
				profile.occlusionOrder());
		}

		EnvironmentProfile toProfile() {
			return new EnvironmentProfile(
				distanceBucket * DISTANCE_BUCKET_METERS,
				airAbsorption,
				temperatureBucket * TEMPERATURE_BUCKET_C,
				humidityBucket * HUMIDITY_BUCKET_PERCENT,
				pressureBucket * 0.1,
				cutoffBucket * (double) CUTOFF_BUCKET_HZ,
				order);
		}

		private static int bucket(double value, double size) {
			return (int) Math.round(value / size);
		}
	}
}
