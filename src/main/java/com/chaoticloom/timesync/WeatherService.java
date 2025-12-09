package com.chaoticloom.timesync;

import com.google.gson.Gson;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

//https://open-meteo.com/en/docs?latitude=37.3279072&longitude=-5.9400771&timezone=Europe%2FLondon&hourly=weather_code
public class WeatherService {
    private static final Gson GSON = new Gson();
    private static final String API_URL = "https://api.open-meteo.com/v1/forecast?latitude=37.3279072&longitude=-5.9400771&current=weather_code&hourly=weather_code&forecast_days=16";
    private static final String FILE_NAME = "weather_cache.json";

    // Cache object in memory
    private static WeatherCache cachedData;

    public static void updateWeather(ServerLevel level) {
        // 1. Try to fetch new data asynchronously
        fetchFromApi().thenAccept(data -> {
            if (data != null) {
                cachedData = data;
                saveCache(level, data);
                applyWeather(level, getCurrentCodeFromCache());
                System.out.println("Weather synced with API.");
            } else {
                // 2. If API fails, fallback to cache
                if (cachedData == null) loadCache(level); // Try loading from disk if memory is empty

                if (cachedData != null) {
                    applyWeather(level, getCodeForCurrentHour());
                    System.out.println("API failed. Using cached forecast.");
                } else {
                    System.out.println("No API or Cache. Vanilla weather taking over.");
                }
            }
        });
    }

    private static CompletableFuture<WeatherCache> fetchFromApi() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(API_URL)).GET().build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(json -> {
                    try {
                        return GSON.fromJson(json, WeatherCache.class);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                })
                .exceptionally(e -> {
                    System.err.println("Failed to fetch weather: " + e.getMessage());
                    return null;
                });
    }

    // Logic to find the correct weather code for the *current* real-world hour from the cached 16-day forecast
    private static int getCodeForCurrentHour() {
        if (cachedData == null || cachedData.hourly == null) return -1;

        // Find the index in the time list that matches the current hour
        String currentHourPrefix = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 13);

        for (int i = 0; i < cachedData.hourly.time.size(); i++) {
            if (cachedData.hourly.time.get(i).startsWith(currentHourPrefix)) {
                return cachedData.hourly.weather_code.get(i);
            }
        }
        return cachedData.current.weather_code; // Fallback to "current" snapshot
    }

    private static int getCurrentCodeFromCache() {
        if (cachedData != null && cachedData.current != null) return cachedData.current.weather_code;
        return 0;
    }

    private static void applyWeather(ServerLevel level, int wmoCode) {
        // Run on main thread to be safe
        level.getServer().execute(() -> {
            int duration = 18000; // 15 minutes in ticks
            WeatherState weatherState = WeatherState.fromCode(wmoCode);
            WeatherStateStrength weatherStateStrength = WeatherStateStrength.fromCode(wmoCode);

            if (weatherState == WeatherState.THUNDERSTORM) {
                level.setWeatherParameters(0, duration, true, true);
            } else if (weatherState == WeatherState.RAINING || weatherState == WeatherState.SNOWING) {
                level.setWeatherParameters(0, duration, true, false);
            } else if (weatherState == WeatherState.CLEAR || weatherState == WeatherState.CLOUDY || weatherState == WeatherState.FOG ) {
                level.setWeatherParameters(duration, 0, false, false);
            }

            if (weatherState == WeatherState.CLOUDY) {
                float amount = 0;
                if (weatherStateStrength == WeatherStateStrength.SLIGHT) {
                    amount = 0.35f;
                } else if (weatherStateStrength == WeatherStateStrength.MODERATE) {
                    amount = 0.5f;
                } else if (weatherStateStrength == WeatherStateStrength.INTENSE) {
                    amount = 1f;
                }

                ClientWeatherController.setCloudiness(amount);
            } else {
                ClientWeatherController.setCloudiness(0);
            }
        });
    }

    // --- Persistence Methods ---

    private static void saveCache(ServerLevel level, WeatherCache data) {
        try {
            Path path = getFilePath(level);

            // Get the folder that contains the file
            Path parentDir = path.getParent();

            // If the folder path is not null and doesn't exist, create it (including any necessary parent folders)
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Writer writer = Files.newBufferedWriter(path);
            GSON.toJson(data, writer);
            writer.close();
        } catch (IOException e) {
            System.err.println("Failed to save weather cache: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void loadCache(ServerLevel level) {
        try {
            Path path = getFilePath(level);
            if (Files.exists(path)) {
                Reader reader = Files.newBufferedReader(path);
                cachedData = GSON.fromJson(reader, WeatherCache.class);
                reader.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path getFilePath(ServerLevel level) {
        // Using the user provided path logic
        return level.getServer().getWorldPath(LevelResource.ROOT)
                .resolve(level.dimension().location().getPath()) // This usually adds "overworld" or similar
                .resolve(FILE_NAME);
    }

    public static WeatherCache getCache() {
        return cachedData;
    }

    public static int getWMO() {
        return getCurrentCodeFromCache();
    }
}