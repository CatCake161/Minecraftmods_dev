package com.example.cinema.client.media.audio.acousticfir;

import com.example.cinema.client.media.audio.acoustics.room.AcousticMaterial;
import com.example.cinema.client.media.audio.dsp.Biquad;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Design-time three-band material reflection curve.
 *
 * <p>The material table stores energy absorption in low/mid/high bands. The
 * pressure reflection coefficient is {@code R = sqrt(1 - alpha)}. This class
 * solves the same low-shelf / peaking / high-shelf target used by the former
 * material biquad, then exposes only its magnitude in dB. It never creates a
 * runtime filter: the curve is merged into the common FIR target.
 */
final class MaterialReflectionCurve {
	static final double LOW_BAND_HZ = Math.sqrt(125.0 * 250.0);
	static final double MID_BAND_HZ = Math.sqrt(500.0 * 1_000.0);
	static final double HIGH_BAND_HZ = Math.sqrt(2_000.0 * 4_000.0);
	static final double LOW_SHELF_HZ = 250.0;
	static final double HIGH_SHELF_HZ = HIGH_BAND_HZ;
	static final double SHELF_Q = Biquad.DEFAULT_Q;
	static final double PEAK_Q = 1.0;
	static final double SAMPLE_RATE = 48_000.0;
	private static final double MAX_TARGET_DB = 36.0;
	private static final double FLAT_GAIN_DB = 0.01;
	private static final double[] BAND_CENTERS = {
		LOW_BAND_HZ,
		MID_BAND_HZ,
		HIGH_BAND_HZ,
	};
	private static final Biquad[] PROTOTYPES = {
		Biquad.lowShelf(LOW_SHELF_HZ, SHELF_Q, 1.0, SAMPLE_RATE),
		Biquad.peaking(MID_BAND_HZ, PEAK_Q, 1.0, SAMPLE_RATE),
		Biquad.highShelf(HIGH_SHELF_HZ, SHELF_Q, 1.0, SAMPLE_RATE),
	};
	private static final double[][] BASIS = createBasis();
	private static final MaterialReflectionCurve FLAT =
		new MaterialReflectionCurve(null, null, null);
	private static final ConcurrentHashMap<AcousticMaterial, MaterialReflectionCurve> CACHE =
		new ConcurrentHashMap<>();

	private final Biquad low;
	private final Biquad peak;
	private final Biquad high;

	private MaterialReflectionCurve(Biquad low, Biquad peak, Biquad high) {
		this.low = low;
		this.peak = peak;
		this.high = high;
	}

	static MaterialReflectionCurve of(AcousticMaterial material) {
		if (material == null) {
			return FLAT;
		}
		return CACHE.computeIfAbsent(material, MaterialReflectionCurve::design);
	}

	double db(double frequencyHz) {
		if (low == null) {
			return 0.0;
		}
		return low.magnitudeDb(frequencyHz, SAMPLE_RATE)
			+ peak.magnitudeDb(frequencyHz, SAMPLE_RATE)
			+ high.magnitudeDb(frequencyHz, SAMPLE_RATE);
	}

	static void clearCache() {
		CACHE.clear();
	}

	private static MaterialReflectionCurve design(AcousticMaterial material) {
		double low = pressureReflection(material.low());
		double mid = pressureReflection(material.mid());
		double high = pressureReflection(material.high());
		if (mid < 1.0e-6) {
			return FLAT;
		}
		double lowDb = clampTarget(relativeDb(low, mid));
		double highDb = clampTarget(relativeDb(high, mid));
		double[] gains = solve(BASIS, new double[]{lowDb, 0.0, highDb});
		if (gains == null || maxAbs(gains) < FLAT_GAIN_DB) {
			return FLAT;
		}
		return new MaterialReflectionCurve(
			Biquad.lowShelf(LOW_SHELF_HZ, SHELF_Q, gains[0], SAMPLE_RATE),
			Biquad.peaking(MID_BAND_HZ, PEAK_Q, gains[1], SAMPLE_RATE),
			Biquad.highShelf(HIGH_SHELF_HZ, SHELF_Q, gains[2], SAMPLE_RATE));
	}

	private static double[][] createBasis() {
		double[][] basis = new double[BAND_CENTERS.length][PROTOTYPES.length];
		for (int band = 0; band < BAND_CENTERS.length; band++) {
			for (int section = 0; section < PROTOTYPES.length; section++) {
				basis[band][section] =
					PROTOTYPES[section].magnitudeDb(BAND_CENTERS[band], SAMPLE_RATE);
			}
		}
		return basis;
	}

	private static double[] solve(double[][] matrix, double[] values) {
		double[][] a = new double[matrix.length][matrix.length + 1];
		for (int row = 0; row < matrix.length; row++) {
			System.arraycopy(matrix[row], 0, a[row], 0, matrix.length);
			a[row][matrix.length] = values[row];
		}
		for (int column = 0; column < matrix.length; column++) {
			int pivot = column;
			for (int row = column + 1; row < matrix.length; row++) {
				if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
					pivot = row;
				}
			}
			if (Math.abs(a[pivot][column]) < 1.0e-12) {
				return null;
			}
			double[] swap = a[column];
			a[column] = a[pivot];
			a[pivot] = swap;
			double divisor = a[column][column];
			for (int k = column; k <= matrix.length; k++) {
				a[column][k] /= divisor;
			}
			for (int row = 0; row < matrix.length; row++) {
				if (row == column) {
					continue;
				}
				double factor = a[row][column];
				for (int k = column; k <= matrix.length; k++) {
					a[row][k] -= factor * a[column][k];
				}
			}
		}
		double[] result = new double[matrix.length];
		for (int row = 0; row < matrix.length; row++) {
			result[row] = a[row][matrix.length];
		}
		return result;
	}

	private static double pressureReflection(double absorption) {
		double alpha = Double.isFinite(absorption)
			? Math.max(0.0, Math.min(1.0, absorption)) : 0.0;
		return Math.sqrt(Math.max(0.0, 1.0 - alpha));
	}

	private static double relativeDb(double coefficient, double reference) {
		if (!(coefficient > 0.0) || !(reference > 0.0)) {
			return -MAX_TARGET_DB;
		}
		return 20.0 * Math.log10(coefficient / reference);
	}

	private static double clampTarget(double db) {
		if (!Double.isFinite(db)) {
			return 0.0;
		}
		return Math.max(-MAX_TARGET_DB, Math.min(MAX_TARGET_DB, db));
	}

	private static double maxAbs(double[] values) {
		double max = 0.0;
		for (double value : values) {
			max = Math.max(max, Math.abs(value));
		}
		return max;
	}
}
