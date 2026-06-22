# Plano — Balanceamento de Economia & Progressão [BALANCE_ECON]

> Status: **implementado** (2026-06-22). Números são **placeholders p/ tuning no playtest**, todos
> em constantes nomeadas e fáceis de achar.

## Diagnóstico (validação)

Auditoria do combate/torre/drops/PvP apontou **um problema-raiz, três sintomas**:

> **O poder de combate está ~80% no gear e ~20% no nível/atributo, e a oferta de gear é
> ilimitada com o nível do item desacoplado do nível do personagem.**

Evidências:

- **Lv16 limpa o andar 40 da torre.** A torre não tem trava de nível (só estamina + bronze).
  Como o item dropa **no nível do MONSTRO** (loot de chefe = `player+1..20`) e **toda peça** rola
  ATK+HP, um lv16 "montado" tem ~650–750 HP e ~200 ATK de gear — esmaga o chefe do andar 40
  (700 HP / 85 ATK). O nível do personagem virou cosmético.
- **Drops inundam.** Quest de reino (10–60% +20% Treasure Hunter), zona (3/6/10%), chefe errante
  (**100% garantido**, 25% lendário) — empilhados, sem ralo real, bag-cheia vai pro correio
  (capacidade infinita), forja ilimitada. Oferta de item alta demais p/ um mercado Steam (RMT):
  item abundante não segura preço, não gera taxa.
- **PvP "qualquer arma":** budget das 7 armas é igual (sem arma dominante), mas o dano segue a ARMA
  (não a classe) → **Arqueiro+arco** ganha tripla sinergia (DEX = dano + acerto, melhor AGI p/ kiting)
  e tende a passar de 55%. Guerreiro+arco é fraco (DEX cap 30), então não há abuso cruzado. Risco é
  só o Arqueiro real ficar acima da curva. (Fora do escopo deste pacote — ver "Follow-up".)

## As 3 alavancas (mesma raiz)

### Lever 1 — Re-acoplar progressão ao nível

1a. **Teto de nível do item dropado.** `InventoryService.cappedItemLevel(raw, playerLevel)` =
   `min(raw, playerLevel + ITEM_LEVEL_LEAD)`, `ITEM_LEVEL_LEAD = 5`. Aplicado em **todos** os pontos
   de drop: `KingdomService.rollDrop`, `ZoneService.rollBossLoot` + `rollCombatItemDrop`,
   `ExpeditionService` (Incursão). O chefe **continua difícil** no nível dele — só o **item** que ele
   larga é capado perto do seu nível. Mata o "gear endgame cedo".

1b. **Trava de nível na torre.** Em `TowerService.fight()`: não pode encarar andar
   `> nível + TOWER_LEVEL_LEAD` (`= 10`). Subir a torre passa a exigir subir de nível — o gear deixa
   de "carregar" dezenas de andares acima.

### Lever 2 — Cortar a torneira (oferta)

Tabelas de raridade centralizadas em `InventoryService` (knob único): `rollDropRarity(dropChance, rng)`
e `rollBossRarity(rng)`. Usadas por Kingdom + Delve + chefe errante.

| Fonte | Antes | Depois |
|---|---|---|
| Quest top (≥60%) | 5% Leg / 47% Épico / 47% Raro | **2% Leg / 18% Épico / 50% Raro / 30% Incomum** |
| Quest média (≥40%) | 50% Incomum / 50% Raro | **25% Raro / 45% Incomum / 30% Comum** |
| Quest baixa (≥25%) | 50% Comum / 50% Incomum | **40% Incomum / 60% Comum** |
| Chefe garantido | 25% Leg / 40% Épico / 35% Raro | **8% Leg / 32% Épico / 60% Raro** |

Drop de zona normal (60/30/10 comum/incomum/raro) já é modesto — mantido.

### Lever 3 — Estratificar tradeabilidade (RMT)

`InventoryService.MIN_TRADE_RARITY = 3` (Raro+). Só Raro+ pode ir pro mercado:
- `AuctionService.list` rejeita raridade < 3 (`error.item_soulbound`).
- `BlueMerchantService.consign` (mercado Steam) idem.

Comum/Incomum viram **soulbound de mercado**: mantêm o jogador equipado, mas não spammam o mercado.
Você dropa bastante (jogador feliz) **sem** inundar o market — só o topo escasso é negociável, e é nele
que mora a taxa (burn 5%+15% do leilão + corte da Steam).

## Follow-up (fora deste pacote)

- **Ralo de item dedicado** (desmanche → material): hoje o ralo de item é indireto (durabilidade/quebra,
  burn do leilão, taxas). Um "salvage" explícito exige UI no cliente Godot — adiado.
- **PvP Arqueiro+arco:** adicionar matriz com armas variadas ao `CombatBalanceProbeTest` (hoje só testa
  Greatsword, não enxerga o kiting cruzado) e, se Arqueiro+arco > 55%, mexer no AGI cap / `MELEE_CLOSE_CHANCE`.

## Constantes (knobs)

| Constante | Arquivo | Valor |
|---|---|---|
| `ITEM_LEVEL_LEAD` | `InventoryService` | 5 |
| `MIN_TRADE_RARITY` | `InventoryService` | 3 |
| `TOWER_LEVEL_LEAD` | `TowerService` | 10 |
| `rollDropRarity` / `rollBossRarity` | `InventoryService` | (tabelas acima) |
