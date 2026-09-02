package com.mtool.app;

public class MemOps {
    static {
        System.loadLibrary("native");
    }

    public static native boolean nativeInit(String packageName);
    public static native boolean nativeInitWithPid(int pid, String packageName);
    public static native void nativeDestroy();
    public static native void nativeStartLoop();
    public static native void nativeStopLoop();
    public static native void nativeSetToggle(String key, boolean value);
    public static native void nativeSetSlider(String key, int value);
    public static native void nativeSetAction(int action, int arg);
    public static native String nativeGetStatus();
    public static native boolean nativeIsConnected();
    public static native boolean nativeIsBaseValid();
    public static native int nativeGetAttachedPid();
    public static native long nativeGetSelfBase();
    public static native boolean nativeRepairXa();
    public static native int nativeGetEnemyCount();
    public static native long nativeGetEnemyRoomBase();
    public static native long nativeGetEnemyId(int index);
    public static native String nativeGetEnemyName(int index);
    public static native int nativeGetEnemySlot(int index);
    public static native boolean nativeGetEnemyEnabled(int index);
    public static native void nativeSetEnemyEnabled(int index, boolean enabled);

    public static final int ACTION_NONE = 0;
    public static final int ACTION_SELF_SCAN = 1;
    public static final int ACTION_SCAN_ENEMIES = 2;
    public static final int ACTION_ENEMY_LIST = 3;
    public static final int ACTION_ENEMY_TOGGLE = 4;
    public static final int ACTION_CAPTURE1 = 5;
    public static final int ACTION_CAPTURE2 = 6;
    public static final int ACTION_CAPTURE3 = 7;
    public static final int ACTION_CAPTURE4 = 8;

    private static boolean initialized = false;

    public static boolean init(String packageName) {
        if (initialized) return true;
        boolean ok = nativeInit(packageName);
        nativeStartLoop();
        initialized = true;
        return ok;
    }

    public static boolean initWithPid(int pid, String packageName) {
        if (initialized) return true;
        boolean ok = nativeInitWithPid(pid, packageName);
        nativeStartLoop();
        initialized = true;
        return ok;
    }

    public static void shutdown() {
        if (!initialized) return;
        nativeStopLoop();
        nativeDestroy();
        initialized = false;
    }

    public static void setToggle(String key, boolean value) {
        if (!initialized) return;
        nativeSetToggle(key, value);
    }

    public static void setSlider(String key, int value) {
        if (!initialized) return;
        nativeSetSlider(key, value);
    }

    public static void setAction(int action, int arg) {
        if (!initialized) return;
        nativeSetAction(action, arg);
    }

    public static String getStatus() {
        if (!initialized) return "";
        return nativeGetStatus();
    }

    public static boolean isConnected() {
        if (!initialized) return false;
        return nativeIsConnected();
    }

    public static boolean isBaseValid() {
        if (!initialized) return false;
        return nativeIsBaseValid();
    }

    public static int getAttachedPid() {
        if (!initialized) return 0;
        return nativeGetAttachedPid();
    }

    public static long getSelfBase() {
        if (!initialized) return 0;
        return nativeGetSelfBase();
    }

    public static boolean repairXa() {
        if (!initialized) return false;
        return nativeRepairXa();
    }

    public static int getEnemyCount() {
        if (!initialized) return 0;
        return nativeGetEnemyCount();
    }

    public static long getEnemyRoomBase() {
        if (!initialized) return 0;
        return nativeGetEnemyRoomBase();
    }

    public static long getEnemyId(int index) {
        if (!initialized) return 0;
        return nativeGetEnemyId(index);
    }

    public static String getEnemyName(int index) {
        if (!initialized) return "";
        return nativeGetEnemyName(index);
    }

    public static boolean getEnemyEnabled(int index) {
        if (!initialized) return false;
        return nativeGetEnemyEnabled(index);
    }

    public static int getEnemySlot(int index) {
        if (!initialized) return -1;
        return nativeGetEnemySlot(index);
    }

    public static void setEnemyEnabled(int index, boolean enabled) {
        if (!initialized) return;
        nativeSetEnemyEnabled(index, enabled);
    }

}
