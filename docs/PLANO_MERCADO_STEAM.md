# Plano — Mercado Steam / "Mercador Azul" (vender item na Steam) [MERCADO_STEAM]

> Status: **planejamento + scaffold de fundação**. A integração real só liga quando o jogo estiver
> **publicado na Steam (appid)** + cliente **Godot** existir. Fonte: docs oficiais do Steam Inventory
> Service (coladas pelo dono do jogo). O que dá pra deixar pronto AGORA é a fundação (seam + link de
> conta + flag); o resto está especificado aqui pra construir quando houver appid.

## Como funciona de verdade (resumo honesto)

Item de jogo só "vira valor" na Steam por **dois caminhos**, e os dois passam pelo **Steam Inventory Service**:

| | A) Community Market (revenda P2P) | B) Microtransações (dev vende) |
|---|---|---|
| Quem vende | **jogador → jogador** | **dev → jogador** |
| O que o vendedor recebe | **Steam Wallet** (NÃO saca como dinheiro) | dev recebe receita (Steam fica com ~30%) |
| Quem roda a loja | a **própria Steam** (UI do Market) | itemdef com preço (`StartPurchase` / `buyitem` URL) |
| Casa com o "Mercador Azul" | **SIM** (deposita → exporta pro inventário Steam → vende no Market) | é outro produto (loja de cosméticos) |

**O ponto que muda tudo:** o que o jogador ganha vendendo no Community Market é **saldo da carteira Steam**
(usa pra comprar jogos/itens), **não dinheiro sacável**. A Steam ainda fica com taxa (~5% Steam + até 10%
que o dev define = ~15%). Então o "Mercador Azul" é **escambo premium dentro do ecossistema Steam**, não
um caixa eletrônico. Vale alinhar isso na lore/expectativa.

### Fluxo oficial do Inventory Service (da doc da Steam)
1. **Criar ItemDefs** (definições de item) — descrevem todos os itens possíveis; sobe na página
   *Steamworks → Inventory Service*. Necessário pra Steam exibir o inventário.
2. **Ativar o serviço de inventário** na página dos itemdefs. Com visibilidade **Privada** (Steam Economy
   Settings), **só contas do grupo de parceiros** acessam → é assim que se **testa antes do lançamento**.
3. **Asset server key** — gerar uma **chave Web API de distribuidora (publisher key)** e colar em
   "Asset server key" (Steam Economy Settings). É a chave **server-side** pra conceder itens.
4. Cliente baixa o inventário com `ISteamInventory::GetAllItems` (SDK nativo, chamado periodicamente).
5. Conceder itens de teste: `ISteamInventory::GenerateItems` (dev).
6. (Opcional) drop por tempo de jogo: `ISteamInventory::TriggerItemDrop`.
7. (Opcional) vender (microtxn): preço no itemdef → `RequestPrices`/`GetItemPrice`/`StartPurchase` +
   callback `SteamInventoryResultReady_t`, ou a URL `store.steampowered.com/buyitem/:appid/:itemdefid/`.

### O que cada camada faz no NOSSO caso
- **Backend Java (server-to-server):** PODE conceder/consultar itens via **Steam Web API** (`IInventoryService`
  / `ISteamInventory` Web methods) usando a **publisher key** — **sem** o SDK nativo. É aqui que mora o
  "linkar o item na Steam" (conceder o itemdef ao inventário do jogador).
- **Cliente Godot (SDK nativo):** precisa pro lado-cliente — `GetAllItems` (ler inventário), callbacks de
  compra, e o **auth ticket** (`GetAuthSessionTicket`) que o backend valida (`ISteamUserAuth/AuthenticateUserTicket`)
  pra **linkar a conta Steam ↔ conta do jogo**. Via **GodotSteam** (GDExtension). O web app **não** faz isso.
- **Steam:** roda o Community Market (revenda) e a carteira. A gente não roda marketplace nenhum.

### Portões de elegibilidade (o que trava o solo dev)
- Precisa do **appid** (jogo na Steam) + Inventory Service ativado. Dá pra desenvolver/testar em
  visibilidade **Privada** com contas de parceiro **antes** do lançamento público.
