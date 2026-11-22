package dev.parallaxlive;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * On-device manager & settings activity for Parallax Live.
 * Handles .wpbundle / .zip import, Home/Lock scene assignment, and engine toggles.
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK_BUNDLE = 1001;
    private static final String PREFS_NAME = "live_prefs";
    private static final String PREF_HOME_SCENE = "home_scene";
    private static final String PREF_LOCK_SCENE = "lock_scene";
    private static final String PREF_RELIEF = "relief_enabled";
    private static final String PREF_MOTES = "motes_enabled";
    private static final String PREF_SCENES_REV = "scenes_rev";
    private static final String TAG = "ParallaxLive";
    private static final String IMPORT_TMP_PREFIX = ".import-";
    private static final long MAX_IMPORT_BYTES = 256L * 1024 * 1024;
    private static final List<String> BUNDLE_FILES = Arrays.asList(
            "bg.jpg", "bg.png", "fg.webp", "fg.png", "relief.png", "sway.png", "swing.png", "thumb.png", "meta.json");

    private SharedPreferences prefs;
    private LinearLayout scenesContainer;
    private Button btnSetService;
    private TextView serviceHint;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Window window = getWindow();
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.parseColor("#121214"));

        initUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatus();
        refreshScenesList();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            importBundleFromUri(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                importBundleFromUri(uri);
            }
        }
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private GradientDrawable makeCardBg(int bgColor, int strokeColor, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) gd.setStroke(dp(1), strokeColor);
        return gd;
    }

    private GradientDrawable makePill(int color) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(100));
        return gd;
    }

    private void initUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#101012"));
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(64), dp(18), dp(36));
        scrollView.addView(root);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                int topPad = Math.max(top + dp(14), dp(64));
                int bottomPad = Math.max(bottom + dp(20), dp(36));
                v.setPadding(dp(18), topPad, dp(18), bottomPad);
                return insets.consumeSystemWindowInsets();
            }
        });

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(0, 0, 0, dp(6));

        ImageView brandIcon = new ImageView(this);
        try {
            int iconRes = getResources().getIdentifier("ic_launcher", "drawable", getPackageName());
            if (iconRes != 0) brandIcon.setImageResource(iconRes);
        } catch (Exception ignored) {}
        brandIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        brandIcon.setClipToOutline(true);
        LinearLayout.LayoutParams pIcon = new LinearLayout.LayoutParams(dp(32), dp(32));
        pIcon.setMargins(0, 0, dp(10), 0);
        brandIcon.setLayoutParams(pIcon);
        brandRow.addView(brandIcon);

        TextView brandTitle = new TextView(this);
        brandTitle.setText("Parallax Live");
        brandTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        brandTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        brandTitle.setTextColor(Color.WHITE);
        brandRow.addView(brandTitle);

        TextView liveBadge = new TextView(this);
        liveBadge.setText("3D ENGINE");
        liveBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        liveBadge.setTypeface(Typeface.DEFAULT_BOLD);
        liveBadge.setTextColor(Color.parseColor("#38bdf8"));
        liveBadge.setBackground(makeCardBg(Color.parseColor("#0c4a6e"), Color.parseColor("#0284c7"), 6));
        liveBadge.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams pBadge = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pBadge.setMargins(dp(8), 0, 0, 0);
        liveBadge.setLayoutParams(pBadge);
        brandRow.addView(liveBadge);

        root.addView(brandRow);

        TextView subtitle = new TextView(this);
        subtitle.setText("Multi-layer parallax & 3D depth relief live wallpaper");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(Color.parseColor("#9ca3af"));
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(0, 0, 0, dp(8));

        Button btnImport = new Button(this);
        btnImport.setText("+ Import Bundle");
        btnImport.setTextColor(Color.WHITE);
        btnImport.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnImport.setTypeface(Typeface.DEFAULT_BOLD);
        btnImport.setBackground(makeCardBg(Color.parseColor("#4f46e5"), Color.parseColor("#6366f1"), 12));
        LinearLayout.LayoutParams pImport = new LinearLayout.LayoutParams(0, dp(46), 1f);
        pImport.setMargins(0, 0, dp(10), 0);
        btnImport.setLayoutParams(pImport);
        btnImport.setOnClickListener(v -> launchFilePicker());
        actionRow.addView(btnImport);

        btnSetService = new Button(this);
        btnSetService.setText("Activate Engine");
        btnSetService.setTextColor(Color.parseColor("#f4f4f5"));
        btnSetService.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnSetService.setTypeface(Typeface.DEFAULT_BOLD);
        btnSetService.setBackground(makeCardBg(Color.parseColor("#27272a"), Color.parseColor("#3f3f46"), 12));
        LinearLayout.LayoutParams pSet = new LinearLayout.LayoutParams(0, dp(46), 1f);
        btnSetService.setLayoutParams(pSet);
        btnSetService.setOnClickListener(v -> launchWallpaperChooser());
        actionRow.addView(btnSetService);

        root.addView(actionRow);

        serviceHint = new TextView(this);
        serviceHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        serviceHint.setTextColor(Color.parseColor("#71717a"));
        serviceHint.setPadding(dp(2), 0, dp(2), dp(14));
        root.addView(serviceHint);
        updateServiceStatus();

        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        settingsCard.setBackground(makeCardBg(Color.parseColor("#18181b"), Color.parseColor("#27272a"), 16));
        LinearLayout.LayoutParams pCard = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pCard.setMargins(0, 0, 0, dp(22));
        settingsCard.setLayoutParams(pCard);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("ENGINE CONTROLS");
        settingsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        settingsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        settingsTitle.setTextColor(Color.parseColor("#71717a"));
        settingsTitle.setPadding(0, 0, 0, dp(10));
        settingsCard.addView(settingsTitle);

        LinearLayout rowRelief = createSwitchRow(
                "3D Depth Relief Mesh",
                "Per-vertex depth displacement from relief.png",
                prefs.getBoolean(PREF_RELIEF, true),
                (buttonView, isChecked) -> prefs.edit().putBoolean(PREF_RELIEF, isChecked).apply()
        );
        settingsCard.addView(rowRelief);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#27272a"));
        LinearLayout.LayoutParams pDiv = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        pDiv.setMargins(0, dp(10), 0, dp(10));
        divider.setLayoutParams(pDiv);
        settingsCard.addView(divider);

        LinearLayout rowMotes = createSwitchRow(
                "Ambient Floating Motes",
                "Depth-layered light particles in front of & behind subject",
                prefs.getBoolean(PREF_MOTES, true),
                (buttonView, isChecked) -> prefs.edit().putBoolean(PREF_MOTES, isChecked).apply()
        );
        settingsCard.addView(rowMotes);

        root.addView(settingsCard);

        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("WALLPAPER LIBRARY");
        sectionTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(Color.parseColor("#71717a"));
        sectionTitle.setPadding(0, 0, 0, dp(10));
        root.addView(sectionTitle);

        scenesContainer = new LinearLayout(this);
        scenesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(scenesContainer);

        setContentView(scrollView);
    }

    private LinearLayout createSwitchRow(String title, String desc, boolean initial, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView txtTitle = new TextView(this);
        txtTitle.setText(title);
        txtTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        txtTitle.setTypeface(Typeface.DEFAULT_BOLD);
        txtTitle.setTextColor(Color.WHITE);
        textCol.addView(txtTitle);

        TextView txtDesc = new TextView(this);
        txtDesc.setText(desc);
        txtDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtDesc.setTextColor(Color.parseColor("#a1a1aa"));
        textCol.addView(txtDesc);

        row.addView(textCol);

        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw);

        return row;
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_PICK_BUNDLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_BUNDLE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importBundleFromUri(data.getData());
        }
    }

    private boolean isEngineActive() {
        try {
            android.app.WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
            return info != null
                    && getPackageName().equals(info.getPackageName())
                    && LiveService.class.getName().equals(info.getServiceName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateServiceStatus() {
        if (btnSetService == null || serviceHint == null) return;
        if (isEngineActive()) {
            btnSetService.setText("✓ Engine Active");
            btnSetService.setTextColor(Color.parseColor("#a7f3d0"));
            btnSetService.setBackground(makeCardBg(Color.parseColor("#064e3b"), Color.parseColor("#059669"), 12));
            serviceHint.setText("Engine is active — tap Home, Lock, or Both below to switch scenes anytime.");
        } else {
            btnSetService.setText("Activate Engine");
            btnSetService.setTextColor(Color.parseColor("#f4f4f5"));
            btnSetService.setBackground(makeCardBg(Color.parseColor("#27272a"), Color.parseColor("#3f3f46"), 12));
            serviceHint.setText("Tap Activate Engine once and choose Home & Lock — then assign scenes below.");
        }
    }

    private void launchWallpaperChooser() {
        try {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, LiveService.class));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
                startActivity(fallback);
            } catch (Exception e2) {
                Toast.makeText(this, "Open Settings -> Wallpaper to activate.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            } catch (Exception ignored) {}
        }
        if (result == null) {
            String path = uri.getPath();
            if (path != null) {
                int cut = path.lastIndexOf('/');
                if (cut != -1) result = path.substring(cut + 1);
            }
        }
        return result;
    }

    private static String sanitizeStem(String raw) {
        if (raw == null) return "scene-" + System.currentTimeMillis();
        int dot = raw.lastIndexOf('.');
        String stem = (dot > 0) ? raw.substring(0, dot) : raw;
        stem = stem.replaceAll("[^a-zA-Z0-9._-]", "_");
        while (stem.startsWith(".")) stem = stem.substring(1);
        if (stem.isEmpty() || "default".equalsIgnoreCase(stem)) {
            stem = "scene-" + System.currentTimeMillis();
        }
        return stem;
    }

    private void importBundleFromUri(Uri uri) {
        Toast.makeText(this, "Unpacking live wallpaper...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String stem = sanitizeStem(getFileName(uri));
            File scenesDir = getExternalFilesDir("scenes");
            final String error = scenesDir == null ? "Storage is not available" : unpackBundle(uri, scenesDir, stem);
            handler.post(() -> {
                if (error == null) {
                    Toast.makeText(this, "Imported: " + stem, Toast.LENGTH_LONG).show();
                    // Wakes a running wallpaper engine so it reloads a scene that was replaced in place.
                    prefs.edit().putLong(PREF_SCENES_REV, System.currentTimeMillis()).apply();
                    refreshScenesList();
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * Unpacks the bundle into a temporary folder, validates it, and only then swaps it in for any scene
     * with the same name, so a bad import never damages an existing scene or leaves stale files behind.
     *
     * @return null on success, otherwise a message for the user
     */
    private String unpackBundle(Uri uri, File scenesDir, String stem) {
        scenesDir.mkdirs();
        File[] leftovers = scenesDir.listFiles((dir, name) -> name.startsWith(IMPORT_TMP_PREFIX));
        if (leftovers != null) {
            for (File f : leftovers) deleteRecursive(f);
        }
        File tmp = new File(scenesDir, IMPORT_TMP_PREFIX + System.nanoTime());
        if (!tmp.mkdirs()) return "Cannot create a folder for the import";

        boolean installed = false;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return "Cannot open the selected file";
            try (ZipInputStream zis = new ZipInputStream(in)) {
                byte[] buf = new byte[8192];
                long total = 0;
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten the path and keep only the files a bundle may contain.
                    String name = new File(entry.getName()).getName().toLowerCase(Locale.ROOT);
                    if (!BUNDLE_FILES.contains(name)) continue;
                    try (FileOutputStream fos = new FileOutputStream(new File(tmp, name))) {
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            total += len;
                            if (total > MAX_IMPORT_BYTES) throw new IOException("bundle is too large");
                            fos.write(buf, 0, len);
                        }
                    }
                }
            }
            if (!LiveService.hasDecodableAssets(tmp)) {
                return "Invalid bundle (needs a readable bg.jpg/png and fg.webp/png)";
            }
            File target = new File(scenesDir, stem);
            deleteRecursive(target);
            if (!tmp.renameTo(target)) return "Cannot save the scene";
            installed = true;
            return null;
        } catch (Exception e) {
            Log.w(TAG, "import failed", e);
            return "Cannot read the bundle: " + e.getMessage();
        } finally {
            if (!installed) deleteRecursive(tmp);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }

    private static class SceneItem {
        final String id;
        final String title;
        final String details;
        final File dir;
        final boolean isDefault;
        final long modified;

        SceneItem(String id, String title, String details, File dir, boolean isDefault, long modified) {
            this.id = id;
            this.title = title;
            this.details = details;
            this.dir = dir;
            this.isDefault = isDefault;
            this.modified = modified;
        }
    }

    private void refreshScenesList() {
        scenesContainer.removeAllViews();

        List<SceneItem> items = new ArrayList<>();
        items.add(new SceneItem("default", "Built-in Sample", "Bundled demo scene", null, true, Long.MAX_VALUE));

        File scenesDir = getExternalFilesDir("scenes");
        if (scenesDir != null && scenesDir.isDirectory()) {
            File[] files = scenesDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isDirectory() || f.getName().startsWith(".")) continue;
                    if (!LiveService.hasRequiredAssets(f)) continue;

                    String title = f.getName();
                    String details = "";
                    File metaFile = new File(f, "meta.json");
                    if (metaFile.exists()) {
                        try (FileInputStream fis = new FileInputStream(metaFile)) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            byte[] buf = new byte[2048];
                            int n;
                            while ((n = fis.read(buf)) > 0) baos.write(buf, 0, n);
                            JSONObject obj = new JSONObject(baos.toString("UTF-8"));
                            String charName = obj.optString("character", "");
                            String style = obj.optString("style", "");
                            boolean hasRelief = obj.optBoolean("relief", new File(f, "relief.png").exists());
                            details = (charName.isEmpty() ? "" : charName + " · ") +
                                    (style.isEmpty() ? "" : style + " · ") +
                                    (hasRelief ? "3D Relief" : "2D Layers");
                        } catch (Exception ignored) {}
                    }
                    if (details.isEmpty()) {
                        boolean hasRelief = new File(f, "relief.png").exists();
                        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(f.lastModified()));
                        details = (hasRelief ? "3D Relief · " : "2D Layers · ") + dateStr;
                    }
                    items.add(new SceneItem(f.getName(), title, details, f, false, f.lastModified()));
                }
            }
        }

        Collections.sort(items, (a, b) -> {
            if (a.isDefault) return -1;
            if (b.isDefault) return 1;
            return Long.compare(b.modified, a.modified);
        });

        String activeHome = prefs.getString(PREF_HOME_SCENE, "default");
        // Mirrors the engine: with no explicit lock scene the lock screen follows the home scene.
        String activeLock = prefs.getString(PREF_LOCK_SCENE, activeHome);

        for (SceneItem item : items) {
            scenesContainer.addView(createSceneCard(item, activeHome, activeLock));
        }

        if (items.size() == 1) {
            scenesContainer.addView(createTipCard());
        }
    }

    private View createTipCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(makeCardBg(Color.parseColor("#141416"), Color.parseColor("#27272a"), 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(16));
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText("Import Your Own Scenes");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, dp(6));
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("1. Pack bg.jpg (or bg.png), fg.webp (or fg.png), and optional relief.png into a .wpbundle or .zip.\n2. Transfer it to your phone and tap '+ Import Bundle' above, or open it with Parallax Live.\n3. Assign separate scenes to Home and Lock screens.");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setTextColor(Color.parseColor("#a1a1aa"));
        desc.setLineSpacing(dp(3), 1f);
        card.addView(desc);

        return card;
    }

    private View createSceneCard(SceneItem item, String activeHome, String activeLock) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(makeCardBg(Color.parseColor("#18181b"), Color.parseColor("#27272a"), 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(14));
        card.setLayoutParams(lp);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        thumb.setClipToOutline(true);
        LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(62), dp(96));
        thumbParams.setMargins(0, 0, dp(14), 0);
        thumb.setLayoutParams(thumbParams);
        thumb.setBackground(makeCardBg(Color.parseColor("#27272a"), 0, 10));

        if (item.isDefault) {
            int thumbRes = getResources().getIdentifier("thumb", "drawable", getPackageName());
            if (thumbRes != 0) {
                thumb.setImageResource(thumbRes);
            } else {
                try (InputStream in = getAssets().open("home/bg.jpg")) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = 4;
                    Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                    if (bmp != null) thumb.setImageBitmap(bmp);
                } catch (Exception ignored) {}
            }
        } else if (item.dir != null) {
            File thumbFile = new File(item.dir, "thumb.png");
            if (!thumbFile.exists()) thumbFile = new File(item.dir, "bg.jpg");
            if (!thumbFile.exists()) thumbFile = new File(item.dir, "bg.png");
            if (thumbFile.exists()) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                Bitmap bmp = BitmapFactory.decodeFile(thumbFile.getAbsolutePath(), opts);
                if (bmp != null) thumb.setImageBitmap(bmp);
            }
        }
        topRow.addView(thumb);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView txtTitle = new TextView(this);
        txtTitle.setText(item.title);
        txtTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        txtTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        txtTitle.setTextColor(Color.WHITE);
        info.addView(txtTitle);

        TextView txtDetails = new TextView(this);
        txtDetails.setText(item.details);
        txtDetails.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        txtDetails.setTextColor(Color.parseColor("#a1a1aa"));
        txtDetails.setPadding(0, dp(3), 0, dp(8));
        info.addView(txtDetails);

        boolean isHome = item.id.equals(activeHome);
        boolean isLock = item.id.equals(activeLock);

        if (isHome || isLock) {
            LinearLayout badgesRow = new LinearLayout(this);
            badgesRow.setOrientation(LinearLayout.HORIZONTAL);

            if (isHome) {
                TextView bHome = new TextView(this);
                bHome.setText("HOME");
                bHome.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                bHome.setTypeface(Typeface.DEFAULT_BOLD);
                bHome.setTextColor(Color.WHITE);
                bHome.setPadding(dp(8), dp(3), dp(8), dp(3));
                bHome.setBackground(makePill(Color.parseColor("#059669")));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.setMargins(0, 0, dp(6), 0);
                bHome.setLayoutParams(p);
                badgesRow.addView(bHome);
            }

            if (isLock) {
                TextView bLock = new TextView(this);
                bLock.setText("LOCK");
                bLock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                bLock.setTypeface(Typeface.DEFAULT_BOLD);
                bLock.setTextColor(Color.WHITE);
                bLock.setPadding(dp(8), dp(3), dp(8), dp(3));
                bLock.setBackground(makePill(Color.parseColor("#0284c7")));
                badgesRow.addView(bLock);
            }
            info.addView(badgesRow);
        }

        topRow.addView(info);
        card.addView(topRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        Button btnHome = createSmallButton(
                isHome ? "✓ Home" : "Home",
                isHome ? Color.parseColor("#064e3b") : Color.parseColor("#27272a"),
                isHome ? Color.parseColor("#059669") : Color.parseColor("#3f3f46"),
                isHome ? Color.parseColor("#a7f3d0") : Color.WHITE
        );
        btnHome.setOnClickListener(v -> {
            prefs.edit().putString(PREF_HOME_SCENE, item.id).apply();
            Toast.makeText(this, "Home screen updated: " + item.title, Toast.LENGTH_SHORT).show();
            refreshScenesList();
        });
        btnRow.addView(btnHome);

        Button btnLock = createSmallButton(
                isLock ? "✓ Lock" : "Lock",
                isLock ? Color.parseColor("#082f49") : Color.parseColor("#27272a"),
                isLock ? Color.parseColor("#0284c7") : Color.parseColor("#3f3f46"),
                isLock ? Color.parseColor("#bae6fd") : Color.WHITE
        );
        btnLock.setOnClickListener(v -> {
            prefs.edit().putString(PREF_LOCK_SCENE, item.id).apply();
            Toast.makeText(this, "Lock screen updated: " + item.title, Toast.LENGTH_SHORT).show();
            refreshScenesList();
        });
        btnRow.addView(btnLock);

        boolean isBoth = isHome && isLock;
        Button btnBoth = createSmallButton(
                isBoth ? "✓ Both" : "Both",
                Color.parseColor("#312e81"),
                Color.parseColor("#4338ca"),
                Color.parseColor("#c7d2fe")
        );
        btnBoth.setOnClickListener(v -> {
            prefs.edit().putString(PREF_HOME_SCENE, item.id).putString(PREF_LOCK_SCENE, item.id).apply();
            Toast.makeText(this, "Applied to Home & Lock: " + item.title, Toast.LENGTH_SHORT).show();
            refreshScenesList();
        });
        btnRow.addView(btnBoth);

        if (!item.isDefault && item.dir != null) {
            Button btnDel = createSmallButton("Delete", Color.parseColor("#450a0a"), Color.parseColor("#7f1d1d"), Color.parseColor("#fca5a5"));
            btnDel.setOnClickListener(v -> confirmDelete(item));
            btnRow.addView(btnDel);
        }

        card.addView(btnRow);
        return card;
    }

    private Button createSmallButton(String text, int bg, int stroke, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(textColor);
        b.setBackground(makeCardBg(bg, stroke, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void confirmDelete(SceneItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Delete wallpaper?")
                .setMessage("Remove '" + item.title + "' from this device?")
                .setPositiveButton("Delete", (d, w) -> {
                    deleteRecursive(item.dir);
                    if (item.id.equals(prefs.getString(PREF_HOME_SCENE, ""))) {
                        prefs.edit().putString(PREF_HOME_SCENE, "default").apply();
                    }
                    if (item.id.equals(prefs.getString(PREF_LOCK_SCENE, ""))) {
                        prefs.edit().putString(PREF_LOCK_SCENE, "default").apply();
                    }
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                    refreshScenesList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
