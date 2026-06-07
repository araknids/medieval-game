package com.medieval.game.service.steam;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * [MERCADO_STEAM] Implementação padrão SEM Steam: loga e simula. Inerte enquanto
 * {@code app.steam.enabled=false}. Quando a integração real existir, troca-se por um
 * {@code WebApiSteamMarketProvider} (mesma interface, publisher key + Web API da Steam).
 */
@Slf4j
@Service
public class StubSteamMarketProvider implements SteamMarketProvider {

    @Value("${app.steam.enabled:false}")
    private boolean enabled;

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public GrantResult grantItem(String steamId, String itemDefId, Map<String, String> props) {
        if (!enabled) {
            log.info("[SteamStub] grantItem ignorado (Steam desligado) steamId={} itemDef={}", steamId, itemDefId);
            return GrantResult.fail("Steam market disabled");
        }
        // Stub: simula sucesso com um instanceId fake. A impl real chamaria a Web API da Steam.
        String fake = "stub-" + itemDefId + "-" + (steamId != null ? Integer.toHexString(steamId.hashCode()) : "0");
        log.info("[SteamStub] grantItem SIMULADO steamId={} itemDef={} props={} -> {}", steamId, itemDefId,
                props != null ? props.size() : 0, fake);
        return GrantResult.ok(fake);
    }
}
