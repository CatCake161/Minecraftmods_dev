package com.example.firstmod.client.audio.directivity;

import com.example.firstmod.block.entity.SpeakerType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One speaker directivity profile.
 *
 * <p>This is the data model for the directivity spike. It supports two analytic
 * patterns used by subwoofers (omni and cardioid) and a measured table with
 * horizontal/vertical SPL cuts. The table is normalized to the on-axis response
 * at evaluation time. Absolute speaker sensitivity is intentionally not part of
 * this model; the client applies a single SPL gain per speaker instead.
 */
public final class SpeakerDirectivityProfile {
	public enum Pattern {
		OMNI,
		CARDIOID,
		MEASURED,
		UNMEASURED
	}

	public record Row(double angle, double[] spl) {
	}

	private static final double MIN_DB = -60.0;
	private static final double MAX_DB = 12.0;
	private static final int MAX_FREQUENCIES = 128;
	private static final int MAX_ANGLES = 181;

	private final String id;
	private final String name;
	private final SpeakerType type;
	private final Pattern pattern;
	private final String source;
	private final String license;
	private final String quality;
	private final double lowHz;
	private final double highHz;
	private final double[] frequencies;
	private final double[] onAxisSpl;
	private final Row[] horizontal;
	private final Row[] vertical;

	SpeakerDirectivityProfile(String id, String name, SpeakerType type, Pattern pattern,
							  String source, String license, String quality,
							  double lowHz, double highHz, double[] frequencies,
							  double[] onAxisSpl, Row[] horizontal, Row[] vertical) {
		this.id = id;
		this.name = name;
		this.type = type;
		this.pattern = pattern;
		this.source = source;
		this.license = license;
		this.quality = quality;
		this.lowHz = lowHz;
		this.highHz = highHz;
		this.frequencies = frequencies == null ? new double[0] : frequencies.clone();
		this.onAxisSpl = onAxisSpl == null ? new double[0] : onAxisSpl.clone();
		this.horizontal = copyRows(horizontal);
		this.vertical = copyRows(vertical);
	}

	public String id() {
		return id;
	}

	public String name() {
		return name;
	}

	public SpeakerType type() {
		return type;
	}

	public Pattern pattern() {
		return pattern;
	}

	public String source() {
		return source;
	}

	public String license() {
		return license;
	}

	public String quality() {
		return quality;
	}

	public double lowHz() {
		return lowHz;
	}

	public double highHz() {
		return highHz;
	}

	public boolean hasOnAxisResponse() {
		return onAxisSpl.length > 0;
	}

	public int onAxisBandCount() {
		return onAxisSpl.length;
	}

	public boolean measured() {
		return pattern == Pattern.MEASURED;
	}

	public boolean unmeasured() {
		return pattern == Pattern.UNMEASURED;
	}

	/**
	 * Relative directivity gain in dB at a direction and frequency. The on-axis
	 * direction is 0 dB. Measured profiles subtract the measured on-axis response
	 * (or the horizontal/vertical 0-degree row when the on-axis curve is absent)
	 * and combine H/V cuts as a separable pattern; this is a spike approximation,
	 * not a full 3D balloon.
	 */
	public double gainDb(double horizontalDegrees, double verticalDegrees, double frequencyHz) {
		double frequency = clampFrequency(frequencyHz);
		return switch (pattern) {
			case OMNI, UNMEASURED -> 0.0;
			case CARDIOID -> {
				double h = Math.toRadians(horizontalDegrees);
				double v = Math.toRadians(verticalDegrees);
				double dot = Math.cos(h) * Math.cos(v);
				double amplitude = Math.max(1.0e-3, 0.5 * (1.0 + dot));
				yield clampDb(20.0 * Math.log10(amplitude));
			}
			case MEASURED -> {
				double h = horizontal.length == 0 ? 0.0 : tableGainDb(horizontal, horizontalDegrees, frequency);
				double v = vertical.length == 0 ? 0.0 : tableGainDb(vertical, verticalDegrees, frequency);
				yield clampDb(h + v);
			}
		};
	}

	public String patternLabel() {
		return switch (pattern) {
			case OMNI -> "omni";
			case CARDIOID -> "cardioid";
			case MEASURED -> "measured";
			case UNMEASURED -> "unmeasured (omni placeholder)";
		};
	}

