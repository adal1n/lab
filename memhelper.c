#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/ioctl.h>
#include <sys/time.h>
#include <time.h>
#include <linux/input.h>

#define BUF_SIZE 4096

typedef struct {
    uint8_t cmd;
    uint64_t addr;
    uint32_t len;
} __attribute__((packed)) Request;

// ---- /dev/input タッチ注入 ----
static int g_touch_fd = -1;
static int g_abs_min_x = 0, g_abs_max_x = 0;
static int g_abs_min_y = 0, g_abs_max_y = 0;
static int g_geom_w = 1080, g_geom_h = 2400;
// ディスプレイ回転 (Display.getRotation: 0=0°, 1=90°, 2=180°, 3=270°)
static int g_rotation = 0;
static int g_track_id = 1000;
// プロトコル種別: 0=未判定, 1=旧型MT(プロトコルA: POSITION+SYN_MT_REPORT), 2=新型MT(プロトコルB: SLOT+TRACKING_ID)
static int g_proto = 0;

static void emit_event(int type, int code, int value) {
    if (g_touch_fd < 0) return;
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    struct timeval tv;
    gettimeofday(&tv, NULL);
    ev.time.tv_sec = tv.tv_sec;
    ev.time.tv_usec = tv.tv_usec;
    ev.type = (uint16_t)type;
    ev.code = (uint16_t)code;
    ev.value = value;
    ssize_t wr = write(g_touch_fd, &ev, sizeof(ev));
    if (wr != (ssize_t)sizeof(ev)) {
        fprintf(stderr, "emit_event: write failed type=%d code=%d val=%d errno=%d\n", type, code, value, errno);
    }
}

static int check_ev_abs_bit(int fd, int code) {
    // EVIOCGBIT(EV_ABS) で ABS_* 対応ビットを確認する (unsigned long は64bit)
    unsigned long bits[16];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(bits)), bits) < 0) return 0;
    return (int)((bits[code >> 6] >> (code & 63)) & 1UL);
}

static void open_touch_device(void) {
    // タッチスクリーン(ABS_MT_POSITION_X をサポート)を持つ /dev/input/event* を探す
    for (int i = 0; i < 32; i++) {
        char path[64];
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_WRONLY | O_NONBLOCK);
        if (fd < 0) continue;

        struct input_absinfo ai;
        memset(&ai, 0, sizeof(ai));
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_X), &ai) < 0) {
            close(fd);
            continue;
        }
        if (ai.maximum <= ai.minimum) {
            close(fd);
            continue;
        }
        g_abs_min_x = ai.minimum;
        g_abs_max_x = ai.maximum;

        memset(&ai, 0, sizeof(ai));
        if (ioctl(fd, EVIOCGABS(ABS_MT_POSITION_Y), &ai) < 0) {
            close(fd);
            continue;
        }
        g_abs_min_y = ai.minimum;
        g_abs_max_y = ai.maximum;

        g_proto = (check_ev_abs_bit(fd, ABS_MT_SLOT) && check_ev_abs_bit(fd, ABS_MT_TRACKING_ID))
                  ? 2 : 1;
        g_touch_fd = fd;
        fprintf(stderr, "touch device: %s abs_x=%d..%d abs_y=%d..%d proto=%s\n",
                path, g_abs_min_x, g_abs_max_x, g_abs_min_y, g_abs_max_y,
                g_proto == 2 ? "MT-B" : "MT-A");
        return;
    }
    g_touch_fd = -1;
    g_proto = 0;
    fprintf(stderr, "touch device: NOT FOUND\n");
}

// ディスプレイ座標(dx,dy)をパネル座標(px,py)へ変換。
// パネルは縦長(X=短辺, Y=長辺)、ディスプレイの向き(g_rotation)を考慮して軸を入れ替える。
static void screen_to_panel(int dx, int dy, int *px, int *py) {
    int w = g_geom_w, h = g_geom_h;
    if (w <= 0) w = 1080;
    if (h <= 0) h = 2400;
    int minx = g_abs_min_x, maxx = g_abs_max_x;
    int miny = g_abs_min_y, maxy = g_abs_max_y;
    switch (g_rotation) {
        case 1: // ROTATION_90: 横画面(右回り90°)
            *px = maxx - (int)((long long)dy * (maxx - minx) / h);
            *py = miny + (int)((long long)dx * (maxy - miny) / w);
            break;
        case 3: // ROTATION_270: 横画面(左回り90°)
            *px = minx + (int)((long long)dy * (maxx - minx) / h);
            *py = maxy - (int)((long long)dx * (maxy - miny) / w);
            break;
        case 2: // ROTATION_180
            *px = maxx - (int)((long long)dx * (maxx - minx) / w);
            *py = maxy - (int)((long long)dy * (maxy - miny) / h);
            break;
        default: // ROTATION_0
            *px = minx + (int)((long long)dx * (maxx - minx) / w);
            *py = miny + (int)((long long)dy * (maxy - miny) / h);
            break;
    }
}

