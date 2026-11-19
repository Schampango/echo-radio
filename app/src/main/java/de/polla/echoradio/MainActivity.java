package de.polla.echoradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Startbildschirm mit frei konfigurierbaren runden Knoepfen.
 * Normalbetrieb: Tippen spielt ab bzw. startet die App, nochmal tippen stoppt.
 * Bearbeiten-Modus: Tippen oeffnet den Editor, "+" fuegt einen Knopf hinzu,
 * dazu Sichern/Laden der Knoepfe als Datei.
 * Langer Druck oeffnet den Editor jederzeit. Mehr Knoepfe als auf eine Seite
 * passen werden auf Seiten verteilt, zwischen denen man wischt.
 */
public class MainActivity extends Activity {

    private static final int[] PALETTE = {
            0xFF00A0E3, 0xFF1565C0, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835,
            0xFFFB8C00, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF6D4C41, 0xFF546E7A,
            0xFFFFFFFF, 0xFF212121,
    };

    private static final String[] TYPES = {Tile.STREAM, Tile.RSS, Tile.SEARCH, Tile.APP};
    private static final String[] TYPE_NAMES = {
            "Radio-Stream (Adresse)",
            "Neueste Folge aus Podcast-Feed (z. B. Nachrichten)",
            "Sender suchen (radio-browser.info)",
            "App oder Webseite öffnen",
    };
    private static final String[] TYPE_HINTS = {
            "https://… Stream-Adresse",
            "https://… Feed-Adresse",
            "Sendername, z. B. Brillux Radio",
            "Paketname oder https://… – oder „App auswählen“",
    };

    /** Hoechstens so viele Knoepfe pro Seite. */
    private static final int MAX_PER_PAGE = 12;
    private static final int REQUEST_EXPORT = 1;
    private static final int REQUEST_IMPORT = 2;
    private static final int REQUEST_MICROPHONE = 3;

    private List<Tile> tiles;
    /** Revision der Knoepfe, die gerade angezeigt werden. */
    private int knownRevision;
    private boolean editMode;
    private int page;
    private int pageCount = 1;
    /** Knoepfe pro Seite beim letzten Aufbau. */
    private int pageCapacity = MAX_PER_PAGE;

    private SwipeFrame gridHolder;
    private ListeningFrame listeningFrame;

