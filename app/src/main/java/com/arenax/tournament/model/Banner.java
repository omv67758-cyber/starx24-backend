package com.arenax.tournament.model;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** Admin-controlled home-page banner. Admins publish these from the console (title, subtitle,
 *  image, display order) and they render live as a scrollable carousel on the User app home screen. */
public class Banner implements Serializable {
    private final String id;
    private final String title;
    private final String subtitle;
    private final String imageUrl;
    private final int displayOrder;
    private final long createdAt;
    private final boolean active;

    public Banner(String id, String title, String subtitle, String imageUrl,
                   int displayOrder, long createdAt, boolean active) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.imageUrl = imageUrl;
        this.displayOrder = displayOrder;
        this.createdAt = createdAt;
        this.active = active;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("title", title);
        map.put("subtitle", subtitle);
        map.put("imageUrl", imageUrl);
        map.put("displayOrder", displayOrder);
        map.put("createdAt", createdAt);
        map.put("active", active);
        return map;
    }

    public static Banner fromMap(String id, Map<String, Object> value) {
        if (value == null) return null;
        Object created = value.get("createdAt");
        Object activeValue = value.get("active");
        Object orderValue = value.get("displayOrder");
        Object imageValue = value.get("imageUrl");
        if (imageValue == null) imageValue = value.get("url");
        return new Banner(id,
                value.get("title") == null ? "" : String.valueOf(value.get("title")),
                value.get("subtitle") == null ? "" : String.valueOf(value.get("subtitle")),
                imageValue == null ? "" : String.valueOf(imageValue),
                orderValue instanceof Number ? ((Number) orderValue).intValue() : 0,
                created instanceof Number ? ((Number) created).longValue() : 0L,
                !(activeValue instanceof Boolean) || (Boolean) activeValue
        );
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public String getImageUrl() { return imageUrl; }
    public int getDisplayOrder() { return displayOrder; }
    public boolean isActive() { return active; }
}
