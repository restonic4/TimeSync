package com.chaoticloom.timesync.mixin;

import com.chaoticloom.timesync.ClientWeatherController;
import com.chaoticloom.timesync.TimeSync;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    /*
    Sync time
     */
    @Redirect(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"))
    private void syncTimeWithRealWorld(ClientLevel clientLevel, long newValue) {
        clientLevel.setDayTime(TimeSync.getSyncedTime(clientLevel));
    }

    /**
     * The getSkyColor method calculates the RGB of the sky.
     * It uses 'this.getRainLevel(partialTick)' to determine how much to darken the sky.
     * We redirect that specific call to inject our custom cloudiness.
     */
    @Redirect(
            method = "getSkyColor",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"
            )
    )
    private float injectCustomCloudinessInSkyColor(ClientLevel instance, float partialTick) {
        // Get the actual rain level (so vanilla rain still works)
        float vanillaRain = instance.getRainLevel(partialTick);

        // Get our custom visual override
        float customClouds = ClientWeatherController.getCloudiness();

        // Return whichever is higher.
        // If it's a sunny day (vanilla=0) but we set clouds=1, sky renders gray.
        return Math.max(vanillaRain, customClouds);
    }
}
