package com.chaoticloom.timesync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class TimeSyncClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(TimeSync.SYNC_PACKET_ID, (client, handler, buf, responseSender) -> {
            // Read the long from the packet
            long receivedTimestamp = buf.readLong();

            // Execute on the main client thread to be safe
            client.execute(() -> {
                TimeSync.LOGGER.info("Received Sync Timestamp: {}", receivedTimestamp);
                TimeSync.cachedTimestamp = receivedTimestamp;
            });
        });

        ClientTickEvents.START_CLIENT_TICK.register((client) -> {
            if (client.level == null) {
                client.execute(() -> {
                    TimeSync.cachedTimestamp = null;
                });
            } else {
                ClientWeatherController.tick();
            }
        });
    }
}
