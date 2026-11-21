package de.polla.echoradio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Kleine HTTP-Hilfen. Folgt Weiterleitungen auch zwischen http und https. */
final class Http {

    // Mit Projektadresse: Wikimedia u. a. lehnen Abrufe ohne aussagekraeftige Kennung ab
    static final String USER_AGENT = "EchoRadio/1.12 (Android; https://github.com/Schampango/echo-radio)";

    /** Server hat mit einem Fehlerstatus geantwortet. */
    static final class StatusException extends IOException {
        final int code;

        StatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    /** Etwas Gesuchtes existiert nicht (kein Neuversuch sinnvoll). */
    static final class NotFoundException extends IOException {
        NotFoundException(String message) {
            super(message);
        }
    }

    private Http() {
    }

    static String getString(String address) throws IOException {
        return new String(getBytes(address, 5 * 1024 * 1024), StandardCharsets.UTF_8);
    }

    static byte[] getBytes(String address, int maxBytes) throws IOException {
        HttpURLConnection c = open(address, null, null);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new IOException("Antwort zu groß");
                }
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Oeffnet eine Verbindung und folgt bis zu 5 Weiterleitungen.
     * Liefert eine Verbindung mit Status 200; sonst StatusException.
     */
    static HttpURLConnection open(String address, String headerName, String headerValue) throws IOException {
        String current = address;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (headerName != null) {
                c.setRequestProperty(headerName, headerValue);
            }
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                c.disconnect();
                throw e;
            }
            if (code >= 300 && code < 400) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) {
                    throw new IOException("Weiterleitung ohne Ziel");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new StatusException(code);
            }
            return c;
        }
        throw new IOException("zu viele Weiterleitungen");
    }

    /**
     * Folgt den Weiterleitungen einer Stream-Adresse (z. B. Lastverteiler) und liefert
     * die endgueltige Adresse. Bei Fehlern wird die urspruengliche Adresse zurueckgegeben,
     * nur ein eindeutiger Fehlerstatus des Servers wird weitergereicht.
     */
    static String finalUrl(String address) throws IOException {
        String current = address;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c;
            int code;
            try {
                c = (HttpURLConnection) new URL(current).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", USER_AGENT);
                code = c.getResponseCode();
            } catch (IOException e) {
                // z. B. alter SHOUTcast-Server ("ICY 200 OK"): dem Player selbst ueberlassen
                return current;
            }
            String location = c.getHeaderField("Location");
            c.disconnect();
            if (code >= 300 && code < 400 && location != null) {
                current = new URL(new URL(current), location).toString();
                continue;
            }
            if (code == 404 || code == 410) {
                throw new StatusException(code);
            }
            return current;
        }
        return current;
    }
}
