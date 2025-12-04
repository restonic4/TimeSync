package com.chaoticloom.timesync;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;

/**
 * This mod syncs the Minecraft day/night cycle with real time.
 *
 * We create a file per world creation which stores the creation timestamp.
 * This allows us to calculate how many days has passed since creation, since we simulate the day/night cycle.
 *
 * TODO: IMPLEMENT DAYS, right now the day count resets each real life day, which is bad. We store the timestamp to implement this, and, we also should consider multiple scenarios: 1: (The player being online at the world before starting a new day, and then becoming a new day), 2: (The player not being online, the world/server is closed, its stopped the day before and openned a few or 1 day after)
 */
public class TimeSync implements ModInitializer {
    public static final String MOD_ID = "timesync";
    public static final String MOD_NAME = "Time Sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static Long cachedTimestamp = null;
    private static final String FILE_NAME = "TimeSync.txt";

    public static final boolean DEBUG = true;

    @Override
    public void onInitialize() {
        LOGGER.info(MOD_NAME + " Mod Initialized.");
    }

    /**
     * Gets the day progress (0..1) of real world.
     * @return Number between 0 and 1.
     */
    public static double getRealDayProgress() {
        if (DEBUG) {
            long ms = System.currentTimeMillis() % 10_000;
            return ms / 10_000.0;
        }

        LocalTime now = LocalTime.now();
        int totalSecondsOfDay = now.toSecondOfDay(); // 0 to 86399
        return totalSecondsOfDay / 86400.0;
    }

    /**
     * Gets the current minecraft tick time based on the real world time.
     * Minecraft Day: 24000 ticks.
     * Real Day: 86400 seconds.
     * @return The ticks.
     */
    public static long getCurrentTime() {
        // Calculate progress through the real day (0.0 to 1.0)
        double dayProgress = getRealDayProgress();

        // Convert to Minecraft ticks (0 to 24000)
        long targetTick = (long) (dayProgress * 24000L);

        // Apply Offset
        long syncedTime = targetTick - 6000L;
        if (syncedTime < 0) {
            syncedTime += 24000L;
        }

        return syncedTime;
    }

    /** Returns per-world TimeSync file path */
    private static Path getFilePath(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(level.dimension().location().getPath()).resolve(FILE_NAME);
    }

    /**
     * Ensure the per-world TimeSync file exists.
     * If it doesn't, create it with the current global timestamp.
     */
    private static void ensureFile(ServerLevel level) {
        Path file = getFilePath(level);

        try {
            Files.createDirectories(file.getParent());

            if (!Files.exists(file)) {
                long timestamp = System.currentTimeMillis();
                Files.writeString(file, Long.toString(timestamp));
                cachedTimestamp = timestamp;
            }
        } catch (IOException e) {
            LOGGER.error("Error: ", e);
        }
    }

    /**
     * Return the stored timestamp as a number.
     * First call will read the file -> later calls use cached value.
     */
    public static long getWorldCreationTimestamp(ServerLevel level) {
        if (cachedTimestamp != null) {
            return cachedTimestamp;
        }

        Path file = getFilePath(level);
        try {
            if (Files.exists(file)) {
                String content = Files.readString(file).trim();
                cachedTimestamp = Long.parseLong(content);
            } else {
                ensureFile(level); // file missing, recreate
            }
            return cachedTimestamp;
        } catch (IOException | NumberFormatException e) {
            LOGGER.error("Error: ", e);
            return -1; // indicate error
        }
    }
}
