package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// TC-001 a TC-004 — PlayerService: Moedas e Stamina
@ExtendWith(MockitoExtension.class)
@DisplayName("TC-001-004 | PlayerService — Moedas e Stamina")
class PlayerServiceTest {

    @Mock PlayerRepository playerRepository;
    @Mock PasswordEncoder  passwordEncoder;
    @InjectMocks PlayerService playerService;

    Player player;

    @BeforeEach
    void setUp() {
        player = new Player();
        player.setBronze(0);
        player.setSilver(0);
        player.setGold(0);
    }

    // ── TC-001: Adicionar bronze converte automaticamente para prata ──
    @Test
    @DisplayName("TC-001 | addBronze(150) → 1 prata 50 bronze")
    void tc001_addBronze_autoConvertsToSilver() {
        when(playerRepository.save(any())).thenReturn(player);

        playerService.addBronze(player, 150);

        assertThat(player.getSilver()).isEqualTo(1);
        assertThat(player.getBronze()).isEqualTo(50);
        assertThat(player.getGold()).isEqualTo(0);
    }

    // ── TC-002: Adicionar prata converte automaticamente para ouro ──
    @Test
    @DisplayName("TC-002 | addBronze(10200) → 1 ouro 2 prata 0 bronze")
    void tc002_addBronze_autoConvertsToGold() {
        when(playerRepository.save(any())).thenReturn(player);

        playerService.addBronze(player, 10200); // 1 ouro 2 prata

        assertThat(player.getGold()).isEqualTo(1);
        assertThat(player.getSilver()).isEqualTo(2);
        assertThat(player.getBronze()).isEqualTo(0);
    }

    // ── TC-003: Gastar bronze decompõe corretamente ouro/prata ──
    @Test
    @DisplayName("TC-003 | spendBronze com saldo misto decompõe corretamente")
    void tc003_spendBronze_decomposesCorrectly() {
        player.setGold(1);    // 10.000 bronze equivalente
        player.setSilver(0);
        player.setBronze(0);
        when(playerRepository.save(any())).thenReturn(player);

        playerService.spendBronze(player, 250); // gasta 2 prata 50 bronze

        // Restante: 10.000 - 250 = 9.750 bronze = 0 ouro, 97 prata, 50 bronze
        assertThat(player.getGold()).isEqualTo(0);
        assertThat(player.getSilver()).isEqualTo(97);
        assertThat(player.getBronze()).isEqualTo(50);
    }

    // ── TC-004: Gastar mais do que o saldo lança exceção ──
    @Test
    @DisplayName("TC-004 | spendBronze com saldo insuficiente → IllegalStateException")
    void tc004_spendBronze_insufficientBalance_throwsException() {
        player.setBronze(50);
        player.setSilver(0);
        player.setGold(0);

        assertThatThrownBy(() -> playerService.spendBronze(player, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient");
    }
}
