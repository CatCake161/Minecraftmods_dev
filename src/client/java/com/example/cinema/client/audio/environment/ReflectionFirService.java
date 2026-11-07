package com.example.cinema.client.audio.environment;

import com.example.cinema.Cinema;
import com.example.cinema.client.audio.directivity.MinimumPhaseFir;
import com.example.cinema.client.audio.directivity.SpeakerDirectivityService;
import com.example.cinema.client.audio.speakerdata.SpeakerDataProfile;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client-side cache for 2048-tap reflection-path FIRs.
 *
 * <p>Requests are quantized before lookup: angles in 5 degree steps, path
 * length in 0.5 m steps, temperature in 0.5 C steps, humidity in 2 % steps,
 * pressure in 0.1 kPa steps and Schroeder frequency in 10 Hz steps. Design runs
 * on one daemon worker; callers get the cached filter immediately, or
 * {@code null} while the first design is pending.
 */
public final class ReflectionFirService {
	private static final double PATH_BUCKET_METERS = 0.5;
	private static final double TEMPERATURE_BUCKET_C = 0.5;
	private static final double HUMIDITY_BUCKET_PERCENT = 2.0;
	private static final double PRESSURE_BUCKET_KPA = 0.1;
	private static final double SCHROEDER_BUCKET_HZ = 10.0;
	private static final int MAX_CACHE_ENTRIES = 512;
	private static final double FIR_WARNING_DB = 2.0;
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
		runnable -> {
			Thread thread = new Thread(runnable, "cinema-reflection-fir");
			thread.setDaemon(true);
			return thread;
		});
	private static final ConcurrentHashMap<Key, CompletableFuture<MinimumPhaseFir>> CACHE =
		new ConcurrentHashMap<>();

	private ReflectionFirService() {
	}

	public static MinimumPhaseFir get(ReflectionFirProfile profile) {
		if (profile == null) {
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
			Cinema.LOGGER.warn("cinema reflection: FIR design failed for {}: {}",
				key, e.getCause() == null ? e.toString() : e.getCause().toString());
			return null;
		}
	}

	private static MinimumPhaseFir design(Key key) {
		long started = System.nanoTime();
		MinimumPhaseFir fir = ReflectionFirDesigner.design(key.toProfile());
		long elapsedMicros = (System.nanoTime() - started) / 1_000L;
		if (elapsedMicros > 20_000L) {
			Cinema.LOGGER.warn("cinema reflection: FIR design took {} ms for {}",
				String.format(Locale.ROOT, "%.1f", elapsedMicros / 1_000.0), key);
		}
		if (fir.maxErrorDb() > FIR_WARNING_DB) {
			Cinema.LOGGER.warn(
				"cinema reflection: {} h={} v={} fs={} FIR error {} dB exceeds {} dB",
				key.profileId(), key.horizontal(), key.vertical(),
				key.schroederBucket() * SCHROEDER_BUCKET_HZ,
				String.format(Locale.ROOT, "%.2f", fir.maxErrorDb()),
				String.format(Locale.ROOT, "%.1f", FIR_WARNING_DB));
		}
		return fir;
	}

	public static void clearCache() {
		CACHE.clear();
	}

	public static int cacheSize() {
		return CACHE.size();
	}

	private record Key(SpeakerDataProfile speaker, int horizontal, int vertical,
					   int pathBucket, boolean airAbsorption, int temperatureBucket,
					   int humidityBucket, int pressureBucket, int schroederBucket) {
		static Key of(ReflectionFirProfile profile) {
			return new Key(profile.speaker(),
				SpeakerDirectivityService.quantizeHorizontal(
					profile.horizontalDegrees()),
				SpeakerDirectivityService.quantizeVertical(profile.verticalDegrees()),
				bucket(profile.pathLengthMeters(), PATH_BUCKET_METERS),
				profile.airAbsorption(),
				bucket(profile.temperatureC(), TEMPERATURE_BUCKET_C),
				bucket(profile.humidityPercent(), HUMIDITY_BUCKET_PERCENT),
				bucket(profile.pressureKpa(), PRESSURE_BUCKET_KPA),
				bucket(profile.schroederHz(), SCHROEDER_BUCKET_HZ));
		}

		ReflectionFirProfile toProfile() {
			return new ReflectionFirProfile(speaker, horizontal, vertical,
				pathBucket * PATH_BUCKET_METERS, airAbsorption,
				temperatureBucket * TEMPERATURE_BUCKET_C,
				humidityBucket * HUMIDITY_BUCKET_PERCENT,
				pressureBucket * PRESSURE_BUCKET_KPA,
				schroederBucket * SCHROEDER_BUCKET_HZ);
		}

		String profileId() {
			return speaker == null ? "flat" : speaker.id();
		}

		private static int bucket(double value, double size) {
			return (int) Math.round(value / size);
		}
	}
}
