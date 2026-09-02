#pragma once

#define TARGET_PACKAGE "com.gameparadiso.milkchoco"
#define EXPECTED_BASE_CHECK_ADDR 0x1D4
#define MIN_VALID_PTR 0x100000

enum GameOffset {
    OFF_DELETE_SYS    = 0x000,
    OFF_RP_SYSTEM     = 0x02C,
    OFF_WEAPON        = 0x02E,
    OFF_NAME          = 0x090,
    OFF_COOLDOWN_0    = 0x0B5,
    OFF_TEAM_PARITY   = 0x0C8,
    OFF_TIMER         = 0x0D0,
    OFF_MAIN_SPEED    = 0x0F0,
    OFF_SUB_SPEED     = 0x0F4,
    OFF_X_SPEED       = 0x104,
    OFF_Z_SPEED       = 0x108,
    OFF_SCRIPT_SYS    = 0x134,
    OFF_INF_SKILL_G   = 0x135,
    OFF_Y_SPEED       = 0x13C,
    OFF_HOOK          = 0x174,
    OFF_Z             = 0x19C,
    OFF_Y             = 0x1A0,
    OFF_X             = 0x1A4,
    OFF_HIDE          = 0x1BC,
    OFF_CHECK         = 0x1D4,
};

// libMyGame.so (lib/arm64-v8a) 内のファイルオフセット（vaddr == file_offset）
// パッチ対象: 各関数の逆アセンブルで特定した命令/定数
static const uint64_t XA_RESPAWN_OFF     = 0x26FC9FC;  // GameScene::GetRespawnTime() 内の fmul (復活時間の乗算) をパッチ
static const uint64_t XA_SKILL_DMG_OFF   = 0x28A56EC;  // CCharacterRef::GetSkillDamage(unsigned char) 内の ldur (スキルダメージ値)
static const uint64_t XA_NOCLIP_MATCH_OFF= 0x2951DC4;  // UserMoveSystem::CalculateMovePosition(..., GameScene&) 末尾の float 定数 (重力/衝突係数)
static const uint64_t XA_NOCLIP_TOWN_OFF = 0x2955518;  // UserMoveSystem::CalculateMovePosition(..., TownScene&) 末尾の float 定数 (重力/衝突係数)
static const uint64_t XA_RECOIL_OFF      = 0x2956CC4;  // Recoil::ShakeCamera(float const&) 内の str (カメラシェイク書き込み)
static const uint64_t XA_SPREAD_JZ_OFF   = 0x2956EF4;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (JZ)
static const uint64_t XA_SPREAD_MZ_OFF   = 0x2956F14;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (MZ)
static const uint64_t XA_SPREAD_J_OFF    = 0x2956F1C;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (J)
static const uint64_t XA_SPREAD_SZ_OFF   = 0x2956F3C;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (SZ)
static const uint64_t XA_SPREAD_IZ_OFF   = 0x2956F5C;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (IZ)
static const uint64_t XA_SPREAD_I_OFF    = 0x2956F64;  // Spread::GetAimGapByCurState() 内 状態別エイム拡散値 (I)
static const uint64_t XA_RELOAD_OFF      = 0x2B5B308;  // CharStatusCalculator::GetReloadSpeedRate() 内の ldur (リロード速度係数)
static const uint64_t XA_SPREAD1_OFF     = 0x2B5B320;  // CharStatusCalculator::GetAimSpreadMoving() 内 (移動中のエイム拡散)
static const uint64_t XA_SPREAD2_OFF     = 0x2B5B338;  // CharStatusCalculator::GetAimSpreadShooting() 内 (射撃中のエイム拡散)
static const uint64_t XA_HEAD_ONEPUNCH_OFF = 0x2B5B47C; // CharStatusCalculator::GetHeadShotDamageRate() 内の ldur (ヘッドショット倍率)
static const uint64_t XA_BODY_ONEPUNCH_OFF = 0x2B5B484; // CharStatusCalculator::GetBodyShotDamageRate() 内 (ボディショット倍率)
static const uint64_t XA_MOVE_SPEED_OFF = 0x273896C;  // GameScene::OnMoveInputMoved(cocos2d::Vec2) 内の ldr+fmul (移動ベクトル係数)

