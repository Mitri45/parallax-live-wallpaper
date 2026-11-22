package dev.parallaxlive;

import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Layered 3D parallax live wallpaper engine for Android.
 *
 * A scene bundle contains:
 *  - bg.jpg (or bg.png): clean background plate with the subject removed / inpainted;
 *  - fg.webp (or fg.png): subject cutout with alpha transparency;
 *  - relief.png (optional): 2-channel per-vertex depth relief map (R = foreground, G = background, centered at 128);
 *  - sway.png (optional): grayscale wind-looseness mask for hair / cloth;
 *  - swing.png (optional): directional wind response (R = Qxx, G = Qxy, B = phase lag);
 *  - meta.json (optional): scene metadata including ambient mote tint [r, g, b].
 *
 * Motion sources:
 *  - accelerometer tilt with a slow self-centering neutral pose;
 *  - touch swipe pan on the home screen plus launcher page offsets when provided;
 *  - slow idle orbit so the scene stays subtly alive on a desk;
 *  - optional per-vertex 3D relief mesh parallax, wind sway, gentle breathing zoom, wake settle-in, and depth-layered motes.
 */
public class LiveService extends WallpaperService {
    private static final String TAG = "ParallaxLive";

    interface BundleSource {
        InputStream open(String name) throws IOException;
        boolean exists(String name);
        String identifier();
    }

    static final class AssetBundleSource implements BundleSource {
        private final AssetManager assets;
        private final String prefix;

        AssetBundleSource(AssetManager assets, String prefix) {
            this.assets = assets;
            this.prefix = prefix;
        }

        @Override
        public InputStream open(String name) throws IOException {
            return assets.open(prefix + "/" + name);
        }

