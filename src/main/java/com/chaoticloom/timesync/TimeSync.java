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
     * Calculates the total Minecraft time (ticks).
     * * Strategy:
     * 1. Calculate how many full days have passed since creation (for the day counter).
     * 2. Calculate how much time has passed since 6:00 AM *TODAY* (for the time of day).
     * * This fixes the +1 Hour / DST bug by recalculating the 6:00 AM anchor every single day.
     */
    public static long getSyncedTime(Level level) {
        if (DEBUG) {
            return (System.currentTimeMillis() / DEBUG_SECONDS_PER_DAY) * 24000L + (long)((System.currentTimeMillis() % (int) DEBUG_SECONDS_PER_DAY) / (double) DEBUG_SECONDS_PER_DAY * 24000);
        }

        long creationMillis = getWorldCreationTimestamp(level);
        if (creationMillis == -1) return level.getDayTime(); // Not synced yet

        long nowMillis = System.currentTimeMillis();
        ZoneId zone = ZoneId.systemDefault();

        // 1. Get the timestamps as ZonedDateTimes
        ZonedDateTime creationTime = Instant.ofEpochMilli(creationMillis).atZone(zone);
        ZonedDateTime nowTime = Instant.ofEpochMilli(nowMillis).atZone(zone);

        // 2. Determine the "Current Solar Day"
        // If it is before 6:00 AM, we are still technically in the "previous" Minecraft day.
        ZonedDateTime currentSolarDay = nowTime;
        if (nowTime.getHour() < 6) {
            currentSolarDay = nowTime.minusDays(1);
        }

        // 3. Calculate 6:00 AM for the Current Solar Day
        ZonedDateTime today6AM = currentSolarDay.toLocalDate().atTime(6, 0).atZone(zone);
        long today6AmMillis = today6AM.toInstant().toEpochMilli();

        // 4. Calculate Time of Day (Ticks since 6 AM today)
        long elapsedSince6AM = nowMillis - today6AmMillis;
        if (elapsedSince6AM < 0) elapsedSince6AM = 0;
        long timeOfDayTicks = elapsedSince6AM / 3600L; // 3600 ms = 1 tick

        // 5. Calculate Total Days Passed (from creation to the current solar day)
        // We use ChronoUnit.DAYS to count calendar days safely
        long daysPassed = java.time.temporal.ChronoUnit.DAYS.between(
                creationTime.toLocalDate(),
                currentSolarDay.toLocalDate()
        );

        if (daysPassed < 0) daysPassed = 0;

        // 6. Combine: Total Days * 24000 + Current Time Ticks
        return (daysPassed * 24000L) + timeOfDayTicks;
    }

    /**
     * returns the epoch millis of 06:00 AM on the day of the provided timestamp.
     */
    private static long getSixAmAnchor(long timestamp) {
        Instant instant = Instant.ofEpochMilli(timestamp);
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime zdt = instant.atZone(zone);

        // Snap to 6:00 AM of that specific date
        ZonedDateTime sixAm = zdt.toLocalDate().atTime(6, 0).atZone(zone);

        // FIX: If the creation timestamp is BEFORE 6:00 AM (e.g. 01:00 AM),
        // the calculated "sixAm" is in the future relative to the creation.
        // This means the "Solar Day" actually started yesterday at 6 AM.
        if (sixAm.toInstant().toEpochMilli() > timestamp) {
            sixAm = sixAm.minusDays(1);
        }

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
