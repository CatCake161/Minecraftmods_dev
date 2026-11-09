package com.example.cinema.client.audio.speakerdata;

import com.example.cinema.block.entity.SpeakerType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One speaker data profile.
 *
 * <p>This is the data model for the speaker data spike. It supports two analytic
 * patterns used by subwoofers (omni and cardioid), a measured table with
 * horizontal/vertical SPL cuts, and an enclosure label for subwoofers (sealed or
 * ported). Directivity is normalized to the on-axis response at evaluation
 * time.
 *
 * <p>Measured profiles retain the raw measurement frequency grid and the raw
 * on-axis absolute SPL. {@link #onAxisEqGainDb(double)} exposes the on-axis
 * response normalized to its full-band energy average for future per-speaker
 * EQ. This keeps the overall on-axis loudness comparable between models. The
 * current playback path still applies only the per-speaker SPL gain.
 *
 * <p>The profile's low/high frequency range is the on-axis -3 dB band. It is
 * metadata for display, bass management and future crossovers; it is not used
 * as the response-evaluation clamp. Measured profiles keep evaluating over the
 * full measured grid so the FIR can reproduce the measured roll-off outside
 * the -3 dB band.
 */
public final class SpeakerDataProfile {
	public enum Enclosure {
		SEALED,
		PORTED,
		UNKNOWN
	}

	public enum Pattern {
		OMNI,
		CARDIOID,
		MEASURED,
		UNMEASURED
	}

	public record Row(double angle, double[] spl) {
	}

	private static final double MIN_DB = -90.0;
	private static final double MAX_DB = 12.0;
	private static final int MAX_FREQUENCIES = 256;
	private static final int MAX_ANGLES = 181;
	private static final double RANGE_THRESHOLD_DB = -3.0;
	private static final double DEFAULT_LOW_HZ = 20.0;
	private static final double DEFAULT_HIGH_HZ = 20_000.0;
	private static final double DEFAULT_SUBWOOFER_HIGH_HZ = 200.0;

	private final String id;
	private final String name;
	private final SpeakerType type;
	private final Enclosure enclosure;
	private final Pattern pattern;
	private final String source;
	private final String license;
	private final double lowHz;
	private final double highHz;
	private final double[] frequencies;
	private final double[] onAxisSpl;
	private final double onAxisReferenceDb;
	private final Row[] horizontal;
	private final Row[] vertical;
	private final ConcurrentHashMap<Integer, Double> powerIntegralCache =
		new ConcurrentHashMap<>();

	SpeakerDataProfile(String id, String name, SpeakerType type, Enclosure enclosure,
							  Pattern pattern, String source, String license,
							  double lowHz, double highHz, double[] frequencies,
							  double[] onAxisSpl, Row[] horizontal, Row[] vertical) {
		this.id = id;
		this.name = name;
		this.type = type;
		this.enclosure = enclosure == null ? Enclosure.UNKNOWN : enclosure;
		this.pattern = pattern;
		this.source = source;
		this.license = license;
		this.lowHz = lowHz;
		this.highHz = highHz;
		this.frequencies = frequencies == null ? new double[0] : frequencies.clone();
		this.onAxisSpl = onAxisSpl == null ? new double[0] : onAxisSpl.clone();
		this.onAxisReferenceDb = energyAverageDb(this.onAxisSpl);
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

	public Enclosure enclosure() {
		return enclosure;
	}

	public Pattern pattern() {
		return pattern;
	}

	public boolean isSubwoofer() {
		return type == SpeakerType.SUBWOOFER;
	}

	public String source() {
		return source;
	}

	public String license() {
		return license;
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

	/**
	 * Full-band energy average of the on-axis response:
	 * {@code 10 * log10(mean(10^(SPL/10)))} over every measured on-axis sample.
	 * Returns 0 when no on-axis data is present. This is the loudness anchor
	 * used by {@link #onAxisEqGainDb(double)} and by the -3 dB frequency-range
	 * calculation.
	 */
	public double onAxisReferenceDb() {
		return onAxisReferenceDb;
	}

	/**
	 * On-axis response relative to {@link #onAxisReferenceDb()}, in dB. This is
	 * the raw measured on-axis shape with its overall loudness normalized, ready
	 * for per-speaker EQ. The value is not clamped: bass roll-off and treble
	 * extension are retained exactly as measured.
	 *
	 * <p>CTA-2034-A defines 0° as the reference axis. Positive horizontal angles
	 * are toward the listener's right when facing the speaker, and positive
	 * vertical angles are up (CTA-2034-A Figure 1). Callers must convert their
	 * world-space direction into those H/V angles before calling
	 * {@link #gainDb(double, double, double)}.
	 */
	public double onAxisEqGainDb(double frequencyHz) {
		if (onAxisSpl.length == 0) {
			return 0.0;
		}
		double frequency = clampFrequency(frequencyHz);
		return splAtFrequency(onAxisSpl, frequency) - onAxisReferenceDb;
	}

	public boolean isMeasured() {
		return pattern == Pattern.MEASURED;
	}

	public boolean isUnmeasured() {
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
				double amplitude = Math.max(0.0, 0.5 * (1.0 + dot));
				yield amplitude <= 0.0 ? MIN_DB : clampDb(20.0 * Math.log10(amplitude));
			}
			case MEASURED -> {
				double h = horizontal.length == 0 ? 0.0 : tableGainDb(horizontal, horizontalDegrees, frequency);
				double v = vertical.length == 0 ? 0.0 : tableGainDb(vertical, verticalDegrees, frequency);
				yield clampDb(h + v);
			}
		};
	}

	/**
	 * Integral {@code ∫ g(Ω)² dΩ} of the directivity amplitude over the sphere.
	 * For an omni source this is {@code 4π}; the result is used to recover
	 * total source power from the on-axis strength. H/V cuts are combined by
	 * the same separable approximation as {@link #gainDb}.
	 */
	public double directivityPowerIntegral(double frequencyHz) {
		if (pattern == Pattern.OMNI || pattern == Pattern.UNMEASURED) {
			return 4.0 * Math.PI;
		}
		double frequency = clampFrequency(frequencyHz);
		int key = (int) Math.round(frequency);
		return powerIntegralCache.computeIfAbsent(key, ignored -> {
			int horizontalSteps = 72;
			int verticalSteps = 36;
			double horizontalStep = 2.0 * Math.PI / horizontalSteps;
			double verticalStep = Math.PI / verticalSteps;
			double integral = 0.0;
			for (int ih = 0; ih < horizontalSteps; ih++) {
				double horizontal = -180.0 + (ih + 0.5) * 360.0 / horizontalSteps;
				for (int iv = 0; iv < verticalSteps; iv++) {
					double vertical = -90.0 + (iv + 0.5) * 180.0 / verticalSteps;
					double amplitude = Math.pow(10.0,
						gainDb(horizontal, vertical, frequency) / 20.0);
					integral += amplitude * amplitude
						* Math.cos(Math.toRadians(vertical))
						* horizontalStep * verticalStep;
				}
			}
			return Double.isFinite(integral) && integral > 1.0e-9
				? integral : 4.0 * Math.PI;
		});
	}

	public String patternLabel() {
		return switch (pattern) {
			case OMNI -> "omni";
			case CARDIOID -> "cardioid";
			case MEASURED -> "measured";
			case UNMEASURED -> "unmeasured (omni placeholder)";
		};
	}

	public String directivityLabel() {
		return switch (pattern) {
			case OMNI -> "omni";
			case CARDIOID -> "cardioid";
			case MEASURED -> "measured";
			case UNMEASURED -> "unmeasured";
		};
	}

	public String enclosureLabel() {
		return switch (enclosure) {
			case SEALED -> "sealed";
			case PORTED -> "ported";
			case UNKNOWN -> "unknown";
		};
	}

	/** Short profile summary used by the picker and debug output. */
	public String summaryLabel() {
		if (isSubwoofer()) {
			return enclosureLabel() + " · " + directivityLabel();
		}
		return patternLabel();
	}

	/**
	 * Clamps a frequency to the response-evaluation range. Measured profiles use
	 * the full measured grid so the FIR can reproduce roll-off outside the -3 dB
	 * band; analytic/unmeasured profiles fall back to their nominal band.
	 */
	private double clampFrequency(double value) {
		if (!Double.isFinite(value)) {
			value = 1000.0;
		}
		double min;
		double max;
		if (frequencies.length > 0) {
			min = frequencies[0];
			max = frequencies[frequencies.length - 1];
		} else {
			min = lowHz;
			max = highHz;
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

	private static double energyAverageDb(double[] values) {
		if (values == null || values.length == 0) {
			return 0.0;
		}
		double power = 0.0;
		for (double value : values) {
			power += Math.pow(10.0, value / 10.0);
		}
		return 10.0 * Math.log10(power / values.length);
	}

	/**
	 * Frequency range between the lowest and highest on-axis samples within
	 * -3 dB of the full-band energy average. Interior dips do not truncate the
	 * range; the boundaries are interpolated between the outermost
	 * above-threshold sample and its below-threshold neighbour. The result is
	 * rounded to 1 Hz. Returns {@code null} when a valid range cannot be found.
	 */
	static double[] computeFrequencyRange(double[] frequencies, double[] onAxis) {
		if (frequencies == null || onAxis == null
			|| frequencies.length < 2 || onAxis.length < 2) {
			return null;
		}
		int count = Math.min(frequencies.length, onAxis.length);
		double threshold = energyAverageDb(onAxis) + RANGE_THRESHOLD_DB;
		int lowIndex = -1;
		for (int i = 0; i < count; i++) {
			if (onAxis[i] >= threshold) {
				lowIndex = i;
				break;
			}
		}
		int highIndex = -1;
		for (int i = count - 1; i >= 0; i--) {
			if (onAxis[i] >= threshold) {
				highIndex = i;
				break;
			}
		}
		if (lowIndex < 0 || highIndex <= lowIndex) {
			return null;
		}
		double low = lowIndex == 0
			? frequencies[0]
			: crossingHz(frequencies[lowIndex - 1], frequencies[lowIndex],
				onAxis[lowIndex - 1], onAxis[lowIndex], threshold);
		double high = highIndex == count - 1
			? frequencies[count - 1]
			: crossingHz(frequencies[highIndex], frequencies[highIndex + 1],
				onAxis[highIndex], onAxis[highIndex + 1], threshold);
		if (!Double.isFinite(low) || !Double.isFinite(high) || high <= low) {
			return null;
		}
		double roundedLow = Math.round(low);
		double roundedHigh = Math.round(high);
		if (roundedHigh <= roundedLow) {
			return null;
		}
		return new double[]{roundedLow, roundedHigh};
	}

	private static double crossingHz(double f0, double f1, double db0, double db1,
									 double threshold) {
		if (db1 == db0) {
			return f0;
		}
		double t = (threshold - db0) / (db1 - db0);
		t = Math.max(0.0, Math.min(1.0, t));
		double log0 = Math.log(Math.max(1.0, f0));
		double log1 = Math.log(Math.max(1.0, f1));
		return Math.exp(log0 + t * (log1 - log0));
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

	public static SpeakerDataProfile fromJson(JsonObject json) {
		String id = requiredString(json, "id");
		if (!id.matches("[A-Za-z0-9_.-]+")) {
			throw new IllegalArgumentException("id may only contain letters, digits, '_', '.', '-'");
		}
		String name = optionalString(json, "name", id);
		SpeakerType type = parseType(requiredString(json, "type"));
		Enclosure enclosure = parseEnclosure(json);
		String source = optionalString(json, "source", "local");
		String license = optionalString(json, "license", "unknown");

		double[] frequencies = parseFrequencies(json);
		double[] onAxisSpl = parseOnAxis(json, frequencies);
		Row[] horizontal = parseRows(json, "horizontal", frequencies);
		Row[] vertical = parseRows(json, "vertical", frequencies);
		Pattern pattern = parsePattern(json, horizontal.length > 0 || vertical.length > 0);

		double lowHz = DEFAULT_LOW_HZ;
		double highHz = type == SpeakerType.SUBWOOFER
			? DEFAULT_SUBWOOFER_HIGH_HZ : DEFAULT_HIGH_HZ;
		JsonElement range = json.get("frequencyRange");
		double[] computed = pattern == Pattern.MEASURED
			? computeFrequencyRange(frequencies, onAxisSpl) : null;
		if (computed != null) {
			// The measured -3 dB band is authoritative. The JSON range is only a
			// fallback for measured profiles without a usable on-axis curve, so
			// old -10 dB profiles migrate automatically.
			lowHz = computed[0];
			highHz = computed[1];
		} else if (range != null && range.isJsonArray()) {
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
		lowHz = Math.round(lowHz);
		highHz = Math.round(highHz);
		if (highHz <= lowHz) {
			highHz = Math.min(24_000.0, lowHz + 1.0);
		}

		if (pattern == Pattern.MEASURED && frequencies.length < 2) {
			throw new IllegalArgumentException("measured profile needs at least two frequencies");
		}
		if (pattern == Pattern.MEASURED && onAxisSpl.length == 0) {
			throw new IllegalArgumentException("measured profile needs an onAxis response array");
		}
		if (pattern == Pattern.MEASURED && horizontal.length == 0 && vertical.length == 0) {
			throw new IllegalArgumentException("measured profile needs horizontal or vertical SPL rows");
		}
		return new SpeakerDataProfile(id, name, type, enclosure, pattern, source, license,
			lowHz, highHz, frequencies, onAxisSpl, horizontal, vertical);
	}

	private static Enclosure parseEnclosure(JsonObject json) {
		String value = optionalString(json, "enclosure", "unknown");
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "sealed", "closed" -> Enclosure.SEALED;
			// "ported" is the canonical name; the bass-reflex spellings remain
			// accepted so older local profiles keep loading.
			case "ported", "bass-reflex", "bass_reflex", "reflex" -> Enclosure.PORTED;
			case "unknown", "unspecified" -> Enclosure.UNKNOWN;
			default -> throw new IllegalArgumentException("unknown enclosure: " + value);
		};
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
