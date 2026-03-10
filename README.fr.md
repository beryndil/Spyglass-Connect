🌐 [English](README.md) | [Español](README.es.md) | [Português](README.pt.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [日本語](README.ja.md)

# Spyglass Connect

**Application de bureau compagnon pour l'application Android [Spyglass](https://github.com/beryndil/Spyglass).**

Spyglass Connect s'exécute sur votre PC et lit les fichiers de sauvegarde de Minecraft Java Edition, puis transmet les données à l'application Spyglass sur votre téléphone via WiFi local. Consultez votre inventaire, trouvez des objets dans les coffres, explorez les structures et visualisez une carte aérienne — le tout depuis votre téléphone pendant que vous jouez.

## Avertissement

**Spyglass Connect n'est pas affilié, approuvé ou associé à Mojang Studios ou Microsoft.** Minecraft est une marque déposée de Mojang Studios. Toutes les données du jeu sont utilisées à des fins informatives uniquement.

---

## Téléchargement

**[Téléchargez le dernier installateur depuis Releases](https://github.com/beryndil/Spyglass-Connect/releases/latest)**

Ou compilez depuis le code source :
```bash
./gradlew run
```

**Prérequis :** Java 21+ | Windows, macOS ou Linux

---

## Fonctionnalités

### Détection des Sauvegardes

Trouve automatiquement les mondes Minecraft depuis :
- **Launcher par défaut** — `.minecraft/saves/`
- **Prism Launcher** — détecte automatiquement toutes les instances
- **Chemins personnalisés** — ajoutez n'importe quel répertoire (serveurs Pterodactyl, launchers moddés, etc.)

### Appairage par Code QR

1. Lancez Spyglass Connect sur votre PC
2. Ouvrez l'application Spyglass sur votre téléphone
3. Appuyez sur **Se connecter au PC** et scannez le code QR
4. Connecté — votre téléphone se reconnecte automatiquement la prochaine fois

### Ce Que Vous Pouvez Voir sur Votre Téléphone

| Fonctionnalité | Description |
|---------|-------------|
| **Inventaire** | Inventaire complet de 36 emplacements, armure, main secondaire et coffre de l'ender |
| **Chercheur de Coffres** | Recherchez n'importe quel objet dans tous les conteneurs du monde |
| **Structures** | Emplacements des villages, temples, monuments et plus encore |
| **Carte Aérienne** | Carte de terrain avec marqueurs de structures et position du joueur |
| **Statistiques du Joueur** | Santé, nourriture, niveau d'XP, coordonnées et dimension |
| **Statistiques** | Statistiques globales — blocs minés, mobs tués, distance parcourue, temps de jeu et plus encore |
| **Progrès** | Les 125 progrès avec le statut d'achèvement extrait de votre fichier de sauvegarde |

### Mises à Jour en Direct

Un observateur de fichiers surveille le dossier de votre monde et envoie les modifications à votre téléphone automatiquement lorsque Minecraft sauvegarde.

### Chiffrement

Toute la communication est chiffrée avec un échange de clés ECDH + AES-256-GCM. Les clés sont conservées de manière persistante, vous n'avez donc besoin de scanner le code QR qu'une seule fois.
Les deux applications négocient les versions du protocole pendant l'appairage — si l'une d'elles est obsolète, vous verrez un message clair expliquant quelle application mettre à jour.

---

## Comment Ça Fonctionne

Spyglass Connect lit vos fichiers de sauvegarde Minecraft directement (il ne les modifie jamais). Il analyse :

- `level.dat` — métadonnées du monde et données du joueur
- `region/*.mca` — fichiers de région Anvil pour le contenu des coffres, les structures et le rendu de carte
- `playerdata/*.dat` — inventaire et statistiques du joueur
- `stats/<uuid>.json` — statistiques globales du joueur (blocs minés, distance, éliminations, etc.)
- `advancements/<uuid>.json` — statut d'achèvement des progrès et critères

Les données sont servies via un serveur WebSocket local (port 29170) à l'application Spyglass Android sur le même réseau WiFi. Le téléphone découvre le bureau via mDNS pour une reconnexion automatique.

---

## Configuration

Cliquez sur l'icône d'engrenage dans l'application pour :

- **Activer/désactiver la détection de Prism Launcher** — scan automatique des instances Prism Launcher
- **Ajouter des répertoires de sauvegarde personnalisés** — pointez vers n'importe quel dossier contenant des mondes Minecraft

Les paramètres sont stockés dans `~/.spyglass-connect/config.json`.

---

## Stack Technique

| Composant | Technologie |
|-----------|-----------|
| Langage | Kotlin/JVM |
| UI | Compose Multiplatform |
| Serveur | Ktor + Netty WebSocket |
| Analyse NBT | Querz NBT |
| Chiffrement | java.security (ECDH) + javax.crypto (AES-256-GCM) |
| Version du Protocole | v3 (chiffré, avec négociation de capacités) |
| Code QR | ZXing |
| Découverte | JmDNS (mDNS) |

---

## Projets Associés

- **[Spyglass](https://github.com/beryndil/Spyglass)** — L'application Android compagnon
- **[Spyglass-Data](https://github.com/beryndil/Spyglass-Data)** — Données de référence Minecraft utilisées par l'application Android

---

## Crédits

**Spyglass Connect** a été créé par **Beryndil**.

---

## Confidentialité

Spyglass Connect s'exécute entièrement sur votre machine locale. Aucune donnée n'est envoyée à un serveur externe. Toute la communication entre l'application de bureau et votre téléphone reste sur votre réseau WiFi local.

---

## Licence

Copyright (c) 2026 Beryndil. Tous droits réservés. Voir [LICENSE](LICENSE) pour les détails.

---

*Développé avec Kotlin, Compose Multiplatform et Ktor.*
