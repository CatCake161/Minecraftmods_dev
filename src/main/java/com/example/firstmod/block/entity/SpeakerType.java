package com.example.firstmod.block.entity;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Physical speaker cabinet types. The cabinet decides which channel roles it
 * may take, so a center speaker cannot be assigned to a surround channel and a
 * subwoofer can only handle LFE.
 */
public enum SpeakerType {
	FLOORSTANDING("floorstanding", List.of(
		SpeakerRole.NONE, SpeakerRole.FL, SpeakerRole.FR, SpeakerRole.BL, SpeakerRole.BR)),
	BOOKSHELF("bookshelf", List.of(
		SpeakerRole.NONE, SpeakerRole.FL, SpeakerRole.FR, SpeakerRole.BL, SpeakerRole.BR)),
	CENTER("center", List.of(
		SpeakerRole.NONE, SpeakerRole.FC)),
	SUBWOOFER("subwoofer", List.of(
		SpeakerRole.NONE, SpeakerRole.LFE));

	private final String id;
	private final List<SpeakerRole> allowedRoles;

	SpeakerType(String id, List<SpeakerRole> allowedRoles) {
		this.id = id;
		this.allowedRoles = allowedRoles;
	}

	public String id() {
		return id;
	}

	public Component displayName() {
		return Component.translatable("speaker_type.firstmod." + id);
	}

	public boolean allows(SpeakerRole role) {
		return allowedRoles.contains(role);
	}

	/** Returns the next valid role, skipping roles this cabinet cannot take. */
	public SpeakerRole nextRole(SpeakerRole current) {
		int index = allowedRoles.indexOf(current);
		return allowedRoles.get((index + 1) % allowedRoles.size());
	}

	/** Replaces a role that is not valid for this cabinet with {@link SpeakerRole#NONE}. */
	public SpeakerRole sanitize(SpeakerRole role) {
		return role == null || !allows(role) ? SpeakerRole.NONE : role;
	}
}
