package com.medieval.game.service;

import com.medieval.game.enums.MatchStatus;
import com.medieval.game.model.ArenaMatch;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.ArenaMatchRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ArenaService {

    private final ArenaMatchRepository matchRepository;
    private final PlayerRepository     playerRepository;
    private final WarriorRepository    warriorRepository;
    private final InventoryService     inventoryService;
    private final PlayerService        playerService;
    private final BattleSimulator      battleSimulator;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    private static final String[] NPC_NAMES = {
        "Bandido da Estrada", "Mercenário Sombrio", "Guarda Corrupto",
        "Desertor do Rei", "Caçador de Recompensas"
    };

    public Optional<ArenaMatch> getActiveFight(Player challenger) {
        return matchRepository.findByChallengerAndStatus(challenger, MatchStatus.FIGHTING);
    }

    @Transactional
    public ArenaMatch startFight(Player challenger) {
        if (matchRepository.findByChallengerAndStatus(challenger, MatchStatus.FIGHTING).isPresent()) {
            throw new IllegalStateException("Você já está em uma batalha");
        }

        Warrior cWarrior = warriorRepository.findByPlayer(challenger)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));

        if (cWarrior.isOnMission()) {
            throw new IllegalStateException("Seu guerreiro está em missão");
        }

        if (!instantComplete) {
            playerService.consumeStamina(challenger, 25);
        }

        // Calcula stats totais do desafiante (base + atributos + itens equipados)
        int[] cStats = totalStats(challenger, cWarrior);

        // Escolhe oponente: outro jogador real ou NPC
        Player opponent = findOpponent(challenger);
        String opponentName;
        int[] oStats;

        if (opponent != null) {
            Warrior oWarrior = warriorRepository.findByPlayer(opponent).orElse(null);
            opponentName = oWarrior != null ? oWarrior.getName() : opponent.getUsername();
            oStats = oWarrior != null ? totalStats(opponent, oWarrior) : npcStats();
        } else {
            opponentName = NPC_NAMES[new Random().nextInt(NPC_NAMES.length)];
            oStats = npcStats();
        }

        // Simula a batalha
        String challengerName = cWarrior.getName();

        List<String> log = battleSimulator.simulate(
                challengerName, cStats[0], cStats[1], cStats[2], cStats[3],
                opponentName, oStats[0], oStats[1], oStats[2], oStats[3]
        );

        // A tag WINNER: é a última linha — parseia para verificar vencedor
        String winnerTag = log.get(log.size() - 1);
        boolean challengerWon = winnerTag.contains("WINNER:" + challengerName);
        log.remove(log.size() - 1); // remove a tag interna
        long goldReward  = challengerWon ? 200 : 50; // bronze
        int  rankChange  = challengerWon ? (opponent != null ? 25 : 15) : (opponent != null ? -15 : -5);

        ArenaMatch match = new ArenaMatch();
        match.setChallenger(challenger);
        match.setOpponent(opponent);
        match.setOpponentName(opponentName);
        match.setBattleLog(String.join("\n", log));
        match.setChallengerWon(challengerWon);
        match.setGoldReward(goldReward);
        match.setRankChange(rankChange);
        match.setStartedAt(LocalDateTime.now());
        match.setFinishesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusSeconds(60));
        match.setStatus(MatchStatus.FIGHTING);

        cWarrior.setOnMission(true);
        warriorRepository.save(cWarrior);

        return matchRepository.save(match);
    }

    @Transactional
    public ArenaMatch collectResult(Player challenger, Long matchId) {
        ArenaMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Batalha não encontrada"));

        if (!match.getChallenger().getId().equals(challenger.getId())) {
            throw new IllegalStateException("Esta batalha não é sua");
        }
        if (match.getStatus() == MatchStatus.COLLECTED) {
            throw new IllegalStateException("Resultado já coletado");
        }
        if (LocalDateTime.now().isBefore(match.getFinishesAt())) {
            long secsLeft = java.time.Duration.between(LocalDateTime.now(), match.getFinishesAt()).getSeconds();
            throw new IllegalStateException("Batalha ainda em andamento. Faltam " + secsLeft + "s");
        }

        playerService.addGold(challenger, match.getGoldReward());

        challenger.setRankPoints(Math.max(0, challenger.getRankPoints() + match.getRankChange()));
        if (match.isChallengerWon()) challenger.setArenaWins(challenger.getArenaWins() + 1);
        else                         challenger.setArenaLosses(challenger.getArenaLosses() + 1);
        playerRepository.save(challenger);

        // Aplica rank no oponente real (se houver)
        if (match.getOpponent() != null) {
            Player opp = match.getOpponent();
            int oppChange = match.isChallengerWon() ? -15 : 25;
            opp.setRankPoints(Math.max(0, opp.getRankPoints() + oppChange));
            if (!match.isChallengerWon()) opp.setArenaWins(opp.getArenaWins() + 1);
            else                          opp.setArenaLosses(opp.getArenaLosses() + 1);
            playerRepository.save(opp);
        }

        // Libera guerreiro
        warriorRepository.findByPlayer(challenger).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        match.setStatus(MatchStatus.COLLECTED);
        return matchRepository.save(match);
    }

    public List<Player> getRanking() {
        return matchRepository.findTopRanked();
    }

    // ── Privados ──

    private Player findOpponent(Player challenger) {
        return playerRepository.findAll().stream()
                .filter(p -> !p.getId().equals(challenger.getId()))
                .findAny()
                .orElse(null);
    }

    private int[] totalStats(Player player, Warrior warrior) {
        List<InventoryItem> equipped = inventoryService.getInventory(player)
                .stream().filter(InventoryItem::isEquipped).toList();
        int bonusAtk = equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
        int bonusDef = equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
        int bonusHp  = equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();
        // Inclui atributos (Força, Constituição) e evasão (Destreza)
        return new int[]{
            warrior.getTotalBaseAttack()  + bonusAtk,
            warrior.getTotalBaseDefense() + bonusDef,
            warrior.getTotalBaseHealth()  + bonusHp,
            warrior.getEvasionChance()  // índice [3]: % evasão
        };
    }

    private int[] npcStats() {
        Random r = new Random();
        return new int[]{ 12 + r.nextInt(8), 8 + r.nextInt(6), 90 + r.nextInt(40), 10 };
    }

}
