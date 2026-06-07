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

    private static final int ROSTER_MAX = 15; // máx. de lutadores por guild por ciclo. [GUERRA_ROSTER]

    private final TerritoryControlRepository     controlRepo;
    private final TerritoryDeclarationRepository declarationRepo;
    private final TerritoryBattleLogRepository   battleLogRepo;
    private final PlayerRepository               playerRepository;
    private final WarriorRepository              warriorRepository;
    private final BattleSimulator                battleSimulator;
    private final GuildRepository                guildRepository;
    private final WarriorStatsService            statsService; // gear+buffs+postura na guerra. [POSTURE]
    private final AbilityService                 abilityService; // elementos + ativas na guerra [GUERRA_FORMACAO]

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

        // Warriors realmente escalados neste ciclo (por playerId) → recebem o stack de cansaço no fim. [GUERRA_ROSTER]
        Map<Long, Warrior> fielded = new HashMap<>();

        for (int i = 0; i < declarations.size(); i++) {
            TerritoryDeclaration decl = declarations.get(i);
            boolean isLastPhase1Fight = (i == declarations.size() - 1);

            Guild attackerGuild = decl.getGuild();
            Fighter[][] attackers = buildFormation(attackerGuild, 0, cycleId);
            collectFielded(fielded, flatten(attackers));

            Fighter[][] defenders;
            if (control.isNeutral()) {
                defenders = buildNpcFormation(territory, countFilled(attackers));
            } else {
                defenders = buildFormation(currentHolder, debuff, cycleId); // always the ORIGINAL holder
                collectFielded(fielded, flatten(defenders));
            }

            // Save pre-battle HP for defender recovery between Phase 1 fights
            Map<Long, Integer> preBattleHp = new HashMap<>();
            for (Fighter f : flatten(defenders)) {
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

        // Cansaço: +1 stack p/ todos os escalados deste ciclo (1× — Phase 2 reusa os mesmos membros). [GUERRA_ROSTER]
        applyWarFatigue(fielded, territory, cycleId);

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
                Fighter[][] champFighters = buildFormation(tiebreakerChampion,    0, cycleId);
                Fighter[][] chalFighters  = buildFormation(tiebreakerChallenger, 0, cycleId);

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
            // [GUERRA_GUILDA] a guild passou a controlar um território → fica elegível p/ guerra de guilda
            if (newHolder != null && !newHolder.isEverControlledTerritory()) {
                newHolder.setEverControlledTerritory(true);
                guildRepository.save(newHolder);
            }
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

    /**
     * Batalha por FORMAÇÃO 3×5: cada lane (coluna) é um gauntlet — frente vs frente, o vencedor
     * segue com o HP REAL restante contra o próximo fresco da coluna inimiga. Vence quem leva ≥2
     * das 3 lanes. Combate completo (elementos + ativas). [GUERRA_FORMACAO]
     */
    public BrawlResult guildBrawl(Fighter[][] attackers, Fighter[][] defenders, Kingdom territory) {
        List<String> fullLog = new ArrayList<>();
        fullLog.add("=== ⚔ Guild Battle at " + territory.displayName + " (3×5 formation) ===");

        int atkLanes = 0, defLanes = 0;
        for (int lane = 0; lane < 3; lane++) {
            List<Fighter> aQ = laneQueue(attackers, lane);
            List<Fighter> dQ = laneQueue(defenders, lane);
            fullLog.add("— Lane " + (lane + 1) + " —");

            if (aQ.isEmpty() && dQ.isEmpty()) { fullLog.add("  (empty lane)"); continue; }
            if (aQ.isEmpty()) { defLanes++; fullLog.add("  🛡 Defenders take lane " + (lane + 1) + " (no attackers)"); continue; }
            if (dQ.isEmpty()) { atkLanes++; fullLog.add("  ⚔ Attackers take lane " + (lane + 1) + " (no defenders)"); continue; }

            int ai = 0, di = 0;
            Fighter a = aQ.get(0), d = dQ.get(0);
            while (a != null && d != null) {
                BattleSimulator.BattleOutcome round = battleSimulator.simulate(a.toCombatant(), d.toCombatant(), false);
                List<String> rl = round.log();
                fullLog.addAll(rl.subList(0, rl.size() - 1)); // tira a tag WINNER
                if (round.firstWon()) {
                    a.hp = round.firstHpFinal();  // vencedor segue com o HP REAL restante
                    d.hp = 0;
                    d = (++di < dQ.size()) ? dQ.get(di) : null;
                } else {
                    d.hp = round.secondHpFinal();
                    a.hp = 0;
                    a = (++ai < aQ.size()) ? aQ.get(ai) : null;
                }
            }
            if (a != null) { atkLanes++; fullLog.add("  ⚔ Attackers win lane " + (lane + 1)); }
            else           { defLanes++; fullLog.add("  🛡 Defenders win lane " + (lane + 1)); }
        }

        boolean attackersWon = atkLanes > defLanes;
        fullLog.add(attackersWon
                ? "🏆 Attackers conquered the territory! (" + atkLanes + "-" + defLanes + " lanes)"
                : "🛡 Defenders held their ground! (" + defLanes + "-" + atkLanes + " lanes)");

        return new BrawlResult(attackersWon, fullLog, flatten(attackers), flatten(defenders));
    }

    // ── Fighter building ──────────────────────────────────────────────────────

    /**
     * Monta os lutadores de uma guild para a batalha do ciclo {@code cycleId}, com cap de 15 e cansaço.
     * [GUERRA_ROSTER]
     *
     * <p>Seleção (≤15): os picks explícitos do líder ({@code Player.inWarRoster}) entram primeiro
     * (cortados por poder se >15); as vagas restantes são auto-preenchidas preferindo membros
     * <b>não-cansados</b> e mais fortes — então o time se auto-rotaciona mesmo sem o líder montar roster.
     *
     * <p>Stats = combatStats(base+gear+buffs+postura) × debuff-de-defensor × cansaço (multiplicativos).
     * A guerra agora vale TODOS os stats (não só base) — gear/buffs/postura contam. [POSTURE]
     */
    /** Candidatos elegíveis da guild (warrior + HP>0) com stats completos. [GUERRA_ROSTER/POSTURE] */
    private List<Candidate> eligibleCandidates(Guild guild) {
        List<Player> members = playerRepository.findAllByGuild(guild);
        List<Candidate> candidates = new ArrayList<>();
        for (Player member : members) {
            warriorRepository.findByPlayer(member).ifPresent(w -> {
                int[] cs = statsService.combatStats(member, w);
                int hp = w.getCalculatedHpPercent() * cs[2] / 100; // cs[2] = HP total (base+gear+buff)
                if (hp > 0) candidates.add(new Candidate(member, w, cs, hp));
            });
        }
        return candidates;
    }

    /** Converte um candidato em Fighter com stats escalados (debuff×cansaço) + elemento + ativas. */
    private Fighter toFighter(Candidate c, int debuffPercent, long cycleId) {
        int[] cs = c.stats;
        double mult = (1.0 - debuffPercent / 100.0)                          // debuff de defensor (streak)
                    * (1.0 - c.warrior.fatiguePctForCycle(cycleId) / 100.0); // cansaço de guerra
        return new Fighter(
                c.player.getId(), c.warrior.getName(),
                (int) Math.max(1, cs[0] * mult),   // ATK (gear+buff+postura) × debuff × cansaço
                (int) Math.max(1, cs[1] * mult),   // DEF
                c.hp,
                (int) Math.max(0, cs[3] * mult),   // dex → acerto
                cs[4], cs[5], c.warrior,           // agi (esquiva/velocidade), luk
                c.warrior.getActiveWeaponElement(), c.warrior.getActiveArmorElement(),
                abilityService.activeLoadout(c.warrior));
    }

    /**
     * Monta a formação 3×5 da guild p/ a batalha do ciclo. [GUERRA_FORMACAO]
     * 1) coloca os membros posicionados pelo líder (warLane/warDepth);
     * 2) auto-preenche as células vazias (prefere roster, depois fresco, depois forte), frente primeiro.
     */
    public Fighter[][] buildFormation(Guild guild, int debuffPercent, long cycleId) {
        List<Candidate> candidates = eligibleCandidates(guild);
        Fighter[][] grid = new Fighter[3][5];
        java.util.Set<Long> used = new java.util.HashSet<>();

        // 1) posicionados pelo líder
        for (Candidate c : candidates) {
            int lane = c.player.getWarLane(), depth = c.player.getWarDepth();
            if (c.player.isInWarRoster() && lane >= 0 && lane < 3 && depth >= 0 && depth < 5 && grid[lane][depth] == null) {
                grid[lane][depth] = toFighter(c, debuffPercent, cycleId);
                used.add(c.player.getId());
            }
        }

        // 2) auto-fill (roster primeiro, depois mais fresco, depois mais forte), frente→fundo
        Comparator<Candidate> byPowerDesc = Comparator
                .comparingInt((Candidate c) -> c.hp + c.stats[0] + c.stats[1]).reversed();
        Comparator<Candidate> fillOrder = Comparator
                .comparing((Candidate c) -> !c.player.isInWarRoster())               // roster primeiro
                .thenComparingInt(c -> c.warrior.fatiguePctForCycle(cycleId))         // menos cansado
                .thenComparing(byPowerDesc);
        List<Candidate> pool = candidates.stream()
                .filter(c -> !used.contains(c.player.getId())).sorted(fillOrder).toList();
        int pi = 0;
        for (int depth = 0; depth < 5 && pi < pool.size(); depth++) {
            for (int lane = 0; lane < 3 && pi < pool.size(); lane++) {
                if (grid[lane][depth] == null) grid[lane][depth] = toFighter(pool.get(pi++), debuffPercent, cycleId);
            }
        }
        return grid;
    }

    /** Seleção plana (≤15) da guild — flatten da formação. Compat/seleção simples. [GUERRA_ROSTER] */
    public List<Fighter> buildFighters(Guild guild, int debuffPercent, long cycleId) {
        return flatten(buildFormation(guild, debuffPercent, cycleId));
    }

    /** Formação de NPCs (território neutro) preenchendo {@code count} células, frente→fundo. */
    public Fighter[][] buildNpcFormation(Kingdom territory, int count) {
        Fighter[][] grid = new Fighter[3][5];
        int placed = 0;
        for (int depth = 0; depth < 5 && placed < count; depth++) {
            for (int lane = 0; lane < 3 && placed < count; lane++) {
                grid[lane][depth] = npcFighter(territory, ++placed);
            }
        }
        return grid;
    }

    // ── Helpers de grid ──
    static List<Fighter> flatten(Fighter[][] grid) {
        List<Fighter> out = new ArrayList<>();
        for (Fighter[] lane : grid) for (Fighter f : lane) if (f != null) out.add(f);
        return out;
    }
    static List<Fighter> laneQueue(Fighter[][] grid, int lane) {
        List<Fighter> out = new ArrayList<>();
        for (int depth = 0; depth < 5; depth++) if (grid[lane][depth] != null) out.add(grid[lane][depth]);
        return out;
    }
    static int countFilled(Fighter[][] grid) { return flatten(grid).size(); }

    /** Candidato a lutador (membro elegível + stats de combate completos + HP já calculado). [GUERRA_ROSTER/POSTURE] */
    private record Candidate(Player player, Warrior warrior, int[] stats, int hp) {}

    /** Acumula o cansaço de guerra (1 stack) nos warriors escalados — chamado 1× por ciclo. [GUERRA_ROSTER] */
    private void applyWarFatigue(Map<Long, Warrior> fielded, Kingdom territory, long cycleId) {
        if (fielded.isEmpty()) return;
        for (Warrior w : fielded.values()) {
            w.recordWarParticipation(cycleId);
            warriorRepository.save(w);
        }
        log.info("[TerritoryService] territory={} cycle={} war fatigue applied to {} fighter(s)",
                territory, cycleId, fielded.size());
    }

    /** Indexa por playerId os warriors realmente escalados (ignora NPCs, playerId null). [GUERRA_ROSTER] */
    private void collectFielded(Map<Long, Warrior> fielded, List<Fighter> fighters) {
        for (Fighter f : fighters) {
            if (f.playerId != null && f.warrior != null) fielded.put(f.playerId, f.warrior);
        }
    }

    /** Um NPC defensor (território neutro). Stats base moderados × multiplicadores do reino. */
    private Fighter npcFighter(Kingdom territory, int idx) {
        int baseAtk = 20, baseDef = 15, baseHp = 80;
        return new Fighter(
                null, territory.npcName + " #" + idx,
                (int) (baseAtk * territory.npcAtkMult),
                (int) (baseDef * territory.npcDefMult),
                (int) (baseHp  * territory.npcHpMult),
                15, 3, 5, null); // [REBALANCE] dex=acerto, agi=esquiva baixa, luk
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
        public int atk, def, hp, dex, agi, luk;
        public final Warrior warrior;
        // [GUERRA_FORMACAO] combate completo na guerra: elementos + habilidades ativas.
        public final com.medieval.game.enums.Element weaponElement, armorElement;
        public final java.util.List<BattleSimulator.ActiveAbility> abilities;

        public Fighter(Long playerId, String name, int atk, int def, int hp, int dex, int agi, int luk, Warrior warrior) {
            this(playerId, name, atk, def, hp, dex, agi, luk, warrior, null, null, java.util.List.of());
        }

        public Fighter(Long playerId, String name, int atk, int def, int hp, int dex, int agi, int luk, Warrior warrior,
                       com.medieval.game.enums.Element weaponElement, com.medieval.game.enums.Element armorElement,
                       java.util.List<BattleSimulator.ActiveAbility> abilities) {
            this.playerId  = playerId;
            this.name      = name;
            this.atk       = atk;
            this.def       = def;
            this.hp        = hp;
            this.dex       = dex;
            this.agi  = agi;
            this.luk       = luk;
            this.warrior   = warrior;
            this.weaponElement = weaponElement;
            this.armorElement  = armorElement;
            this.abilities = abilities != null ? abilities : java.util.List.of();
        }

        BattleSimulator.Combatant toCombatant() {
            boolean ranged = warrior != null && warrior.getWarriorClass().isRanged(); // [KITING] NPC = melee
            return BattleSimulator.Combatant.of(name, new int[]{atk, def, hp, dex, agi, luk},
                    weaponElement, armorElement, abilities, ranged);
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
