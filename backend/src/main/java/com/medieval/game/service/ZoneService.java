package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZoneService {

    private final ZoneActivityRepository   activityRepository;
    private final WarriorRepository        warriorRepository;
    private final InventoryItemRepository  inventoryRepository;
    private final PlayerRepository         playerRepository;
    private final GatheringService         gatheringService;
    private final BattleSimulator          battleSimulator;
    private final WarriorService           warriorService;
    private final MailService              mailService;
    private final InventoryService         inventoryService;
    private final WarriorStatsService      statsService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // ── Entrar na zona ──

    @Transactional
    public ZoneActivity enter(Player player, Zone zone, ActivityRole role,
                              SkillType skillType, int durationMinutes) {
        log.info("[ZoneService] player={} action=enter zone={} role={} skill={} duration={}", player.getId(), zone, role, skillType, durationMinutes);

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isOnMission()) {
            log.warn("[ZoneService] player={} REJECTED: warrior is already busy", player.getId());
            throw new IllegalStateException("Your warrior is already busy");
        }
        if (warrior.isKnockedOut()) {
            log.warn("[ZoneService] player={} REJECTED: warrior is unconscious", player.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        // Auto-cancel orphaned expedition: IN_PROGRESS but warrior is already free
        // This happens when freeIfStuck() was called before the ZoneActivity cancel fix was deployed
        activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS)
                .ifPresent(orphan -> {
                    if (!warrior.isOnMission()) {
                        // Warrior is free but expedition is still marked IN_PROGRESS — cancel it
                        orphan.setStatus(ZoneActivityStatus.CANCELLED);
                        activityRepository.save(orphan);
                    } else {
                        throw new IllegalStateException("You are already on an expedition");
                    }
                });

        if (warrior.getLevel() < zone.minLevel) {
            log.warn("[ZoneService] player={} REJECTED: level {} too low for zone {} (required {})", player.getId(), warrior.getLevel(), zone, zone.minLevel);
            throw new IllegalStateException("Level too low. Required: " + zone.minLevel);
        }

        if (durationMinutes < 30 || durationMinutes > 720) {
            log.warn("[ZoneService] player={} REJECTED: invalid duration={}", player.getId(), durationMinutes);
            throw new IllegalArgumentException("Duration must be between 30 min and 12h");
        }

        // Valida skill para gatherer
        if (role == ActivityRole.GATHERING && skillType == null) {
            log.warn("[ZoneService] player={} REJECTED: gathering requires a skill type", player.getId());
            throw new IllegalArgumentException("Choose a skill to gather with");
        }

        // COMBAT só pode entrar em PVP e HIGH_RISK (SAFE = Training Hall)
        if (role == ActivityRole.COMBAT && zone == Zone.SAFE) {
            log.warn("[ZoneService] player={} REJECTED: combat role not allowed in SAFE zone", player.getId());
            throw new IllegalArgumentException("Use the Training Hall for safe training. Combat zones start at Campo de Batalha (Lv.10+).");
        }

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        ZoneActivity activity = new ZoneActivity();
        activity.setPlayer(player);
        activity.setZone(zone);
        activity.setRole(role);
        activity.setSkillType(skillType);
        activity.setDurationMinutes(durationMinutes);
        activity.setStartedAt(LocalDateTime.now());
        activity.setEndsAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusMinutes(durationMinutes));
        ZoneActivity saved = activityRepository.save(activity);
        log.info("[ZoneService] player={} action=enter OK id={}", player.getId(), saved.getId());
        return saved;
    }

    // ── Coleta da expedição ──

    public record CollectResult(ZoneActivity activity,
                                List<GatheringService.ResourceDrop> drops,
                                boolean wasAttacked, boolean survived,
                                String lostItemName) {}

    @Transactional
    public CollectResult collect(Player player, Long activityId) {
        log.info("[ZoneService] player={} action=collect activityId={}", player.getId(), activityId);
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedição não encontrada"));

        if (!activity.getPlayer().getId().equals(player.getId())) {
            log.warn("[ZoneService] player={} REJECTED: activity {} does not belong to this player", player.getId(), activityId);
            throw new IllegalStateException("This expedition does not belong to you");
        }

        if (activity.getStatus() == ZoneActivityStatus.COMPLETED ||
            activity.getStatus() == ZoneActivityStatus.DEFEATED) {
            log.warn("[ZoneService] player={} REJECTED: activity {} already finished (status={})", player.getId(), activityId, activity.getStatus());
            throw new IllegalStateException("Expedition already finished");
        }

        if (!activity.isReadyToCollect() && activity.getStatus() == ZoneActivityStatus.IN_PROGRESS) {
            long secs = java.time.Duration.between(
                    LocalDateTime.now(), activity.getEndsAt()).getSeconds();
            log.warn("[ZoneService] player={} REJECTED: activity {} still in progress, {}s remaining", player.getId(), activityId, secs);
            throw new IllegalStateException("Expedition still in progress. " + secs + "s");
        }

        List<GatheringService.ResourceDrop> drops = new ArrayList<>();
        boolean wasAttacked = false;
        boolean survived    = true;
        String  lostItem    = null;

        // ── Zona Segura: só coleta ──
        if (activity.getZone() == Zone.SAFE || activity.getRole() == ActivityRole.HUNTING) {
            drops = resolveGathering(player, activity);
            activity.setStatus(ZoneActivityStatus.COMPLETED);

        } else {
            // ── PvP/Alto Risco: verifica encontros ──
            PvpResult pvp = resolveEncounters(player, activity);
            wasAttacked = pvp.wasAttacked();
            survived    = pvp.survived();

            if (!survived) {
                // Derrota — perde parte dos recursos e stamina vai a 0
                activity.setStatus(ZoneActivityStatus.DEFEATED);
                activity.setAttacked(true);
                activity.setSurvivedAttack(false);
                activity.setBronzeLost(pvp.bronzeLost());
                activity.setAttackerWarriorName(pvp.attackerName());
                activity.setBattleLog(String.join("\n", pvp.battleLog()));
                activity.setResolvedAt(LocalDateTime.now());

                // HP = 0 + perde buff; stamina = 0
                player.setCurrentStamina(0);
                player.setStaminaUpdatedAt(LocalDateTime.now());
                warriorRepository.findByPlayer(player).ifPresent(w -> {
                    w.applyDamagePercent(100);
                    w.clearBuff();
                    // XP loss: 10% of XP required for current level (Tibia-style, can drop level)
                    long xpLost = Math.max(1, w.expNeededForNextLevel() / 10);
                    activity.setXpGained(-xpLost); // negative = lost XP, shown in modal
                    warriorService.loseXp(w, xpLost);
                    log.info("[ZoneService] player={} PvP death XP loss={}", player.getId(), xpLost);
                });

                // Alto Risco: 10% de perder item equipado
                if (activity.getZone() == Zone.HIGH_RISK) {
                    lostItem = maybeDropEquippedItem(player);
                    activity.setLostEquippedItem(lostItem);
                }

                playerRepository.save(player);
            } else {
                // Sobreviveu (com ou sem ataque): coleta normalmente
                drops = resolveGathering(player, activity);
                activity.setStatus(ZoneActivityStatus.COMPLETED);
                if (wasAttacked) {
                    activity.setAttacked(true);
                    activity.setSurvivedAttack(true);
                    activity.setBattleLog(String.join("\n", pvp.battleLog()));
                    activity.setAttackerWarriorName(pvp.attackerName());
                    activity.setResolvedAt(LocalDateTime.now());
                }
            }
        }

        // Aplica drops ao inventário
        for (GatheringService.ResourceDrop drop : drops) {
            gatheringService.addResource(player, drop.type(), drop.quantity());
        }

        // Aplica XP de skill (se gatherer)
        if (activity.getRole() == ActivityRole.GATHERING && activity.getSkillType() != null) {
            SkillLevel skill = gatheringService.getOrCreateSkill(player, activity.getSkillType());
            long xp = (long)(activity.getXpGained());
            gatheringService.addSkillXp(skill, (int) xp);
        }

        // Aplica recompensa COMBAT: XP de guerreiro + bronze (só se sobreviveu)
        if (activity.getRole() == ActivityRole.COMBAT && activity.getStatus() == ZoneActivityStatus.COMPLETED) {
            warriorRepository.findByPlayer(player).ifPresent(w -> {
                double hours  = activity.getDurationMinutes() / 60.0;
                double mult   = activity.getZone().multiplier;
                long   xp     = Math.round(hours * mult * w.getLevel() * 20);
                long   bronze = Math.round(hours * mult * w.getLevel() * 15);
                activity.setXpGained(xp);
                activity.setBronzeGained(bronze);
                warriorService.addExperience(w, xp);
                warriorRepository.save(w);
                player.addBronzeAmount(bronze);
                playerRepository.save(player);
            });
        }

        // Libera o guerreiro
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        activityRepository.save(activity);
        log.info("[ZoneService] player={} action=collect OK activityId={} survived={} drops={}", player.getId(), activityId, survived, drops.size());
        return new CollectResult(activity, drops, wasAttacked, survived, lostItem);
    }

    // ── Abandona expedição ──

    @Transactional
    public void cancel(Player player, Long activityId) {
        log.info("[ZoneService] player={} action=cancel activityId={}", player.getId(), activityId);
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedição não encontrada"));
        if (!activity.getPlayer().getId().equals(player.getId())) {
            log.warn("[ZoneService] player={} REJECTED: activity {} does not belong to this player", player.getId(), activityId);
            throw new IllegalStateException("Not yours");
        }
        if (activity.getStatus() != ZoneActivityStatus.IN_PROGRESS) {
            log.warn("[ZoneService] player={} REJECTED: activity {} already finished (status={})", player.getId(), activityId, activity.getStatus());
            throw new IllegalStateException("Already finished");
        }

        activity.setStatus(ZoneActivityStatus.CANCELLED);
        activityRepository.save(activity);

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });
        log.info("[ZoneService] player={} action=cancel OK activityId={}", player.getId(), activityId);
    }

    // ── Verifica expedição ativa ──

    public Optional<ZoneActivity> getCurrentActivity(Player player) {
        return activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS);
    }

    /** Player viu o aviso de emboscada e escolheu CONTINUAR — limpa o flag pendente. */
    @Transactional
    public void acknowledgeAmbush(Player player, Long activityId) {
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedição não encontrada"));
        if (!activity.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Not yours");
        activity.setAmbushPending(false);
        activityRepository.save(activity);
        log.info("[ZoneService] player={} action=acknowledgeAmbush activityId={} — continuing expedition", player.getId(), activityId);
    }

    // ── Histórico ──

    public List<ZoneActivity> getHistory(Player player) {
        return activityRepository.findAllByPlayerAndStatusInOrderByStartedAtDesc(
                player, List.of(ZoneActivityStatus.COMPLETED,
                                ZoneActivityStatus.DEFEATED,
                                ZoneActivityStatus.CANCELLED));
    }

    // ── Privados: cálculo de recursos ──

    private List<GatheringService.ResourceDrop> resolveGathering(Player player, ZoneActivity activity) {
        if (activity.getRole() != ActivityRole.GATHERING || activity.getSkillType() == null) {
            return List.of();
        }

        SkillLevel skill = gatheringService.getOrCreateSkill(player, activity.getSkillType());
        double mult = activity.getZone().multiplier;
        int    durationMin = activity.getDurationMinutes();
        int    xpBase = durationMin * (skill.getLevel() / 10 + 2);

        // Simula múltiplas rodadas de coleta
        List<GatheringService.ResourceDrop> allDrops = new ArrayList<>();
        int rounds = Math.max(1, durationMin / 10); // 1 rodada a cada 10 min
        for (int i = 0; i < rounds; i++) {
            allDrops.addAll(gatheringService.collectGatheringDropsOnly(activity.getSkillType(),
                    skill.getLevel(), 10));
        }

        // Aplica multiplicador de zona
        List<GatheringService.ResourceDrop> scaled = new ArrayList<>();
        for (GatheringService.ResourceDrop d : allDrops) {
            scaled.add(new GatheringService.ResourceDrop(d.type(),
                    Math.max(1, Math.round(d.quantity() * mult))));
        }

        long xp = Math.round(xpBase * mult);
        activity.setXpGained(xp);
        return scaled;
    }

    // ── Privados: resolução de PvP ──

    record PvpResult(boolean wasAttacked, boolean survived, long bronzeLost,
                     String attackerName, List<String> battleLog) {}

    /**
     * Resolve all encounters for the collecting player ("attacker"). Each hour rolls
     * PvP (ambush another in-progress player) and/or NPC. The attacker's HP carries
     * between encounters. Returns the attacker's overall outcome.
     */
    private PvpResult resolveEncounters(Player player, ZoneActivity activity) {
        Zone   zone  = activity.getZone();
        int    hours = Math.max(1, activity.getDurationMinutes() / 60);
        Random rng   = new Random();

        Warrior attacker = warriorRepository.findByPlayer(player).orElse(null);
        if (attacker == null) return new PvpResult(false, true, 0, null, List.of());

        int[] atkStats = getWarriorStats(attacker, player);
        int   atkMaxHp = atkStats[2];
        int   atkHp    = attacker.getCalculatedHpPercent() * atkMaxHp / 100; // current absolute HP

        boolean anyEncounter = false;
        List<String> lastLog  = List.of();
        String       lastFoe  = null;

        for (int h = 0; h < hours; h++) {

            // ── PvP: ambush another in-progress player ──
            if (zone.encounterChancePerHour > 0 && rng.nextInt(100) < zone.encounterChancePerHour) {
                ZoneActivity targetAct = findOpponentActivity(zone, player, rng);
                if (targetAct != null) {
                    Warrior targetWarrior = warriorRepository.findByPlayer(targetAct.getPlayer()).orElse(null);
                    // anti-farm: target escapes with 5% per past survived ambush
                    boolean escaped = rng.nextInt(100) < 5 * targetAct.getAmbushCount();
                    if (targetWarrior != null && !escaped) {
                        anyEncounter = true;
                        Player  targetPlayer = targetAct.getPlayer();
                        int[]   tgtStats = getWarriorStats(targetWarrior, targetPlayer);
                        int     tgtMaxHp = tgtStats[2];
                        int     tgtHp    = targetWarrior.getCalculatedHpPercent() * tgtMaxHp / 100;

                        BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
                            attacker.getName(),      atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5],
                            targetWarrior.getName(), tgtStats[0], tgtStats[1], tgtHp, tgtStats[3], tgtStats[4], tgtStats[5]);

                        List<String> log = stripWinnerTag(out.log());
                        atkHp = out.firstHpFinal();
                        lastLog = log;
                        lastFoe = targetWarrior.getName() + " (jogador)";

                        // Desgaste de equipamento: ambos lutaram
                        inventoryService.wearEquippedItems(player);
                        inventoryService.wearEquippedItems(targetPlayer);

                        // Persist target HP from the fight
                        int tgtPct = tgtMaxHp > 0 ? Math.max(0, out.secondHpFinal() * 100 / tgtMaxHp) : 0;
                        targetWarrior.setCurrentHpSnapshot(tgtPct);
                        targetWarrior.setHpUpdatedAt(LocalDateTime.now());
                        warriorRepository.save(targetWarrior);

                        if (out.firstWon()) {
                            // Attacker won → robs target, target dies
                            long stolen = applyDefeatPenalty(targetPlayer, player);
                            targetWarrior.clearBuff();
                            warriorRepository.save(targetWarrior);
                            markTargetAmbushed(targetAct, attacker.getName(), stolen, log, true, zone);
                            // attacker survives, continue collecting
                        } else {
                            // Attacker lost → dies; target defended and robs the attacker
                            long stolen = applyDefeatPenalty(player, targetPlayer);
                            markTargetAmbushed(targetAct, attacker.getName(), 0, log, false, zone);
                            persistAttackerHp(attacker, 0, atkMaxHp);
                            return new PvpResult(true, false, stolen, lastFoe, log);
                        }
                    }
                }
            }

            // ── NPC encounter (PvE) ──
            if (rng.nextInt(100) < zone.npcEncounterChancePerHour) {
                anyEncounter = true;
                int    npcLevel = attacker.getLevel() + rng.nextInt(4);
                String npcName  = npcName(zone, rng);
                int[]  npcStats = npcStatsByLevel(npcLevel, rng);

                BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
                        attacker.getName(), atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5],
                        npcName,            npcStats[0], npcStats[1], npcStats[2], npcStats[3], npcStats[4], npcStats[5]);

                List<String> log = stripWinnerTag(out.log());
                atkHp = out.firstHpFinal();
                lastLog = log;
                lastFoe = npcName;

                // Desgaste de equipamento por lutar contra o NPC
                inventoryService.wearEquippedItems(player);

                if (!out.firstWon()) {
                    long bronzeLost = applyDefeatPenalty(player, null);
                    persistAttackerHp(attacker, 0, atkMaxHp);
                    return new PvpResult(true, false, bronzeLost, npcName, log);
                }
            }
        }

        // Survived all encounters — persist reduced HP
        persistAttackerHp(attacker, atkHp, atkMaxHp);
        return new PvpResult(anyEncounter, true, 0, lastFoe, lastLog);
    }

    /** Sorteia uma expedição IN_PROGRESS de outro player na mesma zona (pool de alvos). */
    private ZoneActivity findOpponentActivity(Zone zone, Player exclude, Random rng) {
        List<ZoneActivity> pool = activityRepository.findAllByZoneAndStatusAndPlayerNot(
                zone, ZoneActivityStatus.IN_PROGRESS, exclude);
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    /** Aplica o resultado da emboscada no alvo (persistente) e notifica por mail. */
    private void markTargetAmbushed(ZoneActivity targetAct, String attackerName, long bronzeLost,
                                    List<String> log, boolean targetDied, Zone zone) {
        Player targetPlayer = targetAct.getPlayer();
        targetAct.setLastAmbusherName(attackerName);
        targetAct.setLastAmbushBronzeLost(bronzeLost);
        targetAct.setLastAmbushLog(String.join("\n", log));
        targetAct.setAttacked(true);
        targetAct.setAttackerWarriorName(attackerName);

        if (targetDied) {
            targetAct.setStatus(ZoneActivityStatus.DEFEATED);
            targetAct.setSurvivedAttack(false);
            targetAct.setResolvedAt(LocalDateTime.now());
            // Death penalties for the target (it is not collecting, so apply here)
            warriorRepository.findByPlayer(targetPlayer).ifPresent(w -> {
                long xpLost = Math.max(1, w.expNeededForNextLevel() / 10);
                warriorService.loseXp(w, xpLost);
            });
            String itemMsg = "";
            if (zone == Zone.HIGH_RISK) {
                String lost = maybeDropEquippedItem(targetPlayer);
                if (lost != null) { targetAct.setLastAmbushItemLost(lost); targetAct.setLostEquippedItem(lost);
                    itemMsg = " Item perdido: " + lost + "."; }
            }
            targetPlayer.setCurrentStamina(0);
            targetPlayer.setStaminaUpdatedAt(LocalDateTime.now());
            playerRepository.save(targetPlayer);
            mailService.sendSystemMail(targetPlayer,
                "💀 Você foi emboscado e MORTO por " + attackerName + " na " + zone.displayName + "! "
                + "Perdeu " + bronzeLost + " bronze." + itemMsg + " HP: 0%. Sua expedição foi encerrada.");
        } else {
            // Survived: anti-farm counter + pending dialog
            targetAct.setAmbushCount(targetAct.getAmbushCount() + 1);
            targetAct.setAmbushPending(true);
            targetAct.setSurvivedAttack(true);
            int hpPct = warriorRepository.findByPlayer(targetPlayer)
                    .map(Warrior::getCalculatedHpPercent).orElse(0);
            mailService.sendSystemMail(targetPlayer,
                "⚔ Você foi emboscado por " + attackerName + " na " + zone.displayName + " e SOBREVIVEU! "
                + "HP: " + hpPct + "%. Recuperou bronze do atacante. Entre no jogo para continuar ou recolher.");
        }
        activityRepository.save(targetAct);
    }

    /** Persiste o HP absoluto do atacante como % no snapshot. */
    private void persistAttackerHp(Warrior attacker, int hpAbs, int maxHp) {
        int pct = maxHp > 0 ? Math.max(0, Math.min(100, hpAbs * 100 / maxHp)) : 0;
        attacker.setCurrentHpSnapshot(pct);
        attacker.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(attacker);
    }

    /** Remove a tag interna WINNER: do final do log (cópia, não muta o original). */
    private List<String> stripWinnerTag(List<String> log) {
        if (log.isEmpty()) return new ArrayList<>();
        List<String> copy = new ArrayList<>(log);
        copy.remove(copy.size() - 1);
        return copy;
    }

    /** Aplica penalidade de derrota: perde 15% bronze; vencedor (se player) ganha 50% do perdido */
    private long applyDefeatPenalty(Player loser, Player winner) {
        long bronzeLost = Math.round(loser.totalBronze() * 0.15);
        if (bronzeLost > 0) {
            loser.addBronzeAmount(-bronzeLost);
            playerRepository.save(loser);
            if (winner != null) {
                winner.addBronzeAmount(bronzeLost / 2);
                playerRepository.save(winner);
            }
        }
        return bronzeLost;
    }

    // ── Geração de NPCs ──

    private static final String[][] NPC_NAMES = {
        // SAFE
        {"Lobo Selvagem", "Bandoleiro", "Saqueador da Estrada", "Urso Enfurecido", "Javali Gigante"},
        // PVP
        {"Mercenário Corrupto", "Orc Guerreiro", "Cavaleiro Renegado", "Golem de Pedra", "Troll da Montanha"},
        // HIGH_RISK
        {"Demônio Menor", "Lich das Trevas", "Dragão Jovem", "Campeão Infernal", "Espectro da Morte"},
    };

    private String npcName(Zone zone, Random rng) {
        String[] pool = switch (zone) {
            case SAFE      -> NPC_NAMES[0];
            case PVP       -> NPC_NAMES[1];
            case HIGH_RISK -> NPC_NAMES[2];
        };
        return pool[rng.nextInt(pool.length)];
    }

    /** Stats do NPC baseados no nível (até +3 do guerreiro) */
    /** Returns [atk, def, hp, dex, strBonus, luk] for NPCs in d20 system. */
    private int[] npcStatsByLevel(int level, Random rng) {
        int atk      = 4 + level * 3 + rng.nextInt(4);
        int def      = 2 + level * 2 + rng.nextInt(3);
        int hp       = 70 + level * 20 + rng.nextInt(30);
        int dex      = Math.min(5 + level / 2, 20); // AC = 10+dex, cap ~30
        int strBonus = Math.min(level / 10, 3);
        int luk      = Math.min(level / 3, 10);
        return new int[]{atk, def, hp, dex, strBonus, luk};
    }

    /** Returns [atk, def, hp, dex, strBonus, luk] for d20 simulate(). [AUDITORIA A1/A9] */
    private int[] getWarriorStats(Warrior w, Player player) {
        return statsService.combatStats(player, w);
    }

    private String maybeDropEquippedItem(Player player) {
        Random rng = new Random();
        if (rng.nextDouble() >= 0.10) return null; // 10% chance

        // Itens protegidos pelo Templo não caem
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player)
                .stream().filter(i -> i.isEquipped() && !i.isGuarded()).toList();
        if (equipped.isEmpty()) return null;

        InventoryItem item = equipped.get(rng.nextInt(equipped.size()));
        String name = item.getName();
        inventoryRepository.delete(item);
        return name;
    }
}
