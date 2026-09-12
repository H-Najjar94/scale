package com.hookscale.app;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Robustly reduces the scale's cyclic 50 Hz measurements to one calibration value. */
final class WeightFilter {
    static final int WINDOW_SIZE = 25; // Five seconds at the bridge's 5 Hz BLE rate.
    private static final int HISTORY_SIZE = 40;
    private final ArrayDeque<Long> samples = new ArrayDeque<>();

    void add(long value) {
        samples.addLast(value);
        while (samples.size() > HISTORY_SIZE) samples.removeFirst();
    }

    void clear() { samples.clear(); }
    int size() { return samples.size(); }
    boolean isReady() { return samples.size() >= WINDOW_SIZE; }

    private long[] sortedWindow() {
        Long[] all = samples.toArray(new Long[0]);
        int start = Math.max(0, all.length - WINDOW_SIZE);
        long[] window = new long[all.length - start];
        for (int i = start; i < all.length; i++) window[i - start] = all[i];
        Arrays.sort(window);
        return window;
    }

    double value() {
        long[] window = sortedWindow();
        if (window.length == 0) return 0;
        int trim = window.length >= 20 ? 4 : (window.length >= 8 ? 2 : 0);
        long sum = 0;
        for (int i = trim; i < window.length - trim; i++) sum += window[i];
        return (double) sum / (window.length - 2 * trim);
    }

    double centralSpread() {
        long[] window = sortedWindow();
        if (window.length < WINDOW_SIZE) return Double.POSITIVE_INFINITY;
        // Ignore the two lowest and two highest samples. Isolated SPI glitches cannot
        // make a reading look unstable or move a calibration point.
        return window[window.length - 3] - window[2];
    }

    boolean isStable(double countsPerKg) {
        double allowed = Math.max(60.0, Math.abs(countsPerKg) * 3.0);
        return isReady() && centralSpread() <= allowed;
    }
}
