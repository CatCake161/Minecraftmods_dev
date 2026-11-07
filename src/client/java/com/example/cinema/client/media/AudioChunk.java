package com.example.cinema.client.media;

/**
 * One decoded audio block with interleaved channels.
 *
 * <p>For cinema playback {@code samples} is 5.1 in FFmpeg order:
 * FL, FR, FC, LFE, BL, BR. Samples are normalized 32-bit floats: 1.0 is
 * digital full scale. The values are not clamped, so the DSP chain can keep
 * headroom and let only the final device conversion quantize.
 */
public record AudioChunk(double startPtsSeconds, float[] samples) {
}
