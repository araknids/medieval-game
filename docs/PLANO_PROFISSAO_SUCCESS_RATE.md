# Plano — Níveis de Profissão: success rate (craft/socket) + coleta escala com nível

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).

## Objetivo

A Forja ganha **chance de falha** (success rate) que melhora com o nível, no **craft de equipamento**
e no **encaixe de joia (socket)**. A coleta (que já libera recursos melhores por nível) passa a render
**mais quantidade** conforme o nível e a expor os tiers no UI.

## O que JÁ existia (não refazer)

- **Receitas da Forja já têm gate de nível** (refino Lv1/20/40/60/80; craft Lv20-85).
- **Coleta já tem tiers por nível** (`GatheringService.rollFish/getBestOreForLevel/rollGarimpoFragment`):
  Lv20/40/60/80 liberam recursos melhores. **Isso fica como está.**

## Decisões travadas (alinhadas com o dono)

| Tema | Decisão |
|---|---|
| Escopo | Forja (success rate) **+** coleta (escala quantidade por nível) |
| Falha no craft | **Só perde a taxa em bronze** (materiais voltam) + XP reduzido |
| Socket | **Risco ao encaixar a joia** (não cria slot novo) |
| Falha no socket | **Só perde a taxa**; joia e item intactos |
| Refino | Continua **100%** (grind base, não pune) |

## Modelo

### Craft de equipamento (success rate)
- Gate: `smithingLevel ≥ recipe.smithingLevel` (já existe).
- `successPct = min(100, 70 + (smithingLevel − recipe.smithingLevel) × 5)` → 70% no nível exato, 100% em +6.
- **Taxa em bronze** por tentativa (`recipe.bronzeCost`, novo campo, ~`smithingLevel × 20`): paga sempre.
- Sucesso: consome materiais → cria item → XP cheio (`smithingLevel × 10`).
- Falha: materiais **não** consumidos, taxa perdida, **XP reduzido** (~30%).

### Socket de joia (success rate)
- `successPct = min(100, max(5, 50 + smithingLevel − slotIndex × 10))` → confiável ~Lv50; 2º/3º slot mais difícil.
- **Taxa em bronze** por tentativa (flat, ~150).
- Sucesso: consome a joia → cria `SocketedGem` → XP pequeno (~15).
- Falha: joia **não** consumida, taxa perdida, XP pequeno reduzido (~5).

### Criação de joia (coerência)
- Adiciona **gate de nível** = `fragment.levelRequired` (Rubi Lv20, Safira 40, Esmeralda 60, Diamante 80).
- Mantém **100% de sucesso** (mudança mínima; o foco é craft+socket).

### Coleta (escala por nível)
- Quantidade por sessão deixa de ser fixa: `count = max(1, duration/10 + level/25)` (pesca/mineração/garimpo)
  → +1 a cada ~25 níveis (até +4 no Lv100). Os **tiers** de recurso continuam como estão.
- UI: expor "próximo tier no Lv X" (hoje sobe silencioso).

## Mudanças por arquivo

### Backend
- **`service/SmithingService.java`**:
  - `CraftRecipe` ganha `bronzeCost`; `craftEquipment` cobra a taxa, rola sucesso, refunda materiais na falha,
    XP cheio/reduzido. Retorna um resultado com `success`/`message`/item.
  - `socketGem` cobra taxa, rola sucesso (por slot), preserva joia na falha, XP. Retorna `success`/`message`.
  - `craftGem` ganha o gate de nível por fragmento.
  - Helpers: `craftSuccessPct(level, recipe)`, `socketSuccessPct(level, slotIndex)`, constantes (`CRAFT_BASE=70`,
    `CRAFT_STEP=5`, `SOCKET_BASE=50`, `SOCKET_SLOT_PENALTY=10`, fees).
- **`controller/SmithingController.java`**: `GET /recipes` devolve `successPct` + `bronzeCost` por receita de craft;
  `craft`/`socket` devolvem `success` + `message`. (endpoints e assinaturas mantidos)
- **`service/GatheringService.java`**: `rollDrops` usa `count = max(1, duration/10 + level/25)` nas 3 skills.
  Helper `nextTierLevel(skill, level)` (e o tier atual) p/ o UI.
- (Sem migração de schema obrigatória — `bronzeCost` é só de receita em memória; nada novo no banco.)

### Frontend (`static/app.js`)
- Forja: cada receita de craft mostra **Success: X%** e a **taxa**; resultado do craft mostra ✅/❌ (falha
  avisa que os materiais voltaram). Encaixe de joia mostra a chance e o resultado (✅/❌).
- Skills/coleta: badge "next tier at Lv X" por skill de coleta.

### Testes
- **Craft**: nível baixo → falha possível (XP reduzido + materiais voltam + taxa perdida); nível alto → 100%
  (item criado + XP cheio). Sem bronze p/ a taxa → rejeita.
- **Socket**: sucesso encaixa + consome joia; falha preserva joia + perde taxa; slot maior = chance menor.
- **Coleta**: quantidade cresce com o nível (Lv1 vs Lv100, mesma duração).
- Ajustar testes existentes de smithing/socket que assumiam sucesso garantido (ex.: usar nível alto + bronze).

## Consequências / notas
- Forja vira um **sink de bronze** (taxas) e dá peso a subir o nível (confiabilidade + receitas).
- Falhas são **suaves** (perde só a taxa) — combina com idle; sem destruir material/joia.
- Coleta de nível alto rende mais haul, reforçando o valor de subir as skills de coleta.
