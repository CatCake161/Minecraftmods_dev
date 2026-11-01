package com.example.firstmod.block.entity;

import net.minecraft.network.chat.Component;

/** Role assigned by the AV receiver to one speaker block. */
public enum SpeakerRole {
	NONE("none"),
	FL("FL"),
	FR("FR"),
	FC("FC"),
	LFE("LFE"),
	BL("BL"),
	BR("BR");

	private final String label;

	SpeakerRole(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public Component displayName() {
		return Component.literal(label);
	}

	public static SpeakerRole byName(String name) {
		for (SpeakerRole role : values()) {
			if (role.name().equals(name)) {
				return role;
			}
		}
		return NONE;
	}
}