        @Override
        public boolean exists(String name) {
            try (InputStream in = open(name)) {
                return in != null;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public String identifier() {
            return "asset:" + prefix;
        }
    }

    static final class FileBundleSource implements BundleSource {
        private final File dir;

        FileBundleSource(File dir) {
            this.dir = dir;
        }

        @Override
        public InputStream open(String name) throws IOException {
            File f = new File(dir, name);
            if (!f.exists()) throw new IOException("File not found: " + f);
            return new FileInputStream(f);
        }

        @Override
        public boolean exists(String name) {
            return new File(dir, name).exists();
        }

        @Override
        public String identifier() {
            // The modification time makes a re-imported scene with the same name look like a new source.
            return "file:" + dir.getName() + "@" + dir.lastModified();
        }
    }

    /** Longest bitmap side we decode to; larger images are subsampled to stay under GPU texture limits. */
    static final int MAX_BITMAP_SIDE = 4096;

    static File pickFile(File dir, String first, String second) {
        File a = new File(dir, first);
        return a.exists() ? a : new File(dir, second);
    }

    static boolean hasRequiredAssets(File dir) {
        boolean hasBg = new File(dir, "bg.jpg").exists() || new File(dir, "bg.png").exists();
        boolean hasFg = new File(dir, "fg.webp").exists() || new File(dir, "fg.png").exists();
        return hasBg && hasFg;
    }

    private static boolean isDecodableImage(File f) {
        if (!f.isFile()) return false;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    /** Like {@link #hasRequiredAssets} but also checks that the files the engine will load are real images. */
    static boolean hasDecodableAssets(File dir) {
        return hasRequiredAssets(dir)
                && isDecodableImage(pickFile(dir, "bg.jpg", "bg.png"))
                && isDecodableImage(pickFile(dir, "fg.webp", "fg.png"));
    }

    @Override
    public Engine onCreateEngine() {
        return new LiveEngine();
    }

    private static final class SlotArt {
        final String slotName;
        final String sourceId;
        final Bitmap bg;
        final Bitmap fg;
        final float[] sway;
        final float[] swingQxx, swingQxy, swingLag;
        final int swayW, swayH;
        final float[] reliefFg, reliefBg;
        final int tint;

        SlotArt(String slotName, String sourceId, Bitmap bg, Bitmap fg,
                float[] sway, float[] swingQxx, float[] swingQxy, float[] swingLag,
                int swayW, int swayH, float[] reliefFg, float[] reliefBg, int tint) {
            this.slotName = slotName;
            this.sourceId = sourceId;
            this.bg = bg;
            this.fg = fg;
            this.sway = sway;
            this.swingQxx = swingQxx;
            this.swingQxy = swingQxy;
            this.swingLag = swingLag;
            this.swayW = swayW;
            this.swayH = swayH;
            this.reliefFg = reliefFg;
            this.reliefBg = reliefBg;
            this.tint = tint;
        }

        void recycle() {
            if (bg != null) bg.recycle();
            if (fg != null) fg.recycle();
        }
    }

    private final class LiveEngine extends Engine implements SensorEventListener {
        private static final long FRAME_MS = 33; // ~30 fps while visible, zero work while hidden
        private static final float SWAY_AMP = 0.018f; // share of width a fully weighted pixel travels
        private static final float SWING_LAG_S = 0.7f; // seconds wind takes to reach the tip of a strand
        private static final float SWING_VERTICAL = 0.5f; // vertical wind share vs lateral
        private static final float BG_SHIFT = 0.030f; // background parallax at full tilt (share of width)
        private static final float FG_SHIFT = 0.013f; // foreground parallax at full tilt (share of width)
        private static final float VERTICAL_SHARE = 0.45f;
        // Per-vertex depth relief: shift = tilt * width * (BASE + RELIEF * r), r in [-1, 1].
        // Border-zeroed & slope-limited maps prevent mesh folding and keep layer edges inside zoom margins.
        private static final float FG_RELIEF = 0.012f;
        private static final float BG_RELIEF = 0.010f;
        private static final int RELIEF_MESH_W = 61, RELIEF_MESH_H = 135;
        private static final float BG_ZOOM = 1.095f;
        private static final float FG_ZOOM = 1.035f;
        private static final float BREATH_ZOOM = 0.006f; // 6.5 s period
        private static final float SWIPE_ZOOM = 0.05f;
        private static final float SWIPE_FULL = 0.45f;
        private static final float WAKE_ZOOM = 0.05f;
        private static final float FOLLOW_TAU = 0.11f;
        private static final float RELEASE_TAU = 0.32f;
        private static final float WAKE_TAU = 0.45f;
        private static final float TILT_FULL = 0.30f; // sin(tilt) for full deflection (~17 deg)
        private static final float TILT_SIGN = 1f;
        private static final float NEUTRAL_TAU = 5f; // seconds for neutral pose to adapt to grip
        private static final int MESH_W = 20, MESH_H = 44;
        private static final int MOTES = 46;

        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint motePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Matrix bgMatrix = new Matrix();
        private final RectF moteRect = new RectF();
        private final Runnable frame = this::drawFrame;
        private final float[] verts = new float[(RELIEF_MESH_W + 1) * (RELIEF_MESH_H + 1) * 2];
        private final float[] bgVerts = new float[(RELIEF_MESH_W + 1) * (RELIEF_MESH_H + 1) * 2];
        private final float[] moteX = new float[MOTES], moteY = new float[MOTES], moteSize = new float[MOTES];
        private final float[] moteSpeed = new float[MOTES], motePhase = new float[MOTES], moteTwinkle = new float[MOTES];
        private final boolean[] moteFront = new boolean[MOTES];

        private Bitmap dot;
        private SlotArt homeArt = null;
        private SlotArt lockArt = null;
        private SlotArt activeArt = null;
        private final Set<String> failedSources = new HashSet<>();
        private boolean frameErrorLogged = false;
        private boolean screenOff = false;

        private int width, height;
        private boolean visible, touching;
        private float xOffset = 0.5f, xStep = 0f;
        private float downX = 0f, travel = 0f;
        private float swipe = 0f, swipeShift = 0f, wake = 0f;
        private float tiltX = 0f, tiltY = 0f, tiltTargetX = 0f, tiltTargetY = 0f;
        private float neutralX = 0f, neutralY = 0f;
        private long lastSensorNs = 0L;
        private boolean haveNeutral = false;
        private long startMs = SystemClock.uptimeMillis(), lastFrameMs = startMs;
        private SensorManager sensors;
        private Sensor accelerometer;
        private SharedPreferences prefs;

        private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOff = true;
                    prepareLockFrame();
                } else if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOff = false;
                    updateActiveSlot();
                    drawFrame();
                }
            }
        };

