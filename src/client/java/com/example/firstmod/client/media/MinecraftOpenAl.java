package com.example.firstmod.client.media;

import com.example.firstmod.client.mixin.LibraryAccessor;
import com.example.firstmod.client.mixin.SoundEngineAccessor;
import com.example.firstmod.client.mixin.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngine;

/** Accesses the OpenAL device that Minecraft's SoundEngine already owns. */
public final class MinecraftOpenAl {
	private MinecraftOpenAl() {
	}

	public static long device() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return 0L;
		}
		SoundEngine soundEngine = ((SoundManagerAccessor) minecraft.getSoundManager())
			.firstmod$getSoundEngine();
		if (soundEngine == null) {
			return 0L;
		}
		Library library = ((SoundEngineAccessor) soundEngine).firstmod$getLibrary();
		if (library == null) {
			return 0L;
		}
		return ((LibraryAccessor) library).firstmod$getCurrentDevice();
	}
}
