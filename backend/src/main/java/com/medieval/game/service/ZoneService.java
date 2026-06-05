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
    private final com.medieval.game.repository.SocketedGemRepository gemRepository;
    private final com.medieval.game.repository.ItemAffixRepository   affixRepository;
    private final PlayerRepository         playerRepository;
    private final GatheringService         gatheringService;
    private final BattleSimulator          battleSimulator;
    private final WarriorService           warriorService;
    private final MailService              mailService;
    private final InventoryService         inventoryService;
    private final WarriorStatsService      statsService;
    private final ResourceInventoryRepository resourceRepo; // raid de recursos [PVP_FLAG]

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    private static final int PVP_FLAG_MINUTES   = 60; // exposto por 1h após farmar zona PvP
    private static final int PVP_SHIELD_MINUTES  = 60; // imune por 1h após ser saqueado (1x por ciclo)

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

        // [SEM_TIMER] Farm de zona instantâneo → custa estamina (o timer era o gate; sem ele, a estamina é).
        // ~duração/8 (cabe no teto de 100 mesmo em 12h). Pulado no modo de teste (instant-complete).
        if (!instantComplete) {
            int staminaCost = staminaCostFor(durationMinutes);
            int cur = player.getCalculatedStamina();
            if (cur < staminaCost) {
                log.warn("[ZoneService] player={} REJECTED: stamina {}/{}", player.getId(), cur, staminaCost);
                throw new IllegalStateException("Not enough stamina (" + cur + "/" + staminaCost + "). Rest to recover.");
            }
            player.setCurrentStamina(cur - staminaCost);
            player.setStaminaUpdatedAt(LocalDateTime.now());
            playerRepository.save(player);
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
        activity.setEndsAt(LocalDateTime.now()); // [SEM_TIMER] farm de zona instantâneo
        ZoneActivity saved = activityRepository.save(activity);
        log.info("[ZoneService] player={} action=enter OK id={}", player.getId(), saved.getId());
        return saved;
    }

    /** Estamina de um farm de zona: ~duração/8, mín. 5, teto 100 (cabe mesmo num farm de 12h). [SEM_TIMER] */
    static int staminaCostFor(int durationMinutes) {
        return Math.min(100, Math.max(5, Math.round(durationMinutes / 8f)));
    }

    // ── Coleta da expedição ──

    public record CollectResult(ZoneActivity activity,
                                List<GatheringService.ResourceDrop> drops,
                                boolean wasAttacked, boolean survived,
                                String lostItemName) {}

    @Transactional
    public CollectResult collect(Player playerArg, Long activityId) {
        // Recarrega o player como MANAGED nesta tx. O controller passa um detached; sem isto,
        // salvá-lo mais de uma vez (ex.: vencedor do raid + flag) faria merges de versão velha
        // com auto-flush no meio → OptimisticLockException. [PVP_FLAG]
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        log.info("[ZoneService] player={} action=collect activityId={}", player.getId(), activityId);
        ZoneActivity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedition not found"));

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
                // bronze de combate reduzido (15→8/level) para conter inflação no late-game. [AUDITORIA A3]
                long   bronze = Math.round(hours * mult * w.getLevel() * 8);
                activity.setXpGained(xp);
                activity.setBronzeGained(bronze);
                warriorService.addExperience(w, xp);
                warriorRepository.save(w);
                player.addBronzeAmount(bronze);
                playerRepository.save(player);
            });
        }

        // [PVP_FLAG] Farmou zona PvP/Alto Risco e sobreviveu → fica EXPOSTO por 1h (vira alvo de raid).
        // Trava (snapshot) os itens bag+equipados expostos: enquanto flagged não pode vender/stashar/
        // guardar e são exatamente esses que podem ser saqueados.
        if ((activity.getZone() == Zone.PVP || activity.getZone() == Zone.HIGH_RISK)
                && activity.getStatus() == ZoneActivityStatus.COMPLETED) {
            player.setPvpFlaggedZone(activity.getZone());
            player.setPvpFlaggedUntil(LocalDateTime.now().plusMinutes(PVP_FLAG_MINUTES));
            playerRepository.save(player);
            lockExposedItems(player);
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
                .orElseThrow(() -> new IllegalArgumentException("Expedition not found"));
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
        Zone   zone = activity.getZone();
        Random rng  = java.util.concurrent.ThreadLocalRandom.current();

        Warrior attacker = warriorRepository.findByPlayer(player).orElse(null);
        if (attacker == null) return new PvpResult(false, true, 0, null, List.of());

        int[] atkStats = getWarriorStats(attacker, player);
        int   atkMaxHp = atkStats[2];
        int   atkHp    = attacker.getCalculatedHpPercent() * atkMaxHp / 100;

        // ── PvP: cruza com um player FLAGGED na zona (raid de loot). [PVP_FLAG] ──
        if (zone.encounterChancePerHour > 0 && rng.nextInt(100) < zone.encounterChancePerHour) {
            Player  victim  = findFlaggedOpponent(zone, player, rng);
            Warrior victimW = victim != null ? warriorRepository.findByPlayer(victim).orElse(null) : null;
            if (victimW != null) {
                int[] vStats = getWarriorStats(victimW, victim);
                int   vMaxHp = vStats[2];
                int   vHp    = victimW.getCalculatedHpPercent() * vMaxHp / 100;

                BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
                    attacker.getName(), atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5],
                    victimW.getName(),  vStats[0],   vStats[1],   vHp,   vStats[3],   vStats[4],   vStats[5]); // PvP %HP

                List<String> log = stripWinnerTag(out.log());
                String foe = victimW.getName() + " (player)";
                inventoryService.wearEquippedItems(player);
                inventoryService.wearEquippedItems(victim);
                int vPct = vMaxHp > 0 ? Math.max(0, out.secondHpFinal() * 100 / vMaxHp) : 0;
                victimW.setCurrentHpSnapshot(vPct);
                victimW.setHpUpdatedAt(LocalDateTime.now());
                warriorRepository.save(victimW);

                if (out.firstWon()) {
                    raidVictim(player, attacker.getName(), victim, victimW, zone, log); // loot + escudo + mail
                    persistAttackerHp(attacker, out.firstHpFinal(), atkMaxHp);
                    return new PvpResult(true, true, 0, foe, log); // venceu e saqueou
                } else {
                    long lost = applyDefeatPenalty(player, victim); // você perdeu; a vítima defendeu
                    persistAttackerHp(attacker, 0, atkMaxHp);
                    return new PvpResult(true, false, lost, foe, log);
                }
            }
            // Nenhum flagged → NPC ambusher (preenchimento). [PVP_FLAG]
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng);
        }

        // ── NPC selvagem (PvE) ──
        if (rng.nextInt(100) < zone.npcEncounterChancePerHour) {
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng);
        }

        persistAttackerHp(attacker, atkHp, atkMaxHp);
        return new PvpResult(false, true, 0, null, List.of());
    }

    /** Luta contra um NPC (monstro selvagem ou "ambusher" de preenchimento). */
    private PvpResult fightNpc(Player player, Warrior attacker, int[] atkStats, int atkHp, int atkMaxHp, Zone zone, Random rng) {
        int    npcLevel = attacker.getLevel() + rng.nextInt(4);
        String npcName  = npcName(zone, rng);
        int[]  npcStats = npcStatsByLevel(npcLevel, rng);
        BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
                attacker.getName(), atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5],
                npcName,            npcStats[0], npcStats[1], npcStats[2], npcStats[3], npcStats[4], npcStats[5]);
        List<String> log = stripWinnerTag(out.log());
        inventoryService.wearEquippedItems(player);
        if (!out.firstWon()) {
            long lost = applyDefeatPenalty(player, null);
            persistAttackerHp(attacker, 0, atkMaxHp);
            return new PvpResult(true, false, lost, npcName, log);
        }
        persistAttackerHp(attacker, out.firstHpFinal(), atkMaxHp);
        return new PvpResult(true, true, 0, npcName, log);
    }

    /** Sorteia um player FLAGGED (exposto) na zona, sem escudo, que não seja o atacante. [PVP_FLAG] */
    private Player findFlaggedOpponent(Zone zone, Player exclude, Random rng) {
        List<Player> pool = playerRepository.findFlaggedInZone(zone, LocalDateTime.now(), exclude.getId())
                .stream().filter(p -> !p.isPvpShielded()).toList();
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    /** Atacante venceu o raid → saqueia a vítima (bronze + item + recursos), aplica escudo e mail. [PVP_FLAG] */
    private void raidVictim(Player attacker, String attackerName, Player victim, Warrior victimW, Zone zone, List<String> log) {
        long   bronze     = applyDefeatPenalty(victim, attacker);  // vítima −15%, atacante +metade
        String stolenItem = stealOneItem(attacker, victim);        // 35% chance — bag/equip não-protegidos
        long   stolenRes  = stealResources(attacker, victim);      // ~25% dos recursos da bag

        victimW.clearBuff();
        victim.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES)); // saqueado 1x por ciclo
        victim.clearPvpFlag();
        // raidado → ganha escudo e os itens travados restantes DESTRAVAM (fim do ciclo).
        List<InventoryItem> remaining = inventoryRepository.findAllByPlayer(victim);
        unlockAllItems(remaining);
        inventoryRepository.saveAll(remaining);
        warriorRepository.findByPlayer(victim).ifPresent(w -> {
            long xpLost = Math.max(1, w.expNeededForNextLevel() / 20); // perda menor que morte em quest
            warriorService.loseXp(w, xpLost);
        });
        playerRepository.save(victim);

        String summary = "💰 You raided " + victimW.getName() + "! Stole " + bronze + " bronze"
                + (stolenItem != null ? ", " + stolenItem : "")
                + (stolenRes > 0 ? ", +" + stolenRes + " resources" : "") + ".";
        log.add(summary);
        mailService.sendSystemMail(victim,
            "💀 You were RAIDED by " + attackerName + " in the " + zone.displayName + "! Lost " + bronze + " bronze"
            + (stolenItem != null ? ", " + stolenItem : "") + (stolenRes > 0 ? ", " + stolenRes + " resources" : "")
            + ". You have a protection shield for " + PVP_SHIELD_MINUTES + " min.");
    }

    /** Rouba 1 item TRAVADO (pvpLocked = exposto no snapshot da entrada) e transfere ao atacante. */
    private String stealOneItem(Player attacker, Player victim) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextInt(100) >= 35) return null; // 35% chance
        List<InventoryItem> pool = inventoryRepository.findAllByPlayer(victim).stream()
                .filter(InventoryItem::isPvpLocked).toList();
        if (pool.isEmpty() || inventoryService.bagSpaceLeft(attacker) < 1) return null;
        InventoryItem item = pool.get(rng.nextInt(pool.size()));
        String name = item.getName();
        item.setEquipped(false);
        item.setStashed(false);
        item.setPvpLocked(false);  // ao trocar de dono, destrava
        item.setPlayer(attacker);  // transfere (joias/afixos vão junto via FK)
        inventoryRepository.save(item);
        return name;
    }

    /** Trava (snapshot) os itens bag+equipados EXPOSTOS (não-stashed, não-guarded) ao farmar zona PvP. */
    private void lockExposedItems(Player player) {
        List<InventoryItem> items = inventoryRepository.findAllByPlayer(player);
        for (InventoryItem i : items) {
            i.setPvpLocked(!i.isStashed() && !i.isGuarded()); // re-snapshot: expostos travam, resto destrava
        }
        inventoryRepository.saveAll(items);
    }

    /** Destrava todos os itens do player (fim do flag / pós-raid). [PVP_FLAG] */
    static void unlockAllItems(java.util.List<InventoryItem> items) {
        items.forEach(i -> i.setPvpLocked(false));
    }

    /** Rouba ~25% de cada recurso da bag da vítima e dá ao atacante (clamp na bag dele). */
    private long stealResources(Player attacker, Player victim) {
        long total = 0;
        for (ResourceInventory r : resourceRepo.findAllByPlayerAndStashed(victim, false)) {
            if (r.getQuantity() <= 0) continue;
            if (inventoryService.bagSpaceLeft(attacker) <= 0) break;
            long take  = Math.max(1, r.getQuantity() / 4);
            long added = gatheringService.addResource(attacker, r.getResourceType(), take);
            if (added > 0) {
                r.setQuantity(r.getQuantity() - added);
                resourceRepo.save(r);
                total += added;
            }
        }
        return total;
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

    // ── NPC generation ──

    private static final String[][] NPC_NAMES = {
        // SAFE
        {"Wild Wolf", "Brigand", "Road Plunderer", "Enraged Bear", "Giant Boar"},
        // PVP
        {"Corrupt Mercenary", "Orc Warrior", "Renegade Knight", "Stone Golem", "Mountain Troll"},
        // HIGH_RISK
        {"Lesser Demon", "Dark Lich", "Young Dragon", "Infernal Champion", "Death Specter"},
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
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextDouble() >= 0.10) return null; // 10% chance

        // Itens protegidos pelo Templo não caem
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player)
                .stream().filter(i -> i.isEquipped() && !i.isGuarded()).toList();
        if (equipped.isEmpty()) return null;

        InventoryItem item = equipped.get(rng.nextInt(equipped.size()));
        String name = item.getName();
        gemRepository.deleteAllByItem(item);   // limpa joias (FK) antes de remover o item
        affixRepository.deleteByItem(item);    // limpa afixos (FK) — Itens V2
        inventoryRepository.delete(item);
        return name;
    }
}
