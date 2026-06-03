package com.medieval.game.service;

import com.medieval.game.enums.MatchStatus;
import com.medieval.game.model.ArenaMatch;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.ArenaMatchRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class ArenaService {

    private final ArenaMatchRepository matchRepository;
    private final PlayerRepository     playerRepository;
    private final WarriorRepository    warriorRepository;
    private final InventoryService     inventoryService;
    private final PlayerService        playerService;
    private final BattleSimulator      battleSimulator;
    private final VipService           vipService;
    private final WarriorStatsService  statsService;

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
        log.info("[ArenaService] player={} action=startFight", challenger.getId());
        if (matchRepository.findByChallengerAndStatus(challenger, MatchStatus.FIGHTING).isPresent()) {
            log.warn("[ArenaService] player={} REJECTED: already in a battle", challenger.getId());
            throw new IllegalStateException("You are already in a battle");
        }

        Warrior cWarrior = warriorRepository.findByPlayer(challenger)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (cWarrior.isOnMission()) {
            log.warn("[ArenaService] player={} REJECTED: warrior is on a mission", challenger.getId());
            throw new IllegalStateException("Your warrior is on a mission");
        }
        if (cWarrior.isKnockedOut()) {
            log.warn("[ArenaService] player={} REJECTED: warrior is unconscious", challenger.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        // Daily fight limit (5 free / 10 VIP)
        vipService.consumeArenaFight(challenger);

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

        List<String> battleLog = battleSimulator.simulate(
                challengerName, cStats[0], cStats[1], cStats[2], cStats[3], cStats[4], cStats[5],
                opponentName,   oStats[0], oStats[1], oStats[2], oStats[3], oStats[4], oStats[5]
        );

        // Desgaste de equipamento por lutar (1-10 de durabilidade por item)
        inventoryService.wearEquippedItems(challenger);

        // A tag WINNER: é a última linha — parseia para verificar vencedor
        String winnerTag = battleLog.get(battleLog.size() - 1);
        boolean challengerWon = winnerTag.contains("WINNER:" + challengerName);
        battleLog.remove(battleLog.size() - 1); // remove a tag interna
        long goldReward  = challengerWon ? 200 : 50; // bronze
        int  rankChange  = challengerWon ? (opponent != null ? 25 : 15) : (opponent != null ? -15 : -5);

        ArenaMatch match = new ArenaMatch();
        match.setChallenger(challenger);
        match.setOpponent(opponent);
        match.setOpponentName(opponentName);
        match.setBattleLog(String.join("\n", battleLog));
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

        ArenaMatch saved = matchRepository.save(match);
        log.info("[ArenaService] player={} action=startFight OK id={} opponent={} won={}", challenger.getId(), saved.getId(), opponentName, challengerWon);
        return saved;
    }

    @Transactional
    public ArenaMatch collectResult(Player challenger, Long matchId) {
        log.info("[ArenaService] player={} action=collectFight matchId={}", challenger.getId(), matchId);
        ArenaMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Battle not found"));

        if (!match.getChallenger().getId().equals(challenger.getId())) {
            log.warn("[ArenaService] player={} REJECTED: match {} does not belong to this player", challenger.getId(), matchId);
            throw new IllegalStateException("This battle does not belong to you");
        }
        if (match.getStatus() == MatchStatus.COLLECTED) {
            log.warn("[ArenaService] player={} REJECTED: match {} reward already collected", challenger.getId(), matchId);
            throw new IllegalStateException("Reward already collected");
        }
        if (LocalDateTime.now().isBefore(match.getFinishesAt())) {
            long secsLeft = java.time.Duration.between(LocalDateTime.now(), match.getFinishesAt()).getSeconds();
            log.warn("[ArenaService] player={} REJECTED: match {} still in progress, {}s remaining", challenger.getId(), matchId, secsLeft);
            throw new IllegalStateException("Battle still in progress. " + secsLeft + "s");
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

        // Libera guerreiro; derrota = HP 0 + perde buff
        warriorRepository.findByPlayer(challenger).ifPresent(w -> {
            w.setOnMission(false);
            if (!match.isChallengerWon()) {
                w.applyDamagePercent(100); // HP = 0
                w.clearBuff();             // perde o buff
            } else {
                w.applyDamagePercent(10);  // leve desgaste por lutar
            }
            warriorRepository.save(w);
        });

        match.setStatus(MatchStatus.COLLECTED);
        ArenaMatch result = matchRepository.save(match);
        log.info("[ArenaService] player={} action=collectFight OK matchId={} won={} bronze={}", challenger.getId(), matchId, match.isChallengerWon(), match.getGoldReward());
        return result;
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
        // base + atributos + itens equipados + joias (fonte única) [AUDITORIA A1/A9]
        return statsService.combatStats(player, warrior);
    }

    private int[] npcStats() {
        Random r = new Random();
        // NPC: atk, def, hp, dex(AC~15), strBonus(+1), luk(5)
        return new int[]{ 12 + r.nextInt(8), 8 + r.nextInt(6), 90 + r.nextInt(40), 5, 1, 5 };
    }

}
