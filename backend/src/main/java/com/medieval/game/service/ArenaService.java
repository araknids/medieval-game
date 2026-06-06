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
    private final AbilityService       abilityService; // ativas no combate [HABILIDADES]

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    private static final String[] NPC_NAMES = {
        "Bandido da Estrada", "Mercenário Sombrio", "Guarda Corrupto",
        "Desertor do Rei", "Caçador de Recompensas"
    };

    public Optional<ArenaMatch> getActiveFight(Player challenger) {
        return matchRepository.findByChallengerAndStatus(challenger, MatchStatus.FIGHTING);
    }

    /**
     * [SEM_TIMER] Duelo instantâneo: simula a batalha e aplica TUDO de uma vez (bronze, rank,
     * V/D do desafiante e do oponente, HP, desgaste). Sem timer nem etapa de collect.
     */
    @Transactional
    public ArenaMatch startFight(Player challengerArg) {
        log.info("[ArenaService] player={} action=fight", challengerArg.getId());
        // Recarrega como MANAGED (o controller passa um detached; aqui aplicamos recompensa + rank,
        // salvando o player mais de uma vez → sem isto haveria merge de versão velha). [SEM_TIMER/PVP_FLAG]
        final Player challenger = playerRepository.findById(challengerArg.getId()).orElse(challengerArg);

        Warrior cWarrior = warriorRepository.findByPlayer(challenger)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (cWarrior.isKnockedOut()) {
            log.warn("[ArenaService] player={} REJECTED: warrior is unconscious", challenger.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        // Daily fight limit (5 free / 10 VIP) + estamina (gate, pulado no modo de teste)
        vipService.consumeArenaFight(challenger);
        if (!instantComplete) {
            playerService.consumeStamina(challenger, 25);
        }

        int[] cStats = totalStats(challenger, cWarrior);
        // [ELEMENTOS/HABILIDADES] Encantamentos + ativas do desafiante; oponente preenche abaixo (NPC = neutro/sem kit).
        com.medieval.game.enums.Element cWeapon = cWarrior.getActiveWeaponElement();
        com.medieval.game.enums.Element cArmor  = cWarrior.getActiveArmorElement();
        com.medieval.game.enums.Element oWeapon = null, oArmor = null;
        java.util.List<BattleSimulator.ActiveAbility> oAbilities = java.util.List.of();

        // Oponente: outro jogador real (rank próximo) ou NPC
        Player opponent = findOpponent(challenger);
        String opponentName;
        int[]  oStats;
        if (opponent != null) {
            Warrior oWarrior = warriorRepository.findByPlayer(opponent).orElse(null);
            opponentName = oWarrior != null ? oWarrior.getName() : opponent.getUsername();
            oStats = oWarrior != null ? totalStats(opponent, oWarrior) : npcStats();
            if (oWarrior != null) {
                oWeapon = oWarrior.getActiveWeaponElement(); oArmor = oWarrior.getActiveArmorElement();
                oAbilities = abilityService.activeLoadout(oWarrior);
            }
        } else {
            opponentName = NPC_NAMES[java.util.concurrent.ThreadLocalRandom.current().nextInt(NPC_NAMES.length)];
            oStats = npcStats();
        }

        BattleSimulator.BattleOutcome outcome = battleSimulator.simulate(
                BattleSimulator.Combatant.of(cWarrior.getName(), cStats, cWeapon, cArmor, abilityService.activeLoadout(cWarrior)),
                BattleSimulator.Combatant.of(opponentName,       oStats, oWeapon, oArmor, oAbilities),
                false); // PvP %HP
        inventoryService.wearEquippedItems(challenger);

        boolean challengerWon = outcome.firstWon(); // vencedor explícito do simulador [AUDITORIA M13]
        List<String> battleLog = new java.util.ArrayList<>(outcome.log());
        battleLog.remove(battleLog.size() - 1); // remove a tag interna WINNER
        long goldReward = challengerWon ? 200 : 50; // bronze
        int  rankChange = challengerWon ? (opponent != null ? 25 : 15) : (opponent != null ? -15 : -5);

        // ── Aplica o resultado na hora ──
        playerService.addGold(challenger, goldReward);
        challenger.setRankPoints(Math.max(0, challenger.getRankPoints() + rankChange));
        if (challengerWon) challenger.setArenaWins(challenger.getArenaWins() + 1);
        else               challenger.setArenaLosses(challenger.getArenaLosses() + 1);
        playerRepository.save(challenger);

        if (opponent != null) {
            int oppChange = challengerWon ? -15 : 25;
            opponent.setRankPoints(Math.max(0, opponent.getRankPoints() + oppChange));
            if (challengerWon) opponent.setArenaLosses(opponent.getArenaLosses() + 1);
            else               opponent.setArenaWins(opponent.getArenaWins() + 1);
            playerRepository.save(opponent);
        }

        // HP: derrota = KO (0%) + perde buff; vitória = leve desgaste. Duelo instantâneo.
        if (challengerWon) cWarrior.applyDamagePercent(10);
        else { cWarrior.applyDamagePercent(100); cWarrior.clearBuff(); }
        warriorRepository.save(cWarrior);

        ArenaMatch match = new ArenaMatch();
        match.setChallenger(challenger);
        match.setOpponent(opponent);
        match.setOpponentName(opponentName);
        match.setBattleLog(String.join("\n", battleLog));
        match.setChallengerWon(challengerWon);
        match.setGoldReward(goldReward);
        match.setRankChange(rankChange);
        match.setStartedAt(LocalDateTime.now());
        match.setFinishesAt(LocalDateTime.now());
        match.setStatus(MatchStatus.COLLECTED);

        ArenaMatch saved = matchRepository.save(match);
        log.info("[ArenaService] player={} action=fight OK id={} opponent={} won={}", challenger.getId(), saved.getId(), opponentName, challengerWon);
        return saved;
    }

    public List<Player> getRanking() {
        return matchRepository.findTopRanked(org.springframework.data.domain.PageRequest.of(0, 20));
    }

    // ── Privados ──

    // Matchmaking: sorteia entre os 10 jogadores de rank mais próximo (query limitada
    // no banco — não carrega todos os jogadores). Sem candidatos → NPC. [AUDITORIA M14]
    private Player findOpponent(Player challenger) {
        List<Player> candidates = playerRepository.findOpponentsByRank(
                challenger.getId(), challenger.getRankPoints(),
                org.springframework.data.domain.PageRequest.of(0, 10));
        if (candidates.isEmpty()) return null;
        return candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private int[] totalStats(Player player, Warrior warrior) {
        // base + atributos + itens equipados + joias (fonte única) [AUDITORIA A1/A9]
        return statsService.combatStats(player, warrior);
    }

    private int[] npcStats() {
        Random r = java.util.concurrent.ThreadLocalRandom.current();
        // NPC: atk, def, hp, dex(AC~15), strBonus(+1), luk(5)
        return new int[]{ 12 + r.nextInt(8), 8 + r.nextInt(6), 90 + r.nextInt(40), 5, 1, 5 };
    }

}
