package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final PasswordEncoder  passwordEncoder;

    @Transactional
    public Player register(String username, String email, String rawPassword) {
        log.info("[PlayerService] action=register username={}", username);
        if (playerRepository.existsByUsername(username)) {
            log.warn("[PlayerService] action=register REJECTED: username already exists: {}", username);
            throw new IllegalArgumentException("Username already exists: " + username);
        }
        if (playerRepository.existsByEmail(email)) {
            log.warn("[PlayerService] action=register REJECTED: email already registered: {}", email);
            throw new IllegalArgumentException("Email already registered: " + email);
        }
        Player player = new Player();
        player.setUsername(username);
        player.setEmail(email);
        player.setPasswordHash(passwordEncoder.encode(rawPassword));
        Player saved = playerRepository.save(player);
        log.info("[PlayerService] action=register OK playerId={} username={}", saved.getId(), username);
        return saved;
    }

    public Player findByUsername(String username) {
        return playerRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + username));
    }

    public Player findById(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + id));
    }

    public boolean checkPassword(Player player, String rawPassword) {
        return passwordEncoder.matches(rawPassword, player.getPasswordHash());
    }

    // ── Sistema de 3 moedas (100 bronze = 1 prata, 100 prata = 1 ouro) ──

    /** Adiciona bronze e auto-converte para prata/ouro se necessário */
    @Transactional
    public void addBronze(Player player, long amount) {
        long totalBronze = player.getBronze() + amount;
        long silverGained = totalBronze / 100;
        player.setBronze(totalBronze % 100);
        if (silverGained > 0) addSilverInternal(player, silverGained);
        playerRepository.save(player);
    }

    /** Adiciona prata e auto-converte para ouro se necessário */
    @Transactional
    public void addSilver(Player player, long amount) {
        addSilverInternal(player, amount);
        playerRepository.save(player);
    }

    private void addSilverInternal(Player player, long amount) {
        long totalSilver = player.getSilver() + amount;
        player.setGold(player.getGold() + totalSilver / 100);
        player.setSilver(totalSilver % 100);
    }

    /** Gasta um valor em bronze (decompõe automaticamente prata/ouro se necessário) */
    @Transactional
    public void spendBronze(Player player, long bronzeAmount) {
        // SEGURANÇA: valor negativo passaria na guarda "saldo < negativo" e CREDITARIA dinheiro. [AUDITORIA C2]
        if (bronzeAmount < 0) throw new IllegalArgumentException("amount must be >= 0");
        if (player.totalBronze() < bronzeAmount) {
            log.warn("[PlayerService] player={} REJECTED: insufficient funds (have={} need={})", player.getId(), player.totalBronze(), bronzeAmount);
            throw new IllegalStateException("Insufficient funds");
        }
        long remaining = player.totalBronze() - bronzeAmount;
        player.setGold(remaining / 10_000L);
        remaining %= 10_000L;
        player.setSilver(remaining / 100L);
        player.setBronze(remaining % 100L);
        playerRepository.save(player);
    }

    // Mantidos por compatibilidade (usam bronze internamente)
    @Transactional
    public void addGold(Player player, long bronzeAmount) {
        addBronze(player, bronzeAmount);
    }

    @Transactional
    public void spendGold(Player player, long bronzeAmount) {
        spendBronze(player, bronzeAmount);
    }

    @Transactional
    public void consumeStamina(Player player, int cost) {
        int current = player.getCalculatedStamina();
        if (current < cost) {
            long minutesLeft = player.getMinutesToFullStamina();
            throw new IllegalStateException(
                "Insufficient stamina (" + current + "/" + cost + "). " +
                "Regenera totalmente em " + minutesLeft + " min."
            );
        }
        player.setCurrentStamina(current - cost);
        player.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);
    }
}
