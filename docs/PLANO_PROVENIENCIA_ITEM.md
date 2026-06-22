# Plano — Proveniência do Item (de onde veio / quem forjou) [ITEM_PROV]

> Status: **implementado** (2026-06-22). Mostra na ficha do item de onde ele veio (qual monstro/chefe,
> ou baú em qual reino) e, se foi forjado, **por quem** (nome do personagem).

## Contexto

Já existia infra: `InventoryItem.origin` (texto "onde foi encontrado") + `craftedBy` (id do forjador) +
`ItemLoreGenerator.originDrop/FromQuest/...` + o tooltip rico do Godot (`UiKit.item_tooltip_panel`) que já
exibe `origin` e uma pill "Forjado por você" (`selfCrafted`). Faltava: **(a)** alimentar o nome REAL no
`origin` e **(b)** mostrar o nome de **outro** forjador (hoje só "você").

## Decisões (confirmadas com o dono)

- **Forjado por:** usa o **nome do personagem** (guerreiro) — mesma identidade do ranking/guilda.
- **Origem do drop** (mapeamento por fonte):

| Fonte | Label (frase pronta no `origin`) |
|---|---|
| Chefe errante (Zona) | `Obtido ao derrotar {nomeDoChefe}` (`originDrop`) |
| Caça na Fortaleza (Zona COMBAT) | `Caçado em {Fortaleza Maldita}` (`originHunt`) |
| Missão COM luta | `Obtido ao derrotar {nomeDoMonstro}` — usa `res.monsterName` real (`originDrop`) |
| Missão SEM luta | `Encontrado num baú em {Reino}` (`originChest`) |
| Loja / Forja / Inicial | mantêm o texto que já tinham |

## Implementação

**Backend:**
- `ItemLoreGenerator`: + `originChest(place)` ("Found in a chest at {0}."), `originHunt(place)` ("Hunted in {0}.").
- `KingdomService.rollDrop`: recebe `String origin`; o call site (`collectQuest`) calcula:
  combate → `originDrop(res.monsterName)`; senão → `originChest(reino.displayName)`.
- `ZoneService.rollBossLoot`: recebe `bossName` → `originDrop(bossName)`.
- `ZoneService.rollCombatItemDrop`: `originHunt("Cursed Fortress")`.
- `ItemResponse` (DTO): + `craftedByName` (resolvido pelo controller via `WarriorRepository.findByPlayer_IdIn`,
  batch p/ a lista; single no equip/unequip). Mantém `selfCrafted`.
- `WarriorRepository`: + `findByPlayer_IdIn(Collection<Long>)` (batch, player eager).

**Godot (`UiKit.item_tooltip_panel`):**
- Pill "Forjado por {nome}" quando `craftedByName` != "" e não-self (self continua "Forjado por você").
- Exibe o `origin` como **frase pronta** (tira o prefixo redundante "Obtido em:" — o backend já manda a frase completa).

## Follow-up

- **Delve/Incursão** (`ExpeditionService`) continua com origem genérica ("Delve"/"Mercador Errante"); diferenciar
  baú vs combate na run fica p/ depois (precisa do tipo do nó no `rollGear`).
