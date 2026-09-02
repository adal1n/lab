#include "hacks.h"
#include "defs.h"
#include "memops.h"
#include "symsolve.h"
#include <string.h>
#include <math.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>

#define LOG_FILE "/data/data/com.mtool.app/mtool_debug.log"
#define LOGD(...) do { FILE *f = fopen(LOG_FILE, "a"); if(f) { fprintf(f, "[%ld] hacks: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)
#define LOGE(...) do { FILE *f = fopen(LOG_FILE, "a"); if(f) { fprintf(f, "[%ld] hacks ERR: ", time(NULL)); fprintf(f, __VA_ARGS__); fprintf(f, "\n"); fclose(f); } } while(0)

static float g_black_hole_fixed_x = 0, g_black_hole_fixed_y = 0, g_black_hole_fixed_z = 0;
static bool g_black_hole_fixed_saved = false;
static double g_last_enemy_poll_time = 0;
uint64_t g_enemy_room_base = 0;
static uint64_t g_last_base_addr = 0;
static uint64_t g_last_capture_milk_base = 0;
static uint8_t g_last_weapon = 0;
static double g_last_sub_to_main_time = 0;
static double g_last_bh_alive_write = 0;

bool g_enemy_room_found = false;
EnemyInfo g_enemies[MAX_ENEMIES];
int g_enemy_count = 0;

// ---- シンボルベース自動修復（XA_* 系） ----
typedef struct {
    const char *sym;       // .dynsym の mangled 名
    uint64_t rel;          // 関数開始からの相対オフセット
    uint64_t def;          // フォールバック用デフォルト（xa_start 相対）
    uint32_t expect;       // 期待する命令（検証用、0 なら検証なし）
} XASymPatch;

static const XASymPatch XA_PATCHES[] = {
    { SYM_GET_RESPAWN_TIME,   0x018, XA_RESPAWN_OFF,      0x1e200820 }, // fmul s0,s1,s0
    { SYM_ON_MOVE_INPUT_MOVED,0x17c, XA_MOVE_SPEED_OFF,   0xbd4ed440 }, // ldr s0,[x2,#0xed4]
    { SYM_GET_SKILL_DMG,      0x060, XA_SKILL_DMG_OFF,    0xb8469002 }, // ldur w2,[x0,#0x69]
    { SYM_CALC_MOVE_POS_GS,   0x19ec,XA_NOCLIP_MATCH_OFF, 0x3c23d70a }, // float 0.01
    { SYM_CALC_MOVE_POS_TS,   0x1014,XA_NOCLIP_TOWN_OFF,  0x3c23d70a }, // float 0.01
    { SYM_SHAKE_CAMERA,       0x048, XA_RECOIL_OFF,       0xbd000420 }, // str s0,[x1,#0x4]
    { SYM_GET_AIM_GAP,        0x0dc, XA_SPREAD_JZ_OFF,    0xbd403aa0 }, // ldr s0,[x21,#0x38]
    { SYM_GET_AIM_GAP,        0x0fc, XA_SPREAD_MZ_OFF,    0xbd4036a0 }, // ldr s0,[x21,#0x34]
    { SYM_GET_AIM_GAP,        0x104, XA_SPREAD_J_OFF,     0xbd402aa0 }, // ldr s0,[x21,#0x28]
    { SYM_GET_AIM_GAP,        0x124, XA_SPREAD_SZ_OFF,    0xbd4032a0 }, // ldr s0,[x21,#0x30]
    { SYM_GET_AIM_GAP,        0x144, XA_SPREAD_IZ_OFF,    0xbd402ea0 }, // ldr s0,[x21,#0x2c]
    { SYM_GET_AIM_GAP,        0x14c, XA_SPREAD_I_OFF,     0xbd401ea0 }, // ldr s0,[x21,#0x1c]
    { SYM_GET_RELOAD_RATE,    0x010, XA_RELOAD_OFF,       0xbc417000 }, // ldur s0,[x0,#0x17]
    { SYM_GET_AIM_SPREAD_M,   0x010, XA_SPREAD1_OFF,      0xbd402400 }, // ldr s0,[x0,#0x24]
    { SYM_GET_AIM_SPREAD_S,   0x010, XA_SPREAD2_OFF,      0xbd402000 }, // ldr s0,[x0,#0x20]
    { SYM_GET_HEAD_DMG,       0x010, XA_HEAD_ONEPUNCH_OFF,0xbc40f000 }, // ldur s0,[x0,#0xf]
    { SYM_GET_BODY_DMG,       0x000, XA_BODY_ONEPUNCH_OFF,0x1e2e1000 }, // fmov s0,#1.0
};

// ゲーム更新で命令が関数内/近傍に移動しても自動追従できるよう、
// シンボル先頭から 64KB をバルク読みして期待命令を全走査する。
// 複数ヒット時は既知 delta (p->rel) に最も近いものを採用する。
#define XA_SCAN_BYTES 0x10000ULL

static uint64_t resolve_xa(const XASymPatch *p, uint64_t xa_base) {
    uint64_t sv = sym_find(p->sym);
    if (!sv) {
        LOGD("resolve_xa: %s not found, fallback 0x%llx", p->sym,
             (unsigned long long)(xa_base + p->def));
        return xa_base + p->def;
    }
    uint64_t fs = sym_base() + sv;
    uint64_t first = fs + p->rel;

    // memhelper は1リクエスト最大4KB制限のためチャンク分割で読み込む
    static uint8_t scanbuf[XA_SCAN_BYTES];
    size_t filled = 0;
    while (filled < XA_SCAN_BYTES) {
        size_t chunk = XA_SCAN_BYTES - filled;
        if (chunk > 4096) chunk = 4096;
        if (!mem_read_buf(fs + filled, scanbuf + filled, chunk)) break;
        filled += chunk;
    }
    if (p->rel + 4 > filled) {
        LOGD("resolve_xa: %s range short (%zu/%llu), fallback 0x%llx", p->sym,
             filled, (unsigned long long)XA_SCAN_BYTES,
             (unsigned long long)(xa_base + p->def));
        return xa_base + p->def;
    }

    // fast path: 既知 delta の位置が期待命令のまま (通常ケース)
    if (p->expect) {
        uint32_t insn = (uint32_t)scanbuf[p->rel]
                | ((uint32_t)scanbuf[p->rel + 1] << 8)
                | ((uint32_t)scanbuf[p->rel + 2] << 16)
                | ((uint32_t)scanbuf[p->rel + 3] << 24);
        if (insn == p->expect) {
            LOGD("resolve_xa: %s => +0x%llx (delta ok)", p->sym,
                 (unsigned long long)p->rel);
            return first;
        }
    } else {
        return first;
    }

    // シフト検出: 全範囲走査し既知 delta に最も近いヒットを採用
    long relW = (long)(p->rel / 4);
    long bestDist = -1;
    long bestOff = -1;
    int hits = 0;
    for (long off = 0; off + 4 <= (long)XA_SCAN_BYTES; off += 4) {
        uint32_t v = (uint32_t)scanbuf[off]
                | ((uint32_t)scanbuf[off + 1] << 8)
                | ((uint32_t)scanbuf[off + 2] << 16)
                | ((uint32_t)scanbuf[off + 3] << 24);
        if (v == p->expect) {
            hits++;
            long d = off / 4 - relW;
            if (d < 0) d = -d;
            if (bestDist < 0 || d < bestDist) {
                bestDist = d;
                bestOff = off;
            }
        }
    }
    if (bestOff >= 0) {
        uint64_t addr = fs + (uint64_t)bestOff;
        LOGD("resolve_xa: %s shifted: known delta +0x%llx -> found +0x%llx (hits=%d)",
             p->sym, (unsigned long long)p->rel,
             (unsigned long long)bestOff, hits);
        return addr;
    }
    {
        // 診断: 静的フォールバック先の実バイトを確認 (想定定数と一致するか)
        uint64_t defaddr = xa_base + p->def;
        uint8_t db[4] = {0,0,0,0};
        mem_read_buf(defaddr, db, 4);
        LOGD("resolve_xa: %s expect %08x not found in +%llu; fallback @%llx bytes=%02x%02x%02x%02x",
             p->sym, p->expect, (unsigned long long)XA_SCAN_BYTES,
             (unsigned long long)defaddr, db[3], db[2], db[1], db[0]);
    }
    return xa_base + p->def;
}

