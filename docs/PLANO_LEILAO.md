# PLANO — Casa de Leilão (Auction House) [LEILAO]

> Status: **IMPLEMENTADO** (doc escrito retroativamente na auditoria 2026-06-06 — a feature já existia sem PLANO).
> Mercado **entre jogadores** por preço fixo (buyout), dentro do mesmo servidor/realm.

## Conceito

O jogador **posta** um item da bag por um preço fixo; outro jogador **compra** (buyout) na hora.
A casa cobra taxas (sink de economia). O item fica "em custódia" (flag `listed`, sai da bag) e só muda
de dono na venda; cancelar/expirar devolve. Tudo no **mesmo servidor** (não cruza realms — [SERVIDORES]).

## Regras (placeholders p/ tuning)

| Tema | Valor | Onde |
|------|-------|------|
| Taxa adiantada ao postar (queima) | **5%** do preço | `AuctionService.FEE_PCT` |
| Corte na venda (queima) | **15%** → vendedor recebe **85%** | `AuctionService.SALE_CUT_PCT` |
| Líquido do vendedor (contando os 5%) | ≈ **80%** do preço | — |
| Janela da listagem | **2 dias** | `AuctionService.MAX_DAYS` |
| Máx. listagens ativas por jogador | **10** | `AuctionService.MAX_ACTIVE` |
| Preço mínimo | 1 bronze | `list()` |

## Fluxo

- **Postar** (`POST /api/auction/list` `{itemId, price}`): valida posse + item não-equipado/stashed/guarded/
  pvpLocked/já-listado + limite de 10 ativas; cobra 5% adiantado (`spendBronze`, queima); marca
  `InventoryItem.listed=true` (sai da bag); cria `AuctionListing` (ACTIVE, `endsAt = now+2d`).
- **Browse** (`GET /api/auction`) e **Minhas** (`GET /api/auction/mine`): lista as ACTIVE (com stats/afixos/
  joias do item + `sellerPayout` e `secondsLeft`).
- **Comprar** (`POST /api/auction/buy/{id}`): valida ACTIVE + não-expirada + não é a própria + bag livre +
  saldo; debita o comprador, paga **85%** ao vendedor (15% queima), transfere o item, marca SOLD.
- **Cancelar** (`POST /api/auction/cancel/{id}`): devolve o item ao vendedor, marca CANCELLED.
- **Expirar** (`AuctionScheduler`, de hora em hora + no boot; lazy-on-read no `buy`): devolve o item,
  marca EXPIRED. O 5% adiantado **não** volta (é o custo de tentar vender).

## Modelo

- `AuctionListing` (tabela `auction_listings`): `item` (FK `inventory_items`), `seller` (FK `players`),
  `price`, `listedAt`, `endsAt`, `status` (ACTIVE/SOLD/EXPIRED/CANCELLED), `buyerId`.
- `InventoryItem.listed` (boolean): item em leilão não vende/stasha/guarda/equipa.
- **Soft-wipe**: apaga `auction_listings` antes de `inventory_items` (FK). [feito no `MaintenanceService`]

## Arquivos

`AuctionController` (`/api/auction`), `AuctionService`, `AuctionScheduler`, `model/AuctionListing`,
`repository/AuctionListingRepository`; frontend `panel-auction` / `loadAuctionHouse()` (`app.js`).

## Fora de escopo (futuro)

- Leilão por **lance** (em vez de buyout).
- Busca/filtro por tipo/raridade/afixo; ordenação por preço.
- O Mercador influenciar taxas do leilão (citado em `docs/PLANO_CLASSE_MERCADOR.md` como futuro).
- Integração com Steam Marketplace.
- Paginação no browse (hoje retorna todas as ACTIVE — ver backlog de escalabilidade A5).
