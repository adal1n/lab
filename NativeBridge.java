package com.mtool.app;

public final class NativeBridge {
    private static final String LIB = "cocos2dcpp";
    private static boolean triedLoad = false;
    private static boolean available = false;

    private NativeBridge() {
    }

    private static void ensureLoaded() {
        if (triedLoad) return;
        triedLoad = true;
        try {
            System.loadLibrary(LIB);
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    public static boolean trySendCommand(String json) {
        if (json == null) return false;
        ensureLoaded();
        if (!available) return false;
        try {
            nativeOnCommand(json);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static native void nativeOnCommand(String json);
}
