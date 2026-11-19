package de.polla.echoradio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Zugriff auf das freie Senderverzeichnis radio-browser.info. */
final class RadioBrowser {

    static final class Station {
        final String name;
        final String url;
        final String favicon;
        final String country;

        Station(String name, String url, String favicon, String country) {
            this.name = name;
            this.url = url;
            this.favicon = favicon;
            this.country = country;
        }
    }

    private static final String[] SERVERS = {
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info",
            "https://fi1.api.radio-browser.info",
    };

    private RadioBrowser() {
    }

    /** Sender, deren Name den Suchbegriff enthaelt, beliebteste zuerst. */
    static List<Station> search(String name, int limit) throws IOException {
        String q = URLEncoder.encode(name.trim(), "UTF-8").replace("+", "%20");
        String path = "/json/stations/byname/" + q
                + "?hidebroken=true&order=clickcount&reverse=true&limit=" + limit;
        IOException last = null;
        for (String server : SERVERS) {
            try {
                return parse(Http.getString(server + path));
            } catch (Http.StatusException e) {
                if (e.code < 500) {
                    throw e;
                }
                last = e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("radio-browser.info nicht erreichbar");
    }

    /** Stream-Adresse des am besten passenden Senders. */
    static String findStreamUrl(String name) throws IOException {
        Station best = null;
        for (Station s : search(name, 10)) {
            if (s.url.isEmpty()) {
                continue;
            }
            if (best == null) {
                best = s;
            }
            if (s.name.equalsIgnoreCase(name.trim())) {
                best = s;
                break;
            }
        }
        if (best == null) {
            throw new Http.NotFoundException("Sender „" + name + "“ nicht gefunden");
        }
        return best.url;
    }

    private static List<Station> parse(String json) throws IOException {
        List<Station> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String url = o.optString("url_resolved");
                if (url.isEmpty()) {
                    url = o.optString("url");
                }
                list.add(new Station(o.optString("name").trim(), url.trim(),
                        o.optString("favicon").trim(), o.optString("countrycode")));
            }
        } catch (JSONException e) {
            throw new IOException("Antwort von radio-browser.info unlesbar");
        }
        return list;
    }
}
