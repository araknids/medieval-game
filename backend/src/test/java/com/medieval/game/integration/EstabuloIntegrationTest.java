package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.PlayerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Estábulo — montarias que reduzem estamina (ver docs/PLANO_ESTABULO.md).
@DisplayName("Estábulo | Montarias — compra, equip, posse, desconto de estamina")
class EstabuloIntegrationTest extends BaseIntegrationTest {

    @Autowired PlayerRepository playerRepository;
    @Autowired PlayerService    playerService;

    String token;

    @BeforeEach
    void setup() throws Exception {
        token = registerAndGetToken(uniqueUser("stable"));
    }

    private Player player() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getUsername().startsWith("stable"))
                .reduce((a, b) -> b.getId() > a.getId() ? b : a).orElseThrow();
    }

    /** Dá `gold` ouro ao jogador (1 gold = 10.000 bronze). */
    private void giveGold(long gold) {
        Player p = player();
        p.addBronzeAmount(gold * 10_000L);
        playerRepository.save(p);
    }

    // ── Comprar com gold, equipar e reduzir estamina ──
    @Test
    @DisplayName("Comprar cavalo de gold, equipar → discountStamina reflete a redução")
    void buyEquip_reducesStamina() throws Exception {
        giveGold(20); // 20g (PACK_HORSE custa 10g)

        mockMvc.perform(post("/api/stable/buy/PACK_HORSE").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // vitrine: PACK_HORSE agora é dono, ainda não equipado
        mockMvc.perform(get("/api/stable").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mounts[?(@.id=='PACK_HORSE')].owned", contains(true)))
                .andExpect(jsonPath("$.mounts[?(@.id=='PACK_HORSE')].equipped", contains(false)));

        mockMvc.perform(post("/api/stable/equip/PACK_HORSE").header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        // PACK_HORSE = −3% → custo-base 100 vira 97
        Player p = player();
        assertThat(playerService.staminaReductionPct(p)).isEqualTo(3);
        assertThat(playerService.discountStamina(p, 100)).isEqualTo(97);
    }

    // ── Não pode comprar duas vezes a mesma montaria ──
    @Test
    @DisplayName("Comprar a mesma montaria 2x → 400")
    void buyTwice_returns400() throws Exception {
        giveGold(20);
        mockMvc.perform(post("/api/stable/buy/PACK_HORSE").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/stable/buy/PACK_HORSE").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Sem ouro suficiente → 400 ──
    @Test
    @DisplayName("Comprar sem ouro suficiente → 400")
    void buyWithoutGold_returns400() throws Exception {
        // jogador novo tem só 50 prata (0,5 gold); LEGENDARY_STEED custa 300g
        mockMvc.perform(post("/api/stable/buy/LEGENDARY_STEED").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Montaria VIP exige VIP ativo ──
    @Test
    @DisplayName("Comprar montaria VIP sem VIP → 400")
    void buyVipMount_withoutVip_returns400() throws Exception {
        mockMvc.perform(post("/api/stable/buy/CELESTIAL_MOUNT").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // ── Desconto: troca de montaria muda o pct; desequipar zera ──
    @Test
    @DisplayName("Trocar de montaria muda a redução; desequipar zera")
    void switchAndUnequip_changesReduction() throws Exception {
        giveGold(400); // cobre PACK_HORSE (10g) + LEGENDARY_STEED (300g)
        mockMvc.perform(post("/api/stable/buy/PACK_HORSE").header("Authorization", bearer(token))).andExpect(status().isOk());
        mockMvc.perform(post("/api/stable/buy/LEGENDARY_STEED").header("Authorization", bearer(token))).andExpect(status().isOk());

        mockMvc.perform(post("/api/stable/equip/LEGENDARY_STEED").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(playerService.discountStamina(player(), 100)).isEqualTo(85); // −15%

        mockMvc.perform(post("/api/stable/equip/PACK_HORSE").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(playerService.discountStamina(player(), 100)).isEqualTo(97); // −3%

        mockMvc.perform(post("/api/stable/unequip").header("Authorization", bearer(token))).andExpect(status().isOk());
        assertThat(playerService.staminaReductionPct(player())).isZero();
        assertThat(playerService.discountStamina(player(), 100)).isEqualTo(100); // sem montaria
    }
}
