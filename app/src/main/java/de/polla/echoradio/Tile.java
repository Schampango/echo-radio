package de.polla.echoradio;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** Ein Knopf auf dem Startbildschirm. */
final class Tile {

    static final String STREAM = "stream";  // feste Stream-Adresse
    static final String RSS = "rss";        // Podcast-Feed, neueste Folge
    static final String SEARCH = "search";  // Sendername bei radio-browser.info
    static final String APP = "app";        // andere App starten (oder Webseite)

    static final int DEFAULT_COLOR = 0xFF00A0E3;
    /** Logo-Groesse in Prozent des Knopfs; 60 % bleibt auch eckig innerhalb des gelben Rings. */
    static final int DEFAULT_LOGO_SIZE = 60;
    static final int MIN_LOGO_SIZE = 20;
    static final int MAX_LOGO_SIZE = 100;
    static final String SHAPE_SQUARE = "square";  // ganzes Logo sichtbar
    static final String SHAPE_ROUND = "round";    // Logo kreisrund zugeschnitten

    String id;
    String label;
    String type;
    /** Adresse, Feed, Sendername oder Paketname. Bei Apps mehrere Ziele mit | getrennt. */
    String value;
    int color;
    /** Adresse eines Logos (leer = nur Beschriftung). */
    String logo = "";
    int logoSize = DEFAULT_LOGO_SIZE;
    String logoShape = SHAPE_SQUARE;
    /** Weitere Namen fuer die Sprachsteuerung, mit Komma getrennt. */
    String voice = "";

    Tile(String id, String label, String type, String value, int color) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.value = value;
        this.color = color;
    }

    static Tile create() {
        return new Tile(UUID.randomUUID().toString(), "", STREAM, "", DEFAULT_COLOR);
    }

    String displayName() {
        return label.replace('\n', ' ').trim();
    }

    /** Beschriftung und alle weiteren Sprach-Namen. */
    java.util.List<String> spokenNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(label);
        for (String v : voice.split(",")) {
            if (!v.trim().isEmpty()) {
                names.add(v.trim());
            }
        }
        return names;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("label", label);
        o.put("type", type);
        o.put("value", value);
        o.put("color", toHex(color));
        if (!logo.isEmpty()) {
            o.put("logo", logo);
            o.put("logoSize", logoSize);
            o.put("logoShape", logoShape);
        }
        if (!voice.isEmpty()) {
            o.put("voice", voice);
        }
        return o;
    }

    static Tile fromJson(JSONObject o) {
        int color;
        try {
            color = Color.parseColor(o.optString("color", toHex(DEFAULT_COLOR)));
        } catch (IllegalArgumentException e) {
            color = DEFAULT_COLOR;
        }
        String id = o.optString("id");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
        }
        Tile t = new Tile(id, o.optString("label"), o.optString("type", STREAM), o.optString("value"), color);
        t.logo = o.optString("logo").trim();
        t.logoSize = clampLogoSize(o.optInt("logoSize", DEFAULT_LOGO_SIZE));
        t.logoShape = SHAPE_ROUND.equals(o.optString("logoShape")) ? SHAPE_ROUND : SHAPE_SQUARE;
        t.voice = o.optString("voice").trim();
        return t;
    }

    static int clampLogoSize(int v) {
        return Math.max(MIN_LOGO_SIZE, Math.min(MAX_LOGO_SIZE, v));
    }

    boolean roundLogo() {
        return SHAPE_ROUND.equals(logoShape);
    }

    static String toHex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }
}