// ---- ロスター (UserInfoManager) 自動修復 ----
// Manager::User() を .dynsym から探し、先頭命令 (adrp + ldr [x0,#imm]) を
// デコードして SLOT_MGR (マネージャポインタを保持する vaddr) を導出する。
// 続く ldr x0,[x0] / ret を検証し、失敗時は defs.h の SLOT_MGR 定数にフォールバック。
static uint64_t resolve_roster_slot(uint64_t xa_base) {
    uint64_t sv = sym_find(SYM_MANAGER_USER);
    if (sv == 0) {
        LOGD("resolve_roster: %s not found, fallback SLOT_MGR=0x%llx", SYM_MANAGER_USER, (unsigned long long)SLOT_MGR);
        return 0;
    }
    uint64_t f = sym_base() + sv;
    uint32_t insn0 = mem_read_dword(f);
    uint32_t insn1 = mem_read_dword(f + 4);
    uint32_t insn2 = mem_read_dword(f + 8);
    uint32_t insn3 = mem_read_dword(f + 12);
    if ((insn0 & 0x9F000000u) != 0x90000000u) {
        LOGD("resolve_roster: %s insn0 not ADRP (0x%08x), fallback SLOT_MGR=0x%llx", SYM_MANAGER_USER, insn0, (unsigned long long)SLOT_MGR);
        return 0;
    }
    int64_t imm = ((int64_t)((insn0 >> 5) & 0x7FFFF) << 2) | ((insn0 >> 29) & 0x3);
    if (imm & 0x100000) imm -= 0x200000;
    // ADRP は PC 相対: vaddr 基準でページ計算する (sym_find は vaddr を返す)
    uint64_t target_page = (sv & ~0xFFFULL) + (uint64_t)(imm << 12);
    if ((insn1 & 0xFFC00000u) != 0xF9400000u) {
        LOGD("resolve_roster: %s insn1 not LDR (0x%08x), fallback SLOT_MGR=0x%llx", SYM_MANAGER_USER, insn1, (unsigned long long)SLOT_MGR);
        return 0;
    }
    uint64_t off = ((uint64_t)((insn1 >> 10) & 0xFFF)) << 3;
    if (insn2 != 0xF9400000u || insn3 != 0xD65F03C0u) {
        LOGD("resolve_roster: %s verify fail insn2=0x%08x insn3=0x%08x, fallback SLOT_MGR=0x%llx", SYM_MANAGER_USER, insn2, insn3, (unsigned long long)SLOT_MGR);
        return 0;
    }
    uint64_t slot_mgr = target_page + off;
    LOGD("resolve_roster: %s => SLOT_MGR=0x%llx (page=0x%llx off=0x%llx)", SYM_MANAGER_USER, (unsigned long long)slot_mgr, (unsigned long long)target_page, (unsigned long long)off);
    return slot_mgr;
}

#define ROSTER_SLOT_FILE "/data/data/com.mtool.app/roster_slot.bin"

static void roster_save_persisted(uint64_t slot_mgr) {
    FILE *f = fopen(ROSTER_SLOT_FILE, "wb");
    if (!f) { LOGE("roster_save_persisted: open %s failed", ROSTER_SLOT_FILE); return; }
    size_t n = fwrite(&slot_mgr, 1, sizeof(slot_mgr), f);
    fclose(f);
    LOGD("roster_save_persisted: wrote %zu bytes => 0x%llx", n, (unsigned long long)slot_mgr);
}

void roster_load_persisted(GameState *gs) {
    if (gs == NULL) return;
    FILE *f = fopen(ROSTER_SLOT_FILE, "rb");
    if (!f) { gs->roster_slot_mgr = 0; gs->roster_resolved = false; return; }
    uint64_t slot_mgr = 0;
    size_t n = fread(&slot_mgr, 1, sizeof(slot_mgr), f);
    fclose(f);
    if (n == sizeof(slot_mgr) && slot_mgr != 0) {
        gs->roster_slot_mgr = slot_mgr;
        gs->roster_resolved = true;
        LOGD("roster_load_persisted: restored SLOT_MGR=0x%llx", (unsigned long long)slot_mgr);
    } else {
        gs->roster_slot_mgr = 0;
        gs->roster_resolved = false;
    }
}

// 構造オフセット (bucket@0 / modulus@0x8 / size@0x18 等) を disasm から検証する。
// 現行バージョンでは SLOT_MGR のみが変動するため、不一致時はログ警告のみで
// 自動導出は行わない (フォールバックは hacks_scan_enemies の定数)。
static uint64_t ldr_imm_off(uint32_t insn) {
    // LDR/LDRB (unsigned immediate): imm12 を size フィールド (bits[31:30]) でスケーリング
    uint32_t scale = 1u << ((insn >> 30) & 3u);
    return ((uint64_t)((insn >> 10) & 0xFFF)) * scale;
}

// GetUserBy* の命令デコードから導出した動的オフセット。
// 0 のフィールドは静的デフォルト (ROSTER_*_OFF) へフォールバックする。
static struct {
    uint64_t bucket, modulus, size, list_head;
} g_roster_dyn;

static int roster_off_sane(uint64_t off) {
    return off > 0 && off <= 0x400;
}

