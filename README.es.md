🌐 [English](README.md) | [Español](README.es.md) | [Português](README.pt.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [日本語](README.ja.md)

# Spyglass Connect

**Aplicación de escritorio complementaria para la app Android [Spyglass](https://github.com/beryndil/Spyglass).**

Spyglass Connect se ejecuta en tu PC y lee los archivos de guardado de Minecraft Java Edition, luego transmite los datos a la app Spyglass en tu teléfono a través de WiFi local. Ve tu inventario, encuentra objetos en cofres, explora estructuras y visualiza un mapa aéreo — todo desde tu teléfono mientras juegas.

## Aviso Legal

**Spyglass Connect no está afiliado, respaldado ni asociado con Mojang Studios o Microsoft.** Minecraft es una marca registrada de Mojang Studios. Todos los datos del juego se utilizan únicamente con fines informativos.

---

## Descarga

**[Descarga el instalador más reciente desde Releases](https://github.com/beryndil/Spyglass-Connect/releases/latest)**

O compila desde el código fuente:
```bash
./gradlew run
```

**Requisitos:** Java 21+ | Windows, macOS o Linux

---

## Funcionalidades

### Detección de Guardados

Encuentra automáticamente los mundos de Minecraft desde:
- **Launcher predeterminado** — `.minecraft/saves/`
- **Prism Launcher** — detecta automáticamente todas las instancias
- **Rutas personalizadas** — añade cualquier directorio (servidores Pterodactyl, launchers con mods, etc.)

### Emparejamiento por Código QR

1. Inicia Spyglass Connect en tu PC
2. Abre la app Spyglass en tu teléfono
3. Toca **Conectar al PC** y escanea el código QR
4. Conectado — tu teléfono se reconecta automáticamente la próxima vez

### Lo Que Puedes Ver en Tu Teléfono

| Función | Descripción |
|---------|-------------|
| **Inventario** | Inventario completo de 36 ranuras, armadura, mano secundaria y cofre de ender |
| **Buscador de Cofres** | Busca cualquier objeto en todos los contenedores del mundo |
| **Estructuras** | Ubicaciones de aldeas, templos, monumentos y más |
| **Mapa Aéreo** | Mapa de terreno con marcadores de estructuras y posición del jugador |
| **Estadísticas del Jugador** | Salud, hambre, nivel de XP, coordenadas y dimensión |
| **Estadísticas** | Estadísticas de por vida — bloques minados, mobs eliminados, distancia recorrida, tiempo de juego y más |
| **Logros** | Los 125 logros con estado de completado extraído de tu archivo de guardado |

### Actualizaciones en Vivo

Un observador de archivos monitorea la carpeta de tu mundo y envía los cambios a tu teléfono automáticamente cuando Minecraft guarda.

### Cifrado

Toda la comunicación está cifrada con intercambio de claves ECDH + AES-256-GCM. Las claves se almacenan de forma persistente, así que solo necesitas escanear el código QR una vez.
Ambas apps negocian versiones de protocolo durante el emparejamiento — si alguna de las dos está desactualizada, verás un mensaje claro explicando cuál app actualizar.

---

## Cómo Funciona

Spyglass Connect lee tus archivos de guardado de Minecraft directamente (nunca los modifica). Analiza:

- `level.dat` — metadatos del mundo y datos del jugador
- `region/*.mca` — archivos de región Anvil para contenido de cofres, estructuras y renderizado de mapas
- `playerdata/*.dat` — inventario y estadísticas del jugador
- `stats/<uuid>.json` — estadísticas de por vida del jugador (bloques minados, distancia, eliminaciones, etc.)
- `advancements/<uuid>.json` — estado de completado de logros y criterios

Los datos se sirven a través de un servidor WebSocket local (puerto 29170) a la app Spyglass Android en la misma red WiFi. El teléfono descubre el escritorio mediante mDNS para reconexión automática.

---

## Configuración

Haz clic en el ícono de engranaje en la app para:

- **Activar/desactivar detección de Prism Launcher** — escaneo automático de instancias de Prism Launcher
- **Añadir directorios de guardado personalizados** — apunta a cualquier carpeta que contenga mundos de Minecraft

Los ajustes se almacenan en `~/.spyglass-connect/config.json`.

---

## Stack Tecnológico

| Componente | Tecnología |
|-----------|-----------|
| Lenguaje | Kotlin/JVM |
| UI | Compose Multiplatform |
| Servidor | Ktor + Netty WebSocket |
| Análisis NBT | Querz NBT |
| Cifrado | java.security (ECDH) + javax.crypto (AES-256-GCM) |
| Versión de Protocolo | v3 (cifrado, con negociación de capacidades) |
| Código QR | ZXing |
| Descubrimiento | JmDNS (mDNS) |

---

## Proyectos Relacionados

- **[Spyglass](https://github.com/beryndil/Spyglass)** — La app Android complementaria
- **[Spyglass-Data](https://github.com/beryndil/Spyglass-Data)** — Datos de referencia de Minecraft utilizados por la app Android

---

## Créditos

**Spyglass Connect** fue creado por **Beryndil**.

---

## Privacidad

Spyglass Connect se ejecuta completamente en tu máquina local. No se envían datos a ningún servidor externo. Toda la comunicación entre la app de escritorio y tu teléfono permanece en tu red WiFi local.

---

## Licencia

Copyright (c) 2026 Beryndil. Todos los derechos reservados. Consulta [LICENSE](LICENSE) para más detalles.

---

*Desarrollado con Kotlin, Compose Multiplatform y Ktor.*
