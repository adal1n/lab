#define _GNU_SOURCE
#include "memops.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <time.h>

#define LOGD(...) do { __android_log_print(ANDROID_LOG_INFO, "memops", __VA_ARGS__); FILE *f = fopen("/data/data/com.mtool.app/mtool_debug.log", "a"); if(f) { fprintf(f, "[%ld] memops: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, "memops", __VA_ARGS__); FILE *f = fopen("/data/data/com.mtool.app/mtool_debug.log", "a"); if(f) { fprintf(f, "[%ld] memops ERR: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)
#include <sys/wait.h>
#include <sys/stat.h>
#include <pthread.h>

#define HELPER_PATH "/data/local/tmp/memhelper"
#define BUF_SIZE 4096

extern const unsigned char memhelper_binary[];
extern const size_t memhelper_binary_size;

static int g_helper_fd = -1;
static pid_t g_helper_pid = 0;
static pid_t g_target_pid = 0;
static FILE *g_helper_fp = NULL;
static pthread_mutex_t g_pipe_mutex = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    uint8_t cmd;
    uint64_t addr;
    uint32_t len;
} __attribute__((packed)) Request;

static pid_t find_pid(const char *package) {
    char cmd[512];
    char buf[64];
    pid_t pid = -1;
    FILE *f;

    snprintf(cmd, sizeof(cmd), "su -c 'pidof %s'", package);
    f = popen(cmd, "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) pid = (pid_t)atol(buf);
        pclose(f);
        if (pid > 0) return pid;
    }

    snprintf(cmd, sizeof(cmd), "su -c 'pgrep -f %s'", package);
    f = popen(cmd, "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) pid = (pid_t)atol(buf);
        pclose(f);
        if (pid > 0) return pid;
    }

    snprintf(cmd, sizeof(cmd), "su -c 'ps -ef | grep \"%s$\" | head -1 | tr -s \" \" | cut -d\" \" -f2'", package);
    f = popen(cmd, "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) pid = (pid_t)atol(buf);
        pclose(f);
        if (pid > 0) return pid;
    }

    LOGE("find_pid: all methods failed for %s", package);
    return -1;
}

static bool deploy_helper(pid_t target_pid) {
    char cmd[512];
    snprintf(cmd, sizeof(cmd), "su -c 'cat > %s'", HELPER_PATH);
    FILE *f = popen(cmd, "w");
    if (!f) { LOGE("deploy: popen write failed"); return false; }
    size_t written = fwrite(memhelper_binary, 1, memhelper_binary_size, f);
    int ret = pclose(f);
    if (written != memhelper_binary_size) {
        LOGE("deploy: wrote %zu/%zu bytes", written, memhelper_binary_size);
        return false;
    }
    if (ret != 0) {
        LOGD("deploy: pclose=%d (ignored, binary written ok)", ret);
    }
    LOGD("deploy: wrote %zu bytes OK", written);

    snprintf(cmd, sizeof(cmd), "su -c 'chmod 755 %s'", HELPER_PATH);
    system(cmd);

    snprintf(cmd, sizeof(cmd), "su -c '/data/local/tmp/memhelper %d 2>>/data/data/com.mtool.app/mhelper.log'", target_pid);
    g_helper_fp = popen(cmd, "r+");
    if (!g_helper_fp) {
        LOGE("deploy: popen r+ failed");
        return false;
    }
    setvbuf(g_helper_fp, NULL, _IONBF, 0);
    g_helper_fd = fileno(g_helper_fp);
    g_helper_pid = 0;
    LOGD("deploy: helper started, fd=%d", g_helper_fd);

    usleep(300000);
    Request treq = {3, 0, 0};
    ssize_t wr = write(g_helper_fd, &treq, sizeof(treq));
    if (wr != sizeof(treq)) {
        LOGE("deploy: test write failed (errno=%d)", errno);
        pclose(g_helper_fp); g_helper_fp = NULL; g_helper_fd = -1;
        return false;
    }
    uint64_t tres = 0;
    ssize_t rr = read(g_helper_fd, &tres, sizeof(tres));
    if (rr != sizeof(tres)) {
        LOGE("deploy: test read failed (errno=%d)", errno);
        pclose(g_helper_fp); g_helper_fp = NULL; g_helper_fd = -1;
        return false;
    }
    LOGD("deploy: helper confirmed, pid=%llu", tres);
    return true;
}

static bool write_request(uint8_t cmd, uint64_t addr, uint32_t len) {
    Request req = {cmd, addr, len};
    return write(g_helper_fd, &req, sizeof(req)) == sizeof(req);
}

static bool read_exact(void *buf, size_t len) {
    size_t total = 0;
    while (total < len) {
        ssize_t r = read(g_helper_fd, (char*)buf + total, len - total);
        if (r <= 0) return false;
        total += r;
    }
    return true;
}

