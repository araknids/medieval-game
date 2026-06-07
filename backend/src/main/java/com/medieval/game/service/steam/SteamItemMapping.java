package com.medieval.game.service.steam;

import com.medieval.game.model.InventoryItem;

/**
 * [MERCADO_STEAM] Mapeia um item do jogo → id de definição de item (itemdef) da Steam.
 * Placeholder determinístico (tipo + raridade) até o catálogo real de itemdefs existir na Steam (F1).
 */
public final class SteamItemMapping {

    private SteamItemMapping() {}

    public static String itemDefFor(InventoryItem item) {
        String type = item.getType() != null ? item.getType().name().toLowerCase() : "item";
        return "med_" + type + "_r" + item.getRarity();
    }
}
