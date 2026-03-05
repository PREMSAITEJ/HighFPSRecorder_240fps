package com.example.highfps;

import java.util.List;

public final class FpsSelector {
    private FpsSelector() {
    }

    public static int[] pickBestRange(List<int[]> ranges, int desiredFps) {
        if (ranges == null || ranges.isEmpty()) {
            return new int[] {30, 30};
        }

        int[] exact = null;
        int[] highest = ranges.get(0);

        for (int[] range : ranges) {
            if (range == null || range.length < 2) {
                continue;
            }
            if (range[0] == desiredFps && range[1] == desiredFps) {
                exact = range;
            }
            if (range[1] > highest[1] || (range[1] == highest[1] && range[0] > highest[0])) {
                highest = range;
            }
        }

        return exact != null ? exact : highest;
    }
}