bool mem_init_with_pid(pid_t pid) {
    if (g_helper_fd >= 0) return true;
    if (pid <= 0) {
        LOGE("mem_init_with_pid: invalid pid %d", pid);
        return false;
    }
    LOGD("mem_init_with_pid: pid=%d", pid);
    g_target_pid = pid;
    if (!deploy_helper(pid)) {
        LOGE("mem_init_with_pid: deploy_helper failed");
        return false;
    }
    LOGD("mem_init_with_pid: helper connected successfully");
    return true;
}

bool mem_init(const char *package_name) {
    if (g_helper_fd >= 0) return true;
    pid_t pid = find_pid(package_name);
    LOGD("mem_init: pkg=%s pid=%d", package_name, pid);
    return mem_init_with_pid(pid);
}

bool mem_restart(const char *package_name) {
    mem_shutdown();
    return mem_init(package_name);
}

void mem_shutdown(void) {
    if (g_helper_fp) {
        pthread_mutex_lock(&g_pipe_mutex);
        write_request(0xFF, 0, 0);
        pclose(g_helper_fp);
        g_helper_fp = NULL;
        g_helper_fd = -1;
        pthread_mutex_unlock(&g_pipe_mutex);
    }
    g_target_pid = 0;
}

bool mem_is_connected(void) { return g_helper_fd >= 0; }
pid_t mem_get_pid(void) { return g_target_pid; }

bool mem_test_attach(pid_t *out_pid) {
    if (g_helper_fd < 0) return false;
    pthread_mutex_lock(&g_pipe_mutex);
    Request req = {3, 0, 0};
    if (write(g_helper_fd, &req, sizeof(req)) != sizeof(req)) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    uint64_t result = 0;
    if (!read_exact(&result, sizeof(result))) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    if (out_pid) *out_pid = (pid_t)result;
    pthread_mutex_unlock(&g_pipe_mutex);
    return result != 0;
}

bool mem_pid_changed(const char *package_name) {
    if (g_helper_fd < 0) return false;
    pid_t current = find_pid(package_name);
    return current > 0 && current != g_target_pid;
}

bool mem_read_buf(uint64_t addr, void *buf, size_t len) {
    if (g_helper_fd < 0 || len == 0) return false;
    pthread_mutex_lock(&g_pipe_mutex);
    if (!write_request(0, addr, (uint32_t)len)) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    size_t actual = 0;
    if (!read_exact(&actual, sizeof(actual))) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    if (actual == 0) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    size_t to_read = (actual < len) ? actual : len;
    bool ok = read_exact(buf, to_read);
    pthread_mutex_unlock(&g_pipe_mutex);
    return ok;
}

uint8_t mem_read_byte(uint64_t addr) {
    uint8_t v = 0;
    mem_read_buf(addr, &v, 1);
    return v;
}

uint16_t mem_read_word(uint64_t addr) {
    uint16_t v = 0;
    mem_read_buf(addr, &v, 2);
    return v;
}

uint32_t mem_read_dword(uint64_t addr) {
    uint32_t v = 0;
    mem_read_buf(addr, &v, 4);
    return v;
}

uint64_t mem_read_u64(uint64_t addr) {
    uint64_t v = 0;
    mem_read_buf(addr, &v, 8);
    return v;
}

float mem_read_float(uint64_t addr) {
    float v = 0;
    mem_read_buf(addr, &v, 4);
    return v;
}

bool mem_write_buf(uint64_t addr, const void *buf, size_t len) {
    if (g_helper_fd < 0 || len == 0) return false;
    pthread_mutex_lock(&g_pipe_mutex);
    if (!write_request(1, addr, (uint32_t)len)) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    if (write(g_helper_fd, buf, len) != (ssize_t)len) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    uint32_t ack = 0;
    if (!read_exact(&ack, sizeof(ack))) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    bool ok = (ack == len);
    pthread_mutex_unlock(&g_pipe_mutex);
    return ok;
}

bool mem_write_byte(uint64_t addr, uint8_t val) {
    return mem_write_buf(addr, &val, 1);
}

bool mem_write_word(uint64_t addr, uint16_t val) {
    return mem_write_buf(addr, &val, 2);
}

bool mem_write_dword(uint64_t addr, uint32_t val) {
    return mem_write_buf(addr, &val, 4);
}

bool mem_write_float(uint64_t addr, float val) {
    return mem_write_buf(addr, &val, 4);
}

static bool read_maps_from_helper(char *buf, size_t buf_size, size_t *out_len) {
    if (g_helper_fd < 0) return false;
    pthread_mutex_lock(&g_pipe_mutex);
    Request req = {2, 0, 0};
    if (write(g_helper_fd, &req, sizeof(req)) != sizeof(req)) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    uint32_t len = 0;
    if (!read_exact(&len, sizeof(len))) {
        pthread_mutex_unlock(&g_pipe_mutex);
        return false;
    }
    if (len == 0) { *out_len = 0; pthread_mutex_unlock(&g_pipe_mutex); return true; }
    if (len > buf_size - 1) len = buf_size - 1;
    size_t total = 0;
    while (total < len) {
        ssize_t r = read(g_helper_fd, buf + total, len - total);
        if (r > 0) {
            total += r;
        } else if (r == 0) {
            break;
        } else if (errno == EINTR) {
            continue;
        } else {
            break;
        }
    }
    if (total == 0) { *out_len = 0; pthread_mutex_unlock(&g_pipe_mutex); return false; }
    buf[total] = '\0';
    *out_len = total;
    pthread_mutex_unlock(&g_pipe_mutex);
    return true;
}

