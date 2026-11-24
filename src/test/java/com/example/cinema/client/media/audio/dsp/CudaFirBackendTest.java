package com.example.cinema.client.media.audio.dsp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optional GPU smoke test. Skipped when no NVIDIA driver/CUDA device is
 * available, so the normal test suite still runs on AMD/Intel/macOS.
 */
class CudaFirBackendTest {
	@Test
	void firstBlockMatchesCpu() {
		int streams = 6;
		int frames = 2048;
		int taps = 4096;
		CudaFirBackend cuda;
		try {
			cuda = new CudaFirBackend(streams, frames, taps);
		} catch (Throwable t) {
			t.printStackTrace();
			Assumptions.assumeTrue(false, "CUDA unavailable: " + t);
			return;
		}
		try {
			cuda.prewarm();
			MinimumPhaseFir[] filters = new MinimumPhaseFir[streams];
			float[][] inputs = new float[streams][frames];
			float[][] outputs = new float[streams][frames];
			Random random = new Random(7);
			for (int stream = 0; stream < streams; stream++) {
				float[] coefficients = new float[taps];
				for (int k = 0; k < taps; k++) {
					coefficients[k] = (float) ((random.nextDouble() - 0.5) * 0.002);
				}
				coefficients[0] = 0.5f;
				filters[stream] = new MinimumPhaseFir(coefficients, 1.0, 0.0, 0.0,
					"test", 0.0, 0.0);
				for (int i = 0; i < frames; i++) {
					inputs[stream][i] = (float) (random.nextDouble() * 0.5 - 0.25);
				}
			}

			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.setFilter(stream, filters[stream]);
				cuda.process(stream, inputs[stream], 0, frames, outputs[stream]);
			}
			cuda.endBlock();

			for (int stream = 0; stream < streams; stream++) {
				FirProcessor cpu = new FirProcessor();
				cpu.setFilter(filters[stream]);
				float[] expected = new float[frames];
				cpu.process(inputs[stream], 1, 0, 0, frames, expected);
				double maxError = 0.0;
				for (int i = 0; i < frames; i++) {
					maxError = Math.max(maxError,
						Math.abs(expected[i] - outputs[stream][i]));
				}
				assertTrue(maxError < 1.0e-4,
					"stream " + stream + " max error " + maxError);
			}
		} finally {
			cuda.close();
		}
	}

	@Test
	void secondBlockMatchesCpuTail() {
		int streams = 6;
		int frames = 2048;
		int taps = 4096;
		CudaFirBackend cuda;
		try {
			cuda = new CudaFirBackend(streams, frames, taps);
		} catch (Throwable t) {
			Assumptions.assumeTrue(false, "CUDA unavailable: " + t);
			return;
		}
		try {
			cuda.prewarm();
			MinimumPhaseFir[] filters = new MinimumPhaseFir[streams];
			float[][][] inputs = new float[2][streams][frames];
			float[][][] outputs = new float[2][streams][frames];
			Random random = new Random(11);
			for (int stream = 0; stream < streams; stream++) {
				float[] coefficients = new float[taps];
				for (int k = 0; k < taps; k++) {
					coefficients[k] = (float) ((random.nextDouble() - 0.5) * 0.002);
				}
				coefficients[0] = 0.5f;
				filters[stream] = new MinimumPhaseFir(coefficients, 1.0, 0.0, 0.0,
					"test", 0.0, 0.0);
				for (int block = 0; block < 2; block++) {
					for (int i = 0; i < frames; i++) {
						inputs[block][stream][i] =
							(float) (random.nextDouble() * 0.5 - 0.25);
					}
				}
			}

			for (int block = 0; block < 2; block++) {
				cuda.beginBlock(frames);
				for (int stream = 0; stream < streams; stream++) {
					if (block == 0) {
						cuda.setFilter(stream, filters[stream]);
					}
					cuda.process(stream, inputs[block][stream], 0, frames,
						outputs[block][stream]);
				}
				cuda.endBlock();
			}

			for (int stream = 0; stream < streams; stream++) {
				FirProcessor cpu = new FirProcessor();
				cpu.setFilter(filters[stream]);
				for (int block = 0; block < 2; block++) {
					float[] expected = new float[frames];
					cpu.process(inputs[block][stream], 1, 0, 0, frames, expected);
					double maxError = 0.0;
					for (int i = 0; i < frames; i++) {
						maxError = Math.max(maxError,
							Math.abs(expected[i] - outputs[block][stream][i]));
					}
					assertTrue(maxError < 1.0e-4,
						"stream " + stream + " block " + block
							+ " max error " + maxError);
				}
			}
		} finally {
			cuda.close();
		}
	}

	@Test
	void filterChangeCrossfadeIsCorrect() {
		int streams = 12;
		int frames = 2048;
		int taps = 4096;
		CudaFirBackend cuda;
		try {
			cuda = new CudaFirBackend(streams, frames, taps);
		} catch (Throwable t) {
			Assumptions.assumeTrue(false, "CUDA unavailable: " + t);
			return;
		}
		try {
			cuda.prewarm();
			MinimumPhaseFir[] first = new MinimumPhaseFir[streams];
			MinimumPhaseFir[] second = new MinimumPhaseFir[streams];
			float[][][] inputs = new float[3][streams][frames];
			float[][][] outputs = new float[3][streams][frames];
			Random random = new Random(17);
			for (int stream = 0; stream < streams; stream++) {
				first[stream] = randomFilter(random, taps, 0.5f);
				second[stream] = randomFilter(random, taps, -0.25f);
				for (int block = 0; block < 3; block++) {
					for (int i = 0; i < frames; i++) {
						inputs[block][stream][i] =
							(float) (random.nextDouble() * 0.5 - 0.25);
					}
				}
			}

			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.setFilter(stream, first[stream]);
				cuda.process(stream, inputs[0][stream], 0, frames,
					outputs[0][stream]);
			}
			cuda.endBlock();

			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.setFilter(stream, second[stream]);
				cuda.process(stream, inputs[1][stream], 0, frames,
					outputs[1][stream]);
			}
			cuda.endBlock();

			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.process(stream, inputs[2][stream], 0, frames,
					outputs[2][stream]);
			}
			cuda.endBlock();

			// Reference: direct convolution with the same raw input history
			// the CUDA backend keeps, including the 1024-frame crossfade.
			float[] history = new float[taps - 1];
			for (int block = 0; block < 3; block++) {
				float[] current = block == 0 ? first[0].taps() : second[0].taps();
				float[] old = block == 0 ? second[0].taps() : first[0].taps();
				float[] expected = directConvolution(history, inputs[block][0],
					current, old, block == 1 ? 1024 : 0);
				double maxError = 0.0;
				for (int i = 0; i < frames; i++) {
					maxError = Math.max(maxError,
						Math.abs(expected[i] - outputs[block][0][i]));
				}
				assertTrue(maxError < 1.0e-4,
					"stream 0 block " + block + " max error " + maxError);
				updateHistory(history, inputs[block][0]);
			}
		} finally {
			cuda.close();
		}
	}

	@Test
	void smallCrossfadeDebug() {
		int streams = 12;
		int frames = 2048;
		int taps = 4096;
		CudaFirBackend cuda;
		try {
			cuda = new CudaFirBackend(streams, frames, taps);
		} catch (Throwable t) {
			Assumptions.assumeTrue(false, "CUDA unavailable: " + t);
			return;
		}
		try {
			cuda.prewarm();
			float[] firstTaps = new float[taps];
			float[] secondTaps = new float[taps];
			firstTaps[0] = 1.0f;
			secondTaps[1] = 1.0f;
			MinimumPhaseFir first = new MinimumPhaseFir(firstTaps, 1.0, 0.0,
				0.0, "first", 0.0, 0.0);
			MinimumPhaseFir second = new MinimumPhaseFir(secondTaps, 1.0, 0.0,
				0.0, "second", 0.0, 0.0);
			float[] in0 = new float[frames];
			float[] in1 = new float[frames];
			for (int i = 0; i < frames; i++) {
				in0[i] = (float) Math.sin(i * 0.03);
				in1[i] = (float) Math.sin(i * 0.07 + 0.4);
			}
			float[] out0 = new float[frames];
			float[] out1 = new float[frames];
			float[][] other0 = new float[streams][frames];
			float[][] other1 = new float[streams][frames];
			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.setFilter(stream, first);
				cuda.process(stream, in0, 0, frames,
					stream == 0 ? out0 : other0[stream]);
			}
			cuda.endBlock();
			cuda.beginBlock(frames);
			for (int stream = 0; stream < streams; stream++) {
				cuda.setFilter(stream, second);
				cuda.process(stream, in1, 0, frames,
					stream == 0 ? out1 : other1[stream]);
			}
			cuda.endBlock();

			float[] history = new float[taps - 1];
			float[] expected0 = directConvolution(history, in0,
				first.taps(), second.taps(), 0);
			updateHistory(history, in0);
			float[] expected1 = directConvolution(history, in1,
				second.taps(), first.taps(), 1024);
			double error0 = 0.0;
			double error1 = 0.0;
			for (int i = 0; i < frames; i++) {
				error0 = Math.max(error0, Math.abs(expected0[i] - out0[i]));
				error1 = Math.max(error1, Math.abs(expected1[i] - out1[i]));
			}
			System.out.printf("small crossfade: block0 error %.8f, block1 error %.8f%n",
				error0, error1);
			assertTrue(error0 < 1.0e-5, "block0 error " + error0);
			assertTrue(error1 < 1.0e-5, "block1 error " + error1);
		} finally {
			cuda.close();
		}
	}

	private static float[] directConvolution(float[] history, float[] input,
											 float[] currentTaps,
											 float[] oldTaps, int fade) {
		int frames = input.length;
		int tapCount = currentTaps.length;
		float[] combined = new float[history.length + frames];
		System.arraycopy(history, 0, combined, 0, history.length);
		System.arraycopy(input, 0, combined, history.length, frames);
		float[] out = new float[frames];
		for (int n = 0; n < frames; n++) {
			float acc = 0.0f;
			for (int k = 0; k < tapCount; k++) {
				acc = Math.fma(combined[n + history.length - k], currentTaps[k], acc);
			}
			if (fade > 0 && n < fade) {
				float oldAcc = 0.0f;
				for (int k = 0; k < tapCount; k++) {
					oldAcc = Math.fma(combined[n + history.length - k],
						oldTaps[k], oldAcc);
				}
				float oldWeight = (fade - 1 - n) / 1023.0f;
				acc = acc * (1.0f - oldWeight) + oldAcc * oldWeight;
			}
			out[n] = acc;
		}
		return out;
	}

	private static void updateHistory(float[] history, float[] input) {
		int keep = history.length;
		if (input.length >= keep) {
			System.arraycopy(input, input.length - keep, history, 0, keep);
			return;
		}
		System.arraycopy(history, input.length, history, 0, keep - input.length);
		System.arraycopy(input, 0, history, keep - input.length, input.length);
	}

	private static MinimumPhaseFir randomFilter(Random random, int taps,
												 float directTap) {
		float[] coefficients = new float[taps];
		for (int k = 0; k < taps; k++) {
			coefficients[k] = (float) ((random.nextDouble() - 0.5) * 0.002);
		}
		coefficients[0] = directTap;
		return new MinimumPhaseFir(coefficients, 1.0, 0.0, 0.0,
			"test", 0.0, 0.0);
	}
}
