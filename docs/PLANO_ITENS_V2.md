# Plano — Itemização Profunda (Itens V2)

> Documento de planejamento (fase de design). Decisões tomadas com o dono em 2026-06-04.
> **Status: design travado para a Fase A. Não implementado ainda.**
> Regra do projeto: discutir + documentar ANTES de codar (este doc) — implementar depois.

---

## Visão

Transformar loot em **caça**. Hoje os itens são stats planos (ATK/DEF/HP fixos), raridade int **1-4**
(Comum→Épico), 0-3 sockets, lore/origem gerados. Falta o que dá vício em RPG: **variação por item**
(afixos), um **topo de cadeia** (Lendário) e, depois, **objetivos de conjunto** (sets).

### Decisões fechadas (2026-06-04)
1. **Faseamento:** Fase A = **Afixos + tier Lendário** juntos; **Sets** ficam pra Fase B.
2. **Afixos concedem:** stats **planos (ATK/DEF/HP)** **+ atributos (+STR/+DEX/+LUK)** — reusa o
   sistema D&D existente (STR→bônus de ataque, DEX→AC, LUK→crit/drop) sem reescrever o motor.
3. **Lendário:** **só tier de stats** agora (números maiores + máx. afixos + sockets máx.).
   Lendários **únicos** com efeito especial (lifesteal, refletir dano…) ficam pra uma rodada futura.
4. **Reforjar = re-rolar afixos** (dá propósito ao reforge que já existe + vira dreno de bronze).

---

## Baseline (como é hoje)

- `InventoryItem`: `name`, `type` (ItemType=slot), `attackBonus/defenseBonus/healthBonus` (int planos),
  `rarity` (int 1-4), `sockets` (0-3), `guarded`, `description` (lore), `origin`, `durability` (0-100).
- Stats de combate: `WarriorStatsService.equippedItemBonus()` soma **base efetiva + joias** (flat);
  `combatStats()` devolve `[atk, def, hp, dex, strBonus, luk]`. Itens quebrados (durab. 0) não somam.
- Joias: `SocketedGem` (tabela própria), carregadas em **batch** (`findAllByItemIn`) p/ evitar N+1.
- Itens nascem em vários lugares: drop de quest, loja (rotação), craft na Forja, itens iniciais, mail.
- Reforge já existe na Forja (piso de 45%).

---

# FASE A — Afixos + Tier Lendário

## A1. Tier Lendário (raridade 5)

- `rarity = 5`. Sempre com **sockets = 3** e **número máximo de afixos (4)**.
- **Orçamento de stats:** base ≈ **Épico × 1.4** (ajustável). Lendário é o topo da cadeia.
- **Fontes de drop (raras):**
  - **Caça ao Chefe** (quest 30 min): chance pequena (ex.: ~2%).
  - **Torre**, andares altos (ex.: ≥ 15): chance pequena escalando com o andar.
  - (Futuro: world boss / evento.)
- Frontend: classe CSS `rarity-5` (dourado, ex.: `#e6a23c`/`#ff8c00`); atualizar mapas de nome/cor
  de raridade (hoje vão até 4).
- DB: `rarity` é int → **sem** dor de check-constraint. Só adicionar o estilo no front.

## A2. Afixos (prefixo + sufixo)

### Quantidade por raridade
| Raridade | Afixos |
|----------|--------|
| Comum (1) | 0 |
| Incomum (2) | 1 |
| Raro (3) | 2 |
| Épico (4) | 3 |
| Lendário (5) | 4 |

Itens iniciais (Comuns) **não mudam** (0 afixos).

### Modelo de dados
- Nova entidade `ItemAffix` → tabela `item_affixes` (`id`, `item_id` FK, `affix` enum, `magnitude` int).
  `@OneToMany` lógico via query; carregada em **batch** (`findAllByItemIn`), igual às joias
  (respeita `open-in-view=false`).
- Por que tabela e não colunas: o nº de afixos varia (0–4); tabela filha é limpa e extensível.

### Catálogo de afixos (`enum Affix`)
Cada afixo tem: `displayName`, `position` (PREFIX | SUFFIX), `stat` (ATK/DEF/HP/STR/DEX/LUK),
e uma **faixa de magnitude** (min–max) que escala com a raridade do item.

Pool inicial (provisório, ajustável):

**Prefixos (adjetivo):**
- *Sharp* → +ATK · *Heavy* → +DEF · *Sturdy* → +HP · *Brutal* → +STR · *Swift* → +DEX · *Lucky* → +LUK

**Sufixos ("of the …"):**
- *of the Tiger* → +ATK · *of the Turtle* → +DEF · *of the Bear* → +HP · *of the Ox* → +STR ·
  *of the Fox* → +DEX · *of the Cat* → +LUK

