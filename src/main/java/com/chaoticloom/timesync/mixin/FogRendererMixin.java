package com.chaoticloom.timesync.mixin;

import com.chaoticloom.timesync.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void modifyFogDensity(Camera camera, FogRenderer.FogMode fogMode, float viewDistance, boolean thickFog, float partialTick, CallbackInfo ci) {
        // 1. Safety Check: Only apply to Terrain fog (not lava, water, or blindness effect)
        if (fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }

        // 2. Retrieve your current Weather State
        // Assuming you have a static way to access the current weather.
        // If not, you might need to access it via a client instance.
        int wmo = WeatherService.getWMO();
        WeatherState weatherState = WeatherState.fromCode(wmo);
        WeatherStateStrength weatherStateStrength = WeatherStateStrength.fromCode(wmo);

        if (weatherState == WeatherState.FOG) {
            float start;
            float end;

            // 4. Calculate Density based on Strength
            // Note: 'viewDistance' is roughly the render distance in blocks (e.g., 16 chunks = 256 blocks)
            switch (weatherStateStrength) {
                case SLIGHT -> {
                    // Light fog: Starts a bit away, ends near the edge of view
                    start = 0f;
                    end = viewDistance * 0.75f;
                }
                case MODERATE -> {
                    // Standard fog: Starts close, obscures mid-distance
                    start = 0f;
                    end = viewDistance * 0.50f;
                }
                case INTENSE -> {
                    start = 0f;
                    end = 24.0f; // Fixed short distance regardless of render distance
                }

                // Fallback (shouldn't happen if enum is complete)
                default -> {
                    start = 0f;
                    end = viewDistance;
                }
            }

            // 5. Apply the override
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
        }
    }
}