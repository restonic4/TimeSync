package com.chaoticloom.timesync;

import java.util.List;

public class WeatherCache {
    public long lastUpdateTimestamp; // Unix time when we last fetched
    public Current current;
    public Hourly hourly;

    public static class Current {
        public int weather_code;
    }

    public static class Hourly {
        public List<String> time; // ISO8601 strings
        public List<Integer> weather_code;
    }
}