> Magnitudes modestas pra não inflar a economia (Lendário rara; durabilidade e custo de reforge seguram).
> Faixas exatas definidas na implementação (planos maiores que atributos: ex. +ATK 3–12 vs +STR 1–4).

### Geração (roll no drop)
- Após criar o item base, sortear **N afixos distintos** (N pela raridade), com mistura de
  prefixo/sufixo, e rolar a magnitude na faixa (escalada pela raridade).
- Centralizar num helper (`ItemAffixRoller` ou estender `ItemLoreGenerator`) chamado nos pontos de
  criação que devem ter afixos: **drop de quest, loja, craft**. (Itens iniciais = Comum = 0 → no-op.)

### Nome do item
- `name` = `[prefixo] BaseName [of the <sufixo>]`.
  Ex.: *Iron Sword* + Sharp + of the Bear → **"Sharp Iron Sword of the Bear"**.
- Usa o **1º** prefixo como adjetivo e o **1º** sufixo como "of the …". Afixos extras (Épico/Lendário)
  **não** entram no nome, mas aparecem como **linhas de bônus** no card do item.

### Stats no combate
- `equippedItemBonus()` passa a somar os afixos **planos** (ATK/DEF/HP).
- Novo `equippedAttributeBonus()` (str/dex/luk) com os afixos de **atributo**; `combatStats()` soma:
  - `strEfetivo = STR + afixStr` → recalcula `attackBonus = floor(strEfetivo/20)`
  - `dexEfetivo = DEX + buffDex + afixDex` → AC
  - `lukEfetivo = LUK + afixLuk` → janela de crit / Fortune Save / drop
- Item quebrado (durab. 0) **não** aplica afixos (consistente com base/joias).

### Reforjar = re-rolar afixos (Forja)
- `reforge` passa a **re-rolar todos os afixos** do item (mantém a contagem da raridade), por um custo
  de bronze que escala com a raridade (dreno). Mecânica de "caçar o roll perfeito".
- Integrar com o reforge atual (rever o piso de 45% — vale p/ afixos ou só base? decidir na impl;
  provavelmente cada afixo re-rola na sua faixa cheia).

### Frontend
- `rarity-5` no CSS; card de item lista **linhas de afixo** (ex.: "⚔ Sharp +8 ATK", "🍀 of the Cat +2 LUK").
- Resposta da API de inventário/loja inclui os afixos (`InventoryController` + DTOs).

## A3. Migração / DB (Fase A)
- `item_affixes`: tabela nova → Hibernate `ddl-auto=update` cria. (Se precisar, `SchemaMigrator` cobre.)
- `rarity=5`: int, sem constraint.
- `combatStats` muda → coberto por testes (combate/arena/torre/zona usam o mesmo método).

## A4. Riscos / balance
- **Inflação de poder:** manter magnitudes modestas; Lendário raro; reforge como dreno; durabilidade segue.
- **Pontos de criação espalhados:** garantir que TODOS os caminhos de drop passem pelo roller (senão
  itens saem sem afixo). Mapear na impl: quest drop, loja, craft (e confirmar mail/starter = sem afixo).
- **open-in-view=false:** carregar afixos em batch dentro de transação (padrão das joias).

---

# FASE B — Sets (bônus de conjunto)  *(design depois)*

Esboço (a detalhar quando chegar a vez):
- Peças nomeadas agrupadas por `setId`; equipar 2/4/6 dá bônus crescentes (planos + atributos + talvez
  efeito especial no topo). Catálogo `enum ItemSet`. Rastrear peças equipadas do mesmo set em `combatStats`.
- Fontes: drops temáticos (chefes da Torre / reinos). É o **objetivo de longo prazo** do loot.
- Mais trabalho: catálogo + tracking + UI de progresso do set.

---

## Plano de implementação (Fase A, por etapas, cada uma verde antes da próxima)

1. **Tier Lendário (raridade 5):** stats, sockets máx., CSS, fontes de drop (quest/torre).
2. **Modelo de afixos:** entidade/tabela `ItemAffix` + enum `Affix` + carregamento em batch.
3. **Roll no drop:** helper central + wiring nos pontos de criação (quest/loja/craft) + nomeação.
4. **Stats:** `equippedItemBonus` (planos) + `equippedAttributeBonus` (STR/DEX/LUK) em `combatStats`.
5. **Reforge = re-roll** na Forja (custo/dreno).
6. **Frontend:** linhas de afixo no card + `rarity-5` + DTOs.
7. **Docs:** sincronizar FEATURES/GDD/TEST_PLAN.

---

*Plano vivo — atualizar conforme as fases forem implementadas. Origem: pedido de aprofundar itemização
(2026-06-04). Próximo passo: implementar a Fase A (após ok do dono).*
