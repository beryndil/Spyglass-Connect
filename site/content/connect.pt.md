---
title: "Connect"
description: "Conecte seu celular ao aplicativo desktop Spyglass Connect para ver o inventário, encontrar baús, localizar estruturas e explorar um mapa — tudo via WiFi local."
subtitle: "Seu mundo de Minecraft, no seu celular."
cssClass: "connect-page"
---

> **Software Alfa** — O Spyglass Connect está em desenvolvimento ativo. Espere bugs, funcionalidades incompletas e imperfeições.

## Visão Geral

O Spyglass Connect é um aplicativo desktop que lê os arquivos de save do Minecraft Java Edition e transmite os dados para o seu celular via WiFi local. Sem servidores na nuvem, sem contas — tudo permanece na sua rede.

**[Baixe o Spyglass Connect para Desktop](https://github.com/beryndil/Spyglass-Connect)**

## Como Funciona

1. **Abra** o Spyglass Connect no seu PC (Windows, macOS ou Linux)
2. **Escaneie** o QR code mostrado no seu PC pelo app Spyglass no celular
3. **Pronto** — seu celular reconecta automaticamente sempre que ambos os dispositivos estiverem na mesma rede WiFi

Por trás dos panos: o QR code conecta os dispositivos via troca de chaves ECDH, depois se comunica por WebSocket criptografado (AES-256-GCM). O mDNS cuida da reconexão automática.
A negociação de versão do protocolo garante que ambos os apps sejam compatíveis — se algum dos lados estiver desatualizado, você verá uma mensagem de erro clara.

## Funcionalidades

### Visualizador de Personagem

Veja o equipamento completo do seu jogador — armadura, itens em mãos, mão secundária e todas as estatísticas. Toque em qualquer item para ver sua página de detalhes completa no Spyglass.

### Visualizador de Inventário

Explore seu inventário completo, slots de armadura, mão secundária e conteúdo do baú do End. Cada item possui links cruzados com o banco de dados do Spyglass.

### Localizador de Baús

Pesquise qualquer item em **todos os contêineres** do seu mundo — baús, barris, caixas de shulker, funis e muito mais. Os resultados mostram o tipo de contêiner, coordenadas e quantidade de itens.

### Localizador de Estruturas

Encontre vilas, templos, monumentos, fortalezas e todas as outras estruturas geradas. Os resultados incluem coordenadas e distância da sua posição atual.

### Mapa Aéreo

Um mapa interativo do terreno mostrando marcadores de estruturas, a posição do jogador e características do terreno. Use zoom e arraste para explorar seu mundo.

## Requisitos

- [Spyglass Connect](https://github.com/beryndil/Spyglass-Connect) rodando no seu PC
- Ambos os dispositivos na mesma rede WiFi
- Arquivos de save do Minecraft Java Edition acessíveis no seu PC
- Spyglass Connect protocolo v2+ (ambos os apps devem suportar a mesma versão do protocolo)
