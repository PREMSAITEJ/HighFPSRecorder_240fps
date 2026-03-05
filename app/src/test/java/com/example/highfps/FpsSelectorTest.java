package com.example.highfps;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FpsSelectorTest {

    @Test
    public void picksExact240WhenAvailable() {
        List<int[]> ranges = Arrays.asList(
                new int[]{30, 30},
                new int[]{60, 120},
                new int[]{240, 240}
        );

        assertArrayEquals(new int[]{240, 240}, FpsSelector.pickBestRange(ranges, 240));
    }

    @Test
    public void fallsBackToHighestUpperBound() {
        List<int[]> ranges = Arrays.asList(
                new int[]{30, 30},
                new int[]{60, 120},
                new int[]{120, 120}
        );

        assertArrayEquals(new int[]{120, 120}, FpsSelector.pickBestRange(ranges, 240));
    }

    @Test
    public void returns30WhenRangesMissing() {
        assertArrayEquals(new int[]{30, 30}, FpsSelector.pickBestRange(null, 240));
    }
}

