---
title: "Connect"
description: "Verbinde dein Handy mit der Spyglass Connect Desktop-Begleit-App, um Inventar anzusehen, Truhen zu finden, Bauwerke zu orten und eine Karte zu erkunden — alles über lokales WLAN."
subtitle: "Deine Minecraft-Welt auf deinem Handy."
cssClass: "connect-page"
---

> **Alpha-Software** — Spyglass Connect befindet sich in aktiver Entwicklung. Fehler, fehlende Funktionen und Ecken und Kanten sind zu erwarten.

## Überblick

Spyglass Connect ist eine Desktop-Begleit-App, die deine Minecraft Java Edition Speicherdateien liest und die Daten über lokales WLAN auf dein Handy streamt. Keine Cloud-Server, keine Konten — alles bleibt in deinem Netzwerk.

**[Spyglass Connect für Desktop herunterladen](https://github.com/beryndil/Spyglass-Connect)**

## So funktioniert es

1. **Starte** Spyglass Connect auf deinem PC (Windows, macOS oder Linux)
2. **Scanne** den QR-Code auf deinem PC mit der Spyglass App auf deinem Handy
3. **Fertig** — dein Handy verbindet sich automatisch wieder, wenn beide Geräte im selben WLAN sind

Im Hintergrund: Der QR-Code koppelt Geräte über ECDH-Schlüsselaustausch und kommuniziert dann über einen verschlüsselten WebSocket (AES-256-GCM). mDNS sorgt für automatische Wiederverbindung.
Protokoll-Versionsverhandlung stellt sicher, dass beide Apps kompatibel sind — falls eine Seite veraltet ist, wird eine klare Fehlermeldung angezeigt.

## Funktionen

### Charakter-Ansicht

Sieh die gesamte Ausrüstung deines Spielers — Rüstung, gehaltene Gegenstände, Nebenhand und alle Werte. Tippe auf einen Gegenstand für die vollständige Spyglass-Detailseite.

### Inventar-Ansicht

Durchsuche dein komplettes Inventar, Rüstungsplätze, Nebenhand und Endertruhen-Inhalt. Jeder Gegenstand ist mit der Spyglass-Datenbank verknüpft.

### Truhen-Finder

Suche nach jedem Gegenstand in **allen Behältern** deiner Welt — Truhen, Fässer, Shulker-Kisten, Trichter und mehr. Ergebnisse zeigen Behältertyp, Koordinaten und Gegenstandsanzahl.

### Bauwerk-Finder

Finde Dörfer, Tempel, Monumente, Festungen und alle anderen generierten Bauwerke. Ergebnisse enthalten Koordinaten und Entfernung von deiner aktuellen Position.

### Übersichtskarte

Eine interaktive Geländekarte mit Bauwerk-Markierungen, deiner Spielerposition und Geländemerkmalen. Zoome und verschiebe, um deine Welt zu erkunden.

## Voraussetzungen

- [Spyglass Connect](https://github.com/beryndil/Spyglass-Connect) läuft auf deinem PC
- Beide Geräte im selben WLAN-Netzwerk
- Minecraft Java Edition Speicherdateien auf deinem PC zugänglich
- Spyglass Connect Protokoll v2+ (beide Apps müssen dieselbe Protokollversion unterstützen)
