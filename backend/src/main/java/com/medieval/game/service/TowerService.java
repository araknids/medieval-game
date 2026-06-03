package com.medieval.game.service;

import com.medieval.game.enums.TowerStatus;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TowerService {

    private final TowerRunRepository      towerRunRepository;
    private final WarriorRepository       warriorRepository;
    private final PlayerRepository        playerRepository;
    private final BattleSimulator         battleSimulator;
    private final InventoryService        inventoryService;
    private final WarriorStatsService     statsService;
    private final PlayerService           playerService;

    private static final int STAMINA_COST = 25;

    // ── Nomes dos chefes por grupo de andares (3 por grupo) ──
    private static final String[][] BOSSES = {
        {"Esqueleto Errante",    "Goblin Briga-Tudo",    "Rato Gigante"},         // 1-3
        {"Aranha das Trevas",    "Orc Batalhador",       "Troll da Pedra"},       // 4-6
        {"Zumbi Corrompido",     "Vampiro Menor",        "Golem de Ossos"},       // 7-9
        {"Cavaleiro Negro",      "Arqueiro Sombrio",     "Ogro Enfurecido"},      // 10-12
        {"Xamã das Trevas",      "Wyvern Jovem",         "Lich Menor"},           // 13-15
        {"Dragão de Sombra",     "Titan Corrompido",     "Lich Ancião"},          // 16-18
        {"Campeão Infernal",     "Senhor dos Mortos",    "Arcanista Caído"},      // 19-21
        {"Arquidemônio",         "Diabo Ancestral",      "O Guardião da Torre"},  // 22+
    };

    // ── Info do chefe para um andar ──
    /** Boss stats for d20 system: dex contributes to AC, not evasion%. */
    public record BossInfo(String name, int attack, int defense, int health, int dex, int strBonus, int luk) {}

    public BossInfo bossForFloor(int floor) {
        int group = Math.min((floor - 1) / 3, BOSSES.length - 1);
        int idx   = (floor - 1) % 3;
        String name = BOSSES[group][idx] + " (Andar " + floor + ")";
        return new BossInfo(
            name,
            5  + floor * 3,          // attack scales with floor
            3  + floor * 2,          // defense scales with floor
            80 + floor * 25,         // HP scales with floor
            Math.min(floor / 2, 20), // dex → AC = 10+dex, cap at 30
            Math.min(floor / 10, 3), // strBonus grows slowly, cap +3
            Math.min(floor, 15)      // luk, cap 15 (crit on 19-20 at high floors)
        );
    }

    // ── Resultado de um combate ──
    public record FightResult(boolean won, int floor, long bronzeEarned, long expEarned,
                              List<String> log, String bossName, boolean runOver) {}

    public Optional<TowerRun> getCurrentRun(Player player) {
        return towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS);
    }

    public List<Player> getRanking() {
        // Top 20 no banco (não carrega todos os jogadores); descarta quem nunca subiu. [AUDITORIA M14]
        return playerRepository.findTop20ByOrderByTowerBestFloorDesc().stream()
                .filter(p -> p.getTowerBestFloor() > 0)
                .toList();
    }

    @Transactional
    public TowerRun enter(Player player) {
        log.info("[TowerService] player={} action=enter", player.getId());
        if (towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS).isPresent()) {
            log.warn("[TowerService] player={} REJECTED: already inside the tower", player.getId());
            throw new IllegalStateException("You are already inside the tower");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isOnMission()) {
            log.warn("[TowerService] player={} REJECTED: warrior is already busy", player.getId());
            throw new IllegalStateException("Your warrior is already busy");
        }
        if (warrior.isKnockedOut()) {
            log.warn("[TowerService] player={} REJECTED: warrior is unconscious", player.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        int stamina = player.getCalculatedStamina();
        if (stamina < STAMINA_COST) {
            log.warn("[TowerService] player={} REJECTED: insufficient stamina {}/{}", player.getId(), stamina, STAMINA_COST);
            throw new IllegalStateException("Insufficient stamina (" + stamina + "/" + STAMINA_COST + ")");
        }

        player.setCurrentStamina(stamina - STAMINA_COST);
        player.setStaminaUpdatedAt(java.time.LocalDateTime.now());
        playerRepository.save(player);

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        // Começa do andar seguinte ao melhor já completado (checkpoint)
        int startFloor = player.getTowerBestFloor() > 0
                ? player.getTowerBestFloor() + 1
                : 1;

        TowerRun run = new TowerRun();
        run.setPlayer(player);
        run.setCurrentFloor(startFloor);
        run.setHighestFloor(player.getTowerBestFloor()); // já completados anteriormente
        TowerRun saved = towerRunRepository.save(run);
        log.info("[TowerService] player={} action=enter OK runId={} startFloor={}", player.getId(), saved.getId(), startFloor);
        return saved;
    }

    @Transactional
    public FightResult fight(Player player) {
        log.info("[TowerService] player={} action=climbToNextFloor", player.getId());
        TowerRun run = towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .orElseThrow(() -> new IllegalStateException("Você não está na torre"));

        int floor = run.getCurrentFloor();
        BossInfo boss = bossForFloor(floor);

        // Taxa de subida (sink escalável) — a Torre deixa de ser renda pura. [AUDITORIA A3]
        // Custo = floor × 15. Vitória rende floor × 40 (líquido floor × 25); derrota custa a taxa.
        long climbCost = (long) floor * 15;
        if (player.totalBronze() < climbCost) {
            log.warn("[TowerService] player={} REJECTED: bronze insuficiente para subir (have={} need={})",
                    player.getId(), player.totalBronze(), climbCost);
            throw new IllegalStateException("Bronze insuficiente para enfrentar o andar " + floor
                    + " (custo " + climbCost + " bronze).");
        }
        playerService.spendBronze(player, climbCost);

        // Stats do guerreiro (base + atributos + itens + joias — fonte única) [AUDITORIA A1/A9]
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        int[] s = statsService.combatStats(player, warrior);
        BattleSimulator.BattleOutcome outcome = battleSimulator.simulateDetailed(
            warrior.getName(), s[0], s[1], s[2], s[3], s[4], s[5],
            boss.name(), boss.attack(), boss.defense(), boss.health(), boss.dex(), boss.strBonus(), boss.luk()
        );

        // Desgaste de equipamento por lutar (1-10 de durabilidade por item)
        inventoryService.wearEquippedItems(player);

        // Vencedor explícito do simulador (sem parsear string). [AUDITORIA M13]
        boolean won = outcome.firstWon();
        List<String> battleLog = new java.util.ArrayList<>(outcome.log());
        battleLog.remove(battleLog.size() - 1);

        long bronzeEarned = 0;
        long expEarned    = 0;

        if (won) {
            bronzeEarned = (long) floor * 40;
            expEarned    = (long) floor * 20;

            player.addBronzeAmount(bronzeEarned);
            playerRepository.save(player);

            warrior.setExperience(warrior.getExperience() + expEarned);
            while (warrior.getExperience() >= warrior.expNeededForNextLevel()) {
                warrior.setExperience(warrior.getExperience() - warrior.expNeededForNextLevel());
                warrior.levelUp();
            }
            warriorRepository.save(warrior);

            run.setHighestFloor(floor);
            run.setCurrentFloor(floor + 1);

            // Atualiza melhor andar histórico do jogador
            if (floor > player.getTowerBestFloor()) {
                player.setTowerBestFloor(floor);
                playerRepository.save(player);
            }
        } else {
            // Derrotado — sai da torre, HP = 0, perde buff
            run.setStatus(TowerStatus.DEFEATED);
            warrior.setOnMission(false);
            warrior.applyDamagePercent(100);
            warrior.clearBuff();
            warriorRepository.save(warrior);
        }

        towerRunRepository.save(run);
        log.info("[TowerService] player={} action=climbToNextFloor OK floor={} won={} bronze={} xp={}", player.getId(), floor, won, bronzeEarned, expEarned);
        return new FightResult(won, floor, bronzeEarned, expEarned, battleLog, boss.name(),
                run.getStatus() == TowerStatus.DEFEATED);
    }

    @Transactional
    public void exit(Player player) {
        TowerRun run = towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .orElseThrow(() -> new IllegalStateException("Você não está na torre"));

        run.setStatus(TowerStatus.EXITED);
        towerRunRepository.save(run);

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });
    }

}
