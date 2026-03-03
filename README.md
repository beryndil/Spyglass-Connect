# Spyglass Connect

**Desktop companion for the [Spyglass](https://github.com/Dev-VulX/Spyglass) Android app.**

Spyglass Connect runs on your PC and reads your Minecraft Java Edition save files, then streams the data to the Spyglass app on your phone over local WiFi. See your inventory, find items in chests, browse structures, and view an overhead map — all from your phone while you play.

## Disclaimer

**Spyglass Connect is not affiliated with, endorsed by, or associated with Mojang Studios or Microsoft.** Minecraft is a trademark of Mojang Studios. All game data is used for informational purposes only.

---

## Download

**[Download the latest installer from Releases](https://github.com/Dev-VulX/Spyglass-Connect/releases/latest)**

Or build from source:
```bash
./gradlew run
```

**Requirements:** Java 21+ | Windows, macOS, or Linux

---

## Features

### Save Detection

Automatically finds Minecraft worlds from:
- **Default launcher** — `.minecraft/saves/`
- **Prism Launcher** — auto-detects all instances
- **Custom paths** — add any directory (Pterodactyl servers, modded launchers, etc.)

### QR Code Pairing

1. Launch Spyglass Connect on your PC
2. Open the Spyglass app on your phone
3. Tap **Connect to PC** and scan the QR code
4. Connected — your phone auto-reconnects next time

### What You Can See on Your Phone

| Feature | Description |
|---------|-------------|
| **Inventory** | Full 36-slot inventory, armor, offhand, and ender chest |
| **Chest Finder** | Search for any item across all containers in the world |
| **Structures** | Locations of villages, temples, monuments, and more |
| **Overhead Map** | Terrain map with structure markers and player position |
| **Player Stats** | Health, food, XP level, coordinates, and dimension |

### Live Updates

A file watcher monitors your world folder and pushes changes to your phone automatically when Minecraft saves.

### Encryption

All communication is encrypted with ECDH key exchange + AES-256-GCM. Keys are persisted so you only need to scan the QR code once.

---

## How It Works

Spyglass Connect reads your Minecraft save files directly (it never modifies them). It parses:

- `level.dat` — world metadata and player data
- `region/*.mca` — Anvil region files for chest contents, structures, and map rendering
- `playerdata/*.dat` — player inventory and stats

Data is served over a local WebSocket server (port 29170) to the Spyglass Android app on the same WiFi network. The phone discovers the desktop via mDNS for automatic reconnection.

---

## Configuration

Click the gear icon in the app to:

- **Toggle Prism Launcher detection** — auto-scan Prism Launcher instances
- **Add custom save directories** — point to any folder containing Minecraft worlds

Settings are stored in `~/.spyglass-connect/config.json`.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin/JVM |
| UI | Compose Multiplatform |
| Server | Ktor + Netty WebSocket |
| NBT Parsing | Querz NBT |
| Encryption | BouncyCastle (ECDH) + javax.crypto (AES-GCM) |
| QR Code | ZXing |
| Discovery | JmDNS (mDNS) |

---

## Related Projects

- **[Spyglass](https://github.com/Dev-VulX/Spyglass)** — The Android companion app
- **[Spyglass-Data](https://github.com/Dev-VulX/Spyglass-Data)** — Minecraft reference data used by the Android app

---

## Credits

**Spyglass Connect** is created by **Beryndil**.

---

## Privacy

Spyglass Connect runs entirely on your local machine. No data is sent to any external server. All communication between the desktop app and your phone stays on your local WiFi network.

---

## License

Copyright (c) 2026 Beryndil. All rights reserved. See [LICENSE](LICENSE) for details.

---

*Built with Kotlin, Compose Multiplatform, and Ktor.*
