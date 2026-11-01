package dev.cinema.spike;

import java.nio.file.Path;

public final class Main {
	private Main() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			printUsage();
			return;
		}

		if (args[0].equals("caps")) {
			OpenAlCapabilities.run();
			return;
		}

		if (args[0].equals("offset")) {
			StreamingOffsetProbe.run();
			return;
		}

		if (args[0].equals("speakers")) {
			SpeakerSyncBenchmark.run();
			return;
		}

		if (args.length < 2) {
			printUsage();
			return;
		}

		Path file = Path.of(args[1]);
		switch (args[0]) {
			case "info" -> MediaInfo.run(file);
			case "decode" -> DecodeBenchmark.run(file);
			case "upload" -> GlUploadBenchmark.run(file);
			case "audio" -> AudioSyncBenchmark.run(file);
			case "surround" -> SurroundAudioBenchmark.run(file);
			case "sync" -> SyncPlayer.run(file);
			default -> printUsage();
		}
	}

	private static void printUsage() {
		System.out.println("""
			Usage:
			  gradlew -p spike run --args="caps"
			  gradlew -p spike run --args="offset"
			  gradlew -p spike run --args="speakers"
			  gradlew -p spike run --args="info   <file>"
			  gradlew -p spike run --args="decode <file>"
			  gradlew -p spike run --args="upload <file>"
			  gradlew -p spike run --args="audio  <file>"
			  gradlew -p spike run --args="surround <file>"
			  gradlew -p spike run --args="sync   <file>"
			""");
	}
}
