# 第三者ライセンス一覧 (Third-Party Notices)

本アプリ (PrivacyCamera) が利用する第三者ソフトウェアとそのライセンスの一覧です。
バージョンの正は [`gradle/libs.versions.toml`](gradle/libs.versions.toml)（Version Catalog・唯一の正）。

## アプリに含まれるもの（APK に同梱）

| ライブラリ | 提供元 | ライセンス |
|---|---|---|
| Kotlin Standard Library | JetBrains | Apache-2.0 |
| kotlinx.coroutines | JetBrains | Apache-2.0 |
| AndroidX Core KTX | Google (AOSP) | Apache-2.0 |
| AndroidX Activity Compose | Google (AOSP) | Apache-2.0 |
| AndroidX Lifecycle (runtime / viewmodel-compose) | Google (AOSP) | Apache-2.0 |
| AndroidX Navigation Compose | Google (AOSP) | Apache-2.0 |
| AndroidX Fragment KTX | Google (AOSP) | Apache-2.0 |
| AndroidX Biometric | Google (AOSP) | Apache-2.0 |
| Jetpack Compose (UI / Material 3 / Material Icons Extended) | Google (AOSP) | Apache-2.0 |
| CameraX (core / camera2 / lifecycle / view) | Google (AOSP) | Apache-2.0 |

## debug ビルドにのみ含まれる（配布する release APK には入らない）

| ライブラリ | 提供元 | ライセンス |
|---|---|---|
| Compose UI Tooling | Google (AOSP) | Apache-2.0 |
| Compose UI Test Manifest | Google (AOSP) | Apache-2.0 |

## テスト専用（APK には一切含まれない）

| ライブラリ | 提供元 | ライセンス |
|---|---|---|
| JUnit 4 | JUnit Team | EPL-1.0 |
| Robolectric | Robolectric contributors | MIT |
| AndroidX Test (core-ktx / ext junit-ktx) | Google (AOSP) | Apache-2.0 |
| Google Truth | Google | Apache-2.0 |
| Roborazzi (+ roborazzi-compose) | takahirom | Apache-2.0 |

## ライセンス本文

- Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- Eclipse Public License 1.0: <https://www.eclipse.org/legal/epl-v10.html>
- MIT License: <https://opensource.org/license/mit/>

本アプリはネットワーク系ライブラリを一切含みません（CI が依存ツリーを機械検査します。
[`.github/workflows/android.yml`](.github/workflows/android.yml) の "Verify offline policy" 参照）。