static uint64_t roster_bucket_off(void) {
    return roster_off_sane(g_roster_dyn.bucket) ? g_roster_dyn.bucket : ROSTER_BUCKET_OFF;
}
static uint64_t roster_modulus_off(void) {
    return roster_off_sane(g_roster_dyn.modulus) ? g_roster_dyn.modulus : ROSTER_MODULUS_OFF;
}
static uint64_t roster_size_off(void) {
    return roster_off_sane(g_roster_dyn.size) ? g_roster_dyn.size : ROSTER_SIZE_OFF;
}

static void derive_roster_offsets(uint64_t xa_base) {
    (void)xa_base;
    memset(&g_roster_dyn, 0, sizeof(g_roster_dyn));
    int got_seq = 0, got_size = 0;
    uint64_t sv = sym_find(SYM_UIM_GET_BY_SEQ);
    if (sv != 0) {
        uint64_t f = sym_base() + sv;
        uint32_t i0 = mem_read_dword(f);      // ldr x4,[x0,#imm] -> modulus
        uint32_t i2 = mem_read_dword(f + 8);  // ldr x2,[x0]      -> bucket
        uint64_t mo = ldr_imm_off(i0);
        uint64_t bo = ldr_imm_off(i2);
        if (mo > 0 && mo <= 0x400 && bo <= 0x400) {
            g_roster_dyn.modulus = mo;
            g_roster_dyn.bucket = bo;
            got_seq = 1;
        }
    }
    sv = sym_find(SYM_UIM_SIZE);
    if (sv != 0) {
        uint64_t f = sym_base() + sv;
        uint32_t i0 = mem_read_dword(f);      // ldr w0,[x0,#imm] -> size
        uint64_t sz_off = ldr_imm_off(i0);
        if (sz_off > 0 && sz_off <= 0x400) {
            g_roster_dyn.size = sz_off;
            got_size = 1;
        }
    }
    sv = sym_find(SYM_UIM_GET_BY_ORDER);
    if (sv != 0) {
        uint64_t f = sym_base() + sv;
        uint32_t i0 = mem_read_dword(f);      // ldr x2,[x0,#imm] -> node list head
        uint64_t lst_off = ldr_imm_off(i0);
        if (lst_off > 0 && lst_off <= 0x400) g_roster_dyn.list_head = lst_off;
        LOGD("derive_roster: GetUserByOrder list_off=%llx", (unsigned long long)lst_off);
    }
    LOGD("derive_roster: bucket=%llx->%llx modulus=%llx->%llx size=%llx->%llx (seq=%d size=%d)",
         (unsigned long long)ROSTER_BUCKET_OFF,
         (unsigned long long)(g_roster_dyn.bucket ? g_roster_dyn.bucket : ROSTER_BUCKET_OFF),
         (unsigned long long)ROSTER_MODULUS_OFF,
         (unsigned long long)(g_roster_dyn.modulus ? g_roster_dyn.modulus : ROSTER_MODULUS_OFF),
         (unsigned long long)ROSTER_SIZE_OFF,
         (unsigned long long)(g_roster_dyn.size ? g_roster_dyn.size : ROSTER_SIZE_OFF),
         got_seq, got_size);
}

void hacks_resolve_roster(GameState *gs) {
    if (gs->xa_start == 0) return;
    uint64_t slot = resolve_roster_slot(gs->xa_start);
    if (slot != 0) {
        gs->roster_slot_mgr = slot;
        gs->roster_resolved = true;
        roster_save_persisted(slot);
    } else {
        gs->roster_slot_mgr = 0;
        gs->roster_resolved = false;
    }
    derive_roster_offsets(gs->xa_start);
}

double now_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

void hacks_reset_enemies(void) {
    g_enemy_count = 0;
    LOGD("hacks_reset_enemies: enemy list cleared");
}

void hacks_reset_all_state(void) {
    g_enemy_count = 0;
    g_enemy_room_found = false;
    g_last_base_addr = 0;
    g_last_capture_milk_base = 0;
    sym_shutdown();
    LOGD("hacks_reset_all_state: full state cleared (including roster mgr)");
}

bool hacks_discover_regions(GameState *gs) {
    gs->off_check_valid = false;
    FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "=== hacks_discover_regions entered ===\n"); fclose(tf); }

    MemRegionList *maps = mem_parse_maps();
    if (!maps) { FILE *tf2 = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf2) { fprintf(tf2, "mem_parse_maps failed\n"); fclose(tf2); } return false; }

    { FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "total maps: %d\n", maps->count); fclose(tf); } }

    MemRegion *xa = NULL;
    const char *xa_names[] = {"split_config.arm64_v8a.apk", "base.apk", NULL};
    for (int i = 0; xa_names[i]; i++) {
        xa = mem_find_region(maps, xa_names[i], XA_MIN_SIZE);
        if (xa) break;
    }
    if (!xa) {
        { FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "XA NOT FOUND\n"); fclose(tf); } }
        mem_free_regions(maps);
        return false;
    }
    gs->xa_start = xa->start;
    gs->xa_end = xa->end;
    { FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "XA: 0x%llx-0x%llx path=%s\n", xa->start, xa->end, xa->path); fclose(tf); } }
    MemRegion *bss = NULL;
    for (int i = 0; i < maps->count; i++) {
        MemRegion *r = &maps->regions[i];
        uint64_t size = r->end - r->start;
        if (size < XA_MIN_SIZE) continue;
        if (strstr(r->path, "[anon:.bss]") == NULL) continue;
        if (r->start <= xa->end) continue;
        bss = r;
        break;
    }
    if (!bss) {
        { FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "BSS NOT FOUND after XA\n"); fclose(tf); } }
        mem_free_regions(maps);
        return false;
    }
    gs->bss_start = bss->start;
    gs->bss_end = bss->end;
    uint64_t bss_map_sz = bss->end - bss->start;
    { FILE *tf = fopen("/data/data/com.mtool.app/debug_hdr.txt", "a"); if(tf) { fprintf(tf, "BSS: 0x%llx-0x%llx\n", bss->start, bss->end); fclose(tf); } }

    mem_free_regions(maps);

    sym_init(gs->xa_start);
    roster_load_persisted(gs);
    {
        uint64_t *outs[] = {
            &gs->xa_respawn, &gs->xa_move_speed, &gs->xa_skill_dmg,
            &gs->xa_noclip_match, &gs->xa_noclip_town, &gs->xa_recoil,
            &gs->xa_spread_jz, &gs->xa_spread_mz, &gs->xa_spread_j,
            &gs->xa_spread_sz, &gs->xa_spread_iz, &gs->xa_spread_i,
            &gs->xa_reload, &gs->xa_spread1, &gs->xa_spread2,
            &gs->xa_head_onepunch, &gs->xa_body_onepunch,
        };
        for (int i = 0; i < (int)(sizeof(XA_PATCHES)/sizeof(XA_PATCHES[0])); i++) {
            *outs[i] = resolve_xa(&XA_PATCHES[i], gs->xa_start);
        }
    }
    {
        uint64_t elf_sz = sym_bss_size();
        uint64_t page_sz = (elf_sz + 0xFFF) & ~0xFFFULL;
        if (elf_sz && (bss_map_sz < page_sz - 0x1000 || bss_map_sz > page_sz + 0x1000)) {
            LOGD("WARN: bss size mismatch map=0x%llx elf_page=0x%llx", bss_map_sz, page_sz);
        } else {
            LOGD("bss size OK map=0x%llx elf_page=0x%llx", bss_map_sz, page_sz);
        }
    }
    gs->pitch_addr         = gs->bss_start + BSS_PITCH_OFF;
    gs->yaw_addr           = gs->bss_start + BSS_YAW_OFF;
    gs->capture_milk_addr  = gs->bss_start + BSS_CAPTURE_MILK_OFF;

    gs->base_addr = 0;
    gs->base_valid = false;
    g_last_base_addr = 0;
    hacks_reset_enemies();
    return true;
}

