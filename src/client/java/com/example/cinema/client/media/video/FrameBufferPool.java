package com.example.cinema.client.media.video;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Reuses direct frame buffers between the decoder and the render thread.
 *
 * <p>At 4K a single RGBA frame is ~33 MB. Allocating a fresh direct buffer for
 * every frame at 60 fps creates gigabytes per second of native-memory churn;
 * this bounded pool keeps the working set stable.
 */
final class FrameBufferPool {
	private static final int MAX_FREE = 12;
	private static final ArrayDeque<ByteBuffer> freeBuffers = new ArrayDeque<>();
	private static final Set<ByteBuffer> freeBufferIds =
		Collections.newSetFromMap(new IdentityHashMap<>());

	private FrameBufferPool() {
	}

	static synchronized ByteBuffer acquire(int bytes) {
		ByteBuffer buffer = freeBuffers.pollFirst();
		while (buffer != null) {
			freeBufferIds.remove(buffer);
			if (buffer.capacity() >= bytes) {
				buffer.clear();
				buffer.limit(bytes);
				return buffer;
			}
			buffer = freeBuffers.pollFirst();
		}
		return ByteBuffer.allocateDirect(bytes);
	}

	static synchronized void release(ByteBuffer buffer) {
		if (buffer == null || !freeBufferIds.add(buffer)) {
			return;
		}
		buffer.clear();
		if (freeBuffers.size() >= MAX_FREE) {
			freeBufferIds.remove(buffer);
			return;
		}
		freeBuffers.addFirst(buffer);
	}
}
