package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;
import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Loads JavaCV/FFmpeg native libraries in the background while the client is
 * starting. First use otherwise costs several seconds (native extraction plus
 * class loading) and would leave the cinema screen black after pressing play.
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
			FFmpegFrameGrabber.tryLoad();
			LOADED.complete(null);
			FirstMod.LOGGER.info("cinema prewarm: JavaCV/FFmpeg ready in {} ms",
				String.format("%.0f", (System.nanoTime() - started) / 1e6));
		} catch (Throwable t) {
			LOADED.completeExceptionally(t);
			FirstMod.LOGGER.error("cinema prewarm failed", t);
		}
	}
}
