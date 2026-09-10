package com.arenax.tournament.model;

import android.graphics.Color;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public class Tournament implements Serializable {
    private final String id;
    private final String title;
    private final String mode;
    private final String categoryId;
    private final String bannerUrl;
    private final String description;
    private final String date;
    private final String time;
    private final long startAt;
    private final long registrationCloseAt;
    private final long roomReleaseAt;
    private final String prizeInfo;
    private final int totalSlots;
    private int joinedSlots;
    private final int teamSize;
    private final String map;
    private final String matchType;
    private final String rules;
    private final String prizeDistribution;
    private final String roomId;
    private final String roomPassword;
    private final boolean roomReleased;
    private final String delayReason;
    private final String status;
    private final String registrationStatus;
    private final boolean active;
    private final int accentColor;
    private int entryFeeCoins;
    private int prizePoolCoins;
    private int perKillCoins;
    private String gameType = "BATTLE ROYALE";

    public Tournament(String id, String title, String mode, String date, String time,
                      String prizeInfo, int totalSlots, int joinedSlots,
                      String status, int accentColor) {
        this(id, title, mode, mode, "", "", date, time, 0L, 0L, 0L, "Free entry",
                totalSlots, joinedSlots, 1, "TBA", "SOLO", "",
                "", "", "", false, "", status, "OPEN", true, accentColor);
    }

    public Tournament(String id, String title, String mode, String categoryId, String bannerUrl,
                      String description, String date, String time, long startAt,
                      long registrationCloseAt, long roomReleaseAt, String prizeInfo,
                      int totalSlots, int joinedSlots, int teamSize, String map, String matchType,
                      String rules, String prizeDistribution, String roomId, String roomPassword,
                      boolean roomReleased, String delayReason,
                      String status, String registrationStatus, boolean active, int accentColor) {
        this.id = id;
        this.title = title;
        this.mode = mode;
        this.categoryId = categoryId;
        this.bannerUrl = bannerUrl;
        this.description = description;
        this.date = date;
        this.time = time;
        this.startAt = startAt;
        this.registrationCloseAt = registrationCloseAt;
        this.roomReleaseAt = roomReleaseAt;
        this.prizeInfo = prizeInfo == null || prizeInfo.trim().isEmpty() ? "Free entry" : prizeInfo.trim();
        this.totalSlots = totalSlots;
        this.joinedSlots = joinedSlots;
        this.teamSize = teamSize;
        this.map = map;
        this.matchType = matchType;
        this.rules = rules;
        this.prizeDistribution = prizeDistribution;
        this.roomId = roomId;
        this.roomPassword = roomPassword;
        this.roomReleased = roomReleased;
        this.delayReason = delayReason == null ? "" : delayReason.trim();
        this.status = status;
        this.registrationStatus = registrationStatus;
        this.active = active;
        this.accentColor = accentColor;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("title", title);
        map.put("mode", mode);
        map.put("categoryId", categoryId);
        map.put("bannerUrl", bannerUrl);
        map.put("description", description);
        map.put("date", date);
        map.put("time", time);
        map.put("startAt", startAt);
        map.put("registrationCloseAt", registrationCloseAt);
        map.put("roomReleaseAt", roomReleaseAt);
        map.put("prizeInfo", prizeInfo);
        map.put("totalSlots", totalSlots);
        map.put("joinedSlots", joinedSlots);
        map.put("teamSize", teamSize);
        map.put("map", this.map);
        map.put("matchType", matchType);
        map.put("rules", rules);
        map.put("prizeDistribution", prizeDistribution);
        map.put("roomId", roomId);
        map.put("roomPassword", roomPassword);
        map.put("roomReleased", roomReleased);
        map.put("delayReason", delayReason);
        map.put("status", status);
        map.put("registrationStatus", registrationStatus);
        map.put("active", active);
        map.put("accentColor", accentColor);
        map.put("entryFeeCoins", entryFeeCoins);
        map.put("prizePoolCoins", prizePoolCoins);
        map.put("perKillCoins", perKillCoins);
        map.put("gameType", gameType);
        return map;
    }

    public static Tournament fromMap(String id, Map<String, Object> value) {
        if (value == null) return null;
        String mode = text(value.get("mode"), text(value.get("categoryName"), "CUSTOM MODE"));
        Tournament tournament = new Tournament(id,
                text(value.get("title"), "Untitled Battle"),
                mode,
                text(value.get("categoryId"), mode),
                text(value.get("bannerUrl"), ""),
                text(value.get("description"), "Bring your squad, play fair and earn your place on the leaderboard."),
                text(value.get("date"), "TBA"),
                text(value.get("time"), "TBA"),
                longNumber(value.get("startAt"), 0L),
                longNumber(value.get("registrationCloseAt"), 0L),
                longNumber(value.get("roomReleaseAt"), 0L),
                text(value.get("prizeInfo"), "Free entry"),
                number(value.get("totalSlots"), 0),
                number(value.get("joinedSlots"), 0),
                number(value.get("teamSize"), 1),
                text(value.get("map"), "TBA"),
                text(value.get("matchType"), "SOLO"),
                text(value.get("rules"), ""),
                text(value.get("prizeDistribution"), ""),
                text(value.get("roomId"), ""),
                text(value.get("roomPassword"), ""),
                Boolean.TRUE.equals(value.get("roomReleased")),
                text(value.get("delayReason"), ""),
                text(value.get("status"), "UPCOMING"),
                text(value.get("registrationStatus"), "OPEN"),
                !(value.get("active") instanceof Boolean) || (Boolean) value.get("active"),
                number(value.get("accentColor"), Color.rgb(237, 47, 62)));
        tournament.entryFeeCoins = number(value.get("entryFeeCoins"), 0);
        tournament.prizePoolCoins = number(value.get("prizePoolCoins"), 0);
        tournament.perKillCoins = number(value.get("perKillCoins"), 0);
        tournament.gameType = text(value.get("gameType"), "BATTLE ROYALE");
        return tournament;
    }

    private static String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }


    private static long longNumber(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getMode() { return mode; }
    public String getCategoryId() { return categoryId; }
    public String getBannerUrl() { return bannerUrl; }
    public String getDescription() { return description; }
    public String getDate() { return date; }
    public String getTime() { return time; }
    public long getStartAt() { return startAt; }
    public long getEffectiveStartAt() {
        if (startAt > 0L) return startAt;
        return parseDateTime(date, time);
    }
    public long getRegistrationCloseAt() { return registrationCloseAt; }
    public long getRoomReleaseAt() { return roomReleaseAt; }
    public String getPrizeInfo() { return prizeInfo; }
    public int getTotalSlots() { return totalSlots; }
    public int getJoinedSlots() { return joinedSlots; }
    public int getTeamSize() { return teamSize; }
    public String getMap() { return map; }
    public String getMatchType() { return matchType; }
    public String getRules() { return rules; }
    public String getPrizeDistribution() { return prizeDistribution; }
    public String getRoomId() { return roomId; }
    public String getRoomPassword() { return roomPassword; }
    public boolean isRoomReleased() { return roomReleased; }
    public String getDelayReason() { return delayReason; }
    public boolean isDelayed() { return delayReason != null && !delayReason.isEmpty(); }
    public String getStatus() { return status; }
    public String getRegistrationStatus() { return registrationStatus; }
    public boolean isActive() { return active; }
    public int getAccentColor() { return accentColor; }
    public int getEntryFeeCoins() { return entryFeeCoins; }
    public int getPrizePoolCoins() { return prizePoolCoins; }
    public int getPerKillCoins() { return perKillCoins; }
    public String getGameType() { return gameType; }
    public void setCoinDetails(int entryFee, int prizePool, int perKill, String type) {
        entryFeeCoins = Math.max(0, entryFee);
        prizePoolCoins = Math.max(0, prizePool);
        perKillCoins = Math.max(0, perKill);
        gameType = type == null || type.trim().isEmpty() ? "BATTLE ROYALE" : type.trim();
    }
    public int getRemainingSlots() { return Math.max(0, totalSlots - joinedSlots); }
    public void incrementJoinedSlots() { joinedSlots = Math.min(totalSlots, joinedSlots + 1); }

    private static long parseDateTime(String date, String time) {
        if (date == null || time == null || "TBA".equalsIgnoreCase(date)
                || "TBA".equalsIgnoreCase(time)) return 0L;
        String value = date.trim() + " " + time.trim();
        String[] formats = {"dd MMM yyyy hh:mm a", "dd MMM yyyy HH:mm"};
        for (String format : formats) {
            try {
                Date parsed = new SimpleDateFormat(format, Locale.ENGLISH).parse(value);
                if (parsed != null) return parsed.getTime();
            } catch (ParseException ignored) {
                // Older admin records can use a different date/time format.
            }
        }
        return 0L;
    }
}