- Itemdefs precisam ser **`marketable`** (e `tradable`) pro Community Market funcionar.
- Carteira Steam (não-sacável), trade holds / Steam Guard, limites regionais, reembolso/fraude.

## A lore — Mercador Azul (🔵)
Um NPC **azul** (cor da Steam) na cidade. O jogador **entrega um item a ele** ("consigna"). O Mercador Azul
"**leva pro mercado de além-mundo**" (= exporta pro inventário Steam). Lá fora (no Community Market da Steam)
outro jogador compra; o ouro-de-além-mundo (Steam Wallet) chega pra quem vendeu. In-game: o item **sai da bag**
e fica "**em consignação**" até ser vendido (vira saldo Steam) ou **devolvido**.

Estados: `EM_PODER_DO_MERCADOR` (escrow) → `LINKADO` (no inventário Steam) → `VENDIDO` / `DEVOLVIDO`.

## O que dá pra deixar PRONTO agora (fundação, seguro, flag-off)
Construído neste scaffold (inerte enquanto `app.steam.enabled=false`):
1. **`Player.steamId`** (link conta-Steam ↔ conta-jogo) + migração. Foundational, vai precisar sempre.
2. **Seam `SteamMarketProvider`** (interface) + **`StubSteamMarketProvider`** (loga/simula). A implementação
   real (`WebApiSteamMarketProvider` chamando `api.steampowered.com`) pluga aqui depois, sem mexer no resto.
3. **Flags de config** (`app.steam.*`): `enabled=false`, `appid`, `publisher-key`, `market-fee-pct` —
   placeholders, lidos do env em prod.

## O que fica ESPECIFICADO p/ construir quando houver appid (não codar antes do design ok)
- **Modelo de consignação**: `Consignment` (player, item, status, `steamItemInstanceId`, timestamps) +
  `ConsignmentStatus` enum. Item ganha flag `consigned` (espelha `listed` do Leilão: sai da bag, não
  vende/equipa/stasha/guarda/lista).
- **`BlueMerchantService`**: `consign(item)` → escrow + `provider.grantToSteam(steamId, itemDef)` →
  `LINKADO`; `list(player)`; `cancel` → devolve item (`DEVOLVIDO`). Webhook/poll de venda → `VENDIDO`.
- **`BlueMerchantController`** (`/api/blue-merchant`): listar consignáveis, consignar, cancelar, status.
- **Mapa item→itemdef** (`SteamItemMapping`): in-game item (tipo/raridade) → `itemdef id` da Steam.
- **Link de conta**: endpoint que recebe o **auth ticket** do cliente Godot, valida na Web API, grava `steamId`.
- **UI**: aba/loja do Mercador Azul (visível só com `steam.enabled`); badge "via Steam".
- **Provider real**: `WebApiSteamMarketProvider` (publisher key, `IInventoryService.AddItem`/`GetInventory`).

## Plano em fases (solo dev)
- **F0 (agora, sem Steam):** fundação deste scaffold (steamId + seam + flags) + este doc. ✅
- **F1 (quando o jogo entrar na Steam, visibilidade Privada):** criar itemdefs, ativar Inventory Service,
  asset server key; implementar link de conta (Godot auth ticket) + `WebApiSteamMarketProvider`; testar
  grant/inventário com conta de parceiro.
- **F2:** modelo de consignação + Blue Merchant service/controller/UI; marcar itemdefs `marketable`/`tradable`.
- **F3 (pós-lançamento público):** ligar `app.steam.enabled=true`, abrir o Mercador Azul, monitorar
  fraude/taxas; decidir taxa do dev no Market.

## Decisões em aberto (pro dono do jogo)
- Quais itens podem ir pro Mercador Azul? (sugestão: só **gear de raridade alta**, p/ não inundar o Market.)
- Taxa do dev no Market (0–10%) e se cobra um custo in-game pra consignar (sink).
- Microtxn (loja de cosméticos) é um produto à parte — fica fora deste plano por ora.
