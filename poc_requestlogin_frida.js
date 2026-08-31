/*
 * mc4 (libMyGame.so) — SystemPacketSend::RequestLogin PoC / instrumentation
 *
 * 목적: "type != 2" (번호/식별자 기반) 로그인 경로가 서버 발급 세션 토큰 없이
 *       클라이언트가 로컬에서 채워 넣는 식별자 문자열만으로 로그인 패킷을
 *       구성한다는 정적 분석 결과를 실제 실행 중인 클라이언트에서 검증한다.
 *
 * 범위/윤리:
 *   - 본인(회사) 소유의 테스트 계정 2개(A, B)로만 교차 검증할 것.
 *   - 절대 임의의 실제 플레이어 식별자를 넣지 말 것.
 *   - 1단계(로깅)로 실제 메모리 레이아웃을 먼저 확인한 뒤에만 2단계(주입)를 사용할 것 —
 *     std::string 내부 레이아웃 추정이 틀리면 앱이 크래시할 수 있음.
 *
 * 사용법:
 *   frida -U -f <package-name> -l poc_requestlogin_frida.js --no-pause
 *   (이미 떠 있는 프로세스에 붙는 경우) frida -U <package-name-or-pid> -l poc_requestlogin_frida.js
 *
 *   콘솔에서:
 *     rpc.exports.status()                    // 후킹 상태 확인
 *     rpc.exports.armOverwrite("테스트B식별자") // 다음 RequestLogin(type!=2) 호출 때 s2를 이 값으로 in-place 치환
 *     rpc.exports.disarm()                    // 예약된 치환 취소
 *
 * 1단계는 자동 실행됨(로깅). 2단계(armOverwrite)는 사용자가 명시적으로 호출해야만 동작.
 */

'use strict';

const LIB_NAME = 'libMyGame.so';

// RequestLogin은 .dynsym에 살아있는 심볼이라 오프셋 대신 심볼명으로 바로 찾을 수 있음
// (빌드가 바뀌어도 offset보다 안정적).
const REQUESTLOGIN_SYM = '_ZN16SystemPacketSend12RequestLoginEiRKSsS1_';
const SENDLOGINBYPLATFORM_SYM = '_ZN10LobbyScene19SendLoginByPlatformEiRKSsS1_b';
const GETMYCLIENTDATA_SYM = '_ZN6Common15GetMyClientDataEv';

// 분석 결과(mc4_account_theft_analysis.md 16~17장) RequestLogin의 type != 2 분기가
// 실제로 서버에 보내는 유일한 유의미한 값은 s2가 아니라 이 오프셋 -- 계정 UID(unsigned int)로
// 확인됨 (길드원 조회 키, JoinBattleRoyalDuo 매칭 요청 두 곳에서 독립적으로 교차검증).
const CLIENTDATA_UID_OFFSET = 0x2124;

let armedOverwrite = null;   // { text: string } | null  -- s2 in-place 치환 (레거시, 다른 미추적 분기용)
let armedUidOverwrite = null; // number | null            -- +0x2124 UID 치환 (진짜 주입 지점, 17장)

function hexdump(ptr, len) {
    try {
        return hexdumpBytes(ptr.readByteArray(len));
    } catch (e) {
        return `<read failed: ${e}>`;
    }
}

function hexdumpBytes(buf) {
    const bytes = new Uint8Array(buf);
    return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(' ');
}

// libstdc++ std::string (64-bit, small-string-optimized) 레이아웃:
//   +0x00 char*  _M_p              (SSO면 내부 버퍼(+0x10)를, 아니면 heap 버퍼를 가리킴)
//   +0x08 size_t _M_string_length
//   +0x10 union { char buf[16]; size_t cap; }
// RequestLogin이 실제로 읽는 [data_ptr - 0x18]이 이 표준 레이아웃과 안 맞을 수 있으므로
// 여기서는 표준 필드(dataPtr/length)와 -0x18 주변을 둘 다 덤프해서 실측한다.
function describeStdString(stringObjPtr, label) {
    const dataPtr = stringObjPtr.readPointer();
    let length = -1;
    try { length = stringObjPtr.add(8).readU64().toNumber(); } catch (e) {}
    let text = '<unreadable>';
    try { text = dataPtr.readUtf8String(length >= 0 && length < 512 ? length : 64); } catch (e) {}

    console.log(`  [${label}] obj=${stringObjPtr} dataPtr=${dataPtr} length=${length} text="${text}"`);
    console.log(`  [${label}] bytes at dataPtr-0x20..dataPtr+0x10: ${hexdump(dataPtr.sub(0x20), 0x30)}`);
    return { dataPtr, length, text };
}

