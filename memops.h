#pragma once
#include <stdint.h>
#include <stdbool.h>
#include <sys/types.h>

typedef struct {
    uint64_t start;
    uint64_t end;
    char path[256];
    char perms[8];
} MemRegion;

typedef struct {
    MemRegion *regions;
    int count;
    int capacity;
} MemRegionList;

bool mem_init(const char *package_name);
bool mem_init_with_pid(pid_t pid);
void mem_shutdown(void);
bool mem_is_connected(void);
pid_t mem_get_pid(void);
bool mem_restart(const char *package_name);

uint8_t  mem_read_byte(uint64_t addr);
uint16_t mem_read_word(uint64_t addr);
uint32_t mem_read_dword(uint64_t addr);
uint64_t mem_read_u64(uint64_t addr);
float    mem_read_float(uint64_t addr);
bool     mem_read_buf(uint64_t addr, void *buf, size_t len);

bool mem_write_byte(uint64_t addr, uint8_t val);
bool mem_write_word(uint64_t addr, uint16_t val);
bool mem_write_dword(uint64_t addr, uint32_t val);
bool mem_write_float(uint64_t addr, float val);
bool mem_write_buf(uint64_t addr, const void *buf, size_t len);

MemRegionList *mem_parse_maps(void);
void mem_free_regions(MemRegionList *list);
MemRegion *mem_find_region(MemRegionList *list, const char *name_substring, uint64_t min_size);

typedef struct {
    uint64_t address;
    uint32_t value;
    uint8_t type;
} MemScanResult;

typedef struct {
    MemScanResult *results;
    int count;
    int capacity;
} MemScanResults;

bool mem_test_attach(pid_t *out_pid);
bool mem_pid_changed(const char *package_name);

MemScanResults *mem_scan_range(uint64_t start, uint64_t end,
                                uint32_t query, uint8_t type,
                                bool eq, uint64_t min_addr, uint64_t max_addr);
void mem_free_scan_results(MemScanResults *results);
