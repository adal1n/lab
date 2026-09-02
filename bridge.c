#include <jni.h>
#include <string.h>
#include <pthread.h>
#include <stdio.h>
#include <unistd.h>
#include <time.h>
#include <signal.h>
#include <android/log.h>
#include "hacks.h"
#include "defs.h"
#include "memops.h"

#define LOGD(...) do { __android_log_print(ANDROID_LOG_INFO, "mtool", __VA_ARGS__); FILE *f = fopen("/data/data/com.mtool.app/mtool_debug.log", "a"); if(f) { fprintf(f, "[%ld] mtool: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, "mtool", __VA_ARGS__); FILE *f = fopen("/data/data/com.mtool.app/mtool_debug.log", "a"); if(f) { fprintf(f, "[%ld] mtool ERR: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)

static ToggleState g_ts;
static GameState g_gs;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_loop_thread;
static volatile bool g_loop_running = false;
static char g_status[256] = {0};
static float g_loop_interval = 0.010f;
static volatile bool g_xa_dirty = false;
static char g_pkg[256] = {0};

static void set_toggle_internal(int id, bool val) {
    if (id < 0 || id >= TOG_COUNT) return;
    bool changed = (g_ts.toggles[id] != val);
    g_ts.toggles[id] = val;

    if (id == TOG_EXCLUDE_BOT && changed) {
        hacks_reset_enemies();
        LOGD("set_toggle_internal: Exclude BOT %s, enemy list cleared for re-scan", val ? "ON" : "OFF");
    }

    if (id == TOG_ALL_ENEMY && !val) {
        // マッチ判定: 敵パリティ(自己と異なる)が居る場合のみチームメイト除去を行う。
        // ロビーはチーム未分離で全員同一パリティのため、リストを空にしないようガードする。
        bool have_enemy_parity = false;
        for (int i = 0; i < g_enemy_count; i++) {
            if (g_enemies[i].team_parity != g_gs.player_team_parity) {
                have_enemy_parity = true;
                break;
            }
        }
        if (have_enemy_parity) {
            int write_idx = 0;
            for (int i = 0; i < g_enemy_count; i++) {
                if (g_enemies[i].team_parity != g_gs.player_team_parity) {
                    g_enemies[write_idx++] = g_enemies[i];
                }
            }
            g_enemy_count = write_idx;
            LOGD("set_toggle_internal: All Enemy OFF, teammates removed (count now %d)", g_enemy_count);
        } else {
            LOGD("set_toggle_internal: All Enemy OFF, lobby (no enemy parity) => keep list");
        }
    }
}

