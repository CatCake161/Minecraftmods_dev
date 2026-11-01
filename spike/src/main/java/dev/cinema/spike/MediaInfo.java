package dev.cinema.spike;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.nio.file.Path;

public final class MediaInfo {
	private MediaInfo() {
	}

	public static void run(Path file) throws Exception {
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toFile())) {
			grabber.start();

			System.out.println("file      : " + file.toAbsolutePath());
			System.out.println("format    : " + nullSafe(grabber.getFormat()));
			System.out.printf("duration  : %.3f s%n", grabber.getLengthInTime() / 1_000_000.0);
			System.out.println("video     : " + nullSafe(grabber.getVideoCodecName())
				+ "  " + grabber.getImageWidth() + "x" + grabber.getImageHeight()
				+ "  " + String.format("%.2f fps", grabber.getFrameRate())
				+ "  " + (grabber.getVideoBitrate() / 1000) + " kbps");
			System.out.println("audio     : " + nullSafe(grabber.getAudioCodecName())
				+ "  " + grabber.getSampleRate() + " Hz"
				+ "  " + grabber.getAudioChannels() + " ch"
				+ "  " + (grabber.getAudioBitrate() / 1000) + " kbps");

			grabber.stop();
		}
	}

	private static String nullSafe(String value) {
		return value == null ? "none" : value;
	}
}
