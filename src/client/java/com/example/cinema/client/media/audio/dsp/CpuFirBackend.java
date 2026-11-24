package com.example.cinema.client.media.audio.dsp;

/** CPU implementation: one {@link FirProcessor} per direct/reflection stream. */
final class CpuFirBackend implements FirBackend {
	private final FirProcessor[] processors;

	CpuFirBackend(int capacity) {
		processors = new FirProcessor[capacity];
		for (int i = 0; i < capacity; i++) {
			processors[i] = new FirProcessor();
		}
	}

	@Override
	public void prewarm() {
	}

	@Override
	public void setFilter(int stream, MinimumPhaseFir filter) {
		processors[stream].setFilter(filter);
	}

	@Override
	public void beginBlock(int frames) {
	}

	@Override
	public void process(int stream, float[] input, int inputOffset, int frames,
						float[] output) {
		processors[stream].process(input, 1, 0, inputOffset, frames, output);
	}

	@Override
	public void endBlock() {
	}

	@Override
	public void reset(int stream) {
		processors[stream].reset();
	}

	@Override
	public void resetAll() {
		for (FirProcessor processor : processors) {
			processor.reset();
		}
	}

	@Override
	public void close() {
	}
}
