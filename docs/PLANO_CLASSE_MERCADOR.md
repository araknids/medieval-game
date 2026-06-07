# PLANO — Classe Mercador (Merchant) [MERCADOR]

> Status: **implementado** (fases 1 e 2). `MerchantClassTest` cobre canEquip/economia/venda.
> Estende: classes [CLASSES], armas por tipo [CLASSES_ARMAS], habilidades [HABILIDADES].

## Conceito

Terceira classe: **Mercador**. É a **classe de economia** — combatente **um pouco mais fraco**
que Warrior/Archer, mas cujas habilidades focam em **trade**: +% de drop, +craft, +venda, +coleta.
O equilíbrio vem daí: ele perde mais na luta, mas **gera mais riqueza e gear** → se fortalece
indiretamente (snowball econômico). Usa armas **blunt**: **machado e marreta**.

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Armas | **Só machado + marreta** (restrição por TIPO, não só categoria) |
| Niche | **Triângulo** 🏹Archer › 🛡Warrior › 💰Mercador › 🏹Archer, com o Mercador **um pouco mais fraco** no geral |
| Identidade | **Economia**: skills de +drop / +craft / +venda / +coleta + passiva de bronze |
| Kit | Foco em trade/economia (sem lifesteal) + 1 skill de combate pra não ficar indefeso |

## Stats (combatente levemente abaixo)

| | ATK | DEF | HP | cap STR | cap DEX | cap CON | cap LUK | cap INT |
|---|--:|--:|--:|--:|--:|:--:|--:|--:|
| Warrior  | 15 | 14 | 130 | 80 | 30 | ∞ | 40 | 20 |
| Archer   | 18 |  9 |  95 | 50 | 55 | ∞ | 70 | 20 |
| **Mercador** | 15 | 11 | 115 | 55 | 38 | ∞ | **60** | 20 |

LUK 60 não é só crit: **LUK já dá +drop** no jogo (Attribute LUK = "+1% drop") e alimenta o
`rollDrop`. Então o Mercador naturalmente loota mais. Os números são placeholders p/ tuning.

## Armas (machado + marreta)

- **Mace** = novo `WeaponType` (categoria MELEE, blunt). Perfil: **ATK + STR** (pancada pesada e
  certeira) — distinto do Axe (ATK + LUK, crit). Mercador alterna: Axe = crit, Mace = confiável.
- **Restrição por tipo:** estende a trava atual (categoria) p/ tipos permitidos por classe:
  - `WarriorClass.canEquip(WeaponType)`: Warrior = qualquer MELEE; Archer = qualquer RANGED;
    **Mercador = AXE ou MACE**. `InventoryService.equip` passa a checar `canEquip(tipo)`.
  - Warrior continua usando machado (não muda). Mercador NÃO usa espada/lança/montante.
- Loja/forja/loot do Mercador oferecem machado/marreta (como hoje p/ Archer = arcos).

## Habilidades (árvore Mercador) — foco economia

Mecanismo novo: além das passivas de combate (que entram no `combatStats`), o Mercador tem
**passivas de ECONOMIA** que os serviços consultam via `AbilityService` (getters novos). Não
mexem no combate; mexem em drop/craft/venda/coleta.

| Habilidade | Tipo | Efeito (nível N) | Hook |
|---|---|---|---|
| **Haggler** | passiva | +2×N LUK (crit + drop) **e** +3×N% no preço de **venda** | combatStats + `InventoryService.sell` |
| **Treasure Hunter** | passiva | +2×N% de **chance de drop** de item | `KingdomService.rollDrop` (+ zona) |
| **Master Craftsman** | passiva | +3×N% de **sucesso no craft** | `SmithingService.craftSuccessPct` |
| **Prospector** | passiva | +5×N% de **rendimento de coleta** (recursos) | `ZoneService.resolveGathering` / Gathering |
| **Crushing Blow** | ativa (CD 5) | dano bônus +(8+4×N) (a 1 skill de combate) | `BattleSimulator` (reusa BONUS_DAMAGE) |

- **AbilityService** ganha getters de economia: `sellPriceBonusPct(player)`,
  `dropChanceBonus(player)`, `craftSuccessBonus(player)`, `gatherYieldBonusPct(player)` — cada um
  soma o nível da habilidade × valor/nível (0 se não for Mercador / não aprendeu).
- Os serviços de economia consultam esses getters. Combate do Mercador = base + Haggler(LUK) +
  Crushing Blow. Por isso ele é "um pouco mais fraco": gasta os pontos em economia, não em combate.