static const uint64_t BSS_PITCH_OFF = 0xAB8FA4;
static const uint64_t BSS_YAW_OFF   = 0xAB8FA8;
static const uint64_t BSS_CAPTURE_MILK_OFF = 0xB4CF28;

// 構造体検証用: base_addr + OFF_CHECK の期待値（実測 0xb4000074）
static const uint32_t OFF_CHECK_EXPECT = 0xb4000074;

static const uint64_t BSS_BASE_BYTE_OFFSETS[5] = {
    0x97120c,
    0x97120b,
    0x97120a,
    0x971209,
    0x971208,
};

// ---- ロスター (UserInfoManager) 歩行 ----
// base + SLOT_MGR を読んだ値 (slot) をさらに deref すると UserInfoManager*
// Manager::User() の disasm (adrp 0x377b000; ldr [x0,#0xee8]) と
// リロケーション表 (0x377bee8 -> 0x4507ed0) から確定した vaddr
static const uint64_t SLOT_MGR = 0x377bee8;

static const uint64_t ROSTER_BUCKET_OFF   = 0x00; // UserInfoManager + 0 : bucketArr (uint64)
static const uint64_t ROSTER_MODULUS_OFF  = 0x08; // + 8 : modulus (uint32)
static const uint64_t ROSTER_SIZE_OFF     = 0x18; // + 0x18 : size (uint32)

static const uint64_t NODE_NEXT_OFF = 0x00; // ノード + 0 : 次のノード (uint64)
static const uint64_t NODE_SEQ_OFF  = 0x08; // + 8 : seq (uint32)
static const uint64_t NODE_UI_OFF   = 0x10; // + 0x10 : UserInfor* (uint64)

// 普通 ID 帯 (実プレイヤー) の上限。
// これを超える ID 帯は名前が BOT_NAMES と一致した場合のみ採用する。
static const uint32_t ID_NORMAL_MAX = 40000000;

// バケット歩行のガード (破損防止)
#define ROSTER_WALK_GUARD 512
#define ROSTER_MAX_FOUND 64

// UserInfor 検証用マジック (ui + OFF_CHECK)。
// 値は起動/ビルドごとに下位ニブルが変動するため(実測 0x70/0x72/0x73/0x74)、
// 上位3バイトが 0xb4000070 系であることのみで判定する。
static const uint32_t USER_MAGIC_MASK = 0xFFFFFFF0;
static const uint32_t USER_MAGIC_EXPECT = 0xb4000070;

static const uint64_t OFF_ALIVE = 0x134; // UserInfor + 0x134 : 死亡判定 (16 == dead)

// ---- シンボルベース自動修復用（.dynsym の mangled 名） ----
#define SYM_GET_RESPAWN_TIME   "_ZNK9GameScene14GetRespawnTimeEv"
#define SYM_ON_MOVE_INPUT_MOVED "_ZN9GameScene16OnMoveInputMovedEN7cocos2d4Vec2E"
#define SYM_GET_SKILL_DMG      "_ZNK13CCharacterRef14GetSkillDamageEh"
#define SYM_CALC_MOVE_POS_GS   "_ZN14UserMoveSystem21CalculateMovePositionERNS_13CollisionDataEbRKN7cocos2d4Vec3ER9GameSceneR9UserInfor"
#define SYM_CALC_MOVE_POS_TS   "_ZN14UserMoveSystem21CalculateMovePositionERNS_13CollisionDataEbRKN7cocos2d4Vec3ER9TownSceneR9UserInfor"
#define SYM_SHAKE_CAMERA       "_ZN6Recoil11ShakeCameraERKf"
#define SYM_GET_AIM_GAP        "_ZN6Spread19GetAimGapByCurStateEv"
#define SYM_GET_RELOAD_RATE    "_ZN20CharStatusCalculator18GetReloadSpeedRateERK9UserInfor"
#define SYM_GET_AIM_SPREAD_M   "_ZN20CharStatusCalculator18GetAimSpreadMovingERK9UserInfor"
#define SYM_GET_AIM_SPREAD_S   "_ZN20CharStatusCalculator20GetAimSpreadShootingERK9UserInfor"
#define SYM_GET_HEAD_DMG       "_ZN20CharStatusCalculator21GetHeadShotDamageRateERK9UserInfor"
#define SYM_GET_BODY_DMG       "_ZN20CharStatusCalculator21GetBodyShotDamageRateERK9UserInfor"

