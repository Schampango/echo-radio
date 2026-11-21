package de.polla.echoradio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.util.Base64;

import com.caverock.androidsvg.SVG;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Laedt Senderlogos aus dem Netz und speichert sie im Cache der App. */
final class LogoCache {

    interface Callback {
        /** Im Haupt-Thread; null, wenn das Logo nicht geladen werden konnte. */
        void onLogo(Bitmap bitmap);
    }

    private static final int MAX_DOWNLOAD = 2 * 1024 * 1024;

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LogoCache() {
    }

    static void load(Context context, String url, int sizePx, Callback callback) {
        if (url == null || url.trim().isEmpty()) {
            callback.onLogo(null);
            return;
        }
        String address = url.trim();
        String key = address + "@" + sizePx;
        Bitmap cached = MEMORY.get(key);
        if (cached != null) {
            callback.onLogo(cached);
            return;
        }
        File dir = new File(context.getApplicationContext().getCacheDir(), "logos");
        POOL.execute(() -> {
            Bitmap bitmap = null;
            try {
                File file = new File(dir, hash(address));
                if (!file.exists()) {
                    byte[] data = address.startsWith("data:")
                            ? dataUri(address)
                            : Http.getBytes(address, MAX_DOWNLOAD);
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IOException("Cache-Ordner fehlt");
                    }
                    File tmp = new File(dir, file.getName() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(data);
                    }
                    if (!tmp.renameTo(file)) {
                        throw new IOException("Logo nicht gespeichert");
                    }
                }
                bitmap = decode(file, sizePx);
            } catch (Exception e) {
                bitmap = null;
            }
            Bitmap result = bitmap;
            if (result != null) {
                MEMORY.put(key, result);
            }
            MAIN.post(() -> callback.onLogo(result));
        });
    }

    /** Eingebettetes Bild: data:image/png;base64,... oder data:image/svg+xml,<svg ...> */
    static byte[] dataUri(String uri) throws IOException {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            throw new IOException("Ungültige data-Adresse");
        }
        String head = uri.substring(5, comma);
        String payload = uri.substring(comma + 1);
        if (head.endsWith(";base64")) {
            try {
                return Base64.decode(payload, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                throw new IOException("Ungültige Base64-Daten", e);
            }
        }
        return URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
    }

    /** Liest das Bild verkleinert ein; SVG wird in der passenden Groesse gezeichnet. */
    private static Bitmap decode(File file, int sizePx) {
        if (isSvg(file)) {
            return renderSvg(file, sizePx);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }

    /** Beginnt die Datei wie ein SVG (auch mit XML-Kopf oder Kommentar davor)? */
    private static boolean isSvg(File file) {
        byte[] head = new byte[1024];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = in.read(head);
        } catch (IOException e) {
            return false;
        }
        if (n <= 0) {
            return false;
        }
        String s = new String(head, 0, n, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        return s.contains("<svg");
    }

    private static Bitmap renderSvg(File file, int sizePx) {
        try (InputStream in = new FileInputStream(file)) {
            SVG svg = SVG.getFromInputStream(in);
            float w = svg.getDocumentWidth();
            float h = svg.getDocumentHeight();
            RectF box = svg.getDocumentViewBox();
            if ((w <= 0 || h <= 0) && box != null) {
                w = box.width();
                h = box.height();
            }
            if (w <= 0 || h <= 0) {
                w = 1;
                h = 1;
            }
            if (box == null) {
                // Ohne viewBox skaliert AndroidSVG nicht; Groesse des Dokuments als viewBox nehmen
                svg.setDocumentViewBox(0, 0, w, h);
            }
            // In die Logo-Flaeche einpassen, Seitenverhaeltnis behalten
            float scale = Math.min(sizePx / w, sizePx / h);
            int bw = Math.max(1, Math.round(w * scale));
            int bh = Math.max(1, Math.round(h * scale));
            svg.setDocumentWidth(bw);
            svg.setDocumentHeight(bh);
            Bitmap bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            svg.renderToCanvas(new Canvas(bitmap), new RectF(0, 0, bw, bh));
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
