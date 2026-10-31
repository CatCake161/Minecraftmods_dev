package com.example.firstmod.client.media;

/**
 * One decoded audio block with interleaved channels.
 *
 * <p>For cinema playback {@code samples} is 5.1 in FFmpeg order:
 * FL, FR, FC, LFE, BL, BR.
 */
public record AudioChunk(double startPtsSeconds, short[] samples) {
}
