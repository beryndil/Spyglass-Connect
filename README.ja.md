🌐 [English](README.md) | [Español](README.es.md) | [Português](README.pt.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [日本語](README.ja.md)

# Spyglass Connect

**[Spyglass](https://github.com/beryndil/Spyglass) Androidアプリのデスクトップコンパニオン。**

Spyglass ConnectはPC上で動作し、Minecraft Java Editionのセーブファイルを読み取り、ローカルWiFi経由でスマートフォンのSpyglassアプリにデータをストリーミングします。インベントリの確認、チェスト内のアイテム検索、構造物の探索、俯瞰マップの表示 — プレイしながらすべてスマートフォンから操作できます。

## 免責事項

**Spyglass ConnectはMojang StudiosまたはMicrosoftとは提携、承認、関連していません。** MinecraftはMojang Studiosの商標です。すべてのゲームデータは情報提供のみを目的として使用されています。

---

## ダウンロード

**[Releasesから最新のインストーラーをダウンロード](https://github.com/beryndil/Spyglass-Connect/releases/latest)**

またはソースからビルド：
```bash
./gradlew run
```

**要件：** Java 21+ | Windows、macOS、またはLinux

---

## 機能

### セーブデータの検出

以下の場所からMinecraftのワールドを自動検出：
- **デフォルトランチャー** — `.minecraft/saves/`
- **Prism Launcher** — すべてのインスタンスを自動検出
- **カスタムパス** — 任意のディレクトリを追加（Pterodactylサーバー、MODランチャーなど）

### QRコードペアリング

1. PCでSpyglass Connectを起動
2. スマートフォンでSpyglassアプリを開く
3. **PCに接続**をタップしてQRコードをスキャン
4. 接続完了 — 次回からスマートフォンは自動的に再接続されます

### スマートフォンで確認できること

| 機能 | 説明 |
|---------|-------------|
| **インベントリ** | 36スロットの完全なインベントリ、防具、オフハンド、エンダーチェスト |
| **チェストファインダー** | ワールド内のすべてのコンテナからアイテムを検索 |
| **構造物** | 村、寺院、海底神殿などの位置情報 |
| **俯瞰マップ** | 構造物マーカーとプレイヤー位置を表示する地形マップ |
| **プレイヤーステータス** | 体力、空腹度、XPレベル、座標、ディメンション |
| **統計情報** | 生涯統計 — 採掘したブロック、倒したMob、移動距離、プレイ時間など |
| **進捗** | セーブファイルから取得した達成状況付きの全125進捗 |

### ライブアップデート

ファイルウォッチャーがワールドフォルダを監視し、Minecraftがセーブした時に自動的にスマートフォンに変更を送信します。

### 暗号化

すべての通信はECDH鍵交換 + AES-256-GCMで暗号化されています。鍵は永続的に保存されるため、QRコードのスキャンは一度だけで済みます。
両方のアプリはペアリング中にプロトコルバージョンをネゴシエートします — どちらかが古い場合、どのアプリを更新すべきか明確なメッセージが表示されます。

---

## 仕組み

Spyglass ConnectはMinecraftのセーブファイルを直接読み取ります（変更は一切行いません）。解析対象：

- `level.dat` — ワールドメタデータとプレイヤーデータ
- `region/*.mca` — チェスト内容、構造物、マップレンダリング用のAnvilリージョンファイル
- `playerdata/*.dat` — プレイヤーインベントリと統計
- `stats/<uuid>.json` — プレイヤーの生涯統計（採掘ブロック数、移動距離、キル数など）
- `advancements/<uuid>.json` — 進捗の達成状況と基準

データは同じWiFiネットワーク上のSpyglass Androidアプリに、ローカルWebSocketサーバー（ポート29170）経由で配信されます。スマートフォンはmDNS経由でデスクトップを検出し、自動再接続を行います。

---

## 設定

アプリの歯車アイコンをクリックして：

- **Prism Launcher検出の切り替え** — Prism Launcherインスタンスの自動スキャン
- **カスタムセーブディレクトリの追加** — Minecraftワールドを含む任意のフォルダを指定

設定は `~/.spyglass-connect/config.json` に保存されます。

---

## 技術スタック

| コンポーネント | 技術 |
|-----------|-----------|
| 言語 | Kotlin/JVM |
| UI | Compose Multiplatform |
| サーバー | Ktor + Netty WebSocket |
| NBT解析 | Querz NBT |
| 暗号化 | java.security (ECDH) + javax.crypto (AES-256-GCM) |
| プロトコルバージョン | v3（暗号化、機能ネゴシエーション付き） |
| QRコード | ZXing |
| ディスカバリー | JmDNS (mDNS) |

---

## 関連プロジェクト

- **[Spyglass](https://github.com/beryndil/Spyglass)** — Androidコンパニオンアプリ
- **[Spyglass-Data](https://github.com/beryndil/Spyglass-Data)** — Androidアプリで使用されるMinecraftリファレンスデータ

---

## クレジット

**Spyglass Connect**は**Beryndil**によって作成されました。

---

## プライバシー

Spyglass Connectは完全にローカルマシン上で動作します。外部サーバーにデータが送信されることはありません。デスクトップアプリとスマートフォン間のすべての通信は、ローカルWiFiネットワーク内に留まります。

---

## ライセンス

Copyright (c) 2026 Beryndil. All rights reserved. 詳細は[LICENSE](LICENSE)をご覧ください。

---

*Kotlin、Compose Multiplatform、Ktorで構築。*