MemRegionList *mem_parse_maps(void) {
    MemRegionList *list = calloc(1, sizeof(MemRegionList));
    if (!list) return NULL;
    list->capacity = 65536;
    list->regions = calloc(list->capacity, sizeof(MemRegion));

    char *maps_data = malloc(16777216);
    if (!maps_data) { mem_free_regions(list); return NULL; }
    size_t maps_data_cap = 16777216;
    size_t maps_len = 0;
    if (!read_maps_from_helper(maps_data, maps_data_cap, &maps_len)) {
        LOGE("mem_parse_maps: read_maps_from_helper failed");
        free(maps_data);
        mem_free_regions(list);
        return NULL;
    }
    LOGD("mem_parse_maps: got %zu bytes of maps data", maps_len);
    maps_data[maps_len] = '\0';

    int total_lines = 0;
    for (const char *p = maps_data; *p; p++) if (*p == '\n') total_lines++;
    LOGD("maps total lines: %d", total_lines);
    if (!strstr(maps_data, "split_config")) {
        LOGD("WARNING: split_config not found in raw maps");
    }

    char *line = maps_data;
    while (line && *line && list->count < list->capacity) {
        MemRegion *r = &list->regions[list->count];
        char perms[8], pathbuf[256] = {0};
        unsigned long long s, e;
        int n = sscanf(line, "%llx-%llx %7s %*x %*x:%*x %*u %255[^\n]",
                       &s, &e, perms, pathbuf);
        if (n >= 3) {
            r->start = s;
            r->end = e;
            strncpy(r->perms, perms, 7);
            if (n >= 4) strncpy(r->path, pathbuf, 255);
            list->count++;
        }
        line = strchr(line, '\n');
        if (line) line++;
    }
    free(maps_data);
    return list;
}

void mem_free_regions(MemRegionList *list) {
    if (!list) return;
    free(list->regions);
    free(list);
}

MemRegion *mem_find_region(MemRegionList *list, const char *name_substring, uint64_t min_size) {
    if (!list) return NULL;
    MemRegion *best = NULL;
    uint64_t best_size = 0;
    for (int i = 0; i < list->count; i++) {
        MemRegion *r = &list->regions[i];
        uint64_t size = r->end - r->start;
        if (size < min_size) continue;
        if (strstr(r->path, name_substring) == NULL) continue;
        if (size > best_size) {
            bool has_r = strchr(r->perms, 'r') != NULL;
            bool has_w = strchr(r->perms, 'w') != NULL;
            bool has_x = strchr(r->perms, 'x') != NULL;
            bool exec_needed = (strstr(name_substring, ".apk") != NULL);
            if ((exec_needed && has_r && has_x) || (!exec_needed)) {
                best = r;
                best_size = size;
            }
        }
    }
    return best;
}

MemScanResults *mem_scan_range(uint64_t start, uint64_t end,
                                uint32_t query, uint8_t type,
                                bool eq, uint64_t min_addr, uint64_t max_addr) {
    (void)min_addr;
    (void)max_addr;
    MemScanResults *results = calloc(1, sizeof(MemScanResults));
    if (!results) return NULL;
    results->capacity = 1024;
    results->results = calloc(results->capacity, sizeof(MemScanResult));
    if (!results->results) { free(results); return NULL; }

    size_t chunk = 4096;
    uint8_t buf[chunk];
    uint32_t val32;

    for (uint64_t pos = start; pos < end; pos += chunk) {
        size_t to_read = (end - pos < chunk) ? (size_t)(end - pos) : chunk;
        if (!mem_read_buf(pos, buf, to_read)) continue;

        for (size_t i = 0; i + type <= to_read; i += type) {
            switch (type) {
                case 1:
                    val32 = buf[i];
                    if ((eq && val32 == query) || (!eq && val32 != query)) {
                        if (results->count >= results->capacity) break;
                        results->results[results->count].address = pos + i;
                        results->results[results->count].value = val32;
                        results->results[results->count].type = type;
                        results->count++;
                    }
                    break;
                case 4: {
                    if (i + 4 > to_read) break;
                    val32 = *(uint32_t *)(&buf[i]);
                    if ((eq && val32 == query) || (!eq && val32 != query)) {
                        if (results->count >= results->capacity) break;
                        results->results[results->count].address = pos + i;
                        results->results[results->count].value = val32;
                        results->results[results->count].type = type;
                        results->count++;
                    }
                    break;
                }
            }
        }
    }
    return results;
}

void mem_free_scan_results(MemScanResults *results) {
    if (!results) return;
    free(results->results);
    free(results);
}
