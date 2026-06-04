# Plano — Nível de Item (Itens V3)

> Planejamento. Decisões aprovadas com o dono em 2026-06-04.
> **Status: IMPLEMENTADO (462 testes verdes) — backend + frontend.**
> Regra: discutir + documentar ANTES de codar.

## Problema
Hoje o poder do item vem **só da raridade** — um Raro no lvl1 = Raro no lvl50. O loot **não escala
com o nível**, e um lvl1 compra um **Épico na loja** e equipa na hora (sem requisito) → pico precoce.

## Decisões (2026-06-04)
1. **Modelo completo de nível de item** (fixo, não sobe). Poder = **nível do item × multiplicador de raridade**.
   → "lvl100 Comum > lvl1 Épico". Raridade continua dando **afixos + sockets + um multiplicador**.
2. **Trava dura pra equipar:** só equipa se `itemLevel ≤ nível do guerreiro`.

## Modelo de stats
- Campo novo `InventoryItem.itemLevel` (int, fixo, default 1).
- Multiplicador de raridade: Comum 1.0 · Incomum 1.2 · Raro 1.45 · Épico 1.75 · Lendário 2.1.
- `scale = itemLevel × rarityMult(rarity)`; stats rolados a partir de `scale`:
  - `maxAtk = round(scale·0.6)` · `maxDef = round(scale·0.6)` · `maxHp = round(scale·2.2)`
  - rola 0..max em cada; garante ≥1 total. (Helper `InventoryService.rollItemStats(level, rarity)`.)
- Exemplos: lvl1/Comum → ~0-1 atk/def, 0-2 hp (fraco). lvl50/Comum → ~0-30 atk/def, 0-110 hp.
  lvl1/Épico → ~0-1 atk/def, 0-4 hp (mas 3 afixos + sockets). **lvl50 Comum esmaga lvl1 Épico.** ✓
- **Afixos** continuam por **raridade** (não escalam com nível nesta fase) — os stats-base carregam a progressão.

## De onde vem o itemLevel
- **Drop de quest / raid de reino:** `itemLevel = nível do guerreiro` (gear sempre relevante).
- **Loja:** `itemLevel` por **faixa de raridade** (Comum 1 · Incomum 5 · Raro 15 · Épico 30) — mantém
  os stats/preço dos templates curados, mas o **requisito de nível** gateia (lvl1 compra o Épico mas
  só equipa no lvl30). *(Loja não regenera stats nesta fase — só ganha itemLevel + requisito.)*
- **Craft (Forja):** `itemLevel = nível do guerreiro` (stats do recipe mantidos nesta fase).
- **Itens iniciais:** itemLevel 1.

## Trava de equipar
- `InventoryService.equip`: bloqueia se `item.getItemLevel() > warrior.getLevel()` → "Requires level X".

## Frontend
- Card do item mostra **"Lv.X"** e, se acima do teu nível, **"Req. Lv.X"** em vermelho + botão Equipar desabilitado.
- Loja mostra o nível/requisito do item.

## Migração
- Coluna `item_level` (default 1) via SchemaMigrator. Soft-wipe limpa. Itens legados = lvl1 (fracos) — ok (banco descartável).

## Etapas
1. **Campo + fórmula + atribuição** (drops/loja/craft/starter via `make` com itemLevel) + SchemaMigrator.
2. **Trava de equipar** por nível.
3. **Frontend** (nível + requisito + gate do botão).
4. **Testes** (lvl100 Comum > lvl1 Épico; equip bloqueado acima do nível) + docs.

## Fora de escopo (futuro)
- Escalar afixos e stats da loja/craft pelo itemLevel; preço da loja por nível.

*Decisões travadas 2026-06-04.*
