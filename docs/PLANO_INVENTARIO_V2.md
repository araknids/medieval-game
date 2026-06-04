# Plano — Inventário V2 (bag unificada + stash)

> Planejamento. Decisões aprovadas com o dono em 2026-06-04.
> **Status: IMPLEMENTADO (459 testes verdes) — backend + frontend.**
> Regra: discutir + documentar ANTES de codar.

## Decisões (2026-06-04)
1. **Bag free 30 / VIP (ou expandida c/ SoulStone) 50.** (era 10/20)
2. **Bag unificada:** recursos e itens dividem o MESMO pool de slots (hoje recurso fica em armazém
   à parte, ilimitado).
3. **Cada recurso ocupa slot POR UNIDADE** (cada peixe = 1 slot; 5 salmões = 5 slots). Vale p/ **todos**
   os recursos (peixe, minério, fragmento, barra, gema, material).
4. **Stash: 100 slots** (mesma contagem por unidade). **Taxa fixa de 50 bronze por operação** de
   depositar OU retirar (uma operação = mover 1 item, ou mover um recurso de um tipo).

## Modelo de dados
- **`stashed` boolean** em `inventory_items` (default false) e `resource_inventory` (default false).
  - `inventory_items`: itens equipados são sempre BAG (stashed=false). Itens no stash = stashed=true, não equipados.
  - `resource_inventory`: chave única vira **(player, resource_type, stashed)** → o jogador pode ter
    salmão na bag (qtd X) e no stash (qtd Y) como duas linhas.
- Migração: `SchemaMigrator` adiciona a coluna (default false); soft-wipe limpa. Banco descartável.

## Contagem de slots
- **bagSize** = nº de itens não-equipados com stashed=false **+** Σ quantidade de recursos com stashed=false.
- **stashSize** = nº de itens com stashed=true **+** Σ quantidade de recursos com stashed=true.
- `getMaxInventorySlots` = `inventoryExpanded || isVip() ? 50 : 30`.
- **STASH_MAX = 100.**

## Comportamento
- **Adicionar recurso (coleta/drop):** `addResource` passa a **respeitar o limite da bag** — adiciona só
  o que cabe; o excedente é **perdido** (reportado na coleta). Antes era ilimitado.
- **Consumo** (cozinha/forja/venda) lê o recurso da **bag** (stashed=false).
- **Stash deposit (item ou recurso):** valida espaço no stash (≤100), cobra 50 bronze, marca stashed=true
  (item) ou move a quantidade pra linha stash (recurso).
- **Stash withdraw:** valida espaço na bag, cobra 50 bronze, volta pra bag.
- Sem bronze pra taxa → operação rejeitada (mensagem clara).

## Endpoints (novos)
- `GET  /api/stash` — lista itens + recursos no stash + uso (x/100) + taxa.
- `POST /api/stash/deposit/item/{id}` · `/withdraw/item/{id}`
- `POST /api/stash/deposit/resource/{type}` (body: quantity) · `/withdraw/resource/{type}`
- (bag já é exposta pelo `/api/inventory` + `/api/gathering/resources`; o front unifica a exibição.)

## Frontend
- **Bag unificada:** a aba de inventário mostra itens E recursos juntos, com contador `x/30` (ou 50).
- **Aba Stash:** lista o que está guardado (100 slots) + botões depositar/retirar (mostra a taxa de 50 bronze).
- Coleta avisa quando perdeu recurso por bag cheia.

## Etapas
1. **Bag 30/50** (`getMaxInventorySlots`) — rápido.
2. **`stashed` + contagem unificada:** coluna nos 2 modelos + `bagSize` conta itens+recursos +
   `addResource` clampa ao espaço. SchemaMigrator. (núcleo)
3. **StashService + controller:** deposit/withdraw item/recurso, taxa 50 bronze, cap 100.
4. **Frontend:** bag unificada + aba stash.
5. **Testes + docs** (FEATURES/GDD).

## Riscos
- `resource_inventory` muda a chave única → atualizar repo (queries com `stashed`) e callers
  (addResource/removeResource/getResources, cozinha/forja/venda).
- Coleta agora pode encher a bag e perder recurso → stash é a válvula. Mensageria clara.
- Soft-wipe cobre dados legados (cada conta começa limpa).

*Decisões travadas 2026-06-04. Próximo: implementar etapa a etapa.*
