package com.example.cinema.client.audio.reverb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Vector base amplitude panning for the geometric late bus.
 *
 * <p>Coordinates are listener-relative: x right, y up, z forward negative.
 * The pan direction must point from the listener to the virtual source (the
 * last reflection point), not along the sound propagation direction. The
 * propagation direction is the negative of this vector; using it would mirror
 * left/right and front/back.
 *
 * <p>LFE (channel 3) is excluded from the output target set. LFE source
 * channels still contribute through the main speakers; the separate
 * low-frequency send belongs to A5.
 */
public final class Vbap {
	public static final int LFE_CHANNEL = 3;
	private static final double EPSILON = 1.0e-6;
	private static final double TWO_PI = 2.0 * Math.PI;

	public record Weights(int[] channels, float[] weights) {
		public Weights {
			channels = channels == null ? new int[0] : channels.clone();
			weights = weights == null ? new float[0] : weights.clone();
			if (channels.length != weights.length) {
				channels = new int[0];
				weights = new float[0];
			}
		}

		public boolean isEmpty() {
			return channels.length == 0;
		}
	}

	private record Speaker(int channel, double x, double y, double z,
						   double azimuth) {
	}

	private Vbap() {
	}

	/**
	 * Pans one virtual source direction onto the available physical speakers.
	 * The result is energy-normalized ({@code sum(w_i^2) = 1}) and never
	 * contains the LFE channel.
	 */
	public static Weights pan(float[] direction, float[][] speakerPositions) {
		List<Speaker> speakers = speakers(speakerPositions);
		if (speakers.isEmpty()) {
			return new Weights(new int[0], new float[0]);
		}
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		if (direction != null && direction.length >= 3) {
			x = direction[0];
			y = direction[1];
			z = direction[2];
		}
		double length = Math.sqrt(x * x + y * y + z * z);
		if (!Double.isFinite(length) || length < EPSILON) {
			return diffuse(speakers);
		}
		x /= length;
		y /= length;
		z /= length;
		if (speakers.size() == 1) {
			return single(speakers.get(0));
		}
		Weights exact = exact(speakers, x, y, z);
		if (exact != null) {
			return exact;
		}
		Weights horizontal = horizontal(speakers, x, z);
		if (horizontal != null) {
			return horizontal;
		}
		return nearest(speakers, x, y, z);
	}

	private static List<Speaker> speakers(float[][] positions) {
		List<Speaker> result = new ArrayList<>();
		if (positions == null) {
			return result;
		}
		for (int channel = 0; channel < positions.length; channel++) {
			if (channel == LFE_CHANNEL) {
				continue;
			}
			float[] position = positions[channel];
			if (position == null || position.length < 3) {
				continue;
			}
			double x = position[0];
			double y = position[1];
			double z = position[2];
			double length = Math.sqrt(x * x + y * y + z * z);
			if (!Double.isFinite(length) || length < EPSILON) {
				continue;
			}
			x /= length;
			y /= length;
			z /= length;
			result.add(new Speaker(channel, x, y, z, Math.atan2(x, -z)));
		}
		return result;
	}

	/** Exact 3D VBAP over every speaker triplet; null when no triplet contains the direction. */
	private static Weights exact(List<Speaker> speakers, double x, double y,
								 double z) {
		Weights best = null;
		double bestScore = -Double.MAX_VALUE;
		for (int first = 0; first < speakers.size() - 2; first++) {
			for (int second = first + 1; second < speakers.size() - 1;
				 second++) {
				for (int third = second + 1; third < speakers.size(); third++) {
					Speaker a = speakers.get(first);
					Speaker b = speakers.get(second);
					Speaker c = speakers.get(third);
					double det = det3(a.x, b.x, c.x,
						a.y, b.y, c.y,
						a.z, b.z, c.z);
					if (Math.abs(det) < EPSILON) {
						continue;
					}
					double gainA = det3(x, b.x, c.x,
						y, b.y, c.y,
						z, b.z, c.z) / det;
					double gainB = det3(a.x, x, c.x,
						a.y, y, c.y,
						a.z, z, c.z) / det;
					double gainC = det3(a.x, b.x, x,
						a.y, b.y, y,
						a.z, b.z, z) / det;
					if (gainA < -EPSILON || gainB < -EPSILON
						|| gainC < -EPSILON) {
						continue;
					}
					double norm = Math.sqrt(gainA * gainA + gainB * gainB
						+ gainC * gainC);
					if (norm < EPSILON) {
						continue;
					}
					gainA = Math.max(0.0, gainA) / norm;
					gainB = Math.max(0.0, gainB) / norm;
					gainC = Math.max(0.0, gainC) / norm;
					double score = Math.min(gainA, Math.min(gainB, gainC));
					if (score > bestScore) {
						bestScore = score;
						best = weights(
							new int[]{a.channel(), b.channel(), c.channel()},
							new double[]{gainA, gainB, gainC});
					}
				}
			}
		}
		return best;
	}

