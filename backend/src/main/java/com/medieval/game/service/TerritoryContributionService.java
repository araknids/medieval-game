package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryContribution;
import com.medieval.game.repository.TerritoryContributionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [LEADERBOARDS] Registra "incursões" (ajuda do jogador a um território). Find-or-create + increment.
 * Chamado pelos serviços de gameplay quando o jogador completa uma atividade ligada a um reino
 * (hoje: quest concluída no reino). Idempotente por (player, kingdom) via a unique da entidade.
 */
@Service
@RequiredArgsConstructor
public class TerritoryContributionService {

    private final TerritoryContributionRepository repo;

    /** +1 incursão do jogador no território. Cria a linha na primeira vez. */
    public void recordIncursion(Player player, Kingdom kingdom) {
        if (player == null || kingdom == null) return;
        TerritoryContribution tc = repo.findByPlayerAndKingdom(player, kingdom)
                .orElseGet(() -> new TerritoryContribution(player, kingdom));
        tc.setIncursions(tc.getIncursions() + 1);
        repo.save(tc);
    }
}
