package com.example.cinema.client.media.audio.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host-side accumulator for the ray-traced order &gt;= 3 late field.
 *
 * <p>The CUDA tracer returns one record per (ray, bounce); a single pass of a
 * few thousand rays is far too noisy to publish directly, so several passes
 * with different ray sets are merged here before they reach
 * {@code LateReflectionBus}. Records are folded into a fixed grid of
 * {@code 6 channels x 64 delay bins x 256 direction bins}:
 *
 * <ul>
 *   <li>delay bins are {@value #DELAY_BIN_SECONDS} s wide and covered in the
 *       same listener-relative delay domain the bus consumes;</li>
 *   <li>direction bins use an octahedral map, so the 256 bins span the whole
 *       sphere without polar clustering (a bin is roughly 7 degrees across,
 *       which is below the 15 degree premium de-duplication angle).</li>
 * </ul>
 *
 * <p>Each bin keeps the energy sum, plus energy-weighted sums of delay,
 * direction, high-band coefficient and order, so the published tap is the
 * energy centroid of everything that landed in the bin instead of an arbitrary
 * representative hit.
 *
 * <p>Energy normalization is an unbiased pass average: a bin that saw energy
 * {@code E} over {@code P} accumulated passes publishes an amplitude
 * {@code sqrt(E / P)}, i.e. the RMS of the per-pass amplitudes. Summing the
 * passes would raise the level with the pass count; dividing the amplitudes
 * by {@code P} would scale with the square root and lose energy. Merging bins
 * (several hits in one bin) uses {@code sqrt(sum of gain^2)} because the
 * diffuse tail is incoherent, so powers add.
 */
public final class RtLateFieldBins {
	public static final int CHANNELS = 6;
	/** 10 ms per bin, 64 bins = 640 ms, above the late-bus delay ceiling. */
	public static final int DELAY_BINS = 128;
	public static final double DELAY_BIN_SECONDS = 0.010;
	/** 256 octahedral cells over the sphere (~7 degrees across). */
	public static final int DIR_BINS = 256;
	public static final int DIR_GRID = 16;
	public static final int BIN_COUNT = CHANNELS * DELAY_BINS * DIR_BINS;
	/** Longest delay a bin can hold, in seconds. */
	public static final double MAX_DELAY_SECONDS = DELAY_BINS * DELAY_BIN_SECONDS;

	/**
	 * One merged late tap: the energy centroid of a bin. {@code direction} is
	 * the world-space listener-to-hit unit vector, like
	 * {@code LateReflectionBus.Tap#direction()}.
	 */
	public record BinTap(int channel, double delaySeconds, double gain,
						 float[] direction, double high, int material,
						 int order) {
	}

	/** Sum of {@code gain^2} over every accumulated hit in the bin. */
	private final double[] energy = new double[BIN_COUNT];
	private final float[] delaySum = new float[BIN_COUNT];
	private final float[] dirX = new float[BIN_COUNT];
	private final float[] dirY = new float[BIN_COUNT];
	private final float[] dirZ = new float[BIN_COUNT];
	private final float[] highSum = new float[BIN_COUNT];
	private final float[] orderSum = new float[BIN_COUNT];
	/** Material of the strongest single hit; the bin's material vote. */
	private final int[] material = new int[BIN_COUNT];
	private final float[] materialPeak = new float[BIN_COUNT];
	private int passes;

	public RtLateFieldBins() {
		Arrays.fill(material, -1);
	}

	/** Clears every bin and the pass count. */
	public void reset() {
		Arrays.fill(energy, 0.0);
		Arrays.fill(delaySum, 0.0f);
		Arrays.fill(dirX, 0.0f);
		Arrays.fill(dirY, 0.0f);
		Arrays.fill(dirZ, 0.0f);
		Arrays.fill(highSum, 0.0f);
		Arrays.fill(orderSum, 0.0f);
		Arrays.fill(material, -1);
		Arrays.fill(materialPeak, 0.0f);
		passes = 0;
	}

	/** Number of completed passes folded into the bins. */
	public int passes() {
		return passes;
	}

	/** Sum of every bin's energy; equals {@code passes * mean energy}. */
	public double rawEnergy() {
		double sum = 0.0;
		for (double value : energy) {
			sum += value;
		}
		return sum;
	}

	/** Pass-averaged energy of the whole field. */
	public double meanEnergy() {
		return passes <= 0 ? 0.0 : rawEnergy() / passes;
	}

	/**
	 * Folds one hit into the bins.
	 *
	 * @param delaySeconds listener-relative delay the bus will use, in seconds
	 * @return false when the hit was rejected (no speaker, no energy, or
	 *         outside the delay window)
	 */
	public boolean accumulate(CudaRayTracer.Hit hit, double delaySeconds) {
		if (hit == null) {
			return false;
		}
		int channel = hit.channel();
		if (channel < 0 || channel >= CHANNELS) {
			return false;
		}
		float gain = hit.gain();
		if (!(gain > 0.0f) || !Float.isFinite(gain)) {
			return false;
		}
		if (!Double.isFinite(delaySeconds) || delaySeconds < 0.0) {
			return false;
		}
		int delayBin = (int) Math.floor(delaySeconds / DELAY_BIN_SECONDS);
		if (delayBin < 0 || delayBin >= DELAY_BINS) {
			return false;
		}
		int index = index(channel, delayBin,
			directionBin(hit.dirX(), hit.dirY(), hit.dirZ()));
		double power = (double) gain * gain;
		energy[index] += power;
		delaySum[index] += (float) (power * delaySeconds);
		dirX[index] += (float) (power * hit.dirX());
		dirY[index] += (float) (power * hit.dirY());
		dirZ[index] += (float) (power * hit.dirZ());
		highSum[index] += (float) (power * hit.high());
		orderSum[index] += (float) (power * hit.order());
		if (power > materialPeak[index]) {
			materialPeak[index] = (float) power;
			material[index] = hit.material();
		}
		return true;
	}

	/** Marks the folded hits as one complete pass. */
	public void finishPass() {
		passes++;
	}

	/** Converts every non-empty bin into a pass-averaged late tap. */
	public List<BinTap> taps(double minGain) {
		if (passes <= 0) {
			return List.of();
		}
		double floor = Math.max(0.0, minGain);
		List<BinTap> taps = new ArrayList<>();
		for (int channel = 0; channel < CHANNELS; channel++) {
			for (int delayBin = 0; delayBin < DELAY_BINS; delayBin++) {
				for (int dirBin = 0; dirBin < DIR_BINS; dirBin++) {
					int index = index(channel, delayBin, dirBin);
					double binEnergy = energy[index];
					if (binEnergy <= 0.0) {
						continue;
					}
					// Unbiased pass average: the bin's amplitude is the RMS of
					// the per-pass amplitudes, so N passes do not raise level.
					double gain = Math.sqrt(binEnergy / passes);
					if (!(gain > floor)) {
						continue;
					}
					float sx = dirX[index];
					float sy = dirY[index];
					float sz = dirZ[index];
					double length = Math.sqrt((double) sx * sx
						+ (double) sy * sy + (double) sz * sz);
					if (!(length > 1.0e-9)) {
						continue;
					}
					double delay = delaySum[index] / binEnergy;
					double high = highSum[index] / binEnergy;
					int order = (int) Math.round(orderSum[index] / binEnergy);
					taps.add(new BinTap(channel,
						Math.max(0.0, Math.min(MAX_DELAY_SECONDS, delay)),
						gain,
						new float[]{(float) (sx / length),
							(float) (sy / length), (float) (sz / length)},
						Math.max(0.0, Math.min(1.0, high)),
						material[index],
						Math.max(0, Math.min(5, order))));
				}
			}
		}
		return taps;
	}

	/** Pass-averaged energy of one bin, for diagnostics and tests. */
	public double meanBinEnergy(int channel, int delayBin, int dirBin) {
		if (passes <= 0) {
			return 0.0;
		}
		return energy[index(channel, delayBin, dirBin)] / passes;
	}

	/** Flat bin index: {@code (channel * DELAY_BINS + delayBin) * DIR_BINS + dirBin}. */
	public static int index(int channel, int delayBin, int dirBin) {
		return (channel * DELAY_BINS + delayBin) * DIR_BINS + dirBin;
	}

	/**
	 * Octahedral direction quantization (Meyer et al.). Poles map to the four
	 * corners of the square, so no cell degenerates at the sphere's top or
	 * bottom.
	 */
	public static int directionBin(float x, float y, float z) {
		float sum = Math.abs(x) + Math.abs(y) + Math.abs(z);
		if (!(sum > 1.0e-9f) || !Float.isFinite(sum)) {
			return 0;
		}
		float u = x / sum;
		float v = y / sum;
		if (z < 0.0f) {
			float au = Math.abs(u);
			float av = Math.abs(v);
			u = (1.0f - av) * (u >= 0.0f ? 1.0f : -1.0f);
			v = (1.0f - au) * (v >= 0.0f ? 1.0f : -1.0f);
		}
		int iu = (int) Math.floor((u * 0.5f + 0.5f) * DIR_GRID);
		int iv = (int) Math.floor((v * 0.5f + 0.5f) * DIR_GRID);
		iu = Math.max(0, Math.min(DIR_GRID - 1, iu));
		iv = Math.max(0, Math.min(DIR_GRID - 1, iv));
		return iv * DIR_GRID + iu;
	}
}
