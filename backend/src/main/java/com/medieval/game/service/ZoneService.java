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
    private final WorkGuard                workGuard; // [WORK_IDLE][VARREDURA] trava enquanto trabalha
    private final KingdomQuestNarrator     narrator;        // [ENEMY_NAMES] nome do inimigo por bioma + tier (localizado)
    private final PvpRaidService           pvpRaidService;  // [VARREDURA] raid PvP compartilhado (Zona/Incursão/Guerra)

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // [ZONA_CHEFE] Chefe errante ligado em dev/prod; desligado nos testes p/ collect determinístico.
    @Value("${app.zone.boss-enabled:true}")
    private boolean bossEnabled;

    private static final int PVP_FLAG_MINUTES   = 60; // exposto por 1h após farmar zona PvP
    private static final int PVP_SHIELD_MINUTES  = 60; // imune por 1h após ser saqueado (1x por ciclo)
    private static final int PVP_LEVEL_BAND      = 10; // só ataca/é atacado dentro de ±10 níveis

    // [ECON_EXPLOIT] Recompensa da caçada COMBAT, POR RODADA (= por 10min de duração, igual à coleta).
    // ANTES era um valor fixo por collect, ignorando a duração — mas a estamina escalava com a duração
    // (staminaCostFor ~dur/2), então o ótimo era mandar duração=5 e levar a recompensa cheia por 5 de
    // estamina (eficiência ~6× a do uso honesto). Agora bronze/XP/drops escalam por `rounds` → a
    // eficiência por estamina fica CONSTANTE (duração vira neutra). Números são placeholders de tuning;
    // bronze por-kill caiu 10→5 p/ não dominar o income (knob: ajuste aqui). [SEM_TIMER][FORTALEZA_ZONAS]
    private static final int COMBAT_BRONZE_PER_KILL = 5;  // bronze ~= level * 5 * tierMult por rodada
    private static final int COMBAT_XP_PER_KILL     = 12; // XP    ~= level * 12 * tierMult por rodada

    // [ECON_EXPLOIT] Teto de rodadas de recompensa. O fix acima assume que a estamina cresce ~dur/2 e
    // mantém a eficiência (reward∝rounds) constante — MAS `staminaCostFor` faz Math.min(100, …): a
    // estamina SATURA em 100 já aos 200min, enquanto rounds=dur/10 continuaria até 72 em 720min. Sem
    // teto, a eficiência/estamina dispararia ~3.6× na duração máxima (auditoria de hardening, P1). Capar
    // rounds no ponto de saturação (100 estamina ÷ 5 de estamina-por-rodada = 20) re-trava a eficiência.
    static final int MAX_REWARD_ROUNDS = 20;

    /** Rodadas de recompensa por duração, CAPADAS no ponto em que a estamina satura (100). [ECON_EXPLOIT] */
    static int rewardRounds(int durationMinutes) {
        return Math.min(MAX_REWARD_ROUNDS, Math.max(1, durationMinutes / 10));
    }

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

        workGuard.assertNotBusy(player); // [WORK_IDLE] não aventura enquanto trabalha

        // [SEM_TIMER] Auto-cancela expedição pendurada (IN_PROGRESS não coletada): tudo é instantâneo,
        // então uma atividade antiga é só lixo — cancela e segue pra nova (uma expedição ativa por vez).
        activityRepository.findByPlayerAndStatus(player, ZoneActivityStatus.IN_PROGRESS)
                .ifPresent(orphan -> {
                    orphan.setStatus(ZoneActivityStatus.CANCELLED);
                    activityRepository.save(orphan);
                });

        if (warrior.getLevel() < zone.minLevel) {
            log.warn("[ZoneService] player={} REJECTED: level {} too low for zone {} (required {})", player.getId(), warrior.getLevel(), zone, zone.minLevel);
            throw new com.medieval.game.config.LocalizedException("error.zone_level", "Level too low. Required: {0}", zone.minLevel);
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
                throw new com.medieval.game.config.LocalizedException("error.stamina_rest", "Not enough stamina ({0}/{1}). Rest to recover.", cur, staminaCost);
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
                                String lootItemName, Long lootItemId,
                                List<BattleSimulator.BattleEvent> battleEvents) {} // [ZONA_CHEFE][PILOTO_UI][BATALHA_ANIMADA]

    /** [PILOTO_UI] Item dropado: nome + id. id null quando foi pro mail (bag cheia) → sem botão Equip. */
    private record LootRoll(String name, Long id) {}

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
            throw new com.medieval.game.config.LocalizedException("error.expedition_progress", "Expedition still in progress. {0}s", secs);
        }

        // ── [ZONA_CHEFE] Chefe errante: rola ANTES do encontro normal (expedições com encontro). ──
        if (activity.getRole() != ActivityRole.HUNTING) {
            Warrior w = warriorRepository.findByPlayer(player).orElse(null);
            Random rng = java.util.concurrent.ThreadLocalRandom.current();
            if (bossEnabled && w != null && !w.isKnockedOut() && rng.nextInt(1000) < bossChancePerMille(activity.getZone())) {
                int bossLvl = w.getLevel() + 1 + rng.nextInt(6); // [PLAYTEST_FIX] +1..6 (era +1..20 = chefe muito acima do player)
                String bossName = bossNameFor(activity);
                activity.setStatus(ZoneActivityStatus.BOSS_PENDING);
                activity.setBossLevel(bossLvl);
                activity.setBossName(bossName);
                activityRepository.save(activity);
                log.info("[ZoneService] player={} BOSS appeared zone={} bossLvl={}", player.getId(), activity.getZone(), bossLvl);
                return new CollectResult(activity, List.of(), false, true, null, null,
                        true, bossName, bossLvl, fleeChance(w), null, null, List.of());
            }
        }

        // ── Hunter (legado): só coleta ──
        if (activity.getRole() == ActivityRole.HUNTING) {
            List<GatheringService.ResourceDrop> drops = resolveGathering(player, activity);
            activity.setStatus(ZoneActivityStatus.COMPLETED);
            applyDropsAndRewards(player, activity, drops);
            return winResult(activity, drops, false, null, List.of());
        }

        // ── Encontros: 🟢 SAFE = só NPC (PvE); 🟡🔴 = PvP + NPC ──
        PvpResult pvp = resolveEncounters(player, activity);
        if (!pvp.survived()) {
            return defeat(player, activity, pvp.battleLog(), pvp.attackerName(), pvp.bronzeLost(), pvp.battleEvents()); // [BATALHA_ANIMADA]
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
        LootRoll loot = activity.getRole() == ActivityRole.COMBAT ? rollCombatItemDrop(player, activity) : null;
        applyDropsAndRewards(player, activity, drops);
        return winResult(activity, drops, pvp.wasAttacked(), loot, pvp.battleEvents()); // [BATALHA_ANIMADA] eventos do encontro
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

        // [ECON_EXPLOIT] Escala por rodada (= por 10min de duração), igual à coleta (resolveGathering):
        // bronze/XP/materiais ficam proporcionais à estamina paga (staminaCostFor ~dur/2), então mandar
        // duração curta não rende mais por estamina — a eficiência vira constante e a duração, neutra.
        int rounds = rewardRounds(activity.getDurationMinutes()); // [ECON_EXPLOIT] capado no teto de estamina

        List<GatheringService.ResourceDrop> drops = new ArrayList<>();
        long cores = Math.max(1, Math.round((1 + level / 25.0) * mult)) * rounds; // Núcleo de Fera sempre
        drops.add(new GatheringService.ResourceDrop(com.medieval.game.enums.ResourceType.MONSTER_CORE, cores));
        if (rng.nextDouble() < Math.min(0.9, 0.25 * mult)) {            // Pele de Fera: chance sobe com o tier
            drops.add(new GatheringService.ResourceDrop(
                    com.medieval.game.enums.ResourceType.BEAST_HIDE, Math.max(1, Math.round(mult)) * rounds));
        }
        if (activity.getElement() != null) {                            // Essência do elemento (igual à coleta)
            drops.add(new GatheringService.ResourceDrop(
                    activity.getElement().essence(), Math.max(1, (int) Math.round(mult)) * rounds));
        }
        activity.setXpGained(Math.round(level * COMBAT_XP_PER_KILL * mult) * rounds);         // recompensa por-kill × rodadas
        activity.setBronzeGained(Math.round(level * COMBAT_BRONZE_PER_KILL * mult) * rounds);
        return drops;
    }

    /** [FORTALEZA_ZONAS] Chance pequena de item em kill normal de COMBAT (além do chefe). Nível do monstro. */
    private LootRoll rollCombatItemDrop(Player player, ZoneActivity activity) {
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        if (w == null) return null;
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        int chance = switch (activity.getZone()) { case HIGH_RISK -> 10; case PVP -> 6; default -> 3; };
        if (rng.nextInt(100) >= chance) return null;
        int itemLevel = InventoryService.cappedItemLevel(
                monsterLevelFor(activity.getZone(), w.getLevel(), rng), w.getLevel()); // [ITEM_DROP_LEVEL][BALANCE_ECON]
        int r = rng.nextInt(100);
        int rarity = r < 10 ? 3 : r < 40 ? 2 : 1; // 60% Comum / 30% Incomum / 10% Raro
        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(com.medieval.game.enums.ItemType.values().length)];
        // [I18N_ITENS] nome/desc/origem no idioma do request
        String typeName = Messages.tr("itemtype." + type.name() + ".name", type.displayName);
        String name = Messages.tr("item.beast_trophy", "Beast Trophy {0}", typeName);
        long price = switch (rarity) { case 3 -> 200L; case 2 -> 80L; default -> 30L; };
        String desc = Messages.tr("item.beast_trophy.desc", "Taken from a slain beast of the Cursed Fortress."),
               origin = Messages.tr("itemorigin.hunt", "Hunted in {0}.", Messages.word("the Cursed Fortress")); // [ITEM_PROV]
        if (inventoryService.bagSpaceLeft(player) >= 1) {
            var it = inventoryService.make(player, name, type, 0, 0, 0, rarity, price, itemLevel, desc, origin);
            return new LootRoll(it.getName(), it.getId());
        }
        mailService.sendItemMail(player, "Beast trophy loot.", name, type, 0, 0, 0, rarity, itemLevel, 0, desc, origin);
        return new LootRoll(name + " (mailed — bag full)", null);
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
            int huntKills = Math.max(1, activity.getDurationMinutes() / 10); // mesma contagem de "rodadas"/kills da caça
            warriorRepository.findByPlayer(player).ifPresent(w -> {
                warriorService.addExperience(w, activity.getXpGained());
                w.setMobKills(w.getMobKills() + huntKills); // [LEADERBOARDS] caça da Fortaleza (Hunter)
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
                                    boolean wasAttacked, LootRoll loot, List<BattleSimulator.BattleEvent> events) {
        String narrative = (activity.getRole() == ActivityRole.GATHERING && activity.getSkillType() != null)
                ? GatheringNarrator.narrate(activity.getSkillType(), activity.getKingdom()) : null;
        return new CollectResult(activity, drops, wasAttacked, true, null, narrative,
                false, null, 0, 0, loot != null ? loot.name() : null, loot != null ? loot.id() : null, events);
    }

    /** Derrota (PvP/NPC/chefe): KO + penalidade do tier. {@code bronzeLost} é só p/ exibir (já descontado, se houver). */
    private CollectResult defeat(Player player, ZoneActivity activity, List<String> battleLog, String attackerName, long bronzeLost, List<BattleSimulator.BattleEvent> events) {
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
        return new CollectResult(activity, List.of(), true, false, lostItem, null, false, null, 0, 0, null, null, events);
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
        // [BALANCE] Elites mais raros e menos acima do nível (estavam empacando o jogador).
        switch (zone) {
            case PVP       -> { if (rng.nextInt(100) < 20) lvl += 3 + rng.nextInt(4); }  // 20% · +3..6 (era 30% · +4..8)
            case HIGH_RISK -> { if (rng.nextInt(100) < 30) lvl += 4 + rng.nextInt(6); }  // 30% · +4..9 (era 50% · +6..15)
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
            return winResult(activity, drops, false, null, List.of());
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
            BattleSimulator.Combatant.of(w.getName(), s,
                w.getActiveWeaponElement(), w.getActiveArmorElement(), abilityService.activeLoadout(w), statsService.isRangedWeaponEquipped(player)).withCurrentHp(hp), // [HP_SPAWN] entra com HP atual; máximo = s[2]
            BattleSimulator.Combatant.of(activity.getBossName(),
                new int[]{(int)(m[0] * 1.25), (int)(m[1] * 1.2), (int)(m[2] * 1.6), m[3], m[4], m[5]}, // [PLAYTEST_FIX] chefe: ATK×1.25 DEF×1.2 HP×1.6 (era 1.5/1.5/2)
                activity.getElement(), activity.getElement(), List.of(), false), // [KITING] chefe errante = melee
            true); // PvE: timeout = derrota
        inventoryService.wearEquippedItems(player);
        List<String> log = stripWinnerTag(out.log());

        if (out.firstWon()) {
            persistAttackerHp(w, out.firstHpFinal(), maxHp);
            LootRoll loot = rollBossLoot(player, lvl, activity.getBossName()); // item garantido no nível do chefe [ITEM_PROV]
            long bonusXp = lvl * 30L, bonusBronze = lvl * 20L;
            warriorService.addExperience(w, bonusXp); warriorRepository.save(w);
            player.addBronzeAmount(bonusBronze); playerRepository.save(player);
            log.add("🏆 You slew " + activity.getBossName() + "! Loot: " + loot.name() + " (+" + bonusXp + " XP, " + bonusBronze + " bronze).");
            activity.setAttacked(true); activity.setSurvivedAttack(true);
            activity.setAttackerWarriorName(activity.getBossName());
            activity.setBattleLog(String.join("\n", log));
            activity.setResolvedAt(LocalDateTime.now());
            List<GatheringService.ResourceDrop> drops = resolveZoneDrops(player, activity);
            drops = withMonsterCore(drops, Math.max(2, lvl / 6)); // [MONSTER_CORE_BATALHA] chefe = batalha grande
            activity.setStatus(ZoneActivityStatus.COMPLETED);
            applyDropsAndRewards(player, activity, drops);
            return winResult(activity, drops, true, loot, out.events()); // [BATALHA_ANIMADA] eventos do chefe
        }
        persistAttackerHp(w, 0, maxHp);
        return defeat(player, activity, log, activity.getBossName(), 0, out.events()); // [BATALHA_ANIMADA] eventos do chefe
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

    /** 1 item garantido de chefe (Raro+); [BALANCE_ECON] Lendário bem mais raro + nível do item capado. */
    private LootRoll rollBossLoot(Player player, int bossLevel, String bossName) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        int rarity = InventoryService.rollBossRarity(rng); // [BALANCE_ECON] 8% Leg / 32% Épico / 60% Raro
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        int itemLevel = InventoryService.cappedItemLevel(bossLevel, w != null ? w.getLevel() : bossLevel); // chefe segue duro; só o ITEM é capado
        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(com.medieval.game.enums.ItemType.values().length)];
        // [I18N_ITENS] nome/desc/origem no idioma do request
        String typeName = Messages.tr("itemtype." + type.name() + ".name", type.displayName);
        String name = Messages.tr("item.tower_warden", "Tower Warden''s {0}", typeName);
        long price = switch (rarity) { case 5 -> 2500L; case 4 -> 1000L; default -> 400L; };
        String desc = Messages.tr("item.tower_warden.desc", "Spoils from the escaped Tower boss."),
               origin = Messages.tr("itemorigin.drop", "Obtained after defeating {0}.", bossName); // [ITEM_PROV] nome real do chefe
        if (inventoryService.bagSpaceLeft(player) >= 1) {
            var it = inventoryService.make(player, name, type, 0, 0, 0, rarity, price, itemLevel, desc, origin);
            return new LootRoll(it.getName(), it.getId());
        }
        mailService.sendItemMail(player, "Roaming boss loot.", name, type, 0, 0, 0, rarity, itemLevel, 0, desc, origin);
        return new LootRoll(name + " (mailed — bag full)", null);
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
        // [LAUNCH_HARDENING] Top 20 no banco (a tela mostra ~10) — não materializa o histórico inteiro.
        return activityRepository.findTop20ByPlayerAndStatusInOrderByStartedAtDesc(
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
        int rounds = rewardRounds(durationMin); // 1 rodada/10min, capado no teto de estamina [ECON_EXPLOIT]
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
                     String attackerName, List<String> battleLog, long monsterCore,
                     List<BattleSimulator.BattleEvent> battleEvents) {} // [MONSTER_CORE_BATALHA][BATALHA_ANIMADA]

    /**
     * Resolve the encounters for the collecting player ("attacker"). [SEM_TIMER] One farm
     * action = one PvP roll (ambush a flagged player) + one NPC roll — instant model, NOT
     * per-hour (duration doesn't affect the chance). Returns the attacker's overall outcome.
     */
    private PvpResult resolveEncounters(Player player, ZoneActivity activity) {
        Zone   zone = activity.getZone();
        Random rng  = java.util.concurrent.ThreadLocalRandom.current();

        Warrior attacker = warriorRepository.findByPlayer(player).orElse(null);
        if (attacker == null) return new PvpResult(false, true, 0, null, List.of(), 0, List.of());

        int[] atkStats = getWarriorStats(attacker, player);
        int   atkMaxHp = atkStats[2];
        int   atkHp    = attacker.getCalculatedHpPercent() * atkMaxHp / 100;

        // ── PvP: cruza com um player FLAGGED na zona (raid de loot). [PVP_FLAG] ──
        if (zone.pvpEncounterChance > 0 && rng.nextInt(100) < zone.pvpEncounterChance) {
            Player  victim  = pvpRaidService.findFlaggedOpponent(zone, player, attacker.getLevel());
            Warrior victimW = victim != null ? warriorRepository.findByPlayer(victim).orElse(null) : null;
            if (victimW != null) {
                int[] vStats = getWarriorStats(victimW, victim);
                int   vMaxHp = vStats[2];
                int   vHp    = victimW.getCalculatedHpPercent() * vMaxHp / 100;

                BattleSimulator.BattleOutcome out = battleSimulator.simulate(
                    BattleSimulator.Combatant.of(attacker.getName(), atkStats,
                        attacker.getActiveWeaponElement(), attacker.getActiveArmorElement(), abilityService.activeLoadout(attacker),
                        statsService.isRangedWeaponEquipped(player)).withCurrentHp(atkHp), // [HP_SPAWN] entra com HP atual
                    BattleSimulator.Combatant.of(victimW.getName(), vStats,
                        victimW.getActiveWeaponElement(), victimW.getActiveArmorElement(), abilityService.activeLoadout(victimW),
                        statsService.isRangedWeaponEquipped(victim)).withCurrentHp(vHp), // [HP_SPAWN] entra com HP atual
                    false); // PvP %HP [ELEMENTOS/HABILIDADES/KITING]

                List<String> log = stripWinnerTag(out.log());
                String foe = victimW.getName() + " (player)";
                inventoryService.wearEquippedItems(player);
                inventoryService.wearEquippedItems(victim);
                int vPct = vMaxHp > 0 ? Math.max(0, out.secondHpFinal() * 100 / vMaxHp) : 0;
                victimW.setCurrentHpSnapshot(vPct);
                victimW.setHpUpdatedAt(LocalDateTime.now());
                warriorRepository.save(victimW);

                if (out.firstWon()) {
                    // [VARREDURA] saque compartilhado: loot + escudo + mail RICO (replay) + conta player-kill (Slayer)
                    pvpRaidService.raidVictim(player, attacker, victim, victimW, zone, log, out.events(), pvpScene(activity.getKingdom()));
                    persistAttackerHp(attacker, out.firstHpFinal(), atkMaxHp);
                    return new PvpResult(true, true, 0, foe, log, 0, out.events()); // venceu e saqueou (PvP → sem Monster Core)
                } else {
                    long lost = pvpRaidService.applyDefeatPenalty(player, victim, 0.15); // você perdeu; a vítima defendeu
                    persistAttackerHp(attacker, 0, atkMaxHp);
                    return new PvpResult(true, false, lost, foe, log, 0, out.events());
                }
            }
            // Nenhum flagged → NPC ambusher (preenchimento). [PVP_FLAG]
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng, activity.getElement(), activity.getKingdom());
        }

        // ── NPC selvagem (PvE) ──
        if (rng.nextInt(100) < zone.npcEncounterChance) {
            return fightNpc(player, attacker, atkStats, atkHp, atkMaxHp, zone, rng, activity.getElement(), activity.getKingdom());
        }

        persistAttackerHp(attacker, atkHp, atkMaxHp);
        return new PvpResult(false, true, 0, null, List.of(), 0, List.of());
    }

    /** Luta contra um NPC (monstro selvagem ou "ambusher" de preenchimento). Monstro usa o elemento da área. */
    private PvpResult fightNpc(Player player, Warrior attacker, int[] atkStats, int atkHp, int atkMaxHp, Zone zone, Random rng,
                              com.medieval.game.enums.Element areaElement, Kingdom kingdom) {
        int    npcLevel = monsterLevelFor(zone, attacker.getLevel(), rng); // [ZONA_CHEFE] escala por tier
        // [ENEMY_NAMES] inimigo temático do BIOMA (reino) + tier: alto risco = elite. Modelo 3D casa pelo nome.
        String foeName  = narrator.pickZoneEnemy(kingdom, zone == Zone.HIGH_RISK, rng);
        String npcName  = areaElement != null ? areaElement.icon + " " + foeName : foeName;
        int[]  npcStats = npcStatsByLevel(npcLevel, rng);
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(
                BattleSimulator.Combatant.of(attacker.getName(), atkStats,
                    attacker.getActiveWeaponElement(), attacker.getActiveArmorElement(), abilityService.activeLoadout(attacker),
                    statsService.isRangedWeaponEquipped(player)).withCurrentHp(atkHp), // [HP_SPAWN] entra com HP atual
                BattleSimulator.Combatant.of(npcName, npcStats, areaElement, areaElement, java.util.List.of(), false),
                false); // PvE NPC: empate por %HP — monstro usa o elemento da área [ELEMENTOS/HABILIDADES/KITING]
        List<String> log = stripWinnerTag(out.log());
        inventoryService.wearEquippedItems(player);
        if (!out.firstWon()) {
            long lost = pvpRaidService.applyDefeatPenalty(player, null, 0.15);
            persistAttackerHp(attacker, 0, atkMaxHp);
            return new PvpResult(true, false, lost, npcName, log, 0, out.events());
        }
        attacker.setMobKills(attacker.getMobKills() + 1); // [LEADERBOARDS] NPC PvE abatido (Hunter)
        persistAttackerHp(attacker, out.firstHpFinal(), atkMaxHp);
        // [MONSTER_CORE_BATALHA] toda batalha PvE vencida (inclusive durante coleta/mineração) dropa Monster Core.
        long core = Math.max(1, Math.round((1 + npcLevel / 15.0) * zone.multiplier));
        return new PvpResult(true, true, 0, npcName, log, core, out.events());
    }

    // [VARREDURA] findFlaggedOpponent/raidVictim/stealXp/stealOneItem migraram p/ PvpRaidService (compartilhado
    // Zona/Incursão/Guerra). O raid de Zona agora manda mail RICO (replay) e conta player-kill, igual à Incursão.

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
        Warrior loserW  = warriorRepository.findByPlayer(loser).orElse(null);
        Warrior winnerW = warriorRepository.findByPlayer(winner).orElse(null);
        long   bronze = pvpRaidService.applyDefeatPenalty(loser, winner, 0.15);          // [VARREDURA] helpers compartilhados
        long   res    = pvpRaidService.stealResources(winner, loser);
        String item   = inventoryService.stealOnePvpLockedItem(loser, winner);
        long   xp     = (loserW != null && winnerW != null) ? pvpRaidService.stealXp(loserW, winnerW) : 0;

        if (loserW != null) { loserW.clearBuff(); warriorRepository.save(loserW); }
        loser.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES));
        List<InventoryItem> remaining = inventoryRepository.findAllByPlayer(loser);
        unlockAllItems(remaining);
        inventoryRepository.saveAll(remaining);
        playerRepository.save(loser);

        String loot = bronze + " bronze"
                + (item != null ? ", " + item : "")
                + (res > 0 ? ", " + res + " resources" : "")
                + (xp  > 0 ? ", " + xp + " XP" : "");
        String winnerName = winnerW != null ? winnerW.getName() : "an enemy";
        mailService.sendSystemMail(loser,
            "💀 You were defeated in a GUILD WAR by " + winnerName + "! Lost " + loot
            + ". Protection shield for " + PVP_SHIELD_MINUTES + " min.");
        return loot;
    }

    /** Persiste o HP absoluto do atacante como % no snapshot. */
    private void persistAttackerHp(Warrior attacker, int hpAbs, int maxHp) {
        int pct = maxHp > 0 ? Math.max(0, Math.min(100, hpAbs * 100 / maxHp)) : 0;
        attacker.setCurrentHpSnapshot(pct);
        attacker.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(attacker);
    }

    /** Remove a tag interna WINNER: do final do log (cópia, não muta o original). [VARREDURA] centralizado. */
    private List<String> stripWinnerTag(List<String> log) {
        return BattleSimulator.withoutWinnerTag(log);
    }

    /** [VARREDURA] Cena/fundo do replay do raid pelo reino (espelha ZoneController.sceneFor). */
    private static String pvpScene(com.medieval.game.enums.Kingdom k) {
        if (k == null) return "fortress";
        return switch (k) {
            case FISHING -> "coast";
            case MAR_ABENCOADO -> "sea";
            case MINING, GRUTAS_DE_CRISTAL -> "cave";
            default -> "fortress";
        };
    }

    // ── NPC generation ──

    // [ENEMY_NAMES] O nome do NPC agora vem do KingdomQuestNarrator (temático por bioma + tier),
    // não mais de uma lista genérica por tier. Ver narrator.pickZoneEnemy em fightNpc.

    /** Stats do NPC baseados no nível (até +3 do guerreiro) */
    /** Returns [atk, def, hp, dex, agi, luk] for NPCs. [REBALANCE] dex=acerto, agi=esquiva/velocidade. */
    private int[] npcStatsByLevel(int level, Random rng) {
        // [BALANCE] Monstro de zona nerfado (~30-35% menos HP/ATK, ~25% DEF) — estava forte demais.
        int atk = 3 + level * 2 + rng.nextInt(3);
        int def = 2 + (level * 3) / 2 + rng.nextInt(2);
        int hp  = 50 + level * 13 + rng.nextInt(20);
        int dex = Math.min(10 + level / 2, 35); // acerto do NPC (d20 + dex/5)
        int agi = Math.min(level / 5, 12);      // esquiva/velocidade modesta (raramente golpe extra)
        int luk = Math.min(level / 3, 10);
        return new int[]{atk, def, hp, dex, agi, luk};
    }

    /** Returns [atk, def, hp, dex, strBonus, luk] for d20 simulate(). [AUDITORIA A1/A9] */
    private int[] getWarriorStats(Warrior w, Player player) {
        return statsService.combatStats(player, w).toArray();
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