bool hacks_discover_base(GameState *gs) {
    if (gs->bss_start == 0) return false;

    uint8_t bytes[5];
    for (int i = 0; i < 5; i++) {
        uint64_t addr = gs->bss_start + BSS_BASE_BYTE_OFFSETS[i];
        bytes[i] = mem_read_byte(addr);
    }
    uint64_t ptr = ((uint64_t)(bytes[0] & 0xFF) << 32) |
                   ((uint64_t)(bytes[1] & 0xFF) << 24) |
                   ((uint64_t)(bytes[2] & 0xFF) << 16) |
                   ((uint64_t)(bytes[3] & 0xFF) << 8)  |
                   ((uint64_t)(bytes[4] & 0xFF));

    gs->base_addr = ptr;
    gs->player_team_parity = mem_read_byte(ptr + OFF_TEAM_PARITY) % 2;
    if (!gs->off_check_valid) {
        uint32_t check = mem_read_dword(ptr + OFF_CHECK);
        uint32_t expect = OFF_CHECK_EXPECT;
        static bool g_off_check_warned = false;
        if (expect != 0 && check != expect) {
            gs->off_check_valid = false;
            if (!g_off_check_warned) {
                g_off_check_warned = true;
                LOGD("WARN: OFF_CHECK mismatch base=0x%llx check=0x%x expect=0x%x", ptr, check, expect);
            }
        } else {
            gs->off_check_valid = true;
            LOGD("off_check base=0x%llx check=0x%x expect=0x%x", ptr, check, expect);
        }
    }
    gs->base_valid = true;
    if (ptr != g_last_base_addr) {
        hacks_reset_enemies();
        g_last_base_addr = ptr;
    }
    return true;
}

bool hacks_discover_all(GameState *gs) {
    if (gs->xa_start == 0 && gs->bss_start == 0) {
        if (!hacks_discover_regions(gs)) return false;
    }
    if (!gs->base_valid) {
        return hacks_discover_base(gs);
    }
    return true;
}

static void write_xa_dword(GameState *gs, uint64_t addr, uint32_t enable_val, uint32_t disable_val, bool enable) {
    (void)gs;
    mem_write_dword(addr, enable ? enable_val : disable_val);
}

static void write_xa_float(GameState *gs, uint64_t addr, float enable_val, float disable_val, bool enable) {
    (void)gs;
    mem_write_float(addr, enable ? enable_val : disable_val);
}

static const struct { float value; uint32_t word; } FMOV_IMM_TABLE[] = {
    { 1.0f,  0x1E2E1000 }, { 1.5f,  0x1E2F1000 },
    { 2.0f,  0x1E201000 }, { 2.5f,  0x1E209000 },
    { 3.0f,  0x1E211000 }, { 3.5f,  0x1E219000 },
    { 4.0f,  0x1E221000 }, { 4.5f,  0x1E225000 },
    { 5.0f,  0x1E229000 }, { 5.5f,  0x1E22D000 },
    { 6.0f,  0x1E231000 }, { 6.5f,  0x1E235000 },
    { 7.0f,  0x1E239000 }, { 7.5f,  0x1E23D000 },
    { 8.0f,  0x1E241000 }, { 8.5f,  0x1E243000 },
    { 9.0f,  0x1E245000 }, { 9.5f,  0x1E247000 },
    { 10.0f, 0x1E249000 }, { 10.5f, 0x1E24B000 },
    { 11.0f, 0x1E24D000 }, { 11.5f, 0x1E24F000 },
    { 12.0f, 0x1E251000 }, { 12.5f, 0x1E253000 },
    { 13.0f, 0x1E255000 }, { 13.5f, 0x1E257000 },
    { 14.0f, 0x1E259000 }, { 14.5f, 0x1E25B000 },
    { 15.0f, 0x1E25D000 }, { 15.5f, 0x1E25F000 },
};

static uint32_t fmov_imm_for_speed(float mult) {
    if (mult < 1.0f) return 0x1E2E1000;
    if (mult > 15.5f) return 0x1E25F000;
    unsigned n = sizeof(FMOV_IMM_TABLE) / sizeof(FMOV_IMM_TABLE[0]);
    uint32_t best = FMOV_IMM_TABLE[0].word;
    float best_d = fabsf(FMOV_IMM_TABLE[0].value - mult);
    for (unsigned i = 1; i < n; i++) {
        float d = fabsf(FMOV_IMM_TABLE[i].value - mult);
        if (d < best_d) { best_d = d; best = FMOV_IMM_TABLE[i].word; }
    }
    return best;
}

