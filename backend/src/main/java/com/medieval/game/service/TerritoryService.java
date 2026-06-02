package com.medieval.game.service;

import com.medieval.game.enums.Territory;
import com.medieval.game.model.*;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TerritoryService {

    private static final int BASE_XP_BONUS     = 10; // % applied to all territory holders
    private static final int BASE_BRONZE_BONUS  = 10; // %

    private final TerritoryControlRepository     controlRepo;
    private final TerritoryDeclarationRepository declarationRepo;
    private final TerritoryBattleLogRepository   battleLogRepo;
    private final PlayerRepository               playerRepository;
    private final WarriorRepository              warriorRepository;
    private final BattleSimulator                battleSimulator;

    // ── Init: ensure all 3 TerritoryControl rows exist ───────────────────────

    @Transactional
    public void ensureInitialized() {
        for (Territory t : Territory.values()) {
            if (controlRepo.findByTerritory(t).isEmpty()) {
                TerritoryControl tc = new TerritoryControl();
                tc.setTerritory(t);
                controlRepo.save(tc);
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public List<TerritoryControl> getAllTerritories() {
        ensureInitialized();
        return controlRepo.findAll();
    }

    public Optional<TerritoryControl> getControlledTerritory(Guild guild) {
        if (guild == null) return Optional.empty();
        return controlRepo.findByControllingGuild(guild);
    }

    public TerritoryControl getTerritory(Territory territory) {
        return controlRepo.findByTerritory(territory)
                .orElseGet(() -> { ensureInitialized(); return controlRepo.findByTerritory(territory).orElseThrow(); });
    }

    // Returns current battle cycle ID (epoch seconds / 21600)
    public long currentCycleId() {
        return Instant.now().getEpochSecond() / 21600;
    }

    // ── Declaration ───────────────────────────────────────────────────────────

    @Transactional
    public TerritoryDeclaration declare(Player player, Territory territory) {
        Guild guild = playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("You must be in a guild to declare an attack."));

        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Only the guild leader can declare an attack.");

        // Guild cannot attack if it already controls a territory
        if (controlRepo.findByControllingGuild(guild).isPresent())
            throw new IllegalStateException("Your guild already controls a territory. Defend it.");

        long cycleId = currentCycleId() + 1; // targets the NEXT cycle

        if (declarationRepo.existsByGuildAndBattleCycleIdAndStatus(guild, cycleId, DeclarationStatus.PENDING))
            throw new IllegalStateException("Your guild already declared an attack for the next cycle.");

        TerritoryDeclaration decl = new TerritoryDeclaration();
        decl.setGuild(guild);
        decl.setTerritory(territory);
        decl.setBattleCycleId(cycleId);
        return declarationRepo.save(decl);
    }

    @Transactional
    public void cancelDeclaration(Player player) {
        Guild guild = playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("Not in a guild."));

        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Only the guild leader can cancel.");

        long cycleId = currentCycleId() + 1;
        declarationRepo.findByGuildAndBattleCycleIdAndStatus(guild, cycleId, DeclarationStatus.PENDING)
                .ifPresent(d -> { d.setStatus(DeclarationStatus.CANCELLED); declarationRepo.save(d); });
    }

    // ── Scheduled Battle Resolution ───────────────────────────────────────────

    @Scheduled(cron = "0 0 0,6,12,18 * * *")
    @Transactional
    public void resolveAllBattles() {
        log.info("Territory war cycle starting...");
        ensureInitialized();
        long cycleId = currentCycleId(); // current cycle = battles to resolve now
        for (Territory territory : Territory.values()) {
            try {
                resolveTerritory(territory, cycleId);
            } catch (Exception e) {
                log.error("Error resolving territory {}: {}", territory, e.getMessage(), e);
            }
        }
        log.info("Territory war cycle complete.");
    }

    @Transactional
    public void resolveTerritory(Territory territory, long cycleId) {
        TerritoryControl control = getTerritory(territory);

        List<TerritoryDeclaration> declarations =
                declarationRepo.findByTerritoryAndStatusOrderByDeclaredAtAsc(territory, DeclarationStatus.PENDING)
                        .stream()
                        .filter(d -> d.getBattleCycleId() == cycleId)
                        .collect(Collectors.toList());

        if (declarations.isEmpty()) {
            // No attackers — defender keeps, streak increases
            if (!control.isNeutral()) {
                control.setDefenseStreak(control.getDefenseStreak() + 1);
                controlRepo.save(control);
            }
            return;
        }

        int debuff = control.debuffPercent(); // applied this round (from previous streak)

        Guild currentHolder = control.getControllingGuild();
        Guild newHolder     = currentHolder; // will be updated if attacker wins

        for (int i = 0; i < declarations.size(); i++) {
            TerritoryDeclaration decl = declarations.get(i);
            boolean isLastFight = (i == declarations.size() - 1);

            Guild attackerGuild = decl.getGuild();
            List<Fighter> attackers = buildFighters(attackerGuild, 0);

            // Is this a tiebreaker? (newHolder changed from original — a previous attacker won)
            boolean isTiebreaker = newHolder != null && !newHolder.equals(currentHolder);

            List<Fighter> defenders;
            if (control.isNeutral()) {
                defenders = buildNpcFighters(territory, attackers.size());
            } else {
                // If tiebreaker: fight previous winner with their REMAINING HP (already in DB)
                defenders = buildFighters(newHolder, isTiebreaker ? 0 : debuff);
            }

            // Save pre-battle HP for defenders (needed for HP recovery between regular defense fights)
            Map<Long, Integer> preBattleHp = new HashMap<>();
            for (Fighter f : defenders) {
                if (f.warrior != null) preBattleHp.put(f.warrior.getId(), f.hp);
            }

            BrawlResult result = guildBrawl(attackers, defenders, territory);

            persistHpChanges(result.attackerFighters);

            // Restore defender HP between regular fights — but NOT in tiebreakers (remaining HP is intentional)
            if (!control.isNeutral() && !isLastFight && !isTiebreaker) {
                for (Fighter f : result.defenderFighters) {
                    if (f.warrior != null && preBattleHp.containsKey(f.warrior.getId())) {
                        f.hp = preBattleHp.get(f.warrior.getId());
                    }
                }
            }
            persistHpChanges(result.defenderFighters);

            // Build battle log with clear tiebreaker label
            String defenderLabel;
            if (control.isNeutral()) {
                defenderLabel = territory.npcName + "s (NPC)";
            } else if (isTiebreaker) {
                defenderLabel = newHolder.getName() + " [TIEBREAKER — remaining HP]";
            } else {
                defenderLabel = newHolder != null ? newHolder.getName() : "NPC";
            }

            TerritoryBattleLog battleLog = new TerritoryBattleLog();
            battleLog.setTerritory(territory);
            battleLog.setAttackerGuildName(isTiebreaker
                    ? attackerGuild.getName() + " [TIEBREAKER]"
                    : attackerGuild.getName());
            battleLog.setDefenderGuildName(defenderLabel);
            battleLog.setBattleLog(String.join("\n", result.log));
            battleLog.setResolvedAt(LocalDateTime.now());

            if (result.attackersWon) {
                battleLog.setWinnerGuildName(attackerGuild.getName());
                newHolder = attackerGuild;
                debuff = 0;
            } else {
                battleLog.setWinnerGuildName(newHolder != null ? newHolder.getName() : "NPC");
            }

            battleLogRepo.save(battleLog);
            decl.setStatus(DeclarationStatus.RESOLVED);
            declarationRepo.save(decl);
        }

        // Update territory control
        if (!Objects.equals(newHolder, currentHolder)) {
            control.setControllingGuild(newHolder);
            control.setDefenseStreak(0);
            control.setDominantSince(LocalDateTime.now());
        } else if (!control.isNeutral()) {
            control.setDefenseStreak(control.getDefenseStreak() + 1);
        }
        controlRepo.save(control);
    }

    // ── Guild Brawl (King of the Hill) ────────────────────────────────────────

    public BrawlResult guildBrawl(List<Fighter> attackers, List<Fighter> defenders, Territory territory) {
        List<String> fullLog = new ArrayList<>();
        List<Fighter> atks = new ArrayList<>(attackers);
        List<Fighter> defs = new ArrayList<>(defenders);

        Collections.shuffle(atks);
        Collections.shuffle(defs);

        fullLog.add("=== ⚔ Guild Battle at " + territory.displayName + " ===");

        while (!atks.isEmpty() && !defs.isEmpty()) {
            Fighter a = atks.get(0);
            Fighter d = defs.get(0);

            List<String> roundLog = battleSimulator.simulate(
                    a.name, a.atk, a.def, a.hp, a.evasion,
                    d.name, d.atk, d.def, d.hp, d.evasion);

            // Parse result from last WINNER: line
            String winnerLine = roundLog.get(roundLog.size() - 1);
            boolean attackerWon = winnerLine.contains("WINNER:" + a.name);

            // Remove internal WINNER tag before adding to full log
            List<String> visibleLines = roundLog.subList(0, roundLog.size() - 1);
            fullLog.addAll(visibleLines);

            if (attackerWon) {
                // Estimate remaining HP (rough: winner had some HP left)
                a.hp = Math.max(1, a.hp / 3); // survivor carries reduced HP
                defs.remove(0);
            } else {
                d.hp = Math.max(1, d.hp / 3);
                atks.remove(0);
            }
        }

        boolean attackersWon = defs.isEmpty();
        fullLog.add(attackersWon
                ? "🏆 Attackers have conquered the territory!"
                : "🛡 Defenders held their ground!");

        return new BrawlResult(attackersWon, fullLog, atks, defs);
    }

    // ── Fighter building ──────────────────────────────────────────────────────

    public List<Fighter> buildFighters(Guild guild, int debuffPercent) {
        List<Player> members = playerRepository.findAllByGuild(guild);
        List<Fighter> fighters = new ArrayList<>();
        for (Player member : members) {
            warriorRepository.findByPlayer(member).ifPresent(w -> {
                int hp = w.getCalculatedHpPercent() * w.getHealth() / 100;
                if (hp <= 0) return; // unconscious warriors sit out
                double debuffMult = 1.0 - debuffPercent / 100.0;
                fighters.add(new Fighter(
                        member.getId(),
                        w.getName(),
                        (int) Math.max(1, w.getAttack()  * debuffMult),
                        (int) Math.max(1, w.getDefense() * debuffMult),
                        hp,
                        w.getDexterity(),
                        w
                ));
            });
        }
        return fighters;
    }

    private List<Fighter> buildNpcFighters(Territory territory, int count) {
        List<Fighter> npcs = new ArrayList<>();
        // NPC base stats (moderate challenge)
        int baseAtk = 20;
        int baseDef = 15;
        int baseHp  = 80;
        for (int i = 0; i < count; i++) {
            npcs.add(new Fighter(
                    null,
                    territory.npcName + " #" + (i + 1),
                    (int) (baseAtk * territory.npcAtkMult),
                    (int) (baseDef * territory.npcDefMult),
                    (int) (baseHp  * territory.npcHpMult),
                    5,
                    null
            ));
        }
        return npcs;
    }

    private void persistHpChanges(List<Fighter> fighters) {
        for (Fighter f : fighters) {
            if (f.warrior == null) continue;
            int maxHp = f.warrior.getHealth();
            int pct   = Math.max(0, Math.min(100, f.hp * 100 / maxHp));
            f.warrior.setCurrentHpSnapshot(pct);
            f.warrior.setHpUpdatedAt(LocalDateTime.now());
            warriorRepository.save(f.warrior);
        }
    }

    // ── Territory bonuses for services ────────────────────────────────────────

    public TerritoryBonus getBonusForPlayer(Player player) {
        Guild guild = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        if (guild == null) return TerritoryBonus.NONE;

        return controlRepo.findByControllingGuild(guild)
                .map(tc -> new TerritoryBonus(tc.getTerritory(), BASE_XP_BONUS, BASE_BRONZE_BONUS))
                .orElse(TerritoryBonus.NONE);
    }

    // ── History ───────────────────────────────────────────────────────────────

    public List<TerritoryBattleLog> getHistory(Territory territory) {
        return battleLogRepo.findTop10ByTerritoryOrderByResolvedAtDesc(territory);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public static class Fighter {
        public final Long   playerId;
        public final String name;
        public int atk, def, hp, evasion;
        public final Warrior warrior;

        public Fighter(Long playerId, String name, int atk, int def, int hp, int evasion, Warrior warrior) {
            this.playerId = playerId;
            this.name     = name;
            this.atk      = atk;
            this.def      = def;
            this.hp       = hp;
            this.evasion  = evasion;
            this.warrior  = warrior;
        }
    }

    public record BrawlResult(boolean attackersWon, List<String> log,
                               List<Fighter> attackerFighters, List<Fighter> defenderFighters) {}

    public record TerritoryBonus(Territory territory, int xpBonus, int bronzeBonus) {
        public static final TerritoryBonus NONE = new TerritoryBonus(null, 0, 0);

        public int miningBonus() {
            return territory == Territory.MINAS_DE_FERRO_NEGRO ? territory.exclusiveBonus : 0;
        }

        public int fishingBonus() {
            return territory == Territory.DESFILADEIRO_DO_OSSO ? territory.exclusiveBonus : 0;
        }

        public int questXpBonus() {
            return territory == Territory.FORTALEZA_MALDITA ? territory.exclusiveBonus : 0;
        }
    }
}