    // Abdunkeln
    /** Nach einem Sprachbefehl so lange hell bleiben (ms). */
    private static final long VOICE_WAKE_MS = 8000;
    private AppSettings settings;
    private View dimOverlay;
    private boolean dimmed;
    /** Die Beruehrung, die das Display aufweckt, loest nichts aus. */
    private boolean swallowTouch;
    /** Display wurde nur fuer einen Sprachbefehl aufgeweckt. */
    private boolean voiceWake;
    private final Runnable dimNow = this::dim;
    private TextView status;
    private TextView editButton;
    private TextView stopButton;
    private TextView backupButton;
    private TextView restoreButton;
    private TextView displayButton;
    private TextView soundButton;
    private final Map<String, View> tileViews = new HashMap<>();
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    // Fortschrittsbalken fuer Podcasts
    private LinearLayout progressRow;
    private SeekBar seekBar;
    private TextView positionLabel;
    private TextView durationLabel;
    private boolean dragging;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ticker.postDelayed(this, 500);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VoiceService.ACTION_LISTENING.equals(intent.getAction())) {
                // Liegt der Rand schon ueber allen Apps, nicht doppelt zeichnen
                int mode = intent.getIntExtra(VoiceService.EXTRA_MODE, ListeningFrame.IDLE);
                if (!intent.getBooleanExtra(VoiceService.EXTRA_OVERLAY, false) && listeningFrame != null) {
                    listeningFrame.show(mode);
                }
                onVoice(mode);
            } else if (AppSettings.ACTION_CHANGED.equals(intent.getAction())) {
                settings = AppSettings.load(MainActivity.this);
                wake();
            } else if (TileStore.ACTION_TILES_CHANGED.equals(intent.getAction())) {
                // In der Weboberflaeche geaendert
                reloadTiles();
            } else {
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tiles = TileStore.load(this);
        knownRevision = TileStore.revision(this);
        settings = AppSettings.load(this);
        // Display bleibt an, solange die App vorne ist; abgedunkelt wird selbst
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Die Lautstaerketasten am Echo regeln die Musik
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildUi();
        try {
            startForegroundService(new Intent(this, WebService.class));
        } catch (RuntimeException e) {
            // ohne Weboberflaeche weitermachen
        }
        // Sprachsteuerung: braucht das Mikrofon
        if (VoiceService.hasPermission(this)) {
            VoiceService.startIfAllowed(this);
        } else if (VoiceService.isEnabled(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MICROPHONE && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            VoiceService.startIfAllowed(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(PlayerService.ACTION_STATE);
        filter.addAction(TileStore.ACTION_TILES_CHANGED);
        filter.addAction(VoiceService.ACTION_LISTENING);
        filter.addAction(AppSettings.ACTION_CHANGED);
        registerReceiver(stateReceiver, filter);
        // Waehrend die App im Hintergrund war, kann im Browser etwas geaendert worden sein
        if (TileStore.revision(this) != knownRevision) {
            reloadTiles();
        }
        refresh();
        ticker.post(tick);
        ReturnTimer.cancel();
        settings = AppSettings.load(this);
        wake();
    }

    @Override
    protected void onStop() {
        ticker.removeCallbacks(tick);
        ticker.removeCallbacks(dimNow);
        unregisterReceiver(stateReceiver);
        // Eine andere App ist vorne: spaeter zurueckholen, falls eingestellt
        ReturnTimer.schedule(this, settings.returnMinutes);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    // ---------------------------------------------------------------- Abdunkeln

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            swallowTouch = dimmed;
            voiceWake = false;
            wake();
        }
        if (swallowTouch) {
            // Erste Beruehrung weckt nur auf, damit nicht versehentlich ein Sender startet
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** Volle Helligkeit und Zeitschaltung neu starten. */
    private void wake() {
        ticker.removeCallbacks(dimNow);
        if (dimmed) {
            dimmed = false;
            applyBrightness();
        }
        scheduleDim(settings.dimSeconds * 1000L);
    }

    private void scheduleDim(long delay) {
        ticker.removeCallbacks(dimNow);
        if (settings.dimSeconds > 0) {
            ticker.postDelayed(dimNow, delay);
        }
    }

    private void dim() {
        if (editMode) {
            // Beim Bearbeiten nicht stoeren
            scheduleDim(settings.dimSeconds * 1000L);
            return;
        }
        if (!dimmed && settings.dimSeconds > 0) {
            dimmed = true;
            applyBrightness();
        }
    }

    private void applyBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float overlay;
        if (dimmed) {
            int level = settings.dimLevel;
            lp.screenBrightness = Math.max(0.01f, level / 100f);
            // Unterhalb der kleinsten Hintergrundbeleuchtung mit Schwarz nachhelfen
            overlay = level == 0 ? 1f : Math.max(0f, 0.6f * (1f - level / 20f));
        } else {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            overlay = 0f;
        }
        getWindow().setAttributes(lp);
        if (dimOverlay != null) {
            dimOverlay.animate().cancel();
            if (overlay > 0f) {
                dimOverlay.setVisibility(View.VISIBLE);
            }
            dimOverlay.animate().alpha(overlay).setDuration(dimmed ? 1000 : 250)
                    .withEndAction(() -> {
                        if (!dimmed) {
                            dimOverlay.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    /** "Alexa" weckt das Display kurz auf. */
    private void onVoice(int mode) {
        if (mode == ListeningFrame.LISTENING) {
            if (dimmed) {
                voiceWake = true;
            }
            wake();
            // Waehrend zugehoert wird, hell bleiben
            ticker.removeCallbacks(dimNow);
        } else if (voiceWake) {
            voiceWake = false;
            scheduleDim(Math.min(VOICE_WAKE_MS, settings.dimSeconds * 1000L));
        } else {
            scheduleDim(settings.dimSeconds * 1000L);
        }
    }

    @Override
    public void onBackPressed() {
        // Als Startbildschirm soll "Zurueck" die App nicht schliessen
        if (editMode) {
            toggleEditMode();
        }
    }

    // ---------------------------------------------------------------- Grundgeruest

    private void buildUi() {
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(pad, pad, pad, pad);

        gridHolder = new SwipeFrame(this);
        gridHolder.setOnSwipeListener(direction -> {
            int next = page + direction;
            if (next >= 0 && next < pageCount) {
                page = next;
                rebuildGrid();
            }
        });
        gridHolder.addOnLayoutChangeListener((v, left, top, right, bottom,
                                              oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                v.post(this::rebuildGrid);
            }
        });
        root.addView(gridHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        // Links: Statuszeile, darunter (nur bei Podcasts) der Fortschrittsbalken
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(dp(16), 0, dp(16), 0);

        status = new TextView(this);
        status.setTextColor(0xFF444444);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(status);

        progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressRow.setVisibility(View.GONE);
        positionLabel = timeLabel();
        durationLabel = timeLabel();
        seekBar = new SeekBar(this);
        seekBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) {
                    positionLabel.setText(PlayerService.formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                seekTo(sb.getProgress());
            }
        });
        progressRow.addView(positionLabel);
        progressRow.addView(seekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        progressRow.addView(durationLabel);
        info.addView(progressRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bar.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        backupButton = pillButton("Sichern", 0xFF00897B);
        backupButton.setOnClickListener(x -> exportTiles());
        bar.addView(backupButton, barButtonParams());

        restoreButton = pillButton("Laden", 0xFF00897B);
        restoreButton.setOnClickListener(x -> importTiles());
        bar.addView(restoreButton, barButtonParams());

        displayButton = pillButton("Display", 0xFF5E35B1);
        displayButton.setOnClickListener(x -> showDisplaySettings());
        bar.addView(displayButton, barButtonParams());

        soundButton = pillButton("Klang", 0xFF5E35B1);
        soundButton.setOnClickListener(x -> showEqualizer());
        bar.addView(soundButton, barButtonParams());

        editButton = pillButton("Bearbeiten", 0xFF757575);
        editButton.setOnClickListener(x -> toggleEditMode());
        bar.addView(editButton, barButtonParams());

        stopButton = pillButton("Stopp", 0xFFE53935);
        stopButton.setOnClickListener(x -> stopPlayback());
        bar.addView(stopButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));

        // Feste Hoehe, damit die Knoepfe nicht springen, wenn der Balken erscheint
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));

        // Rand fuer die Sprachsteuerung liegt ueber allem
        FrameLayout outer = new FrameLayout(this);
        outer.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(Color.BLACK);
        dimOverlay.setAlpha(0f);
        dimOverlay.setVisibility(View.GONE);
        outer.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listeningFrame = new ListeningFrame(this);
        outer.addView(listeningFrame.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(outer);
        updateBarButtons();
    }

    private LinearLayout.LayoutParams barButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        lp.setMarginEnd(dp(16));
        return lp;
    }

    private void updateBarButtons() {
        editButton.setText(editMode ? "Fertig" : "Bearbeiten");
        backupButton.setVisibility(editMode ? View.VISIBLE : View.GONE);
        restoreButton.setVisibility(editMode ? View.VISIBLE : View.GONE);
        displayButton.setVisibility(editMode ? View.VISIBLE : View.GONE);
        soundButton.setVisibility(editMode ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(editMode ? View.GONE : View.VISIBLE);
    }

    private void saveTiles() {
        TileStore.save(this, tiles);
        knownRevision = TileStore.revision(this);
    }

    private void reloadTiles() {
        tiles.clear();
        tiles.addAll(TileStore.load(this));
        knownRevision = TileStore.revision(this);
        rebuildGrid();
    }

    private void toggleEditMode() {
        editMode = !editMode;
        updateBarButtons();
        rebuildGrid();
        if (editMode) {
            Toast.makeText(this, "Knopf antippen zum Bearbeiten, + für einen neuen", Toast.LENGTH_LONG).show();
        }
    }

    private TextView timeLabel() {
        TextView t = new TextView(this);
        t.setTextColor(0xFF616161);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setText("0:00");
        return t;
    }

    // ---------------------------------------------------------------- Raster und Seiten

    /**
     * Baut die aktuelle Seite neu: alle Knoepfe gleich gross, in Zeilen und Spalten
     * ausgerichtet. Passen nicht alle auf eine Seite, gibt es mehrere zum Wischen.
     */
    private void rebuildGrid() {
        int w = gridHolder.getWidth();
        int h = gridHolder.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        gridHolder.removeAllViews();
        tileViews.clear();

        int total = tiles.size() + (editMode ? 1 : 0);
        if (total == 0) {
            pageCount = 1;
            page = 0;
            TextView empty = new TextView(this);
            empty.setText("Keine Knöpfe – tippe unten auf „Bearbeiten“");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            empty.setTextColor(0xFF757575);
            gridHolder.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            refresh();
            return;
        }

        int margin = dp(12);
        int dotsHeight = dp(32);
        int perPage = Math.min(total, MAX_PER_PAGE);
        int[] fit = fit(perPage, w, h, margin);
        boolean paged = total > fit[0] * fit[1];
        if (paged) {
            fit = fit(perPage, w, h - dotsHeight, margin);
        }
        int cols = fit[0];
        int size = fit[2];
        pageCapacity = fit[0] * fit[1];
        pageCount = (total + pageCapacity - 1) / pageCapacity;
        paged = pageCount > 1;
        page = Math.max(0, Math.min(page, pageCount - 1));
        float textPx = size * textFactor();

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        int from = page * pageCapacity;
        int to = Math.min(total, from + pageCapacity);
        for (int i = from; i < to; i++) {
            if (i < tiles.size()) {
                Tile t = tiles.get(i);
                View v = makeTile(t, size, textPx);
                v.setOnClickListener(x -> {
                    if (editMode) {
                        openEditor(t, false);
                    } else {
                        onTile(t);
                    }
                });
                v.setOnLongClickListener(x -> {
                    openEditor(t, false);
                    return true;
                });
                grid.addView(v, cellParams(size, margin));
                tileViews.put(t.id, v);
            } else {
                FrameLayout add = makeCircle("+", 0xFFBDBDBD, size, size * 0.45f);
                add.setOnClickListener(x -> openEditor(Tile.create(), true));
                grid.addView(add, cellParams(size, margin));
            }
        }
        if (paged) {
            // Leere Plaetze auffuellen, damit jede Seite gleich ausgerichtet ist
            for (int i = to; i < from + pageCapacity; i++) {
                grid.addView(new View(this), cellParams(size, margin));
            }
        }
        FrameLayout.LayoutParams gridLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        if (paged) {
            gridLp.bottomMargin = dotsHeight;
        }
        gridHolder.addView(grid, gridLp);

        if (paged) {
            StringBuilder dots = new StringBuilder();
            for (int p = 0; p < pageCount; p++) {
                if (p > 0) {
                    dots.append("   ");
                }
                dots.append(p == page ? "●" : "○");
            }
            TextView indicator = new TextView(this);
            indicator.setText(dots);
            indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            indicator.setTextColor(0xFF9E9E9E);
            indicator.setGravity(Gravity.CENTER);
            gridHolder.addView(indicator, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dotsHeight, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        }
        refresh();
    }

    /** Spaltenzahl, bei der n Knoepfe am groessten werden: {Spalten, Zeilen, Groesse}. */
    private int[] fit(int n, int w, int h, int margin) {
        int bestCols = 1;
        int bestSize = 0;
        for (int c = 1; c <= n; c++) {
            int r = (n + c - 1) / c;
            int s = Math.min(w / c, h / r) - 2 * margin;
            if (s > bestSize) {
                bestSize = s;
                bestCols = c;
            }
        }
        int rows = (n + bestCols - 1) / bestCols;
        return new int[]{bestCols, rows, Math.max(dp(60), Math.min(bestSize, dp(260)))};
    }

    /** Baut neu auf und blaettert zu der Seite, auf der der Knopf mit diesem Index liegt. */
    private void showIndex(int index) {
        rebuildGrid();
        if (pageCapacity > 0) {
            int target = index / pageCapacity;
            if (target != page) {
                page = target;
                rebuildGrid();
            }
        }
    }

    private static GridLayout.LayoutParams cellParams(int size, int margin) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = size;
        lp.height = size;
        lp.setMargins(margin, margin, margin, margin);
        return lp;
    }

    /** Einheitliche Schriftgroesse fuer alle Knoepfe, passend zur laengsten Zeile. */
    private float textFactor() {
        int longest = 1;
        for (Tile t : tiles) {
            for (String line : t.label.split("\n")) {
                longest = Math.max(longest, line.trim().length());
            }
        }
        float f = 0.72f / (longest * 0.58f);
        return Math.max(0.08f, Math.min(0.22f, f));
    }

    /** Kreis mit Beschriftung; mit Logo, sobald es geladen ist. */
    private View makeTile(Tile t, int size, float textPx) {
        FrameLayout frame = makeCircle(t.label, t.color, size, textPx);
        if (!t.logo.isEmpty()) {
            TextView label = (TextView) frame.getChildAt(0);
            ImageView logo = new ImageView(this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int p = Math.round(size * 0.16f);
            logo.setPadding(p, p, p, p);
            logo.setVisibility(View.GONE);
            frame.addView(logo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LogoCache.load(this, t.logo, size, bitmap -> {
                if (bitmap != null) {
                    logo.setImageBitmap(bitmap);
                    logo.setVisibility(View.VISIBLE);
                    label.setVisibility(View.GONE);
                }
            });
        }
        return frame;
    }

    private FrameLayout makeCircle(String text, int color, int size, float textPx) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackground(tileBackground(color, false));
        frame.setClipToOutline(true);
        frame.setForeground(ripple());
        frame.setClickable(true);
        frame.setFocusable(true);

        TextView label = new TextView(this);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(textColorFor(color));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        label.setIncludeFontPadding(false);
        label.setMaxLines(2);
        label.setPadding(size / 10, 0, size / 10, 0);
        frame.addView(label, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private GradientDrawable tileBackground(int color, boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(active ? darker(color) : color);
        if (active) {
            d.setStroke(dp(8), 0xFFFFC107);
        } else if (editMode) {
            d.setStroke(dp(3), 0xFF9E9E9E, dp(10), dp(6));
        } else if (textColorFor(color) != Color.WHITE) {
            // helle Kreise auf weissem Grund: duenner Rand
            d.setStroke(dp(2), 0xFFE0E0E0);
        }
        return d;
    }

    private void refresh() {
        String current = PlayerService.currentId;
        for (Tile t : tiles) {
            View v = tileViews.get(t.id);
            if (v != null) {
                v.setBackground(tileBackground(t.color, t.id.equals(current)));
            }
        }
        String s = PlayerService.currentStatus;
        if (s == null || s.isEmpty()) {
            if (editMode) {
                String web = WebService.address();
                s = web == null ? "Bearbeiten" : "Auch im Browser: " + web;
            } else {
                s = "Bereit";
            }
        }
        if (pageCount > 1 && !editMode && PlayerService.currentId == null) {
            s = s + " · Seite " + (page + 1) + "/" + pageCount;
        }
        status.setText(s);
    }

    // ---------------------------------------------------------------- Podcast-Fortschritt

    /** Zeigt den Fortschrittsbalken, solange ein Podcast laeuft. */
    private void updateProgress() {
        long[] p = PlayerService.progress();
        if (p == null || p[1] <= 0) {
            progressRow.setVisibility(View.GONE);
            return;
        }
        progressRow.setVisibility(View.VISIBLE);
        durationLabel.setText(PlayerService.formatTime(p[1]));
        if (!dragging) {
            seekBar.setMax((int) p[1]);
            seekBar.setProgress((int) p[0]);
            positionLabel.setText(PlayerService.formatTime(p[0]));
        }
    }

    private void seekTo(int positionMs) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_SEEK);
        i.putExtra(PlayerService.EXTRA_POSITION, positionMs);
        startService(i);
    }

    // ---------------------------------------------------------------- Abspielen

    private void onTile(Tile t) {
        if (Tile.APP.equals(t.type)) {
            stopPlayback();
            launch(t);
            return;
        }
        if (t.id.equals(PlayerService.currentId)) {
            stopPlayback();
            return;
        }
        PlayerService.play(this, t);
    }

    private void stopPlayback() {
        PlayerService.stop(this);
    }

    /** Startet die erste vorhandene App bzw. Webseite aus der |-getrennten Liste. */
    private void launch(Tile t) {
        for (String raw : t.value.split("\\|")) {
            String target = raw.trim();
            if (target.isEmpty()) {
                continue;
            }
            Intent i;
            if (target.startsWith("http://") || target.startsWith("https://")) {
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
        Toast.makeText(this, t.displayName() + " ist nicht installiert", Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- Editor

    private void openEditor(Tile tile, boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        caption(box, "Beschriftung (Enter = neue Zeile, max. 2 Zeilen)");
        EditText labelEdit = new EditText(this);
        labelEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        labelEdit.setMaxLines(2);
        labelEdit.setText(tile.label);
        box.addView(labelEdit);

        caption(box, "Art");
        RadioGroup typeGroup = new RadioGroup(this);
        int[] radioIds = new int[TYPES.length];
        final int[] selected = {indexOfType(tile.type)};
        for (int i = 0; i < TYPES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(TYPE_NAMES[i]);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            radioIds[i] = View.generateViewId();
            rb.setId(radioIds[i]);
            typeGroup.addView(rb);
        }
        typeGroup.check(radioIds[selected[0]]);
        box.addView(typeGroup);

        caption(box, "Ziel");
        EditText valueEdit = new EditText(this);
        valueEdit.setSingleLine(true);
        valueEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        valueEdit.setText(tile.value);
        valueEdit.setHint(TYPE_HINTS[selected[0]]);
        box.addView(valueEdit);

        TextView pickApp = pillButton("App auswählen …", 0xFF757575);
        pickApp.setOnClickListener(x -> showAppPicker((pkg, name) -> {
            valueEdit.setText(pkg);
            if (labelEdit.getText().toString().trim().isEmpty()) {
                labelEdit.setText(name);
            }
        }));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        pickLp.topMargin = dp(8);
        box.addView(pickApp, pickLp);
        pickApp.setVisibility(Tile.APP.equals(TYPES[selected[0]]) ? View.VISIBLE : View.GONE);

        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            for (int i = 0; i < radioIds.length; i++) {
                if (radioIds[i] == checkedId) {
                    selected[0] = i;
                }
            }
            valueEdit.setHint(TYPE_HINTS[selected[0]]);
            pickApp.setVisibility(Tile.APP.equals(TYPES[selected[0]]) ? View.VISIBLE : View.GONE);
        });

        caption(box, "Farbe");
        final int[] chosenColor = {tile.color};
        EditText hexEdit = new EditText(this);
        hexEdit.setSingleLine(true);
        hexEdit.setText(Tile.toHex(tile.color));
        GridLayout swatches = new GridLayout(this);
        swatches.setColumnCount(7);
        List<View> swatchViews = new ArrayList<>();
        for (int color : PALETTE) {
            View sw = new View(this);
            sw.setTag(color);
            sw.setOnClickListener(x -> {
                chosenColor[0] = color;
                hexEdit.setText(Tile.toHex(color));
                markSwatches(swatchViews, color);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(44);
            lp.height = dp(44);
            lp.setMargins(dp(6), dp(6), dp(6), dp(6));
            swatches.addView(sw, lp);
            swatchViews.add(sw);
        }
        markSwatches(swatchViews, tile.color);
        box.addView(swatches);
        box.addView(hexEdit);

        caption(box, "Logo (leer = nur Beschriftung)");
        LinearLayout logoRow = new LinearLayout(this);
        logoRow.setOrientation(LinearLayout.HORIZONTAL);
        logoRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setShape(GradientDrawable.OVAL);
        previewBg.setColor(0xFFEEEEEE);
        preview.setBackground(previewBg);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        previewLp.setMarginEnd(dp(12));
        logoRow.addView(preview, previewLp);
        EditText logoEdit = new EditText(this);
        logoEdit.setSingleLine(true);
        logoEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        logoEdit.setHint("https://… Bildadresse (PNG/JPG)");
        logoEdit.setText(tile.logo);
        logoRow.addView(logoEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(logoRow);
        loadPreview(preview, tile.logo, false);

        LinearLayout logoButtons = new LinearLayout(this);
        logoButtons.setOrientation(LinearLayout.HORIZONTAL);
        TextView findLogo = pillButton("Logo suchen", 0xFF757575);
        findLogo.setOnClickListener(x -> {
            String query;
            String value = valueEdit.getText().toString().trim();
            if (Tile.SEARCH.equals(TYPES[selected[0]]) && !value.isEmpty() && !value.startsWith("http")) {
                query = value;
            } else {
                query = labelEdit.getText().toString().replace('\n', ' ').trim();
            }
            searchLogo(query, url -> {
                logoEdit.setText(url);
                loadPreview(preview, url, true);
            });
        });
        TextView clearLogo = pillButton("Entfernen", 0xFF757575);
        clearLogo.setOnClickListener(x -> {
            logoEdit.setText("");
            preview.setImageDrawable(null);
        });
        TextView testLogo = pillButton("Vorschau", 0xFF757575);
        testLogo.setOnClickListener(x -> loadPreview(preview, logoEdit.getText().toString(), true));
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lbLp.setMarginEnd(dp(12));
        lbLp.topMargin = dp(8);
        logoButtons.addView(findLogo, lbLp);
        LinearLayout.LayoutParams lbLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lbLp2.setMarginEnd(dp(12));
        lbLp2.topMargin = dp(8);
        logoButtons.addView(testLogo, lbLp2);
        LinearLayout.LayoutParams lbLp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lbLp3.topMargin = dp(8);
        logoButtons.addView(clearLogo, lbLp3);
        box.addView(logoButtons);

        if (!isNew) {
            caption(box, "Position");
            LinearLayout moveRow = new LinearLayout(this);
            moveRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView left = pillButton("◀ nach vorne", 0xFF757575);
            left.setOnClickListener(x -> move(tile, -1));
            TextView right = pillButton("nach hinten ▶", 0xFF757575);
            right.setOnClickListener(x -> move(tile, 1));
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            leftLp.setMarginEnd(dp(12));
            moveRow.addView(left, leftLp);
            moveRow.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
            box.addView(moveRow);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(isNew ? "Neuer Knopf" : "Knopf bearbeiten")
                .setView(scroll)
                .setPositiveButton("Speichern", null)
                .setNegativeButton("Abbrechen", null);
        if (!isNew) {
            builder.setNeutralButton("Löschen", null);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(x -> {
                String label = labelEdit.getText().toString().trim();
                String value = valueEdit.getText().toString().trim();
                if (label.isEmpty()) {
                    labelEdit.setError("Bitte eine Beschriftung eingeben");
                    return;
                }
                if (value.isEmpty()) {
                    valueEdit.setError("Bitte ein Ziel eingeben");
                    return;
                }
                tile.label = label;
                tile.value = value;
                tile.type = TYPES[selected[0]];
                tile.color = parseColor(hexEdit.getText().toString(), chosenColor[0]);
                tile.logo = logoEdit.getText().toString().trim();
                if (isNew) {
                    tiles.add(tile);
                }
                saveTiles();
                showIndex(tiles.indexOf(tile));
                dialog.dismiss();
            });
            if (!isNew) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
                        .setOnClickListener(x -> confirmDelete(tile, dialog));
            }
        });
        dialog.show();
    }

    private void caption(LinearLayout box, String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        c.setTextColor(0xFF757575);
        c.setPadding(0, dp(16), 0, dp(4));
        box.addView(c);
    }

    private void markSwatches(List<View> swatchViews, int selectedColor) {
        for (View sw : swatchViews) {
            int c = (Integer) sw.getTag();
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(c);
            if ((c & 0xFFFFFF) == (selectedColor & 0xFFFFFF)) {
                d.setStroke(dp(4), 0xFFFFC107);
            } else {
                d.setStroke(dp(1), 0xFFBDBDBD);
            }
            sw.setBackground(d);
        }
    }

    private void loadPreview(ImageView preview, String url, boolean complain) {
        String address = url == null ? "" : url.trim();
        if (address.isEmpty()) {
            preview.setImageDrawable(null);
            return;
        }
        LogoCache.load(this, address, dp(72), bitmap -> {
            preview.setImageBitmap(bitmap);
            if (bitmap == null && complain) {
                Toast.makeText(this, "Logo konnte nicht geladen werden (SVG wird nicht unterstützt)",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Sucht Senderlogos bei radio-browser.info und laesst eines auswaehlen. */
    private void searchLogo(String query, Consumer<String> onPick) {
        if (query.isEmpty()) {
            Toast.makeText(this, "Erst eine Beschriftung oder einen Sendernamen eingeben", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Suche Logos für „" + query + "“ …", Toast.LENGTH_SHORT).show();
        background.execute(() -> {
            List<RadioBrowser.Station> found;
            try {
                found = RadioBrowser.searchFlexible(query, 40);
            } catch (IOException e) {
                runOnUiThread(() -> Toast.makeText(this, "Suche fehlgeschlagen: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                return;
            }
            List<RadioBrowser.Station> withLogo = new ArrayList<>();
            for (RadioBrowser.Station s : found) {
                if (!s.favicon.isEmpty()) {
                    withLogo.add(s);
                }
            }
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                if (withLogo.isEmpty()) {
                    Toast.makeText(this, found.isEmpty()
                                    ? "Kein Sender gefunden – anderen Namen probieren"
                                    : "Sender gefunden, aber ohne Logo im Verzeichnis – Bildadresse von Hand eintragen",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String[] names = new String[withLogo.size()];
                for (int i = 0; i < names.length; i++) {
                    RadioBrowser.Station s = withLogo.get(i);
                    names[i] = s.country.isEmpty() ? s.name : s.name + " (" + s.country + ")";
                }
                new AlertDialog.Builder(this)
                        .setTitle("Logo auswählen")
                        .setItems(names, (d, which) -> onPick.accept(withLogo.get(which).favicon))
                        .setNegativeButton("Abbrechen", null)
                        .show();
            });
        });
    }

    private void move(Tile tile, int delta) {
        int from = tiles.indexOf(tile);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= tiles.size()) {
            return;
        }
        Collections.swap(tiles, from, to);
        saveTiles();
        showIndex(to);
        Toast.makeText(this, "Position " + (to + 1) + " von " + tiles.size(), Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(Tile tile, AlertDialog editor) {
        new AlertDialog.Builder(this)
                .setTitle("„" + tile.displayName() + "“ löschen?")
                .setPositiveButton("Löschen", (d, which) -> {
                    if (tile.id.equals(PlayerService.currentId)) {
                        stopPlayback();
                    }
                    tiles.remove(tile);
                    saveTiles();
                    rebuildGrid();
                    editor.dismiss();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    /** Liste aller installierten Apps mit Symbol im App-Menue. */
    private void showAppPicker(BiConsumer<String, String> onPick) {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(launcher, 0);
        List<String[]> apps = new ArrayList<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) {
                continue;
            }
            apps.add(new String[]{ri.loadLabel(pm).toString(), pkg});
        }
        apps.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i)[0];
        }
        new AlertDialog.Builder(this)
                .setTitle("App auswählen")
                .setItems(names, (d, which) -> onPick.accept(apps.get(which)[1], apps.get(which)[0]))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------------------------------------------------------------- Display-Einstellungen

    static final int[] DIM_CHOICES = {0, 15, 30, 60, 120, 300, 600, 1800};
    static final int[] RETURN_CHOICES = {0, 1, 2, 5, 10, 30, 60};

    private static String dimName(int seconds) {
        if (seconds == 0) return "nie";
        if (seconds < 60) return seconds + " Sekunden";
        return seconds / 60 + (seconds == 60 ? " Minute" : " Minuten");
    }

    private static String returnName(int minutes) {
        if (minutes == 0) return "nie";
        return minutes + (minutes == 1 ? " Minute" : " Minuten");
    }

    private static int nearest(int[] choices, int value) {
        int best = 0;
        for (int i = 1; i < choices.length; i++) {
            if (Math.abs(choices[i] - value) < Math.abs(choices[best] - value)) best = i;
        }
        return best;
    }

    private void showDisplaySettings() {
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        int[] sel = {
                nearest(DIM_CHOICES, settings.dimSeconds),
                settings.dimLevel,
                nearest(RETURN_CHOICES, settings.returnMinutes)};

        TextView dimLabel = new TextView(this);
        SeekBar dimBar = new SeekBar(this);
        dimBar.setMax(DIM_CHOICES.length - 1);
        dimBar.setProgress(sel[0]);
        TextView levelLabel = new TextView(this);
        SeekBar levelBar = new SeekBar(this);
        levelBar.setMax(30);
        levelBar.setProgress(Math.min(30, sel[1]));
        TextView returnLabel = new TextView(this);
        SeekBar returnBar = new SeekBar(this);
        returnBar.setMax(RETURN_CHOICES.length - 1);
        returnBar.setProgress(sel[2]);

        Runnable labels = () -> {
            dimLabel.setText("Abdunkeln nach: " + dimName(DIM_CHOICES[dimBar.getProgress()]));
            levelLabel.setText("Helligkeit abgedunkelt: "
                    + (levelBar.getProgress() == 0 ? "schwarz" : levelBar.getProgress() + " %"));
            returnLabel.setText("Aus anderen Apps zurück nach: " + returnName(RETURN_CHOICES[returnBar.getProgress()]));
        };
        labels.run();
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                labels.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        };
        dimBar.setOnSeekBarChangeListener(l);
        levelBar.setOnSeekBarChangeListener(l);
        returnBar.setOnSeekBarChangeListener(l);

        for (View v : new View[]{dimLabel, dimBar, levelLabel, levelBar, returnLabel, returnBar}) {
            if (v instanceof TextView) {
                ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                ((TextView) v).setPadding(0, dp(12), 0, dp(4));
            }
            box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        new AlertDialog.Builder(this)
                .setTitle("Display")
                .setView(box)
                .setPositiveButton("Speichern", (d, which) -> settings = AppSettings.save(this,
                        DIM_CHOICES[dimBar.getProgress()], levelBar.getProgress(),
                        RETURN_CHOICES[returnBar.getProgress()]))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------------------------------------------------------------- Equalizer

    private static String freqName(int milliHz) {
        int hz = milliHz / 1000;
        if (hz >= 1000) {
            float k = hz / 1000f;
            return (k == Math.round(k) ? String.valueOf(Math.round(k)) : String.format(java.util.Locale.GERMANY, "%.1f", k)) + " kHz";
        }
        return hz + " Hz";
    }

    private static String dbName(int millibel) {
        int db = Math.round(millibel / 100f);
        return (db > 0 ? "+" : "") + db + " dB";
    }

    private void showEqualizer() {
        Equalizer eq = Eq.get(this);
        if (eq == null) {
            Toast.makeText(this, "Dieses Gerät bietet keinen Equalizer an", Toast.LENGTH_LONG).show();
            return;
        }
        final short bands;
        final short min;
        final short max;
        final int[] centers;
        final String[] presets;
        try {
            bands = eq.getNumberOfBands();
            short[] range = eq.getBandLevelRange();
            min = range[0];
            max = range[1];
            centers = new int[bands];
            for (short b = 0; b < bands; b++) {
                centers[b] = eq.getCenterFreq(b);
            }
            presets = new String[eq.getNumberOfPresets()];
            for (short i = 0; i < presets.length; i++) {
                presets[i] = eq.getPresetName(i);
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, "Equalizer nicht verfügbar: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }

        final boolean oldEnabled = Eq.isEnabled(this);
        final int[] oldLevels = Eq.savedLevels(this, bands);
        final int[] levels = oldLevels.clone();
        final String[] preset = {Eq.presetName(this)};
        // Schieberegler in 1-dB-Schritten
        final int step = 100;

        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        CheckBox enabled = new CheckBox(this);
        enabled.setText("Equalizer an");
        enabled.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        enabled.setChecked(oldEnabled);
        box.addView(enabled);

        TextView presetLabel = new TextView(this);
        presetLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        presetLabel.setTextColor(0xFF6B7785);
        box.addView(presetLabel);

        SeekBar[] bars = new SeekBar[bands];
        TextView[] values = new TextView[bands];
        Runnable updatePreset = () -> presetLabel.setText(preset[0].isEmpty()
                ? "Eigene Einstellung" : "Voreinstellung: " + preset[0]);
        updatePreset.run();

        for (short b = 0; b < bands; b++) {
            final short band = b;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText(freqName(centers[b]));
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            row.addView(name, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));

            SeekBar sb = new SeekBar(this);
            sb.setMax((max - min) / step);
            sb.setProgress((levels[b] - min) / step);
            bars[b] = sb;
            row.addView(sb, new LinearLayout.LayoutParams(0, dp(48), 1f));

            TextView value = new TextView(this);
            value.setText(dbName(levels[b]));
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            value.setGravity(Gravity.END);
            values[b] = value;
            row.addView(value, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    levels[band] = min + p * step;
                    value.setText(dbName(levels[band]));
                    if (fromUser) {
                        preset[0] = "";
                        updatePreset.run();
                        if (!enabled.isChecked()) {
                            enabled.setChecked(true);
                        }
                        Eq.preview(enabled.isChecked(), levels);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        enabled.setOnCheckedChangeListener((v, on) -> Eq.preview(on, levels));

        Consumer<int[]> setAll = (newLevels) -> {
            for (int b = 0; b < bands; b++) {
                levels[b] = Math.max(min, Math.min(max, newLevels[b]));
                bars[b].setProgress((levels[b] - min) / step);
                values[b].setText(dbName(levels[b]));
            }
            enabled.setChecked(true);
            Eq.preview(true, levels);
        };

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, dp(8));
        TextView presetButton = pillButton("Voreinstellung …", 0xFF757575);
        presetButton.setOnClickListener(x -> new AlertDialog.Builder(this)
                .setTitle("Voreinstellung")
                .setItems(presets, (d, which) -> {
                    try {
                        eq.usePreset((short) which);
                        int[] l = new int[bands];
                        for (short b = 0; b < bands; b++) {
                            l[b] = eq.getBandLevel(b);
                        }
                        preset[0] = presets[which];
                        updatePreset.run();
                        setAll.accept(l);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Voreinstellung nicht verfügbar", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show());
        presetButton.setEnabled(presets.length > 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.setMarginEnd(dp(12));
        buttons.addView(presetButton, lp);
        TextView reset = pillButton("Alles auf 0", 0xFF757575);
        reset.setOnClickListener(x -> {
            preset[0] = "";
            updatePreset.run();
            setAll.accept(new int[bands]);
        });
        buttons.addView(reset, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
        box.addView(buttons);

        TextView hint = new TextView(this);
        hint.setText(PlayerService.currentId == null
                ? "Tipp: Starte vorher einen Sender, dann hörst du die Änderungen sofort."
                : "Änderungen sind sofort zu hören.");
        hint.setTextColor(0xFF6B7785);
        box.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Klang")
                .setView(scroll)
                .setPositiveButton("Speichern", (d, which) -> {
                    Eq.save(this, enabled.isChecked(), levels, preset[0]);
                    Eq.applySaved(this);
                })
                .setNegativeButton("Abbrechen", (d, which) -> Eq.preview(oldEnabled, oldLevels))
                .create();
        dialog.setOnCancelListener(d -> Eq.preview(oldEnabled, oldLevels));
        dialog.show();
    }

    // ---------------------------------------------------------------- Sichern & Wiederherstellen

    private void exportTiles() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "echo-radio-knoepfe.json");
        try {
            startActivityForResult(i, REQUEST_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Kein Dateidialog verfügbar", Toast.LENGTH_LONG).show();
        }
    }

    private void importTiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Kein Dateidialog verfügbar", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT) {
            writeBackup(uri);
        } else if (requestCode == REQUEST_IMPORT) {
            readBackup(uri);
        }
    }

    private void writeBackup(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new IOException("Datei lässt sich nicht öffnen");
            }
            out.write(TileStore.exportJson(tiles).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, tiles.size() + " Knöpfe gesichert", Toast.LENGTH_LONG).show();
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Sichern fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        List<Tile> imported;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Datei lässt sich nicht öffnen");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > 2 * 1024 * 1024) {
                    throw new IOException("Datei zu groß");
                }
            }
            imported = TileStore.parseImport(new String(buf.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            Toast.makeText(this, "Datei konnte nicht gelesen werden: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(imported.size() + " Knöpfe laden?")
                .setMessage("Die aktuellen " + tiles.size() + " Knöpfe werden dabei ersetzt.")
                .setPositiveButton("Laden", (d, which) -> {
                    stopPlayback();
                    tiles.clear();
                    tiles.addAll(imported);
                    saveTiles();
                    page = 0;
                    rebuildGrid();
                    Toast.makeText(this, imported.size() + " Knöpfe geladen", Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    // ---------------------------------------------------------------- Hilfen

    private static int indexOfType(String type) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equals(type)) {
                return i;
            }
        }
        return 0;
    }

    private static int parseColor(String text, int fallback) {
        String s = text.trim();
        if (!s.startsWith("#")) {
            s = "#" + s;
        }
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int textColorFor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.7 ? 0xFF212121 : Color.WHITE;
    }

    private static int darker(int color) {
        return Color.rgb((int) (Color.red(color) * 0.75),
                (int) (Color.green(color) * 0.75),
                (int) (Color.blue(color) * 0.75));
    }

    private TextView pillButton(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(24), 0, dp(24), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(28));
        bg.setColor(color);
        b.setBackground(bg);
        b.setForeground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, null));
        b.setClickable(true);
        return b;
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
}