void hacks_apply_xa(const ToggleState *ts, GameState *gs) {
    write_xa_dword(gs, gs->xa_reload,
        505925632,
        (uint32_t)-1136562176,
        ts->toggles[TOG_RELOAD]);

    write_xa_dword(gs, gs->xa_head_onepunch,
        505925632,
        (uint32_t)-1136594944,
        ts->toggles[TOG_DAMAGE_UP_GUN]);

    write_xa_dword(gs, gs->xa_body_onepunch,
        505925632,
        506335232,
        ts->toggles[TOG_DAMAGE_UP_GUN]);

    write_xa_dword(gs, gs->xa_skill_dmg,
        1384718338,
        (uint32_t)-1203335166,
        ts->toggles[TOG_DAMAGE_UP_SKILL]);

    write_xa_dword(gs, gs->xa_respawn,
        505415680,
        505415712,
        ts->toggles[TOG_RESPAWN]);

    write_xa_float(gs, gs->xa_noclip_match, 100.0f, 0.01f, ts->toggles[TOG_NO_CLIP]);
    write_xa_float(gs, gs->xa_noclip_town, 100.0f, 0.01f, ts->toggles[TOG_NO_CLIP]);

    bool recoil = ts->toggles[TOG_RECOIL];
    uint32_t recoil_enable = 505942016;
    uint32_t recoil_disable_values[] = {
        (uint32_t)-1124072416,
        (uint32_t)-1119869952,
        (uint32_t)-1119870976,
        (uint32_t)-1119871328,
        (uint32_t)-1119867232,
        (uint32_t)-1119868256,
        (uint32_t)-1119864160,
        (uint32_t)-1119865184,
        (uint32_t)-1119866208,
    };
    uint64_t recoil_addrs[] = {
        gs->xa_recoil, gs->xa_spread1, gs->xa_spread2,
        gs->xa_spread_i, gs->xa_spread_iz, gs->xa_spread_j,
        gs->xa_spread_jz, gs->xa_spread_mz, gs->xa_spread_sz,
    };
    for (int i = 0; i < 9; i++) {
        mem_write_dword(recoil_addrs[i], recoil ? recoil_enable : recoil_disable_values[i]);
    }

    write_xa_dword(gs, gs->xa_move_speed,
        fmov_imm_for_speed(ts->speed_multiplier),
        0xBD4ED440,
        ts->toggles[TOG_SPEED]);
}

void hacks_apply_all(const ToggleState *ts, GameState *gs) {
    bool base_ok = (gs->base_valid && gs->base_addr != 0);
    uint64_t b = base_ok ? gs->base_addr : 0;

    if (!base_ok) return;

    if (ts->toggles[TOG_SHOOT]) {
        mem_write_float(b + OFF_MAIN_SPEED, -1000.0f);
        mem_write_float(b + OFF_SUB_SPEED, -1000.0f);
    }

    if (ts->toggles[TOG_BLACK_HOLE]) {
        float pz = mem_read_float(b + OFF_Z);
        float py = mem_read_float(b + OFF_Y);
        float px = mem_read_float(b + OFF_X);
        float dy = py + BLACK_HOLE_HEIGHT_OFF;

        float tz, tx;
        for (int dir = -1; dir <= 1; dir += 2) {
            tz = pz + BLACK_HOLE_RANGE * dir;
            for (int d2 = -1; d2 <= 1; d2 += 2) {
                tx = px + BLACK_HOLE_RANGE * d2;
                (void)tz; (void)tx;
            }
        }

        if (ts->toggles[TOG_BLACK_HOLE_FIXED]) {
            if (!g_black_hole_fixed_saved) {
                g_black_hole_fixed_x = px;
                g_black_hole_fixed_y = py;
                g_black_hole_fixed_z = pz;
                g_black_hole_fixed_saved = true;
            }
        } else {
            g_black_hole_fixed_saved = false;
        }
    } else {
        g_black_hole_fixed_saved = false;
    }

    if (ts->toggles[TOG_BAVA_HACK] || ts->toggles[TOG_KDA_BOOSTER]) {

        if (ts->toggles[TOG_KDA_BOOSTER]) {
            mem_write_byte(b + OFF_COOLDOWN_0, 14);
            mem_write_byte(b + OFF_INF_SKILL_G, 1);
        }

        if (ts->toggles[TOG_BAVA_HACK]) {
            mem_write_float(b + OFF_TIMER, 0.98f);
            mem_write_byte(b + OFF_COOLDOWN_0, 25);
            mem_write_byte(b + OFF_INF_SKILL_G, 1);
        }
    }

}

// ---- ロスター歩行 (milk_plugin / monitor_roster と同一方式) ----

// ボット名リスト (milk_plugin main.cpp と同一内容)
static const char *BOT_NAMES[] = {
    "ふじい",
    "Adam", "Agt.Neo", "Agt.Smith", "Alvaro", "Carlos", "Coatney",
    "Cò Công", "Daniel", "Desai", "Débora", "Đinh Hải", "Elena",
    "Fábio", "Jared", "Jonathan", "Kayo", "Matthew", "Nazmi", "Nadia",
    "Nicolás", "Okada", "Pooja", "Santos", "Sánchez", "Stephen",
    "Sharda", "Tom", "Tffany", "Tiffany", "Thuy", "Thuỷ", "Takeshi",
    "Xiaohu", "Xu Jizhe",
    "赤井林檎", "前田智史", "郭立新", "朱仲斌", "韋德玲", "罗楠",
    "로재", "다재", "햇세", "하민훈", "신서우", "한서윤", "신온유",
    "Лимон", "Кирилл", "Сычева",
    "นายณัฐวุฒิ", "الحنين", "พุทธพร", "ญา ลิต", "'ญา ลิต",
    "　", "︎︎",
};

// 方向制御文字 (U+200E/U+200F/U+202A..U+202E) の UTF-8 バイト列 (E2 80 8E 等) を除去する
static bool bot_name_matches(const char *name) {
    static char norm[ENEMY_NAME_LEN];
    const unsigned char *p = (const unsigned char *)name;
    size_t n = 0;
    while (p[n]) n++;
    size_t o = 0;
    for (size_t i = 0; i < n && o < sizeof(norm) - 1; i++) {
        if (i + 2 < n && p[i] == 0xE2 && p[i + 1] == 0x80 &&
            p[i + 2] >= 0x8E && p[i + 2] <= 0xAE) {
            i += 2;
            continue;
        }
        norm[o++] = (char)p[i];
    }
    norm[o] = '\0';
    for (size_t i = 0; i < sizeof(BOT_NAMES) / sizeof(BOT_NAMES[0]); i++) {
        if (strcmp(norm, BOT_NAMES[i]) == 0) return true;
    }
    return false;
}

// 名前読み取り (制御文字・表示不可能文字で停止)
static void read_user_name(uint64_t ui, char *out, size_t cap) {
    char buf[ENEMY_NAME_LEN + 1];
    memset(buf, 0, sizeof(buf));
    if (!mem_read_buf(ui + OFF_NAME, buf, ENEMY_NAME_LEN)) {
        out[0] = '\0';
        return;
    }
    size_t o = 0;
    for (size_t i = 0; i < ENEMY_NAME_LEN && o + 1 < cap; i++) {
        char ch = buf[i];
        if (ch == '\0') break;
        if ((unsigned char)ch < 0x20 && ch != '\t') break;
        out[o++] = ch;
    }
    out[o] = '\0';
}