	/** Horizontal pair VBAP; null when no adjacent pair brackets the direction. */
	private static Weights horizontal(List<Speaker> speakers, double x,
									  double z) {
		double length = Math.sqrt(x * x + z * z);
		if (length < EPSILON) {
			return null;
		}
		double targetX = x / length;
		double targetZ = z / length;
		double targetAzimuth = Math.atan2(targetX, -targetZ);
		List<Speaker> ring = new ArrayList<>();
		for (Speaker speaker : speakers) {
			double horizontalLength = Math.sqrt(speaker.x * speaker.x
				+ speaker.z * speaker.z);
			if (horizontalLength >= EPSILON) {
				ring.add(speaker);
			}
		}
		if (ring.isEmpty()) {
			return null;
		}
		ring.sort(Comparator.comparingDouble(Speaker::azimuth));
		int count = ring.size();
		if (count == 1) {
			return single(ring.get(0));
		}
		for (int index = 0; index < count; index++) {
			Speaker first = ring.get(index);
			Speaker second = ring.get((index + 1) % count);
			double firstAzimuth = first.azimuth();
			double secondAzimuth = second.azimuth();
			if (index == count - 1) {
				secondAzimuth += TWO_PI;
			}
			double target = targetAzimuth;
			while (target < firstAzimuth) {
				target += TWO_PI;
			}
			while (target >= firstAzimuth + TWO_PI) {
				target -= TWO_PI;
			}
			if (target > secondAzimuth + EPSILON) {
				continue;
			}
			double firstX = Math.sin(firstAzimuth);
			double firstZ = -Math.cos(firstAzimuth);
			double secondX = Math.sin(secondAzimuth);
			double secondZ = -Math.cos(secondAzimuth);
			double det = firstX * secondZ - secondX * firstZ;
			if (Math.abs(det) < EPSILON) {
				continue;
			}
			double gainFirst = (targetX * secondZ - secondX * targetZ) / det;
			double gainSecond = (firstX * targetZ - targetX * firstZ) / det;
			if (gainFirst < -EPSILON || gainSecond < -EPSILON) {
				continue;
			}
			Weights result = weights(
				new int[]{first.channel(), second.channel()},
				new double[]{Math.max(0.0, gainFirst),
					Math.max(0.0, gainSecond)});
			if (!result.isEmpty()) {
				return result;
			}
		}
		return null;
	}

	private static Weights nearest(List<Speaker> speakers, double x, double y,
								   double z) {
		Speaker best = null;
		double bestDot = -Double.MAX_VALUE;
		for (Speaker speaker : speakers) {
			double dot = x * speaker.x + y * speaker.y + z * speaker.z;
			if (dot > bestDot) {
				bestDot = dot;
				best = speaker;
			}
		}
		return best == null ? diffuse(speakers) : single(best);
	}

	private static Weights diffuse(List<Speaker> speakers) {
		int count = speakers.size();
		int[] channels = new int[count];
		float[] weights = new float[count];
		float weight = (float) (1.0 / Math.sqrt(count));
		for (int index = 0; index < count; index++) {
			channels[index] = speakers.get(index).channel();
			weights[index] = weight;
		}
		return new Weights(channels, weights);
	}

	private static Weights single(Speaker speaker) {
		return new Weights(new int[]{speaker.channel()}, new float[]{1.0f});
	}

	private static Weights weights(int[] channels, double[] gains) {
		int count = 0;
		for (double gain : gains) {
			if (gain > EPSILON) {
				count++;
			}
		}
		if (count == 0) {
			return new Weights(new int[0], new float[0]);
		}
		int[] outputChannels = new int[count];
		float[] outputWeights = new float[count];
		double sum = 0.0;
		int output = 0;
		for (int index = 0; index < channels.length; index++) {
			double gain = gains[index];
			if (gain <= EPSILON) {
				continue;
			}
			outputChannels[output] = channels[index];
			outputWeights[output] = (float) gain;
			sum += gain * gain;
			output++;
		}
		double norm = Math.sqrt(sum);
		for (int index = 0; index < count; index++) {
			outputWeights[index] /= (float) norm;
		}
		return new Weights(outputChannels, outputWeights);
	}

	private static double det3(double a00, double a01, double a02,
							   double a10, double a11, double a12,
							   double a20, double a21, double a22) {
		return a00 * (a11 * a22 - a12 * a21)
			- a01 * (a10 * a22 - a12 * a20)
			+ a02 * (a10 * a21 - a11 * a20);
	}
}
