package com.chaoticloom.timesync;

public enum WeatherStateStrength {
    SLIGHT(1, 51, 56, 61, 66, 71, 80, 85, 96),
    MODERATE(2, 45, 53, 57, 63, 73, 81),
    INTENSE(3, 48, 55, 65, 67, 75, 82, 86, 99);

    private final int[] codes;

    WeatherStateStrength(int... codes) {
        this.codes = codes;
    }

    public int[] getCodes() {
        return codes;
    }

    public static WeatherStateStrength fromCode(int code) {
        for (WeatherStateStrength state : values()) {
            for (int c : state.codes) {
                if (c == code) {
                    return state;
                }
            }
        }
        return MODERATE;
    }
}