function main() {
    const mod = Process.findModuleByName(LIB_NAME);
    if (!mod) {
        console.log(`[*] ${LIB_NAME} not loaded yet, retrying...`);
        setTimeout(main, 500);
        return;
    }
    console.log(`[*] ${LIB_NAME} loaded @ ${mod.base}`);

    const requestLoginAddr = Module.findExportByName(LIB_NAME, REQUESTLOGIN_SYM) ||
        DebugSymbol.fromName(REQUESTLOGIN_SYM).address;
    const sendLoginByPlatformAddr = Module.findExportByName(LIB_NAME, SENDLOGINBYPLATFORM_SYM);

    if (!requestLoginAddr) {
        console.log('[!] RequestLogin symbol not found — is this the same build that was analyzed?');
        return;
    }
    console.log(`[*] RequestLogin        @ ${requestLoginAddr}`);
    console.log(`[*] SendLoginByPlatform @ ${sendLoginByPlatformAddr}`);

    // Common::GetMyClientData()를 후킹 -- 이게 실제 주입 지점(+0x2124 = 계정 UID).
    // RequestLogin 자체는 UID를 인자로 받지 않고, 함수 내부에서 이걸 직접 호출해서 읽으므로
    // 값을 바꾸려면 인자가 아니라 이 함수의 반환값이 가리키는 구조체를 패치해야 함.
    const getMyClientDataAddr = Module.findExportByName(LIB_NAME, GETMYCLIENTDATA_SYM);
    let lastClientDataPtr = null;
    if (getMyClientDataAddr) {
        Interceptor.attach(getMyClientDataAddr, {
            onLeave(retval) {
                lastClientDataPtr = retval;
                if (armedUidOverwrite !== null) {
                    const uidPtr = retval.add(CLIENTDATA_UID_OFFSET);
                    const before = uidPtr.readU32();
                    uidPtr.writeU32(armedUidOverwrite);
                    console.log(`[UID INJECTED] GetMyClientData()+0x2124: ${before} -> ${armedUidOverwrite}`);
                    armedUidOverwrite = null; // 1회성 -- 계속 덮어쓰면 다른 기능(듀오매칭 등)까지 오염됨
                }
            }
        });
    } else {
        console.log('[!] GetMyClientData symbol not found -- armUidOverwrite will not work.');
    }

    Interceptor.attach(requestLoginAddr, {
        onEnter(args) {
            const type = args[0].toInt32();
            console.log(`\n[RequestLogin] type=${type} (${type === 2 ? 'PLATFORM' : 'IDENTIFIER-ONLY, s1 is ignored by client code'})`);

            if (lastClientDataPtr) {
                const uid = lastClientDataPtr.add(CLIENTDATA_UID_OFFSET).readU32();
                console.log(`  [UID field] GetMyClientData()+0x2124 = ${uid} (this is what actually identifies the account per mc4_account_theft_analysis.md 16장)`);
            } else {
                console.log('  [UID field] GetMyClientData() has not been observed yet this run -- value unknown.');
            }

            const s1 = describeStdString(args[1], 's1');
            const s2 = describeStdString(args[2], 's2');

            if (type !== 2 && armedOverwrite) {
                const maxLen = s2.length; // 원본 버퍼보다 길게 쓰면 힙/스택 손상 위험 -> 절대 확장하지 않음
                const newText = armedOverwrite.text;
                armedOverwrite = null;

                if (newText.length > maxLen) {
                    console.log(`[!] armOverwrite 취소: 새 값(${newText.length}bytes)이 원본(${maxLen}bytes)보다 깁니다. 같은 길이 이하로만 지원.`);
                } else {
                    try {
                        // 원본 데이터 버퍼에 in-place로 덮어씀 (재할당하지 않음 -> length/capacity 헤더는 원본 그대로 유지)
                        s2.dataPtr.writeUtf8String(newText);
                        // 남는 바이트는 0으로 패딩 (원래 문자열이 더 길었을 경우 잔여 바이트 정리)
                        if (newText.length < maxLen) {
                            s2.dataPtr.add(newText.length).writeByteArray(new Array(maxLen - newText.length).fill(0));
                        }
                        console.log(`[INJECTED] s2 데이터 버퍼를 "${newText}"로 in-place 치환 완료 (length 필드는 원본 유지: ${maxLen})`);
                    } catch (e) {
                        console.log(`[!] 치환 실패: ${e}`);
                    }
                }
            }
        }
    });

    if (sendLoginByPlatformAddr) {
        Interceptor.attach(sendLoginByPlatformAddr, {
            onEnter(args) {
                console.log(`[SendLoginByPlatform] type=${args[1].toInt32()} bool=${args[4].toInt32()}`);
            }
        });
    }

    console.log('[*] Hooks installed. Trigger the game\'s normal connect/login flow now and watch the log.');
    console.log('[*] Step 1 (observe only): just watch the [UID field] and s1/s2 logs on real RequestLogin calls.');
    console.log('[*] Step 2 (inject, test accounts only): rpc.exports.armUidOverwrite(<test-account-B-uid>) then reconnect/retry -- this patches the REAL injection point (GetMyClientData()+0x2124), not s1/s2.');
    console.log('[*] armOverwrite(text) for s2 is kept for other, still-untraced branches (see 14장/17장 of the analysis doc) -- in the traced auto-trigger path s2 is empty and unused, so armUidOverwrite is what actually matters there.');
}

rpc.exports = {
    status() {
        return { armed: armedOverwrite, armedUid: armedUidOverwrite };
    },
    armOverwrite(text) {
        armedOverwrite = { text: String(text) };
        console.log(`[*] Armed (legacy, s2): next RequestLogin(type != 2) call will have s2 overwritten with "${text}" (if it fits in the original buffer).`);
    },
    armUidOverwrite(uid) {
        armedUidOverwrite = Number(uid) >>> 0;
        console.log(`[*] Armed (real injection point): next Common::GetMyClientData() call will have +0x2124 overwritten with ${armedUidOverwrite}.`);
    },
    disarm() {
        armedOverwrite = null;
        armedUidOverwrite = null;
        console.log('[*] Disarmed.');
    }
};

setImmediate(main);
