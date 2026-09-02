package com.mtool.app;

import android.content.SharedPreferences;
import android.graphics.Color;

public final class Defaults {
    private static final String KEY_APPLIED = "defaults_applied_v157";

    private Defaults() {
    }

    public static void ensure(SharedPreferences prefs) {
        if (prefs == null) return;
        if (prefs.getBoolean(KEY_APPLIED, false)) return;
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean("fridaTool", false);
        e.putBoolean("shoot", false);
        e.putBoolean("reload", false);
        e.putBoolean("damageUpGun", false);
        e.putBoolean("damageUpSkill", false);
        e.putBoolean("respawn", false);
        e.putBoolean("respawnGroup", false);
        e.putBoolean("respawnInstant", false);
        e.putBoolean("speed", false);
        e.putBoolean("noClip", false);
        e.putBoolean("recoil", false);
        e.putBoolean("blackHole", false);
        e.putBoolean("blackHoleFixed", false);
        e.putBoolean("bavaHack", false);
        e.putBoolean("kdaBooster", false);
        e.putBoolean("aimBot", false);
        e.putBoolean("aimAssist", false);
        e.putBoolean("assistDisableOnSubWeapon", false);
        e.putBoolean("assistOnlyShooting", false);
        e.putInt("assistActiveTimeValue", 10);
        e.putBoolean("allEnemy", false);
        e.putBoolean("excludeBot", false);
        e.putBoolean("show_excludeBot", true);
        e.putBoolean("skillDisableOnMainWeapon", false);
        e.putBoolean("captureMilk", false);
        e.putBoolean("touhouMedley", false);
        e.putBoolean("touhouLoop", false);
        e.putFloat("speedMultiplier", 5.0f);
        e.putInt("lockZonePos", 15);
        e.putInt("smoothAimPos", 30);
        e.putInt("bubbleOpacity", 100);
        e.putString("bubbleIconPath", "");
        e.putInt("bubbleBorderColor", Color.rgb(0, 255, 70));
        e.putBoolean("bubbleBorderEnabled", true);
        e.putInt("panelBgColor", Color.rgb(24, 31, 45));
        e.putString("panelBgPath", "");
        e.putInt("panelBrightness", 0);
        e.putInt("panelBlur", 0);
        e.putFloat("bgPanX", 0);
        e.putFloat("bgPanY", 0);
        e.putFloat("bgZoom", 1f);
        e.putBoolean("menuTextStroke", false);
        e.putInt("menuTextColor", Color.WHITE);
        e.putBoolean(KEY_APPLIED, true);
        e.apply();
    }
}
