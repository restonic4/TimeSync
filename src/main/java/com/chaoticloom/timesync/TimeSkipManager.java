package com.chaoticloom.timesync;

import com.chaoticloom.timesync.mixin.MobEffectInstanceAccessor;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.storage.LevelResource;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.chaoticloom.timesync.TimeSync.LOGGER;
import static com.chaoticloom.timesync.TimeSync.MOD_ID;

/*
TODO:
    - Brewing stands
    - Animal growth
    - Breeding cooldowns
    - Villager restocking
    - Sheep wool regrowth
    - Copper oxidation
    - Amethyst cluster growth
    - Hunger
    - Cauldron Filling
 */

public class TimeSkipManager {
    private static final String FILE_NAME = "time_tracker.dat";
    static final String WAS_LOADED_TAG = MOD_ID + ":seen_before";
    private static int tickCounter = 0;
    private static long startUpSavedDiff;

    private static final boolean DEBUG = true;

    private static final Set<UUID> PROCESSED_ENTITIES = new HashSet<>();
    private static final Map<ResourceKey<Level>, LongSet> PROCESSED_CHUNKS = new HashMap<>();

    private static final Queue<ChunkTask> taskQueue = new LinkedList<>();
    private record ChunkTask(ServerLevel level, LevelChunk chunk, long ticksSkipped) {}
    public static void queueTimeSkip(ServerLevel level, LevelChunk chunk, long ticksSkipped) {
        if (ticksSkipped > 0) {
            taskQueue.add(new ChunkTask(level, chunk, ticksSkipped));
        }
    }

    public static void tick() {
        if (taskQueue.isEmpty()) return;

        // Process up to 2 chunks per tick.
        // Increase this number if you want faster updates but more TPS risk.
        int chunksToProcess = 2;

        for (int i = 0; i < chunksToProcess; i++) {
            ChunkTask task = taskQueue.poll();
            if (task == null) break;

            // Verify chunk is still loaded before processing
            if (task.level.getChunkSource().hasChunk(task.chunk.getPos().x, task.chunk.getPos().z)) {
                processChunk(task);
            }
        }
    }

    /**
     * Process a chunk when loaded, skipping chunks that we already have checked
     */
    private static void processChunk(ChunkTask task) {
        LevelChunk chunk = task.chunk;
        ServerLevel level = task.level;
        long ticksSkipped = task.ticksSkipped;

        skipCropStages(level, chunk, ticksSkipped);
        skipFurnaceTimes(level, chunk, ticksSkipped);
    }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            TimeSkipManager.tick();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            long lastSavedTime = TimeSkipManager.loadLastTime(server);
            long currentTime = System.currentTimeMillis();

            PROCESSED_ENTITIES.clear();
            PROCESSED_CHUNKS.clear();

