package com.example.cinema.client.audio.fir;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cache and async designer for the common direct/reflection FIR.
 *
 * <p>The profile is already quantized, so it is used directly as the cache
 * key. Designs run on one daemon worker; callers get the cached filter
 * immediately, or {@code null} while the first design is pending.
 */
public final class CommonAcousticFirService {
	private static final int MAX_CACHE_ENTRIES = 1_024;
	private static final double FIR_WARNING_DB = 2.0;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
		runnable -> {
			Thread thread = new Thread(runnable, "cinema-acoustic-fir");
			thread.setDaemon(true);
			return thread;
		});
	private static final ConcurrentHashMap<CommonAcousticFirProfile,
		CompletableFuture<MinimumPhaseFir>> CACHE = new ConcurrentHashMap<>();

	private CommonAcousticFirService() {
	}

	public static MinimumPhaseFir get(CommonAcousticFirProfile profile) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		if (CACHE.size() > MAX_CACHE_ENTRIES) {
			CACHE.clear();
		}
		CompletableFuture<MinimumPhaseFir> future = CACHE.computeIfAbsent(profile,
			ignored -> CompletableFuture.supplyAsync(() -> design(profile), EXECUTOR));
		try {
			return future.getNow(null);
		} catch (CompletionException e) {
			CACHE.remove(profile);
			Cinema.LOGGER.warn("cinema fir: design failed for {}: {}", profile,
				e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	private static MinimumPhaseFir design(CommonAcousticFirProfile profile) {
		long started = System.nanoTime();
		MinimumPhaseFir fir = CommonAcousticFirDesigner.design(profile);
		long elapsedMicros = (System.nanoTime() - started) / 1_000L;
		if (elapsedMicros > 20_000L) {
			Cinema.LOGGER.warn("cinema fir: design took {} ms for {}",
				String.format(Locale.ROOT, "%.1f", elapsedMicros / 1_000.0), profile);
		}
		if (fir.maxErrorDb() > FIR_WARNING_DB) {
			Cinema.LOGGER.warn("cinema fir: {} taps={} maxErr={} dB exceeds {} dB",
				profile.id(), fir.length(),
				String.format(Locale.ROOT, "%.2f", fir.maxErrorDb()),
				String.format(Locale.ROOT, "%.1f", FIR_WARNING_DB));
		}
		return fir;
	}

	public static void clearCache() {
		CACHE.clear();
		MaterialReflectionCurve.clearCache();
	}

	public static int cacheSize() {
		return CACHE.size();
	}
}
