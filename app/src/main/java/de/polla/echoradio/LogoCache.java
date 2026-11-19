package de.polla.echoradio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
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
                    byte[] data = Http.getBytes(address, MAX_DOWNLOAD);
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

    /** Liest das Bild verkleinert ein (SVG wird von Android nicht unterstuetzt). */
    private static Bitmap decode(File file, int sizePx) {
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
