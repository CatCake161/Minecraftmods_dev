package dev.catcake.cinema.spike;

import java.nio.ByteBuffer;

record VideoFrame(ByteBuffer pixels, int width, int height, double ptsSeconds) {
}