bool hacks_scan_enemies(const ToggleState *ts, GameState *gs, EnemyInfo *enemies, int *count, int max_count) {
    *count = 0;
    if (!gs->base_valid) return false;
    if (gs->base_addr == 0) return false;
    if (gs->xa_start == 0) return false;

    double t = now_seconds();
    if (t - g_last_enemy_poll_time < ENEMY_POLL_INTERVAL_S) return false;
    g_last_enemy_poll_time = t;

    // Manager::User のグローバル: base + SLOT_MGR を読み、その値(slot)をさらに deref -> UserInfoManager*
    // SLOT_MGR は .dynsym (_ZN7Manager4UserEv) から自動解決、未解決時は定数フォールバック
    uint64_t slot_off = (gs->roster_resolved && gs->roster_slot_mgr != 0) ? gs->roster_slot_mgr : SLOT_MGR;
    uint64_t slot = mem_read_u64(gs->xa_start + slot_off);
    if (slot == 0) { LOGD("roster: slot null (xa_start=0x%llx)", (unsigned long long)gs->xa_start); return false; }
    uint64_t mgr = mem_read_u64(slot);
    g_enemy_room_found = (mgr != 0);
    g_enemy_room_base = mgr;
    if (mgr == 0) { LOGD("roster: mgr null (slot=0x%llx)", (unsigned long long)slot); return false; }

    uint64_t bucket_array = mem_read_u64(mgr + roster_bucket_off());
    uint32_t modulus = mem_read_dword(mgr + roster_modulus_off());
    uint32_t size_cnt = mem_read_dword(mgr + roster_size_off());
    LOGD("roster: mgr=0x%llx bucket=0x%llx modulus=%u size=%u",
         (unsigned long long)mgr, (unsigned long long)bucket_array, modulus, size_cnt);
    if (bucket_array == 0 || modulus == 0 || modulus > 4096) return false;

    EnemyInfo found[ROSTER_MAX_FOUND];
    int nfound = 0;
    uint64_t seen[ROSTER_MAX_FOUND];
    int c_raw = 0, c_dup = 0, c_magic = 0, c_id = 0, c_name = 0, c_bot = 0, c_team = 0;
    bool have_enemy_parity = false;

    for (uint32_t b = 0; b < modulus && nfound < ROSTER_MAX_FOUND; b++) {
        uint64_t node = mem_read_u64(bucket_array + (uint64_t)b * 8);
        uint32_t guard = 0;
        while (node != 0 && node > 0x100000 && guard < ROSTER_WALK_GUARD && nfound < ROSTER_MAX_FOUND) {
            uint64_t ui = mem_read_u64(node + NODE_UI_OFF);
            if (ui > 0x100000) {
                c_raw++;
                bool dup = false;
                for (int i = 0; i < nfound; i++) {
                    if (seen[i] == ui) { dup = true; break; }
                }
                if (dup) { c_dup++; }
                else {
                    uint32_t id = mem_read_dword(ui);
                    char name[ENEMY_NAME_LEN];
                    read_user_name(ui, name, sizeof(name));
                    uint8_t p = mem_read_byte(ui + OFF_TEAM_PARITY) % 2;
                    uint32_t magic = mem_read_dword(ui + OFF_CHECK);
                    if ((magic & USER_MAGIC_MASK) != USER_MAGIC_EXPECT) { c_magic++; }
                    else if (id == 0 || id == gs->player_id) { c_id++; }
                    else if (name[0] == '\0') { c_name++; }
                    else if (ts->toggles[TOG_EXCLUDE_BOT] && id > ID_NORMAL_MAX) { c_bot++; }
                    else if (id > ID_NORMAL_MAX && !bot_name_matches(name)) { c_bot++; }
                    else {
                        if (p != gs->player_team_parity) have_enemy_parity = true;
                        EnemyInfo *e = &found[nfound];
                        e->base = ui;
                        e->id = id;
                        memcpy(e->name, name, sizeof(e->name));
                        e->team_parity = p;
                        e->slot = mem_read_byte(ui + OFF_TEAM_PARITY);
                        uint8_t death_status = mem_read_byte(ui + OFF_ALIVE);
                        e->alive = (death_status != 16);
                        e->enabled = true;
                        seen[nfound] = ui;
                        nfound++;
                    }
                }
            }
            node = mem_read_u64(node + NODE_NEXT_OFF);
            guard++;
        }
    }

    // マッチ/ロビー判定: 自己と異なるパリティが1人でも居ればマッチ
    // マッチでは従来通り敵チームのみ表示(TOG_ALL_ENEMY 時は全員)
    // ロビーではチーム未分離のためチームフィルタを無効化し全員表示
    if (have_enemy_parity && !ts->toggles[TOG_ALL_ENEMY]) {
        int w = 0;
        for (int i = 0; i < nfound; i++) {
            if (found[i].team_parity != gs->player_team_parity) {
                found[w++] = found[i];
            } else {
                c_team++;
            }
        }
        nfound = w;
    }

    LOGD("roster: found=%d raw=%d dup=%d magic=%d id=%d name=%d bot=%d team=%d match=%d",
         nfound, c_raw, c_dup, c_magic, c_id, c_name, c_bot, c_team, (int)have_enemy_parity);
    if (nfound == 0 && size_cnt > 1) {
        LOGD("WARN: roster empty walk (size=%u modulus=%u bucket_off=%llu mod_off=%llu sz_off=%llu)"
             " - offsets may be stale, check derive_roster log",
             size_cnt, modulus,
             (unsigned long long)roster_bucket_off(),
             (unsigned long long)roster_modulus_off(),
             (unsigned long long)roster_size_off());
    }
    for (int i = 0; i < nfound && i < max_count; i++) {
        enemies[i] = found[i];
    }
    *count = (nfound < max_count) ? nfound : max_count;
    return (*count > 0);
}

