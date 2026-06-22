package com.medieval.game.service;

import com.medieval.game.model.Guild;
import com.medieval.game.model.GuildWar;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.GuildWarRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Guerra de Guilda (7 dias): membros atacam membros da guild inimiga (mesmo prejuízo da zona vermelha);
 * quem tem mais kills leva 25% do gold acumulado da inimiga (pode regredir nível). [GUERRA_GUILDA]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuildWarService {

    private static final int    WAR_DAYS       = 7;
    private static final int    ATTACK_STAMINA = 25;
    private static final double REWARD_PCT     = 0.25; // 25% do lifetimeGold do perdedor

    private final GuildWarRepository  warRepo;
    private final GuildRepository     guildRepository;
    private final PlayerRepository    playerRepository;
    private final WarriorRepository   warriorRepository;
    private final com.medieval.game.repository.WorkSessionRepository workSessionRepository; // [WORK_IDLE]
    private final WarriorStatsService statsService;
    private final BattleSimulator     battleSimulator;
    private final ZoneService         zoneService;   // applyGuildWarRaid (loot/penalty/escudo)
    private final PlayerService       playerService; // consumeStamina
    private final MailService         mailService;

    // ── DTOs ──
    public record EnemyMember(Long playerId, String warriorName, String title, int level, int hpPercent,
                              boolean knockedOut, boolean shielded) {}
    public record WarStatus(boolean atWar, Long warId, String enemyGuildName, Long enemyGuildId,
                            int myKills, int enemyKills, long secondsLeft, List<EnemyMember> enemies) {}
    public record TargetGuild(Long id, String name, int level) {}
    public record AttackResult(boolean won, String opponentName, String loot,
                               int myKills, int enemyKills, List<String> log,
                               List<BattleSimulator.BattleEvent> battleEvents) {} // [BATALHA_ANIMADA] replay 3D

    // ── Declaração ────────────────────────────────────────────────────────────
    @Transactional
    public GuildWar declare(Player leader, Long targetGuildId) {
        log.info("[GuildWarService] player={} action=declareWar target={}", leader.getId(), targetGuildId);
        Guild mine = requireGuild(leader);
        requireLeader(leader, mine);

        Guild target = guildRepository.findById(targetGuildId)
                .orElseThrow(() -> new IllegalArgumentException("Target guild not found."));
        if (target.getId().equals(mine.getId()))
            throw new IllegalArgumentException("You can't declare war on your own guild.");
        if (!mine.isEverControlledTerritory())
            throw new IllegalStateException("Your guild must have controlled a territory at least once.");
        if (!target.isEverControlledTerritory())
            throw new IllegalStateException("The target guild has never controlled a territory.");
        if (currentWar(mine).isPresent())
            throw new IllegalStateException("Your guild is already at war.");
        if (currentWar(target).isPresent())
            throw new IllegalStateException("The target guild is already at war.");

        GuildWar war = new GuildWar();
        war.setGuildA(mine);
        war.setGuildB(target);
        war.setStartedAt(LocalDateTime.now());
        war.setEndsAt(LocalDateTime.now().plusDays(WAR_DAYS));
        GuildWar saved = warRepo.save(war);
        log.info("[GuildWarService] war declared id={} {} vs {}", saved.getId(), mine.getName(), target.getName());
        return saved;
    }

    // ── Ataque (qualquer membro) ────────────────────────────────────────────────
    @Transactional
    public AttackResult attack(Player attacker, Long targetPlayerId) {
        log.info("[GuildWarService] player={} action=warAttack target={}", attacker.getId(), targetPlayerId);
        // Recarrega como entidade MANAGED nesta tx (o param chega detached do controller → evita
        // conflito de versão/optimistic-lock nos vários saves do loot). [GUERRA_GUILDA]
        attacker = playerRepository.findById(attacker.getId()).orElseThrow();
        Guild myGuild = requireGuild(attacker);
        GuildWar war = currentWar(myGuild)
                .orElseThrow(() -> new IllegalStateException("Your guild is not at war."));
        Long enemyGuildId = war.otherGuildId(myGuild.getId());

        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Target player not found."));
        if (target.getId().equals(attacker.getId()))
            throw new IllegalArgumentException("You can't attack yourself.");
        Long targetGuildId = playerRepository.findGuildByPlayerId(target.getId()).map(Guild::getId).orElse(null);
        if (!enemyGuildId.equals(targetGuildId))
            throw new IllegalStateException("That player is not in the enemy guild.");
        if (target.isPvpShielded())
            throw new IllegalStateException("That player is shielded right now.");

        Warrior aw = warriorRepository.findByPlayer(attacker)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));
        Warrior tw = warriorRepository.findByPlayer(target)
                .orElseThrow(() -> new IllegalStateException("Target has no warrior."));
        if (aw.isKnockedOut())  throw new IllegalStateException("Your warrior is unconscious. Heal at the Temple.");
        if (tw.isKnockedOut())  throw new IllegalStateException("That player is already down.");
        WorkService.assertNotBusy(workSessionRepository, attacker); // [WORK_IDLE] não ataca na guerra enquanto trabalha
        if (attacker.getCalculatedStamina() < ATTACK_STAMINA)
            throw new com.medieval.game.config.LocalizedException("error.war_stamina", "Not enough stamina (need {0}).", ATTACK_STAMINA);

        playerService.consumeStamina(attacker, ATTACK_STAMINA);

        // Combate PvP (stats completos dos dois)
        int[] a = statsService.combatStats(attacker, aw);
        int[] d = statsService.combatStats(target, tw);
        int aMax = a[2], dMax = d[2];
        int aHp = aw.getCalculatedHpPercent() * aMax / 100;
        int dHp = tw.getCalculatedHpPercent() * dMax / 100;
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(
                BattleSimulator.Combatant.of(aw.getName(), a, null, null, java.util.List.of(),
                    statsService.isRangedWeaponEquipped(attacker)).withCurrentHp(aHp), // [HP_SPAWN] entra com HP atual; máximo = a[2]
                BattleSimulator.Combatant.of(tw.getName(), d, null, null, java.util.List.of(),
                    statsService.isRangedWeaponEquipped(target)).withCurrentHp(dHp), // [HP_SPAWN] entra com HP atual; máximo = d[2]
                false); // [KITING] PvP, arma ranged (arco) qualquer classe
        boolean attackerWon = out.firstWon();

        // HP final dos dois
        persistHp(aw, out.firstHpFinal(),  aMax);
        persistHp(tw, out.secondHpFinal(), dMax);

        // Kill simétrica: a guild do VENCEDOR ganha +1
        Long winnerGuildId = attackerWon ? myGuild.getId() : enemyGuildId;
        war.incKillFor(winnerGuildId);
        warRepo.save(war);

        // Prejuízo da zona vermelha no PERDEDOR (vencedor saqueia)
        Player winner = attackerWon ? attacker : target;
        Player loser  = attackerWon ? target   : attacker;
        String loot   = zoneService.applyGuildWarRaid(winner, loser);

        // [LEADERBOARDS] kill de guerra: +1 playerKill no vencedor (Slayer) + warKills na guild vencedora.
        winner.setPlayerKills(winner.getPlayerKills() + 1);
        playerRepository.save(winner);
        Guild winnerGuild = attackerWon ? myGuild : guildRepository.findById(enemyGuildId).orElse(null);
        if (winnerGuild != null) {
            winnerGuild.setWarKills(winnerGuild.getWarKills() + 1);
            guildRepository.save(winnerGuild);
        }

        List<String> battleLog = stripWinnerTag(out.log());
        log.info("[GuildWarService] war={} attacker={} won={} loot={}", war.getId(), attacker.getId(), attackerWon, loot);
        return new AttackResult(attackerWon, tw.getName(), loot,
                war.killsFor(myGuild.getId()), war.killsFor(enemyGuildId), battleLog, out.events());
    }

    // ── Status / alvos ──────────────────────────────────────────────────────────
    @Transactional
    public WarStatus statusFor(Player player) {
        Guild mine = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        if (mine == null) return new WarStatus(false, null, null, null, 0, 0, 0, List.of());
        Optional<GuildWar> opt = currentWar(mine);
        if (opt.isEmpty()) return new WarStatus(false, null, null, null, 0, 0, 0, List.of());

        GuildWar war = opt.get();
        Long enemyId = war.otherGuildId(mine.getId());
        Guild enemy = guildRepository.findById(enemyId).orElseThrow();
        long secsLeft = Math.max(0, Duration.between(LocalDateTime.now(), war.getEndsAt()).getSeconds());

        List<Player> enemyMembers = playerRepository.findAllByGuild(enemy);
        // [AUDITORIA_2 A5] warriors dos inimigos em 1 query (em vez de findByPlayer por membro)
        java.util.Map<Long, Warrior> wByP = warriorRepository.findByPlayerIn(enemyMembers).stream()
                .collect(java.util.stream.Collectors.toMap(w -> w.getPlayer().getId(), w -> w, (a, b) -> a));
        List<EnemyMember> enemies = new ArrayList<>();
        for (Player m : enemyMembers) {
            Warrior w = wByP.get(m.getId());
            if (w != null) enemies.add(new EnemyMember(
                    m.getId(), w.getName(), AchievementService.titleString(m), // [TITULOS]
                    w.getLevel(), w.getCalculatedHpPercent(),
                    w.isKnockedOut(), m.isPvpShielded()));
        }
        return new WarStatus(true, war.getId(), enemy.getName(), enemyId,
                war.killsFor(mine.getId()), war.killsFor(enemyId), secsLeft, enemies);
    }

    /** Guildas que dá pra declarar guerra (já controlaram território, não estão em guerra, não a minha). */
    @Transactional
    public List<TargetGuild> eligibleTargets(Player player) {
        Guild mine = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        if (mine == null || !mine.isEverControlledTerritory() || currentWar(mine).isPresent()) return List.of();
        List<TargetGuild> out = new ArrayList<>();
        for (Guild g : guildRepository.findAll()) {
            if (g.getId().equals(mine.getId())) continue;
            if (!g.isEverControlledTerritory()) continue;
            if (currentWar(g).isPresent()) continue;
            out.add(new TargetGuild(g.getId(), g.getName(), g.getLevel()));
        }
        return out;
    }

    // ── Resolução ───────────────────────────────────────────────────────────────
    /** Guerra ativa da guild; se já acabou, resolve na hora (lazy) e retorna vazio. */
    @Transactional
    public Optional<GuildWar> currentWar(Guild guild) {
        Optional<GuildWar> opt = warRepo.findActiveByGuild(guild);
        if (opt.isPresent() && opt.get().isOver()) {
            resolve(opt.get());
            return Optional.empty();
        }
        return opt;
    }

    @Transactional
    public void resolve(GuildWar war) {
        // [VARREDURA] Claim ATÔMICO (ACTIVE→RESOLVED via UPDATE guardado): só a tx que ganha (rowcount==1)
        // aplica a recompensa. Substitui o `if status != ACTIVE` (check-then-act não-atômico) que deixava
        // 2 requests concorrentes / 2 instâncias resolverem a MESMA guerra e roubarem o gold 2×. O UPDATE
        // serializa via lock de linha: o perdedor bloqueia, re-lê RESOLVED e sai com rowcount 0.
        if (warRepo.claimForResolution(war.getId(), GuildWar.Status.ACTIVE, GuildWar.Status.RESOLVED) == 0) return;
        war.setStatus(GuildWar.Status.RESOLVED); // alinha a entidade em memória com o que o claim já persistiu
        Guild gA = guildRepository.findById(war.getGuildA().getId()).orElseThrow();
        Guild gB = guildRepository.findById(war.getGuildB().getId()).orElseThrow();

        Guild winner = null, loser = null;
        if (war.getKillsA() > war.getKillsB())      { winner = gA; loser = gB; }
        else if (war.getKillsB() > war.getKillsA()) { winner = gB; loser = gA; }

        if (winner != null) {
            long stolen = Math.round(loser.getLifetimeGold() * REWARD_PCT);
            // Perdedor: tira do acumulado E do tesouro; nível pode CAIR (set direto). [GUERRA_GUILDA]
            loser.setLifetimeGold(Math.max(0, loser.getLifetimeGold() - stolen));
            loser.setGold(Math.max(0, loser.getGold() - stolen));
            loser.setLevel(Guild.levelForGold(loser.getLifetimeGold()));
            // Vencedor: ganha nos dois; nível pode SUBIR (monotônico).
            winner.setLifetimeGold(winner.getLifetimeGold() + stolen);
            winner.setGold(winner.getGold() + stolen);
            winner.recomputeLevel();
            guildRepository.save(loser);
            guildRepository.save(winner);
            war.setWinnerGuildId(winner.getId());
            log.info("[GuildWarService] war={} RESOLVED winner={} stolen={} loserNewLevel={}",
                    war.getId(), winner.getName(), stolen, loser.getLevel());
            mailLeader(winner, "🏆 Your guild WON the war vs " + loser.getName() + "! Looted " + stolen + " gold.");
            mailLeader(loser,  "💀 Your guild LOST the war vs " + winner.getName() + ". Lost " + stolen + " gold.");
        } else {
            log.info("[GuildWarService] war={} RESOLVED draw ({}–{})", war.getId(), war.getKillsA(), war.getKillsB());
            mailLeader(gA, "⚖ Your guild war vs " + gB.getName() + " ended in a draw.");
            mailLeader(gB, "⚖ Your guild war vs " + gA.getName() + " ended in a draw.");
        }
        war.setStatus(GuildWar.Status.RESOLVED);
        warRepo.save(war);
    }

    /** Resolve todas as guerras vencidas (scheduler). [GUERRA_GUILDA] */
    @Transactional
    public void resolveDueWars() {
        List<GuildWar> due = warRepo.findActiveDue(LocalDateTime.now());
        for (GuildWar w : due) {
            try { resolve(w); } catch (Exception e) { log.error("Error resolving guild war {}: {}", w.getId(), e.getMessage(), e); }
        }
        if (!due.isEmpty()) log.info("[GuildWarService] resolved {} due war(s)", due.size());
    }

    // ── Helpers ──
    private void persistHp(Warrior w, int hpFinal, int maxHp) {
        int pct = maxHp > 0 ? Math.max(0, Math.min(100, hpFinal * 100 / maxHp)) : 0;
        w.setCurrentHpSnapshot(pct);
        w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
    }

    private void mailLeader(Guild guild, String msg) {
        playerRepository.findById(guild.getLeaderId()).ifPresent(l -> mailService.sendSystemMail(l, msg));
    }

    private List<String> stripWinnerTag(List<String> log) {
        if (log.isEmpty()) return log;
        return log.subList(0, log.size() - 1); // remove a tag WINNER interna
    }

    private Guild requireGuild(Player player) {
        return playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("You do not belong to any guild."));
    }
    private void requireLeader(Player player, Guild guild) {
        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Only the leader can declare war.");
    }
}
