package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final PasswordEncoder  passwordEncoder;

    @Transactional
    public Player register(String username, String email, String rawPassword) {
        if (playerRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Nome de usuário já existe: " + username);
        }
        if (playerRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("E-mail já cadastrado: " + email);
        }
        Player player = new Player();
        player.setUsername(username);
        player.setEmail(email);
        player.setPasswordHash(passwordEncoder.encode(rawPassword));
        return playerRepository.save(player);
    }

    public Player findByUsername(String username) {
        return playerRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Jogador não encontrado: " + username));
    }

    public Player findById(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Jogador não encontrado: " + id));
    }

    public boolean checkPassword(Player player, String rawPassword) {
        return passwordEncoder.matches(rawPassword, player.getPasswordHash());
    }

    @Transactional
    public void addGold(Player player, long amount) {
        player.setGold(player.getGold() + amount);
        playerRepository.save(player);
    }

    @Transactional
    public void spendGold(Player player, long amount) {
        if (player.getGold() < amount) {
            throw new IllegalStateException("Ouro insuficiente");
        }
        player.setGold(player.getGold() - amount);
        playerRepository.save(player);
    }

    // Consome estamina — salva o valor atual calculado menos o custo
    @Transactional
    public void consumeStamina(Player player, int cost) {
        int current = player.getCalculatedStamina();
        if (current < cost) {
            long minutesLeft = player.getMinutesToFullStamina();
            throw new IllegalStateException(
                "Estamina insuficiente (" + current + "/" + cost + "). " +
                "Regenera totalmente em " + minutesLeft + " min."
            );
        }
        player.setCurrentStamina(current - cost);
        player.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);
    }
}
