package com.example.firstmod.client.media;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Locates and probes the external FFmpeg installation.
 *
 * <p>FFmpeg is intentionally not bundled. The user is expected to download a
 * full build from gyan.dev and place it under the game's {@code mods} folder,
 * for example:
 *
 * <pre>
 *   mods/ffmpeg.exe
 *   mods/ffmpeg/bin/ffmpeg.exe
 *   mods/ffmpeg-full_build/bin/ffmpeg.exe
 * </pre>
 *
 * <p>An explicit path can also be supplied with
 * {@code -Dfirstmod.ffmpeg.path=C:\path\to\ffmpeg.exe}.
 */
public final class Ffmpeg {
	public static final String INSTALL_HINT =
		"FFmpeg not found. Download ffmpeg-release-full.7z from https://www.gyan.dev/ffmpeg/builds/ "
			+ "and put ffmpeg.exe + ffprobe.exe under <game>/mods/ffmpeg/bin/ "
			+ "(or set -Dfirstmod.ffmpeg.path=...)";

	private static Path ffmpegPath;
	private static Path ffprobePath;
	private static String version;

	private Ffmpeg() {
	}

	public static synchronized Path ffmpeg() throws IOException {
		if (ffmpegPath == null) {
			ffmpegPath = locate("ffmpeg");
		}
		return ffmpegPath;
	}

	public static synchronized Path ffprobe() throws IOException {
		if (ffprobePath == null) {
			Path ffmpeg = ffmpeg();
			Path sibling = ffmpeg.resolveSibling(isWindows() ? "ffprobe.exe" : "ffprobe");
			if (Files.isRegularFile(sibling)) {
				ffprobePath = sibling;
			} else {
				ffprobePath = locate("ffprobe");
			}
		}
		return ffprobePath;
	}

	public static synchronized String version() throws IOException {
		if (version == null) {
			String output = runCapture(List.of(ffmpeg().toString(), "-version"), 15);
			int newline = output.indexOf('\n');
			version = (newline >= 0 ? output.substring(0, newline) : output).trim();
		}
		return version;
	}

	private static Path locate(String binary) throws IOException {
		String baseName = isWindows() ? binary + ".exe" : binary;
		String explicit = System.getProperty("firstmod.ffmpeg.path");
		if (explicit != null && !explicit.isBlank()) {
			Path path = Path.of(explicit);
			if (Files.isDirectory(path)) {
				path = path.resolve(baseName);
			}
			if (Files.isRegularFile(path)) {
				return path.toAbsolutePath();
			}
		}

		Path gameDir = FabricLoader.getInstance().getGameDir();
		List<Path> candidates = new ArrayList<>();
		for (String root : List.of("mods", ".", "mods/cinema", "mods/ffmpeg", "mods/ffmpeg-full_build")) {
			Path dir = gameDir.resolve(root);
			candidates.add(dir.resolve(baseName));
			candidates.add(dir.resolve("bin").resolve(baseName));
		}
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				return candidate.toAbsolutePath();
			}
		}

		// Let users extract the gyan.dev archive as-is (ffmpeg-*-full_build/bin/...).
		Path mods = gameDir.resolve("mods");
		if (Files.isDirectory(mods)) {
			try (var stream = Files.walk(mods, 4)) {
				Path found = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().equalsIgnoreCase(baseName))
					.findFirst()
					.orElse(null);
				if (found != null) {
					return found.toAbsolutePath();
				}
			}
		}
		throw new IOException(INSTALL_HINT);
	}

	static String runCapture(List<String> command, int timeoutSeconds) throws IOException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append('\n');
			}
		}
		try {
			if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				throw new IOException("FFmpeg command timed out: " + String.join(" ", command));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("FFmpeg command interrupted", e);
		}
		if (process.exitValue() != 0) {
			throw new IOException("FFmpeg command failed (" + process.exitValue() + "): "
				+ String.join(" ", command) + "\n" + output);
		}
		return output.toString();
	}

	public static VideoInfo probeVideo(Path file) throws IOException {
		String output = runCapture(List.of(
			ffprobe().toString(),
			"-v", "error",
			"-select_streams", "v:0",
			"-show_entries", "stream=width,height,r_frame_rate,codec_name,pix_fmt,color_transfer,color_primaries,color_space",
			"-of", "default=noprint_wrappers=1",
			file.toString()), 30);

		int width = 0;
		int height = 0;
		double frameRate = 30.0;
		String codec = "unknown";
		String pixelFormat = "unknown";
		String transfer = "unknown";
		String primaries = "unknown";
		String space = "unknown";
		for (String line : output.split("\\R")) {
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq);
			String value = line.substring(eq + 1);
			switch (key) {
				case "width" -> width = parseInt(value, 0);
				case "height" -> height = parseInt(value, 0);
				case "r_frame_rate" -> frameRate = parseRational(value, 30.0);
				case "codec_name" -> codec = value;
				case "pix_fmt" -> pixelFormat = value;
				case "color_transfer" -> transfer = value;
				case "color_primaries" -> primaries = value;
				case "color_space" -> space = value;
				default -> {
				}
			}
		}
		if (width <= 0 || height <= 0) {
			throw new IOException("FFprobe could not read video dimensions: " + file);
		}
		return new VideoInfo(width, height, frameRate, codec, pixelFormat, transfer, primaries, space);
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double parseRational(String value, double fallback) {
		try {
			int slash = value.indexOf('/');
			if (slash < 0) {
				return Double.parseDouble(value);
			}
			double numerator = Double.parseDouble(value.substring(0, slash));
			double denominator = Double.parseDouble(value.substring(slash + 1));
			return denominator == 0 ? fallback : numerator / denominator;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public record VideoInfo(int width, int height, double frameRate, String codec,
							String pixelFormat, String colorTransfer, String colorPrimaries,
							String colorSpace) {
		public boolean isHdr() {
			return "smpte2084".equalsIgnoreCase(colorTransfer)
				|| "arib-std-b67".equalsIgnoreCase(colorTransfer);
		}
	}
}
