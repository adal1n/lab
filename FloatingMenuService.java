package com.mtool.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public class FloatingMenuService extends Service {
    private static final String PREFS = "mco_remote";
    private static final String COMMAND_FILE = "MCO_GG_command.json";
    private static final String STATUS_FILE = "MCO_GG_status.json";
    private static final String CHANNEL = "mco_remote_overlay";
    private static final int GREEN = Color.rgb(0, 255, 70);
    private static final int NEON = Color.rgb(0, 255, 220);
    private static final int BASE = Color.rgb(0, 205, 182);
    private static final int RED = Color.rgb(255, 23, 68);
    private static final int DARK = Color.rgb(15, 23, 42);
    private static final int PANEL = Color.rgb(24, 31, 45);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(178, 186, 199);
    private static final int BLUE = Color.rgb(0, 122, 255);

    private static final String[] TOGGLE_KEYS = new String[]{
            "fridaTool", "shoot", "reload", "damageUpGun", "damageUpSkill", "respawn",
            "speed", "noClip", "recoil", "blackHole", "kdaBooster", "aimBot", "aimAssist",
            "allEnemy", "excludeBot", "captureMilk", "touhouMedley",
    };

    private final Map<String, Boolean> states = new LinkedHashMap<>();
    private final Map<String, Switch> switchViews = new LinkedHashMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View currentView;
    private SharedPreferences prefs;
    private boolean expanded = false;
    private float speedMultiplier = 5.0f;
    private int lockZonePos = 15;
    private int smoothAimPos = 30;
    private int assistActiveTime = 10;
    private long commandSeq = 0;
    private MediaPlayer mediaPlayer;
    private int currentSongIndex = -1;
    private RenderScript renderScript;
    private boolean updatingSwitches = false;
    private boolean updatingEnemySwitches = false;
    private boolean autoRespawnOn = false;
    private boolean kickLoopOn = false;
    private String kickLoopAction = null;
    private TextView kickLoopTargetView;
    private Switch kickLoopSwitch;
    private final Runnable kickLoopRunnable = new Runnable() {
        @Override public void run() {
            if (!kickLoopOn) return;
            if (kickLoopAction != null && MemOps.isConnected()) {
                FridaTool.run(kickLoopAction, loopArgFor(kickLoopAction), null);
            }
            handler.postDelayed(this, loopIntervalFor(kickLoopAction));
        }
    };
    private static final int AUTO_RESPAWN_POLL_MS = 50;
    private final Runnable autoRespawnRunnable = new Runnable() {
        @Override public void run() {
            if (!autoRespawnOn) return;
            if (MemOps.isConnected()) {
                FridaTool.run("checkRespawn", null, null);
            }
            handler.postDelayed(this, AUTO_RESPAWN_POLL_MS);
        }
    };

    private String loopArgFor(String action) {
        if ("electric".equals(action) || "mago".equals(action)) return enabledSlotsArg();
        if ("buffOnKhaos".equals(action)) return enabledSlotsArg();
        if ("buffOnWheelleg".equals(action)) return enabledSlotsArg();
        if ("createChooChooBuff".equals(action)) return enabledSlotsArg();
        if ("mix".equals(action)) return enabledSlotsArg();
        return null;
    }

    private static long loopIntervalFor(String action) {
        if ("esco".equals(action)) return 200;
        if ("electric".equals(action)) return 5000;
        if ("mago".equals(action)) return 6000;
        if ("enemyKick".equals(action) || "allKick".equals(action) || "botKick".equals(action)) return 2000;
        if ("buffOnKhaos".equals(action)) return 6000;
        if ("buffOnWheelleg".equals(action)) return 6000;
        if ("createChooChooBuff".equals(action)) return 8000;
        if ("mix".equals(action)) return 6000;
        return 1000;
    }

    private void setKickLoop(boolean on) {
        states.put("kickLoop", on);
        saveStateOnly();
        if (on) startKickLoop(); else stopKickLoop();
    }

    private void startKickLoop() {
        kickLoopOn = true;
        handler.removeCallbacks(kickLoopRunnable);
        updateKickLoopTargetView();
        if (kickLoopAction != null && MemOps.isConnected()) {
            FridaTool.run(kickLoopAction, loopArgFor(kickLoopAction), null);
        }
        handler.postDelayed(kickLoopRunnable, loopIntervalFor(kickLoopAction));
    }

    private void stopKickLoop() {
        kickLoopOn = false;
        handler.removeCallbacks(kickLoopRunnable);
        updateKickLoopTargetView();
    }

    private void recordKickPress(String action, String arg) {
        if (!"enemyKick".equals(action) && !"allKick".equals(action) && !"botKick".equals(action)
                && !"electric".equals(action) && !"mago".equals(action) && !"esco".equals(action)
                && !"buffOnKhaos".equals(action) && !"createChooChooBuff".equals(action)
                && !"buffOnWheelleg".equals(action) && !"mix".equals(action)) return;
        kickLoopAction = action;
        updateKickLoopTargetView();
    }

    private void updateKickLoopTargetView() {
        if (kickLoopTargetView == null) return;
        if (!kickLoopOn) {
            kickLoopTargetView.setText("-");
            return;
        }
        String name;
        if ("enemyKick".equals(kickLoopAction)) name = getString(R.string.frida_enemy_kick);
        else if ("allKick".equals(kickLoopAction)) name = getString(R.string.frida_all_kick);
        else if ("botKick".equals(kickLoopAction)) name = getString(R.string.frida_bot_kick);
        else if ("electric".equals(kickLoopAction)) name = getString(R.string.frida_electric);
        else if ("mago".equals(kickLoopAction)) name = getString(R.string.frida_mago);
        else if ("esco".equals(kickLoopAction)) name = getString(R.string.frida_esco);
        else if ("buffOnKhaos".equals(kickLoopAction)) name = getString(R.string.frida_buff_on_khaos);
        else if ("createChooChooBuff".equals(kickLoopAction)) name = getString(R.string.frida_create_choo_choo_buff);
        else if ("buffOnWheelleg".equals(kickLoopAction)) name = getString(R.string.frida_buff_on_wheelleg);
        else if ("mix".equals(kickLoopAction)) name = getString(R.string.frida_mix);
        else name = "-";
        kickLoopTargetView.setText(name);
    }
    private boolean updatingBlackHoleHackSwitches = false;
    private FrameLayout pageContainer;
    private View mainPage;
    private View enemyPage;
    private LinearLayout enemyListContainer;
    private TextView enemyRoomView;
    private View costumePage;
    private LinearLayout costumeListContainer;
    private Button costumeConfirmBtn;
    private final List<LinearLayout> costumeRows = new ArrayList<>();
    private int selectedCostumeClass = 0;
    private String[] costumeClassNames;
    private Button magoBuffBtn;
    private boolean magoBuffOn = false;
    private static final String[] COSTUME_CLASS_NAMES_FALLBACK = new String[]{
            "Assault", "Medic", "Bomber", "Recon", "Ghost", "Shield", "Launcher", "Invisible",
            "Hook", "Desperado", "MyoCat", "Iron", "Carog", "Wheeleg", "Unco", "Air",
            "Electric", "Blade", "Usagi", "Mago", "Plug", "ChooChoo", "Viveli", "Cooker",
            "Bava", "Mush", "Bebe", "Meka", "Khaos", "Octobber"
    };
    private TextView baseLabelView;
    private String lastBaseLabel = "";
    private boolean lastConnected = false;
    private TextView attachStatusView;
    private View fridaShieldView;
    private int panelWidth = 0;

    // ---- オートクリッカー統合 ----
    private android.animation.ValueAnimator panelHeightAnimator;
    private final Runnable enemyListPoll = new Runnable() {
        @Override public void run() {
            if (!expanded || currentView == null) return;
            if (enemyPage == null || enemyPage.getVisibility() != View.VISIBLE) return;
            refreshEnemyList();
            postEnemyListPoll(3000);
        }
    };

    private void postEnemyListPoll(int delayMs) {
        if (currentView == null) return;
        currentView.removeCallbacks(enemyListPoll);
        currentView.postDelayed(enemyListPoll, delayMs);
    }

    private void cancelEnemyListPoll() {
        if (currentView != null) currentView.removeCallbacks(enemyListPoll);
    }

    private long pendingMagoDeniedSeq = -1;
    private int pendingMagoDeniedChecks = 0;

    private final Runnable baseStatusPoll = new Runnable() {
        @Override public void run() {
            if (!expanded || currentView == null) return;
            boolean connected = MemOps.isConnected();
            if (connected && !lastConnected) {
                pushAllStates();
            }
            lastConnected = connected;
            if (baseLabelView != null) {
                String status = MemOps.getStatus();
                if (status != null && !status.isEmpty() && !status.contains("---")) {
                    if (!status.equals(lastBaseLabel)) {
                        lastBaseLabel = status;
                        baseLabelView.setText(status);
                    }
                } else if (lastBaseLabel != null && !lastBaseLabel.isEmpty()) {
                    baseLabelView.setText(lastBaseLabel);
                } else {
                    baseLabelView.setText("");
                }
            }
            postBaseStatusPoll(100);
        }
    };

    private void postBaseStatusPoll(int delayMs) {
        if (currentView == null) return;
        currentView.removeCallbacks(baseStatusPoll);
        currentView.postDelayed(baseStatusPoll, delayMs);
    }

    private void scheduleBaseStatusPoll() {
        postBaseStatusPoll(0);
    }

    private void cancelBaseStatusPoll() {
        if (currentView != null) currentView.removeCallbacks(baseStatusPoll);
    }

    private final Handler pidStatusHandler = new Handler(Looper.getMainLooper());
    private int lastAttachedPid = 0;
    private int detachMisses = 0;
    private volatile boolean repairingXa = false;

    private final Runnable attachStatusPoll = new Runnable() {
        @Override public void run() {
            int pid = MemOps.getAttachedPid();
            if (pid > 0 && pid != lastAttachedPid) {
                detachMisses = 0;
                lastAttachedPid = pid;
                handleProcessAttached(pid);
            } else if (pid <= 0) {
                if (detachMisses < 3) {
                    detachMisses++;
                } else if (lastAttachedPid != 0) {
                    lastAttachedPid = 0;
                    FridaTool.onGameDetached();
                }
            } else {
                detachMisses = 0;
            }
            if (pid > 0) {
                FridaTool.tick(pid);
            }
            if (expanded && attachStatusView != null) {
                if (pid > 0) {
                    attachStatusView.setText("Process attached " + pid);
                    attachStatusView.setTextColor(Color.GREEN);
                } else {
                    attachStatusView.setText("Not attached");
                    attachStatusView.setTextColor(RED);
                }
            }
            pidStatusHandler.postDelayed(this, 2000);
        }
    };

    private void postAttachStatusPoll(int delayMs) {
        pidStatusHandler.removeCallbacks(attachStatusPoll);
        pidStatusHandler.postDelayed(attachStatusPoll, delayMs);
    }

    private void scheduleAttachStatusPoll() {
        postAttachStatusPoll(0);
    }

    private void cancelAttachStatusPoll() {
        pidStatusHandler.removeCallbacks(attachStatusPoll);
    }

    private void handleProcessAttached(int pid) {
        FridaTool.init(this);
        FridaTool.onGameAttached(pid);
        if (repairingXa) return;
        repairingXa = true;
        new Thread(() -> {
            try {
                final boolean changed = checkGameVersionChanged();
                if (changed) {
                    new Handler(Looper.getMainLooper()).post(() -> showFridaShield(getString(R.string.repairing_update)));
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    final boolean repaired = MemOps.repairXa();
                    new Handler(Looper.getMainLooper()).post(() -> {
                        hideFridaShield();
                        if (!repaired) {
                            Toast.makeText(FloatingMenuService.this,
                                    getString(R.string.toast_xa_repair_failed), Toast.LENGTH_LONG).show();
                        }
                        pushAllStates();
                    });
                }
            } finally {
                repairingXa = false;
            }
        }).start();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        LocaleHelper.apply(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Defaults.ensure(prefs);
        windowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        loadState();
        startForeground(41, notification());
        FridaTool.setMonitor(new FridaTool.Monitor() {
            @Override public void onLog(final String message) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        try {
                            Toast.makeText(FloatingMenuService.this, "AutoHeal: " + message,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {}
                    }
                });
            }
        });
        new Thread(() -> {
            MemOps.init("com.gameparadiso.milkchoco");
            new Handler(Looper.getMainLooper()).post(() -> {
                pushAllStates();
                scheduleAttachStatusPoll();
            });
        }).start();
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LocaleHelper.apply(this);
        loadState();
        if (currentView == null) showBubble();
        if (intent != null) {
            if (intent.getBooleanExtra("syncNow", false)) {
                // sync handled by loop
            }
            if (intent.getBooleanExtra("refreshUi", false)) {
                if (expanded) showPanel();
                else showBubble();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        kickLoopOn = false;
        handler.removeCallbacks(kickLoopRunnable);
        autoRespawnOn = false;
        handler.removeCallbacks(autoRespawnRunnable);
        FridaTool.shutdown();
        FridaTool.setMonitor(null);
        MemOps.shutdown();
        cancelAttachStatusPoll();
        removeCurrentView();
        hideFridaShield();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleHelper.apply(this);
        if (expanded) showPanel();
    }

    private void loadState() {
        states.put("fridaTool", prefs.getBoolean("fridaTool", false));
        states.put("kickLoop", prefs.getBoolean("kickLoop", false));
        states.put("shoot", prefs.getBoolean("shoot", false));
        states.put("reload", prefs.getBoolean("reload", false));
        boolean legacyDamage = prefs.getBoolean("damageUp", false);
        states.put("damageUpGun", prefs.getBoolean("damageUpGun", legacyDamage));
        states.put("damageUpSkill", prefs.getBoolean("damageUpSkill", legacyDamage));
        states.put("skillDamageDisableMainWeapon", prefs.getBoolean("skillDamageDisableMainWeapon", false));
        states.put("respawn", prefs.getBoolean("respawn", false));
        states.put("respawnGroup", prefs.getBoolean("respawnGroup", false));
        states.put("respawnInstant", prefs.getBoolean("respawnInstant", false));
        states.put("speed", prefs.getBoolean("speed", false));
        states.put("noClip", prefs.getBoolean("noClip", false));
        states.put("recoil", prefs.getBoolean("recoil", false));
        states.put("blackHole", prefs.getBoolean("blackHole", false));
        states.put("blackHoleFixed", prefs.getBoolean("blackHoleFixed", false));
        states.put("bavaHack", prefs.getBoolean("bavaHack", false));
        states.put("kdaBooster", prefs.getBoolean("kdaBooster", false));
        states.put("aimBot", prefs.getBoolean("aimBot", false));
        states.put("aimAssist", prefs.getBoolean("aimAssist", false));
        states.put("assistDisableSubWeapon", prefs.getBoolean("assistDisableSubWeapon", false));
        states.put("assistActiveTime", prefs.getBoolean("assistActiveTime", false));
        states.put("assistOnlyShooting", prefs.getBoolean("assistOnlyShooting", false));
        states.put("allEnemy", prefs.getBoolean("allEnemy", false));
        states.put("excludeBot", prefs.getBoolean("excludeBot", false));
        states.put("touhouMedley", prefs.getBoolean("touhouMedley", false));
        states.put("touhouLoop", prefs.getBoolean("touhouLoop", false));
        speedMultiplier = prefs.getFloat("speedMultiplier", 5.0f);
        lockZonePos = prefs.getInt("lockZonePos", 15);
        smoothAimPos = prefs.getInt("smoothAimPos", 30);
        assistActiveTime = prefs.getInt("assistActiveTimeValue", 10);
        commandSeq = prefs.getLong("commandSeq", 0);
        applyHiddenTogglesOff();
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.app_label), NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
            return new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(getApplicationInfo().icon)
                    .setContentTitle(getString(R.string.app_label))
                    .setContentText(getString(R.string.notif_overlay_running))
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(getString(R.string.app_label))
                .setContentText(getString(R.string.notif_overlay_running))
                .build();
    }

    private void showBubble() {
        if (expanded && currentView != null) {
            final View oldView = currentView;
            int pw = oldView.getWidth();
            int ph = oldView.getHeight();
            if (ph > 0) {
                int lastBubbleX = prefs.getInt("lastBubbleX", dp(18));
                int lastBubbleY = prefs.getInt("lastBubbleY", dp(120));
                float bcx = lastBubbleX + dp(29);
                float bcy = lastBubbleY + dp(29);
                float pivotX = Math.max(0, Math.min(pw, bcx - params.x));
                float pivotY = Math.max(0, Math.min(ph, bcy - params.y));
                oldView.setPivotX(pivotX);
                oldView.setPivotY(pivotY);
                oldView.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).setDuration(200).withEndAction(new Runnable() {
                    @Override public void run() {
                        expanded = false;
                        cancelBaseStatusPoll();
                        baseLabelView = null;
                        oldView.setVisibility(View.GONE);
                        removeCurrentView();
                        params.x = lastBubbleX;
                        params.y = lastBubbleY;
                        prefs.edit().putInt("overlayX", lastBubbleX).putInt("overlayY", lastBubbleY).apply();
                        ImageView bubble = new ImageView(FloatingMenuService.this);
                        setupBubbleIcon(bubble);
                        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        bubble.setPadding(dp(3), dp(3), dp(3), dp(3));
                        float op = prefs.getInt("bubbleOpacity", 100) / 100f;
                        bubble.setAlpha(op);
                        applyBubbleBackground(bubble);
                        bubble.setOnTouchListener(new DragTouchListener(true));
                        addOverlayView(bubble, dp(58), dp(58), false);
                    }
                }).start();
                return;
            }
        }
        expanded = false;
        cancelBaseStatusPoll();
        baseLabelView = null;
        removeCurrentView();
        ImageView bubble = new ImageView(this);
        setupBubbleIcon(bubble);
        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubble.setPadding(dp(3), dp(3), dp(3), dp(3));
        float op = prefs.getInt("bubbleOpacity", 100) / 100f;
        bubble.setAlpha(op);
        applyBubbleBackground(bubble);
        bubble.setOnTouchListener(new DragTouchListener(true));
        addOverlayView(bubble, dp(58), dp(58), false);
    }

    private void applyBubbleBackground(ImageView iv) {
        int borderColor = prefs.getInt("bubbleBorderColor", Color.rgb(0, 255, 70));
        boolean borderEnabled = prefs.getBoolean("bubbleBorderEnabled", true);
        int borderWidth = borderEnabled ? dp(2) : 0;
        iv.setBackground(round(DARK, dp(29), borderColor, borderWidth));
    }

    private Drawable makePanelBg() {
        String path = prefs.getString("panelBgPath", "");
        int brightness = prefs.getInt("panelBrightness", 0);
        int blur = prefs.getInt("panelBlur", 0);

        if (!path.isEmpty()) {
            Bitmap src = BitmapFactory.decodeFile(path);
            if (src != null) {
                return new PanelBgDrawable(src, brightness, blur, dp(14));
            }
        }
        int color = prefs.getInt("panelBgColor", Color.rgb(24, 31, 45));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(14));
        gd.setColor(color);
        gd.setStroke(dp(1), Color.rgb(59, 70, 92));
        return gd;
    }

    private Bitmap blurBitmap(Context ctx, Bitmap src, int blurLevel) {
        if (blurLevel <= 0) return src;
        int iw = Math.max(1, src.getWidth() / 2);
        int ih = Math.max(1, src.getHeight() / 2);
        Bitmap cur = Bitmap.createScaledBitmap(src, iw, ih, true);
        try {
            if (renderScript == null) renderScript = RenderScript.create(ctx);
            Allocation input = Allocation.createFromBitmap(renderScript, cur);
            Allocation output = Allocation.createTyped(renderScript, input.getType());
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
            script.setInput(input);
            script.setRadius(Math.max(0.01f, Math.min(25, blurLevel * 0.15f)));
            script.forEach(output);
            output.copyTo(cur);
            script.destroy();
            input.destroy();
            output.destroy();
        } catch (Exception ignored) {}
        Bitmap result = Bitmap.createScaledBitmap(cur, src.getWidth(), src.getHeight(), true);
        if (cur != src) cur.recycle();
        return result;
    }

    private class PanelBgDrawable extends Drawable {
        private final Bitmap src;
        private final int brightness;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private final float radius;
        private final Bitmap blurred;
        private final float panX, panY, zoom;

        PanelBgDrawable(Bitmap src, int brightness, int blur, float radius) {
            this.src = src;
            this.brightness = brightness;
            this.radius = radius;
            this.blurred = blurBitmap(FloatingMenuService.this, src, blur);
            paint.setFilterBitmap(true);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(Color.rgb(59, 70, 92));
            panX = prefs.getFloat("bgPanX", 0);
            panY = prefs.getFloat("bgPanY", 0);
            zoom = prefs.getFloat("bgZoom", 1f);
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            clipPath.rewind();
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            float bmpW = blurred.getWidth(), bmpH = blurred.getHeight();
            float refH = panelHeight();
            float scale = Math.max(rect.width() / bmpW, refH / bmpH) * zoom;
            float imgW = bmpW * scale;
            float imgH = bmpH * scale;
            float left = rect.left + (rect.width() - imgW) / 2f + panX;
            float top = rect.top + (rect.height() - imgH) / 2f + panY;
            canvas.drawBitmap(blurred, null, new RectF(left, top, left + imgW, top + imgH), paint);

            int alpha = (int) (brightness * 2.55f);
            if (alpha > 0) {
                canvas.drawColor(Color.argb(Math.min(alpha, 255), 0, 0, 0));
            }

            canvas.restore();

            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(Color.rgb(59, 70, 92));
            canvas.drawRoundRect(rect, radius, radius, borderPaint);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private void showPanel() {
        expanded = true;
        pushAllStates();
        prefs.edit().putInt("lastBubbleX", prefs.getInt("overlayX", dp(18)))
                   .putInt("lastBubbleY", prefs.getInt("overlayY", dp(120)))
                   .apply();
        removeCurrentView();
        switchViews.clear();
        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setPadding(dp(10), dp(8), dp(10), dp(10));
        frame.setBackground(makePanelBg());
        frame.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    showBubble();
                    return true;
                }
                return false;
            }
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, 0, 0, dp(1));
        header.setOnTouchListener(new DragTouchListener(false));
        TextView title = text("Mtool Ver " + GameVersion.get(this), 22, TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setBackground(round(Color.rgb(35, 46, 66), dp(9), Color.rgb(72, 85, 110), dp(1)));
        title.setOnTouchListener(new DragTouchListener(false));
        header.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
        frame.addView(header);

        baseLabelView = text("", 13, BASE, Typeface.BOLD);
        baseLabelView.setGravity(Gravity.CENTER);
        baseLabelView.setSingleLine(true);
        baseLabelView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        baseLabelView.setPadding(0, 0, 0, dp(1));
        if (lastBaseLabel != null && !lastBaseLabel.isEmpty()) {
            baseLabelView.setText(lastBaseLabel);
        }
        baseLabelView.setTextColor(BASE);
        frame.addView(baseLabelView, new LinearLayout.LayoutParams(-1, -2));

        panelWidth = dp(242);
        pageContainer = new FrameLayout(this);
        frame.addView(pageContainer, new LinearLayout.LayoutParams(-1, -2));

        mainPage = buildMainPage();
        enemyPage = buildEnemyPage();
        enemyPage.setVisibility(View.GONE);
        costumePage = buildCostumePage();
        costumePage.setVisibility(View.GONE);

        pageContainer.addView(mainPage, new FrameLayout.LayoutParams(-1, -2));
        pageContainer.addView(enemyPage, new FrameLayout.LayoutParams(-1, -2));
        pageContainer.addView(costumePage, new FrameLayout.LayoutParams(-1, -2));

        int maxHeight = panelHeight();
        frame.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int height = Math.min(maxHeight, Math.max(dp(120), frame.getMeasuredHeight()));
        int bubbleX = prefs.getInt("overlayX", dp(18));
        int bubbleY = prefs.getInt("overlayY", dp(120));
        int bcx = bubbleX + dp(29);
        int bcy = bubbleY + dp(29);
        int pw = panelWidth, ph = height;
        int sW, sH;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = windowManager.getCurrentWindowMetrics().getBounds();
            sW = b.width(); sH = b.height();
        } else {
            Point pt = new Point();
            windowManager.getDefaultDisplay().getRealSize(pt);
            sW = pt.x; sH = pt.y;
        }
        int panelX = Math.max(0, Math.min(bcx - pw / 2, sW - pw));
        int panelY = Math.max(0, Math.min(bcy - ph / 2, sH - ph));
        prefs.edit().putInt("overlayX", panelX).putInt("overlayY", panelY).apply();
        addOverlayView(frame, pw, ph, true);
        currentView.setPivotX(pw / 2f);
        currentView.setPivotY(ph / 2f);
        float bubbleScale = dp(58) / (float)pw;
        currentView.setScaleX(bubbleScale);
        currentView.setScaleY(bubbleScale);
        currentView.setAlpha(0f);
        currentView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(250).start();
        scheduleBaseStatusPoll();
        scheduleAttachStatusPoll();

        applyGestureExclusion(header);
        applyGestureExclusion(title);
    }

    private View buildMainPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        int[] toggleLabels = new int[]{
                R.string.frida_tool,
                R.string.shoot,
                R.string.reload,
                R.string.gun_damage,
                R.string.skill_damage,
                R.string.respawn,
                R.string.speed,
                R.string.no_clip,
                R.string.recoil,
                R.string.blackhole,
                R.string.kda_booster,
                R.string.aimbot,
                R.string.assist,
                R.string.all_enemy,
                R.string.exclude_bot,
                R.string.capture_milk,
                R.string.touhou_medley,
        };
        String[] toggleKeys = TOGGLE_KEYS;
        for (int i = 0; i < toggleKeys.length; i++) {
            if (!isToggleVisible(toggleKeys[i])) continue;
            if ("fridaTool".equals(toggleKeys[i])) addFridaToolGroup(panel, getString(toggleLabels[i]));
            else if ("blackHole".equals(toggleKeys[i])) addBlackHoleGroup(panel, getString(toggleLabels[i]));
            else if ("speed".equals(toggleKeys[i])) addSpeedGroup(panel, getString(toggleLabels[i]));
            else if ("aimAssist".equals(toggleKeys[i])) addAimAssistGroup(panel, getString(toggleLabels[i]));
            else if ("damageUpSkill".equals(toggleKeys[i])) addSkillDamageGroup(panel, getString(toggleLabels[i]));
            else if ("touhouMedley".equals(toggleKeys[i])) addTouhouMedleyGroup(panel, getString(toggleLabels[i]));
            else if ("respawn".equals(toggleKeys[i])) addRespawnGroup(panel, getString(toggleLabels[i]));
            else addToggle(panel, getString(toggleLabels[i]), toggleKeys[i]);
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button enemyList = miniButton(getString(R.string.enemy_list));
        enemyList.setTranslationY(shouldShiftEnemyList() ? -dp(5) : 0);
        enemyList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showEnemyPage(true);
            }
        });
        LinearLayout.LayoutParams enemyLp = new LinearLayout.LayoutParams(0, dp(32), 1);
        enemyLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        actions.addView(enemyList, enemyLp);
        addAction(actions, "END APP", "end", 1);
        panel.addView(actions);

        attachStatusView = text("", 12, RED, Typeface.NORMAL);
        attachStatusView.setGravity(Gravity.CENTER);
        attachStatusView.setPadding(0, dp(2), 0, dp(2));
        panel.addView(attachStatusView, new LinearLayout.LayoutParams(-1, -2));

        return scroll;
    }

    private View buildEnemyPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button back = miniButton(getString(R.string.back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMainPage();
            }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dp(32), 1);
        backLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        top.addView(back, backLp);
        root.addView(top);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(6));
        TextView h1 = text(getString(R.string.enemy_header_id), 10, MUTED, Typeface.BOLD);
        TextView h2 = text(getString(R.string.enemy_header_name), 10, MUTED, Typeface.BOLD);
        header.addView(h1, new LinearLayout.LayoutParams(dp(88), dp(22)));
        header.addView(h2, new LinearLayout.LayoutParams(-2, dp(22)));
        enemyRoomView = text(getString(R.string.enemy_room), 10, MUTED, Typeface.BOLD);
        enemyRoomView.setSingleLine(true);
        enemyRoomView.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_UNIFORM);
        enemyRoomView.setAutoSizeTextTypeUniformWithConfiguration(6, 10, 1, TypedValue.COMPLEX_UNIT_SP);
        enemyRoomView.setPadding(dp(8), 0, 0, 0);
        header.addView(enemyRoomView, new LinearLayout.LayoutParams(0, dp(22), 1));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        enemyListContainer = new LinearLayout(this);
        enemyListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(enemyListContainer, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private void showEnemyPage(boolean withRefresh) {
        if (enemyPage == null || mainPage == null) return;
        if (withRefresh) {
            refreshEnemyList();
            currentView.postDelayed(new Runnable() {
                @Override public void run() {
                    refreshEnemyList();
                }
            }, 900);
        }
        showEnemyPageInternal();
        postEnemyListPoll(1500);
    }

    private View buildCostumePage() {
        try {
            costumeClassNames = getResources().getStringArray(R.array.costume_class_names);
        } catch (Throwable ignored) {
            costumeClassNames = null;
        }
        if (costumeClassNames == null || costumeClassNames.length == 0) {
            costumeClassNames = COSTUME_CLASS_NAMES_FALLBACK;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button back = miniButton(getString(R.string.back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMainPage();
            }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(0, dp(32), 1);
        backLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        top.addView(back, backLp);
        root.addView(top);

        TextView title = text(getString(R.string.frida_costumes_title), 11, MUTED, Typeface.BOLD);
        title.setPadding(dp(4), dp(2), dp(4), dp(4));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        costumeListContainer = new LinearLayout(this);
        costumeListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(costumeListContainer, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp(2), dp(3), dp(2), dp(3));
        costumeConfirmBtn = miniButton(getString(R.string.frida_costumes_confirm));
        costumeConfirmBtn.setEnabled(false);
        costumeConfirmBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (selectedCostumeClass <= 0) return;
                final int cid = selectedCostumeClass;
                costumeConfirmBtn.setEnabled(false);
                runFridaTool("getAllCostumes", String.valueOf(cid));
                costumeConfirmBtn.setEnabled(true);
            }
        });
        bottom.addView(costumeConfirmBtn, new LinearLayout.LayoutParams(-1, dp(34)));
        root.addView(bottom);

        refreshCostumeList();
        return root;
    }

    private void refreshCostumeList() {
        if (costumeListContainer == null) return;
        costumeListContainer.removeAllViews();
        costumeRows.clear();
        for (int i = 0; i < costumeClassNames.length; i++) {
            final int cid = i + 1;
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(2), dp(3), dp(2), dp(3));
            row.setClickable(true);
            TextView idText = text(String.valueOf(cid), 11, TEXT, Typeface.BOLD);
            idText.setGravity(Gravity.CENTER);
            row.addView(idText, new LinearLayout.LayoutParams(dp(36), dp(30)));
            TextView nameText = text(costumeClassNames[i], 11, TEXT, Typeface.BOLD);
            nameText.setPadding(dp(8), 0, 0, 0);
            row.addView(nameText, new LinearLayout.LayoutParams(0, dp(30), 1));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedCostumeClass = cid;
                    highlightCostumeRow();
                    if (costumeConfirmBtn != null) costumeConfirmBtn.setEnabled(true);
                }
            });
            costumeRows.add(row);
            costumeListContainer.addView(row);
        }
        highlightCostumeRow();
    }

    private void highlightCostumeRow() {
        for (int i = 0; i < costumeRows.size(); i++) {
            LinearLayout row = costumeRows.get(i);
            if (i + 1 == selectedCostumeClass) {
                row.setBackground(round(Color.rgb(58, 88, 122), dp(7), Color.TRANSPARENT, 0));
            } else {
                row.setBackground(round(Color.rgb(38, 48, 68), dp(7), Color.TRANSPARENT, 0));
            }
        }
    }

    private void showCostumePage() {
        if (costumePage == null || mainPage == null) return;
        if (enemyPage != null && enemyPage.getVisibility() == View.VISIBLE) {
            enemyPage.setVisibility(View.GONE);
        }
        costumePage.setVisibility(View.VISIBLE);
        costumePage.setTranslationX(panelWidth);
        mainPage.setTranslationX(0);
        costumePage.animate().translationX(0).setDuration(180).start();
        mainPage.animate().translationX(-panelWidth).setDuration(180).start();
    }


    private void showEnemyPageInternal() {
        enemyPage.setVisibility(View.VISIBLE);
        enemyPage.setTranslationX(panelWidth);
        mainPage.setTranslationX(0);
        enemyPage.animate().translationX(0).setDuration(180).start();
        mainPage.animate().translationX(-panelWidth).setDuration(180).start();
    }

    private void showMainPage() {
        cancelEnemyListPoll();
        if (mainPage == null) return;
        if (enemyPage != null && enemyPage.getVisibility() == View.VISIBLE) {
            enemyPage.animate().translationX(panelWidth).setDuration(180).withEndAction(new Runnable() {
                @Override public void run() {
                    enemyPage.setVisibility(View.GONE);
                }
            }).start();
        }
        if (costumePage != null && costumePage.getVisibility() == View.VISIBLE) {
            costumePage.animate().translationX(panelWidth).setDuration(180).withEndAction(new Runnable() {
                @Override public void run() {
                    costumePage.setVisibility(View.GONE);
                }
            }).start();
        }
        mainPage.animate().translationX(0).setDuration(180).start();
    }

    private void refreshEnemyList() {
        if (enemyListContainer == null) return;
        enemyListContainer.removeAllViews();

        if (enemyRoomView != null) {
            long room = MemOps.getEnemyRoomBase();
            if (room != 0) {
                enemyRoomView.setText(getString(R.string.enemy_room) + ": " + Long.toHexString(room).toUpperCase());
            } else {
                enemyRoomView.setText(getString(R.string.enemy_room) + ": ---");
            }
        }

        if (MemOps.isConnected()) {
            int count = MemOps.getEnemyCount();
            if (count == 0) {
                TextView empty = text(getString(R.string.scanning), 12, MUTED, Typeface.BOLD);
                empty.setPadding(0, dp(8), 0, dp(6));
                enemyListContainer.addView(empty);
                return;
            }
            for (int i = 0; i < count; i++) {
                final long id = MemOps.getEnemyId(i);
                String name = MemOps.getEnemyName(i);
                boolean enabled = MemOps.getEnemyEnabled(i);
                enemyListContainer.addView(enemyRow(i, id, name, enabled));
            }
        }
    }

    private View enemyRow(final int index, final long id, String name, boolean enabled) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView idText = text(String.valueOf(id), 11, TEXT, Typeface.BOLD);
        idText.setSingleLine(true);
        idText.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(idText, new LinearLayout.LayoutParams(dp(88), dp(32)));
        TextView nameText = text(name, 11, TEXT, Typeface.BOLD);
        nameText.setPadding(dp(8), 0, 0, 0);
        row.addView(nameText, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        tintSwitch(sw);
        updatingEnemySwitches = true;
        try {
            sw.setChecked(enabled);
        } finally {
            updatingEnemySwitches = false;
        }
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingEnemySwitches) return;
                if (MemOps.isConnected()) {
                    MemOps.setEnemyEnabled(index, isChecked);
                }
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        return row;
    }

    private boolean isToggleVisible(String key) {
        return prefs.getBoolean("show_" + key, true);
    }

    private void applyHiddenTogglesOff() {
        for (String key : TOGGLE_KEYS) {
            if (!isToggleVisible(key)) {
                states.put(key, false);
            }
        }
    }

    private void addToggle(LinearLayout parent, String label, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));
        TextView bullet = text("\u30FB", 13, TEXT, Typeface.BOLD);
        bullet.setGravity(Gravity.CENTER);
        row.addView(bullet, new LinearLayout.LayoutParams(dp(18), dp(32)));
        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get(key)));
        switchViews.put(key, sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put(key, isChecked);
                if (isChecked && "kdaBooster".equals(key)) {
                    setBlackHoleHackMode(null);
                }
                if (isChecked && isExclusiveMode(key)) {
                    setExclusiveMode(key);
                }
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
    }

    private void addBlackHoleGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("blackHole")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("blackHole")));
        switchViews.put("blackHole", sw);
        tintSwitch(sw);
        final LinearLayout hackContainer = new LinearLayout(this);
        hackContainer.setOrientation(LinearLayout.VERTICAL);
        hackContainer.setVisibility(Boolean.TRUE.equals(states.get("blackHole")) ? View.VISIBLE : View.GONE);
        addBlackHoleHackToggle(hackContainer, getString(R.string.blackhole_bava_hack), "bavaHack", null);
        addBlackHoleOptionToggle(hackContainer, getString(R.string.fixed), "blackHoleFixed");
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                animateBlackHoleHackMenu(hackContainer, arrow, isChecked);
                if (updatingSwitches) return;
                states.put("blackHole", isChecked);
                if (isChecked) setExclusiveMode("blackHole");
                else setBlackHoleHackMode(null);
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(hackContainer);
    }

    private void addRespawnGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("respawnGroup")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("respawnGroup")));
        switchViews.put("respawnGroup", sw);
        tintSwitch(sw);
        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setVisibility(Boolean.TRUE.equals(states.get("respawnGroup")) ? View.VISIBLE : View.GONE);
        addRespawnTimeToggle(container, getString(R.string.respawn_time_reduce));
        addRespawnInstantToggle(container, getString(R.string.respawn_instant));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                animateBlackHoleHackMenu(container, arrow, isChecked);
                if (updatingSwitches) return;
                states.put("respawnGroup", isChecked);
                if (!isChecked) {
                    states.put("respawn", false);
                    states.put("respawnInstant", false);
                    stopAutoRespawn();
                    updatingSwitches = true;
                    try {
                        Switch swTime = switchViews.get("respawn");
                        if (swTime != null) swTime.setChecked(false);
                        Switch swInstant = switchViews.get("respawnInstant");
                        if (swInstant != null) swInstant.setChecked(false);
                    } finally {
                        updatingSwitches = false;
                    }
                    saveAndWrite("sync");
                } else {
                    saveStateOnly();
                }
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(container);
    }

    private void addRespawnTimeToggle(LinearLayout parent, String label) {
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(dp(18), dp(2), 0, dp(2));
        TextView t = text(label, 12, TEXT, Typeface.BOLD);
        r2.addView(t, new LinearLayout.LayoutParams(0, dp(30), 1));
        final Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("respawn")));
        switchViews.put("respawn", sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put("respawn", isChecked);
                saveAndWrite("sync");
            }
        });
        r2.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        parent.addView(r2);
    }

    private void startAutoRespawn() {
        autoRespawnOn = true;
        handler.removeCallbacks(autoRespawnRunnable);
        handler.postDelayed(autoRespawnRunnable, AUTO_RESPAWN_POLL_MS);
    }
    private void stopAutoRespawn() {
        autoRespawnOn = false;
        handler.removeCallbacks(autoRespawnRunnable);
    }

    private void addRespawnInstantToggle(LinearLayout parent, String label) {
        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        r2.setPadding(dp(18), dp(2), 0, dp(2));
        TextView t = text(label, 12, TEXT, Typeface.BOLD);
        r2.addView(t, new LinearLayout.LayoutParams(0, dp(30), 1));
        final Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        boolean on = Boolean.TRUE.equals(states.get("respawnInstant"));
        if (on && !autoRespawnOn) {
            startAutoRespawn();
        } else if (!on && autoRespawnOn) {
            stopAutoRespawn();
        }
        sw.setChecked(on);
        switchViews.put("respawnInstant", sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put("respawnInstant", isChecked);
                if (isChecked) startAutoRespawn(); else stopAutoRespawn();
                saveStateOnly();
            }
        });
        boolean curInstant = Boolean.TRUE.equals(states.get("respawnInstant"));
        if (curInstant && !autoRespawnOn) startAutoRespawn(); else if (!curInstant && autoRespawnOn) stopAutoRespawn();
        r2.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        parent.addView(r2);
    }

    private void addFridaToolGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("fridaTool")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("fridaTool")));
        switchViews.put("fridaTool", sw);
        tintSwitch(sw);

        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(Boolean.TRUE.equals(states.get("fridaTool")) ? View.VISIBLE : View.GONE);

        {
            LinearLayout loopRow = new LinearLayout(this);
            loopRow.setGravity(Gravity.CENTER_VERTICAL);
            loopRow.setPadding(dp(2), dp(3), dp(2), dp(3));

            TextView loopDot = text("\u30FB", 13, TEXT, Typeface.BOLD);
            loopDot.setGravity(Gravity.CENTER);
            loopRow.addView(loopDot, new LinearLayout.LayoutParams(dp(18), dp(32)));

            TextView loopLabel = text(getString(R.string.kick_loop), 13, TEXT, Typeface.BOLD);
            loopLabel.setSingleLine(true);
            loopRow.addView(loopLabel, new LinearLayout.LayoutParams(0, dp(32), 1));

            kickLoopTargetView = text("-", 10, NEON, Typeface.BOLD);
            kickLoopTargetView.setSingleLine(true);
            kickLoopTargetView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            loopRow.addView(kickLoopTargetView, new LinearLayout.LayoutParams(dp(80), dp(30)));

            kickLoopSwitch = new Switch(this);
            kickLoopSwitch.setMinWidth(dp(42));
            boolean loopOn = Boolean.TRUE.equals(states.get("kickLoop"));
            if (loopOn && !kickLoopOn) startKickLoop();
            else if (!loopOn && kickLoopOn) stopKickLoop();
            else updateKickLoopTargetView();
            kickLoopSwitch.setChecked(loopOn);
            tintSwitch(kickLoopSwitch);
            kickLoopSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (updatingSwitches) return;
                    setKickLoop(isChecked);
                }
            });
            loopRow.addView(kickLoopSwitch, new LinearLayout.LayoutParams(dp(54), dp(30)));
            detail.addView(loopRow);
        }

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        addFridaUiButton(row1, getString(R.string.frida_enemy_kick), "enemyKick");
        addFridaUiButton(row1, getString(R.string.frida_all_kick), "allKick");
        addFridaUiButton(row1, getString(R.string.frida_esco), "esco");
        detail.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        addFridaUiButton(row2, getString(R.string.frida_bot_kick), "botKick");
        addFridaUiButton(row2, getString(R.string.frida_match_reset), "matchReset");
        detail.addView(row2);

        LinearLayout rowEm = new LinearLayout(this);
        rowEm.setOrientation(LinearLayout.HORIZONTAL);
        addFridaUiButton(rowEm, getString(R.string.frida_electric), "electric");
        addFridaUiButton(rowEm, getString(R.string.frida_mago), "mago");
        detail.addView(rowEm);

        LinearLayout rowKc = new LinearLayout(this);
        rowKc.setOrientation(LinearLayout.HORIZONTAL);
        addFridaUiButton(rowKc, getString(R.string.frida_buff_on_khaos), "buffOnKhaos");
        addFridaUiButton(rowKc, getString(R.string.frida_create_choo_choo_buff), "createChooChooBuff");
        detail.addView(rowKc);

        LinearLayout rowWm = new LinearLayout(this);
        rowWm.setOrientation(LinearLayout.HORIZONTAL);
        addFridaUiButton(rowWm, getString(R.string.frida_buff_on_wheelleg), "buffOnWheelleg");
        addFridaUiButton(rowWm, getString(R.string.frida_mix), "mix");
        detail.addView(rowWm);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        row3.setPadding(dp(2), dp(3), dp(2), dp(3));

        final EditText nicknameInput = new EditText(this);
        nicknameInput.setSingleLine(true);
        nicknameInput.setHint(getString(R.string.frida_nickname_hint));
        nicknameInput.setHintTextColor(MUTED);
        nicknameInput.setTextColor(TEXT);
        nicknameInput.setTextSize(12);
        nicknameInput.setTypeface(Typeface.DEFAULT_BOLD);
        nicknameInput.setPadding(dp(8), 0, dp(8), 0);
        nicknameInput.setBackground(round(Color.rgb(45, 56, 77), dp(8), 0, 0));
        applyTextStroke(nicknameInput);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(32), 2.2f);
        row3.addView(nicknameInput, inputLp);

        Button change = miniButton(getString(R.string.frida_change));
        change.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String name = nicknameInput.getText() == null ? "" : nicknameInput.getText().toString().trim();
                runFridaTool("nickChange", name);
            }
        });
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(dp(62), dp(32));
        changeLp.setMargins(dp(4), 0, 0, 0);
        row3.addView(change, changeLp);

        magoBuffBtn = miniButton(getString(R.string.frida_mago_buff));
        magoBuffBtn.setAllCaps(false);
        magoBuffBtn.setSingleLine(true);
        magoBuffBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                magoBuffOn = !magoBuffOn;
                runFridaTool("magoBuff", magoBuffOn ? "on" : "off");
                styleAutoBtn(magoBuffBtn, magoBuffOn);
            }
        });

        LinearLayout.LayoutParams magoBuffLp = new LinearLayout.LayoutParams(-1, dp(32));
        magoBuffLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        detail.addView(magoBuffBtn, magoBuffLp);
        styleAutoBtn(magoBuffBtn, magoBuffOn);
        detail.addView(row3);

        Button costumeBtn = miniButton(getString(R.string.frida_costumes_button));
        costumeBtn.setAllCaps(false);
        costumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showCostumePage();
            }
        });
        LinearLayout.LayoutParams costumeLp = new LinearLayout.LayoutParams(-1, dp(32));
        costumeLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        detail.addView(costumeBtn, costumeLp);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                animateBlackHoleHackMenu(detail, arrow, isChecked);
                if (updatingSwitches) return;
                states.put("fridaTool", isChecked);
                saveStateOnly();
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(detail);
    }

    private void addFridaUiButton(LinearLayout parent, String label, final String action) {
        Button button = miniButton(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String a;
                if ("electric".equals(action) || "mago".equals(action)
                        || "buffOnKhaos".equals(action) || "createChooChooBuff".equals(action)
                        || "buffOnWheelleg".equals(action) || "mix".equals(action)) a = enabledSlotsArg();
                else a = null;
                runFridaTool(action, a);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        parent.addView(button, lp);
    }

    private void styleAutoBtn(Button btn, boolean on) {
        if (btn == null) return;
        if (on) {
            btn.setBackground(round(Color.rgb(58, 88, 122), dp(8), 0, 0));
            btn.setTextColor(Color.rgb(190, 225, 255));
        } else {
            btn.setBackground(miniButtonBackground());
            btn.setTextColor(TEXT);
        }
    }

    private String fridaKickArg(String action) {
        if (!"allKick".equals(action) && !"enemyKick".equals(action)) return null;
        if (!MemOps.isConnected()) return null;
        StringBuilder sb = new StringBuilder();
        int count = MemOps.getEnemyCount();
        for (int i = 0; i < count; i++) {
            if (!MemOps.getEnemyEnabled(i)) {
                int slot = MemOps.getEnemySlot(i);
                if (slot >= 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(slot);
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 敵リストで有効 (ON) になっているスロットの CSV */
    private String enabledSlotsArg() {
        StringBuilder sb = new StringBuilder();
        int count = MemOps.getEnemyCount();
        for (int i = 0; i < count; i++) {
            if (MemOps.getEnemyEnabled(i)) {
                int slot = MemOps.getEnemySlot(i);
                if (slot >= 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(slot);
                }
            }
        }
        return sb.toString();
    }

    private void runFridaTool(final String action, final String arg) {
        cancelOngoingTouches();
        recordKickPress(action, arg);
        FridaTool.init(this);
        FridaTool.run(action, arg, new FridaTool.Callback() {
            @Override public void onResult(final boolean ok, final String message) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        String text;
                        try {
                            text = (ok ? "" : getString(R.string.toast_frida_error)) + translateFridaMessage(message);
                        } catch (Throwable t) {
                            text = (message != null && !message.isEmpty()) ? message : "";
                        }
                        Toast.makeText(FloatingMenuService.this, text, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private String translateFridaMessage(String msg) {
        if (msg == null || !msg.startsWith("MSG:")) return msg == null ? "" : msg;
        String body = msg.substring(4);
        int paren = body.indexOf('(');
        if (paren > 0) body = body.substring(0, paren);
        int colon = body.indexOf(':');
        String code = colon > 0 ? body.substring(0, colon) : body;
        String arg = colon > 0 ? body.substring(colon + 1) : null;
        switch (code) {
            case "ENEMY_KICK_OK": return getString(R.string.frida_enemy_kick_ok);
            case "ALL_KICK_OK": return getString(R.string.frida_all_kick_ok);
            case "ESCO_DONE": return getString(R.string.frida_esco_done);
            case "BOT_KICK_OK": return getString(R.string.frida_bot_kick_ok);
            case "MATCH_RESET_OK": return getString(R.string.frida_match_reset_ok);
            case "NICK_CHANGE_OK": return getString(R.string.frida_nick_change_ok);
            case "MODULE_NOT_FOUND": return getString(R.string.frida_module_not_found);
            case "EXPORT_NOT_FOUND": return arg != null ? getString(R.string.frida_export_not_found, arg) : getString(R.string.frida_export_not_found);
            case "SELF_INFO_NULL": return getString(R.string.frida_self_info_null);
            case "FRIDA_EXECUTED": return getString(R.string.frida_executed);
            case "FRIDA_FAILED": return getString(R.string.frida_failed, parseIntArg(arg, 0));
            case "NO_TARGETS": return getString(R.string.frida_no_targets);
            case "BUFF_ON_KHAOS_OK": return getString(R.string.frida_buff_on_khaos_ok);
            case "CHOO_CHOO_BUFF_OK": return getString(R.string.frida_choo_choo_buff_ok);
            case "BUFF_ON_WHEELLEG_OK": return getString(R.string.frida_buff_on_wheelleg_ok);
            case "MIX_OK": return getString(R.string.frida_mix_ok);
            case "COSTUMES_ALL_UNLOCKED":
            case "COSTUMES_ALL_UNLOCKED_OK": return getString(R.string.frida_costumes_ok);
            case "NO_CLASS": return getString(R.string.frida_costumes_no_class);
            case "MAGO_BUFF_ON_OK": return getString(R.string.frida_mago_buff_on);
            case "MAGO_BUFF_OFF_OK": return getString(R.string.frida_mago_buff_off);
            default: return msg;
        }
    }

    private int parseIntArg(String arg, int def) {
        if (arg == null) return def;
        try { return Integer.parseInt(arg.trim()); } catch (NumberFormatException e) { return def; }
    }

    private boolean checkGameVersionChanged() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo("com.gameparadiso.milkchoco", 0);
            String current = pi.versionName != null ? pi.versionName : "";
            String last = prefs.getString("lastGameVersion", "");
            if (current.isEmpty()) return false;
            if (last.isEmpty()) {
                prefs.edit().putString("lastGameVersion", current).apply();
                return false;
            }
            boolean changed = !current.equals(last);
            if (changed) prefs.edit().putString("lastGameVersion", current).apply();
            return changed;
        } catch (Exception e) {
            return false;
        }
    }

    private void showFridaShield(String message) {
        if (fridaShieldView != null || windowManager == null) return;
        FrameLayout shield = new FrameLayout(this);
        shield.setBackgroundColor(Color.argb(150, 10, 14, 24));
        TextView label = new TextView(this);
        label.setText(message != null ? message : "");
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        shield.addView(label, lp);
        shield.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                return true;
            }
        });
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams sp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        sp.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            windowManager.addView(shield, sp);
            fridaShieldView = shield;
        } catch (Exception e) {
            Log.e("MtoolShield", "addView failed: " + e.getMessage(), e);
            fridaShieldView = null;
        }
    }

    private void hideFridaShield() {
        if (fridaShieldView == null || windowManager == null) return;
        View view = fridaShieldView;
        fridaShieldView = null;
        try {
            windowManager.removeView(view);
        } catch (Exception ignored) {
        }
    }

    private void cancelOngoingTouches() {
        try {
            Process p = new ProcessBuilder("su", "-c", "service call input 57")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }

    private void animateBlackHoleHackMenu(final View menu, TextView arrow, boolean show) {
        arrow.setText(show ? "v" : ">");
        if (show) {
            menu.setAlpha(0.0f);
            menu.setTranslationY(-dp(6));
            menu.setVisibility(View.VISIBLE);
            updatePanelHeight();
            menu.animate().alpha(1.0f).translationY(0).setDuration(160).start();
        } else {
            menu.animate().alpha(0.0f).translationY(-dp(6)).setDuration(130).withEndAction(new Runnable() {
                @Override public void run() {
                    menu.setVisibility(View.GONE);
                    menu.setAlpha(1.0f);
                    menu.setTranslationY(0);
                    updatePanelHeight();
                }
            }).start();
        }
    }

    private void updatePanelHeight() {
        if (currentView == null || params == null || windowManager == null || panelWidth <= 0) return;
        currentView.post(new Runnable() {
            @Override public void run() {
                int maxHeight = panelHeight();
                currentView.measure(
                        View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
                int targetHeight = Math.min(maxHeight, Math.max(dp(120), currentView.getMeasuredHeight()));
                animatePanelHeight(targetHeight);
            }
        });
    }

    private void animatePanelHeight(int targetHeight) {
        if (params == null || currentView == null || windowManager == null) return;
        int startHeight = params.height;
        if (startHeight == targetHeight) return;
        if (panelHeightAnimator != null) panelHeightAnimator.cancel();
        panelHeightAnimator = android.animation.ValueAnimator.ofInt(startHeight, targetHeight);
        panelHeightAnimator.setDuration(160);
        panelHeightAnimator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                if (params == null || currentView == null || windowManager == null) return;
                params.height = (Integer) animation.getAnimatedValue();
                try {
                    windowManager.updateViewLayout(currentView, params);
                } catch (Exception ignored) {
                }
            }
        });
        panelHeightAnimator.start();
    }

    private void addBlackHoleHackToggle(LinearLayout parent, String label, final String key, final String otherKey) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(2), 0, dp(2));
        TextView name = text(label, 12, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get(key)));
        switchViews.put(key, sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingBlackHoleHackSwitches) return;
                states.put(key, isChecked);
                if (isChecked) {
                    setBlackHoleHackMode(key);
                }
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        parent.addView(row);
    }

    private void addBlackHoleOptionToggle(LinearLayout parent, String label, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(2), 0, dp(2));
        TextView name = text(label, 12, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get(key)));
        switchViews.put(key, sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put(key, isChecked);
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        parent.addView(row);
    }

    private void setBlackHoleHackMode(String activeKey) {
        states.put("bavaHack", "bavaHack".equals(activeKey));
        if (activeKey != null) states.put("kdaBooster", false);
        updatingBlackHoleHackSwitches = true;
        updatingSwitches = true;
        try {
            for (String key : new String[]{"bavaHack", "kdaBooster"}) {
                Switch sw = switchViews.get(key);
                if (sw != null) sw.setChecked(Boolean.TRUE.equals(states.get(key)));
            }
        } finally {
            updatingSwitches = false;
            updatingBlackHoleHackSwitches = false;
        }
    }

    private void addSpeedGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("speed")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("speed")));
        switchViews.put("speed", sw);
        tintSwitch(sw);

        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(Boolean.TRUE.equals(states.get("speed")) ? View.VISIBLE : View.GONE);
        addSeekIndented(detail, "SPD", 1, 15, Math.round(speedMultiplier), "x", 1.0f, 1, new ValueChange() {
            @Override public void changed(int value) {
                speedMultiplier = value;
                saveAndWrite("sync");
            }
        });

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put("speed", isChecked);
                saveAndWrite("sync");
                animateBlackHoleHackMenu(detail, arrow, isChecked);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(detail);
    }

    private void addAimAssistGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("aimAssist")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("aimAssist")));
        switchViews.put("aimAssist", sw);
        tintSwitch(sw);

        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(Boolean.TRUE.equals(states.get("aimAssist")) ? View.VISIBLE : View.GONE);

        final LinearLayout subDetail = new LinearLayout(this);
        subDetail.setOrientation(LinearLayout.VERTICAL);
        boolean disableOn = Boolean.TRUE.equals(states.get("assistDisableSubWeapon"));
        subDetail.setVisibility(disableOn ? View.VISIBLE : View.GONE);

        addOptionToggleIndented(subDetail, getString(R.string.assist_active_time), "assistActiveTime");
        {
            LinearLayout timeRow = new LinearLayout(this);
            timeRow.setGravity(Gravity.CENTER_VERTICAL);
            timeRow.setPadding(dp(18), 0, 0, 0);
            TextView timeLabel = text("TIME", 10, MUTED, Typeface.BOLD);
            timeLabel.setSingleLine(true);
            timeLabel.setMaxLines(1);
            timeRow.addView(timeLabel, new LinearLayout.LayoutParams(dp(36), dp(28)));
            SeekBar timeSeek = new SeekBar(this);
            timeSeek.setMax(90);
            timeSeek.setProgress(Math.max(0, Math.min(90, assistActiveTime - 10)));
            if (Build.VERSION.SDK_INT >= 21) {
                timeSeek.setProgressTintList(ColorStateList.valueOf(NEON));
                timeSeek.setThumbTintList(ColorStateList.valueOf(NEON));
            }
            timeRow.addView(timeSeek, new LinearLayout.LayoutParams(0, dp(28), 1));
            final TextView timeVal = text(String.format("%.2fs/1.00", assistActiveTime / 100.0), 10, TEXT, Typeface.BOLD);
            timeVal.setSingleLine(true);
            timeVal.setMaxLines(1);
            timeVal.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            timeRow.addView(timeVal, new LinearLayout.LayoutParams(-2, dp(28)));
            timeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    int actual = progress + 10;
                    timeVal.setText(String.format("%.2fs/1.00", actual / 100.0));
                    if (fromUser) {
                        assistActiveTime = actual;
                        saveAndWrite("sync");
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) { }
                @Override public void onStopTrackingTouch(SeekBar seekBar) { }
            });
            subDetail.addView(timeRow);
        }

        addOptionToggleIndented(detail, getString(R.string.assist_only_shooting), "assistOnlyShooting");

        {
            LinearLayout dsRow = new LinearLayout(this);
            dsRow.setGravity(Gravity.CENTER_VERTICAL);
            dsRow.setPadding(dp(18), dp(2), 0, dp(2));
            TextView dsName = text(getString(R.string.assist_disable_sub_weapon), 12, TEXT, Typeface.BOLD);
            dsRow.addView(dsName, new LinearLayout.LayoutParams(0, dp(30), 1));
            final Switch dsSw = new Switch(this);
            dsSw.setMinWidth(dp(42));
            dsSw.setChecked(Boolean.TRUE.equals(states.get("assistDisableSubWeapon")));
            switchViews.put("assistDisableSubWeapon", dsSw);
            tintSwitch(dsSw);
            dsSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (updatingSwitches) return;
                    states.put("assistDisableSubWeapon", isChecked);
                    saveAndWrite("sync");
                    subDetail.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
            });
            dsRow.addView(dsSw, new LinearLayout.LayoutParams(dp(54), dp(30)));
            detail.addView(dsRow);
        }
        detail.addView(subDetail);

        addSeekIndented(detail, "LOCK", 1, 100, lockZonePos, "", 1.0f, 1, new ValueChange() {
            @Override public void changed(int value) {
                lockZonePos = value;
                saveAndWrite("sync");
            }
        });
        addSeekIndented(detail, "SMTH", 1, 100, smoothAimPos, "", 1.0f, 1, new ValueChange() {
            @Override public void changed(int value) {
                smoothAimPos = value;
                saveAndWrite("sync");
            }
        });

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                animateBlackHoleHackMenu(detail, arrow, isChecked);
                if (updatingSwitches) return;
                states.put("aimAssist", isChecked);
                if (isChecked) setExclusiveMode("aimAssist");
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(detail);
    }

    private void addSkillDamageGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("damageUpSkill")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("damageUpSkill")));
        switchViews.put("damageUpSkill", sw);
        tintSwitch(sw);

        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(Boolean.TRUE.equals(states.get("damageUpSkill")) ? View.VISIBLE : View.GONE);
        addOptionToggleIndented(detail, getString(R.string.skill_damage_disable_main_weapon), "skillDamageDisableMainWeapon");

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put("damageUpSkill", isChecked);
                saveAndWrite("sync");
                animateBlackHoleHackMenu(detail, arrow, isChecked);
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(detail);
    }

    private void addOptionToggleIndented(LinearLayout parent, String label, final String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(2), 0, dp(2));
        TextView name = text(label, 12, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(30), 1));
        Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get(key)));
        switchViews.put(key, sw);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put(key, isChecked);
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        parent.addView(row);
    }

    private void addTouhouMedleyGroup(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        final TextView arrow = text(Boolean.TRUE.equals(states.get("touhouMedley")) ? "v" : ">", 13, TEXT, Typeface.BOLD);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(32)));

        TextView name = text(label, 13, TEXT, Typeface.BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
        final Switch sw = new Switch(this);
        sw.setMinWidth(dp(42));
        sw.setChecked(Boolean.TRUE.equals(states.get("touhouMedley")));
        switchViews.put("touhouMedley", sw);
        tintSwitch(sw);

        final LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        boolean expanded = Boolean.TRUE.equals(states.get("touhouMedley"));
        detail.setVisibility(expanded ? View.VISIBLE : View.GONE);

        final int[] songResIds = new int[]{
                R.raw.night_of_nights, R.raw.final_flandre, R.raw.bad_apple,
                R.raw.native_faith, R.raw.cirno_math, R.raw.tomboyish_girl,
                R.raw.marisa_stole, R.raw.reisen_orcatia, R.raw.smoke_beyond_moon
        };
        final int[] songLabelIds = new int[]{
                R.string.night_of_nights, R.string.final_flandre, R.string.bad_apple,
                R.string.native_faith, R.string.cirno_math, R.string.tomboyish_girl,
                R.string.marisa_stole, R.string.reisen_orcatia, R.string.smoke_beyond_moon
        };

        final TextView nowPlaying = text(
                currentSongIndex >= 0 ? getString(songLabelIds[currentSongIndex]) : getString(songLabelIds[0]),
                14, GREEN, Typeface.BOLD);
        nowPlaying.setGravity(Gravity.CENTER);
        nowPlaying.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        nowPlaying.setMarqueeRepeatLimit(-1);
        nowPlaying.setSingleLine(true);
        nowPlaying.setSelected(true);
        nowPlaying.setPadding(0, dp(4), 0, dp(2));
        detail.addView(nowPlaying, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout ctrlRow = new LinearLayout(this);
        ctrlRow.setGravity(Gravity.CENTER);
        ctrlRow.setPadding(dp(6), 0, dp(6), 0);

        final Button prevBtn = miniButton("|\u25C0");
        final Button playBtn = miniButton(mediaPlayer != null && mediaPlayer.isPlaying() ? "\u25A0" : "\u25B6");
        final Button nextBtn = miniButton("\u25B6|");

        ctrlRow.addView(prevBtn, mparams(dp(56), dp(32), dp(2), 0, dp(2), 0));
        ctrlRow.addView(playBtn, mparams(dp(56), dp(32), dp(2), 0, dp(2), 0));
        ctrlRow.addView(nextBtn, mparams(dp(56), dp(32), dp(2), 0, dp(2), 0));
        detail.addView(ctrlRow);

        LinearLayout loopRow = new LinearLayout(this);
        loopRow.setGravity(Gravity.CENTER_VERTICAL);
        loopRow.setPadding(dp(6), dp(2), 0, dp(2));
        TextView loopLabel = text(getString(R.string.touhou_loop), 12, TEXT, Typeface.BOLD);
        loopRow.addView(loopLabel, new LinearLayout.LayoutParams(0, dp(30), 1));
        final Switch loopSw = new Switch(this);
        loopSw.setMinWidth(dp(42));
        loopSw.setChecked(Boolean.TRUE.equals(states.get("touhouLoop")));
        switchViews.put("touhouLoop", loopSw);
        tintSwitch(loopSw);
        loopSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingSwitches) return;
                states.put("touhouLoop", isChecked);
                saveAndWrite("sync");
            }
        });
        loopRow.addView(loopSw, new LinearLayout.LayoutParams(dp(54), dp(30)));
        detail.addView(loopRow);

        prevBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int prev = currentSongIndex <= 0 ? songResIds.length - 1 : currentSongIndex - 1;
                playTouhouSong(prev, songResIds, nowPlaying, songLabelIds, playBtn);
            }
        });
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mediaPlayer != null) {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.pause();
                        playBtn.setText("\u25B6");
                    } else {
                        mediaPlayer.start();
                        playBtn.setText("\u25A0");
                    }
                } else {
                    int idx = currentSongIndex >= 0 ? currentSongIndex : 0;
                    playTouhouSong(idx, songResIds, nowPlaying, songLabelIds, playBtn);
                }
            }
        });
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int next = (currentSongIndex + 1) % songResIds.length;
                playTouhouSong(next, songResIds, nowPlaying, songLabelIds, playBtn);
            }
        });

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                animateBlackHoleHackMenu(detail, arrow, isChecked);
                if (updatingSwitches) return;
                states.put("touhouMedley", isChecked);
                if (isChecked) {
                } else {
                    if (mediaPlayer != null) {
                        mediaPlayer.stop();
                        mediaPlayer.release();
                        mediaPlayer = null;
                        currentSongIndex = -1;
                        nowPlaying.setText(getString(songLabelIds[0]));
                        playBtn.setText("\u25B6");
                    }
                }
                saveAndWrite("sync");
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
        parent.addView(row);
        parent.addView(detail);
    }

    private void playTouhouSong(int index, int[] songResIds, TextView nowPlaying, int[] songLabelIds, Button playBtn) {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        try {
            mediaPlayer = MediaPlayer.create(FloatingMenuService.this, songResIds[index]);
            mediaPlayer.start();
            currentSongIndex = index;
            nowPlaying.setText(getString(songLabelIds[index]));
            playBtn.setText("\u25A0");

            final int fi = index;
            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    if (Boolean.TRUE.equals(states.get("touhouLoop"))) {
                        mp.seekTo(0);
                        mp.start();
                    } else {
                        int next = fi + 1;
                        if (next < songResIds.length) {
                            playTouhouSong(next, songResIds, nowPlaying, songLabelIds, playBtn);
                        } else {
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            currentSongIndex = -1;
                            playBtn.setText("\u25B6");
                            nowPlaying.setText(getString(songLabelIds[0]));
                        }
                    }
                }
            });
        } catch (Exception e) {
            android.util.Log.w("MtoolTouhou", "playTouhouSong failed: " + e.getMessage());
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }
        }
    }

    private void addSeekIndented(LinearLayout parent, String label, final int min, final int max, int value,
                                 final String suffix, final float divisor, final int step, final ValueChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), 0, 0, 0);
        TextView name = text(label, 10, MUTED, Typeface.BOLD);
        name.setSingleLine(true);
        name.setMaxLines(1);
        row.addView(name, new LinearLayout.LayoutParams(dp(36), dp(28)));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, value - min)));
        if (Build.VERSION.SDK_INT >= 21) {
            seek.setProgressTintList(ColorStateList.valueOf(NEON));
            seek.setThumbTintList(ColorStateList.valueOf(NEON));
        }
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(28), 1));
        final TextView valueText = text(formatSeekValue(value, min, max, suffix, divisor), 10, TEXT, Typeface.BOLD);
        valueText.setSingleLine(true);
        valueText.setMaxLines(1);
        valueText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(valueText, new LinearLayout.LayoutParams(-2, dp(28)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = progress + min;
                if (step > 1) {
                    actual = (actual / step) * step;
                    if (actual < min) actual = min;
                    if (actual > max) actual = max;
                    seekBar.setProgress(actual - min);
                }
                valueText.setText(formatSeekValue(actual, min, max, suffix, divisor));
                if (fromUser) change.changed(actual);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(row);
    }

    private void addSeek(LinearLayout parent, String label, final int min, final int max, int value,
                         final String suffix, final float divisor, final ValueChange change) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 10, MUTED, Typeface.BOLD);
        name.setSingleLine(true);
        name.setMaxLines(1);
        row.addView(name, new LinearLayout.LayoutParams(dp(36), dp(28)));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(Math.max(0, Math.min(max - min, value - min)));
        if (Build.VERSION.SDK_INT >= 21) {
            seek.setProgressTintList(ColorStateList.valueOf(NEON));
            seek.setThumbTintList(ColorStateList.valueOf(NEON));
        }
        row.addView(seek, new LinearLayout.LayoutParams(0, dp(28), 1));
        final TextView valueText = text(formatSeekValue(value, min, max, suffix, divisor), 10, TEXT, Typeface.BOLD);
        valueText.setSingleLine(true);
        valueText.setMaxLines(1);
        valueText.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(valueText, new LinearLayout.LayoutParams(-2, dp(28)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int actual = progress + min;
                valueText.setText(formatSeekValue(actual, min, max, suffix, divisor));
                if (fromUser) change.changed(actual);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        parent.addView(row);
    }

    private void addAction(LinearLayout parent, String label, final String action, float weight) {
        Button button = miniButton(label);
        button.setTranslationY(0);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if ("end".equals(action)) stopSelf();
                else if ("hide".equals(action)) showBubble();
                else {
                    saveAndWrite(action);
                    if (!"sync".equals(action)) showBubble();
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        parent.addView(button, lp);
    }

    private Button miniButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setSingleLine(false);
        button.setMinLines(1);
        button.setMaxLines(2);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setLineSpacing(-dp(2), 1.0f);
        button.setGravity(Gravity.CENTER);
        button.setBackground(miniButtonBackground());
        attachMiniButtonPressEffect(button);
        applyTextStroke(button);
        return button;
    }

    private Drawable miniButtonBackground() {
        int normal = Color.rgb(45, 56, 77);
        int pressed = Color.rgb(35, 44, 61);
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_pressed}, round(pressed, dp(8), 0, 0));
        drawable.addState(new int[]{android.R.attr.state_selected}, round(pressed, dp(8), 0, 0));
        drawable.addState(new int[]{}, round(normal, dp(8), 0, 0));
        return drawable;
    }

    private void attachMiniButtonPressEffect(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event == null) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(70).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(110).start();
                }
                return false;
            }
        });
    }

    private LinearLayout.LayoutParams mparams(int w, int h, int ml, int mt, int mr, int mb) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMargins(ml, mt, mr, mb);
        return lp;
    }

    private boolean shouldShiftEnemyList() {
        String chosen = prefs != null ? prefs.getString(LocaleHelper.KEY_LANG, "system") : "system";
        String lang;
        if (chosen == null || chosen.trim().isEmpty() || "system".equalsIgnoreCase(chosen)) {
            lang = Locale.getDefault().getLanguage();
        } else {
            String raw = chosen.trim().replace('_', '-');
            int dash = raw.indexOf('-');
            lang = dash > 0 ? raw.substring(0, dash) : raw;
        }
        return "es".equals(lang) || "pt".equals(lang) || "ru".equals(lang) || "fr".equals(lang) || "vi".equals(lang);
    }

    private void addOverlayView(View view, int width, int height, boolean watchOutside) {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = 0;
        if (!expanded) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (watchOutside) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        params = new WindowManager.LayoutParams(
                width,
                height,
                type,
                flags,
                PixelFormat.TRANSLUCENT);
        if (expanded) {
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        }
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = prefs.getInt("overlayX", dp(18));
        params.y = prefs.getInt("overlayY", dp(120));
        currentView = view;
        clampOverlayPosition(width, height);
        windowManager.addView(currentView, params);
    }

    private void clampOverlayPosition(int width, int height) {
        if (params == null) return;
        int screenWidth;
        int screenHeight;
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = windowManager.getCurrentWindowMetrics().getBounds();
            screenWidth = b.width();
            screenHeight = b.height();
        } else {
            Point size = new Point();
            try {
                windowManager.getDefaultDisplay().getRealSize(size);
            } catch (Exception ignored) {
                size.x = getResources().getDisplayMetrics().widthPixels;
                size.y = getResources().getDisplayMetrics().heightPixels;
            }
            screenWidth = size.x;
            screenHeight = size.y;
        }
        int maxX = Math.max(0, screenWidth - width);
        int maxY = Math.max(0, screenHeight - height);
        params.x = Math.max(0, Math.min(params.x, maxX));
        params.y = Math.max(0, Math.min(params.y, maxY));
    }

    private void removeCurrentView() {
        if (currentView != null) {
            try {
                windowManager.removeView(currentView);
            } catch (Exception ignored) {
            }
            currentView = null;
        }
    }

    private void setupBubbleIcon(ImageView iv) {
        if (prefs == null) { iv.setImageResource(getApplicationInfo().icon); return; }
        String path = prefs.getString("bubbleIconPath", "");
        if (!path.isEmpty()) {
            Bitmap bm = BitmapFactory.decodeFile(path);
            if (bm != null) { iv.setImageBitmap(bm); applyCircleClip(iv); return; }
        }
        iv.setImageResource(getApplicationInfo().icon);
        applyCircleClip(iv);
    }

    private void applyCircleClip(final ImageView iv) {
        if (Build.VERSION.SDK_INT < 21) return;
        iv.post(new Runnable() {
            @Override public void run() {
                iv.setClipToOutline(true);
            }
        });
    }

    private void tintSwitch(Switch sw) {
        if (Build.VERSION.SDK_INT < 21) return;
        int[][] stateSet = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_checked}
        };
        sw.setThumbTintList(new ColorStateList(stateSet, new int[]{NEON, Color.WHITE}));
        sw.setTrackTintList(new ColorStateList(stateSet, new int[]{NEON, RED}));
    }

    private boolean isExclusiveMode(String key) {
        return "aimBot".equals(key) || "aimAssist".equals(key) || "blackHole".equals(key);
    }

    private void setExclusiveMode(String activeKey) {
        states.put("aimBot", "aimBot".equals(activeKey));
        states.put("aimAssist", "aimAssist".equals(activeKey));
        states.put("blackHole", "blackHole".equals(activeKey));
        updatingSwitches = true;
        try {
            for (String key : new String[]{"aimBot", "aimAssist", "blackHole"}) {
                Switch sw = switchViews.get(key);
                if (sw != null) sw.setChecked(Boolean.TRUE.equals(states.get(key)));
            }
        } finally {
            updatingSwitches = false;
        }
    }

    private void saveAndWrite(String action) {
        saveStateOnly();
        writeCommand(action);
    }

    private void pushAllStates() {
        applyHiddenTogglesOff();
        saveStateOnly();
        writeCommand("sync");
    }

    private void saveStateOnly() {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            editor.putBoolean(entry.getKey(), entry.getValue());
        }
        editor.putFloat("speedMultiplier", speedMultiplier);
        editor.putInt("lockZonePos", lockZonePos);
        editor.putInt("smoothAimPos", smoothAimPos);
        editor.putInt("assistActiveTimeValue", assistActiveTime);
        editor.apply();
    }

    private void writeCommand(String action) {
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            MemOps.setToggle(entry.getKey(), entry.getValue());
        }
        MemOps.setSlider("speedMultiplier", (int)speedMultiplier);
        MemOps.setSlider("lockZonePos", lockZonePos);
        MemOps.setSlider("smoothAimPos", smoothAimPos);
        MemOps.setSlider("assistActiveTime", assistActiveTime);
        if (MemOps.isConnected()) {
            Integer mapped = ACTION_MAP.get(action);
            if (mapped != null) {
                MemOps.setAction(mapped, 0);
            }
        }
    }

    private static final Map<String, Integer> ACTION_MAP = new HashMap<>();
    static {
        ACTION_MAP.put("selfScan", MemOps.ACTION_SELF_SCAN);
        ACTION_MAP.put("scanEnemies", MemOps.ACTION_SCAN_ENEMIES);
        ACTION_MAP.put("enemyToggle", MemOps.ACTION_ENEMY_TOGGLE);
        ACTION_MAP.put("capture1", MemOps.ACTION_CAPTURE1);
        ACTION_MAP.put("capture2", MemOps.ACTION_CAPTURE2);
        ACTION_MAP.put("capture3", MemOps.ACTION_CAPTURE3);
        ACTION_MAP.put("capture4", MemOps.ACTION_CAPTURE4);
    }

    private void applyGestureExclusion(final View v) {
        if (Build.VERSION.SDK_INT < 29) return;
        if (v == null) return;
        v.post(new Runnable() {
            @Override public void run() {
                if (v.getWidth() <= 0 || v.getHeight() <= 0) return;
                v.setSystemGestureExclusionRects(
                    java.util.Collections.singletonList(new Rect(0, 0, v.getWidth(), v.getHeight())));
            }
        });
    }

    private void applyTextStroke(TextView v) {
        if (v == null) return;
        int c = Color.WHITE;
        if (prefs != null) c = prefs.getInt("menuTextColor", Color.WHITE);
        v.setTextColor(c);
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, style);
        v.setGravity(Gravity.CENTER_VERTICAL);
        applyTextStroke(v);
        return v;
    }

    private GradientDrawable round(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int panelHeight() {
        int w, h;
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Rect b = windowManager.getCurrentWindowMetrics().getBounds();
                w = b.width(); h = b.height();
            } else {
                Point size = new Point();
                try {
                    windowManager.getDefaultDisplay().getRealSize(size);
                    w = size.x; h = size.y;
                } catch (Exception ignored) {
                    w = getResources().getDisplayMetrics().widthPixels;
                    h = getResources().getDisplayMetrics().heightPixels;
                }
            }
        } else {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        int ref = Math.min(w, h);
        int max = (int) (ref * 0.68f);
        int base = Math.max(dp(220), Math.min(dp(380), max));
        int scaled = (int) (base * 1.2f);
        int cap = (int) (ref * 0.85f);
        return Math.min(scaled, cap);
    }

    private String formatSeekValue(int value, int min, int max, String suffix, float divisor) {
        if (divisor == 1.0f) return value + "/" + max;
        return String.format("%.1f%s/%d", value / divisor, suffix, max / 10);
    }

    private interface ValueChange {
        void changed(int value);
    }

    private class DragTouchListener implements View.OnTouchListener {
        private final boolean bubble;
        private int startX;
        private int startY;
        private float touchX;
        private float touchY;
        private long downAt;

        DragTouchListener(boolean bubble) {
            this.bubble = bubble;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = params.x;
                    startY = params.y;
                    touchX = event.getRawX();
                    touchY = event.getRawY();
                    downAt = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = startX + (int) (event.getRawX() - touchX);
                    params.y = startY + (int) (event.getRawY() - touchY);
                    clampOverlayPosition(params.width, params.height);
                    windowManager.updateViewLayout(currentView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("overlayX", params.x).putInt("overlayY", params.y).apply();
                    int dx = Math.abs(params.x - startX);
                    int dy = Math.abs(params.y - startY);
                    if (bubble && dx < dp(6) && dy < dp(6) && System.currentTimeMillis() - downAt < 250) {
                        showPanel();
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
}