void hacks_apply_aimbot(EnemyInfo *enemies, int enemy_count, const ToggleState *ts, GameState *gs) {
    if (!gs->base_valid || enemy_count == 0) return;
    bool aim_bot = ts->toggles[TOG_AIM_BOT];
    bool aim_assist = ts->toggles[TOG_AIM_ASSIST];
    if (!aim_bot && !aim_assist) return;

    uint8_t weapon = mem_read_byte(gs->base_addr + OFF_WEAPON);

    if (weapon != g_last_weapon) {
        if (g_last_weapon == 1 && weapon != 1) {
            g_last_sub_to_main_time = now_seconds();
        }
        g_last_weapon = weapon;
    }

    bool effective_assist = aim_assist;
    if (ts->toggles[TOG_ASSIST_ACTIVE_TIME]) {
        if (weapon == 1) {
            effective_assist = aim_assist && !ts->toggles[TOG_ASSIST_DISABLE_SUB];
        } else if (g_last_sub_to_main_time > 0) {
            double elapsed = now_seconds() - g_last_sub_to_main_time;
            effective_assist = (elapsed < ts->assist_active_time * 0.01);
        }
    } else {
        if (aim_assist && ts->toggles[TOG_ASSIST_DISABLE_SUB] && weapon == 1) {
            effective_assist = false;
        }
    }
    if (ts->toggles[TOG_ASSIST_ONLY_SHOOTING]) {
        uint8_t st = mem_read_byte(gs->base_addr + OFF_ALIVE);
        if (st < 8 || st > 11) effective_assist = false;
    }
    if (!aim_bot && !effective_assist) return;

    float my_x = mem_read_float(gs->base_addr + OFF_X);
    float my_y = mem_read_float(gs->base_addr + OFF_Y);
    float my_z = mem_read_float(gs->base_addr + OFF_Z);

    if (aim_bot) {
        float best_dist = 1e9f;
        int best_idx = -1;
        for (int i = 0; i < enemy_count; i++) {
            if (!enemies[i].alive || !enemies[i].enabled) continue;
            if (!ts->toggles[TOG_ALL_ENEMY] && enemies[i].team_parity == gs->player_team_parity) continue;
            float ex = mem_read_float(enemies[i].base + OFF_X);
            float ey = mem_read_float(enemies[i].base + OFF_Y);
            float ez = mem_read_float(enemies[i].base + OFF_Z);
            float dx = ez - my_z, dy = ey - my_y - 4.0f, dz = ex - my_x;
            float dist = sqrtf(dx * dx + dy * dy + dz * dz);
            if (dist < best_dist) { best_dist = dist; best_idx = i; }
        }
        if (best_idx >= 0) {
            float ex = mem_read_float(enemies[best_idx].base + OFF_X);
            float ey = mem_read_float(enemies[best_idx].base + OFF_Y);
            float ez = mem_read_float(enemies[best_idx].base + OFF_Z);
            float dx = ez - my_z, dy = ey - my_y - 4.0f, dz = ex - my_x;
            mem_write_float(gs->yaw_addr, atan2f(dx, dz));
            mem_write_float(gs->pitch_addr, atan2f(dy, sqrtf(dx * dx + dz * dz)));
        }
    }

    if (effective_assist) {
        float cur_yaw = mem_read_float(gs->yaw_addr);
        float cur_pitch = mem_read_float(gs->pitch_addr);

        bool yaw_ok = (cur_yaw == cur_yaw);
        bool pitch_ok = (cur_pitch == cur_pitch) && fabsf(cur_pitch) <= (float)M_PI * 0.5f;

        if (!yaw_ok || !pitch_ok) return;

        float raw_yaw = cur_yaw;
        float raw_pitch = cur_pitch;

        cur_yaw = remainderf(cur_yaw, 2.0f * (float)M_PI);
        cur_pitch = remainderf(cur_pitch, 2.0f * (float)M_PI);

        float lock_zone = 0.05f + (ts->assist_lock_zone_pos * 0.005f);
        float best_angular = 1e9f;
        float best_dyaw = 0, best_dpitch = 0;

        for (int i = 0; i < enemy_count; i++) {
            if (!enemies[i].alive || !enemies[i].enabled) continue;
            if (!ts->toggles[TOG_ALL_ENEMY] && enemies[i].team_parity == gs->player_team_parity) continue;

            float ex = mem_read_float(enemies[i].base + OFF_X);
            float ey = mem_read_float(enemies[i].base + OFF_Y);
            float ez = mem_read_float(enemies[i].base + OFF_Z);
            float dx = ez - my_z, dy = ey - my_y - 4.0f, dz = ex - my_x;

            float target_yaw = atan2f(dx, dz);
            float target_pitch = atan2f(dy, sqrtf(dx * dx + dz * dz));

            float dyaw = remainderf(target_yaw - cur_yaw, 2.0f * (float)M_PI);
            float dpitch = remainderf(target_pitch - cur_pitch, 2.0f * (float)M_PI);

            float total = fabsf(dyaw) + fabsf(dpitch);
            if (total < lock_zone && total < best_angular) {
                best_angular = total;
                best_dyaw = dyaw;
                best_dpitch = dpitch;
            }
        }

        if (best_angular < 1e8f) {
            float smooth = 0.05f + (ts->assist_smooth_aim_pos * 0.005f);
            mem_write_float(gs->yaw_addr, raw_yaw + best_dyaw * smooth);
            mem_write_float(gs->pitch_addr, raw_pitch + best_dpitch * smooth);
        }
    }
}

void hacks_apply_black_hole(GameState *gs, EnemyInfo *enemies, int enemy_count, const ToggleState *ts) {
    if (!gs->base_valid || !ts->toggles[TOG_BLACK_HOLE] || enemy_count == 0) return;

    uint64_t b = gs->base_addr;
    float pz, py, px;

    if (ts->toggles[TOG_BLACK_HOLE_FIXED] && g_black_hole_fixed_saved) {
        px = g_black_hole_fixed_x;
        py = g_black_hole_fixed_y;
        pz = g_black_hole_fixed_z;
    } else {
        float my_x = mem_read_float(b + OFF_X);
        float my_y = mem_read_float(b + OFF_Y);
        float my_z = mem_read_float(b + OFF_Z);
        float yaw = mem_read_float(gs->yaw_addr);
        float pitch = mem_read_float(gs->pitch_addr);

        float r = 8.0f;
        float dx = r * cosf(pitch) * cosf(yaw);
        float dy = r * sinf(pitch);
        float dz = r * cosf(pitch) * sinf(yaw);

        px = my_x + dx;
        py = my_y + dy + 3.0f;
        pz = my_z + dz;

        if (ts->toggles[TOG_BLACK_HOLE_FIXED]) {
            g_black_hole_fixed_x = px;
            g_black_hole_fixed_y = py;
            g_black_hole_fixed_z = pz;
            g_black_hole_fixed_saved = true;
        }
    }

    for (int i = 0; i < enemy_count; i++) {
        if (!enemies[i].alive || !enemies[i].enabled) continue;
        if (!ts->toggles[TOG_ALL_ENEMY]) {
            if (enemies[i].team_parity == gs->player_team_parity) continue;
        }
        mem_write_float(enemies[i].base + OFF_Z, pz);
        mem_write_float(enemies[i].base + OFF_Y, py);
        mem_write_float(enemies[i].base + OFF_X, px);
        mem_write_float(enemies[i].base + OFF_X_SPEED, 0.0f);
        mem_write_float(enemies[i].base + OFF_Z_SPEED, 0.0f);
        mem_write_float(enemies[i].base + OFF_Y_SPEED, 0.0f);
    }

    if (gs->capture_milk_addr != 0) {
        double t = now_seconds();
        if (t - g_last_bh_alive_write >= 0.5) {
            if (mem_read_byte(gs->capture_milk_addr) == 13) {
                for (int i = 0; i < enemy_count; i++) {
                    if (!enemies[i].enabled) continue;
                    if (!ts->toggles[TOG_ALL_ENEMY]) {
                        if (enemies[i].team_parity == gs->player_team_parity) continue;
                    }
                    mem_write_byte(enemies[i].base + OFF_ALIVE, 1);
                }
                g_last_bh_alive_write = t;
            }
        }
    }
}

