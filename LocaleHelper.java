package com.mtool.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

public final class LocaleHelper {
    private static final String PREFS = "mco_remote";
    public static final String KEY_LANG = "lang";

    private LocaleHelper() {
    }

    private static Locale systemLocale() {
        try {
            Resources sys = Resources.getSystem();
            Configuration c = sys.getConfiguration();
            if (Build.VERSION.SDK_INT >= 24) {
                LocaleList list = c.getLocales();
                if (list != null && !list.isEmpty()) return list.get(0);
            }
            return c.locale;
        } catch (Exception ignored) {
            return Locale.getDefault();
        }
    }

    public static void apply(Context context) {
        if (context == null) return;
        Locale locale = selectedLocale(context);
        if (locale == null) locale = systemLocale();
        Locale.setDefault(locale);
        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(new LocaleList(locale));
        }
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLayoutDirection(locale);
        }
        res.updateConfiguration(config, res.getDisplayMetrics());
    }

    public static Context wrap(Context context) {
        if (context == null) return null;
        Locale locale = selectedLocale(context);
        if (locale == null) locale = systemLocale();
        Locale.setDefault(locale);
        Resources res = context.getResources();
        Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= 24) {
            config.setLocales(new LocaleList(locale));
        }
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLayoutDirection(locale);
        }
        if (Build.VERSION.SDK_INT >= 17) return context.createConfigurationContext(config);
        res.updateConfiguration(config, res.getDisplayMetrics());
        return context;
    }

    private static Locale selectedLocale(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_LANG, "system");
        if (raw == null) return null;
        String lang = raw.trim();
        if (lang.isEmpty() || "system".equalsIgnoreCase(lang)) return null;
        lang = lang.replace('_', '-');
        int dash = lang.indexOf('-');
        if (dash > 0 && dash < lang.length() - 1) {
            String l = lang.substring(0, dash);
            String r = lang.substring(dash + 1);
            if (!l.isEmpty() && !r.isEmpty()) return new Locale(l, r);
        }
        return new Locale(lang);
    }
}
