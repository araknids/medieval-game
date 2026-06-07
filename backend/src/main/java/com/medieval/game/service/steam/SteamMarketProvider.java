package com.medieval.game.service.steam;

import java.util.Map;

/**
 * [MERCADO_STEAM] Seam pro Steam Inventory Service. Hoje só o {@link StubSteamMarketProvider}
 * (não chama a Steam). A implementação real (publisher/asset-server key + api.steampowered.com,
 * IInventoryService/ISteamInventory) pluga aqui quando o jogo tiver appid + cliente Godot — sem
 * mexer no resto do código. Ver docs/PLANO_MERCADO_STEAM.md.
 */
public interface SteamMarketProvider {

    /** true quando a integração real está ligada (app.steam.enabled + appid + publisher key). */
    boolean isEnabled();

    /** Resultado de uma concessão de item ao inventário Steam do jogador. */
    record GrantResult(boolean success, String steamItemInstanceId, String message) {
        public static GrantResult ok(String instanceId) { return new GrantResult(true, instanceId, "ok"); }
        public static GrantResult fail(String message)  { return new GrantResult(false, null, message); }
    }

    /**
     * Concede (server-side) um itemdef ao inventário Steam do jogador — é o "linkar o item na Steam"
     * do Mercador Azul. No real: ISteamInventory/IInventoryService AddItem com a publisher key.
     *
     * @param steamId   SteamID64 do jogador linkado (ver {@code Player.steamId})
     * @param itemDefId id da definição de item (itemdef) na Steam
     * @param props     propriedades dinâmicas (stats/raridade) a anexar à instância concedida
     */
    GrantResult grantItem(String steamId, String itemDefId, Map<String, String> props);
}
