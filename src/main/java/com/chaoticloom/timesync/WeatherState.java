package com.chaoticloom.timesync;

// WMO Code Mapping: https://open-meteo.com/en/docs (The end of the page)
// 0: Clear
// 1, 2, 3: Cloudy
// 45, 48: Fog
// 51, 53, 55: Drizzle (Llovizna)
// 56, 57: Freezing Drizzle (Llovizna que se congela al llegar al suelo)
// 61, 63, 65: Rain
// 66, 67: Freezing Rain
// 71, 73, 75: Snow fall
// 77: Snow grains
// 80, 81, 82: Rain showers
// 85, 86: Snow showers
// 95: Thunderstorm
// 96, 99: Thunderstorm with hail (Hail es como nieve gorda)

public enum WeatherState {
    CLEAR(0),
    CLOUDY(1, 2, 3),
    FOG(45, 48),
    RAINING(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82),
    SNOWING(71, 73, 75, 77, 85, 86),
    THUNDERSTORM(95, 96, 99);

    private final int[] codes;

    WeatherState(int... codes) {
        this.codes = codes;
    }

    public int[] getCodes() {
        return codes;
    }

    /**
     * Utility method to resolve a WMO code into a WeatherState.
     * Returns null if no state matches the code.
     */
    public static WeatherState fromCode(int code) {
        for (WeatherState state : values()) {
            for (int c : state.codes) {
                if (c == code) {
                    return state;
                }
            }
        }
        return null;
    }
}
