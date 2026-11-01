package com.example.firstmod.client.media;

import com.example.firstmod.FirstMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes video with an external FFmpeg process.
 *
 * <p>The user must install a full FFmpeg build under {@code mods/}; see
 * {@link Ffmpeg#INSTALL_HINT}. FFmpeg handles HEVC/VP9/AV1/HDR better than the
 * bundled JavaCV build, and can use hardware decode when available.
 */
public final class VideoDecoder implements AutoCloseable {
	private static final Pattern PTS_TIME = Pattern.compile("pts_time:([-+0-9.eE]+)");
	private static final int STDERR_TAIL_LINES = 16;

	private final Process process;
	private final ReadableByteChannel stdout;
	private final BlockingQueue<Double> ptsQueue = new LinkedBlockingQueue<>(64);
	private final ArrayDeque<String> stderrTail = new ArrayDeque<>();
	private final Thread stderrThread;
	private final int width;
	private final int height;
	private final double frameRate;
	private final double startSeconds;
	private final int frameBytes;

	private double firstRawPts = Double.NaN;
	private double lastPts;
	private int frameIndex;
	private long decodedFrames;
	private long lastStatsAtNanos = System.nanoTime();
	private volatile boolean closed;

	public VideoDecoder(Path file, double startSeconds) throws Exception {
		Ffmpeg.VideoInfo info = Ffmpeg.probeVideo(file);
		frameRate = info.frameRate() > 1.0 ? info.frameRate() : 30.0;
		this.startSeconds = Math.max(0.0, startSeconds);

		int maxHeight = Integer.getInteger("firstmod.video.maxHeight", info.isHdr() ? 720 : 1080);
		int outHeight = info.height();
		int outWidth = info.width();
		if (maxHeight > 0 && outHeight > maxHeight) {
			outHeight = maxHeight & ~1;
			outWidth = Math.max(2, (int) Math.round(info.width() * (outHeight / (double) info.height())) & ~1);
		}
		width = outWidth;
		height = outHeight;
		frameBytes = Math.multiplyExact(Math.multiplyExact(width, height), 4);

		List<String> command = new ArrayList<>();
		command.add(Ffmpeg.ffmpeg().toString());
		command.add("-hide_banner");
		command.add("-nostdin");
		command.add("-loglevel");
		boolean showInfo = Boolean.getBoolean("firstmod.video.showinfo");
		command.add(showInfo ? "info" : "error");
		String hwaccel = System.getProperty("firstmod.ffmpeg.hwaccel", "auto");
		if (!"none".equalsIgnoreCase(hwaccel)) {
			command.add("-hwaccel");
			command.add(hwaccel.isBlank() ? "auto" : hwaccel);
		}
		if (this.startSeconds > 0.0) {
			command.add("-ss");
			command.add(String.format(java.util.Locale.ROOT, "%.3f", this.startSeconds));
		}
		command.add("-i");
		command.add(file.toString());
		command.add("-map");
		command.add("0:v:0");
		command.add("-an");
		command.add("-sn");
		command.add("-dn");
		command.add("-vf");
		command.add(buildFilter(info, outWidth, outHeight, showInfo));
		command.add("-fps_mode");
		command.add("passthrough");
		command.add("-pix_fmt");
		command.add("rgba");
		command.add("-f");
		command.add("rawvideo");
		command.add("pipe:1");

		process = new ProcessBuilder(command).redirectErrorStream(false).start();
		stdout = Channels.newChannel(process.getInputStream());
		stderrThread = new Thread(this::readStderr, "cinema-ffmpeg-video-stderr");
		stderrThread.setDaemon(true);
		stderrThread.start();

		FirstMod.LOGGER.info(
			"cinema video decoder: {}x{} codec={} pix={} trc={} primaries={} space={} fps={} -> {}x{} hdr={} start={} s",
			info.width(), info.height(), info.codec(), info.pixelFormat(), info.colorTransfer(),
			info.colorPrimaries(), info.colorSpace(), String.format("%.3f", info.frameRate()),
			width, height, info.isHdr(), String.format("%.2f", this.startSeconds));
		if (info.isHdr()) {
			FirstMod.LOGGER.info("cinema video decoder: HDR tone mapping enabled ({} -> BT.709)", info.colorTransfer());
		}
		if (width != info.width() || height != info.height()) {
			FirstMod.LOGGER.info("cinema video decoder: downscaled to {}x{} (maxHeight={})",
				width, height, maxHeight);
		}
	}

	private static String buildFilter(Ffmpeg.VideoInfo info, int width, int height, boolean showInfo) {
		List<String> filters = new ArrayList<>();
		if (width != info.width() || height != info.height()) {
			filters.add("scale=" + width + ":" + height + ":flags=bicubic");
		}
		if (info.isHdr()) {
			boolean hlg = "arib-std-b67".equalsIgnoreCase(info.colorTransfer());
			filters.add("zscale=tin=" + (hlg ? "arib-std-b67" : "smpte2084")
				+ ":pin=bt2020:min=bt2020nc:rin=tv:t=linear:npl=100");
			filters.add("tonemap=tonemap=hable:desat=0");
			filters.add("zscale=t=bt709:p=bt709:m=bt709:r=tv");
		}
		filters.add("format=rgba");
		if (showInfo) {
			filters.add("showinfo");
		}
		return String.join(",", filters);
	}

	private void readStderr() {
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher matcher = PTS_TIME.matcher(line);
				if (matcher.find()) {
					try {
						ptsQueue.offer(Double.parseDouble(matcher.group(1)));
					} catch (NumberFormatException ignored) {
					}
				}
				if (!line.contains("Parsed_showinfo") && !line.isBlank()) {
					synchronized (stderrTail) {
						stderrTail.addLast(line);
						while (stderrTail.size() > STDERR_TAIL_LINES) {
							stderrTail.removeFirst();
						}
					}
				}
			}
		} catch (IOException ignored) {
		}
	}

	public VideoFrame next() throws Exception {
		if (closed) {
			return null;
		}
		ByteBuffer pixels = FrameBufferPool.acquire(frameBytes);
		pixels.clear();
		pixels.limit(frameBytes);
		boolean eof = false;
		try {
			while (pixels.hasRemaining()) {
				int read = stdout.read(pixels);
				if (read < 0) {
					eof = true;
					break;
				}
			}
		} catch (IOException e) {
			pixels.clear();
			FrameBufferPool.release(pixels);
			throw e;
		}
		if (eof) {
			FrameBufferPool.release(pixels);
			int exit = process.waitFor();
			if (frameIndex == 0 && exit != 0) {
				throw new IOException("FFmpeg video decoder failed (" + exit + "): " + stderrSummary());
			}
			return null;
		}
		pixels.flip();

		Double rawPts = ptsQueue.poll();
		double pts;
		if (rawPts != null) {
			if (Double.isNaN(firstRawPts)) {
				firstRawPts = rawPts;
			}
			pts = startSeconds + Math.max(0.0, rawPts - firstRawPts);
		} else {
			pts = startSeconds + frameIndex / frameRate;
		}
		lastPts = pts;
		frameIndex++;
		decodedFrames++;
		logStatsIfNeeded();
		return new VideoFrame(pixels, width, height, pts);
	}

	private void logStatsIfNeeded() {
		long now = System.nanoTime();
		if (now - lastStatsAtNanos < 5_000_000_000L) {
			return;
		}
		double seconds = (now - lastStatsAtNanos) / 1e9;
		lastStatsAtNanos = now;
		FirstMod.LOGGER.info("cinema video decoder: decoded {} frames, {} fps, {}x{}",
			decodedFrames, String.format("%.1f", decodedFrames / seconds), width, height);
		decodedFrames = 0;
	}

	private String stderrSummary() {
		synchronized (stderrTail) {
			return String.join("\n", stderrTail);
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		try {
			stdout.close();
		} catch (IOException ignored) {
		}
		process.destroy();
		try {
			if (!process.waitFor(1, TimeUnit.SECONDS)) {
				process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
	}
}
