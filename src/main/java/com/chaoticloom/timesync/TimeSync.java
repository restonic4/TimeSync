package com.chaoticloom.timesync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * This mod syncs the Minecraft day/night cycle with real time.
 *
 * We create a file per world creation which stores the creation timestamp.
 * This allows us to calculate how many days has passed since creation, since we simulate the day/night cycle.
 * */

// TODO: IMPROVE TABLIST 3 DAY PREDICTION, IT LOOKS AT THE CURRENT HOUR AND ADS 24, THATS BAD PREDICTION
public class TimeSync implements ModInitializer {
    public static final String MOD_ID = "timesync";
    public static final String MOD_NAME = "Time Sync";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceLocation SYNC_PACKET_ID = new ResourceLocation(MOD_ID, "sync_timestamp");

    static Long cachedTimestamp = null;
    private static final String FILE_NAME = "TimeSync.txt";

    // Rain / Thunder
    private int tickCounter = 0;
    private static final int INTERVAL = 2400; // 2 Minutes

    public static final boolean DEBUG = false;
    public static final long DEBUG_SECONDS_PER_DAY = 60000L;

    @Override
    public void onInitialize() {
        LOGGER.info(MOD_NAME + " Mod Initialized.");

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerLevel level = server.overworld();
            long timestamp = getWorldCreationTimestamp(level);

            // Create packet
            FriendlyByteBuf buf = PacketByteBufs.create();
            buf.writeLong(timestamp);

            // Send to the specific player
            ServerPlayNetworking.send(handler.getPlayer(), SYNC_PACKET_ID, buf);
        });

        ServerWorldEvents.UNLOAD.register((minecraftServer, serverLevel) -> {
            cachedTimestamp = null;
        });

        TabListManager.init();

        ServerWorldEvents.LOAD.register((server, level) -> {
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                System.out.println("World loaded. Syncing weather...");
                WeatherService.updateWeather(level);
            }
        });

        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                tickCounter++;
                if (tickCounter >= INTERVAL) {
                    tickCounter = 0;
                    WeatherService.updateWeather(level);
                }
            }
        });
    }

    /**
     * Calculates the total Minecraft time (ticks) based on real-world elapsed time.
     * This handles day rollovers and offline time-skips automatically.
     * * Logic:
     * Real 06:00 AM = MC Tick 0 (Sunrise).
     * We find the 06:00 AM anchor of the creation date and count ticks from there.
     */
    public static long getSyncedTime(Level level) {
        if (DEBUG) {
            // Debug: Fast cycle to test transitions (10 seconds = 1 day)
            return (System.currentTimeMillis() / DEBUG_SECONDS_PER_DAY) * 24000L + (long)((System.currentTimeMillis() % (int) DEBUG_SECONDS_PER_DAY) / (double) DEBUG_SECONDS_PER_DAY * 24000);
        }

        long creationMillis = getWorldCreationTimestamp(level);
        long nowMillis = System.currentTimeMillis();

        // Calculate the Anchor: 06:00 AM on the day of creation
        long anchor6AM = getSixAmAnchor(creationMillis);

        // Calculate elapsed time since that 6 AM anchor
        long elapsedMillis = nowMillis - anchor6AM;
        if (elapsedMillis < 0) elapsedMillis = 0;

        // Convert to Ticks.
        // 1 Real Day (86,400,000 ms) = 24,000 MC Ticks
        // Ratio = 3,600 ms per 1 Tick.
        return elapsedMillis / 3600L;
    }

    /**
     * returns the epoch millis of 06:00 AM on the day of the provided timestamp.
     */
    private static long getSixAmAnchor(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());

        // Snap to 6:00 AM of that specific date
        ZonedDateTime sixAm = zdt.toLocalDate().atTime(6, 0).atZone(ZoneId.systemDefault());

        return sixAm.toInstant().toEpochMilli();
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
    public static long getWorldCreationTimestamp(Level level) {
        if (cachedTimestamp != null) {
            return cachedTimestamp;
        }

        // Gets the world time depending on the networking side
        if (level instanceof ClientLevel clientLevel) {
            // We return -1 if the packet hasn't arrived yet.
            // The Client Packet Handler will update cachedTimestamp automatically.
            return -1;
        } else if (level instanceof ServerLevel serverLevel) {
            Path file = getFilePath(serverLevel);
            try {
                if (Files.exists(file)) {
                    String content = Files.readString(file).trim();
                    cachedTimestamp = Long.parseLong(content);
                } else {
                    ensureFile(serverLevel); // file missing, recreate
                }
                return cachedTimestamp;
            } catch (IOException | NumberFormatException e) {
                LOGGER.error("Error: ", e);
                return -1; // indicate error
            }
        }

        return -1;
    }
}
