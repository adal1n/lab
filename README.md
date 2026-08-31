# mc4 계정/세션 하이재킹 분석 · アカウント/セッションハイジャック分析

> 이 저장소는 mc4 계정 탈취 사고에 대한 정적 바이너리 분석(리버싱) 보고서를 담고 있습니다. 라이브 서버 침투 테스트는 수행하지 않았습니다.
> この Repository は mc4 アカウント乗っ取り事案に関する静的バイナリ解析（リバースエンジニアリング）報告書です。本番サーバーへの侵入テストは実施していません。

**언어별 상세 문서 · 言語別の詳細ドキュメント**

- 🇰🇷 한국어: [`README.ko.md`](./README.ko.md)
- 🇯🇵 日本語: [`README.ja.md`](./README.ja.md)

전체 명령어 단위 분석 히스토리(18개 장, 전 과정 기록)는 브랜치 [`claude/mc4-account-theft-analysis-10cpb8`](../../tree/claude/mc4-account-theft-analysis-10cpb8)의 `mc4_account_theft_analysis.md`를 참고하세요. 검증용 Frida 스크립트(`poc_requestlogin_frida.js`)도 같은 브랜치에 있습니다.

命令レベルでの分析履歴全体（全18章）は、ブランチ [`claude/mc4-account-theft-analysis-10cpb8`](../../tree/claude/mc4-account-theft-analysis-10cpb8) の `mc4_account_theft_analysis.md` を参照してください。検証用のFridaスクリプト（`poc_requestlogin_frida.js`）も同じブランチにあります。