static void *hack_loop(void *arg) {
    (void)arg;
    double last_enemy_scan = 0;

    LOGD("hack_loop started");
    int loop_count = 0;
    int pid_check_count = 0;
    while (g_loop_running) {
        double t0 = now_seconds();

        pthread_mutex_lock(&g_mutex);

        if (!mem_is_connected()) {
            pthread_mutex_unlock(&g_mutex);
            if (++loop_count % 50 == 0) {
                LOGD("loop: memhelper not connected, attempting restart...");
                mem_shutdown();
                if (mem_restart(g_pkg)) {
                    hacks_discover_regions(&g_gs);
                    hacks_apply_xa(&g_ts, &g_gs);
                    LOGD("loop: restart successful");
                } else {
                    LOGD("loop: restart failed, will retry");
                }
            }
            usleep(100000);
            continue;
        }

        if (++pid_check_count % 100 == 0) {
            if (mem_pid_changed(g_pkg)) {
                LOGD("loop: game PID changed, restarting helper");
                pthread_mutex_unlock(&g_mutex);
                mem_shutdown();
                if (mem_restart(g_pkg)) {
                    hacks_reset_all_state();
                    hacks_discover_regions(&g_gs);
                    hacks_apply_xa(&g_ts, &g_gs);
                    LOGD("loop: restart with new PID successful and XA reapplied");
                }
                usleep(1000);
                continue;
            }
        }

        if (g_xa_dirty) {
            g_xa_dirty = false;
            LOGD("applying XA hacks (dirty)");
            hacks_apply_xa(&g_ts, &g_gs);
        }

        hacks_discover_base(&g_gs);
        if (g_gs.base_valid) {
            g_gs.player_id = mem_read_dword(g_gs.base_addr + 0);
            g_gs.player_team_parity = mem_read_byte(g_gs.base_addr + OFF_TEAM_PARITY) % 2;
            snprintf(g_status, sizeof(g_status),
                     "ID: %u  Base: %lX", g_gs.player_id, g_gs.base_addr);
        } else {
            snprintf(g_status, sizeof(g_status), "Base: ---");
        }

        if (g_gs.base_valid) {
            hacks_apply_all(&g_ts, &g_gs);
            hacks_run_action(&g_ts, &g_gs);
            hacks_tick_capture(&g_gs, &g_ts);
            hacks_auto_capture_milk(&g_ts, &g_gs);

            {
                double t = now_seconds();
                if (t - last_enemy_scan >= ENEMY_POLL_INTERVAL_S) {
                    last_enemy_scan = t;
                    EnemyInfo tmp[MAX_ENEMIES];
                    int cnt = 0;
                    if (hacks_scan_enemies(&g_ts, &g_gs, tmp, &cnt, MAX_ENEMIES)) {
                        bool old_enabled[MAX_ENEMIES];
                        for (int i = 0; i < cnt; i++) {
                            old_enabled[i] = tmp[i].enabled;
                            for (int j = 0; j < g_enemy_count; j++) {
                                if (g_enemies[j].id == tmp[i].id) {
                                    old_enabled[i] = g_enemies[j].enabled;
                                    break;
                                }
                            }
                            tmp[i].enabled = old_enabled[i];
                        }
                        for (int i = 0; i < cnt; i++) {
                            g_enemies[i] = tmp[i];
                        }
                        g_enemy_count = cnt;
                    }
                }

                if (g_ts.toggles[TOG_BLACK_HOLE]) {
                    for (int i = 0; i < g_enemy_count; i++) {
                        uint8_t death_status = mem_read_byte(g_enemies[i].base + 0x134);
                        g_enemies[i].alive = (death_status != 16);
                    }
                    hacks_apply_black_hole(&g_gs, g_enemies, g_enemy_count, &g_ts);
                }
                if ((g_ts.toggles[TOG_AIM_BOT] || g_ts.toggles[TOG_AIM_ASSIST])) {
                    for (int i = 0; i < g_enemy_count; i++) {
                        uint8_t death_status = mem_read_byte(g_enemies[i].base + 0x134);
                        g_enemies[i].alive = (death_status != 16);
                    }
                    hacks_apply_aimbot(g_enemies, g_enemy_count, &g_ts, &g_gs);
                }
            }
        }

        pthread_mutex_unlock(&g_mutex);

        double elapsed = now_seconds() - t0;
        int sleep_ms = (int)((g_loop_interval - elapsed) * 1000);
        if (sleep_ms > 0) usleep(sleep_ms * 1000);
        else usleep(1000);
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeInitWithPid(JNIEnv *env, jclass clazz, jint pid, jstring pkg) {
    (void)clazz;
    const char *pkg_c = (*env)->GetStringUTFChars(env, pkg, NULL);
    if (!pkg_c) return JNI_FALSE;

    signal(SIGPIPE, SIG_IGN);
    LOGD("nativeInitWithPid called, pkg=%s pid=%d", pkg_c, pid);
    {
        FILE *tf = fopen("/data/data/com.mtool.app/native_init_test.txt", "w");
        if (tf) { fprintf(tf, "nativeInitWithPid called %s pid=%d\n", pkg_c, pid); fclose(tf); }
    }

    memset(&g_ts, 0, sizeof(g_ts));
    g_ts.speed_multiplier = 5.0f;
    g_ts.assist_lock_zone_pos = 15;
    g_ts.assist_smooth_aim_pos = 30;
    memset(&g_gs, 0, sizeof(g_gs));

    bool ok = mem_init_with_pid((pid_t)pid);
    LOGD("mem_init_with_pid returned %d", ok);
    (*env)->ReleaseStringUTFChars(env, pkg, pkg_c);

    if (ok) {
        hacks_discover_regions(&g_gs);
        LOGD("hacks_discover_regions done, base_valid=%d", g_gs.base_valid);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeInit(JNIEnv *env, jclass clazz, jstring pkg) {
    (void)clazz;
    const char *pkg_c = (*env)->GetStringUTFChars(env, pkg, NULL);
    if (!pkg_c) return JNI_FALSE;

    signal(SIGPIPE, SIG_IGN);
    LOGD("nativeInit called, pkg=%s", pkg_c);
    {
        FILE *tf = fopen("/data/data/com.mtool.app/native_init_test.txt", "w");
        if (tf) { fprintf(tf, "nativeInit called with %s\n", pkg_c); fclose(tf); }
    }

    strncpy(g_pkg, pkg_c, sizeof(g_pkg) - 1);
    g_pkg[sizeof(g_pkg) - 1] = '\0';

    memset(&g_ts, 0, sizeof(g_ts));
    g_ts.speed_multiplier = 5.0f;
    g_ts.assist_lock_zone_pos = 15;
    g_ts.assist_smooth_aim_pos = 30;
    memset(&g_gs, 0, sizeof(g_gs));

    bool ok = mem_init(pkg_c);
    LOGD("mem_init returned %d", ok);
    (*env)->ReleaseStringUTFChars(env, pkg, pkg_c);

    if (ok) {
        hacks_discover_regions(&g_gs);
        LOGD("hacks_discover_regions done, base_valid=%d", g_gs.base_valid);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeDestroy(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    g_loop_running = false;
    pthread_join(g_loop_thread, NULL);
    mem_shutdown();
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeStartLoop(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (g_loop_running) { LOGD("nativeStartLoop - already running"); return; }
    g_loop_running = true;
    pthread_create(&g_loop_thread, NULL, hack_loop, NULL);
    LOGD("nativeStartLoop - thread created");
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeStopLoop(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    g_loop_running = false;
    pthread_join(g_loop_thread, NULL);
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeSetToggle(JNIEnv *env, jclass clazz, jstring key, jboolean value) {
    (void)env; (void)clazz;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k) return;
    LOGD("nativeSetToggle key=%s val=%d", k, value);
    pthread_mutex_lock(&g_mutex);
    for (int i = 0; i < TOG_COUNT; i++) {
        if (strcmp(k, TOGGLE_NAMES[i]) == 0) {
            set_toggle_internal(i, value);
            LOGD("  matched toggle %d (%s) => %d", i, k, value);
            break;
        }
    }
    g_xa_dirty = true;
    pthread_mutex_unlock(&g_mutex);
    (*env)->ReleaseStringUTFChars(env, key, k);
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeSetSlider(JNIEnv *env, jclass clazz, jstring key, jint value) {
    (void)env; (void)clazz;
    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    if (!k) return;
    pthread_mutex_lock(&g_mutex);
    if (strcmp(k, "speedMultiplier") == 0) {
        g_ts.speed_multiplier = (float)value;
        g_xa_dirty = true;
    } else if (strcmp(k, "lockZonePos") == 0) g_ts.assist_lock_zone_pos = value;
    else if (strcmp(k, "smoothAimPos") == 0) g_ts.assist_smooth_aim_pos = value;
    else if (strcmp(k, "assistActiveTime") == 0) g_ts.assist_active_time = value;
    pthread_mutex_unlock(&g_mutex);
    (*env)->ReleaseStringUTFChars(env, key, k);
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeSetAction(JNIEnv *env, jclass clazz, jint action, jint arg) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    g_ts.action = action;
    g_ts.action_arg = arg;
    g_ts.action_pending = true;
    pthread_mutex_unlock(&g_mutex);
}

JNIEXPORT jstring JNICALL
Java_com_mtool_app_MemOps_nativeGetStatus(JNIEnv *env, jclass clazz) {
    (void)clazz;
    pthread_mutex_lock(&g_mutex);
    jstring result = (*env)->NewStringUTF(env, g_status);
    pthread_mutex_unlock(&g_mutex);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeIsConnected(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return mem_is_connected() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeIsBaseValid(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    bool ok = g_gs.base_valid;
    pthread_mutex_unlock(&g_mutex);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_mtool_app_MemOps_nativeGetAttachedPid(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pid_t pid = 0;
    mem_test_attach(&pid);
    return (jint)pid;
}

JNIEXPORT jlong JNICALL
Java_com_mtool_app_MemOps_nativeGetSelfBase(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    jlong v = g_gs.base_valid ? (jlong)g_gs.base_addr : 0;
    pthread_mutex_unlock(&g_mutex);
    return v;
}

JNIEXPORT jint JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemyCount(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    int cnt = g_enemy_count;
    pthread_mutex_unlock(&g_mutex);
    return (jint)cnt;
}

JNIEXPORT jlong JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemyRoomBase(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    uint64_t base = g_enemy_room_found ? g_enemy_room_base : 0;
    pthread_mutex_unlock(&g_mutex);
    return (jlong)base;
}

JNIEXPORT jlong JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemyId(JNIEnv *env, jclass clazz, jint index) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    uint64_t id = (index >= 0 && index < g_enemy_count) ? g_enemies[index].id : 0;
    pthread_mutex_unlock(&g_mutex);
    return (jlong)id;
}

JNIEXPORT jstring JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemyName(JNIEnv *env, jclass clazz, jint index) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    const char *name = (index >= 0 && index < g_enemy_count) ? g_enemies[index].name : "";
    jstring result = (*env)->NewStringUTF(env, name);
    pthread_mutex_unlock(&g_mutex);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemySlot(JNIEnv *env, jclass clazz, jint index) {
    (void)env; (void)clazz;
    jint slot = -1;
    pthread_mutex_lock(&g_mutex);
    if (index >= 0 && index < g_enemy_count) {
        slot = (jint)g_enemies[index].slot;
    }
    pthread_mutex_unlock(&g_mutex);
    return slot;
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeGetEnemyEnabled(JNIEnv *env, jclass clazz, jint index) {
    (void)env; (void)clazz;
    jboolean res = JNI_FALSE;
    pthread_mutex_lock(&g_mutex);
    if (index >= 0 && index < g_enemy_count) {
        res = g_enemies[index].enabled ? JNI_TRUE : JNI_FALSE;
    }
    pthread_mutex_unlock(&g_mutex);
    return res;
}

JNIEXPORT void JNICALL
Java_com_mtool_app_MemOps_nativeSetEnemyEnabled(JNIEnv *env, jclass clazz, jint index, jboolean enabled) {
    (void)env; (void)clazz;
    pthread_mutex_lock(&g_mutex);
    if (index >= 0 && index < g_enemy_count) {
        g_enemies[index].enabled = (enabled == JNI_TRUE);
    }
    pthread_mutex_unlock(&g_mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_mtool_app_MemOps_nativeRepairXa(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (!mem_is_connected()) {
        LOGD("nativeRepairXa: not connected");
        return JNI_FALSE;
    }
    pthread_mutex_lock(&g_mutex);
    LOGD("nativeRepairXa: re-discovering XA regions");
    bool ok = hacks_discover_regions(&g_gs);
    if (ok) {
        hacks_resolve_roster(&g_gs);
        hacks_apply_xa(&g_ts, &g_gs);
        LOGD("nativeRepairXa: XA repair done");
    } else {
        LOGD("nativeRepairXa: hacks_discover_regions failed");
    }
    pthread_mutex_unlock(&g_mutex);
    return ok ? JNI_TRUE : JNI_FALSE;
}
