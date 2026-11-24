package com.officepool;

import org.json.JSONArray;
import org.json.JSONObject;

final class OfficeMember {
    static final int START_RATING = 1200;
    final String uid, name, photoPath;
    final int rating;
    final JSONArray ratingHistory;

    OfficeMember(String uid, String name, String photoPath) {
        this(uid, name, photoPath, START_RATING);
    }

    OfficeMember(String uid, String name, String photoPath, int rating) {
        this(uid, name, photoPath, rating, new JSONArray());
    }

    OfficeMember(String uid, String name, String photoPath, int rating, JSONArray history) {
        this.uid = uid;
        this.name = name;
        this.photoPath = photoPath;
        this.rating = rating;
        this.ratingHistory = history == null ? new JSONArray() : history;
    }

    static OfficeMember fromJson(JSONObject j) {
        return new OfficeMember(
                j.optString("uid"),
                j.optString("displayName", "Office player"),
                j.optString("photoPath", ""),
                j.optInt("rating", START_RATING),
                j.optJSONArray("ratingHistory"));
    }

    JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("uid", uid);
            j.put("displayName", name);
            j.put("photoPath", photoPath);
            j.put("rating", rating);
            j.put("ratingHistory", ratingHistory);
        } catch (Exception ignored) {
        }
        return j;
    }
}