static int tap_down(uint32_t sx, uint32_t sy) {
    if (g_touch_fd < 0) return -1;
    int x, y;
    screen_to_panel((int)sx, (int)sy, &x, &y);
    if (g_proto == 2) {
        emit_event(EV_ABS, ABS_MT_SLOT, 0);
        emit_event(EV_ABS, ABS_MT_TRACKING_ID, g_track_id++);
        emit_event(EV_ABS, ABS_MT_POSITION_X, x);
        emit_event(EV_ABS, ABS_MT_POSITION_Y, y);
        emit_event(EV_KEY, BTN_TOUCH, 1);
        emit_event(EV_KEY, BTN_TOOL_FINGER, 1);
        emit_event(EV_SYN, SYN_REPORT, 0);
    } else {
        emit_event(EV_ABS, ABS_MT_POSITION_X, x);
        emit_event(EV_ABS, ABS_MT_POSITION_Y, y);
        emit_event(EV_SYN, SYN_MT_REPORT, 0);
        emit_event(EV_SYN, SYN_REPORT, 0);
    }
    fprintf(stderr, "tap_down: screen=%u,%u panel=%d,%d\n", sx, sy, x, y);
    return 0;
}

static int tap_up(void) {
    if (g_touch_fd < 0) return -1;
    if (g_proto == 2) {
        emit_event(EV_ABS, ABS_MT_SLOT, 0);
        emit_event(EV_ABS, ABS_MT_TRACKING_ID, -1);
        emit_event(EV_KEY, BTN_TOUCH, 0);
        emit_event(EV_KEY, BTN_TOOL_FINGER, 0);
        emit_event(EV_SYN, SYN_REPORT, 0);
    } else {
        emit_event(EV_SYN, SYN_MT_REPORT, 0);
        emit_event(EV_SYN, SYN_REPORT, 0);
    }
    return 0;
}

static ssize_t read_process_mem(pid_t pid, void *buf, size_t len, uint64_t addr) {
    struct iovec local[1];
    struct iovec remote[1];
    local[0].iov_base = buf;
    local[0].iov_len = len;
    remote[0].iov_base = (void*)(uintptr_t)addr;
    remote[0].iov_len = len;
    return process_vm_readv(pid, local, 1, remote, 1, 0);
}

static ssize_t write_process_mem(pid_t pid, const void *buf, size_t len, uint64_t addr) {
    if (ptrace(PTRACE_ATTACH, pid, NULL, NULL) < 0) return -1;
    int status;
    waitpid(pid, &status, 0);

    size_t total = 0;
    int err = 0;
    while (total < len) {
        uint64_t cur = addr + total;
        uint64_t aligned = cur & ~7ULL;
        size_t offset = cur - aligned;
        size_t chunk = (8 - offset);
        if (chunk > len - total) chunk = len - total;

        errno = 0;
        unsigned long word = ptrace(PTRACE_PEEKDATA, pid, (void*)(uintptr_t)aligned, NULL);
        if (errno) { err = 1; break; }

        unsigned char wbuf[8];
        memcpy(wbuf, &word, 8);
        memcpy(wbuf + offset, (const unsigned char*)buf + total, chunk);
        memcpy(&word, wbuf, 8);

        if (ptrace(PTRACE_POKEDATA, pid, (void*)(uintptr_t)aligned, (void*)(uintptr_t)word) < 0) {
            err = 1; break;
        }
        total += chunk;
    }

    ptrace(PTRACE_DETACH, pid, NULL, NULL);
    return err ? -1 : (ssize_t)total;
}

