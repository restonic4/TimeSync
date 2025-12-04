package com.chaoticloom.timesync;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

public class TabListManager {
    // Formatter for: 22/12/2025 - 18:47:45
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy   -   HH:mm:ss");

    // Standard width for the separator (approx 27 dashes)
    private static final int MIN_SEPARATOR_WIDTH = 27;

    private static final int UPDATE_INTERVAL = 1;
    private static int tickCounter = 0;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TabListManager::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        // 1. Generate the complex Footer Component
        Component footer = buildFooter(server);

        // 2. Define Header
        // We calculate the width based on the footer content so the header separator matches too
        int estimatedWidth = footer.getString().lines()
                .mapToInt(String::length)
                .max()
                .orElse(MIN_SEPARATOR_WIDTH);

        MutableComponent header = Component.literal("§6§lEstado del mundo\n");
        header.append(getSeparator(estimatedWidth, false));

        // 3. Send to all players
        ClientboundTabListPacket packet = new ClientboundTabListPacket(header, footer);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }

    private static Component buildFooter(MinecraftServer server) {
        // --- DATA PREPARATION ---
        LocalDateTime now = LocalDateTime.now();
        String dateString = now.format(DATE_FORMAT);
        String season = getSeason(now.getMonthValue());

        long totalTicks = server.overworld().getDayTime();
        long totalWorldDays = totalTicks / 24000L;

        long calcYears = totalWorldDays / 365;
        long remainingDaysAfterYear = totalWorldDays % 365;
        long calcMonths = remainingDaysAfterYear / 30;
        long calcDays = remainingDaysAfterYear % 30;

        // --- COMPONENT CONSTRUCTION ---

        // 1. Create the Bottom Line FIRST to measure it
        MutableComponent bottomLine = Component.literal("§7Día: §e" + totalWorldDays + "   §7-   ");
        bottomLine.append(formatDuration(calcYears, calcMonths, calcDays));

        // 2. Calculate Width
        // .getString() returns the raw text without color codes, allowing accurate length measurement
        int bottomLineLength = bottomLine.getString().length();

        // Add a small buffer (+2 or +4) so the line extends slightly past the text
        int separatorWidth = Math.max(MIN_SEPARATOR_WIDTH, bottomLineLength + 2);

        // 3. Assemble the full Footer
        MutableComponent footer = Component.empty();

        // Top Separator
        footer.append(getSeparator(separatorWidth, true));

        // Date & Season
        footer.append(Component.literal("§7" + dateString + "\n"));
        footer.append(Component.literal("§b" + season + "\n"));

        // Middle Separator (Dynamic Length)
        footer.append(getSeparator(separatorWidth, true));

        // Bottom Line (Day & Duration)
        footer.append(bottomLine);

        return footer;
    }

    /**
     * Generates a separator line of hyphens based on the target width.
     */
    private static Component getSeparator(int targetWidth, boolean newLine) {
        // Ensure we never go below the minimum aesthetic width
        int finalWidth = Math.max(MIN_SEPARATOR_WIDTH, targetWidth);

        // Create the string of dashes
        String dashes = String.join("", Collections.nCopies(finalWidth, "-"));

        String text = "§f" + dashes;
        if (newLine) {
            text += "\n";
        }

        return Component.literal(text);
    }

    private static String getSeason(int month) {
        return switch (month) {
            case 12, 1, 2 -> "Invierno";
            case 3, 4, 5 -> "Primavera";
            case 6, 7, 8 -> "Verano";
            case 9, 10, 11 -> "Otoño";
            default -> "Error";
        };
    }

    private static Component formatDuration(long years, long months, long days) {
        StringBuilder sb = new StringBuilder("§e"); // Start with Yellow color

        boolean hasYears = years > 0;
        boolean hasMonths = months > 0;

        if (hasYears) {
            sb.append(years).append(years == 1 ? " año" : " años");
        }

        if (hasMonths) {
            if (hasYears) sb.append(", ");
            sb.append(months).append(months == 1 ? " mes" : " meses");
        }

        if (days > 0 || (!hasYears && !hasMonths)) {
            if (hasYears || hasMonths) sb.append(" y ");
            sb.append(days).append(days == 1 ? " día" : " dias");
        }

        return Component.literal(sb.toString());
    }
}