## Trial (terceiro caminho)

- A Path Trial (Lv10) passa a oferecer **3 caminhos**: Warrior / Archer / **Merchant**.
- **Merchant Guardian** (placeholder): um guardião blunt de dificuldade média. `ClassChangeService`
  já é genérico (guardião por caminho) — adiciona o terceiro. Frontend mostra 3 cards.
- Virar Mercador: desequipa armas que não sejam machado/marreta (como o Archer perde a espada) e
  dá um machado/marreta inicial.

## O que muda no código

### Novo
- **`WarriorClass.MERCHANT`**: base stats + caps + `weaponCategory()=MELEE` + `canEquip(WeaponType)`.
- **`WeaponType.MACE`** (MELEE, perfil ATK+STR) + keywords (mace, marreta, maul, hammer, martelo).
- **`ClassAbility`**: 5 habilidades MERCHANT (Haggler/Treasure Hunter/Master Craftsman/Prospector/
  Crushing Blow). Novo conceito: passivas de economia (não-combate).
- **`AbilityService`**: getters de economia (sell/drop/craft/gather).
- **Merchant Guardian** no `ClassChangeService`.

### Alterado
- **`InventoryService.equip`**: trava por TIPO (`canEquip`) além da categoria. **`sell`**: aplica
  `sellPriceBonusPct`.
- **`KingdomService.rollDrop`** (e zona): aplica `dropChanceBonus`.
- **`SmithingService.craftSuccessPct`**: aplica `craftSuccessBonus`.
- **`ZoneService.resolveGathering`** (rendimento): aplica `gatherYieldBonusPct`.
- **`ClassChangeService`**: 3º caminho + desequipa arma incompatível + arco→machado inicial.
- **Frontend**: 3º card na Trial; árvore de habilidades do Mercador (passivas de economia
  marcadas como "📦 Economy"); loja/forja oferecendo machado/marreta.
- **DB**: nenhum schema novo (MERCHANT é valor de enum; check de `warrior_class` já é derrubada).

## Combate / equilíbrio

- Triângulo-alvo: **Archer › Warrior › Mercador › Archer** (mantém Archer›Warrior atual).
- Mercador é de propósito **um pouco mais fraco** em combate puro (pontos vão p/ economia).
  Compensa com **riqueza/gear** (mais drop, craft melhor, mais bronze) → fica forte indiretamente.
- Números (stats, %s, guardião) são **placeholders p/ tuning no playtest**.

## Fases

1. **Classe + armas:** MERCHANT (stats/caps), MACE WeaponType, `canEquip` por tipo, Trial 3º
   caminho + guardião, frontend da Trial. (Já dá pra virar Mercador e usar machado/marreta.)
2. **Habilidades de economia:** 5 skills + getters no `AbilityService` + hooks em
   sell/drop/craft/gather + Crushing Blow no combate + UI da árvore.

## Self-crafted gear bonus — [MERCADOR_SELFCRAFT]
"Forjou e usa = melhor nas suas mãos." Itens que o **próprio Mercador forjou** dão **+2.5% nos stats por
nível de Master Craftsman** (até **+25%** no nível 10) quando equipados por ele.
- `InventoryItem.craftedBy` (playerId do forjador; setado em `SmithingService.craftEquipment`) +
  `isSelfCraftedBy(playerId)`. Migração: `inventory_items.crafted_by bigint` (null p/ itens antigos/dropados).
- `AbilityService.selfCraftedStatBonusPct(player)` = `MASTER_CRAFTSMAN.level × 2.5%` (0 se não-Mercador/sem skill).
- Aplicado em `WarriorStatsService.equippedGear`: escala os stats-base (atk/def/hp/str/dex/luk) **do item
  craftado** por `(1+pct%)`. Joias/afixos socketados depois **não** entram nessa escala (é o item em si).
- Só os **seus** itens (comprou de outro Mercador → sem bônus). Caminho de mail (bag cheia no craft) ainda
  não marca `craftedBy` — TODO. Cobertura: `MerchantClassTest.selfCrafted_*`.

## Fora de escopo (futuro)
- **Bônus de riqueza** (stats por bronze que tem/já gastou) — desenhado, adiado a pedido (definir fórmula).
- Marcar `craftedBy` no item entregue por **mail** (quando a bag está cheia no craft).
- Skills ativas de economia (ex.: "leilão relâmpago", "suborno" no PvP).
- Mercador influenciar a Casa de Leilão (taxa menor).
- 4ª classe (Mage, com o INT reservado).
