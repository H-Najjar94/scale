package com.hookscale.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeightFilterTest {
    private static final int[] RIPPLE = {
            -24,-18,-10,0,10,18,24,18,10,0,-10,-18,-24,
            -18,-10,0,10,18,24,18,10,0,-10,-18,-24
    };

    private WeightFilter fill(long center) {
        WeightFilter filter = new WeightFilter();
        for (int delta : RIPPLE) filter.add(center + delta);
        return filter;
    }

    @Test public void sameLoadReturnsSameWeight() {
        WeightFilter zero = fill(3150);
        WeightFilter loaded = fill(27150);
        double factor = (loaded.value() - zero.value()) / 3000.0;
        assertEquals(8.0, factor, 0.001);
        assertEquals(3000.0, (fill(27150).value() - zero.value()) / factor, 0.1);
    }

    @Test public void negativeSensorDirectionWorks() {
        WeightFilter zero = fill(3150);
        WeightFilter loaded = fill(-20850);
        double factor = (loaded.value() - zero.value()) / 3000.0;
        assertEquals(-8.0, factor, 0.001);
        assertEquals(3000.0, (loaded.value() - zero.value()) / factor, 0.1);
    }

    @Test public void isolatedCorruptSamplesAreTrimmed() {
        WeightFilter filter = fill(3150);
        filter.add(100);
        filter.add(900000);
        assertTrue(filter.isStable(0));
        assertEquals(3150.0, filter.value(), 2.0);
    }

    @Test public void movingLoadIsRejected() {
        WeightFilter filter = new WeightFilter();
        for (int i = 0; i < WeightFilter.WINDOW_SIZE; i++) filter.add(3000 + i * 100);
        assertFalse(filter.isStable(0));
    }

    @Test public void fullFiveSecondWindowIsRequired() {
        WeightFilter filter = new WeightFilter();
        for (int i = 0; i < WeightFilter.WINDOW_SIZE - 1; i++) filter.add(3150);
        assertFalse(filter.isReady());
        filter.add(3150);
        assertTrue(filter.isReady());
    }

    @Test public void cleanHomeLoadCanCalibrate() {
        assertEquals(20.0, WeightFilter.minimumCalibrationSpan(1.0, 1.0), 0.001);
    }

    @Test public void noisyLoadRequiresLargerChange() {
        assertEquals(300.0, WeightFilter.minimumCalibrationSpan(50.0, 50.0), 0.001);
    }
}
