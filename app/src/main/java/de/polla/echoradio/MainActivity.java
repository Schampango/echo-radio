package de.polla.echoradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Startbildschirm mit runden Knoepfen. Ein Tipp spielt den Sender ab
 * (ein zweiter Tipp auf denselben Knopf stoppt), ein langer Druck aendert
 * die Adresse des Senders.
 */
public class MainActivity extends Activity {

    static final String MODE_STREAM = "stream";  // feste Stream-Adresse
    static final String MODE_RSS = "rss";        // Podcast-Feed, neueste Folge
    static final String MODE_SEARCH = "search";  // Sendername bei radio-browser.info
    static final String MODE_APP = "app";        // andere App starten

    static final class Tile {
        final String id;
        final String label;
        final String mode;
        final String value;
        final String[] fallbacks;

        Tile(String id, String label, String mode, String value, String... fallbacks) {
            this.id = id;
            this.label = label;
            this.mode = mode;
            this.value = value;
            this.fallbacks = fallbacks;
        }
    }

    // Reihenfolge = Reihenfolge auf dem Bildschirm (4 pro Zeile)
    private final Tile[] tiles = {
            new Tile("news", "News", MODE_RSS,
                    "https://www.deutschlandfunk.de/nachrichten-108.xml"),
            new Tile("1live", "1 Live", MODE_STREAM,
                    "https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3"),
            new Tile("dlf", "DLF", MODE_STREAM,
                    "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3"),
            new Tile("brillux", "Brillux", MODE_SEARCH,
                    "Brillux Radio"),
            new Tile("apple", "Apple\nMusik", MODE_APP,
                    "com.apple.android.music"),
            new Tile("youtube", "Youtube", MODE_APP,
                    "org.schabi.newpipe", "com.google.android.youtube", "https://m.youtube.com"),
            new Tile("bundesliga", "Bundesliga\nKonferenz", MODE_STREAM,
                    "https://wdr-wdr2-rheinland.icecastssl.wdr.de/wdr/wdr2/rheinland/mp3/128/stream.mp3"),
    };

    private final List<TextView> tileViews = new ArrayList<>();
    private TextView status;
    private SharedPreferences prefs;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("streams", MODE_PRIVATE);
        // Die Lautstaerketasten am Echo regeln die Musik
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(stateReceiver, new IntentFilter(PlayerService.ACTION_STATE));
        refresh();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(stateReceiver);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    public void onBackPressed() {
        // Als Startbildschirm soll "Zurueck" die App nicht schliessen
    }

    // ---------------------------------------------------------------- Oberflaeche

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int pad = dp(16);
        int barH = dp(72);
        int margin = dp(14);
        int cols = 4;
        int rows = (tiles.length + cols - 1) / cols;
        int size = Math.min(
                (dm.widthPixels - 2 * pad) / cols - 2 * margin,
                (dm.heightPixels - 2 * pad - barH) / rows - 2 * margin);
        if (size < dp(60)) {
            size = dp(60);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        for (Tile t : tiles) {
            TextView v = new TextView(this);
            v.setText(t.label);
            v.setGravity(Gravity.CENTER);
            v.setTextColor(Color.WHITE);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * textFactor(t.label));
            v.setBackgroundResource(R.drawable.circle);
            v.setForeground(ripple());
            v.setClickable(true);
            v.setFocusable(true);
            v.setTag(t.id);
            v.setOnClickListener(x -> onTile(t));
            if (!MODE_APP.equals(t.mode)) {
                v.setOnLongClickListener(x -> {
                    editTile(t);
                    return true;
                });
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(margin, margin, margin, margin);
            grid.addView(v, lp);
            tileViews.add(v);
        }

        FrameLayout center = new FrameLayout(this);
        center.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(center, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        status = new TextView(this);
        status.setTextColor(0xFF444444);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        status.setSingleLine(true);
        status.setPadding(dp(16), 0, dp(16), 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stop = new TextView(this);
        stop.setText("Stopp");
        stop.setTextColor(Color.WHITE);
        stop.setTypeface(Typeface.DEFAULT_BOLD);
        stop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stop.setGravity(Gravity.CENTER);
        stop.setBackgroundResource(R.drawable.pill);
        stop.setPadding(dp(36), 0, dp(36), 0);
        stop.setOnClickListener(x -> stopPlayback());
        bar.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barH));
        setContentView(root);
    }

    private static float textFactor(String label) {
        int longest = 0;
        for (String line : label.split("\n")) {
            longest = Math.max(longest, line.length());
        }
        if (longest <= 4) {
            return 0.20f;
        }
        if (longest <= 7) {
            return 0.16f;
        }
        return 0.11f;
    }

    private static RippleDrawable ripple() {
        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        mask.getPaint().setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, mask);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private void refresh() {
        String current = PlayerService.currentId;
        for (TextView v : tileViews) {
            boolean active = v.getTag().equals(current);
            v.setBackgroundResource(active ? R.drawable.circle_active : R.drawable.circle);
        }
        String s = PlayerService.currentStatus;
        status.setText(s == null || s.isEmpty() ? "Bereit" : s);
    }

    // ---------------------------------------------------------------- Aktionen

    private String valueFor(Tile t) {
        return prefs.getString(t.id, t.value);
    }

    private void onTile(Tile t) {
        if (MODE_APP.equals(t.mode)) {
            stopPlayback();
            launchApp(t);
            return;
        }
        if (t.id.equals(PlayerService.currentId)) {
            stopPlayback();
            return;
        }
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_PLAY);
        i.putExtra(PlayerService.EXTRA_ID, t.id);
        i.putExtra(PlayerService.EXTRA_NAME, t.label.replace('\n', ' '));
        i.putExtra(PlayerService.EXTRA_MODE, t.mode);
        i.putExtra(PlayerService.EXTRA_VALUE, valueFor(t));
        startForegroundService(i);
    }

    private void stopPlayback() {
        if (PlayerService.currentId == null) {
            return;
        }
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_STOP);
        startService(i);
    }

    private void launchApp(Tile t) {
        List<String> targets = new ArrayList<>();
        targets.add(t.value);
        for (String f : t.fallbacks) {
            targets.add(f);
        }
        for (String target : targets) {
            Intent i;
            if (target.startsWith("http")) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            } else {
                i = getPackageManager().getLaunchIntentForPackage(target);
                if (i == null) {
                    continue;
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException e) {
                // naechstes Ziel probieren
            }
        }
        Toast.makeText(this, t.label.replace('\n', ' ') + " ist nicht installiert", Toast.LENGTH_LONG).show();
    }

    private void editTile(Tile t) {
        String hint;
        if (MODE_RSS.equals(t.mode)) {
            hint = "Adresse des Podcast-Feeds";
        } else if (MODE_SEARCH.equals(t.mode)) {
            hint = "Sendername oder Stream-Adresse";
        } else {
            hint = "Stream-Adresse";
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(valueFor(t));
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle(t.label.replace('\n', ' ') + ": " + hint)
                .setView(input)
                .setPositiveButton("Speichern", (dialog, which) ->
                        prefs.edit().putString(t.id, input.getText().toString().trim()).apply())
                .setNeutralButton("Standard", (dialog, which) ->
                        prefs.edit().remove(t.id).apply())
                .setNegativeButton("Abbrechen", null)
                .show();
    }
}
