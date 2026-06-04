package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.model.*;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private static final long UPKEEP_BASE_BRONZE = 500; // custo base de manutenção por ciclo (6h)

    private final TerritoryControlRepository     controlRepo;
    private final TerritoryDeclarationRepository declarationRepo;
    private final TerritoryBattleLogRepository   battleLogRepo;
    private final PlayerRepository               playerRepository;
    private final WarriorRepository              warriorRepository;
    private final BattleSimulator                battleSimulator;
    private final GuildRepository                guildRepository;

    // Quais reinos são território de guild-war (config). Os demais são zonas abertas.
    // Começa com os 3 reinos antigos; mudar a config liga guerra em mais reinos. [REINOS_V2 / flag]
    @org.springframework.beans.factory.annotation.Value("${app.kingdoms.war-territories:FISHING,MINING,COMBAT}")
    private String warTerritoriesCsv;

    /** Conjunto de reinos contestáveis por guild (da config). */
    public java.util.Set<Kingdom> warKingdoms() {
        java.util.EnumSet<Kingdom> set = java.util.EnumSet.noneOf(Kingdom.class);
        for (String s : warTerritoriesCsv.split(",")) {
            String name = s.trim();
            if (!name.isEmpty()) {
                try { set.add(Kingdom.valueOf(name)); }
                catch (IllegalArgumentException ignored) { log.warn("[TerritoryService] reino de guerra inválido na config: {}", name); }
            }
        }
        return set;
    }

    /** true se o reino é território de guild-war. */
    public boolean isWarKingdom(Kingdom k) {
        return warKingdoms().contains(k);
    }

    // ── Init: garante uma linha TerritoryControl por reino de GUERRA ───────────

    @Transactional
    public void ensureInitialized() {
        for (Kingdom t : warKingdoms()) {
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

    public TerritoryControl getTerritory(Kingdom territory) {
        return controlRepo.findByTerritory(territory)
                .orElseGet(() -> { ensureInitialized(); return controlRepo.findByTerritory(territory).orElseThrow(); });
    }

    // Returns current battle cycle ID (epoch seconds / 21600)
    public long currentCycleId() {
        return Instant.now().getEpochSecond() / 21600;
    }

    // ── Declaration ───────────────────────────────────────────────────────────

    @Transactional
    public TerritoryDeclaration declare(Player player, Kingdom territory) {
        log.info("[TerritoryService] player={} action=declare territory={}", player.getId(), territory);
        Guild guild = playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("You must be in a guild to declare an attack."));

        if (!guild.getLeaderId().equals(player.getId())) {
            log.warn("[TerritoryService] player={} REJECTED: only the guild leader can declare an attack", player.getId());
            throw new IllegalStateException("Only the guild leader can declare an attack.");
        }

        // Guild cannot attack if it already controls a territory
        if (controlRepo.findByControllingGuild(guild).isPresent()) {
            log.warn("[TerritoryService] player={} REJECTED: guild {} already controls a territory", player.getId(), guild.getName());
            throw new IllegalStateException("Your guild already controls a territory. Defend it.");
        }

        long cycleId = currentCycleId() + 1; // targets the NEXT cycle

        if (declarationRepo.existsByGuildAndBattleCycleIdAndStatus(guild, cycleId, DeclarationStatus.PENDING)) {
            log.warn("[TerritoryService] player={} REJECTED: guild {} already declared for next cycle", player.getId(), guild.getName());
            throw new IllegalStateException("Your guild already declared an attack for the next cycle.");
        }

        TerritoryDeclaration decl = new TerritoryDeclaration();
        decl.setGuild(guild);
        decl.setTerritory(territory);
        decl.setBattleCycleId(cycleId);
        TerritoryDeclaration saved = declarationRepo.save(decl);
        log.info("[TerritoryService] player={} action=declare OK guild={} territory={} cycleId={}", player.getId(), guild.getName(), territory, cycleId);
        return saved;
    }

    @Transactional
    public void cancelDeclaration(Player player) {
        log.info("[TerritoryService] player={} action=cancelDeclaration", player.getId());
        Guild guild = playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("Not in a guild."));

        if (!guild.getLeaderId().equals(player.getId())) {
            log.warn("[TerritoryService] player={} REJECTED: only the guild leader can cancel", player.getId());
            throw new IllegalStateException("Only the guild leader can cancel.");
        }

        long cycleId = currentCycleId() + 1;
        declarationRepo.findByGuildAndBattleCycleIdAndStatus(guild, cycleId, DeclarationStatus.PENDING)
                .ifPresent(d -> {
                    d.setStatus(DeclarationStatus.CANCELLED);
                    declarationRepo.save(d);
                    log.info("[TerritoryService] player={} action=cancelDeclaration OK guild={} territory={}", player.getId(), guild.getName(), d.getTerritory());
                });
    }

    // ── Scheduled Battle Resolution ───────────────────────────────────────────
    // O agendamento (cron + catch-up no boot) fica em TerritoryScheduler, que chama
    // os métodos abaixo via proxy (cross-bean) para que cada território seja resolvido
    // em sua PRÓPRIA transação (isolamento) e o @Transactional seja honrado. [AUDITORIA A7]

    // Quantos ciclos perdidos (em downtime) reprocessar no máximo — evita loop gigante
    // se o lastResolvedCycleId estiver muito atrás. 8 ciclos = 2 dias.
    private static final long MAX_CATCHUP_CYCLES = 8;

    /**
     * Resolve todos os ciclos devidos de UM território: de (lastResolved+1) até o ciclo atual.
     * Idempotente — não reprocessa o que já foi resolvido. Transação própria por território.
     */
    @Transactional
    public void resolveDueCyclesForTerritory(Kingdom territory, long current) {
        TerritoryControl control = getTerritory(territory);
        long last = control.getLastResolvedCycleId();
        if (last <= 0) {
            // Primeira execução / pós-migração: marca o ponto atual sem reprocessar histórico.
            control.setLastResolvedCycleId(current);
            controlRepo.save(control);
            return;
        }
        if (last >= current) return; // nada novo

        long from = Math.max(last + 1, current - MAX_CATCHUP_CYCLES + 1);
        if (from > last + 1) {
            log.warn("Kingdom {}: skipping {} stale cycles (cap {})",
                    territory, (from - last - 1), MAX_CATCHUP_CYCLES);
        }
        for (long cycle = from; cycle <= current; cycle++) {
            resolveTerritory(territory, cycle);
        }
        // Re-busca: resolveTerritory pode ter trocado o controle/streak
        TerritoryControl after = getTerritory(territory);
        after.setLastResolvedCycleId(current);
        controlRepo.save(after);
    }

    @Transactional
    public void resolveTerritory(Kingdom territory, long cycleId) {
        TerritoryControl control = getTerritory(territory);

        // ── Manutenção do território (sink econômico) ──────────────────────────
        // A guild dominante paga 500 × (1 + streak × 0.1) bronze do tesouro por ciclo.
        // Se o tesouro não cobrir, o território é abandonado (volta a neutro).
        chargeUpkeep(territory, control);

        List<TerritoryDeclaration> declarations =
                declarationRepo.findByTerritoryAndStatusOrderByDeclaredAtAsc(territory, DeclarationStatus.PENDING)
                        .stream()
                        .filter(d -> d.getBattleCycleId() == cycleId)
                        .collect(Collectors.toList());

        if (declarations.isEmpty()) {
            if (!control.isNeutral()) {
                control.setDefenseStreak(control.getDefenseStreak() + 1);
                controlRepo.save(control);
            }
            return;
        }

        int debuff = control.debuffPercent();
        Guild currentHolder = control.getControllingGuild();
        Guild newHolder = currentHolder;

        // ── PHASE 1: every attacker fights the original defenders independently ──
        // Defenders recover HP between each fight (except after the last one).
        // Collect the guilds that beat the defenders.

        List<Guild> phase1Winners = new ArrayList<>();

        for (int i = 0; i < declarations.size(); i++) {
            TerritoryDeclaration decl = declarations.get(i);
            boolean isLastPhase1Fight = (i == declarations.size() - 1);

            Guild attackerGuild = decl.getGuild();
            List<Fighter> attackers = buildFighters(attackerGuild, 0);

            List<Fighter> defenders;
            if (control.isNeutral()) {
                defenders = buildNpcFighters(territory, attackers.size());
            } else {
                defenders = buildFighters(currentHolder, debuff); // always the ORIGINAL holder
            }

            // Save pre-battle HP for defender recovery between Phase 1 fights
            Map<Long, Integer> preBattleHp = new HashMap<>();
            for (Fighter f : defenders) {
                if (f.warrior != null) preBattleHp.put(f.warrior.getId(), f.hp);
            }

            BrawlResult result = guildBrawl(attackers, defenders, territory);

            // Persist attacker HP (their Phase 1 remaining HP goes to DB)
            persistHpChanges(result.attackerFighters);

            // Restore defenders between Phase 1 fights — except after the last Phase 1 fight
            if (!isLastPhase1Fight) {
                for (Fighter f : result.defenderFighters) {
                    if (f.warrior != null && preBattleHp.containsKey(f.warrior.getId()))
                        f.hp = preBattleHp.get(f.warrior.getId());
                }
            }
            persistHpChanges(result.defenderFighters);

            String defenderName = control.isNeutral() ? territory.npcName + "s" : currentHolder.getName();
            saveBattleLog(territory, attackerGuild.getName(), defenderName,
                    result.attackersWon ? attackerGuild.getName() : defenderName, result.log);

            if (result.attackersWon) {
                phase1Winners.add(attackerGuild);
            }

            decl.setStatus(DeclarationStatus.RESOLVED);
            declarationRepo.save(decl);
        }

        if (phase1Winners.isEmpty()) {
            // All attackers beaten — original holder wins, streak increases
            if (!control.isNeutral()) {
                control.setDefenseStreak(control.getDefenseStreak() + 1);
                controlRepo.save(control);
            }
            return;
        }

        // Single winner — takes territory directly
        if (phase1Winners.size() == 1) {
            newHolder = phase1Winners.get(0);
        } else {
            // ── PHASE 2: tiebreaker among Phase 1 winners (random bracket) ──
            //
            // Every guild enters EACH tiebreaker fight with their Phase 1 HP
            // (the HP remaining after beating Guild X). HP does NOT carry over
            // between tiebreaker fights — each fight resets to Phase 1 HP.
            //
            // This gives every guild the same starting condition in every fight:
            // "you fought the defender and kept what was left."

            Collections.shuffle(phase1Winners, new java.util.Random());
            Guild tiebreakerChampion = phase1Winners.get(0);

            for (int i = 1; i < phase1Winners.size(); i++) {
                Guild tiebreakerChallenger = phase1Winners.get(i);

                // Build fighters fresh from DB — Phase 1 HP is what's stored
                // (we didn't persist between tiebreaker fights)
                List<Fighter> champFighters = buildFighters(tiebreakerChampion,    0);
                List<Fighter> chalFighters  = buildFighters(tiebreakerChallenger, 0);

                BrawlResult tResult = guildBrawl(chalFighters, champFighters, territory);

                // Do NOT persist HP between tiebreaker fights —
                // next fight always starts from Phase 1 HP (DB state unchanged)

                String tbWinner;
                if (tResult.attackersWon) {
                    tbWinner            = tiebreakerChallenger.getName();
                    tiebreakerChampion  = tiebreakerChallenger;
                } else {
                    tbWinner = tiebreakerChampion.getName();
                }

                saveBattleLog(territory,
                    tiebreakerChallenger.getName() + " [TIEBREAKER]",
                    tiebreakerChampion.getName()   + " [TIEBREAKER — Phase 1 HP]",
                    tbWinner, tResult.log);
            }

            newHolder = tiebreakerChampion;
        }

        // Update territory control — compara por id (não por referência/valor da entidade). [AUDITORIA M4]
        Long newId = newHolder != null ? newHolder.getId() : null;
        Long curId = currentHolder != null ? currentHolder.getId() : null;
        if (!Objects.equals(newId, curId)) {
            control.setControllingGuild(newHolder);
            control.setDefenseStreak(0);
            control.setDominantSince(LocalDateTime.now());
        } else if (!control.isNeutral()) {
            control.setDefenseStreak(control.getDefenseStreak() + 1);
        }
        controlRepo.save(control);
    }

    /** Custo de manutenção do território para a guild dominante neste ciclo. */
    public long upkeepCost(TerritoryControl control) {
        return Math.round(UPKEEP_BASE_BRONZE * (1.0 + control.getDefenseStreak() * 0.1));
    }

    /**
     * Cobra a manutenção da guild dominante. Se o tesouro não cobrir o custo,
     * o território é abandonado (volta a neutro) e o streak é zerado.
     */
    private void chargeUpkeep(Kingdom territory, TerritoryControl control) {
        Guild holder = control.getControllingGuild();
        if (holder == null) return; // território neutro não paga manutenção

        long cost = upkeepCost(control);
        if (holder.getGold() >= cost) {
            holder.setGold(holder.getGold() - cost);
            guildRepository.save(holder);
            log.info("[TerritoryService] territory={} upkeep paid guild={} cost={} treasuryLeft={}",
                    territory, holder.getName(), cost, holder.getGold());
        } else {
            // Não consegue pagar — abandona o território
            log.info("[TerritoryService] territory={} upkeep UNPAID guild={} cost={} treasury={} → neutral",
                    territory, holder.getName(), cost, holder.getGold());
            saveBattleLog(territory, holder.getName(), territory.npcName + "s",
                    territory.npcName + "s",
                    List.of("💰 " + holder.getName() + " could not pay upkeep (" + cost
                            + " bronze) and abandoned " + territory.displayName + "."));
            control.setControllingGuild(null);
            control.setDefenseStreak(0);
            control.setDominantSince(null);
            controlRepo.save(control);
        }
    }

    // ── Guild Brawl (King of the Hill) ────────────────────────────────────────

    public BrawlResult guildBrawl(List<Fighter> attackers, List<Fighter> defenders, Kingdom territory) {
        List<String> fullLog = new ArrayList<>();
        List<Fighter> atks = new ArrayList<>(attackers);
        List<Fighter> defs = new ArrayList<>(defenders);

        Collections.shuffle(atks);
        Collections.shuffle(defs);

        fullLog.add("=== ⚔ Guild Battle at " + territory.displayName + " ===");

        while (!atks.isEmpty() && !defs.isEmpty()) {
            Fighter a = atks.get(0);
            Fighter d = defs.get(0);

            BattleSimulator.BattleOutcome round = battleSimulator.simulateDetailed(
                    a.name, a.atk, a.def, a.hp, a.dex, a.strBonus, a.luk,
                    d.name, d.atk, d.def, d.hp, d.dex, d.strBonus, d.luk);

            // Vencedor explícito (sem parsear string — nomes podem se conter). [AUDITORIA M13]
            boolean attackerWon = round.firstWon();

            // Remove internal WINNER tag before adding to full log
            List<String> roundLog = round.log();
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
                        (int) Math.max(0, w.getDexterity()   * (1.0 - debuffPercent / 100.0)),
                        w.getAttackBonus(),
                        w.getLuck(),
                        w
                ));
            });
        }
        return fighters;
    }

    private List<Fighter> buildNpcFighters(Kingdom territory, int count) {
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
                    5,  // dex → AC 15
                    1,  // strBonus
                    5,  // luk
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

    // ── Kingdom bonuses for services ────────────────────────────────────────

    public TerritoryBonus getBonusForPlayer(Player player) {
        Guild guild = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        if (guild == null) return TerritoryBonus.NONE;

        return controlRepo.findByControllingGuild(guild)
                .map(tc -> new TerritoryBonus(tc.getTerritory(), BASE_XP_BONUS, BASE_BRONZE_BONUS))
                .orElse(TerritoryBonus.NONE);
    }

    // ── History ───────────────────────────────────────────────────────────────

    public List<TerritoryBattleLog> getHistory(Kingdom territory) {
        return battleLogRepo.findTop10ByTerritoryOrderByResolvedAtDesc(territory);
    }

    // ── Battle log helper ─────────────────────────────────────────────────────

    private void saveBattleLog(Kingdom territory, String attacker, String defender,
                               String winner, java.util.List<String> log) {
        TerritoryBattleLog entry = new TerritoryBattleLog();
        entry.setTerritory(territory);
        entry.setAttackerGuildName(attacker);
        entry.setDefenderGuildName(defender);
        entry.setWinnerGuildName(winner);
        entry.setBattleLog(String.join("\n", log));
        entry.setResolvedAt(LocalDateTime.now());
        battleLogRepo.save(entry);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public static class Fighter {
        public final Long   playerId;
        public final String name;
        public int atk, def, hp, dex, strBonus, luk;
        public final Warrior warrior;

        public Fighter(Long playerId, String name, int atk, int def, int hp, int dex, int strBonus, int luk, Warrior warrior) {
            this.playerId  = playerId;
            this.name      = name;
            this.atk       = atk;
            this.def       = def;
            this.hp        = hp;
            this.dex       = dex;
            this.strBonus  = strBonus;
            this.luk       = luk;
            this.warrior   = warrior;
        }
    }

    public record BrawlResult(boolean attackersWon, List<String> log,
                               List<Fighter> attackerFighters, List<Fighter> defenderFighters) {}

    public record TerritoryBonus(Kingdom territory, int xpBonus, int bronzeBonus) {
        public static final TerritoryBonus NONE = new TerritoryBonus(null, 0, 0);

        public int miningBonus() {
            return territory == Kingdom.MINING ? territory.exclusiveBonus : 0;
        }

        public int fishingBonus() {
            return territory == Kingdom.FISHING ? territory.exclusiveBonus : 0;
        }

        public int questXpBonus() {
            return territory == Kingdom.COMBAT ? territory.exclusiveBonus : 0;
        }
    }
}
