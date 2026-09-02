#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "defs.h"

typedef struct {
    bool toggles[TOG_COUNT];
    float speed_multiplier;
    int assist_lock_zone_pos;
    int assist_smooth_aim_pos;
    int assist_active_time;
    int action;
    int action_arg;
    bool action_pending;
    bool capture_active;
    int capture_step;
    int capture_key_index;
    double capture_next_time;
} ToggleState;

typedef struct {
    uint64_t xa_start;
    uint64_t xa_end;
    uint64_t bss_start;
    uint64_t bss_end;
    uint64_t xa_respawn;
    uint64_t xa_skill_dmg;
    uint64_t xa_noclip_match;
    uint64_t xa_noclip_town;
    uint64_t xa_recoil;
    uint64_t xa_spread_jz;
    uint64_t xa_spread_mz;
    uint64_t xa_spread_j;
    uint64_t xa_spread_sz;
    uint64_t xa_spread_iz;
    uint64_t xa_spread_i;
    uint64_t xa_reload;
    uint64_t xa_spread1;
    uint64_t xa_spread2;
    uint64_t xa_head_onepunch;
    uint64_t xa_body_onepunch;
    uint64_t xa_move_speed;
    uint64_t pitch_addr;
    uint64_t yaw_addr;
    uint64_t capture_milk_addr;
    uint64_t base_addr;
    uint32_t player_id;
    uint8_t player_team_parity;
    bool base_valid;
    bool off_check_valid;
    uint64_t roster_slot_mgr;   // 解決済み SLOT_MGR vaddr (0 なら未解決)
    bool roster_resolved;
} GameState;

typedef struct {
    uint64_t base;
    uint64_t id;
    char name[100];
    uint8_t team_parity;
    uint8_t slot;
    int16_t rp;
    bool alive;
    bool enabled;
} EnemyInfo;

bool hacks_discover_regions(GameState *gs);
bool hacks_discover_base(GameState *gs);
bool hacks_discover_all(GameState *gs);
void hacks_resolve_roster(GameState *gs);
void roster_load_persisted(GameState *gs);

void hacks_apply_xa(const ToggleState *ts, GameState *gs);
void hacks_apply_all(const ToggleState *ts, GameState *gs);
void hacks_run_action(ToggleState *ts, GameState *gs);
void hacks_start_capture(GameState *gs, ToggleState *ts, int key_index);
void hacks_tick_capture(GameState *gs, ToggleState *ts);
void hacks_apply_black_hole(GameState *gs, EnemyInfo *enemies, int enemy_count, const ToggleState *ts);
void hacks_apply_black_hole_fixed(const ToggleState *ts, GameState *gs);

bool hacks_scan_enemies(const ToggleState *ts, GameState *gs, EnemyInfo *enemies, int *count, int max_count);
void hacks_apply_aimbot(EnemyInfo *enemies, int enemy_count, const ToggleState *ts, GameState *gs);
void hacks_auto_capture_milk(const ToggleState *ts, GameState *gs);
void hacks_reset_enemies(void);
void hacks_reset_all_state(void);

double now_seconds(void);

extern bool g_enemy_room_found;
extern uint64_t g_enemy_room_base;
extern EnemyInfo g_enemies[MAX_ENEMIES];
extern int g_enemy_count;
