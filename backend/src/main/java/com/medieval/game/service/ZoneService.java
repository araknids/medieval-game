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
    private final PlayerService            playerService;
    private final GatheringService         gatheringService;
    private final BattleSimulator          battleSimulator;
    private final WarriorService           warriorService;
    private final MailService              mailService;
    private final InventoryService         inventoryService;
    private final WarriorStatsService      statsService;
    private final ResourceInventoryRepository resourceRepo; // raid de recursos [PVP_FLAG]
    private final AbilityService           abilityService; // ativas no combate [HABILIDADES]

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // [ZONA_CHEFE] Chefe errante ligado em dev/prod; desligado nos testes p/ collect determinístico.
    @Value("${app.zone.boss-enabled:true}")
    private boolean bossEnabled;

    private static final int PVP_FLAG_MINUTES   = 60; // exposto por 1h após farmar zona PvP
    private static final int PVP_SHIELD_MINUTES  = 60; // imune por 1h após ser saqueado (1x por ciclo)
    private static final int PVP_LEVEL_BAND      = 10; // só ataca/é atacado dentro de ±10 níveis

    // ── Entrar na zona ──

    @Transactional
    public ZoneActivity enter(Player player, Zone zone, ActivityRole role,
                              SkillType skillType, int durationMinutes) {
        return enter(player, zone, role, skillType, durationMinutes, null, null);
    }

    @Transactional
    public ZoneActivity enter(Player player, Zone zone, ActivityRole role,
                              SkillType skillType, int durationMinutes, com.medieval.game.enums.Kingdom kingdom) {
        return enter(player, zone, role, skillType, durationMinutes, kingdom, null);
    }

    @Transactional
    public ZoneActivity enter(Player player, Zone zone, ActivityRole role,
                              SkillType skillType, int durationMinutes, com.medieval.game.enums.Kingdom kingdom,
                              com.medieval.game.enums.Element element) {
        log.info("[ZoneService] player={} action=enter zone={} role={} skill={} duration={} kingdom={} element={}", player.getId(), zone, role, skillType, durationMinutes, kingdom, element);

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isKnockedOut()) {
            log.warn("[ZoneService] player={} REJECTED: warrior is unconscious", player.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        // [SEM_TIMER] Auto-cancela expedição pendurada (IN_PROGRESS não coletada): tudo é instantâneo,
        // então uma atividade antiga é só lixo — cancela e segue pra nova (uma expedição ativa por vez).
        activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS)
                .ifPresent(orphan -> {
                    orphan.setStatus(ZoneActivityStatus.CANCELLED);
                    activityRepository.save(orphan);
                });

        if (warrior.getLevel() < zone.minLevel) {
            log.warn("[ZoneService] player={} REJECTED: level {} too low for zone {} (required {})", player.getId(), warrior.getLevel(), zone, zone.minLevel);
            throw new IllegalStateException("Level too low. Required: " + zone.minLevel);
        }

        if (durationMinutes < 5 || durationMinutes > 720) { // coleta usa chunk curto (~20min); combate maior
            log.warn("[ZoneService] player={} REJECTED: invalid duration={}", player.getId(), durationMinutes);
            throw new IllegalArgumentException("Duration must be between 5 min and 12h");
        }

        // Valida skill para gatherer
        if (role == ActivityRole.GATHERING && skillType == null) {
            log.warn("[ZoneService] player={} REJECTED: gathering requires a skill type", player.getId());
            throw new IllegalArgumentException("Choose a skill to gather with");
        }

        // [FORTALEZA_ZONAS] COMBAT agora tem os 3 tiers (🟢/🟡/🔴) de caçada, igual aos reinos de coleta.
        // O Training Hall continua à parte (XP por bronze) — a verde aqui é caçar mob fraco, sem PvP.

        // [SEM_TIMER] Farm de zona instantâneo → custa estamina (o timer era o gate; sem ele, a estamina é).
        // ~duração/8 (cabe no teto de 100 mesmo em 12h). Pulado no modo de teste (instant-complete).
        if (!instantComplete) {
            int staminaCost = playerService.discountStamina(player, staminaCostFor(role, durationMinutes)); // [ESTABULO]
            int cur = player.getCalculatedStamina();
            if (cur < staminaCost) {
                log.warn("[ZoneService] player={} REJECTED: stamina {}/{}", player.getId(), cur, staminaCost);
                throw new IllegalStateException("Not enough stamina (" + cur + "/" + staminaCost + "). Rest to recover.");
            }
            player.setCurrentStamina(cur - staminaCost);
            player.setStaminaUpdatedAt(LocalDateTime.now());
            playerRepository.save(player);
        }

        ZoneActivity activity = new ZoneActivity();
        activity.setPlayer(player);
        activity.setZone(zone);
        activity.setRole(role);
        activity.setSkillType(skillType);
        activity.setKingdom(kingdom);
        activity.setElement(element); // [ELEMENTOS] área de elemento (essência + elemento dos monstros)
        activity.setDurationMinutes(durationMinutes);
        activity.setStartedAt(LocalDateTime.now());
        // [SEM_TIMER] farm de zona é instantâneo (já pronto pra coletar). endsAt 1s no passado
        // mata a corrida de sub-segundo do isReadyToCollect quando o collect vem logo após o enter
        // (persistência/relógio podem deixar endsAt um tico no futuro → "still in progress 0s"). [FLAKE_FIX]
        activity.setEndsAt(LocalDateTime.now().minusSeconds(1));
        ZoneActivity saved = activityRepository.save(activity);
        log.info("[ZoneService] player={} action=enter OK id={}", player.getId(), saved.getId());
        return saved;
    }

    /** Estamina por ação de zona: coleta E caça (COMBAT) instantâneas ~duração/2; HUNTING (legado) ~duração/8. [SEM_TIMER][FORTALEZA_ZONAS] */
    static int staminaCostFor(ActivityRole role, int durationMinutes) {
        int cost = role == ActivityRole.HUNTING
                ? Math.round(durationMinutes / 8f)
                : Math.max(5, durationMinutes / 2);
        return Math.min(100, Math.max(5, cost));
    }

    // ── Coleta da expedição ──

    public record CollectResult(ZoneActivity activity,
                                List<GatheringService.ResourceDrop> drops,
                                boolean wasAttacked, boolean survived,
                                String lostItemName, String narrative,
                                boolean bossPending, String bossName, int bossLevel, int fleeChance,
                                String lootItemName) {} // [ZONA_CHEFE]

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

        if (activity.getStatus() == ZoneActivityStatus.BOSS_PENDING) { // [ZONA_CHEFE]
            throw new IllegalStateException("A roaming boss is blocking you — flee or fight it first.");
        }

        if (!activity.isReadyToCollect() && activity.getStatus() == ZoneActivityStatus.IN_PROGRESS) {
            long secs = java.time.Duration.between(
                    LocalDateTime.now(), activity.getEndsAt()).getSeconds();
            log.warn("[ZoneService] player={} REJECTED: activity {} still in progress, {}s remaining", player.getId(), activityId, secs);
            throw new IllegalStateException("Expedition still in progress. " + secs + "s");
        }

        // ── [ZONA_CHEFE] Chefe errante: rola ANTES do encontro normal (expedições com encontro). ──
        if (activity.getRole() != ActivityRole.HUNTING) {
            Warrior w = warriorRepository.findByPlayer(player).orElse(null);
            Random rng = java.util.concurrent.ThreadLocalRandom.current();
            if (bossEnabled && w != null && !w.isKnockedOut() && rng.nextInt(1000) < bossChancePerMille(activity.getZone())) {
                int bossLvl = w.getLevel() + 1 + rng.nextInt(20); // +1..20
                String bossName = bossNameFor(activity);
                activity.setStatus(ZoneActivityStatus.BOSS_PENDING);
                activity.setBossLevel(bossLvl);
                activity.setBossName(bossName);
                activityRepository.save(activity);
                log.info("[ZoneService] player={} BOSS appeared zone={} bossLvl={}", player.getId(), activity.getZone(), bossLvl);
                return new CollectResult(activity, List.of(), false, true, null, null,
                        true, bossName, bossLvl, fleeChance(w), null);
            }
        }

        // ── Hunter (legado): só coleta ──
        if (activity.getRole() == ActivityRole.HUNTING) {
            List<GatheringService.ResourceDrop> drops = resolveGathering(player, activity);
            activity.setStatus(ZoneActivityStatus.COMPLETED);
            applyDropsAndRewards(player, activity, drops);
            return winResult(activity, drops, false, null);
        }

        // ── Encontros: 🟢 SAFE = só NPC (PvE); 🟡🔴 = PvP + NPC ──
        PvpResult pvp = resolveEncounters(player, activity);
        if (!pvp.survived()) {
            return defeat(player, activity, pvp.battleLog(), pvp.attackerName(), pvp.bronzeLost());
        }
        List<GatheringService.ResourceDrop> drops = resolveZoneDrops(player, activity);
        if (pvp.monsterCore() > 0) drops = withMonsterCore(drops, pvp.monsterCore()); // [MONSTER_CORE_BATALHA] batalha vencida na coleta
        activity.setStatus(ZoneActivityStatus.COMPLETED);
        if (pvp.wasAttacked()) {
            activity.setAttacked(true);
            activity.setSurvivedAttack(true);
            activity.setBattleLog(String.join("\n", pvp.battleLog()));
            activity.setAttackerWarriorName(pvp.attackerName());
            activity.setResolvedAt(LocalDateTime.now());
        }
        // [FORTALEZA_ZONAS] caçada de combate pode dropar 1 item em kill normal (além do chefe).
        String loot = activity.getRole() == ActivityRole.COMBAT ? rollCombatItemDrop(player, activity) : null;
        applyDropsAndRewards(player, activity, drops);
        return winResult(activity, drops, pvp.wasAttacked(), loot);
    }

    /** Drops da expedição por papel: COMBAT caça (materiais+essência), resto coleta. [FORTALEZA_ZONAS] */
    private List<GatheringService.ResourceDrop> resolveZoneDrops(Player player, ZoneActivity activity) {
        return activity.getRole() == ActivityRole.COMBAT
                ? resolveCombatHunt(player, activity)
                : resolveGathering(player, activity);
    }

    /** Soma {@code extra} Monster Core aos drops (mescla com a entrada existente se houver). [MONSTER_CORE_BATALHA] */
    private List<GatheringService.ResourceDrop> withMonsterCore(List<GatheringService.ResourceDrop> drops, long extra) {
        List<GatheringService.ResourceDrop> out = new ArrayList<>();
        boolean merged = false;
        for (GatheringService.ResourceDrop d : drops) {
            if (d.type() == com.medieval.game.enums.ResourceType.MONSTER_CORE) {
                out.add(new GatheringService.ResourceDrop(d.type(), d.quantity() + extra));
                merged = true;
            } else out.add(d);
        }
        if (!merged) out.add(new GatheringService.ResourceDrop(com.medieval.game.enums.ResourceType.MONSTER_CORE, extra));
        return out;
    }

    /**
     * [FORTALEZA_ZONAS] "Coleta" da Fortaleza Maldita = caçar mob. Dropa materiais de combate
     * (Monster Core/Beast Hide) + essência do elemento da área, escalando pelo tier. Grava XP/bronze
     * por-kill em xpGained/bronzeGained (aplicados no applyDropsAndRewards).
     */
    private List<GatheringService.ResourceDrop> resolveCombatHunt(Player player, ZoneActivity activity) {
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        int    level = w != null ? w.getLevel() : 1;
        double mult  = activity.getZone().multiplier;
        Random rng   = java.util.concurrent.ThreadLocalRandom.current();

        List<GatheringService.ResourceDrop> drops = new ArrayList<>();
        long cores = Math.max(1, Math.round((1 + level / 25.0) * mult)); // Núcleo de Fera sempre
        drops.add(new GatheringService.ResourceDrop(com.medieval.game.enums.ResourceType.MONSTER_CORE, cores));
        if (rng.nextDouble() < Math.min(0.9, 0.25 * mult)) {            // Pele de Fera: chance sobe com o tier
            drops.add(new GatheringService.ResourceDrop(
                    com.medieval.game.enums.ResourceType.BEAST_HIDE, Math.max(1, Math.round(mult))));
        }
        if (activity.getElement() != null) {                            // Essência do elemento (igual à coleta)
            drops.add(new GatheringService.ResourceDrop(
                    activity.getElement().essence(), Math.max(1, (int) Math.round(mult))));
        }
        activity.setXpGained(Math.round(level * 12 * mult));            // recompensa por-kill
        activity.setBronzeGained(Math.round(level * 10 * mult));
        return drops;
    }

    /** [FORTALEZA_ZONAS] Chance pequena de item em kill normal de COMBAT (além do chefe). Nível do monstro. */
    private String rollCombatItemDrop(Player player, ZoneActivity activity) {
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        if (w == null) return null;
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        int chance = switch (activity.getZone()) { case HIGH_RISK -> 10; case PVP -> 6; default -> 3; };
        if (rng.nextInt(100) >= chance) return null;
        int itemLevel = monsterLevelFor(activity.getZone(), w.getLevel(), rng); // [ITEM_DROP_LEVEL]
        int r = rng.nextInt(100);
        int rarity = r < 10 ? 3 : r < 40 ? 2 : 1; // 60% Comum / 30% Incomum / 10% Raro
        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(com.medieval.game.enums.ItemType.values().length)];
        String name = "Beast Trophy " + type.displayName;
        long price = switch (rarity) { case 3 -> 200L; case 2 -> 80L; default -> 30L; };
        String desc = "Taken from a slain beast of the Cursed Fortress.", origin = "Combat Zone";
        if (inventoryService.bagSpaceLeft(player) >= 1)
            return inventoryService.make(player, name, type, 0, 0, 0, rarity, price, itemLevel, desc, origin).getName();
        mailService.sendItemMail(player, "Beast trophy loot.", name, type, 0, 0, 0, rarity, itemLevel, 0, desc, origin);
        return name + " (mailed — bag full)";
    }

    // ── [ZONA_CHEFE] Finalização compartilhada (vitória) e derrota ──────────────

    /** Aplica drops + XP de skill + recompensa COMBAT + flag PvP e persiste. Status já = COMPLETED. */
    private void applyDropsAndRewards(Player player, ZoneActivity activity, List<GatheringService.ResourceDrop> drops) {
        for (GatheringService.ResourceDrop drop : drops) {
            gatheringService.addResource(player, drop.type(), drop.quantity());
        }
        if (activity.getRole() == ActivityRole.GATHERING && activity.getSkillType() != null) {
            SkillLevel skill = gatheringService.getOrCreateSkill(player, activity.getSkillType());
            gatheringService.addSkillXp(skill, (int) activity.getXpGained());
        }
        if (activity.getRole() == ActivityRole.COMBAT) {
            // [FORTALEZA_ZONAS] recompensa por-kill já calculada em resolveCombatHunt (xp/bronzeGained).
            warriorRepository.findByPlayer(player).ifPresent(w -> {
                warriorService.addExperience(w, activity.getXpGained());
                warriorRepository.save(w);
                player.addBronzeAmount(activity.getBronzeGained());
                playerRepository.save(player);
            });
        }
        // [PVP_FLAG] Farmou zona PvP/Alto Risco e sobreviveu → fica EXPOSTO por 1h.
        if (activity.getZone() == Zone.PVP || activity.getZone() == Zone.HIGH_RISK) {
            player.setPvpFlaggedZone(activity.getZone());
            player.setPvpFlaggedUntil(LocalDateTime.now().plusMinutes(PVP_FLAG_MINUTES));
            playerRepository.save(player);
            if (activity.getZone() == Zone.HIGH_RISK) lockExposedItems(player);
        }
        warriorRepository.findByPlayer(player).ifPresent(warriorRepository::save);
        activityRepository.save(activity);
    }

    private CollectResult winResult(ZoneActivity activity, List<GatheringService.ResourceDrop> drops,
                                    boolean wasAttacked, String lootItemName) {
        String narrative = (activity.getRole() == ActivityRole.GATHERING && activity.getSkillType() != null)
                ? GatheringNarrator.narrate(activity.getSkillType(), activity.getKingdom()) : null;
        return new CollectResult(activity, drops, wasAttacked, true, null, narrative,
                false, null, 0, 0, lootItemName);
    }

    /** Derrota (PvP/NPC/chefe): KO + penalidade do tier. {@code bronzeLost} é só p/ exibir (já descontado, se houver). */
    private CollectResult defeat(Player player, ZoneActivity activity, List<String> battleLog, String attackerName, long bronzeLost) {
        activity.setStatus(ZoneActivityStatus.DEFEATED);
        activity.setAttacked(true);
        activity.setSurvivedAttack(false);
        activity.setBronzeLost(bronzeLost);
        activity.setAttackerWarriorName(attackerName);
        activity.setBattleLog(String.join("\n", battleLog));
        activity.setResolvedAt(LocalDateTime.now());
        // [KO_SEM_ZERAR_STAMINA] Derrota = KO (perde a VIDA) + penalidade do tier. NÃO zera a estamina:
        // a estamina já foi gasta pela AÇÃO (enter). Curar no Templo deve devolver o jogador ao jogo com a
        // estamina que restou — zerar aqui era punição dupla (KO trava combate até curar; o gate é o HP).
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.applyDamagePercent(100);
            w.clearBuff();
            if (activity.getZone() != Zone.SAFE) { // XP só some fora da verde
                long xpLost = Math.max(1, w.expNeededForNextLevel() / 10);
                activity.setXpGained(-xpLost);
                warriorService.loseXp(w, xpLost);
            }
            warriorRepository.save(w);
        });
        String lostItem = null;
        if (activity.getZone() == Zone.HIGH_RISK) {
            lostItem = maybeDropEquippedItem(player);
            activity.setLostEquippedItem(lostItem);
        }
        playerRepository.save(player);
        activityRepository.save(activity);
        return new CollectResult(activity, List.of(), true, false, lostItem, null, false, null, 0, 0, null);
    }

    // ── [ZONA_CHEFE] Chefe errante ─────────────────────────────────────────────

    /** Chance (por mil) de um chefe errante aparecer, por tier. 🟢0.5% 🟡1.5% 🔴3%. */
    private int bossChancePerMille(Zone zone) {
        return switch (zone) { case PVP -> 15; case HIGH_RISK -> 30; default -> 5; };
    }

    private static final String[] BOSS_NAMES = {
        "Escaped Tower Warden", "Runaway Tower Behemoth", "Tower Tyrant", "Forsaken Tower Champion"
    };
    private String bossNameFor(ZoneActivity a) {
        String n = BOSS_NAMES[java.util.concurrent.ThreadLocalRandom.current().nextInt(BOSS_NAMES.length)];
        return a.getElement() != null ? a.getElement().icon + " " + n : n;
    }

    /** Stat de fuga por classe (Warrior=STR, Archer=DEX, Merchant=LUK, resto=DEX). [ZONA_CHEFE] */
    private int classFleeStat(Warrior w) {
        return switch (w.getWarriorClass()) {
            case WARRIOR  -> w.getStrength();
            case MERCHANT -> w.getLuck();
            default       -> w.getDexterity(); // ARCHER / RECRUIT
        };
    }
    /** % de fuga do chefe (20–90): 30 + stat da classe. */
    private int fleeChance(Warrior w) {
        return Math.max(20, Math.min(90, 30 + classFleeStat(w)));
    }

    /** Nível do monstro normal por tier: 🟢 +0..3; 🟡 +0..3 & 30% elite (+4..8); 🔴 +0..3 & 50% (+6..15). */
    private int monsterLevelFor(Zone zone, int playerLevel, Random rng) {
        int lvl = playerLevel + rng.nextInt(4); // +0..3
        switch (zone) {
            case PVP       -> { if (rng.nextInt(100) < 30) lvl += 4 + rng.nextInt(5); }  // +4..8
            case HIGH_RISK -> { if (rng.nextInt(100) < 50) lvl += 6 + rng.nextInt(10); } // +6..15
            default        -> { }
        }
        return Math.max(1, lvl);
    }

    @Transactional
    public CollectResult resolveBossFlee(Player playerArg, Long activityId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ZoneActivity activity = requireBossPending(player, activityId);
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        if (rng.nextInt(100) < fleeChance(w)) {
            activity.setBattleLog("🏃 You slipped away from " + activity.getBossName() + ".");
            List<GatheringService.ResourceDrop> drops = resolveZoneDrops(player, activity);
            activity.setStatus(ZoneActivityStatus.COMPLETED);
            applyDropsAndRewards(player, activity, drops);
            log.info("[ZoneService] player={} fled boss OK", player.getId());
            return winResult(activity, drops, false, null);
        }
        log.info("[ZoneService] player={} flee FAILED → forced fight", player.getId());
        return resolveBossFight(player, activityId); // fuga falhou → encara
    }

    @Transactional
    public CollectResult resolveBossFight(Player playerArg, Long activityId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ZoneActivity activity = requireBossPending(player, activityId);
        Warrior w = warriorRepository.findByPlayer(player).orElseThrow();

        int[] s = getWarriorStats(w, player);
        int maxHp = s[2];
        int hp    = w.getCalculatedHpPercent() * maxHp / 100;
        int lvl   = activity.getBossLevel();
        int[] m   = npcStatsByLevel(lvl, java.util.concurrent.ThreadLocalRandom.current());
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(
            BattleSimulator.Combatant.of(w.getName(), new int[]{s[0], s[1], hp, s[3], s[4], s[5]},
                w.getActiveWeaponElement(), w.getActiveArmorElement(), abilityService.activeLoadout(w)),
            BattleSimulator.Combatant.of(activity.getBossName(),
                new int[]{(int)(m[0] * 1.5), (int)(m[1] * 1.5), m[2] * 2, m[3], m[4], m[5]}, // chefe: ATK/DEF ×1.5, HP ×2
                activity.getElement(), activity.getElement(), List.of()),
            true); // PvE: timeout = derrota
        inventoryService.wearEquippedItems(player);
        List<String> log = stripWinnerTag(out.log());

        if (out.firstWon()) {
            persistAttackerHp(w, out.firstHpFinal(), maxHp);
            String loot = rollBossLoot(player, lvl); // item garantido no nível do chefe
            long bonusXp = lvl * 30L, bonusBronze = lvl * 20L;
            warriorService.addExperience(w, bonusXp); warriorRepository.save(w);
            player.addBronzeAmount(bonusBronze); playerRepository.save(player);
            log.add("🏆 You slew " + activity.getBossName() + "! Loot: " + loot + " (+" + bonusXp + " XP, " + bonusBronze + " bronze).");
            activity.setAttacked(true); activity.setSurvivedAttack(true);
            activity.setAttackerWarriorName(activity.getBossName());
            activity.setBattleLog(String.join("\n", log));
            activity.setResolvedAt(LocalDateTime.now());
            List<GatheringService.ResourceDrop> drops = resolveZoneDrops(player, activity);
            drops = withMonsterCore(drops, Math.max(2, lvl / 6)); // [MONSTER_CORE_BATALHA] chefe = batalha grande
            activity.setStatus(ZoneActivityStatus.COMPLETED);
            applyDropsAndRewards(player, activity, drops);
            return winResult(activity, drops, true, loot);
        }
        persistAttackerHp(w, 0, maxHp);
        return defeat(player, activity, log, activity.getBossName(), 0);
    }

    private ZoneActivity requireBossPending(Player player, Long activityId) {
        ZoneActivity a = activityRepository.findById(activityId)
                .orElseThrow(() -> new IllegalArgumentException("Expedition not found"));
        if (!a.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This expedition does not belong to you");
        if (a.getStatus() != ZoneActivityStatus.BOSS_PENDING)
            throw new IllegalStateException("No roaming boss to resolve.");
        return a;
    }

    /** 1 item garantido no nível do chefe, raridade alta: 25% Lendário / 40% Épico / 35% Raro. */
    private String rollBossLoot(Player player, int bossLevel) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        int r = rng.nextInt(100);
        int rarity = r < 25 ? 5 : r < 65 ? 4 : 3;
        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(com.medieval.game.enums.ItemType.values().length)];
        String name = "Tower Warden's " + type.displayName;
        long price = switch (rarity) { case 5 -> 2500L; case 4 -> 1000L; default -> 400L; };
        String desc = "Spoils from the escaped Tower boss.", origin = "Roaming Boss";
        if (inventoryService.bagSpaceLeft(player) >= 1)
            return inventoryService.make(player, name, type, 0, 0, 0, rarity, price, bossLevel, desc, origin).getName();
        mailService.sendItemMail(player, "Roaming boss loot.", name, type, 0, 0, 0, rarity, bossLevel, 0, desc, origin);
        return name + " (mailed — bag full)";
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
                    skill.getLevel(), 10, activity.getKingdom())); // [UNIFICAÇÃO_ZONA] drops por reino
        }

        // Aplica multiplicador de zona × bônus de coleta do Mercador (Prospector). [MERCADOR]
        double yieldMult = mult * (1 + abilityService.gatherYieldBonusPct(player) / 100.0);
        List<GatheringService.ResourceDrop> scaled = new ArrayList<>();
        for (GatheringService.ResourceDrop d : allDrops) {
            scaled.add(new GatheringService.ResourceDrop(d.type(),
                    Math.max(1, Math.round(d.quantity() * yieldMult))));
        }

        // [ELEMENTOS] Essência do elemento da área (material de encantamento) — escala com o tier.
        if (activity.getElement() != null) {
            int essenceQty = Math.max(1, (int) Math.round(rounds * mult / 2.0));
            scaled.add(new GatheringService.ResourceDrop(activity.getElement().essence(), essenceQty));
        }

        long xp = Math.round(xpBase * mult);
        activity.setXpGained(xp);
        return scaled;
    }

    // ── Privados: resolução de PvP ──

    record PvpResult(boolean wasAttacked, boolean survived, long bronzeLost,
                     String attackerName, List<String> battleLog, long monsterCore) {} // [MONSTER_CORE_BATALHA]

    /**
     * Resolve the encounters for the collecting player ("attacker"). [SEM_TIMER] One farm
     * action = one PvP roll (ambush a flagged player) + one NPC roll — instant model, NOT
     * per-hour (duration doesn't affect the chance). Returns the attacker's overall outcome.
     */
    private PvpResult resolveEncounters(Player player, ZoneActivity activity) {
        Zone   zone = activity.getZone();
        Random rng  = java.util.concurrent.ThreadLocalRandom.current();

        Warrior attacker = warriorRepository.findByPlayer(player).orElse(null);
        if (attacker == null) return new PvpResult(false, true, 0, null, List.of(), 0);

        int[] atkStats = getWarriorStats(attacker, player);
        int   atkMaxHp = atkStats[2];
        int   atkHp    = attacker.getCalculatedHpPercent() * atkMaxHp / 100;

        // ── PvP: cruza com um player FLAGGED na zona (raid de loot). [PVP_FLAG] ──
        if (zone.pvpEncounterChance > 0 && rng.nextInt(100) < zone.pvpEncounterChance) {
            Player  victim  = findFlaggedOpponent(zone, player, attacker.getLevel(), rng);
            Warrior victimW = victim != null ? warriorRepository.findByPlayer(victim).orElse(null) : null;
            if (victimW != null) {
                int[] vStats = getWarriorStats(victimW, victim);
                int   vMaxHp = vStats[2];
                int   vHp    = victimW.getCalculatedHpPercent() * vMaxHp / 100;

                BattleSimulator.BattleOutcome out = battleSimulator.simulate(
                    BattleSimulator.Combatant.of(attacker.getName(),
                        new int[]{atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5]},
                        attacker.getActiveWeaponElement(), attacker.getActiveArmorElement(), abilityService.activeLoadout(attacker)),
                    BattleSimulator.Combatant.of(victimW.getName(),
                        new int[]{vStats[0], vStats[1], vHp, vStats[3], vStats[4], vStats[5]},
                        victimW.getActiveWeaponElement(), victimW.getActiveArmorElement(), abilityService.activeLoadout(victimW)),
                    false); // PvP %HP [ELEMENTOS/HABILIDADES]

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
                    return new PvpResult(true, true, 0, foe, log, 0); // venceu e saqueou (PvP → sem Monster Core)
                } else {
                    long lost = applyDefeatPenalty(player, victim); // você perdeu; a vítima defendeu
                    persistAttackerHp(attacker, 0, atkMaxHp);
                    return new PvpResult(true, false, lost, foe, log, 0);
                }
            }
            // Nenhum flagged → NPC ambusher (preenchimento). [PVP_FLAG]
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng, activity.getElement());
        }

        // ── NPC selvagem (PvE) ──
        if (rng.nextInt(100) < zone.npcEncounterChance) {
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng, activity.getElement());
        }

        persistAttackerHp(attacker, atkHp, atkMaxHp);
        return new PvpResult(false, true, 0, null, List.of(), 0);
    }

    /** Luta contra um NPC (monstro selvagem ou "ambusher" de preenchimento). Monstro usa o elemento da área. */
    private PvpResult fightNpc(Player player, Warrior attacker, int[] atkStats, int atkHp, int atkMaxHp, Zone zone, Random rng,
                              com.medieval.game.enums.Element areaElement) {
        int    npcLevel = monsterLevelFor(zone, attacker.getLevel(), rng); // [ZONA_CHEFE] escala por tier
        String npcName  = areaElement != null ? areaElement.icon + " " + npcName(zone, rng) : npcName(zone, rng);
        int[]  npcStats = npcStatsByLevel(npcLevel, rng);
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(
                BattleSimulator.Combatant.of(attacker.getName(),
                    new int[]{atkStats[0], atkStats[1], atkHp, atkStats[3], atkStats[4], atkStats[5]},
                    attacker.getActiveWeaponElement(), attacker.getActiveArmorElement(), abilityService.activeLoadout(attacker)),
                BattleSimulator.Combatant.of(npcName, npcStats, areaElement, areaElement, java.util.List.of()),
                false); // PvE NPC: empate por %HP — monstro usa o elemento da área [ELEMENTOS/HABILIDADES]
        List<String> log = stripWinnerTag(out.log());
        inventoryService.wearEquippedItems(player);
        if (!out.firstWon()) {
            long lost = applyDefeatPenalty(player, null);
            persistAttackerHp(attacker, 0, atkMaxHp);
            return new PvpResult(true, false, lost, npcName, log, 0);
        }
        persistAttackerHp(attacker, out.firstHpFinal(), atkMaxHp);
        // [MONSTER_CORE_BATALHA] toda batalha PvE vencida (inclusive durante coleta/mineração) dropa Monster Core.
        long core = Math.max(1, Math.round((1 + npcLevel / 15.0) * zone.multiplier));
        return new PvpResult(true, true, 0, npcName, log, core);
    }

    /** Sorteia um player FLAGGED (exposto) na zona, sem escudo, dentro de ±PVP_LEVEL_BAND níveis. [PVP_FLAG] */
    private Player findFlaggedOpponent(Zone zone, Player exclude, int attackerLevel, Random rng) {
        List<Player> pool = playerRepository.findFlaggedInZone(zone, LocalDateTime.now(), exclude.getId())
                .stream()
                .filter(p -> !p.isPvpShielded())
                .filter(p -> Math.abs(attackerLevel
                        - warriorRepository.findByPlayer(p).map(Warrior::getLevel).orElse(1)) <= PVP_LEVEL_BAND)
                .toList();
        if (pool.isEmpty()) return null;
        return pool.get(rng.nextInt(pool.size()));
    }

    /**
     * Atacante venceu o raid → saqueia a vítima por TIER. [PVP_FLAG]
     * 🟡 Amarela (PVP): 10% bronze + XP (vítima perde, killer +50%). SEM recursos, SEM item. [FORTALEZA_ZONAS]
     * 🔴 Vermelha (HIGH_RISK): 50% recursos + 15% bronze + 1 item travado (35%) + XP.
     */
    private void raidVictim(Player attacker, String attackerName, Player victim, Warrior victimW, Zone zone, List<String> log) {
        boolean red       = (zone == Zone.HIGH_RISK);
        long   bronze     = applyDefeatPenalty(victim, attacker, red ? 0.15 : 0.10);
        long   stolenRes  = red ? stealResources(attacker, victim) : 0;  // recursos só na vermelha [FORTALEZA_ZONAS]
        String stolenItem = red ? stealOneItem(attacker, victim)   : null; // item só na vermelha
        long   xpLost     = stealXp(victim, attacker);                   // XP em ambas (killer +50%)

        victimW.clearBuff();
        victim.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES)); // saqueado 1x por ciclo
        victim.clearPvpFlag();
        // destrava os itens travados restantes (fim do ciclo)
        List<InventoryItem> remaining = inventoryRepository.findAllByPlayer(victim);
        unlockAllItems(remaining);
        inventoryRepository.saveAll(remaining);
        playerRepository.save(victim);

        String loot = bronze + " bronze"
                + (stolenItem != null ? ", " + stolenItem : "")
                + (stolenRes > 0 ? ", " + stolenRes + " resources" : "")
                + (xpLost > 0 ? ", " + xpLost + " XP" : "");
        log.add("💰 You raided " + victimW.getName() + "! Stole " + loot + ".");
        mailService.sendSystemMail(victim,
            "💀 You were RAIDED by " + attackerName + " in the " + zone.displayName + "! Lost " + loot
            + ". Protection shield for " + PVP_SHIELD_MINUTES + " min.");
    }

    /** Vítima perde XP; o killer ganha 50% (teto: 10% do XP do nível do killer). [PVP_FLAG] */
    private long stealXp(Player victim, Player attacker) {
        Warrior vw = warriorRepository.findByPlayer(victim).orElse(null);
        if (vw == null) return 0;
        long xpLost = Math.max(1, vw.expNeededForNextLevel() / 20);
        warriorService.loseXp(vw, xpLost);
        warriorRepository.findByPlayer(attacker).ifPresent(aw -> {
            long gain = Math.min(xpLost / 2, Math.max(1, aw.expNeededForNextLevel() / 10));
            if (gain > 0) { warriorService.addExperience(aw, gain); warriorRepository.save(aw); }
        });
        return xpLost;
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

    /**
     * Aplica no PERDEDOR o prejuízo da zona VERMELHA — reuso pela Guerra de Guilda. [GUERRA_GUILDA]
     * O vencedor saqueia: −15% bronze (½ vai pro vencedor), −50% recursos, 35% de levar 1 item exposto,
     * XP (vencedor +50%). O perdedor perde o buff e ganha escudo de 1h. Retorna o resumo do loot.
     * (Trava os itens expostos só no momento da derrota pra permitir o roubo de 1, depois destrava — sem
     *  flag persistente como na zona.)
     */
    @Transactional
    public String applyGuildWarRaid(Player winner, Player loser) {
        lockExposedItems(loser); // expõe os itens (não-stashed/não-guarded) pro roubo de 1
        long   bronze = applyDefeatPenalty(loser, winner, 0.15);
        long   res    = stealResources(winner, loser);
        String item   = stealOneItem(winner, loser);
        long   xp     = stealXp(loser, winner);

        warriorRepository.findByPlayer(loser).ifPresent(w -> { w.clearBuff(); warriorRepository.save(w); });
        loser.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES));
        List<InventoryItem> remaining = inventoryRepository.findAllByPlayer(loser);
        unlockAllItems(remaining);
        inventoryRepository.saveAll(remaining);
        playerRepository.save(loser);

        String loot = bronze + " bronze"
                + (item != null ? ", " + item : "")
                + (res > 0 ? ", " + res + " resources" : "")
                + (xp  > 0 ? ", " + xp + " XP" : "");
        String winnerName = warriorRepository.findByPlayer(winner).map(Warrior::getName).orElse("an enemy");
        mailService.sendSystemMail(loser,
            "💀 You were defeated in a GUILD WAR by " + winnerName + "! Lost " + loot
            + ". Protection shield for " + PVP_SHIELD_MINUTES + " min.");
        return loot;
    }

    /** Rouba ~50% de cada recurso da bag da vítima e dá ao atacante (clamp na bag dele). */
    private long stealResources(Player attacker, Player victim) {
        long total = 0;
        for (ResourceInventory r : resourceRepo.findAllByPlayerAndStashed(victim, false)) {
            if (r.getQuantity() <= 0) continue;
            if (inventoryService.bagSpaceLeft(attacker) <= 0) break;
            long take  = Math.max(1, r.getQuantity() / 2);
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

    /** Penalidade de derrota padrão (15% bronze). */
    private long applyDefeatPenalty(Player loser, Player winner) {
        return applyDefeatPenalty(loser, winner, 0.15);
    }

    /** Aplica penalidade de derrota: perde `pct` do bronze; vencedor (se player) ganha 50% do perdido. */
    private long applyDefeatPenalty(Player loser, Player winner, double pct) {
        long bronzeLost = Math.round(loser.totalBronze() * pct);
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
