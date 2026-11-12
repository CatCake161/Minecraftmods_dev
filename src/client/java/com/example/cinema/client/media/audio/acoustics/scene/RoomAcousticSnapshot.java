package com.example.cinema.client.media.audio.acoustics.scene;

import com.example.cinema.client.media.audio.acoustics.room.AcousticRoom;

import java.util.List;

/** Immutable A1 snapshot for debug output and future A2 audio consumers. */
public record RoomAcousticSnapshot(long version, long gameTime, AcousticRoom room,
								   AcousticListener listener,
								   List<AcousticSpeaker> speakers) {
	public RoomAcousticSnapshot {
		speakers = speakers == null ? List.of() : List.copyOf(speakers);
	}
}
