package com.arenax.tournament.data;

import android.graphics.Color;
import com.arenax.tournament.model.Tournament;
import java.util.ArrayList;
import java.util.List;

public final class MockData {
    private MockData() {}
    public static final List<Tournament> TOURNAMENTS = new ArrayList<>();
    static {
        TOURNAMENTS.add(new Tournament("t1", "Clash Squad Night", "CLASH SQUAD", "10 SEP 2026", "08:00 PM", "100 Diamonds", 48, 24, "UPCOMING", Color.rgb(237, 47, 62)));
        TOURNAMENTS.add(new Tournament("t2", "Lone Wolf Rush", "LONE WOLF", "11 SEP 2026", "09:30 PM", "Bundle reward", 24, 18, "UPCOMING", Color.rgb(255, 119, 54)));
        TOURNAMENTS.add(new Tournament("t3", "STARX24 Elite Series", "FF FULL MAP", "09 SEP 2026", "07:00 PM", "Winner reward", 50, 42, "ONGOING", Color.rgb(215, 25, 53)));
        TOURNAMENTS.add(new Tournament("t4", "Redline Duos", "LW 2vs2", "03 SEP 2026", "06:30 PM", "Exclusive badge", 32, 32, "COMPLETED", Color.rgb(60, 190, 145)));
        TOURNAMENTS.add(new Tournament("t5", "Iron District", "CS 4vs4", "15 SEP 2026", "10:00 PM", "500 Diamonds", 64, 31, "UPCOMING", Color.rgb(48, 133, 255)));
    }
}