// ---- ロスター (UserInfoManager) 自動修復用（.dynsym の mangled 名） ----
#define SYM_MANAGER_USER        "_ZN7Manager4UserEv"
#define SYM_UIM_GET_BY_SEQ      "_ZN15UserInfoManager16GetUserByUserSeqEj"
#define SYM_UIM_SIZE            "_ZNK15UserInfoManager4SizeEv"
#define SYM_UIM_GET_BY_ORDER    "_ZN15UserInfoManager14GetUserByOrderEh"

enum ToggleID {
    TOG_SHOOT,
    TOG_RELOAD,
    TOG_DAMAGE_UP_GUN,
    TOG_DAMAGE_UP_SKILL,
    TOG_RESPAWN,
    TOG_SPEED,
    TOG_NO_CLIP,
    TOG_RECOIL,
    TOG_BLACK_HOLE,
    TOG_BLACK_HOLE_FIXED,
    TOG_BAVA_HACK,
    TOG_KDA_BOOSTER,
    TOG_AIM_BOT,
    TOG_AIM_ASSIST,
    TOG_ASSIST_DISABLE_SUB,
    TOG_ASSIST_ACTIVE_TIME,
    TOG_ASSIST_ONLY_SHOOTING,
    TOG_ALL_ENEMY,
    TOG_EXCLUDE_BOT,
    TOG_SKILL_DMG_DISABLE_MAIN,
    TOG_CAPTURE_MILK,
    TOG_COUNT,
};

static const char* TOGGLE_NAMES[TOG_COUNT] = {
    "shoot", "reload", "damageUpGun", "damageUpSkill",
    "respawn", "speed", "noClip", "recoil",
    "blackHole", "blackHoleFixed", "bavaHack", "kdaBooster",
    "aimBot", "aimAssist", "assistDisableSubWeapon",
    "assistActiveTime", "assistOnlyShooting",
    "allEnemy", "excludeBot", "skillDamageDisableMainWeapon",
    "captureMilk",
};

enum ActionID {
    ACT_NONE,
    ACT_SELF_SCAN,
    ACT_SCAN_ENEMIES,
    ACT_ENEMY_LIST,
    ACT_ENEMY_TOGGLE,
    ACT_CAPTURE1,
    ACT_CAPTURE2,
    ACT_CAPTURE3,
    ACT_CAPTURE4,
};

enum {
    TYPE_BYTE  = 1,
    TYPE_WORD  = 2,
    TYPE_DWORD = 4,
    TYPE_FLOAT = 8,
};

#define XA_MIN_SIZE 0x100000
#define MAX_ENEMIES 64
#define ENEMY_NAME_LEN 100
#define REGION_PATH_LEN 256

#define SPEED_DEAD_ZONE 0.05f
#define BLACK_HOLE_RANGE 12.0f
#define BLACK_HOLE_HEIGHT_OFF 3.0f
#define ENEMY_POLL_INTERVAL_S 0.05
#define BASE_POLL_INTERVAL_S 0.10
