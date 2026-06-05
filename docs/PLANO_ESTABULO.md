# Plano — Estábulo (montarias / redução de estamina)

> Decidido em 2026-06-05. Nova aba no Comércio pra comprar cavalos que, equipados,
> reduzem o custo de estamina das ações. Stats ficam pra uma fase futura.

## Visão
- Aba **🐴 Estábulo** no Comércio. Player compra cavalos (gold) que reduzem **% de estamina**
  ao serem equipados. Não é fácil: custa **gold** (1 gold = 10.000 bronze), curva íngreme.
- **Coleção**: compra cada cavalo 1x, fica seu pra sempre, equipa 1 por vez, troca livre.
- Buff atual = **só redução de estamina**. **Bônus de stats = FUTURO** (estrutura extensível).

## Catálogo (`MountType`)
| Cavalo | Redução | Preço | Onde compra |
|---|---|---|---|
| 🐴 Cavalo de Carga | −3% | 10 g | Estábulo |
| 🐎 Cavalo de Montaria | −6% | 30 g | Estábulo |
| 🐎 Corcel de Guerra | −9% | 75 g | Estábulo |
| 🐎 Corcel Real | −12% | 150 g | Estábulo |
| 🏇 Corcel Lendário | −15% | 300 g | Estábulo |
| 💎 Montaria Celestial (VIP) | −20% | 12 💎 SoulStones | **VIP Shop** (só VIP) |

## Modelo de dados
- **`enums/MountType`**: displayName, icon, `staminaReductionPct`, `priceGold`, `priceSoulStones`,
  `vipOnly`. (Campos de stats entram depois, default 0 — não criados agora pra evitar dead fields.)
- **`model/Mount`**: id, `player` (FK), `mountType` (enum STRING), `equipped` (bool default false).
  Tabela `mounts` nova → criada pelo Hibernate (ddl-auto=update); SchemaMigrator só se necessário.
- **Equipado** vive na própria `Mount` (`equipped=true`, no máx. 1 por player). A redução = pct do
  MountType equipado. Sem campo novo no Warrior — consulta `findByPlayerAndEquippedTrue`.

## Backend
- **`MountRepository`**: `findByPlayer`, `existsByPlayerAndMountType`, `findByPlayerAndEquippedTrue`.
- **`EstabuloService`**:
  - `list(player)` → catálogo + estado (owned/equipped/canBuy) de TODOS os 6 (o VIP aparece como
    equipável se já é dono, senão "compre na VIP Shop").
  - `buy(player, mountType)` → se `vipOnly`: exige VIP + cobra SoulStones; senão cobra gold
    (`spendGold`). Bloqueia duplicata (`existsByPlayerAndMountType`). Cria `Mount`.
  - `equip(player, mountType)` → exige posse; desequipa o atual; equipa. `unequip(player)`.
- **`EstabuloController`** `/api/stable`: `GET ` (list), `POST /buy/{mountType}`, `POST /equip/{mountType}`,
  `POST /unequip`. O VIP Shop chama o mesmo `/buy/CELESTIAL_MOUNT`.
- **Redução de estamina** (núcleo): `PlayerService.staminaReductionPct(player)` (consulta a montaria
  equipada) + `discountStamina(player, custoBase) = max(1, round(custoBase*(1-pct/100)))`.
  Aplicar:
  - dentro de `consumeStamina(player, cost)` → cobre Kingdom (quest) e Arena.
  - nos 3 sites inline (Zone.enter, Work.startWork, Tower.enter): trocar `int custo = X` por
    `int custo = playerService.discountStamina(player, X)`.
  - Sem dupla aplicação (os 3 inline NÃO chamam consumeStamina).
  - Em `instant-complete=true` (teste) a estamina é pulada → o desconto só importa em jogo real.
- **`MaintenanceService.softWipe`**: `mountRepository.deleteAllInBatch()` (fresh start zera montarias).

## Frontend
- **index.html**: aba `🐴 Estábulo` + `panel-estabulo` na seção Comércio.
- **app.js**:
  - `switchCommerceTab`: toggle do estabulo + `loadEstabulo()`.
  - `loadEstabulo()`: GET `/api/stable` → cards (ícone, nome, −X% estamina, dono/equipado/Comprar/Equipar).
    O Celestial (VIP) aparece com nota "compre na VIP Shop" se não for dono.
  - `buyMount/equipMount/unequipMount`.
  - `loadVipShop`: adiciona a linha da Montaria Celestial (−20%, 12💎, só VIP) → `buyMount('CELESTIAL_MOUNT')`.
- Obs.: em teste (instant-complete) o custo mostrado nos botões não muda; o desconto vale em jogo real.

## Testes
- `EstabuloServiceTest`/integração: comprar cavalo de gold (debita gold + vira dono), equipar,
  bloquear compra duplicada, bloquear sem fundos. VIP: exige VIP + SoulStones.
- Desconto: `discountStamina(player, 100)` com −15% equipado → 85 (testa o núcleo direto, já que
  instant-complete pula a estamina no fluxo normal).

## Futuro (não nesta fase)
- Bônus de stats nas montarias (ATK/DEF/HP) — adicionar campos no `MountType` + somar no efetivo do
  guerreiro (espelhar itens). Possíveis montarias raras dropáveis em vez de só compráveis.
