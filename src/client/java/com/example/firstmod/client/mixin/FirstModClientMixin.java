package com.example.firstmod.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class FirstModClientMixin {
	@Inject(at = @At("HEAD"), method = "run")
	private void onRun(CallbackInfo info) {
		// Injected at the start of Minecraft.run().
	}
}
