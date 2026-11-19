package de.polla.echoradio;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Uhr mit Sender, Interpret und Titel; erscheint, wenn das Display abgedunkelt ist. */
final class ClockView extends FrameLayout {

    private final TextView time;
    private final TextView date;
    private final TextView station;
    private final TextView artist;
    private final TextView title;
    private final LinearLayout column;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.GERMANY);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d. MMMM", Locale.GERMANY);
    private int lastMinute = -1;

    ClockView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);

        column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        time = text(c, 150, 0xFFEEEEEE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        date = text(c, 30, 0xFF9E9E9E, Typeface.DEFAULT);
        station = text(c, 34, 0xFF4FC3F7, Typeface.DEFAULT_BOLD);
        artist = text(c, 30, 0xFFE0E0E0, Typeface.DEFAULT_BOLD);
        title = text(c, 28, 0xFFBDBDBD, Typeface.DEFAULT);

        column.addView(time);
        column.addView(date);
        View gap = new View(c);
        column.addView(gap, new LinearLayout.LayoutParams(1, dp(40)));
        column.addView(station);
        column.addView(artist);
        column.addView(title);

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.leftMargin = dp(40);
        lp.rightMargin = dp(40);
        addView(column, lp);
        update();
    }

    private TextView text(Context c, int sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    /** Uhrzeit und laufenden Titel aktualisieren (einmal pro Sekunde aufrufen). */
    void update() {
        Date now = new Date();
        time.setText(timeFormat.format(now));
        date.setText(dateFormat.format(now));

        String name = PlayerService.currentId == null ? null : PlayerService.currentStation;
        String song = PlayerService.currentId == null ? "" : PlayerService.currentTitle;
        if (name == null || name.isEmpty()) {
            setLine(station, null);
            setLine(artist, null);
            setLine(title, null);
        } else {
            setLine(station, name.replace('\n', ' '));
            String[] parts = splitSong(song == null ? "" : song);
            setLine(artist, parts[0]);
            setLine(title, parts[1]);
        }

        // Alle paar Minuten leicht verschieben, damit nichts einbrennt
        int minute = (int) (now.getTime() / 60000L);
        if (minute != lastMinute && minute % 3 == 0) {
            lastMinute = minute;
            int range = dp(24);
            column.setTranslationX((float) ((minute * 7) % (2 * range + 1) - range));
            column.setTranslationY((float) ((minute * 13) % (2 * range + 1) - range));
        }
    }

    /** "Interpret - Titel" in zwei Zeilen. */
    static String[] splitSong(String song) {
        String s = song.trim();
        if (s.isEmpty()) {
            return new String[]{null, null};
        }
        for (String sep : new String[]{" - ", " – ", " — ", " / "}) {
            int i = s.indexOf(sep);
            if (i > 0 && i + sep.length() < s.length()) {
                return new String[]{s.substring(0, i).trim(), s.substring(i + sep.length()).trim()};
            }
        }
        return new String[]{null, s};
    }

    private static void setLine(TextView v, String text) {
        if (text == null || text.isEmpty()) {
            v.setVisibility(GONE);
        } else {
            v.setText(text);
            v.setVisibility(VISIBLE);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
