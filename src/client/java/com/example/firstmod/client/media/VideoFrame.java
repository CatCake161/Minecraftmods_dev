package com.example.firstmod.client.media;

import java.nio.ByteBuffer;

/** One decoded video frame in packed RGBA, owned by the cinema player. */
public record VideoFrame(ByteBuffer rgba, int width, int height, double ptsSeconds) {
}