static const float cap_z[8][8] = {
    {261.2665f, -256.5806f, 261.2665f, -256.5806f, 261.2665f, -256.5806f, 261.2665f, -256.5806f},
    {-261.2665f, 256.5806f, -261.2665f, 256.5806f, -261.2665f, 256.5806f, -261.2665f, 256.5806f},
    {175.71618f, -200.07823f, 208.81658f, -200.07823f, 175.71618f, -200.07823f, 208.81658f, -200.07823f},
    {-175.71618f, 200.07823f, -208.81658f, 200.07823f, -175.71618f, 200.07823f, -208.81658f, 200.07823f},
    {242.23381f, -250.27249f, 242.23381f, -250.27249f, 242.23381f, -250.27249f, 242.23381f, -250.27249f},
    {-242.23381f, 250.27249f, -242.23381f, 250.27249f, -242.23381f, 250.27249f, -242.23381f, 250.27249f},
    {161.88835f, -169.05087f, 161.88835f, -169.05087f, 161.88835f, -169.05087f, 161.88835f, -169.05087f},
    {-161.88835f, 169.05087f, -161.88835f, 169.05087f, -161.88835f, 169.05087f, -161.88835f, 169.05087f},
};
static const float cap_x[8][8] = {
    {71.68717f, 0.10532f, 44.69466f, 0.10532f, -44.18560f, 0.10532f, -71.52375f, 0.10532f},
    {71.68717f, 0.10532f, 44.69466f, 0.10532f, -44.18560f, 0.10532f, -71.52375f, 0.10532f},
    {-144.57347f, 128.38220f, -111.58444f, 128.38220f, -111.58444f, 128.38220f, -144.57347f, 128.38220f},
    {144.57347f, -128.38220f, 111.58444f, -128.38220f, 111.58444f, -128.38220f, 144.57347f, -128.38220f},
    {60.50044f, -0.42039f, 37.09999f, -0.42039f, -37.09999f, -0.42039f, -60.50044f, -0.42039f},
    {60.50044f, -0.42039f, 37.09999f, -0.42039f, -37.09999f, -0.42039f, -60.50044f, -0.42039f},
    {158.02603f, -124.86391f, 142.92604f, -124.86391f, 105.92604f, -124.86391f, 90.92604f, -124.86391f},
    {-158.02603f, 124.86391f, -142.92604f, 124.86391f, -105.92604f, 124.86391f, -90.92604f, 124.86391f},
};
static const float cap_y[8][8] = {
    {48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f},
    {48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f, 48.21943365f},
    {38.3997101f, 38.4000153f, 38.3997101f, 38.4000153f, 38.3997101f, 38.4000153f, 38.3997101f, 38.4000153f},
    {38.4000153f, 38.3997101f, 38.4000153f, 38.3997101f, 38.4000153f, 38.3997101f, 38.4000153f, 38.3997101f},
    {19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f},
    {19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f, 19.2000463f, 54.3999939f},
    {0.00002479f, 0.00001708f, 0.00002479f, 0.00001708f, 0.00002479f, 0.00001708f, 0.00002479f, 0.00001708f},
    {0.00001709f, 0.00002480f, 0.00001709f, 0.00002480f, 0.00001709f, 0.00002480f, 0.00001709f, 0.00002480f},
};

void hacks_start_capture(GameState *gs, ToggleState *ts, int key_index) {
    if (!gs->base_valid || key_index < 1 || key_index > 4) return;
    ts->capture_active = true;
    ts->capture_step = 0;
    ts->capture_key_index = key_index;
    ts->capture_next_time = now_seconds();
}

void hacks_tick_capture(GameState *gs, ToggleState *ts) {
    if (!ts->capture_active) return;
    if (!gs->base_valid) { ts->capture_active = false; return; }

    double t = now_seconds();
    if (t < ts->capture_next_time) return;

    uint64_t b = gs->base_addr;
    uint8_t parity = mem_read_byte(b + OFF_TEAM_PARITY);
    bool even_mode = (parity % 2 == 0);
    int set_idx = (ts->capture_key_index - 1) * 2 + (even_mode ? 0 : 1);
    int i = ts->capture_step;

    mem_write_dword(b + OFF_SCRIPT_SYS, 1);
    mem_write_float(b + OFF_Z, cap_z[set_idx][i]);
    mem_write_float(b + OFF_X, cap_x[set_idx][i]);
    mem_write_float(b + OFF_Y, cap_y[set_idx][i]);

    ts->capture_step++;
    if (ts->capture_step >= 8) {
        // 8ステップ完了後もループし続ける (ミルク取得中はワープを継続)
        ts->capture_step = 0;
        ts->capture_next_time = t + 0.250;
    } else {
        ts->capture_next_time = t + ((i % 2 == 0) ? 0.250 : 0.500);
    }
}

void hacks_auto_capture_milk(const ToggleState *ts, GameState *gs) {
    ToggleState *t = (ToggleState*)ts;
    if (!t->toggles[TOG_CAPTURE_MILK]) {
        t->capture_active = false;
        g_last_capture_milk_base = 0;
        return;
    }
    if (!gs->base_valid || gs->capture_milk_addr == 0) return;

    // マップ値がミルク取得状態(3/21/24/27)の間、対応するキー位置セットで
    // ワープをループし続ける。base_addr が同じでも毎ループ判定する。
    uint8_t val = mem_read_byte(gs->capture_milk_addr);
    int want_key = 0;
    switch (val) {
        case 3:  want_key = 1; break;
        case 21: want_key = 2; break;
        case 24: want_key = 3; break;
        case 27: want_key = 4; break;
        default: break;
    }
    if (want_key == 0) {
        t->capture_active = false;
        return;
    }
    if (t->capture_active && t->capture_key_index == want_key) return;
    LOGD("auto_capture_milk: byte=0x%02X(%d) at 0x%lX start key=%d", val, val, gs->capture_milk_addr, want_key);
    hacks_start_capture(gs, t, want_key);
}

void hacks_run_action(ToggleState *ts, GameState *gs) {
    if (!ts->action_pending) return;
    ts->action_pending = false;

    switch (ts->action) {
    case ACT_SELF_SCAN:
        gs->base_valid = false;
        hacks_reset_enemies();
        hacks_discover_base(gs);
        break;
    case ACT_SCAN_ENEMIES:
        hacks_reset_enemies();
        break;
    case ACT_CAPTURE1: hacks_start_capture(gs, ts, 1); break;
    case ACT_CAPTURE2: hacks_start_capture(gs, ts, 2); break;
    case ACT_CAPTURE3: hacks_start_capture(gs, ts, 3); break;
    case ACT_CAPTURE4: hacks_start_capture(gs, ts, 4); break;
    case ACT_ENEMY_LIST:
        break;
    case ACT_ENEMY_TOGGLE:
        break;
    default: break;
    }
}
