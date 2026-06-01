package com.medieval.game.service;

import com.medieval.game.enums.TowerStatus;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class TowerService {

    private final TowerRunRepository   towerRunRepository;
    private final WarriorRepository    warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final PlayerRepository     playerRepository;

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
    public record BossInfo(String name, int attack, int defense, int health, int evasion) {}

    public BossInfo bossForFloor(int floor) {
        int group = Math.min((floor - 1) / 3, BOSSES.length - 1);
        int idx   = (floor - 1) % 3;
        String name = BOSSES[group][idx] + " (Andar " + floor + ")";
        return new BossInfo(
            name,
            5  + floor * 3,
            3  + floor * 2,
            80 + floor * 25,
            Math.min(5 + floor, 30)
        );
    }

    // ── Resultado de um combate ──
    public record FightResult(boolean won, int floor, long bronzeEarned, long expEarned,
                              List<String> log, String bossName, boolean runOver) {}

    public Optional<TowerRun> getCurrentRun(Player player) {
        return towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS);
    }

    public List<Player> getRanking() {
        return playerRepository.findAll().stream()
                .filter(p -> p.getTowerBestFloor() > 0)
                .sorted((a, b) -> Integer.compare(b.getTowerBestFloor(), a.getTowerBestFloor()))
                .limit(20)
                .toList();
    }

    @Transactional
    public TowerRun enter(Player player) {
        if (towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS).isPresent()) {
            throw new IllegalStateException("Você já está dentro da torre");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));

        if (warrior.isOnMission()) {
            throw new IllegalStateException("Seu guerreiro já está ocupado");
        }

        int stamina = player.getCalculatedStamina();
        if (stamina < STAMINA_COST) {
            throw new IllegalStateException("Estamina insuficiente (" + stamina + "/" + STAMINA_COST + ")");
        }

        player.setCurrentStamina(stamina - STAMINA_COST);
        player.setStaminaUpdatedAt(java.time.LocalDateTime.now());
        playerRepository.save(player);

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        TowerRun run = new TowerRun();
        run.setPlayer(player);
        return towerRunRepository.save(run);
    }

    @Transactional
    public FightResult fight(Player player) {
        TowerRun run = towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .orElseThrow(() -> new IllegalStateException("Você não está na torre"));

        int floor = run.getCurrentFloor();
        BossInfo boss = bossForFloor(floor);

        // Stats do guerreiro (base + atributos + itens)
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));

        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isEquipped).toList();

        int wAtk = warrior.getTotalBaseAttack()  + equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
        int wDef = warrior.getTotalBaseDefense() + equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
        int wHp  = warrior.getTotalBaseHealth()  + equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();
        int wEva = warrior.getEvasionChance();

        List<String> log = simulate(
            warrior.getName(), wAtk, wDef, wHp, wEva,
            boss.name(), boss.attack(), boss.defense(), boss.health(), boss.evasion()
        );

        boolean won = log.get(log.size() - 1).contains(warrior.getName());

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
            // Derrotado — sai da torre
            run.setStatus(TowerStatus.DEFEATED);
            warrior.setOnMission(false);
            warriorRepository.save(warrior);
        }

        towerRunRepository.save(run);
        return new FightResult(won, floor, bronzeEarned, expEarned, log, boss.name(),
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

    // ── Simulação de combate ──
    private List<String> simulate(String wName, int wAtk, int wDef, int wHp, int wEva,
                                   String bName, int bAtk, int bDef, int bHp, int bEva) {
        List<String> log = new ArrayList<>();
        Random rng = new Random();
        int wCurrentHp = wHp;
        int bCurrentHp = bHp;

        log.add("⚔ " + wName + " enfrenta " + bName + "!");
        log.add("─────────────────────────");

        for (int round = 1; round <= 40 && wCurrentHp > 0 && bCurrentHp > 0; round++) {
            log.add("Rodada " + round);

            // Guerreiro ataca boss
            if (rng.nextInt(100) < bEva) {
                log.add("  " + bName + " esquiva!");
            } else {
                int dmg = Math.max(1, wAtk - rng.nextInt(Math.max(1, bDef / 2 + 1)));
                bCurrentHp -= dmg;
                log.add("  " + wName + " ataca por " + dmg + " → " + bName + " HP: " + Math.max(0, bCurrentHp));
            }
            if (bCurrentHp <= 0) break;

            // Boss ataca guerreiro
            if (rng.nextInt(100) < wEva) {
                log.add("  " + wName + " esquiva!");
            } else {
                int dmg = Math.max(1, bAtk - rng.nextInt(Math.max(1, wDef / 2 + 1)));
                wCurrentHp -= dmg;
                log.add("  " + bName + " ataca por " + dmg + " → " + wName + " HP: " + Math.max(0, wCurrentHp));
            }
        }

        log.add("─────────────────────────");
        boolean wWon = wCurrentHp > bCurrentHp;
        log.add("🏆 " + (wWon ? wName : bName) + " vence!");
        return log;
    }
}
