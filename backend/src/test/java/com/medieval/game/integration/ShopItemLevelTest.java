package com.medieval.game.integration;

import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Itens V3 — loja: Comum/Incomum + Raro 15% e Épico 5% (sem Lendário), nível ≈ nível do jogador ±5, sem repetir item.
@DisplayName("Itens V3 | loja por nível")
class ShopItemLevelTest extends BaseIntegrationTest {

    @Autowired ShopService      shop;
    @Autowired PlayerRepository playerRepo;
    @Autowired WarriorRepository warriorRepo;

    Player p;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("shoplvl");
        registerAndGetToken(user);
        p = playerRepo.findAll().stream().filter(x -> x.getUsername().equals(user)).findFirst().orElseThrow();
        Warrior w = warriorRepo.findByPlayer(p).orElseThrow();
        w.setLevel(40); warriorRepo.save(w);
    }

    @Test
    @DisplayName("Raridade da loja: Comum..Épico (1–4), sem Lendário (5)")
    void rarityWithinShopRange() {
        var items = shop.getItems(p);
        assertThat(items).isNotEmpty();
        assertThat(items).allMatch(i -> i.rarity() >= 1 && i.rarity() <= 4);
    }

    @Test
    @DisplayName("[LOJA_SEM_DUP] sem nomes repetidos entre os itens não-arma (pool >> nº de slots)")
    void noDuplicateNonWeapons() {
        var names = shop.getItems(p).stream()
                .filter(i -> i.type() != com.medieval.game.enums.ItemType.WEAPON)
                .map(i -> i.name())
                .toList();
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Nível dos itens fica em ~nível do jogador ±5")
    void levelNearPlayer() {
        var items = shop.getItems(p); // guerreiro nível 40
        assertThat(items).allMatch(i -> i.itemLevel() >= 35 && i.itemLevel() <= 45);
    }
}
