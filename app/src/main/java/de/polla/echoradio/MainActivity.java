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
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Startbildschirm mit frei konfigurierbaren runden Knoepfen.
 * Normalbetrieb: Tippen spielt ab bzw. startet die App, nochmal tippen stoppt.
 * Bearbeiten-Modus: Tippen oeffnet den Editor, "+" fuegt einen Knopf hinzu.
 * Langer Druck oeffnet den Editor jederzeit.
 */
public class MainActivity extends Activity {

    private static final int[] PALETTE = {
            0xFF00A0E3, 0xFF1565C0, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835,
            0xFFFB8C00, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF6D4C41, 0xFF546E7A,
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

    private List<Tile> tiles;
    private boolean editMode;

    private FrameLayout gridHolder;
    private TextView status;
    private TextView editButton;
    private final Map<String, TextView> tileViews = new HashMap<>();

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tiles = TileStore.load(this);
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

        gridHolder = new FrameLayout(this);
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

        status = new TextView(this);
        status.setTextColor(0xFF444444);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        status.setSingleLine(true);
        status.setPadding(dp(16), 0, dp(16), 0);
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        editButton = pillButton("Bearbeiten", 0xFF757575);
        editButton.setOnClickListener(x -> toggleEditMode());
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56));
        editLp.setMarginEnd(dp(16));
        bar.addView(editButton, editLp);

        TextView stop = pillButton("Stopp", 0xFFE53935);
        stop.setOnClickListener(x -> stopPlayback());
        bar.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)));

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        setContentView(root);
    }

    private void toggleEditMode() {
        editMode = !editMode;
        editButton.setText(editMode ? "Fertig" : "Bearbeiten");
        rebuildGrid();
        if (editMode) {
            Toast.makeText(this, "Knopf antippen zum Bearbeiten, + für einen neuen", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- Raster

    /** Baut das Raster neu: alle Knoepfe gleich gross, in Zeilen und Spalten ausgerichtet. */
    private void rebuildGrid() {
        int w = gridHolder.getWidth();
        int h = gridHolder.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        gridHolder.removeAllViews();
        tileViews.clear();

        int count = tiles.size() + (editMode ? 1 : 0);
        if (count == 0) {
            TextView empty = new TextView(this);
            empty.setText("Keine Knöpfe – tippe unten auf „Bearbeiten“");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            empty.setTextColor(0xFF757575);
            gridHolder.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            refresh();
            return;
        }

        // Spaltenzahl waehlen, bei der die Knoepfe am groessten werden
        int margin = dp(12);
        int cols = 1;
        int size = 0;
        for (int c = 1; c <= count; c++) {
            int r = (count + c - 1) / c;
            int s = Math.min(w / c, h / r) - 2 * margin;
            if (s > size) {
                size = s;
                cols = c;
            }
        }
        size = Math.min(size, dp(260));
        float textPx = size * textFactor();

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(cols);
        for (Tile t : tiles) {
            TextView v = makeTile(t.label, t.color, size, textPx);
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
        }
        if (editMode) {
            TextView add = makeTile("+", 0xFFBDBDBD, size, size * 0.45f);
            add.setOnClickListener(x -> openEditor(Tile.create(), true));
            grid.addView(add, cellParams(size, margin));
        }
        gridHolder.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        refresh();
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

    private TextView makeTile(String label, int color, int size, float textPx) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(textColorFor(color));
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        v.setIncludeFontPadding(false);
        v.setMaxLines(2);
        v.setPadding(size / 10, 0, size / 10, 0);
        v.setBackground(tileBackground(color, false));
        v.setForeground(ripple());
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private GradientDrawable tileBackground(int color, boolean active) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(active ? darker(color) : color);
        if (active) {
            d.setStroke(dp(8), 0xFFFFC107);
        } else if (editMode) {
            d.setStroke(dp(3), 0xFF9E9E9E, dp(10), dp(6));
        }
        return d;
    }

    private void refresh() {
        String current = PlayerService.currentId;
        for (Tile t : tiles) {
            TextView v = tileViews.get(t.id);
            if (v != null) {
                v.setBackground(tileBackground(t.color, t.id.equals(current)));
            }
        }
        String s = PlayerService.currentStatus;
        status.setText(s == null || s.isEmpty() ? (editMode ? "Bearbeiten" : "Bereit") : s);
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
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(PlayerService.ACTION_PLAY);
        i.putExtra(PlayerService.EXTRA_ID, t.id);
        i.putExtra(PlayerService.EXTRA_NAME, t.displayName());
        i.putExtra(PlayerService.EXTRA_MODE, t.type);
        i.putExtra(PlayerService.EXTRA_VALUE, t.value);
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
        swatches.setColumnCount(6);
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
                if (isNew) {
                    tiles.add(tile);
                }
                TileStore.save(this, tiles);
                rebuildGrid();
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
                d.setStroke(dp(4), 0xFF212121);
            }
            sw.setBackground(d);
        }
    }

    private void move(Tile tile, int delta) {
        int from = tiles.indexOf(tile);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= tiles.size()) {
            return;
        }
        Collections.swap(tiles, from, to);
        TileStore.save(this, tiles);
        rebuildGrid();
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
                    TileStore.save(this, tiles);
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
        b.setPadding(dp(28), 0, dp(28), 0);
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
