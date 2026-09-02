package com.mtool.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class GameVersion {
    public static final String GAME_PACKAGE = "com.gameparadiso.milkchoco";
    public static final String FALLBACK = "1.57.2";

    public static String get(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(GAME_PACKAGE, 0);
            String v = pi.versionName != null ? pi.versionName : "";
            if (!v.isEmpty()) return v;
        } catch (Exception ignored) {
        }
        return FALLBACK;
    }
}
