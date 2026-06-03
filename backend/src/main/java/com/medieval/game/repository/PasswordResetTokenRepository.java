package com.medieval.game.repository;

import com.medieval.game.model.PasswordResetToken;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);

    // Tokens ainda válidos de um jogador — usado para invalidar os anteriores. [AUDITORIA B5]
    List<PasswordResetToken> findByPlayerAndUsedFalse(Player player);
}