	private double clampFrequency(double value) {
		if (!Double.isFinite(value)) {
			value = 1000.0;
		}
		double min = lowHz;
		double max = highHz;
		if (frequencies.length > 0) {
			min = Math.max(min, frequencies[0]);
			max = Math.min(max, frequencies[frequencies.length - 1]);
		}
		if (max < min) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	private static double normalizeAngle(double angle, Row[] rows) {
		if (angle > 180.0) {
			angle -= 360.0;
		} else if (angle < -180.0) {
			angle += 360.0;
		}
		if (rows.length == 0) {
			return angle;
		}
		double min = rows[0].angle;
		double max = rows[rows.length - 1].angle;
		// Many hand-authored tables only cover one half-plane. Mirror the
		// angle so a 0..180 or 0..90 table still behaves symmetrically.
		if (min >= 0.0 && angle < 0.0) {
			angle = -angle;
		} else if (max <= 0.0 && angle > 0.0) {
			angle = -angle;
		}
		if (min >= -90.5 && max <= 90.5) {
			return Math.max(min, Math.min(max, angle));
		}
		return angle;
	}

	/**
	 * The row interpolation above needs the profile frequency grid. This helper
	 * is used by {@link #gainDb} so the full grid is available for interpolation.
	 */
	private double tableGainDb(Row[] rows, double angle, double frequency) {
		double normalized = normalizeAngle(angle, rows);
		double value = interpolateSpl(rows, normalized, frequency);
		double onAxis = onAxisSpl.length > 0
			? splAtFrequency(onAxisSpl, frequency)
			: interpolateSpl(rows, 0.0, frequency);
		return value - onAxis;
	}

	private double interpolateSpl(Row[] rows, double angle, double frequency) {
		if (rows.length == 0) {
			return 0.0;
		}
		if (rows.length == 1) {
			return splAtFrequency(rows[0], frequency);
		}
		if (angle <= rows[0].angle) {
			return splAtFrequency(rows[0], frequency);
		}
		for (int i = 0; i < rows.length - 1; i++) {
			Row a = rows[i];
			Row b = rows[i + 1];
			if (angle <= b.angle) {
				double t = (angle - a.angle) / Math.max(1.0e-9, b.angle - a.angle);
				return lerp(splAtFrequency(a, frequency), splAtFrequency(b, frequency), t);
			}
		}
		return splAtFrequency(rows[rows.length - 1], frequency);
	}

	private double splAtFrequency(Row row, double frequency) {
		return splAtFrequency(row.spl(), frequency);
	}

	private double splAtFrequency(double[] spl, double frequency) {
		if (spl.length == 0) {
			return 0.0;
		}
		if (spl.length == 1 || frequencies.length < 2) {
			return spl[0];
		}
		int count = Math.min(spl.length, frequencies.length);
		if (frequency <= frequencies[0]) {
			return spl[0];
		}
		for (int i = 0; i < count - 1; i++) {
			double f0 = frequencies[i];
			double f1 = frequencies[i + 1];
			if (frequency <= f1) {
				double t = (Math.log(Math.max(1.0, frequency)) - Math.log(Math.max(1.0, f0)))
					/ Math.max(1.0e-9, Math.log(Math.max(1.0, f1)) - Math.log(Math.max(1.0, f0)));
				return lerp(spl[i], spl[i + 1], t);
			}
		}
		return spl[count - 1];
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double clampDb(double value) {
		return Math.max(MIN_DB, Math.min(MAX_DB, value));
	}

	private static Row[] copyRows(Row[] rows) {
		if (rows == null) {
			return new Row[0];
		}
		Row[] copy = new Row[rows.length];
		for (int i = 0; i < rows.length; i++) {
			Row row = rows[i];
			copy[i] = new Row(row.angle(), row.spl().clone());
		}
		return copy;
	}

	public static SpeakerDirectivityProfile fromJson(JsonObject json) {
		String id = requiredString(json, "id");
		if (!id.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException("id may only contain letters, digits, '_', '.', '-'");
		}
		String name = optionalString(json, "name", id);
		SpeakerType type = parseType(requiredString(json, "type"));
		String source = optionalString(json, "source", "local");
		String license = optionalString(json, "license", "unknown");
		String quality = optionalString(json, "quality", "unknown");

		double[] frequencies = parseFrequencies(json);
		double[] onAxisSpl = parseOnAxis(json, frequencies);
		Row[] horizontal = parseRows(json, "horizontal", frequencies);
		Row[] vertical = parseRows(json, "vertical", frequencies);
		Pattern pattern = parsePattern(json, horizontal.length > 0 || vertical.length > 0);

		double lowHz = 20.0;
		double highHz = 20_000.0;
		JsonElement range = json.get("frequencyRange");
		if (range != null && range.isJsonArray()) {
			JsonArray array = range.getAsJsonArray();
			if (array.size() >= 2) {
				lowHz = array.get(0).getAsDouble();
				highHz = array.get(1).getAsDouble();
			}
		} else if (frequencies.length >= 2) {
			lowHz = frequencies[0];
			highHz = frequencies[frequencies.length - 1];
		}
		if (!Double.isFinite(lowHz) || !Double.isFinite(highHz) || highHz <= lowHz) {
			throw new IllegalArgumentException("frequencyRange must be [low, high] with low < high");
		}
		lowHz = Math.max(1.0, Math.min(23_999.0, lowHz));
		highHz = Math.max(lowHz + 1.0, Math.min(24_000.0, highHz));

		if (pattern == Pattern.MEASURED && frequencies.length < 2) {
			throw new IllegalArgumentException("measured profile needs at least two frequencies");
		}
		if (pattern == Pattern.MEASURED && onAxisSpl.length == 0) {
			throw new IllegalArgumentException("measured profile needs an onAxis response array");
		}
		if (pattern == Pattern.MEASURED && horizontal.length == 0 && vertical.length == 0) {
			throw new IllegalArgumentException("measured profile needs horizontal or vertical SPL rows");
		}
		return new SpeakerDirectivityProfile(id, name, type, pattern, source, license, quality,
			lowHz, highHz, frequencies, onAxisSpl, horizontal, vertical);
	}

	private static Pattern parsePattern(JsonObject json, boolean hasTables) {
		String value = optionalString(json, "pattern", hasTables ? "measured" : "unmeasured");
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "omni" -> Pattern.OMNI;
			case "cardioid" -> Pattern.CARDIOID;
			case "measured" -> Pattern.MEASURED;
			case "unmeasured" -> Pattern.UNMEASURED;
			default -> throw new IllegalArgumentException("unknown pattern: " + value);
		};
	}

	private static double[] parseFrequencies(JsonObject json) {
		JsonElement element = json.get("frequencies");
		if (element == null || !element.isJsonArray()) {
			return new double[0];
		}
		JsonArray array = element.getAsJsonArray();
		if (array.size() > MAX_FREQUENCIES) {
			throw new IllegalArgumentException("too many frequencies (max " + MAX_FREQUENCIES + ")");
		}
		double[] values = new double[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsDouble();
			if (!Double.isFinite(values[i]) || values[i] <= 0.0) {
				throw new IllegalArgumentException("frequencies must be positive");
			}
		}
		// Be lenient with hand-authored files: sort ascending.
		Arrays.sort(values);
		return values;
	}

	private static double[] parseOnAxis(JsonObject json, double[] frequencies) {
		JsonElement element = json.get("onAxis");
		if (element == null || !element.isJsonArray()) {
			return new double[0];
		}
		JsonArray array = element.getAsJsonArray();
		if (frequencies.length == 0) {
			throw new IllegalArgumentException("onAxis response needs a frequencies array");
		}
		if (array.size() != frequencies.length) {
			throw new IllegalArgumentException("onAxis response length must match frequencies");
		}
		double[] values = new double[array.size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = array.get(i).getAsDouble();
			if (!Double.isFinite(values[i])) {
				throw new IllegalArgumentException("onAxis response values must be finite");
			}
		}
		return values;
	}

	private static Row[] parseRows(JsonObject json, String key, double[] frequencies) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonArray()) {
			return new Row[0];
		}
		JsonArray array = element.getAsJsonArray();
		if (array.size() > MAX_ANGLES) {
			throw new IllegalArgumentException("too many " + key + " rows (max " + MAX_ANGLES + ")");
		}
		List<Row> rows = new ArrayList<>();
		for (JsonElement item : array) {
			if (!item.isJsonObject()) {
				throw new IllegalArgumentException(key + " rows must be objects");
			}
			JsonObject row = item.getAsJsonObject();
			double angle = requiredDouble(row, "angle");
			JsonElement splElement = row.get("spl");
			if (splElement == null || !splElement.isJsonArray()) {
				throw new IllegalArgumentException(key + " row needs an spl array");
			}
			JsonArray splArray = splElement.getAsJsonArray();
			if (frequencies.length > 0 && splArray.size() != frequencies.length) {
				throw new IllegalArgumentException(key + " row spl length must match frequencies");
			}
			double[] spl = new double[splArray.size()];
			for (int i = 0; i < spl.length; i++) {
				spl[i] = splArray.get(i).getAsDouble();
				if (!Double.isFinite(spl[i])) {
					throw new IllegalArgumentException(key + " spl values must be finite");
				}
			}
			rows.add(new Row(angle, spl));
		}
		rows.sort(Comparator.comparingDouble(Row::angle));
		return rows.toArray(Row[]::new);
	}

	private static SpeakerType parseType(String value) {
		for (SpeakerType type : SpeakerType.values()) {
			if (type.id().equalsIgnoreCase(value)) {
				return type;
			}
		}
		throw new IllegalArgumentException("unknown speaker type: " + value);
	}

	private static String requiredString(JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive() || element.getAsString().isBlank()) {
			throw new IllegalArgumentException("missing string: " + key);
		}
		return element.getAsString();
	}

	private static String optionalString(JsonObject json, String key, String fallback) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			return fallback;
		}
		String value = element.getAsString();
		return value.isBlank() ? fallback : value;
	}

	private static double requiredDouble(JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive()) {
			throw new IllegalArgumentException("missing number: " + key);
		}
		double value = element.getAsDouble();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(key + " must be finite");
		}
		return value;
	}

}
