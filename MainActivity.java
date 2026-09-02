package com.mtool.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import android.app.Dialog;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.ScaleGestureDetector;

public class MainActivity extends Activity {
    private static final String PREFS = "mco_remote";
    private static final int BG = Color.rgb(10, 15, 25);
    private static final int CARD = Color.rgb(17, 24, 39);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(178, 186, 199);
    private static final int PURPLE = Color.rgb(151, 71, 255);
    private static final int GREEN = Color.rgb(0, 255, 70);
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int PICK_ICON_REQUEST = 100;
    private static final int PICK_BG_REQUEST = 101;
    private static final String ICON_FILENAME = "bubble_icon.png";
    private static final String PANEL_BG_FILENAME = "panel_bg.png";

    private ImageView iconPreview;
    private PanelPreviewView panelPreview;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LocaleHelper.apply(this);
        setContentView(buildUi());
        requestStoragePermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleHelper.apply(this);
        setContentView(buildUi());
    }

    private View buildUi() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Defaults.ensure(prefs);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(36), dp(24), dp(24));
        root.setBackgroundColor(BG);
        scroll.setBackgroundColor(BG);

        TextView title = text(getString(R.string.app_title, GameVersion.get(this)), 28, TEXT, Typeface.BOLD);
        root.addView(title);

        TextView body = text(getString(R.string.desc_overlay), 15, MUTED, Typeface.NORMAL);
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
        bodyLp.setMargins(0, dp(12), 0, dp(20));
        root.addView(body, bodyLp);

        Button permission = new Button(this);
        permission.setText(getString(R.string.overlay_permission));
        permission.setTextColor(Color.WHITE);
        permission.setTextSize(15);
        permission.setAllCaps(false);
        permission.setBackground(round(Color.rgb(45, 56, 77), dp(12), BLUE, dp(2)));
        permission.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openOverlaySettings();
            }
        });
        root.addView(permission, new LinearLayout.LayoutParams(-1, dp(48)));

        Button start = new Button(this);
        start.setText(getString(R.string.start_floating_menu));
        start.setTextColor(Color.WHITE);
        start.setTextSize(15);
        start.setAllCaps(false);
        start.setBackground(round(Color.rgb(45, 56, 77), dp(12), GREEN, dp(2)));
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (startOverlay(true)) finish();
            }
        });
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, dp(48));
        startLp.setMargins(0, dp(10), 0, 0);
        root.addView(start, startLp);

        TextView langLabel = text(getString(R.string.language), 15, MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams langLabelLp = new LinearLayout.LayoutParams(-1, -2);
        langLabelLp.setMargins(0, dp(18), 0, dp(6));
        root.addView(langLabel, langLabelLp);

        final String[] langLabels = new String[]{
                getString(R.string.lang_system),
                "English",
                "\u65E5\u672C\u8A9E",
                "\uD55C\uAD6D\uC5B4",
                "Espa\u00F1ol",
                "Portugu\u00EAs",
                "\u0420\u0443\u0441\u0441\u043A\u0438\u0439",
                "Deutsch",
                "Fran\u00E7ais",
                "Italiano",
                "Tagalog",
                "Bahasa Indonesia",
                "\u0939\u093F\u0928\u094D\u0926\u0940",
                "\u0627\u0644\u0639\u0631\u0628\u064A\u0629",
                "Ti\u1EBFng Vi\u1EC7t",
        };
        final String[] langCodes = new String[]{
                "system",
                "en",
                "ja",
                "ko",
                "es",
                "pt",
                "ru",
                "de",
                "fr",
                "it",
                "tl",
                "id",
                "hi",
                "ar",
                "vi",
        };
        final Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, langLabels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(18);
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setPadding(dp(8), dp(8), dp(8), dp(8));
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(18);
                v.setPadding(dp(12), dp(10), dp(12), dp(10));
                return v;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String currentLang = prefs.getString(LocaleHelper.KEY_LANG, "system");
        int initialIndex = 0;
        for (int i = 0; i < langCodes.length; i++) {
            if (langCodes[i].equals(currentLang)) {
                initialIndex = i;
                break;
            }
        }
        spinner.setSelection(initialIndex);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = langCodes[Math.max(0, Math.min(langCodes.length - 1, position))];
                String prev = prefs.getString(LocaleHelper.KEY_LANG, "system");
                if (selected.equals(prev)) return;
                prefs.edit().putString(LocaleHelper.KEY_LANG, selected).apply();
                LocaleHelper.apply(MainActivity.this);
                refreshOverlayUi();
                recreate();
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinner.setBackgroundColor(Color.TRANSPARENT);
        spinner.setPopupBackgroundDrawable(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        LinearLayout spinnerBox = new LinearLayout(this);
        spinnerBox.setPadding(dp(6), dp(2), dp(6), dp(2));
        spinnerBox.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        spinnerBox.addView(spinner, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(spinnerBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView menuLabel = text(getString(R.string.menu_items), 15, MUTED, Typeface.BOLD);
        LinearLayout.LayoutParams menuLabelLp = new LinearLayout.LayoutParams(-1, -2);
        menuLabelLp.setMargins(0, dp(18), 0, dp(6));
        root.addView(menuLabel, menuLabelLp);

        final int[] toggleLabels = new int[]{
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
        final String[] toggleKeys = new String[]{
                "fridaTool",
                "shoot",
                "reload",
                "damageUpGun",
                "damageUpSkill",
                "respawn",
                "speed",
                "noClip",
                "recoil",
                "blackHole",
                "kdaBooster",
                "aimBot",
                "aimAssist",
                "allEnemy",
                "excludeBot",
                "captureMilk",
                "touhouMedley",
        };
        for (int i = 0; i < toggleKeys.length; i++) {
            final String key = toggleKeys[i];
            final String label = getString(toggleLabels[i]);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(6), dp(12), dp(6));
            row.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
            TextView name = text(label, 24, TEXT, Typeface.BOLD);
            name.setIncludeFontPadding(false);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            Switch sw = new Switch(this);
            sw.setChecked(prefs.getBoolean("show_" + key, true));
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    prefs.edit().putBoolean("show_" + key, isChecked).apply();
                    refreshOverlayUi();
                }
            });
            row.addView(sw, new LinearLayout.LayoutParams(-2, -2));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, dp(2), 0, dp(2));
            root.addView(row, rowLp);
        }

        LinearLayout bubbleHeader = new LinearLayout(this);
        bubbleHeader.setClickable(true);
        bubbleHeader.setFocusable(true);
        bubbleHeader.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubbleHeader.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        LinearLayout.LayoutParams bubbleHeaderLp = new LinearLayout.LayoutParams(-1, -2);
        bubbleHeaderLp.setMargins(0, dp(18), 0, 0);
        final TextView bubbleArrow = text("\u25B6", 12, MUTED, Typeface.BOLD);
        bubbleHeader.addView(bubbleArrow, new LinearLayout.LayoutParams(-2, -2));
        TextView bubbleTitle = text(getString(R.string.bubble_icon), 15, MUTED, Typeface.BOLD);
        bubbleTitle.setPadding(dp(8), 0, 0, 0);
        bubbleHeader.addView(bubbleTitle, new LinearLayout.LayoutParams(-1, -2, 1));
        root.addView(bubbleHeader, bubbleHeaderLp);

        final LinearLayout bubbleContent = new LinearLayout(this);
        bubbleContent.setOrientation(LinearLayout.VERTICAL);
        bubbleContent.setPadding(0, dp(6), 0, 0);
        bubbleContent.setVisibility(View.GONE);
        root.addView(bubbleContent);

        final LinearLayout opacityRow = new LinearLayout(this);
        opacityRow.setGravity(Gravity.CENTER_VERTICAL);
        opacityRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        opacityRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        TextView opacityName = text(getString(R.string.bubble_opacity), 16, TEXT, Typeface.BOLD);
        opacityRow.addView(opacityName, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView opacityVal = text(prefs.getInt("bubbleOpacity", 100) + "%", 14, PURPLE, Typeface.BOLD);
        opacityVal.setGravity(Gravity.CENTER);
        opacityRow.addView(opacityVal, new LinearLayout.LayoutParams(-2, -2));
        bubbleContent.addView(opacityRow);

        final SeekBar opacitySeek = new SeekBar(this);
        opacitySeek.setMax(100);
        opacitySeek.setProgress(prefs.getInt("bubbleOpacity", 100));
        if (Build.VERSION.SDK_INT >= 21) {
            opacitySeek.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
            opacitySeek.setThumbTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        }
        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                opacityVal.setText(progress + "%");
                prefs.edit().putInt("bubbleOpacity", progress).apply();
                refreshOverlayUi();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        bubbleContent.addView(opacitySeek);

        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        iconRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        iconRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));

        iconPreview = new ImageView(this);
        iconPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iconPreview.setPadding(dp(1), dp(1), dp(1), dp(1));
        loadIconPreview(prefs);
        iconRow.addView(iconPreview, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout iconBtnRow = new LinearLayout(this);
        iconBtnRow.setOrientation(LinearLayout.VERTICAL);
        iconBtnRow.setPadding(dp(10), 0, 0, 0);

        Button changeIcon = new Button(this);
        changeIcon.setText(getString(R.string.bubble_icon_change));
        changeIcon.setTextColor(Color.WHITE);
        changeIcon.setTextSize(12);
        changeIcon.setAllCaps(false);
        changeIcon.setBackground(round(Color.rgb(45, 56, 77), dp(10), BLUE, dp(1)));
        changeIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, PICK_ICON_REQUEST);
            }
        });
        iconBtnRow.addView(changeIcon, new LinearLayout.LayoutParams(-1, dp(36)));

        Button resetIcon = new Button(this);
        resetIcon.setText(getString(R.string.bubble_icon_reset));
        resetIcon.setTextColor(Color.WHITE);
        resetIcon.setTextSize(12);
        resetIcon.setAllCaps(false);
        resetIcon.setBackground(round(Color.rgb(45, 56, 77), dp(10), Color.rgb(255, 68, 68), dp(1)));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(-1, dp(36));
        resetLp.setMargins(0, dp(4), 0, 0);
        resetIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().putString("bubbleIconPath", "").apply();
                File f = new File(getFilesDir(), ICON_FILENAME);
                if (f.exists()) f.delete();
                loadIconPreview(prefs);
                refreshOverlayUi();
            }
        });
        iconBtnRow.addView(resetIcon, resetLp);

        iconRow.addView(iconBtnRow, new LinearLayout.LayoutParams(0, -2, 1));
        bubbleContent.addView(iconRow);

        final int[] borderColors = new int[]{
                Color.rgb(0, 255, 70), Color.rgb(0, 122, 255), Color.rgb(255, 23, 68),
                Color.WHITE, Color.rgb(255, 214, 0), Color.rgb(151, 71, 255), Color.rgb(0, 229, 255),
                Color.BLACK, Color.rgb(255, 105, 180)
        };

        final LinearLayout borderToggleRow = new LinearLayout(this);
        borderToggleRow.setGravity(Gravity.CENTER_VERTICAL);
        borderToggleRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        borderToggleRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        TextView borderOnName = text(getString(R.string.bubble_border_on), 16, TEXT, Typeface.BOLD);
        borderToggleRow.addView(borderOnName, new LinearLayout.LayoutParams(0, -2, 1));
        final Switch borderSwitch = new Switch(this);
        borderSwitch.setChecked(prefs.getBoolean("bubbleBorderEnabled",
                prefs.getInt("bubbleBorderWidth", 2) > 0));
        borderToggleRow.addView(borderSwitch, new LinearLayout.LayoutParams(-2, -2));
        bubbleContent.addView(borderToggleRow);

        final LinearLayout colorRow = new LinearLayout(this);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        colorRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        colorRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        LinearLayout.LayoutParams colorRowLp = new LinearLayout.LayoutParams(-1, -2);
        colorRowLp.setMargins(0, dp(2), 0, 0);
        int currentBorderColor = prefs.getInt("bubbleBorderColor", borderColors[0]);
        for (int i = 0; i < borderColors.length; i++) {
            final int bc = borderColors[i];
            View dot = new View(this);
            int dotSize = dp(24);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(bc);
            if (bc == currentBorderColor) {
                gd.setStroke(dp(2), Color.WHITE);
            }
            dot.setBackground(gd);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) dotLp.setMargins(dp(8), 0, 0, 0);
            final int index = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt("bubbleBorderColor", bc).apply();
                    refreshOverlayUi();
                    for (int j = 0; j < colorRow.getChildCount(); j++) {
                        View child = colorRow.getChildAt(j);
                        GradientDrawable g = (GradientDrawable) child.getBackground();
                        g.setStroke(0, 0);
                        if (j == index) g.setStroke(dp(2), Color.WHITE);
                        child.setBackground(g);
                    }
                }
            });
            colorRow.addView(dot, dotLp);
        }
        bubbleContent.addView(colorRow, colorRowLp);

        borderSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean("bubbleBorderEnabled", isChecked).apply();
                colorRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                refreshOverlayUi();
            }
        });
        colorRow.setVisibility(borderSwitch.isChecked() ? View.VISIBLE : View.GONE);

        bubbleHeader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean expanded = bubbleContent.getVisibility() == View.VISIBLE;
                if (expanded) {
                    bubbleContent.setVisibility(View.GONE);
                    bubbleArrow.animate().rotation(0).setDuration(200).start();
                } else {
                    bubbleContent.setVisibility(View.VISIBLE);
                    bubbleArrow.animate().rotation(90).setDuration(200).start();
                }
            }
        });

        final int PANEL_DEF = Color.rgb(24, 31, 45);
        LinearLayout panelHeader = new LinearLayout(this);
        panelHeader.setClickable(true);
        panelHeader.setFocusable(true);
        panelHeader.setPadding(dp(12), dp(8), dp(12), dp(8));
        panelHeader.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        LinearLayout.LayoutParams panelHeaderLp = new LinearLayout.LayoutParams(-1, -2);
        panelHeaderLp.setMargins(0, dp(18), 0, 0);
        final TextView panelArrow = text("\u25B6", 12, MUTED, Typeface.BOLD);
        panelHeader.addView(panelArrow, new LinearLayout.LayoutParams(-2, -2));
        TextView panelTitle = text(getString(R.string.panel_background), 15, MUTED, Typeface.BOLD);
        panelTitle.setPadding(dp(8), 0, 0, 0);
        panelHeader.addView(panelTitle, new LinearLayout.LayoutParams(-1, -2, 1));
        root.addView(panelHeader, panelHeaderLp);

        final LinearLayout panelContent = new LinearLayout(this);
        panelContent.setOrientation(LinearLayout.VERTICAL);
        panelContent.setPadding(0, dp(6), 0, 0);
        panelContent.setVisibility(View.GONE);
        root.addView(panelContent);

        LinearLayout panelBtnRow = new LinearLayout(this);
        panelBtnRow.setGravity(Gravity.CENTER_VERTICAL);
        panelBtnRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        panelBtnRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));

        Button changeBgBtn = new Button(this);
        changeBgBtn.setText(getString(R.string.panel_bg_image));
        changeBgBtn.setTextColor(Color.WHITE);
        changeBgBtn.setTextSize(12);
        changeBgBtn.setAllCaps(false);
        changeBgBtn.setBackground(round(Color.rgb(45, 56, 77), dp(8), BLUE, dp(1)));
        changeBgBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("image/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, PICK_BG_REQUEST);
            }
        });
        panelBtnRow.addView(changeBgBtn, new LinearLayout.LayoutParams(0, dp(36), 1f));

        Button resetBgBtn = new Button(this);
        resetBgBtn.setText(getString(R.string.panel_bg_reset));
        resetBgBtn.setTextColor(Color.WHITE);
        resetBgBtn.setTextSize(12);
        resetBgBtn.setAllCaps(false);
        resetBgBtn.setBackground(round(Color.rgb(60, 60, 60), dp(8), 0, 0));
        LinearLayout.LayoutParams resetBgLp = new LinearLayout.LayoutParams(-2, dp(36));
        resetBgLp.setMargins(dp(6), 0, 0, 0);
        resetBgBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().remove("panelBgPath").putInt("panelBgColor", PANEL_DEF)
                    .putFloat("bgPanX", 0).putFloat("bgPanY", 0).putFloat("bgZoom", 1f).apply();
                File f = new File(getFilesDir(), PANEL_BG_FILENAME);
                if (f.exists()) f.delete();
                refreshOverlayUi();
                if (panelPreview != null) panelPreview.loadFromPrefs();
            }
        });
        panelBtnRow.addView(resetBgBtn, resetBgLp);
        panelContent.addView(panelBtnRow);

        panelPreview = new PanelPreviewView(this);
        LinearLayout centerWrap = new LinearLayout(this);
        centerWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        centerWrap.addView(panelPreview, new LinearLayout.LayoutParams(dp(242), -2));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(-1, -2);
        wrapLp.setMargins(0, dp(4), 0, 0);
        panelContent.addView(centerWrap, wrapLp);

        TextView adjustHint = text(getString(R.string.panel_bg_adjust_hint), 11, Color.rgb(178, 186, 199), Typeface.NORMAL);
        adjustHint.setGravity(Gravity.CENTER);
        adjustHint.setPadding(dp(4), dp(4), dp(4), dp(2));
        panelContent.addView(adjustHint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout brightRow = new LinearLayout(this);
        brightRow.setGravity(Gravity.CENTER_VERTICAL);
        brightRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        brightRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        TextView brightName = text(getString(R.string.panel_brightness), 16, TEXT, Typeface.BOLD);
        brightRow.addView(brightName, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView brightVal = text(prefs.getInt("panelBrightness", 0) + "%", 14, PURPLE, Typeface.BOLD);
        brightVal.setGravity(Gravity.CENTER);
        brightRow.addView(brightVal, new LinearLayout.LayoutParams(-2, -2));
        panelContent.addView(brightRow);

        final SeekBar brightSeek = new SeekBar(this);
        brightSeek.setMax(100);
        brightSeek.setProgress(prefs.getInt("panelBrightness", 0));
        if (Build.VERSION.SDK_INT >= 21) {
            brightSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
            brightSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        }
        brightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int p, boolean fromUser) {
                if (!fromUser) return;
                brightVal.setText(p + "%");
                prefs.edit().putInt("panelBrightness", p).apply();
                if (panelPreview != null) panelPreview.setBrightness(p);
                refreshOverlayUi();
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        panelContent.addView(brightSeek);

        LinearLayout blurRow = new LinearLayout(this);
        blurRow.setGravity(Gravity.CENTER_VERTICAL);
        blurRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        blurRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        TextView blurName = text(getString(R.string.panel_blur), 16, TEXT, Typeface.BOLD);
        blurRow.addView(blurName, new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView blurVal = text(prefs.getInt("panelBlur", 0) + "%", 14, PURPLE, Typeface.BOLD);
        blurVal.setGravity(Gravity.CENTER);
        blurRow.addView(blurVal, new LinearLayout.LayoutParams(-2, -2));
        panelContent.addView(blurRow);

        final SeekBar blurSeek = new SeekBar(this);
        blurSeek.setMax(100);
        blurSeek.setProgress(prefs.getInt("panelBlur", 0));
        if (Build.VERSION.SDK_INT >= 21) {
            blurSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));
            blurSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(PURPLE));
        }
        blurSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int p, boolean fromUser) {
                if (!fromUser) return;
                blurVal.setText(p + "%");
                prefs.edit().putInt("panelBlur", p).apply();
                if (panelPreview != null) panelPreview.setBlur(p);
                refreshOverlayUi();
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {}
        });
        panelContent.addView(blurSeek);

        int currentTextColor = prefs.getInt("menuTextColor", Color.WHITE);
        final int[] textColors = new int[]{
                Color.rgb(0, 255, 70), Color.rgb(0, 122, 255), Color.rgb(255, 23, 68),
                Color.WHITE, Color.rgb(255, 214, 0), Color.rgb(151, 71, 255), Color.rgb(0, 229, 255),
                Color.BLACK, Color.rgb(255, 105, 180)
        };
        final LinearLayout textColorRow = new LinearLayout(this);
        textColorRow.setGravity(Gravity.CENTER_VERTICAL);
        textColorRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        textColorRow.setBackground(round(CARD, dp(12), Color.rgb(35, 46, 66), dp(1)));
        LinearLayout.LayoutParams textColorRowLp = new LinearLayout.LayoutParams(-1, -2);
        textColorRowLp.setMargins(0, dp(4), 0, 0);
        for (int i = 0; i < textColors.length; i++) {
            final int tc = textColors[i];
            View dot = new View(this);
            int dotSize = dp(24);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(tc);
            if (tc == currentTextColor) gd.setStroke(dp(2), Color.WHITE);
            dot.setBackground(gd);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSize, dotSize);
            if (i > 0) dotLp.setMargins(dp(6), 0, 0, 0);
            final int index = i;
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    prefs.edit().putInt("menuTextColor", tc).apply();
                    refreshOverlayUi();
                    if (panelPreview != null) panelPreview.loadFromPrefs();
                    for (int j = 0; j < textColorRow.getChildCount(); j++) {
                        View child = textColorRow.getChildAt(j);
                        GradientDrawable g = (GradientDrawable) child.getBackground();
                        g.setStroke(0, 0);
                        if (j == index) g.setStroke(dp(2), Color.WHITE);
                        child.setBackground(g);
                    }
                }
            });
            textColorRow.addView(dot, dotLp);
        }
        panelContent.addView(textColorRow, textColorRowLp);

        panelHeader.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean expanded = panelContent.getVisibility() == View.VISIBLE;
                if (expanded) {
                    panelContent.setVisibility(View.GONE);
                    panelArrow.animate().rotation(0).setDuration(200).start();
                } else {
                    panelContent.setVisibility(View.VISIBLE);
                    panelArrow.animate().rotation(90).setDuration(200).start();
                }
            }
        });

        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private boolean startOverlay(boolean showToast) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            return false;
        }
        Intent intent = new Intent(this, FloatingMenuService.class);
        intent.putExtra("syncNow", true);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        if (showToast) Toast.makeText(this, getString(R.string.toast_floating_started), Toast.LENGTH_SHORT).show();
        return true;
    }

    private void refreshOverlayUi() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            return;
        }
        Intent intent = new Intent(this, FloatingMenuService.class);
        intent.putExtra("refreshUi", true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_ICON_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            showCropDialog(data.getData());
        } else if (requestCode == PICK_BG_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                if (in == null) return;
                Bitmap bm = BitmapFactory.decodeStream(in);
                in.close();
                if (bm == null) return;
                File outFile = new File(getFilesDir(), PANEL_BG_FILENAME);
                FileOutputStream out = new FileOutputStream(outFile);
                bm.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("panelBgPath", outFile.getAbsolutePath()).apply();
                if (panelPreview != null) panelPreview.loadFromPrefs();
                refreshOverlayUi();
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_error) + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showCropDialog(final Uri imageUri) {
        try {
            InputStream in = getContentResolver().openInputStream(imageUri);
            if (in == null) return;
            final Bitmap src = BitmapFactory.decodeStream(in);
            in.close();
            if (src == null) return;

            final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.BLACK);

            final CircleCropView cropView = new CircleCropView(this, src);
            cropView.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));

            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(8), dp(8), dp(8), dp(8));
            bar.setBackgroundColor(Color.rgb(20, 20, 20));

            

            Button cancelBtn = new Button(this);
            cancelBtn.setText("Cancel");
            cancelBtn.setTextColor(Color.WHITE);
            cancelBtn.setTextSize(12);
            cancelBtn.setAllCaps(false);
            cancelBtn.setBackground(round(Color.rgb(60, 60, 60), dp(8), 0, 0));
            cancelBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { dialog.dismiss(); src.recycle(); }
            });
            bar.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(36), 1f));

            Button okBtn = new Button(this);
            okBtn.setText("OK");
            okBtn.setTextColor(Color.WHITE);
            okBtn.setTextSize(12);
            okBtn.setAllCaps(false);
            okBtn.setBackground(round(Color.rgb(0, 122, 255), dp(8), 0, 0));
            LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            okLp.setMargins(dp(6), 0, 0, 0);
            okBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Bitmap cropped = cropView.crop();
                    if (cropped != null) {
                        saveIconBitmap(cropped);
                        cropped.recycle();
                        loadIconPreview(getSharedPreferences(PREFS, MODE_PRIVATE));
                        refreshOverlayUi();
                        Toast.makeText(MainActivity.this, getString(R.string.bubble_icon_change), Toast.LENGTH_SHORT).show();
                    }
                    src.recycle();
                    dialog.dismiss();
                }
            });
            bar.addView(okBtn, okLp);

            root.addView(cropView);
            root.addView(bar);

            dialog.setContentView(root);
            dialog.getWindow().setLayout(-1, -1);
            dialog.show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveIconBitmap(Bitmap bm) {
        try {
            File outFile = new File(getFilesDir(), ICON_FILENAME);
            FileOutputStream out = new FileOutputStream(outFile);
            bm.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.close();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("bubbleIconPath", outFile.getAbsolutePath()).apply();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadIconPreview(SharedPreferences prefs) {
        String path = prefs.getString("bubbleIconPath", "");
        if (!path.isEmpty()) {
            File f = new File(path);
            if (f.exists()) {
                Bitmap bm = BitmapFactory.decodeFile(path);
                if (bm != null) { iconPreview.setImageBitmap(bm); return; }
            }
        }
        File f = new File(getFilesDir(), ICON_FILENAME);
        if (f.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm != null) { iconPreview.setImageBitmap(bm); return; }
        }
        iconPreview.setImageResource(getApplicationInfo().icon);
    }

    private class CircleCropView extends View {
        private final Bitmap src;
        private float panX, panY;
        private float zoom = 1f;
        private float lastX, lastY;
        private float lastDist = -1f;
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path overlayPath = new Path();
        private final RectF dst = new RectF();
        private float radius;

        CircleCropView(Context context, Bitmap bitmap) {
            super(context);
            this.src = bitmap;
            overlayPaint.setColor(0x99000000);
            borderPaint.setColor(Color.WHITE);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));
            bitmapPaint.setFilterBitmap(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            radius = Math.min(cx, cy) - dp(32);

            float imgW = src.getWidth() * zoom;
            float imgH = src.getHeight() * zoom;
            dst.set(cx - imgW / 2f + panX, cy - imgH / 2f + panY,
                    cx + imgW / 2f + panX, cy + imgH / 2f + panY);
            canvas.drawBitmap(src, null, dst, bitmapPaint);

            overlayPath.rewind();
            overlayPath.addCircle(cx, cy, radius, Path.Direction.CW);
            overlayPath.setFillType(Path.FillType.INVERSE_WINDING);
            canvas.drawPath(overlayPath, overlayPaint);

            canvas.drawCircle(cx, cy, radius, borderPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int pc = event.getPointerCount();
            if (pc == 1) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX();
                        lastY = event.getY();
                        lastDist = -1f;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panX += event.getX() - lastX;
                        panY += event.getY() - lastY;
                        lastX = event.getX();
                        lastY = event.getY();
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lastDist = -1f;
                        return true;
                }
                return super.onTouchEvent(event);
            }

            if (pc >= 2) {
                float dx = event.getX(0) - event.getX(1);
                float dy = event.getY(0) - event.getY(1);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (lastDist < 0) {
                    lastDist = dist;
                } else {
                    float factor = dist / lastDist;
                    zoom *= factor;
                    if (zoom < 0.3f) zoom = 0.3f;
                    if (zoom > 5f) zoom = 5f;
                    lastDist = dist;
                    invalidate();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }

        Bitmap crop() {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            int size = (int) (radius * 2);
            if (size <= 0) return null;
            Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Path path = new Path();
            path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW);
            c.clipPath(path);

            float imgW = src.getWidth() * zoom;
            float imgH = src.getHeight() * zoom;
            Matrix m = new Matrix();
            m.postScale(zoom, zoom);
            m.postTranslate(cx - imgW / 2f + panX, cy - imgH / 2f + panY);
            m.postTranslate(-(cx - radius), -(cy - radius));
            m.postScale(size / (2 * radius), size / (2 * radius));
            c.drawBitmap(src, m, bitmapPaint);
            return out;
        }
    }

    private class PanelPreviewView extends LinearLayout {
        private Bitmap src, blurred;
        private int brightness, blurLevel, textColor = Color.WHITE;
        private float bgPanX, bgPanY, bgZoom = 1f;
        private float lastX, lastY, lastDist = -1f;
        private static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 5f;
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF tmpRect = new RectF();

        PanelPreviewView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(10), dp(8), dp(10), dp(10));
            setWillNotDraw(false);
            bitmapPaint.setFilterBitmap(true);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1));
            borderPaint.setColor(Color.rgb(59, 70, 92));

            TextView title = text("Mtool Ver " + GameVersion.get(getContext()), 22, TEXT, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            title.setBackground(round(Color.rgb(35, 46, 66), dp(9), Color.rgb(72, 85, 110), dp(1)));
            addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

            TextView baseLabel = text("ID: XXXXX  Base: 0x1234", 13, Color.rgb(0, 205, 182), Typeface.BOLD);
            baseLabel.setTag("baseLabel");
            baseLabel.setGravity(Gravity.CENTER);
            baseLabel.setSingleLine(true);
            baseLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            baseLabel.setPadding(0, 0, 0, dp(1));
            addView(baseLabel, new LinearLayout.LayoutParams(-1, -2));

            int[] toggleIds = {R.string.shoot, R.string.reload, R.string.gun_damage, R.string.skill_damage,
                               R.string.respawn, R.string.speed, R.string.no_clip, R.string.recoil,
                               R.string.blackhole, R.string.kda_booster, R.string.aimbot, R.string.assist,
                               R.string.all_enemy, R.string.capture_milk};
            for (int id : toggleIds) {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(3), 0, dp(3));
                String prefix = "\u30FB";
                if (id == R.string.speed || id == R.string.blackhole || id == R.string.assist || id == R.string.skill_damage)
                    prefix = ">";
                TextView bullet = text(prefix, 13, TEXT, Typeface.BOLD);
                bullet.setGravity(Gravity.CENTER);
                row.addView(bullet, new LinearLayout.LayoutParams(dp(18), dp(32)));
                TextView name = text(getString(id), 13, TEXT, Typeface.BOLD);
                row.addView(name, new LinearLayout.LayoutParams(0, dp(32), 1));
                Switch sw = new Switch(context);
                sw.setMinWidth(dp(42));
                if (Build.VERSION.SDK_INT >= 21) {
                    int neon = Color.rgb(0, 255, 220);
                    int red = Color.rgb(255, 23, 68);
                    int[][] states = new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{-android.R.attr.state_checked}
                    };
                    sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{neon, Color.WHITE}));
                    sw.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{neon, red}));
                }
                sw.setEnabled(false);
                row.addView(sw, new LinearLayout.LayoutParams(dp(54), dp(32)));
                addView(row);
            }

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            String[] row1 = {getString(R.string.capture_1), getString(R.string.capture_2), getString(R.string.enemy_list)};
            for (String lbl : row1) {
                Button b = miniButton(lbl);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                actions.addView(b, lp);
            }
            addView(actions);

            LinearLayout actions2 = new LinearLayout(context);
            actions2.setOrientation(HORIZONTAL);
            String[] row2 = {getString(R.string.capture_3), getString(R.string.capture_4)};
            for (String lbl : row2) {
                Button b = miniButton(lbl);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                actions2.addView(b, lp);
            }
            addView(actions2);

            LinearLayout actions3 = new LinearLayout(context);
            actions3.setOrientation(HORIZONTAL);
            String[] row3 = {"END APP"};
            for (String lbl : row3) {
                Button b = miniButton(lbl);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(32), 1);
                lp.setMargins(dp(2), dp(3), dp(2), dp(3));
                actions3.addView(b, lp);
            }
            addView(actions3);

            loadFromPrefs();
        }

        void loadFromPrefs() {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            brightness = p.getInt("panelBrightness", 0);
            blurLevel = p.getInt("panelBlur", 0);
            bgPanX = p.getFloat("bgPanX", 0);
            bgPanY = p.getFloat("bgPanY", 0);
            bgZoom = p.getFloat("bgZoom", 1f);
            if (bgZoom < MIN_ZOOM) bgZoom = MIN_ZOOM;
            String path = p.getString("panelBgPath", "");
            if (!path.isEmpty()) src = BitmapFactory.decodeFile(path);
            else src = null;
            textColor = p.getInt("menuTextColor", Color.WHITE);
            recolorChildren();
            recomputeBlur();
            invalidate();
        }

        private void recolorChildren() {
            for (int i = 0; i < getChildCount(); i++) recolorView(getChildAt(i));
        }

        private void recolorView(View v) {
            if ("baseLabel".equals(v.getTag())) return;
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(textColor);
            } else if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) recolorView(g.getChildAt(i));
            }
        }

        void setBrightness(int b) { brightness = b; invalidate(); }
        void setBlur(int bl) { blurLevel = bl; recomputeBlur(); invalidate(); }
        void setImage(Bitmap bm) { src = bm; bgPanX = 0; bgPanY = 0; bgZoom = 1f; saveBgTransform(); recomputeBlur(); invalidate(); }

        private void saveBgTransform() {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putFloat("bgPanX", bgPanX).putFloat("bgPanY", bgPanY).putFloat("bgZoom", bgZoom).apply();
        }

        private void recomputeBlur() {
            if (src == null || src.isRecycled()) { blurred = null; return; }
            if (blurLevel <= 0) { blurred = src; return; }
            int iw = Math.max(1, src.getWidth() / 2);
            int ih = Math.max(1, src.getHeight() / 2);
            Bitmap cur = Bitmap.createScaledBitmap(src, iw, ih, true);
            try {
                RenderScript rs = RenderScript.create(MainActivity.this);
                Allocation input = Allocation.createFromBitmap(rs, cur);
                Allocation output = Allocation.createTyped(rs, input.getType());
                ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
                script.setInput(input);
                script.setRadius(Math.max(0.01f, Math.min(25, blurLevel * 0.15f)));
                script.forEach(output);
                output.copyTo(cur);
                script.destroy();
                input.destroy();
                output.destroy();
                rs.destroy();
            } catch (Exception ignored) {}
            Bitmap result = Bitmap.createScaledBitmap(cur, src.getWidth(), src.getHeight(), true);
            if (cur != src) cur.recycle();
            blurred = result;
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            float r = dp(14);
            tmpRect.set(0, 0, getWidth(), getHeight());
            clipPath.rewind();
            clipPath.addRoundRect(tmpRect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            if (blurred != null && !blurred.isRecycled()) {
                float bmpW = blurred.getWidth(), bmpH = blurred.getHeight();
                float s = Math.max(getWidth() / bmpW, getHeight() / bmpH) * bgZoom;
                float imgW = bmpW * s, imgH = bmpH * s;
                float l = (getWidth() - imgW) / 2f + bgPanX;
                float t = (getHeight() - imgH) / 2f + bgPanY;
                canvas.drawBitmap(blurred, null, new RectF(l, t, l + imgW, t + imgH), bitmapPaint);
                int alpha = (int) (brightness * 2.55f);
                if (alpha > 0) canvas.drawColor(Color.argb(Math.min(alpha, 255), 0, 0, 0));
            } else {
                canvas.drawColor(Color.rgb(24, 31, 45));
            }

            super.dispatchDraw(canvas);
            canvas.restore();

            canvas.drawRoundRect(tmpRect, r, r, borderPaint);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            int mh = previewHeight();
            if (getMeasuredHeight() > mh) {
                setMeasuredDimension(getMeasuredWidth(), mh);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int pc = event.getPointerCount();
            if (pc == 1) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = event.getX(); lastY = event.getY(); lastDist = -1f;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        bgPanX += event.getX() - lastX;
                        bgPanY += event.getY() - lastY;
                        lastX = event.getX(); lastY = event.getY();
                        saveBgTransform();
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lastDist = -1f;
                        getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                }
                return super.onTouchEvent(event);
            }
            if (pc >= 2) {
                float dx = event.getX(0) - event.getX(1);
                float dy = event.getY(0) - event.getY(1);
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (lastDist < 0) lastDist = dist;
                else {
                    bgZoom *= dist / lastDist;
                    bgZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, bgZoom));
                    lastDist = dist;
                    saveBgTransform();
                    invalidate();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private TextView text(String s, int sp, int color, int style) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, style);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private Button miniButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(10);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(Color.WHITE);
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
        return button;
    }

    private StateListDrawable miniButtonBackground() {
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

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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

    private int previewHeight() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int ref = Math.min(w, h);
        int max = (int) (ref * 0.68f);
        int base = Math.max(dp(220), Math.min(dp(380), max));
        int scaled = (int) (base * 1.2f);
        int cap = (int) (ref * 0.85f);
        return Math.min(scaled, cap);
    }
}
