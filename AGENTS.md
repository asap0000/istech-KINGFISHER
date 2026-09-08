# AGENTS.md — istech-KINGFISHER（PrivacyCamera・公開リポ）で Codex が守ること

- **公開リポ。** コミットされるものは世界に見える前提で書く（実地名・個人名・鍵・内部 URL を書かない）。
- 制度・現況は `D:\dev\istech\teams\privacycamera\{CHARTER,STATE}.md`、判断の前提は同 `decisions/`（**seam の規律**＝テストの都合で本番のシグネチャを変えない、を含む）。
- テストは **`./gradlew :app:verifyRoborazziProDebug`**（`JAVA_HOME` 明示必須）。**ゴールデンの差分が出たら「意図した変更か」を報告に書く**（勝手に record し直さない）。
- **release ビルドで壊れる系（R8 / 署名 / 権限 / リフレクション生成）は JVM テストでは見えない。** ViewModel の生成経路・Keystore・ライフサイクルに触ったら、**その旨を報告の先頭に書く**（検査場が実機で当てる）。
- 署名鍵はリポに無い。`RELEASE_KEYSTORE_BASE64` 等の Secret を**推測・生成・出力しない**。
- 触ってよい範囲は**依頼文のディレクトリのみ**。`.github/workflows/`・`build.gradle.kts` の署名節・`applicationId` は触らない。
- `git add` / `commit` / `push` はしない。秘密ファイル（`CLAUDE.local.md` `*.keystore` `*.jks` `*.pem` `*.key` `.env` **`~/.codex/config.toml`**）は読まない・中身を出力しない。
