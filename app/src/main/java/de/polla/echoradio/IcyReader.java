package de.polla.echoradio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest die "Jetzt laeuft"-Angaben (ICY-Metadaten) eines Radiostreams ueber eine
 * zweite Verbindung mit. Liefert der Sender keine Angaben, beendet sich der Leser still.
 */
final class IcyReader implements Runnable {

    interface Listener {
        /** Wird im Hintergrund-Thread aufgerufen. */
        void onTitle(String title);
    }

    private static final Pattern TITLE = Pattern.compile("StreamTitle='(.*?)';", Pattern.DOTALL);

    private final String url;
    private final Listener listener;
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private Thread thread;
    private String lastTitle;

    IcyReader(String url, Listener listener) {
        this.url = url;
        this.listener = listener;
    }

    void start() {
        thread = new Thread(this, "icy-reader");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stopped = true;
        HttpURLConnection c = connection;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // egal
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                if (!readStream()) {
                    return; // Sender liefert keine Titel
                }
            } catch (Exception e) {
                // Verbindung abgebrochen: gleich nochmal
            }
            if (stopped) {
                return;
            }
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** @return false, wenn der Sender keine Metadaten anbietet. */
    private boolean readStream() throws IOException {
        HttpURLConnection c = Http.open(url, "Icy-MetaData", "1");
        connection = c;
        try {
            String header = c.getHeaderField("icy-metaint");
            if (header == null) {
                return false;
            }
            int interval;
            try {
                interval = Integer.parseInt(header.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            if (interval <= 0) {
                return false;
            }
            InputStream in = new BufferedInputStream(c.getInputStream(), 16384);
            byte[] meta = new byte[16 * 255];
            while (!stopped) {
                skipFully(in, interval);
                int lengthByte = in.read();
                if (lengthByte < 0) {
                    throw new IOException("Stream zu Ende");
                }
                int length = lengthByte * 16;
                if (length > 0) {
                    readFully(in, meta, length);
                    handle(decode(meta, length));
                }
            }
            return true;
        } finally {
            c.disconnect();
            connection = null;
        }
    }

    private void handle(String meta) {
        Matcher m = TITLE.matcher(meta);
        if (!m.find()) {
            return;
        }
        String title = m.group(1).trim();
        if (title.isEmpty() || title.equals(lastTitle)) {
            return;
        }
        lastTitle = title;
        listener.onTitle(title);
    }

    private static String decode(byte[] data, int length) {
        String s = new String(data, 0, length, StandardCharsets.UTF_8);
        if (s.indexOf('�') >= 0) {
            s = new String(data, 0, length, StandardCharsets.ISO_8859_1);
        }
        return s;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Stream zu Ende");
                }
                skipped = 1;
            }
            left -= skipped;
        }
    }

    private static void readFully(InputStream in, byte[] buf, int length) throws IOException {
        int off = 0;
        while (off < length) {
            int r = in.read(buf, off, length - off);
            if (r < 0) {
                throw new IOException("Stream zu Ende");
            }
            off += r;
        }
    }
}
