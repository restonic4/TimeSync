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
import java.util.*;

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
        WeatherCache data = WeatherService.getCache();

        MutableComponent weatherLineCurrent = Component.empty();
        MutableComponent timelineHours = Component.empty();
        MutableComponent timelineIcons = Component.empty();
        MutableComponent weatherForecast = Component.empty();

        if (data != null && data.current != null) {
            // A. Current Header
            String currentIcon = getWeatherIcon(data.current.weather_code);
            String currentName = getWeatherName(data.current.weather_code);
            weatherLineCurrent.append(Component.literal("§7Actual: " + currentIcon + " " + currentName));

            // B. Hourly Strip (The 2-Row Timeline)
            if (data.hourly != null && !data.hourly.weather_code.isEmpty()) {
                // Label for the top row
                timelineHours.append(Component.literal("§8Horas: §7"));
                // Spacer for the bottom row to match "Horas: " width (approx 7 spaces)
                timelineIcons.append(Component.literal("       "));

                int currentHour = now.getHour();

                // Loop 0 to 23 with a step of 3 to fit tablist width
                for (int h = 0; h < 24; h += 3) {
                    if (h < data.hourly.weather_code.size()) {
                        int code = data.hourly.weather_code.get(h);

                        // Highlight the column closest to current time in Gold
                        boolean isNow = (Math.abs(currentHour - h) <= 1);
                        String color = isNow ? "§6" : "§7";

                        // Top Row: Numbers (e.g. "09")
                        timelineHours.append(Component.literal(color + String.format("%02d", h) + "  "));

                        // Bottom Row: Icons (e.g. "☁")
                        timelineIcons.append(Component.literal(getWeatherIcon(code) + "  "));
                    }
                }
            }

            // C. Forecast (Next 3 Days - Weighted)
            if (data.hourly != null) {
                weatherForecast.append(Component.literal("§8Pronóstico: §f"));
                for (int dayOffset = 1; dayOffset <= 3; dayOffset++) {
                    int startHourIndex = dayOffset * 24;
                    int representativeCode = calculateDailyForecast(data.hourly.weather_code, startHourIndex);

                    String dayName = now.plusDays(dayOffset).getDayOfWeek().getDisplayName(TextStyle.SHORT, SPANISH);
                    dayName = dayName.substring(0, 1).toUpperCase() + dayName.substring(1);

                    weatherForecast.append(Component.literal(dayName + " " + getWeatherIcon(representativeCode) + "  "));
                }
            }
        } else {
            weatherLineCurrent.append(Component.literal("§8Sincronizando clima..."));
        }

        // --- 3. WORLD AGE ---
        long calcYears = totalWorldDays / 365;
        long remainingDaysAfterYear = totalWorldDays % 365;
        long calcMonths = remainingDaysAfterYear / 30;
        long calcDays = remainingDaysAfterYear % 30;

        MutableComponent ageLine = Component.literal("§7Día: §e" + totalWorldDays + "   §7-   ");
        ageLine.append(formatDuration(calcYears, calcMonths, calcDays));

        // --- 4. WIDTH CALCULATION ---
        int maxLen = Math.max(ageLine.getString().length(), dateString.length());
        maxLen = Math.max(maxLen, weatherLineCurrent.getString().length());
        // Check the new timeline width
        maxLen = Math.max(maxLen, timelineHours.getString().length());
        maxLen = Math.max(maxLen, weatherForecast.getString().length());

        int separatorWidth = Math.max(MIN_SEPARATOR_WIDTH, maxLen + 2);

        // --- 5. ASSEMBLE FOOTER ---
        MutableComponent footer = Component.empty();

        footer.append(getSeparator(separatorWidth, true));
        footer.append(Component.literal("§7" + dateString + "\n"));
        footer.append(Component.literal("§b" + season + "\n"));

        if (data != null) {
            footer.append(Component.literal("\n"));

            // Current Weather
            footer.append(weatherLineCurrent).append(Component.literal("\n\n"));

            // The Timeline Strip
            footer.append(timelineHours).append(Component.literal("\n"));
            footer.append(timelineIcons).append(Component.literal("\n\n"));

            // Future Forecast
            footer.append(weatherForecast).append(Component.literal("\n"));
        } else {
            footer.append(Component.literal("\n§8(Buscando satélites...)\n"));
        }

        footer.append(getSeparator(separatorWidth, true));
        footer.append(ageLine);

        return footer;
    }

    // --- ALGORITHM FOR WEIGHTED FORECAST ---

    /**
     * Scans 24 hours of data and returns the code that has the highest accumulated "Severity Score".
     */
    private static int calculateDailyForecast(List<Integer> hourlyCodes, int startIndex) {
        Map<Integer, Integer> scoreMap = new HashMap<>();

        // Loop through 24 hours (or less if end of list)
        for (int i = 0; i < 24; i++) {
            int index = startIndex + i;
            if (index >= hourlyCodes.size()) break;

            int code = hourlyCodes.get(index);
            int weight = getWeatherWeight(code);

            // Add the weight to this specific code's total score
            scoreMap.put(code, scoreMap.getOrDefault(code, 0) + weight);
        }

        // Find the code with the highest score
        return scoreMap.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0); // Default to clear sky if error
    }

    /**
     * Assigns a "Severity Score" to weather codes.
     * Higher score = This weather takes priority in the forecast.
     */
    private static int getWeatherWeight(int code) {
        WeatherState state = WeatherState.fromCode(code);
        WeatherStateStrength strength = WeatherStateStrength.fromCode(code);

        // 1. DANGEROUS / EXTREME (Highest Priority)
        if (state == WeatherState.THUNDERSTORM) return 50;
        if (state == WeatherState.SNOWING && strength == WeatherStateStrength.INTENSE) return 40;
        if (state == WeatherState.RAINING && strength == WeatherStateStrength.INTENSE) return 35;

        // 2. BAD WEATHER
        if (state == WeatherState.SNOWING) return 20;
        if (state == WeatherState.RAINING) return 15;

        // 3. CLOUDY
        if (state == WeatherState.CLOUDY && strength == WeatherStateStrength.INTENSE) return 5;
        if (state == WeatherState.CLOUDY) return 3;
        if (state == WeatherState.FOG) return 2;

        // 4. CLEAR (Lowest Priority)
        return 1;
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
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.SLIGHT) return "§7⛅";
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.MODERATE) return "§7⛅";
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.INTENSE) return "§7☁";
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
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.SLIGHT) return "Poco Nublado";
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.MODERATE) return "Nublado";
        if (weatherState == WeatherState.CLOUDY && weatherStateStrength == WeatherStateStrength.INTENSE) return "Muy Nublado";
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