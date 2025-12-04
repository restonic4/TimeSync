package com.chaoticloom.timesync;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Collections;
import java.util.Locale;

public class TabListManager {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy   -   HH:mm:ss");
    private static final int MIN_SEPARATOR_WIDTH = 27;
    private static final int UPDATE_INTERVAL = 20; // Updated to 20 (1 sec) to reduce packet spam, 1 tick is too fast for Tablist
    private static int tickCounter = 0;

    // Spanish Locale for Day names (Lun, Mar, Mie...)
    private static final Locale SPANISH = new Locale("es", "ES");

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(TabListManager::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        Component footer = buildFooter(server);

        // Calculate header width based on the widest line in the footer
        int estimatedWidth = footer.getString().lines()
                .mapToInt(String::length)
                .max()
                .orElse(MIN_SEPARATOR_WIDTH);

        MutableComponent header = Component.literal("§6§lEstado del Mundo\n");
        header.append(getSeparator(estimatedWidth, false));

        ClientboundTabListPacket packet = new ClientboundTabListPacket(header, footer);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(packet);
        }
    }

    private static Component buildFooter(MinecraftServer server) {
        // --- 1. BASIC DATA ---
        LocalDateTime now = LocalDateTime.now();
        String dateString = now.format(DATE_FORMAT);
        String season = getSeason(now.getMonthValue());

        long totalTicks = server.overworld().getDayTime();
        long totalWorldDays = totalTicks / 24000L;

        // --- 2. WEATHER DATA CONSTRUCTION ---
        MutableComponent weatherLine1 = Component.empty();
        MutableComponent weatherLine2 = Component.empty();

        WeatherCache data = WeatherService.getCache();

        if (data != null && data.current != null) {
            // -- Current Weather Line --
            // Format: "Actualmente: ⛈ Tormenta (18°C)" (Temp optional if you have it, here just code)
            String currentIcon = getWeatherIcon(data.current.weather_code);
            String currentName = getWeatherName(data.current.weather_code);
            weatherLine1.append(Component.literal("§7Actual: " + currentIcon + " " + currentName));

            // -- Forecast Line (Compact) --
            // Format: "Lun ☀  Mar ☁  Mie 🌧"
            // We look ahead 24, 48, and 72 hours index positions
            if (data.hourly != null && !data.hourly.weather_code.isEmpty()) {
                weatherLine2.append(Component.literal("§8Pronóstico: §f"));

                // We start getting the index for "Tomorrow same time"
                // This is a rough approximation based on the API list.
                // A safer way is checking the time string, but fixed index jumps work for cache lists usually.
                int currentHourIndex = LocalDateTime.now().getHour(); // 0-23

                // Get next 3 days
                for (int i = 1; i <= 3; i++) {
                    int targetIndex = currentHourIndex + (i * 24); // Jump 24 hours ahead

                    if (targetIndex < data.hourly.weather_code.size()) {
                        int code = data.hourly.weather_code.get(targetIndex);
                        String dayName = now.plusDays(i).getDayOfWeek().getDisplayName(TextStyle.SHORT, SPANISH);

                        weatherLine2.append(Component.literal(dayName + " " + getWeatherIcon(code) + "  "));
                    }
                }
            }
        } else {
            weatherLine1.append(Component.literal("§8Sincronizando clima..."));
        }

        // --- 3. WORLD AGE CALCULATION ---
        long calcYears = totalWorldDays / 365;
        long remainingDaysAfterYear = totalWorldDays % 365;
        long calcMonths = remainingDaysAfterYear / 30;
        long calcDays = remainingDaysAfterYear % 30;

        MutableComponent ageLine = Component.literal("§7Día: §e" + totalWorldDays + "   §7-   ");
        ageLine.append(formatDuration(calcYears, calcMonths, calcDays));

        // --- 4. WIDTH CALCULATION ---
        // We must check the width of ALL lines to ensure the separator is wide enough
        int maxLen = Math.max(ageLine.getString().length(), dateString.length());
        maxLen = Math.max(maxLen, weatherLine1.getString().length());
        maxLen = Math.max(maxLen, weatherLine2.getString().length());

        int separatorWidth = Math.max(MIN_SEPARATOR_WIDTH, maxLen + 2);

        // --- 5. ASSEMBLE FOOTER ---
        MutableComponent footer = Component.empty();

        // Top Separator
        footer.append(getSeparator(separatorWidth, true));

        // Real Time
        footer.append(Component.literal("§7" + dateString + "\n"));
        footer.append(Component.literal("§b" + season + "\n"));

        // Weather Section (Only if data exists)
        if (data != null) {
            footer.append(Component.literal("\n")); // Small spacer
            footer.append(weatherLine1).append(Component.literal("\n"));
            footer.append(weatherLine2).append(Component.literal("\n"));
        } else {
            footer.append(Component.literal("\n§8(Buscando satélites...)\n"));
        }

        // Middle Separator
        footer.append(getSeparator(separatorWidth, true));

        // World Age
        footer.append(ageLine);

        return footer;
    }

    // --- HELPER METHODS ---

    /**
     * Returns a pretty Unicode icon + Color code for WMO codes.
     */
    private static String getWeatherIcon(int code) {
        // Colors: e=yellow, 7=gray, b=aqua, 9=blue, 8=dark gray
        WeatherState weatherState = WeatherState.fromCode(code);
        WeatherStateStrength weatherStateStrength = WeatherStateStrength.fromCode(code);

        if (weatherState == WeatherState.CLEAR) return "§e☀";
        if (weatherState == WeatherState.CLOUDY) return "§7☁";
        if (weatherState == WeatherState.FOG) return "§7🌫";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.SLIGHT) return "§b🌦";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.MODERATE) return "§b🌧";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.INTENSE) return "§9☔";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.SLIGHT) return "§f❅";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.MODERATE) return "§f❄";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.INTENSE) return "§f🌨";
        if (weatherState == WeatherState.THUNDERSTORM && weatherStateStrength == WeatherStateStrength.SLIGHT) return "§5⛈";
        if (weatherState == WeatherState.THUNDERSTORM && weatherStateStrength == WeatherStateStrength.INTENSE) return "§5⛈";
        return "§7?";
    }

    /**
     * Returns a short friendly name for the weather.
     */
    private static String getWeatherName(int code) {
        WeatherState weatherState = WeatherState.fromCode(code);
        WeatherStateStrength weatherStateStrength = WeatherStateStrength.fromCode(code);

        if (weatherState == WeatherState.CLEAR) return "Despejado";
        if (weatherState == WeatherState.CLOUDY) return "Nublado";
        if (weatherState == WeatherState.FOG) return "Niebla";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.SLIGHT) return "Llovizna";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.MODERATE) return "Lluvia";
        if (weatherState == WeatherState.RAINING && weatherStateStrength == WeatherStateStrength.INTENSE) return "Lluvia Fuerte";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.SLIGHT) return "Nieve Leve";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.MODERATE) return "Nieve";
        if (weatherState == WeatherState.SNOWING && weatherStateStrength == WeatherStateStrength.INTENSE) return "Nevada Fuerte";
        if (weatherState == WeatherState.THUNDERSTORM && weatherStateStrength == WeatherStateStrength.SLIGHT) return "Tormenta";
        if (weatherState == WeatherState.THUNDERSTORM && weatherStateStrength == WeatherStateStrength.INTENSE) return "Tormenta Fuerte";
        return "Desconocido";
    }

    private static Component getSeparator(int targetWidth, boolean newLine) {
        int finalWidth = Math.max(MIN_SEPARATOR_WIDTH, targetWidth);
        String dashes = String.join("", Collections.nCopies(finalWidth, "-"));
        String text = "§f" + dashes;
        if (newLine) text += "\n";
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
        StringBuilder sb = new StringBuilder("§e");
        boolean hasYears = years > 0;
        boolean hasMonths = months > 0;

        if (hasYears) sb.append(years).append(years == 1 ? " año" : " años");
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