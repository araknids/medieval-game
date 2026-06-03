package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combate PvE da Fortaleza Maldita (Reinos V2). Caçada repetível contra mobs
 * comuns que escalam com o nível do guerreiro e dropam gold + materiais.
 * (Antes era um reino próprio — Covil das Feras — fundido na Fortaleza.)
 * Chefes ficam reservados para a Tower. Reusa o BattleSimulator e o WarriorStatsService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CombatPveService {

    private static final int RAID_STAMINA = 15;

    private static final String[] BEASTS = {
        "Lobo Sombrio", "Aranha Gigante", "Ogro das Cavernas", "Harpia Selvagem",
        "Quimera Menor", "Verme das Profundezas", "Gárgula de Pedra", "Basilisco Jovem"
    };

    private final WarriorRepository   warriorRepository;
    private final PlayerRepository    playerRepository;
    private final BattleSimulator     battleSimulator;
    private final WarriorStatsService statsService;
    private final InventoryService    inventoryService;
    private final GatheringService    gatheringService;
    private final PlayerService       playerService;
    private final WarriorService      warriorService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    public record RaidDrop(ResourceType type, long quantity) {}
    public record RaidResult(boolean won, String beastName, long goldEarned, long xpEarned,
                             List<RaidDrop> materials, List<String> log) {}

    @Transactional
    public RaidResult raid(Player player) {
        log.info("[CombatPveService] player={} action=raid", player.getId());
        Warrior w = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        if (w.isOnMission()) {
            throw new IllegalStateException("Seu guerreiro está ocupado.");
        }
        if (w.isKnockedOut()) {
            throw new IllegalStateException("Seu guerreiro está inconsciente. Cure-se no Templo!");
        }
        if (!instantComplete) {
            playerService.consumeStamina(player, RAID_STAMINA);
        }

        int level = w.getLevel();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        String beast = BEASTS[rng.nextInt(BEASTS.length)];

        int[] s = statsService.combatStats(player, w);
        int maxHp = s[2];
        int curHp = w.getCalculatedHpPercent() * maxHp / 100;
        int[] mob = mobStats(level, rng);

        BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
            w.getName(), s[0], s[1], curHp, s[3], s[4], s[5],
            beast, mob[0], mob[1], mob[2], mob[3], mob[4], mob[5]);

        // Desgaste de equipamento por lutar
        inventoryService.wearEquippedItems(player);

        boolean won = out.firstWon();
        List<String> battleLog = new ArrayList<>(out.log());
        battleLog.remove(battleLog.size() - 1); // remove tag WINNER

        // Persiste HP do guerreiro pós-luta (0 = morreu)
        int finalPct = maxHp > 0 ? Math.max(0, out.firstHpFinal() * 100 / maxHp) : 0;
        w.setCurrentHpSnapshot(finalPct);
        w.setHpUpdatedAt(LocalDateTime.now());

        long goldEarned = 0, xpEarned = 0;
        List<RaidDrop> materials = new ArrayList<>();

        if (won) {
            goldEarned = (long) level * 10;
            xpEarned   = (long) level * 12;
            player.addBronzeAmount(goldEarned);
            playerRepository.save(player);
            warriorService.addExperience(w, xpEarned);

            // Materiais: Núcleo de Fera sempre (1 + level/25); Pele de Fera com chance.
            long cores = 1 + level / 25;
            gatheringService.addResource(player, ResourceType.MONSTER_CORE, cores);
            materials.add(new RaidDrop(ResourceType.MONSTER_CORE, cores));
            if (rng.nextDouble() < 0.25) {
                gatheringService.addResource(player, ResourceType.BEAST_HIDE, 1);
                materials.add(new RaidDrop(ResourceType.BEAST_HIDE, 1));
            }
        }

        warriorRepository.save(w);
        log.info("[CombatPveService] player={} action=raid OK won={} gold={} xp={} beast={}",
                player.getId(), won, goldEarned, xpEarned, beast);
        return new RaidResult(won, beast, goldEarned, xpEarned, materials, battleLog);
    }

    // Mob escala com o nível — calibrado para ser vencível por um guerreiro equipado.
    private int[] mobStats(int level, ThreadLocalRandom rng) {
        int atk      = 3 + level * 2 + rng.nextInt(3);
        int def      = 1 + level + rng.nextInt(2);
        int hp       = 50 + level * 14 + rng.nextInt(20);
        int dex      = Math.min(level / 3, 14);
        int strBonus = Math.min(level / 15, 3);
        int luk      = Math.min(level / 5, 8);
        return new int[]{atk, def, hp, dex, strBonus, luk};
    }
}
