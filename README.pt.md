🌐 [English](README.md) | [Español](README.es.md) | [Português](README.pt.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [日本語](README.ja.md)

# Spyglass Connect

**Aplicativo desktop complementar para o app Android [Spyglass](https://github.com/beryndil/Spyglass).**

O Spyglass Connect roda no seu PC e lê os arquivos de salvamento do Minecraft Java Edition, transmitindo os dados para o app Spyglass no seu celular via WiFi local. Veja seu inventário, encontre itens em baús, explore estruturas e visualize um mapa aéreo — tudo pelo seu celular enquanto joga.

## Aviso Legal

**Spyglass Connect não é afiliado, endossado ou associado à Mojang Studios ou Microsoft.** Minecraft é uma marca registrada da Mojang Studios. Todos os dados do jogo são utilizados apenas para fins informativos.

---

## Download

**[Baixe o instalador mais recente em Releases](https://github.com/beryndil/Spyglass-Connect/releases/latest)**

Ou compile a partir do código-fonte:
```bash
./gradlew run
```

**Requisitos:** Java 21+ | Windows, macOS ou Linux

---

## Funcionalidades

### Detecção de Salvamentos

Encontra automaticamente os mundos do Minecraft em:
- **Launcher padrão** — `.minecraft/saves/`
- **Prism Launcher** — detecta automaticamente todas as instâncias
- **Caminhos personalizados** — adicione qualquer diretório (servidores Pterodactyl, launchers com mods, etc.)

### Pareamento por Código QR

1. Inicie o Spyglass Connect no seu PC
2. Abra o app Spyglass no seu celular
3. Toque em **Conectar ao PC** e escaneie o código QR
4. Conectado — seu celular reconecta automaticamente na próxima vez

### O Que Você Pode Ver no Seu Celular

| Recurso | Descrição |
|---------|-------------|
| **Inventário** | Inventário completo de 36 slots, armadura, mão secundária e baú do ender |
| **Buscador de Baús** | Pesquise qualquer item em todos os contêineres do mundo |
| **Estruturas** | Localizações de vilas, templos, monumentos e mais |
| **Mapa Aéreo** | Mapa de terreno com marcadores de estruturas e posição do jogador |
| **Estatísticas do Jogador** | Vida, fome, nível de XP, coordenadas e dimensão |
| **Estatísticas** | Estatísticas gerais — blocos minerados, mobs eliminados, distância percorrida, tempo de jogo e mais |
| **Progressos** | Todos os 125 progressos com status de conclusão extraídos do seu arquivo de salvamento |

### Atualizações em Tempo Real

Um observador de arquivos monitora a pasta do seu mundo e envia as alterações para o seu celular automaticamente quando o Minecraft salva.

### Criptografia

Toda a comunicação é criptografada com troca de chaves ECDH + AES-256-GCM. As chaves são armazenadas de forma persistente, então você só precisa escanear o código QR uma vez.
Ambos os apps negociam versões de protocolo durante o pareamento — se algum deles estiver desatualizado, você verá uma mensagem clara explicando qual app atualizar.

---

## Como Funciona

O Spyglass Connect lê seus arquivos de salvamento do Minecraft diretamente (nunca os modifica). Ele analisa:

- `level.dat` — metadados do mundo e dados do jogador
- `region/*.mca` — arquivos de região Anvil para conteúdo de baús, estruturas e renderização de mapas
- `playerdata/*.dat` — inventário e estatísticas do jogador
- `stats/<uuid>.json` — estatísticas gerais do jogador (blocos minerados, distância, eliminações, etc.)
- `advancements/<uuid>.json` — status de conclusão de progressos e critérios

Os dados são servidos através de um servidor WebSocket local (porta 29170) para o app Spyglass Android na mesma rede WiFi. O celular descobre o desktop via mDNS para reconexão automática.

---

## Configuração

Clique no ícone de engrenagem no app para:

- **Ativar/desativar detecção do Prism Launcher** — escaneamento automático de instâncias do Prism Launcher
- **Adicionar diretórios de salvamento personalizados** — aponte para qualquer pasta contendo mundos do Minecraft

As configurações são armazenadas em `~/.spyglass-connect/config.json`.

---

## Stack Tecnológico

| Componente | Tecnologia |
|-----------|-----------|
| Linguagem | Kotlin/JVM |
| UI | Compose Multiplatform |
| Servidor | Ktor + Netty WebSocket |
| Análise NBT | Querz NBT |
| Criptografia | java.security (ECDH) + javax.crypto (AES-256-GCM) |
| Versão do Protocolo | v3 (criptografado, com negociação de capacidades) |
| Código QR | ZXing |
| Descoberta | JmDNS (mDNS) |

---

## Projetos Relacionados

- **[Spyglass](https://github.com/beryndil/Spyglass)** — O app Android complementar
- **[Spyglass-Data](https://github.com/beryndil/Spyglass-Data)** — Dados de referência do Minecraft utilizados pelo app Android

---

## Créditos

**Spyglass Connect** foi criado por **Beryndil**.

---

## Privacidade

O Spyglass Connect roda inteiramente na sua máquina local. Nenhum dado é enviado para servidores externos. Toda a comunicação entre o app desktop e seu celular permanece na sua rede WiFi local.

---

## Licença

Copyright (c) 2026 Beryndil. Todos os direitos reservados. Veja [LICENSE](LICENSE) para detalhes.

---

*Desenvolvido com Kotlin, Compose Multiplatform e Ktor.*
