package com.chaoticloom.timesync;

import net.minecraft.util.Mth;

/**
 * Holds the state for custom weather visuals.
 * This should only be accessed on the logical CLIENT.
 */
public class ClientWeatherController {

    // 0.0 = Clear Sky, 1.0 = Fully Gray/Stormy appearance
    private static float targetCloudiness = 0.0f;
    private static float currentCloudiness = 0.0f;

    public static void setCloudiness(float value) {
        targetCloudiness = Mth.clamp(value, 0.0f, 1.0f);
    }

    public static float getCloudiness() {
        return currentCloudiness;
    }

    /**
     * Call this in a client tick event (e.g., ClientTickEvents.END_CLIENT_TICK)
     * to smoothly transition the sky color.
     */
    public static void tick() {
        // Smoothly interpolate current value to target value
        if (currentCloudiness < targetCloudiness) {
            currentCloudiness += 0.01f;
            if (currentCloudiness > targetCloudiness) currentCloudiness = targetCloudiness;
        } else if (currentCloudiness > targetCloudiness) {
            currentCloudiness -= 0.01f;
            if (currentCloudiness < targetCloudiness) currentCloudiness = targetCloudiness;
        }
    }
}