package com.example.cinema.client.media;

import com.example.cinema.CinemaLog;
import com.example.cinema.client.media.ffmpeg.Ffmpeg;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Verifies the external FFmpeg installation in the background while the client
 * starts. FFmpeg is a required runtime component; this also makes the install
 * error visible in the log before the user tries to play a file.
 */
public final class CinemaPrewarm {
	private static final CompletableFuture<Void> loaded = new CompletableFuture<>();

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
			loaded.get(30, TimeUnit.SECONDS);
			return true;
		} catch (Exception e) {
			CinemaLog.warn(CinemaLog.PREWARM, "did not finish", e);
			return false;
		}
	}

	private static void load() {
		long started = System.nanoTime();
		try {
			String version = Ffmpeg.version();
			loaded.complete(null);
			CinemaLog.info(CinemaLog.PREWARM, "external FFmpeg ready in {} ms: {}",
				String.format("%.0f", (System.nanoTime() - started) / 1e6), version);
		} catch (Throwable t) {
			loaded.completeExceptionally(t);
			CinemaLog.error(CinemaLog.PREWARM, "failed: {}", t.getMessage());
			CinemaLog.error(CinemaLog.PREWARM, "{}", Ffmpeg.INSTALL_HINT);
		}
	}
}