        private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
                new SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override
                    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                        ensureSlots();
                        drawFrame();
                    }
                };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            prefs = getSharedPreferences("live_prefs", Context.MODE_PRIVATE);
            if (prefs != null) {
                prefs.registerOnSharedPreferenceChangeListener(prefListener);
            }
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_USER_PRESENT);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                } else {
                    registerReceiver(lockReceiver, filter);
                }
            } catch (Exception ignored) {}
            sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
            accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            dot = softDot(48);
            motePaint.setFilterBitmap(true);
            Random random = new Random(7);
            for (int i = 0; i < MOTES; i++) {
                moteFront[i] = i % 3 == 0;
                moteX[i] = random.nextFloat();
                moteY[i] = random.nextFloat();
                moteSize[i] = moteFront[i] ? 9f + random.nextFloat() * 13f : 4f + random.nextFloat() * 8f;
                moteSpeed[i] = (moteFront[i] ? 0.020f : 0.011f) + random.nextFloat() * 0.012f;
                motePhase[i] = random.nextFloat() * 6.2832f;
                moteTwinkle[i] = 0.5f + random.nextFloat() * 1.4f;
            }
        }

        private String wantedSlot() {
            if (screenOff) return "lock";
            if (Build.VERSION.SDK_INT >= 34) {
                int flags = getWallpaperFlags();
                if ((flags & WallpaperManager.FLAG_LOCK) != 0 && (flags & WallpaperManager.FLAG_SYSTEM) == 0) {
                    return "lock";
                }
                if ((flags & WallpaperManager.FLAG_SYSTEM) != 0 && (flags & WallpaperManager.FLAG_LOCK) == 0) {
                    return "home";
                }
            }
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                return "lock";
            }
            return "home";
        }

        private BundleSource resolveSource(String wanted) {
            if (prefs != null) {
                String chosen = wanted.equals("lock")
                        ? prefs.getString("lock_scene", null)
                        : prefs.getString("home_scene", null);
                if (chosen == null && wanted.equals("lock")) {
                    chosen = prefs.getString("home_scene", null);
                }
                if (chosen != null && !chosen.isEmpty() && !"default".equals(chosen)) {
                    File scenesDir = getExternalFilesDir("scenes");
                    if (scenesDir != null) {
                        File dir = new File(scenesDir, chosen);
                        if (dir.isDirectory() && hasRequiredAssets(dir)) {
                            BundleSource source = new FileBundleSource(dir);
                            // A scene that failed to load falls back to the built-in one instead of retrying forever.
                            if (!failedSources.contains(source.identifier())) return source;
                        }
                    }
                }
            }
            AssetManager assets = getAssets();
            try {
                String[] have = assets.list(wanted);
                if (have == null || have.length == 0) wanted = "home";
            } catch (IOException e) {
                wanted = "home";
            }
            return new AssetBundleSource(assets, wanted);
        }

        private SlotArt loadSlotArt(String slotName) {
            BundleSource source = resolveSource(slotName);
            int tint = Color.rgb(255, 247, 232);
            Bitmap newBg = null, newFg = null;
            float[] weights = null, qxx = null, qxy = null, lag = null;
            int w = 0, h = 0;
            float[] newReliefFg = null, newReliefBg = null;
            try {
                String bgName = source.exists("bg.jpg") ? "bg.jpg" : "bg.png";
                String fgName = source.exists("fg.webp") ? "fg.webp" : "fg.png";
                newBg = decode(source, bgName);
                newFg = decode(source, fgName);

                if (source.exists("sway.png")) {
                    try {
                        Bitmap map = decode(source, "sway.png");
                        if (map != null) {
                            w = map.getWidth();
                            h = map.getHeight();
                            weights = new float[w * h];
                            for (int y = 0; y < h; y++) {
                                for (int x = 0; x < w; x++) weights[y * w + x] = (map.getPixel(x, y) & 0xFF) / 255f;
                            }
                            map.recycle();
                            qxx = new float[w * h];
                            qxy = new float[w * h];
                            lag = new float[w * h];
                            java.util.Arrays.fill(qxx, 1f);
                            if (source.exists("swing.png")) {
                                try {
                                    Bitmap swing = decode(source, "swing.png");
                                    if (swing != null && swing.getWidth() == w && swing.getHeight() == h) {
                                        for (int y = 0; y < h; y++) {
                                            for (int x = 0; x < w; x++) {
                                                int px = swing.getPixel(x, y);
                                                qxx[y * w + x] = ((px >> 16) & 0xFF) / 255f;
                                                qxy[y * w + x] = (((px >> 8) & 0xFF) - 127.5f) / 255f;
                                                lag[y * w + x] = (px & 0xFF) / 255f;
                                            }
                                        }
                                    }
                                    if (swing != null) swing.recycle();
                                } catch (IOException ignored) {}
                            }
                        }
                    } catch (IOException ignored) {}
                }

                if (source.exists("relief.png")) {
                    try {
                        Bitmap relief = decode(source, "relief.png");
                        if (relief != null) {
                            int rw = relief.getWidth(), rh = relief.getHeight();
                            float[] mapFg = new float[rw * rh], mapBg = new float[rw * rh];
                            for (int y = 0; y < rh; y++) {
                                for (int x = 0; x < rw; x++) {
                                    int px = relief.getPixel(x, y);
                                    mapFg[y * rw + x] = (((px >> 16) & 0xFF) - 128) / 127f;
                                    mapBg[y * rw + x] = (((px >> 8) & 0xFF) - 128) / 127f;
                                }
                            }
                            relief.recycle();
                            int vertexCount = (RELIEF_MESH_W + 1) * (RELIEF_MESH_H + 1);
                            newReliefFg = new float[vertexCount];
                            newReliefBg = new float[vertexCount];
                            for (int j = 0; j <= RELIEF_MESH_H; j++) {
                                for (int i = 0; i <= RELIEF_MESH_W; i++) {
                                    float u = i / (float) RELIEF_MESH_W, v = j / (float) RELIEF_MESH_H;
                                    newReliefFg[j * (RELIEF_MESH_W + 1) + i] = sampleMap(mapFg, rw, rh, u, v);
                                    newReliefBg[j * (RELIEF_MESH_W + 1) + i] = sampleMap(mapBg, rw, rh, u, v);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (source.exists("meta.json")) {
                    try {
                        JSONObject meta = new JSONObject(readText(source, "meta.json"));
                        JSONArray rgb = meta.optJSONArray("tint");
                        if (rgb != null && rgb.length() == 3) {
                            tint = Color.rgb(rgb.getInt(0), rgb.getInt(1), rgb.getInt(2));
                        }
                    } catch (Exception ignored) {}
                }

                Log.i(TAG, "loaded slot art: " + slotName + " source=" + source.identifier() + " relief=" + (newReliefFg != null));
                return new SlotArt(slotName, source.identifier(), newBg, newFg, weights, qxx, qxy, lag, w, h, newReliefFg, newReliefBg, tint);
            } catch (Exception | OutOfMemoryError e) {
                Log.e(TAG, "failed loading art for " + slotName + " from " + source.identifier(), e);
                if (newBg != null) newBg.recycle();
                if (newFg != null) newFg.recycle();
                if (source instanceof FileBundleSource) {
                    failedSources.add(source.identifier());
                    return loadSlotArt(slotName); // resolves to the built-in scene now
                }
                return null;
            }
        }

        private SlotArt findLoaded(String sourceId, SlotArt a, SlotArt b) {
            if (a != null && a.sourceId.equals(sourceId)) return a;
            if (b != null && b.sourceId.equals(sourceId)) return b;
            return null;
        }

        private void recycleIfUnused(SlotArt art) {
            if (art != null && art != homeArt && art != lockArt && art != activeArt) art.recycle();
        }

        private void ensureSlots() {
            String homeId = resolveSource("home").identifier();
            String lockId = resolveSource("lock").identifier();
            SlotArt oldHome = homeArt, oldLock = lockArt;

            // Load the new art first and recycle the old one only after nothing points at it any more,
            // so a failed load can never leave the renderer holding a recycled bitmap.
            SlotArt newHome = findLoaded(homeId, oldHome, oldLock);
            if (newHome == null) newHome = loadSlotArt("home");
            SlotArt newLock;
            if (homeId.equals(lockId)) {
                newLock = newHome;
            } else {
                newLock = findLoaded(lockId, oldHome, oldLock);
                if (newLock == null) newLock = loadSlotArt("lock");
            }

            homeArt = newHome;
            lockArt = newLock;
            updateActiveSlot();
            recycleIfUnused(oldHome);
            recycleIfUnused(oldLock);
        }

        private void updateActiveSlot() {
            String wanted = wantedSlot();
            SlotArt target = wanted.equals("lock") ? lockArt : homeArt;
            if (target == null) target = (homeArt != null) ? homeArt : lockArt;
            if (target == null) {
                activeArt = null;
                return;
            }

            if (target != activeArt) {
                activeArt = target;
                Log.i(TAG, "activeArt switched to slot=" + wanted + " (" + target.sourceId + ")");
            }
            motePaint.setColorFilter(new PorterDuffColorFilter(activeArt.tint, PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void onWallpaperFlagsChanged(int which) {
            super.onWallpaperFlagsChanged(which);
            ensureSlots();
        }

        @Override
        public void onDestroy() {
            try {
                unregisterReceiver(lockReceiver);
            } catch (Exception ignored) {}
            if (prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            }
            handler.removeCallbacks(frame);
            stopSensors();
            if (homeArt != null) homeArt.recycle();
            if (lockArt != null && lockArt != homeArt) lockArt.recycle();
            homeArt = null;
            lockArt = null;
            activeArt = null;
            if (dot != null) dot.recycle();
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            ensureSlots();
            drawFrame();
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            handler.removeCallbacks(frame);
            if (visible) {
                lastFrameMs = SystemClock.uptimeMillis();
                haveNeutral = false;
                if (sensors != null && accelerometer != null) {
                    sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
                }
                ensureSlots();
                drawFrame();
            } else {
                stopSensors();
                prepareLockFrame();
            }
        }

        private void prepareLockFrame() {
            if (lockArt == null) return;
            activeArt = lockArt;
            motePaint.setColorFilter(new PorterDuffColorFilter(activeArt.tint, PorterDuff.Mode.SRC_IN));
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) paint(canvas);
            } catch (Exception ignored) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {}
                }
            }
        }

        private void stopSensors() {
            if (sensors != null) sensors.unregisterListener(this);
        }

        @Override
        public void onSensorChanged(SensorEvent e) {
            float ax = e.values[0] / SensorManager.GRAVITY_EARTH;
            float az = e.values[2] / SensorManager.GRAVITY_EARTH;
            float dt = lastSensorNs == 0L ? 0f : Math.min(1f, (e.timestamp - lastSensorNs) / 1e9f);
            lastSensorNs = e.timestamp;
            if (!haveNeutral) {
                neutralX = ax;
                neutralY = az;
                haveNeutral = true;
            }
            float follow = dt / (NEUTRAL_TAU + dt);
            neutralX += (ax - neutralX) * follow;
            neutralY += (az - neutralY) * follow;
            tiltTargetX = clamp((ax - neutralX) / TILT_FULL, -1f, 1f) * TILT_SIGN;
            tiltTargetY = clamp((az - neutralY) / TILT_FULL, -1f, 1f) * TILT_SIGN;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void onOffsetsChanged(float x, float y, float xStepNew, float yStepNew, int xPixels, int yPixels) {
            xStep = xStepNew;
            xOffset = x;
            if (visible && activeArt == lockArt && activeArt != homeArt) {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && !km.isKeyguardLocked()) {
                    updateActiveSlot();
                    drawFrame();
                }
            }
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            super.onTouchEvent(e);
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                travel = 0f;
                touching = true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (width > 0) travel = (e.getX() - downX) / width;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                touching = false;
            }
        }

        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z, android.os.Bundle extras, boolean resultRequested) {
            if ("android.wallpaper.goingtosleep".equals(action)) {
                screenOff = true;
                prepareLockFrame();
            } else if ("android.wallpaper.wakingup".equals(action)) {
                wake = 1f;
            } else if (WallpaperManager.COMMAND_TAP.equals(action)) {
                wake = Math.max(wake, 0.5f);
            }
            return null;
        }

        private void drawFrame() {
            if (!visible && !screenOff) return;
            handler.removeCallbacks(frame);

            if (visible && activeArt == lockArt && activeArt != homeArt) {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && !km.isKeyguardLocked()) {
                    updateActiveSlot();
                }
            }

            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) paint(canvas);
            } catch (Exception e) {
                // An invalid surface or an undrawable bitmap must not take the whole process down.
                if (!frameErrorLogged) {
                    frameErrorLogged = true;
                    Log.e(TAG, "frame skipped", e);
                }
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {}
                }
            }
            if (visible) handler.postDelayed(frame, FRAME_MS);
        }

        private float clamp(float v, float lo, float hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        private float ease(float value, float target, float tau, float dt) {
            return value + (target - value) * (1f - (float) Math.exp(-dt / tau));
        }

        private void paint(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (activeArt == null || width <= 0 || height <= 0) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
            lastFrameMs = now;
            float t = (now - startMs) / 1000f;
            double twoPi = 2.0 * Math.PI;

            float tau = touching ? FOLLOW_TAU : RELEASE_TAU;
            float pan = touching ? clamp(travel / SWIPE_FULL, -1f, 1f) : 0f;
            swipe = ease(swipe, Math.abs(pan), tau, dt);
            swipeShift = ease(swipeShift, pan, tau, dt);
            wake = ease(wake, 0f, WAKE_TAU, dt);
            tiltX = ease(tiltX, tiltTargetX, 0.18f, dt);
            tiltY = ease(tiltY, tiltTargetY, 0.18f, dt);
            float page = xStep > 0f ? (xOffset - 0.5f) * 1.6f : 0f;
            float px = clamp(tiltX - swipeShift * 0.9f + page + 0.35f * (float) Math.sin(twoPi * t / 17.0), -1.3f, 1.3f);
            float py = clamp(tiltY + 0.25f * (float) Math.sin(twoPi * t / 23.0 + 1.0), -1.3f, 1.3f);
            float breath = 0.5f * (1f + (float) Math.sin(twoPi * t / 6.5));

            boolean showMotes = (prefs == null || prefs.getBoolean("motes_enabled", true));

            renderArt(canvas, activeArt, t, dt, px, py, breath, swipe, wake, twoPi, showMotes);
        }

        private void renderArt(Canvas canvas, SlotArt art, float t, float dt, float px, float py,
                               float breath, float swipe, float wake, double twoPi, boolean showMotes) {
            if (art == null || art.bg == null || art.fg == null) return;

            boolean useRelief = (art.reliefFg != null) && (prefs == null || prefs.getBoolean("relief_enabled", true));
            boolean useReliefBg = (art.reliefBg != null) && (prefs == null || prefs.getBoolean("relief_enabled", true));

            // 1. Background (far plane)
            float bgCover = Math.max(width / (float) art.bg.getWidth(), height / (float) art.bg.getHeight());
            float bgScale = bgCover * (BG_ZOOM + 0.5f * SWIPE_ZOOM * swipe);
            float bgX = -px * BG_SHIFT * width;
            float bgY = -py * BG_SHIFT * VERTICAL_SHARE * width;
            if (useReliefBg) {
                float bgw = art.bg.getWidth(), bgh = art.bg.getHeight();
                int bk = 0;
                for (int j = 0; j <= RELIEF_MESH_H; j++) {
                    float by = j * bgh / RELIEF_MESH_H;
                    for (int i = 0; i <= RELIEF_MESH_W; i++) {
                        float gain = BG_SHIFT + BG_RELIEF * art.reliefBg[j * (RELIEF_MESH_W + 1) + i];
                        bgVerts[bk++] = width / 2f + (i * bgw / RELIEF_MESH_W - bgw / 2f) * bgScale - px * gain * width;
                        bgVerts[bk++] = height / 2f + (by - bgh / 2f) * bgScale - py * gain * VERTICAL_SHARE * width;
                    }
                }
                canvas.drawBitmapMesh(art.bg, RELIEF_MESH_W, RELIEF_MESH_H, bgVerts, 0, null, 0, bitmapPaint);
            } else {
                bgMatrix.reset();
                bgMatrix.postScale(bgScale, bgScale);
                bgMatrix.postTranslate((width - art.bg.getWidth() * bgScale) / 2f + bgX, (height - art.bg.getHeight() * bgScale) / 2f + bgY);
                canvas.drawBitmap(art.bg, bgMatrix, bitmapPaint);
            }

            // 2. Back motes (between background and subject)
            if (showMotes) {
                drawMotes(canvas, t, dt, px, false);
            }

            // 3. Foreground subject (near plane, relief + optional wind sway)
            float fgCover = Math.max(width / (float) art.fg.getWidth(), height / (float) art.fg.getHeight());
            float fgZoom = FG_ZOOM + BREATH_ZOOM * breath + SWIPE_ZOOM * swipe + WAKE_ZOOM * wake;
            float fgScale = fgCover * fgZoom;
            float fgX = px * FG_SHIFT * width;
            float fgY = py * FG_SHIFT * VERTICAL_SHARE * width;
            float bw = art.fg.getWidth(), bh = art.fg.getHeight();
            int meshW = useRelief ? RELIEF_MESH_W : MESH_W;
            int meshH = useRelief ? RELIEF_MESH_H : MESH_H;
            int k = 0;
            for (int j = 0; j <= meshH; j++) {
                float by = j * bh / meshH;
                for (int i = 0; i <= meshW; i++) {
                    float bx = i * bw / meshW;
                    float w = sampleSway(art.sway, art.swayW, art.swayH, bx / bw, by / bh);
                    float dx = 0f, dy = 0f;
                    if (w > 0.003f) {
                        float qxx = sampleSway(art.swingQxx, art.swayW, art.swayH, bx / bw, by / bh);
                        float qxy = sampleSway(art.swingQxy, art.swayW, art.swayH, bx / bw, by / bh);
                        float lag = sampleSway(art.swingLag, art.swayW, art.swayH, bx / bw, by / bh);
                        float tw = t - SWING_LAG_S * lag;
                        float gustW = 0.8f + 0.2f * (float) Math.sin(twoPi * tw / 13.0 + 0.6);
                        float lateral = gustW * (0.55f * (float) Math.sin(twoPi * tw / 4.7)
                                + 0.30f * (float) Math.sin(twoPi * tw / 7.9 + 1.7)
                                + 0.15f * (float) Math.sin(twoPi * tw / 2.9 + 3.1));
                        float vertical = gustW * SWING_VERTICAL * (0.50f * (float) Math.sin(twoPi * tw / 6.1 + 0.8)
                                + 0.30f * (float) Math.sin(twoPi * tw / 3.7 + 2.2));
                        float amp = SWAY_AMP * bw * w;
                        dx = amp * (qxx * lateral + qxy * vertical);
                        dy = amp * (qxy * lateral + (1f - qxx) * vertical);
                    }
                    float shiftX = fgX, shiftY = fgY;
                    if (useRelief) {
                        float gain = FG_SHIFT + FG_RELIEF * art.reliefFg[j * (RELIEF_MESH_W + 1) + i];
                        shiftX = px * gain * width;
                        shiftY = py * gain * VERTICAL_SHARE * width;
                    }
                    verts[k++] = width / 2f + (bx + dx - bw / 2f) * fgScale + shiftX;
                    verts[k++] = height / 2f + (by + dy - bh / 2f) * fgScale + shiftY;
                }
            }
            canvas.drawBitmapMesh(art.fg, meshW, meshH, verts, 0, null, 0, bitmapPaint);

            // 4. Front motes (in front of subject)
            if (showMotes) {
                drawMotes(canvas, t, dt, px, true);
            }
        }

        private float sampleSway(float[] map, int mw, int mh, float u, float v) {
            if (map == null || mw <= 0 || mh <= 0) return 0f;
            return sampleMap(map, mw, mh, u, v);
        }

        private float sampleMap(float[] map, int mw, int mh, float u, float v) {
            float x = clamp(u * mw - 0.5f, 0f, Math.max(0f, mw - 1.001f));
            float y = clamp(v * mh - 0.5f, 0f, Math.max(0f, mh - 1.001f));
            int x0 = (int) x, y0 = (int) y;
            int x1 = Math.min(x0 + 1, mw - 1), y1 = Math.min(y0 + 1, mh - 1);
            float fx = x - x0, fy = y - y0;
            float a = map[y0 * mw + x0], b = map[y0 * mw + x1];
            float c = map[y1 * mw + x0], d = map[y1 * mw + x1];
            return (a * (1f - fx) + b * fx) * (1f - fy) + (c * (1f - fx) + d * fx) * fy;
        }

        private void drawMotes(Canvas canvas, float t, float dt, float px, boolean front) {
            if (dot == null) return;
            float parallax = front ? px * 0.022f * width : -px * 0.004f * width;
            for (int i = 0; i < MOTES; i++) {
                if (moteFront[i] != front) continue;
                moteY[i] -= moteSpeed[i] * dt;
                moteX[i] += (float) Math.sin(t * 0.45f + motePhase[i]) * 0.012f * dt;
                if (moteY[i] < -0.03f) {
                    moteY[i] = 1.03f;
                    moteX[i] = (moteX[i] * 7.3f + motePhase[i]) % 1f;
                }
                if (moteX[i] < 0f) moteX[i] += 1f;
                if (moteX[i] > 1f) moteX[i] -= 1f;
                float twinkle = 0.55f + 0.45f * (float) Math.sin(t * moteTwinkle[i] + motePhase[i]);
                int alpha = (int) (255f * (front ? 0.55f : 0.40f) * twinkle);
                motePaint.setAlpha(Math.max(0, Math.min(255, alpha)));
                float cx = moteX[i] * width + parallax;
                float cy = moteY[i] * height;
                float r = moteSize[i];
                moteRect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawBitmap(dot, null, moteRect, motePaint);
            }
        }

        private Bitmap decode(BundleSource source, String name) throws IOException {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = source.open(name)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException("cannot decode " + name + " from " + source.identifier());
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / opts.inSampleSize > MAX_BITMAP_SIDE) {
                opts.inSampleSize *= 2;
            }
            try (InputStream in = source.open(name)) {
                Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
                if (bitmap == null) throw new IOException("cannot decode " + name + " from " + source.identifier());
                return bitmap;
            }
        }

        private String readText(BundleSource source, String name) throws IOException {
            try (InputStream in = source.open(name)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        }

        private Bitmap softDot(int size) {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bitmap);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float r = size / 2f;
            p.setShader(new RadialGradient(r, r, r,
                    new int[]{Color.argb(255, 255, 255, 255), Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(r, r, r, p);
            return bitmap;
        }
    }
}
