---
title: "Connect"
description: "Pair your phone with the Spyglass Connect desktop companion to view inventory, find chests, locate structures, and explore a map — all over local WiFi."
subtitle: "Your Minecraft world, on your phone."
cssClass: "connect-page"
---

> **Alpha Software** — Spyglass Connect is in active development. Expect bugs, missing features, and rough edges.

## Overview

Spyglass Connect is a desktop companion app that reads your Minecraft Java Edition save files and streams the data to your phone over local WiFi. No cloud servers, no accounts — everything stays on your network.

**[Get Spyglass Connect for Desktop](https://github.com/beryndil/Spyglass-Connect)**

## How It Works

1. **Launch** Spyglass Connect on your PC (Windows, macOS, or Linux)
2. **Scan** the QR code shown on your PC from the Spyglass app on your phone
3. **Done** — your phone auto-reconnects whenever both devices are on the same WiFi

Under the hood: QR code pairs devices via ECDH key exchange, then communicates over an encrypted WebSocket (AES-256-GCM). mDNS handles automatic reconnection.
Protocol version negotiation ensures both apps are compatible — if either side is outdated, you'll see a clear error message.

## Features

### Character Viewer

See your player's full equipment — armor, held items, offhand, and all stats. Tap any item for its full Spyglass detail page.

### Inventory Viewer

Browse your complete inventory, armor slots, offhand, and ender chest contents. Every item is cross-linked to the Spyglass database.

### Chest Finder

Search for any item across **all containers** in your world — chests, barrels, shulker boxes, hoppers, and more. Results show the container type, coordinates, and item count.

### Structure Locator

Find villages, temples, monuments, strongholds, and all other generated structures. Results include coordinates and distance from your current position.

### Overhead Map

An interactive terrain map showing structure markers, your player position, and terrain features. Zoom and pan to explore your world.

## Requirements

- [Spyglass Connect](https://github.com/beryndil/Spyglass-Connect) running on your PC
- Both devices on the same WiFi network
- Minecraft Java Edition save files accessible on your PC
- Spyglass Connect protocol v2+ (both apps must support the same protocol version)
