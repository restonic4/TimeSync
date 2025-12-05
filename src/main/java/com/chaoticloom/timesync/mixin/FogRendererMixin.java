package com.chaoticloom.timesync.mixin;

import com.chaoticloom.timesync.ClientWeatherController;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FogRenderer.class)
public class FogRendererMixin {

    /**
     * FogRenderer uses getRainLevel to determine how gray/thick the fog should be.
     * We redirect it to apply our cloudiness factor.
     */
    @Redirect(
            method = "setupColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"
            )
    )
    private static float injectCustomCloudinessInFog(ClientLevel instance, float partialTick) {
        float vanillaRain = instance.getRainLevel(partialTick);
        float customClouds = ClientWeatherController.getCloudiness();

        return Math.max(vanillaRain, customClouds);
    }
}