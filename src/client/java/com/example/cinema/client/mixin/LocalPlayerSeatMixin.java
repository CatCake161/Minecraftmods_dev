package com.example.cinema.client.mixin;

import com.example.cinema.entity.SeatEntity;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla LocalPlayer uses interpolated head yaw while riding any vehicle.
 * That makes mouse look feel smoothed/laggy on a stationary cinema seat.
 * Use the live body yaw for our own seat vehicles only.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSeatMixin {
	@Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
	private void cinema$seatViewYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
		LocalPlayer player = (LocalPlayer) (Object) this;
		if (player.getVehicle() instanceof SeatEntity) {
			cir.setReturnValue(player.getYRot());
		}
	}
}
