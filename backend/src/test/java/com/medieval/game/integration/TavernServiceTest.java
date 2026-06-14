package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TavernMessageRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.TavernService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

// [TAVERNA] Beber → buff stackável + garrafas + aviso de marco; chat + feed + cooldown.
@DisplayName("Taverna | beber/buff + chat/avisos")
class TavernServiceTest extends BaseIntegrationTest {

    @Autowired TavernService           tavernService;
    @Autowired PlayerRepository        playerRepository;
    @Autowired WarriorRepository       warriorRepository;
    @Autowired TavernMessageRepository messageRepository;

    @BeforeEach
    void setup() throws Exception { registerAndGetToken(uniqueUser("tav")); }

    private Player fresh(String prefix) throws Exception {
        registerAndGetToken(uniqueUser(prefix));
        return playerRepository.findAll().stream().filter(p -> p.getUsername().startsWith(prefix))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }
    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }
    private Warrior warrior(Player p) { return warriorRepository.findByPlayer(reload(p)).orElseThrow(); }

    @Test
    @DisplayName("Beber com sucesso: -1 bronze, +1 stack, +1 garrafa, buff ativo")
    void drink_success() throws Exception {
        Player p = fresh("tavok");
        long bronzeBefore = p.totalBronze();
        Map<String, Object> st = tavernService.drink(p, true);
        Player after = reload(p);
        assertThat(after.totalBronze()).isEqualTo(bronzeBefore - 1);
        assertThat(warrior(p).activeTavernStacks()).isEqualTo(1);
        assertThat(after.getBottlesDrunk()).isEqualTo(1);
        assertThat(((Number) st.get("stacks")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("Beber 'errando' o minigame ainda concede stack — servidor ignora o success do cliente [SEGURANCA]")
    void drink_fail() throws Exception {
        Player p = fresh("tavfail");
        long bronzeBefore = p.totalBronze();
        tavernService.drink(p, false);   // success=false é IGNORADO: o gate é o bronze, não o minigame
        Player after = reload(p);
        assertThat(after.totalBronze()).isEqualTo(bronzeBefore - 1);
        assertThat(warrior(p).activeTavernStacks()).isEqualTo(1); // stack concedido mesmo "errando"
        assertThat(after.getBottlesDrunk()).isEqualTo(1);
    }

    @Test
    @DisplayName("Goles consecutivos somam stacks")
    void drink_stacks() throws Exception {
        Player p = fresh("tavstack");
        tavernService.drink(p, true);
        tavernService.drink(reload(p), true);
        tavernService.drink(reload(p), true);
        assertThat(warrior(p).activeTavernStacks()).isEqualTo(3);
    }

    @Test
    @DisplayName("Marco de 10 garrafas dispara aviso global no feed")
    void bottleMilestone_announces() throws Exception {
        Player p = fresh("tavmile");
        p.setBottlesDrunk(9); playerRepository.save(p);
        tavernService.drink(reload(p), true); // 10ª garrafa
        boolean hasAnnounce = tavernService.feed(0L).stream()
                .anyMatch(m -> "ANNOUNCEMENT".equals(m.getType()) && m.getText().contains("10"));
        assertThat(hasAnnounce).isTrue();
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
}
