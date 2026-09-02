#include "symsolve.h"
#include "memops.h"
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#define SYM_LOG_FILE "/data/data/com.mtool.app/mtool_debug.log"
#define LOGD(...) do { FILE *f = fopen(SYM_LOG_FILE, "a"); if(f) { fprintf(f, "[%ld] syms: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)

#define READ_CHUNK 4096
#define MAX_DYNSTR (6*1024*1024)

typedef struct {
    uint64_t base;
    uint64_t dynsym;
    uint64_t dynstr;
    uint32_t sym_count;
    uint8_t *sym_cache;
    uint32_t sym_cache_len;
    uint8_t *str_cache;
    uint32_t str_cache_len;
    uint64_t bss_vaddr;
    uint64_t bss_size;
    bool inited;
} SymResolver;

static SymResolver g_sym;

static uint16_t rd16(uint64_t a) { uint16_t v = 0; mem_read_buf(a, &v, 2); return v; }
static uint32_t rd32(uint64_t a) { uint32_t v = 0; mem_read_buf(a, &v, 4); return v; }
static uint64_t rd64(uint64_t a) { uint64_t v = 0; mem_read_buf(a, &v, 8); return v; }

static bool read_chunked(uint64_t addr, uint8_t *buf, uint32_t len) {
    uint32_t done = 0;
    bool any = false;
    while (done < len) {
        uint32_t chunk = len - done;
        if (chunk > READ_CHUNK) chunk = READ_CHUNK;
        if (!mem_read_buf(addr + done, buf + done, chunk)) break;
        done += chunk;
        any = true;
    }
    return any;
}

bool sym_init(uint64_t base) {
    if (g_sym.inited && g_sym.base == base) return true;
    sym_shutdown();
    g_sym.base = base;

    uint8_t ehdr[64];
    if (!mem_read_buf(base, ehdr, sizeof(ehdr))) { LOGD("sym_init: ehdr read fail @0x%llx", base); return false; }
    if (memcmp(ehdr, "\x7f""ELF", 4) != 0) { LOGD("sym_init: not ELF @0x%llx", base); return false; }
    if (ehdr[4] != 2) { LOGD("sym_init: not ELF64"); return false; }

    uint64_t phoff = *(uint64_t*)(ehdr + 32);
    uint16_t phentsize = *(uint16_t*)(ehdr + 54);
    uint16_t phnum = *(uint16_t*)(ehdr + 56);
    LOGD("sym_init: ehdr magic=0x%02x%02x%02x%02x class=%u phoff=0x%llx entsz=%u num=%u",
         ehdr[0], ehdr[1], ehdr[2], ehdr[3], ehdr[4], phoff, phentsize, phnum);

    uint64_t dyn_va = 0;
    for (int i = 0; i < phnum; i++) {
        uint64_t ph = base + phoff + (uint64_t)i * phentsize;
        uint32_t t = rd32(ph);
        LOGD("sym_init: ph[%d] type=%u @0x%llx", i, t, ph);
        if (t == 1) {
            uint64_t vaddr = rd64(ph + 16);
            uint64_t filesz = rd64(ph + 32);
            uint64_t memsz = rd64(ph + 40);
            if (memsz > filesz) {
                g_sym.bss_vaddr = vaddr + filesz;
                g_sym.bss_size = memsz - filesz;
            }
        } else if (t == 2) {
            dyn_va = rd64(ph + 16);
        }
    }
    if (dyn_va == 0) { LOGD("sym_init: no PT_DYNAMIC (phnum=%u phoff=0x%llx)", phnum, phoff); return false; }
    uint64_t dyn = base + dyn_va;

    uint64_t symtab = 0, strtab = 0;
    uint32_t strsz = 0;
    int tag_count = 0;
    for (int i = 0; i < 1024; i++) {
        uint64_t d = dyn + (uint64_t)i * 16;
        int64_t tag = (int64_t)rd64(d);
        if (tag == 0) break;
        tag_count++;
        uint64_t val = rd64(d + 8);
        if (tag == 6) symtab = val;
        else if (tag == 5) strtab = val;
        else if (tag == 10) strsz = (uint32_t)val;
        else if (tag == 4) {
            uint32_t nb = rd32(base + val);
            uint32_t nc = rd32(base + val + 4);
            g_sym.sym_count = nc;
            LOGD("sym_init: hash nbucket=%u nchain=%u", nb, nc);
        }
    }
    if (symtab == 0 || strtab == 0) {
        LOGD("sym_init: missing symtab/strtab (tags=%d symtab=0x%llx strtab=0x%llx)", tag_count, symtab, strtab);
        return false;
    }
    g_sym.dynsym = base + symtab;
    g_sym.dynstr = base + strtab;
    if (g_sym.sym_count == 0) {
        uint64_t gap = strtab - symtab;
        g_sym.sym_count = (uint32_t)(gap / 24);
    }

    uint32_t dstr_len = strsz ? strsz : MAX_DYNSTR;
    if (dstr_len > MAX_DYNSTR) dstr_len = MAX_DYNSTR;
    g_sym.str_cache = malloc(dstr_len);
    if (!g_sym.str_cache) { LOGD("sym_init: malloc str fail"); return false; }
    g_sym.str_cache_len = read_chunked(g_sym.dynstr, g_sym.str_cache, dstr_len) ? dstr_len : 0;

    uint32_t dsym_len = g_sym.sym_count * 24;
    if (dsym_len > MAX_DYNSTR) dsym_len = MAX_DYNSTR;
    g_sym.sym_cache = malloc(dsym_len);
    if (!g_sym.sym_cache) { LOGD("sym_init: malloc sym fail"); return false; }
    g_sym.sym_cache_len = read_chunked(g_sym.dynsym, g_sym.sym_cache, dsym_len) ? dsym_len : 0;

    LOGD("sym_init: base=0x%llx symtab=0x%llx strtab=0x%llx count=%u dstr=%u dsym=%u bss_vaddr=0x%llx bss_size=0x%llx",
         g_sym.base, g_sym.dynsym, g_sym.dynstr, g_sym.sym_count,
         g_sym.str_cache_len, g_sym.sym_cache_len, g_sym.bss_vaddr, g_sym.bss_size);
    g_sym.inited = true;
    return true;
}

void sym_shutdown(void) {
    if (g_sym.sym_cache) { free(g_sym.sym_cache); g_sym.sym_cache = NULL; }
    if (g_sym.str_cache) { free(g_sym.str_cache); g_sym.str_cache = NULL; }
    memset(&g_sym, 0, sizeof(g_sym));
}

uint64_t sym_base(void) { return g_sym.base; }
uint64_t sym_bss_vaddr(void) { return g_sym.bss_vaddr; }
uint64_t sym_bss_size(void) { return g_sym.bss_size; }

uint64_t sym_find(const char *name) {
    if (!g_sym.inited || !g_sym.sym_cache || g_sym.sym_cache_len < 24) return 0;
    uint32_t n = g_sym.sym_count;
    if (n > g_sym.sym_cache_len / 24) n = g_sym.sym_cache_len / 24;
    for (uint32_t i = 0; i < n; i++) {
        const uint8_t *sym = g_sym.sym_cache + (uint64_t)i * 24;
        uint32_t st_name;
        uint64_t st_value;
        memcpy(&st_name, sym, 4);
        memcpy(&st_value, sym + 8, 8);
        if (st_name >= g_sym.str_cache_len) continue;
        const char *nm = (const char*)g_sym.str_cache + st_name;
        if (strcmp(nm, name) == 0) return st_value;
    }
    return 0;
}
