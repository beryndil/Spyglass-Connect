🌐 [English](README.md) | [Español](README.es.md) | [Português](README.pt.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [日本語](README.ja.md)

# Spyglass Connect

**Desktop-Begleitanwendung für die [Spyglass](https://github.com/beryndil/Spyglass) Android-App.**

Spyglass Connect läuft auf deinem PC und liest die Speicherdateien von Minecraft Java Edition, dann überträgt es die Daten an die Spyglass-App auf deinem Handy über lokales WLAN. Sieh dir dein Inventar an, finde Gegenstände in Truhen, erkunde Strukturen und betrachte eine Übersichtskarte — alles von deinem Handy aus, während du spielst.

## Haftungsausschluss

**Spyglass Connect ist nicht mit Mojang Studios oder Microsoft verbunden, wird nicht von ihnen unterstützt oder ist mit ihnen assoziiert.** Minecraft ist eine Marke von Mojang Studios. Alle Spieldaten werden ausschließlich zu Informationszwecken verwendet.

---

## Download

**[Lade den neuesten Installer von Releases herunter](https://github.com/beryndil/Spyglass-Connect/releases/latest)**

Oder aus dem Quellcode bauen:
```bash
./gradlew run
```

**Voraussetzungen:** Java 21+ | Windows, macOS oder Linux

---

## Funktionen

### Speicherstand-Erkennung

Findet automatisch Minecraft-Welten in:
- **Standard-Launcher** — `.minecraft/saves/`
- **Prism Launcher** — erkennt automatisch alle Instanzen
- **Benutzerdefinierte Pfade** — füge beliebige Verzeichnisse hinzu (Pterodactyl-Server, Mod-Launcher, etc.)

### QR-Code-Kopplung

1. Starte Spyglass Connect auf deinem PC
2. Öffne die Spyglass-App auf deinem Handy
3. Tippe auf **Mit PC verbinden** und scanne den QR-Code
4. Verbunden — dein Handy verbindet sich beim nächsten Mal automatisch wieder

### Was Du auf Deinem Handy Sehen Kannst

| Funktion | Beschreibung |
|---------|-------------|
| **Inventar** | Vollständiges 36-Slot-Inventar, Rüstung, Nebenhand und Endertruhe |
| **Truhenfinder** | Suche nach beliebigen Gegenständen in allen Behältern der Welt |
| **Strukturen** | Standorte von Dörfern, Tempeln, Monumenten und mehr |
| **Übersichtskarte** | Geländekarte mit Strukturmarkierungen und Spielerposition |
| **Spielerstatistiken** | Gesundheit, Nahrung, XP-Stufe, Koordinaten und Dimension |
| **Statistiken** | Gesamtstatistiken — abgebaute Blöcke, getötete Mobs, zurückgelegte Strecke, Spielzeit und mehr |
| **Fortschritte** | Alle 125 Fortschritte mit Abschlussstatus aus deiner Speicherdatei |

### Live-Aktualisierungen

Ein Dateibeobachter überwacht deinen Weltordner und sendet Änderungen automatisch an dein Handy, wenn Minecraft speichert.

### Verschlüsselung

Die gesamte Kommunikation ist mit ECDH-Schlüsselaustausch + AES-256-GCM verschlüsselt. Schlüssel werden persistent gespeichert, sodass du den QR-Code nur einmal scannen musst.
Beide Apps handeln die Protokollversionen während der Kopplung aus — wenn eine Seite veraltet ist, siehst du eine klare Nachricht, die erklärt, welche App aktualisiert werden muss.

---

## So Funktioniert Es

Spyglass Connect liest deine Minecraft-Speicherdateien direkt (es verändert sie nie). Es analysiert:

- `level.dat` — Welt-Metadaten und Spielerdaten
- `region/*.mca` — Anvil-Regiondateien für Truheninhalte, Strukturen und Kartenrendering
- `playerdata/*.dat` — Spielerinventar und Statistiken
- `stats/<uuid>.json` — Gesamtstatistiken des Spielers (abgebaute Blöcke, Distanz, Kills, etc.)
- `advancements/<uuid>.json` — Fortschritts-Abschlussstatus und Kriterien

Die Daten werden über einen lokalen WebSocket-Server (Port 29170) an die Spyglass Android-App im selben WLAN-Netzwerk übermittelt. Das Handy entdeckt den Desktop via mDNS für automatische Wiederverbindung.

---

## Konfiguration

Klicke auf das Zahnrad-Symbol in der App, um:

- **Prism Launcher-Erkennung umschalten** — automatischer Scan von Prism Launcher-Instanzen
- **Benutzerdefinierte Speicherverzeichnisse hinzufügen** — verweise auf beliebige Ordner mit Minecraft-Welten

Einstellungen werden in `~/.spyglass-connect/config.json` gespeichert.

---

## Technologie-Stack

| Komponente | Technologie |
|-----------|-----------|
| Sprache | Kotlin/JVM |
| UI | Compose Multiplatform |
| Server | Ktor + Netty WebSocket |
| NBT-Analyse | Querz NBT |
| Verschlüsselung | java.security (ECDH) + javax.crypto (AES-256-GCM) |
| Protokollversion | v3 (verschlüsselt, mit Fähigkeitsaushandlung) |
| QR-Code | ZXing |
| Erkennung | JmDNS (mDNS) |

---

## Verwandte Projekte

- **[Spyglass](https://github.com/beryndil/Spyglass)** — Die Android-Begleitanwendung
- **[Spyglass-Data](https://github.com/beryndil/Spyglass-Data)** — Minecraft-Referenzdaten, die von der Android-App verwendet werden

---

## Credits

**Spyglass Connect** wurde von **Beryndil** erstellt.

---

## Datenschutz

Spyglass Connect läuft vollständig auf deinem lokalen Rechner. Es werden keine Daten an externe Server gesendet. Die gesamte Kommunikation zwischen der Desktop-App und deinem Handy bleibt in deinem lokalen WLAN-Netzwerk.

---

## Lizenz

Copyright (c) 2026 Beryndil. Alle Rechte vorbehalten. Siehe [LICENSE](LICENSE) für Details.

---

*Entwickelt mit Kotlin, Compose Multiplatform und Ktor.*
