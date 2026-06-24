package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.TavernService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

// [TAVERNA] Chat + feed + cooldown; avisos globais.
@DisplayName("Taverna | chat/avisos")
class TavernServiceTest extends BaseIntegrationTest {

    @Autowired TavernService    tavernService;
    @Autowired PlayerRepository playerRepository;

    @BeforeEach
    void setup() throws Exception { registerAndGetToken(uniqueUser("tav")); }

    private Player fresh(String prefix) throws Exception {
        registerAndGetToken(uniqueUser(prefix));
        return playerRepository.findAll().stream().filter(p -> p.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    @Test
    @DisplayName("Chat: posta e aparece no feed; cooldown rejeita o 2º imediato")
    void chat_postAndCooldown() throws Exception {
        Player p = fresh("tavchat");
        tavernService.postMessage(p, "ola taverna");
        assertThat(tavernService.feed(0L).stream().anyMatch(m -> m.getText().equals("ola taverna"))).isTrue();
        assertThatThrownBy(() -> tavernService.postMessage(p, "spam"))
                .isInstanceOf(com.medieval.game.config.LocalizedException.class);
    }

    @Test
    @DisplayName("Aviso global aparece no feed marcado como ANNOUNCEMENT")
    void announce_showsInFeed() throws Exception {
        fresh("tavann");
        tavernService.announce("Servidor reiniciado");
        boolean hasAnnounce = tavernService.feed(0L).stream()
                .anyMatch(m -> "ANNOUNCEMENT".equals(m.getType()) && m.getText().contains("reiniciado"));
        assertThat(hasAnnounce).isTrue();
    }
}
