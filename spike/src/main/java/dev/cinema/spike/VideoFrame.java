package dev.cinema.spike;

import java.nio.ByteBuffer;

record VideoFrame(ByteBuffer pixels, int width, int height, double ptsSeconds) {
}