int main(int argc, char *argv[]) {
    if (argc < 2) return 1;
    pid_t pid = (pid_t)atol(argv[1]);

    setvbuf(stdin, NULL, _IONBF, 0);
    setvbuf(stdout, NULL, _IONBF, 0);

    open_touch_device();

    Request req;
    char buf[BUF_SIZE];
    while (1) {
        ssize_t n = read(STDIN_FILENO, &req, sizeof(req));
        if (n <= 0) break;
        if (req.len > BUF_SIZE) req.len = BUF_SIZE;
        if (req.cmd == 0) {
            errno = 0;
            ssize_t r = read_process_mem(pid, buf, req.len, req.addr);
            int saved = errno;
            size_t out_len = (r > 0) ? (size_t)r : 0;
            write(STDOUT_FILENO, &out_len, sizeof(out_len));
            if (out_len > 0) write(STDOUT_FILENO, buf, out_len);
            if (r < 0) fprintf(stderr, "read pid=%d addr=%llx len=%zu errno=%d (%s)\n",
                pid, (unsigned long long)req.addr, (size_t)req.len, saved, strerror(saved));
        } else if (req.cmd == 1) {
            ssize_t r = 0;
            while ((size_t)r < req.len) {
                ssize_t n2 = read(STDIN_FILENO, buf + r, req.len - r);
                if (n2 <= 0) break;
                r += n2;
            }
            ssize_t written = write_process_mem(pid, buf, r, req.addr);
            uint32_t ack = (written > 0) ? (uint32_t)written : 0;
            write(STDOUT_FILENO, &ack, sizeof(ack));
        } else if (req.cmd == 2) {
            char mappath[64];
            snprintf(mappath, sizeof(mappath), "/proc/%d/maps", pid);
            int mfd = open(mappath, O_RDONLY);
            if (mfd >= 0) {
                size_t maps_size = 16777216;
                char *mdata = malloc(maps_size);
                if (!mdata) { close(mfd); uint32_t len = 0; write(STDOUT_FILENO, &len, sizeof(len)); }
                else {
                    ssize_t total = 0;
                    while (total < (ssize_t)maps_size) {
                        size_t remain = maps_size - total;
                        ssize_t r = read(mfd, mdata + total, remain);
                        if (r > 0) total += r;
                        else if (r == 0) break;
                        else if (errno == EINTR) continue;
                        else break;
                    }
                    close(mfd);
                    uint32_t len = (uint32_t)total;
                    write(STDOUT_FILENO, &len, sizeof(len));
                    if (total > 0) write(STDOUT_FILENO, mdata, total);
                    free(mdata);
                }
            } else {
                uint32_t len = 0;
                write(STDOUT_FILENO, &len, sizeof(len));
            }
        } else if (req.cmd == 3) {
            uint64_t result = 0;
            char auxvpath[64];
            snprintf(auxvpath, sizeof(auxvpath), "/proc/%d/auxv", pid);
            int fd = open(auxvpath, O_RDONLY);
            if (fd >= 0) {
                unsigned char tmp[4];
                if (read(fd, tmp, 4) == 4) result = (uint64_t)pid;
                close(fd);
            }
            write(STDOUT_FILENO, &result, sizeof(result));
        } else if (req.cmd == 4) {
            uint32_t ok = (tap_down((uint32_t)req.addr, req.len) == 0) ? 1 : 0;
            write(STDOUT_FILENO, &ok, sizeof(ok));
        } else if (req.cmd == 5) {
            uint32_t ok = (tap_up() == 0) ? 1 : 0;
            write(STDOUT_FILENO, &ok, sizeof(ok));
        } else if (req.cmd == 6) {
            // addr=幅, len=高さ, 上位32bit無し。回転は別途cmd 7で設定
            g_geom_w = (int)req.addr;
            g_geom_h = (int)req.len;
            uint32_t ok = 1;
            write(STDOUT_FILENO, &ok, sizeof(ok));
        } else if (req.cmd == 7) {
            g_rotation = (int)req.addr;
            fprintf(stderr, "rotation set to %d\n", g_rotation);
            uint32_t ok = 1;
            write(STDOUT_FILENO, &ok, sizeof(ok));
        } else if (req.cmd == 0xFF) {
            break;
        }
    }
    if (g_touch_fd >= 0) close(g_touch_fd);
    return 0;
}