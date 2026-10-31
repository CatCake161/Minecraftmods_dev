package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the external FFmpeg installation in the background while the client
 * starts. FFmpeg is a required runtime component; this also makes the install
 * error visible in the log before the user tries to play a file.
 */
public final class CinemaPrewarm {
	private static final CompletableFuture<Void> LOADED = new CompletableFuture<>();

	static {
		Thread thread = new Thread(CinemaPrewarm::load, "cinema-prewarm");
		thread.setDaemon(true);
		thread.start();
	}

	private CinemaPrewarm() {
	}

	public static void start() {
		// Class initialization starts the daemon thread.
	}

	public static boolean await() {
		try {
			LOADED.get(30, TimeUnit.SECONDS);
			return true;
		} catch (Exception e) {
			FirstMod.LOGGER.warn("cinema prewarm did not finish", e);
			return false;
		}
	}

	private static void load() {
		long started = System.nanoTime();
		try {
			String version = Ffmpeg.version();
			LOADED.complete(null);
			FirstMod.LOGGER.info("cinema prewarm: external FFmpeg ready in {} ms: {}",
				String.format("%.0f", (System.nanoTime() - started) / 1e6), version);
		} catch (Throwable t) {
			LOADED.completeExceptionally(t);
			FirstMod.LOGGER.error("cinema prewarm failed: {}", t.getMessage());
			FirstMod.LOGGER.error(Ffmpeg.INSTALL_HINT);
		}
	}
}
