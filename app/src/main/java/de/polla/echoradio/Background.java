package de.polla.echoradio;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.graphics.Matrix;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Hintergrundbild des Startbildschirms. Wird beim Speichern auf Displaygroesse verkleinert
 * und als JPEG im App-Speicher abgelegt; dazu die mittlere Helligkeit fuer die Textfarbe.
 */
final class Background {

    /** Laengste Seite des gespeicherten Bildes (Echo Show 8: 1280 x 800). */
    private static final int MAX_SIDE = 1920;
    private static final String PREFS = "background";
    private static final String LUMA = "luma";
    private static final String VERSION = "version";

    private Background() {
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), "background.jpg");
    }

    static boolean has(Context c) {
        return file(c).isFile();
    }

    /** Zaehlt bei jeder Aenderung hoch (fuer den Browser-Cache). */
    static long version(Context c) {
        return prefs(c).getLong(VERSION, 0);
    }

    /** Mittlere Helligkeit des Bildes, 0 = schwarz, 1 = weiss. */
    static float luma(Context c) {
        return prefs(c).getFloat(LUMA, 0.5f);
    }

    /** Bild (PNG, JPG, WebP, ...) uebernehmen. Wirft IOException, wenn es kein Bild ist. */
    static void save(Context c, byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Kein Bild (PNG, JPG oder WebP)");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (bmp == null) {
            throw new IOException("Bild konnte nicht gelesen werden");
        }
        bmp = rotateByExif(bmp, data);
        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest > MAX_SIDE) {
            float f = MAX_SIDE / (float) longest;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * f),
                    Math.round(bmp.getHeight() * f), true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            bmp = scaled;
        }
        float luma = averageLuma(bmp);

        File target = file(c);
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                throw new IOException("Bild nicht gespeichert");
            }
        } finally {
            bmp.recycle();
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Bild nicht gespeichert");
        }
        prefs(c).edit().putFloat(LUMA, luma).putLong(VERSION, System.currentTimeMillis()).commit();
    }

    static void remove(Context c) {
        //noinspection ResultOfMethodCallIgnored
        file(c).delete();
        prefs(c).edit().putLong(VERSION, System.currentTimeMillis()).commit();
    }

    /** Fuer die Anzeige, verkleinert auf etwa die Fenstergroesse. */
    static Bitmap load(Context c, int width, int height) {
        File f = file(c);
        if (!f.isFile()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        int sample = 1;
        if (width > 0 && height > 0) {
            while (bounds.outWidth / (sample * 2) >= width && bounds.outHeight / (sample * 2) >= height) {
                sample *= 2;
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    /** Fotos vom Handy liegen oft gedreht vor; die Drehung steht in den EXIF-Daten. */
    private static Bitmap rotateByExif(Bitmap bmp, byte[] data) {
        int degrees = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(data));
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degrees = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degrees = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degrees = 270;
                    break;
                default:
                    break;
            }
        } catch (IOException | RuntimeException ignored) {
            // kein EXIF: so lassen
        }
        if (degrees == 0) {
            return bmp;
        }
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        if (rotated != bmp) {
            bmp.recycle();
        }
        return rotated;
    }

    private static float averageLuma(Bitmap bmp) {
        Bitmap tiny = Bitmap.createScaledBitmap(bmp, 16, 16, true);
        double sum = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                sum += luma(tiny.getPixel(x, y));
            }
        }
        if (tiny != bmp) {
            tiny.recycle();
        }
        return (float) (sum / 256);
    }

    /** Wahrgenommene Helligkeit einer Farbe, 0..1. */
    static float luma(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
