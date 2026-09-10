package com.arenax.tournament.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class GameMode implements Serializable {
    private final String id;
    private final String title;
    private final String imageUrl;
    private final String description;
    private final int displayOrder;
    private final long createdAt;
    private final boolean active;
    private final boolean online;
    private final int totalUsers;

    public GameMode(String id, String title, String imageUrl, long createdAt, boolean active) {
        this(id, title, imageUrl, "", 0, createdAt, active, false, 0);
    }

    public GameMode(String id, String title, String imageUrl, String description,
                    int displayOrder, long createdAt, boolean active) {
        this(id, title, imageUrl, description, displayOrder, createdAt, active, false, 0);
    }

    public GameMode(String id, String title, String imageUrl, String description,
                    int displayOrder, long createdAt, boolean active, boolean online, int totalUsers) {
        this.id = id;
        this.title = title;
        this.imageUrl = imageUrl;
        this.description = description;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
        this.active = active;
        this.online = online;
        this.totalUsers = totalUsers;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("title", title);
        map.put("imageUrl", imageUrl);
        map.put("description", description);
        map.put("displayOrder", displayOrder);
        map.put("createdAt", createdAt);
        map.put("active", active);
        map.put("online", online);
        map.put("totalUsers", totalUsers);
        return map;
    }

    public static GameMode fromMap(String id, Map<String, Object> value) {
        if (value == null) return null;
        Object created = value.get("createdAt");
        Object activeValue = value.get("active");
        Object orderValue = value.get("displayOrder");
        Object onlineValue = value.get("online");
        Object usersValue = value.get("totalUsers");
        return new GameMode(id,
                value.get("title") == null ? "Untitled Game" : String.valueOf(value.get("title")),
                value.get("imageUrl") == null ? "" : String.valueOf(value.get("imageUrl")),
                value.get("description") == null ? "" : String.valueOf(value.get("description")),
                orderValue instanceof Number ? ((Number) orderValue).intValue() : 0,
                created instanceof Number ? ((Number) created).longValue() : 0L,
                !(activeValue instanceof Boolean) || (Boolean) activeValue,
                onlineValue instanceof Boolean && (Boolean) onlineValue,
                usersValue instanceof Number ? ((Number) usersValue).intValue() : 0
        );
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getImageUrl() { return imageUrl; }
    public String getDescription() { return description; }
    public int getDisplayOrder() { return displayOrder; }
    public long getCreatedAt() { return createdAt; }
    public boolean isActive() { return active; }
    public boolean isOnline() { return online; }
    public int getTotalUsers() { return totalUsers; }
}
