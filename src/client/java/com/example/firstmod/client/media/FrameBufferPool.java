package com.example.firstmod.client.media;

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
	private static final ArrayDeque<ByteBuffer> FREE = new ArrayDeque<>();
	private static final Set<ByteBuffer> FREE_IDS =
		Collections.newSetFromMap(new IdentityHashMap<>());

	private FrameBufferPool() {
	}

	static synchronized ByteBuffer acquire(int bytes) {
		ByteBuffer buffer = FREE.pollFirst();
		while (buffer != null) {
			FREE_IDS.remove(buffer);
			if (buffer.capacity() >= bytes) {
				buffer.clear();
				buffer.limit(bytes);
				return buffer;
			}
			buffer = FREE.pollFirst();
		}
		return ByteBuffer.allocateDirect(bytes);
	}

	static synchronized void release(ByteBuffer buffer) {
		if (buffer == null || !FREE_IDS.add(buffer)) {
			return;
		}
		buffer.clear();
		if (FREE.size() >= MAX_FREE) {
			FREE_IDS.remove(buffer);
			return;
		}
		FREE.addFirst(buffer);
	}
}
