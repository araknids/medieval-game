# PLANO — Desgaste de Reparo + Desmontagem (Peças) [DESGASTE][DESMONTAGEM]

> Status: **IMPLEMENTADO** (2026-06-25, commits backend `2e6fd3ee` + Godot). 667 testes verdes.
> Decisões travadas com o dono via /grill-me; números são **placeholders pra tuning**.

## Objetivo
Itens deixam de ser **eternos**. Cada **reparo** corrói o "poder" do item (1–5%); abaixo de
**~50%** ele não pode mais ser reparado — vira sucata. A **desmontagem** transforma itens
(gastos ou indesejados) em **Peças**, que alimentam **reparo**, **craft de armas** e **encaixe de
joias**. Fecha o loop: `drop → uso (durabilidade cai) → reparo (poder cai) → fim de vida →
desmontar → Peças → manutenção de outros itens`. Precisa de fluxo constante de itens novos
(drops/quests) pra sustentar o estoque — é isso que mata a economia infinita de gear.

---

## Mecânica 1 — Desgaste no reparo [DESGASTE]  *(decisão: "Poder %" separado + piso ~50%)*

- **Campo novo** `InventoryItem.powerPct` (int 0–100, default **100**). Migração no
  `SchemaMigrator` (`ALTER TABLE inventory_items ADD COLUMN IF NOT EXISTS power_pct integer NOT NULL DEFAULT 100`).
- **`repairItem`** passa a, além de `durability → 100`, fazer `powerPct -= rand(REPAIR_WEAR_MIN..MAX)`
  (placeholder **1..5**).
- **Piso de reparo** (`REPAIR_FLOOR = 50`): se `powerPct < 50`, o reparo é **bloqueado**
  (`LocalizedException` "item gasto demais — desmonte-o"). Item "morto" = só desmontar.
  (No piso 50, ainda dá pra reparar uma última vez → cai pra 45–49 → trava.)
- **Stats efetivos**: o bônus do item no combate é **multiplicado por `powerPct/100`** em
  `WarriorStatsService.equippedItemBonus` (broken/durabilidade 0 continua **zerando** o bônus, à parte).
  Stats-base ficam **intactos** (reforge/afixo seguem funcionando). Tooltip ganha linha
  **"Poder X%"** (vermelho < 70%). As **joias socketadas NÃO degradam** (poder escala só os stats
  do próprio item). *(ajustável)*
- Vale pra **todo equipamento** com stats. O `powerPct` é **só do reparo** — o **uso** continua
  derrubando só a durabilidade (mecânica atual). Item novo (drop/craft/loja) começa em 100%.
- Reforjar mantém o `powerPct` (re-rola só os stats-base).

---

## Mecânica 2 — Desmontagem → Peças [DESMONTAGEM]  *(decisão FINAL: recurso ÚNICO, qtd por raridade×nível)*

- **Um `ResourceType` único** `SCRAP` (displayName "Salvage" / PT "Peças"), categoria `MATERIAL`
  (sem tiers — mais simples). **A quantidade** dropada é que varia (raridade × nível). i18n `resource.SCRAP.name=Peças`.
- **`dismantleItem(player, itemId)`** → `POST /api/smithing/dismantle/{itemId}`:
  - **Guards** iguais ao vender: **não** equipado / pvpLocked / guarded / listed / consigned / stashed.
  - **Destrói** o item; concede **Peças do tier** (qtd = base por raridade × fator de nível —
    placeholder: raridade 1/2/3/4/5 → **1/2/4/7/12** peças, × `(1 + itemLevel/50)`).
  - **Joias socketadas**: **perdidas** na desmontagem (com **confirm** na UI avisando). *(ajustável)*
  - Bag cheia → entrega por **mail de recurso** (mesmo caminho da Daily).

---

## Usos das Peças (sinks)  *(decisão: reparo = Peças + bronze, substitui o só-bronze, p/ TODO equip)*

1. **Reparo** (substitui o reparo só-bronze): `repairItem` passa a custar **Peças (do tier do item) +
   bronze**. O reparo **sempre** aplica o desgaste de poder (Mecânica 1).
2. **Craft de arma** ("criar armas novas"): receitas de **arma** ganham custo de **Peças** (além de
   barras + bronze).
3. **Encaixar joia** ("add joias"): `socketGem` passa a custar **Peças (do tier do item)** além do
   que já custa.
- Magnitudes = placeholders pra tuning.

---

## UI (Forja → Manutenção) + Godot
- **Forge.gd / aba Manutenção**: junto de Reparar/Reforjar, botão **Desmontar** por item (confirm que
  mostra o que vai dropar + aviso se tiver joia). Reparo mostra o custo em **Peças + bronze**;
  some/desabilita com aviso "desmonte" quando `powerPct < 50`.
- **`UiKit.item_tooltip_panel`**: linha **"Poder X%"** (vermelho < 70%).
- **Estoque de Sucata** aparece na seção de recursos (Mochila/Forja).
- **i18n** PT/EN de tudo.

---

## Backend tocado
- `model/InventoryItem` (+`powerPct`), `config/SchemaMigrator` (coluna), `service/WarriorStatsService`
  (multiplicador `powerPct/100` no bônus de gear), `service/SmithingService` (repair: Peças+desgaste+floor;
  `dismantleItem`; craft/socket consomem Peças), `controller/SmithingController` (+`/dismantle/{id}`),
  `enums/ResourceType` (+`SCRAP_COMMON/FINE/NOBLE`, categoria `SCRAP`), DTOs de item (expõem `powerPct`).
- **Testes**: reparo derruba `powerPct` 1–5; floor 50 bloqueia reparo; `dismantle` rende Peças por
  tier/raridade + respeita guards; reparo consome Peças+bronze; combate aplica `powerPct` (bônus efetivo).

## Ordem sugerida de implementação
1. **Mecânica 1 isolada** (testável sozinha): `powerPct` (campo + migração) + multiplicador no combate +
   reparo aplica desgaste + piso 50 + "Poder %" no tooltip.
2. **Desmontagem (source)**: `ResourceType SCRAP_*` + `dismantleItem` + endpoint + UI Desmontar + ícones.
3. **Peças nos sinks**: reparo (Peças+bronze), craft de arma, socket de joia + **tuning** dos números.

## Knobs abertos (decidir no tuning / podem mudar)
- Mapa de tiers de Peça (por banda de raridade — proposto — vs por metal/nível).
- Números: desgaste por reparo (1–5), piso (50%), yield da desmontagem, custos em Peças.
- Joias na desmontagem: **perdidas** (proposto) vs recuperadas (risco de exploit) vs bloqueia se tiver joia.
- Comparação (▲/▼) e venda usam stat-base; "Poder %" é linha à parte (não reescala a seta). *(proposto)*
