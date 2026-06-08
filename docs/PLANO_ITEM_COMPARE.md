# PLANO — Comparativo de Item (melhor/pior que o equipado) [ITEM_COMPARE]

> Status: **IMPLEMENTADO** (2026-06-08). Só frontend (`app.js`) — **zero mudança no backend**.

## Conceito

Mostrar, ao olhar um item, se ele é **melhor/pior/igual** ao que o jogador tem equipado naquele
slot — com um **selo** (▲ Better / ▼ Worse / ≈ Same / ✦ New slot) **+ os deltas por stat**
(`+5 ATK  −2 DEF  +3 STR`, verde/vermelho). Aparece em **4 telas**: Shop, Inventário, Forja (craft)
e na **dialog de loot** (quando recebe o item).

Reaproveita o critério que já existia no "Equip ✓ better" do loot: **soma dos 6 stats**
(ATK+DEF+HP+STR+DEX+LUK) do candidato vs o equipado do mesmo slot.

## Decisão (alinhada com o dono)

| Tema | Decisão |
|------|---------|
| Estilo | **Selo + deltas por stat** (verde/vermelho). |
| Critério "melhor" | Soma dos 6 stats (consistente com o `equipUpgrade` antigo). |
| Onde | Shop, Inventário (bag), Craft, dialog de loot (zona/coleta + quest). |
| Backend | **Nenhuma mudança** — tudo client-side a partir dos dados já enviados. |

## Como funciona (frontend)

Helper único em [app.js](../backend/src/main/resources/static/app.js) (logo após `ALL_SLOTS`):

- **`equippedByType`** — cache `{slot → item equipado}`. Atualizado no `loadInventory`
  (`equippedByType = equipped`), no `renderSmithing`/loot (`setEquippedCache(inv)`), e
  `ensureEquipped()` busca o inventário se o cache estiver vazio (shop/quest abertos antes do invent.).
- **`compareToEquipped(cand, slot)`** — normaliza stats (`attackBonus…` de item **ou** `atk…` de
  receita de craft) e devolve `{ verdict: better|worse|same|new, deltas[], cur }`.
- **`compareHtml(cand, slot)`** — o selo + deltas prontos (string). `''` se não há como comparar.

### Pontos de uso

| Tela | Função | Slot |
|------|--------|------|
| Shop | `loadShop` (card do item) | `i.type` |
| Inventário (bag) | `loadInventory` (card do item) | `item.type` |
| Craft | `renderSmithing` (card da receita) | derivado: `r.weaponType ? 'WEAPON' : 'ARMOR'` |
| Loot zona/coleta | bloco do `equipUpgrade` → `showCollectModal({compareInfo})` | `dropped.type` |
| Quest result | `showQuestResultModal` → `showCollectModal({compareInfo})` | `droppedItem.type` |

`showCollectModal` ganhou o parâmetro **`compareInfo`** (HTML) renderizado acima do log/botões.

## Notas / limites

- Critério é **soma simples** dos stats — não pondera HP vs ATK nem afixos/sockets/elemento. É um
  "hint" rápido, não um cálculo de DPS. Fácil de evoluir depois (mexe só no `compareToEquipped`).
- **Durabilidade** ignorada (item quebrado conta os stats cheios) — igual ao comportamento antigo.
- Arma de classe não-usável (ex.: arqueiro vendo espada no shop) **ainda mostra** o comparativo; o
  **equipar** continua bloqueado à parte (`weaponUsable`).
- Cache pode ficar levemente **stale** se equipar algo e ir direto pra outra tela sem reabrir o
  inventário; `loadInventory`/shop/craft refrescam, então o risco é baixo.
- Labels via `t('compare.*') || 'English'` — pronto p/ i18n, mostra EN por enquanto. [idioma UI=EN]
