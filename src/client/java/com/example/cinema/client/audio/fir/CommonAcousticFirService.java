package com.example.cinema.client.audio.fir;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;

import java.util.Iterator;
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
 *
 * <p>Direct FIRs depend on angle, distance, air absorption and occlusion, so a
 * moving listener can otherwise queue far more 4096-tap designs than the
 * worker can finish. A token bucket allows an initial burst, then limits new
 * direct designs to five per second; callers keep the previous FIR until the
 * next update. Reflection designs are cheaper and get a higher budget.
 */
public final class CommonAcousticFirService {
	private static final int MAX_CACHE_ENTRIES = 2_048;
	private static final int EVICT_BATCH = 256;
	private static final double FIR_WARNING_DB = 2.0;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
		runnable -> {
			Thread thread = new Thread(runnable, "cinema-acoustic-fir");
			thread.setDaemon(true);
			return thread;
		});
	private static final ConcurrentHashMap<CommonAcousticFirProfile,
		CompletableFuture<MinimumPhaseFir>> CACHE = new ConcurrentHashMap<>();
	private static final TokenBucket DIRECT_BUDGET = new TokenBucket(8.0, 5.0);
	private static final TokenBucket REFLECTION_BUDGET = new TokenBucket(24.0, 20.0);

	private CommonAcousticFirService() {
	}

	public static MinimumPhaseFir get(CommonAcousticFirProfile profile) {
		if (profile == null) {
			return MinimumPhaseFir.FLAT;
		}
		CompletableFuture<MinimumPhaseFir> future = CACHE.get(profile);
		if (future == null) {
			if (!allowDesign(profile.role())) {
				return null;
			}
			evictIfNeeded();
			future = CACHE.computeIfAbsent(profile,
				ignored -> CompletableFuture.supplyAsync(() -> design(profile), EXECUTOR));
		}
		try {
			return future.getNow(null);
		} catch (CompletionException e) {
			CACHE.remove(profile);
			Cinema.LOGGER.warn("cinema fir: design failed for {}: {}", profile,
				e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	private static boolean allowDesign(CommonAcousticFirProfile.Role role) {
		return (role == CommonAcousticFirProfile.Role.DIRECT
			? DIRECT_BUDGET : REFLECTION_BUDGET).tryAcquire();
	}

	private static void evictIfNeeded() {
		int size = CACHE.size();
		if (size <= MAX_CACHE_ENTRIES) {
			return;
		}
		int remove = Math.min(size, size - MAX_CACHE_ENTRIES + EVICT_BATCH);
		Iterator<CommonAcousticFirProfile> iterator = CACHE.keySet().iterator();
		while (iterator.hasNext() && remove-- > 0) {
			iterator.next();
			iterator.remove();
		}
	}

	/** Simple burst + sustained-rate limiter, called from the client thread. */
	private static final class TokenBucket {
		private final double capacity;
		private final double tokensPerSecond;
		private double tokens;
		private long lastNanos = System.nanoTime();

		private TokenBucket(double capacity, double tokensPerSecond) {
			this.capacity = capacity;
			this.tokensPerSecond = tokensPerSecond;
			tokens = capacity;
		}

		private synchronized boolean tryAcquire() {
			long now = System.nanoTime();
			double elapsed = (now - lastNanos) / 1.0e9;
			lastNanos = now;
			tokens = Math.min(capacity, tokens + elapsed * tokensPerSecond);
			if (tokens < 1.0) {
				return false;
			}
			tokens -= 1.0;
			return true;
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