            if (lastSavedTime != -1) {
                long timeDiff = currentTime - lastSavedTime;

                // Only do logic if time actually passed and it's a positive value
                if (timeDiff > 0) {
                    LOGGER.info("Time skipped: " + timeDiff + "ms");

                    // --- CALL YOUR STATIC METHOD HERE ---
                    if (DEBUG) {
                        timeDiff = timeDiff * 100;
                        for (int i = 0; i < 25; i++) {
                            if (i % 2 == 0) {
                                LOGGER.error("DEBUG MODE ON, TIME SKIP");
                            } else {
                                LOGGER.warn("DEBUG MODE ON, TIME SKIP");
                            }
                        }
                    }

                    startUpSavedDiff = timeDiff;
                    applyStartUpTimeSkipEffects(server, timeDiff);

                    // --- CRITICAL STEP ---
                    // Save immediately after logic finishes.
                    // If the server crashes 5 mins later, we don't want to re-apply this specific skip logic next load.
                    TimeSkipManager.saveCurrentTime(server);
                }
            } else {
                LOGGER.info("No previous time saved. Creating new tracker.");
                TimeSkipManager.saveCurrentTime(server);
            }
        });

        // 2. SERVER STOPPING EVENT
        // Saves when the server shuts down cleanly.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, saving timestamp.");
            TimeSkipManager.saveCurrentTime(server);
            PROCESSED_ENTITIES.clear();
            PROCESSED_CHUNKS.clear();
        });

        // 3. SERVER TICK EVENT (Crash Protection)
        // Runs at the end of every server tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;

            // 20 ticks = 1 second. 1200 ticks = 60 seconds (1 minute).
            if (tickCounter >= 1200) {
                TimeSkipManager.saveCurrentTime(server);
                tickCounter = 0; // Reset counter
            }
        });

        ServerChunkEvents.CHUNK_LOAD.register((ServerLevel level, LevelChunk chunk) -> {
            ResourceKey<Level> dimKey = level.dimension();
            long chunkPosLong = chunk.getPos().toLong();

            LongSet chunksInDimension = PROCESSED_CHUNKS.computeIfAbsent(dimKey, k -> new LongOpenHashSet());

            if (chunksInDimension.add(chunkPosLong)) {
                applyChunkLoadedTimeSkipEffects(level, chunk, startUpSavedDiff);
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((Entity entity, ServerLevel level) -> {
            if (level.isClientSide()) return;

            UUID id = entity.getUUID();

            // 1. Check if we have already processed this entity in this specific runtime session
            if (!PROCESSED_ENTITIES.contains(id)) {

                // 2. Check if the entity has our persistent "signature" tag
                // If the tag is present, it means the entity was saved to disk and is now reloading (OLD)
                // If the tag is missing, it is a brand new spawn or a pre-mod entity (NEW)
                boolean isPreviouslyLoaded = entity.getTags().contains(WAS_LOADED_TAG);

                if (isPreviouslyLoaded) {
                    // It's an existing baby loaded from disk -> Apply the time skip
                    applyEntityLoadedTimeSkipEffects(entity, level, startUpSavedDiff);
                } else {
                    // It's a brand new spawn (or first time seeing it) -> Mark it for next time, but SKIP the effect now
                    entity.addTag(WAS_LOADED_TAG);
                }

                // Add to runtime set to prevent double processing in this session
                PROCESSED_ENTITIES.add(id);
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((livingEntity, damageSource) -> {
            if (!livingEntity.level().isClientSide()) {
                PROCESSED_ENTITIES.remove(livingEntity.getUUID());
            }
        });
    }

    // Helper to get the file path for the current world
    private static Path getFilePath(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(level.dimension().location().getPath()).resolve(FILE_NAME);
    }

    public static void saveCurrentTime(MinecraftServer server) {
        Path path = getFilePath(server.overworld());
        long currentTime = System.currentTimeMillis();
        try {
            // Write string representation of the long to file
            Files.writeString(path, String.valueOf(currentTime),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save time tracker", e);
        }
    }

    public static long loadLastTime(MinecraftServer server) {
        Path path = getFilePath(server.overworld());
        if (!Files.exists(path)) {
            return -1; // Return -1 if file doesn't exist (first run)
        }
        try {
            String content = Files.readString(path).trim();
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            LOGGER.error("Failed to load time tracker", e);
            return -1;
        }
    }

    /**
     * Applies logic based on the real-world time elapsed while the server was offline.
     * @param server   The current Minecraft server instance.
     * @param timeDiff The amount of time passed in **milliseconds** (ms).
     */
    public static void applyStartUpTimeSkipEffects(MinecraftServer server, long timeDiff) {
        long ticksSkipped = timeDiff / 50L;
        if (ticksSkipped <= 0) {
            LOGGER.info("Time difference too small to skip ticks.");
            return;
        }

        long millis = timeDiff;
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        millis -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        LOGGER.info("SKIPPING TIME: {} ms ({} ticks)", timeDiff, ticksSkipped);
        LOGGER.info("{} days, {} hours, {} minutes and {} seconds.", days, hours, minutes, seconds);

        for (ServerLevel level : server.getAllLevels()) {
            for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
                LevelChunk chunk = holder.getFullChunk();
                if (chunk == null) continue; // Skip not-ready chunks

                ResourceKey<Level> dimKey = level.dimension();
                long chunkPosLong = chunk.getPos().toLong();
                LongSet processed = PROCESSED_CHUNKS.computeIfAbsent(dimKey, k -> new LongOpenHashSet());

                if (processed.add(chunkPosLong)) {
                    applyChunkLoadedTimeSkipEffects(level, chunk, startUpSavedDiff);
                }
            }
        }

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                UUID id = entity.getUUID();
                if (!PROCESSED_ENTITIES.contains(id)) {
                    applyEntityLoadedTimeSkipEffects(entity, level, startUpSavedDiff);
                    PROCESSED_ENTITIES.add(id);
                }
            }
        }

    }

    /**
     * Applies logic based on the real-world time elapsed while the server was offline.
     * Gets called when a chunk loads for the first time on the server.
     * @param timeDiff The amount of time passed in **milliseconds** (ms).
     */
    public static void applyChunkLoadedTimeSkipEffects(ServerLevel serverLevel, LevelChunk levelChunk, long timeDiff) {
        long ticksSkipped = timeDiff / 50L;
        if (ticksSkipped <= 0) return;

        TimeSkipManager.queueTimeSkip(serverLevel, levelChunk, timeDiff); // async applications
    }

    /**
     * Applies logic based on the real-world time elapsed while the server was offline.
     * Gets called when an entity gets loaded for the first time on the server.
     * @param timeDiff The amount of time passed in **milliseconds** (ms).
     */
    public static void applyEntityLoadedTimeSkipEffects(Entity entity, ServerLevel serverLevel, long timeDiff) {
        long ticksSkipped = timeDiff / 50L;
        if (ticksSkipped <= 0) return;

        skipMobEffects(entity, ticksSkipped);
    }

    private static void skipMobEffects(Entity entity, long ticksSkipped) {
        if (entity instanceof LivingEntity living) {
            // Create a copy of the effects list to avoid ConcurrentModificationException
            // getActiveEffectsMap().values() gives us the raw instances
            List<MobEffectInstance> effectsToUpdate = new ArrayList<>(living.getActiveEffects());

            for (MobEffectInstance effect : effectsToUpdate) {
                int currentDuration = effect.getDuration();
                long newDurationLong = currentDuration - ticksSkipped;

                if (newDurationLong <= 0) {
                    // If the effect has expired, remove it properly
                    living.removeEffect(effect.getEffect());
                } else {
                    // Otherwise, update the duration using the Accessor
                    ((MobEffectInstanceAccessor) effect).setDuration((int) newDurationLong);
                }
            }
        }
    }

    private static void skipCropStages(ServerLevel level, LevelChunk chunk, long ticksSkipped) {
        int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        if (randomTickSpeed <= 0) return;

        double chancePerTick = (double) randomTickSpeed / 4096.0;
        int estimatedUpdates = (int) (ticksSkipped * chancePerTick);
        int attemptsToSimulate = Math.min(estimatedUpdates, 64);

        LevelChunkSection[] sections = chunk.getSections();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            if (!section.getStates().maybeHas(state -> state.getBlock() instanceof CropBlock)) {
                continue;
            }

            int sectionBottomY = chunk.getMinBuildHeight() + (i * 16);

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);

                        // We already know this section contains crops, but we still check the specific block
                        if (state.getBlock() instanceof CropBlock && state.isRandomlyTicking()) {

                            BlockPos pos = new BlockPos(
                                    chunk.getPos().getMinBlockX() + x,
                                    sectionBottomY + y,
                                    chunk.getPos().getMinBlockZ() + z
                            );

                            for (int k = 0; k < attemptsToSimulate; k++) {
                                BlockState currentState = level.getBlockState(pos);
                                if (!(currentState.getBlock() instanceof CropBlock) || !currentState.isRandomlyTicking()) {
                                    break;
                                }
                                currentState.randomTick(level, pos, level.random);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void skipFurnaceTimes(ServerLevel level, LevelChunk chunk, long ticksSkipped) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be instanceof AbstractFurnaceBlockEntity furnace) {
                simulateFurnace(level, furnace, ticksSkipped);
            }
        }
    }

    private static void simulateFurnace(ServerLevel level, AbstractFurnaceBlockEntity furnace, long ticksSkipped) {
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < ticksSkipped; i++) {
            AbstractFurnaceBlockEntity.serverTick(level, furnace.getBlockPos(), furnace.getBlockState(), furnace);
        }
        long endTime = System.currentTimeMillis();
        long elapsed = endTime - startTime;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(elapsed);
        LOGGER.info("Furnace ticking took {} milis and {} seconds.", elapsed, seconds);
    }
}

/*
On my minecraft fabric 1.20.1 mod using mojmaps mappings. How can I do time skips when I log back to my world?
I already have the logic the gathers the time passed, lets apply the effects. Let's do the next task:

- Make all furnaces speedup/skip time, consuming resources and producing results.
 */

/*
On my minecraft fabric 1.20.1 mod using mojmaps mappings. My mod skips time, if you logout and log in again, lets say, 4 hours later, it applies the time you where off to your world.
It's an "Offline Progression" mod type.
This is the feauter we have already:

- Entities gets their mob-effects durations modified.
- Crops grow with prediction.
- Rain and day/night time gets skipped.

Suggest new features for the mod.
 */