package com.example.highfps;

import java.util.Arrays;
import java.util.List;

public class FpsSelectorHarness {
    public static void main(String[] args) {
        List<int[]> ranges = Arrays.asList(
                new int[] {30, 30},
                new int[] {120, 120},
                new int[] {240, 240}
        );
        int[] selected = FpsSelector.pickBestRange(ranges, 240);
        System.out.println("Selected FPS range: [" + selected[0] + ", " + selected[1] + "]");
    }
}

