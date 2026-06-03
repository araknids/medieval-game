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
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatheringService {

    // Teto de HP que o peixe de vida alcança (resto exige Templo/regen). [AUDITORIA A5 / REINOS_V2]
    private static final int FISH_HP_CAP = 90;

    private final GatheringSessionRepository  sessionRepository;
    private final SkillLevelRepository        skillRepository;
    private final ResourceInventoryRepository resourceRepository;
    private final WarriorRepository           warriorRepository;
    private final PlayerRepository            playerRepository;
    private final TerritoryService            territoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // ── Habilidades ──

    public SkillLevel getOrCreateSkill(Player player, SkillType skillType) {
        return skillRepository.findByPlayerAndSkillType(player, skillType)
                .orElseGet(() -> {
                    SkillLevel s = new SkillLevel();
                    s.setPlayer(player);
                    s.setSkillType(skillType);
                    return skillRepository.save(s);
                });
    }

    public List<SkillLevel> getAllSkills(Player player) {
        List<SkillLevel> skills = new ArrayList<>();
        for (SkillType type : SkillType.values()) {
            skills.add(getOrCreateSkill(player, type));
        }
        return skills;
    }

    // ── Inventário de recursos ──

    public List<ResourceInventory> getResources(Player player) {
        return resourceRepository.findAllByPlayer(player).stream()
                .filter(r -> r.getQuantity() > 0).toList();
    }

    @Transactional
    public void addResource(Player player, ResourceType type, long qty) {
        if (qty < 0) throw new IllegalArgumentException("qty must be >= 0"); // [AUDITORIA C2]
        ResourceInventory inv = resourceRepository.findByPlayerAndResourceType(player, type)
                .orElseGet(() -> {
                    ResourceInventory r = new ResourceInventory();
                    r.setPlayer(player); r.setResourceType(type);
                    return r;
                });
        inv.setQuantity(inv.getQuantity() + qty);
        resourceRepository.save(inv);
    }

    @Transactional
    public void removeResource(Player player, ResourceType type, long qty) {
        if (qty < 0) throw new IllegalArgumentException("qty must be >= 0"); // [AUDITORIA C2]
        ResourceInventory inv = resourceRepository.findByPlayerAndResourceType(player, type)
                .orElseThrow(() -> new IllegalStateException("Resource not found"));
        if (inv.getQuantity() < qty) throw new IllegalStateException("Insufficient quantity of " + type.displayName);
        inv.setQuantity(inv.getQuantity() - qty);
        resourceRepository.save(inv);
    }

    // ── Sessão de coleta ──

    public Optional<GatheringSession> getCurrentSession(Player player) {
        return sessionRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS);
    }

    @Transactional
    public GatheringSession startGathering(Player player, SkillType skillType, int durationMinutes) {
        return startGathering(player, skillType, durationMinutes, null);
    }

    @Transactional
    public GatheringSession startGathering(Player player, SkillType skillType, int durationMinutes,
                                           com.medieval.game.enums.Kingdom kingdom) {
        log.info("[GatheringService] player={} action=startGathering skillType={} duration={} kingdom={}", player.getId(), skillType, durationMinutes, kingdom);
        if (sessionRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS).isPresent()) {
            log.warn("[GatheringService] player={} REJECTED: already gathering", player.getId());
            throw new IllegalStateException("You are already gathering");
        }
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        if (warrior.isOnMission()) {
            log.warn("[GatheringService] player={} REJECTED: warrior is busy", player.getId());
            throw new IllegalStateException("Your warrior is busy");
        }

        int minDuration = skillType == SkillType.FISHING ? 5 : 10;
        if (durationMinutes < minDuration || durationMinutes > 60) {
            log.warn("[GatheringService] player={} REJECTED: invalid duration={} for skillType={}", player.getId(), durationMinutes, skillType);
            throw new IllegalArgumentException("Invalid duration");
        }

        // Coletar (pesca/mineração/garimpo) consome estamina proporcional à duração. [REINOS_V2]
        // Pulado em dev/test (instant-complete) pra não atrapalhar os timers zerados.
        if (!instantComplete) {
            int staminaCost = staminaCostFor(durationMinutes);
            int current = player.getCalculatedStamina();
            if (current < staminaCost) {
                log.warn("[GatheringService] player={} REJECTED: estamina {}/{}", player.getId(), current, staminaCost);
                throw new IllegalStateException("Estamina insuficiente (" + current + "/" + staminaCost +
                        "). Coma um peixe ou descanse.");
            }
            player.setCurrentStamina(current - staminaCost);
            player.setStaminaUpdatedAt(LocalDateTime.now());
            playerRepository.save(player);
        }

        SkillLevel skill = getOrCreateSkill(player, skillType);
        int xpReward = durationMinutes * (skill.getLevel() / 10 + 2);

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        GatheringSession session = new GatheringSession();
        session.setPlayer(player);
        session.setSkillType(skillType);
        session.setKingdom(kingdom);
        session.setDurationMinutes(durationMinutes);
        session.setXpReward(xpReward);
        session.setStartedAt(LocalDateTime.now());
        session.setFinishesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusMinutes(durationMinutes));
        GatheringSession saved = sessionRepository.save(session);
        log.info("[GatheringService] player={} action=startGathering OK id={}", player.getId(), saved.getId());
        return saved;
    }

    /** Estamina gasta por uma coleta, proporcional à duração (mín. 5, ~metade dos minutos). */
    static int staminaCostFor(int durationMinutes) {
        return Math.max(5, durationMinutes / 2);
    }

    public record ResourceDrop(ResourceType type, long quantity) {}

    @Transactional
    public List<ResourceDrop> collectGathering(Player player, Long sessionId) {
        log.info("[GatheringService] player={} action=collectGathering sessionId={}", player.getId(), sessionId);
        GatheringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[GatheringService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you");
        }
        if (session.getStatus() == GatheringStatus.COLLECTED) {
            log.warn("[GatheringService] player={} REJECTED: session {} already collected", player.getId(), sessionId);
            throw new IllegalStateException("Already collected");
        }
        if (!session.isReadyToCollect()) {
            long secs = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).getSeconds();
            log.warn("[GatheringService] player={} REJECTED: session {} still in progress, {}s remaining", player.getId(), sessionId, secs);
            throw new IllegalStateException("Ainda coletando. Faltam " + secs + "s");
        }

        SkillLevel skill = getOrCreateSkill(player, session.getSkillType());

        // Territory bonus: fishing or mining yield
        TerritoryService.TerritoryBonus terr = territoryService.getBonusForPlayer(player);
        int yieldBonusPct = session.getSkillType() == com.medieval.game.enums.SkillType.FISHING
                ? terr.fishingBonus()
                : session.getSkillType() == com.medieval.game.enums.SkillType.MINING
                    ? terr.miningBonus()
                    : 0;

        List<ResourceDrop> drops = rollDrops(session.getSkillType(), skill.getLevel(),
                session.getDurationMinutes(), session.getKingdom());

        // Apply yield bonus: extra items proportional to bonus %
        List<ResourceDrop> boostedDrops = drops.stream().map(d -> {
            int bonus = (int) Math.round(d.quantity() * yieldBonusPct / 100.0);
            return bonus > 0 ? new ResourceDrop(d.type(), d.quantity() + bonus) : d;
        }).toList();

        for (ResourceDrop drop : boostedDrops) addResource(player, drop.type(), drop.quantity());

        // XP da skill (also apply territory xp bonus)
        int xpBonus = (int) Math.round(session.getXpReward() * terr.xpBonus() / 100.0);
        addSkillXp(skill, session.getXpReward() + xpBonus);

        // Libera guerreiro
        warriorRepository.findByPlayer(player).ifPresent(w -> { w.setOnMission(false); warriorRepository.save(w); });

        session.setStatus(GatheringStatus.COLLECTED);
        sessionRepository.save(session);
        log.info("[GatheringService] player={} action=collectGathering OK sessionId={} drops={}", player.getId(), sessionId, drops.size());
        return drops;
    }

    @Transactional
    public void cancelGathering(Player player, Long sessionId) {
        log.info("[GatheringService] player={} action=cancelGathering sessionId={}", player.getId(), sessionId);
        GatheringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[GatheringService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you");
        }
        if (session.getStatus() != GatheringStatus.IN_PROGRESS) {
            log.warn("[GatheringService] player={} REJECTED: session {} already finished (status={})", player.getId(), sessionId, session.getStatus());
            throw new IllegalStateException("Session already finished");
        }

        session.setStatus(GatheringStatus.CANCELLED);
        sessionRepository.save(session);
        warriorRepository.findByPlayer(player).ifPresent(w -> { w.setOnMission(false); warriorRepository.save(w); });
        log.info("[GatheringService] player={} action=cancelGathering OK sessionId={}", player.getId(), sessionId);
    }

    // ── Consumir peixe (restaura stamina) ──

    /** Result of eating a fish: restored stamina and HP percent. */
    public record FishResult(int newStamina, int newHpPercent) {}

    // Peixes de VIDA (Mar Abençoado) restauram HP; os demais restauram estamina. [REINOS_V2]
    private static boolean isHpFish(ResourceType t) {
        return switch (t) {
            case CORAL_FISH, ANGEL_FISH, SPIRIT_FISH, SACRED_FISH, PHOENIX_FISH -> true;
            default -> false;
        };
    }

    @Transactional
    public FishResult consumeFish(Player player, ResourceType fishType) {
        if (fishType.category != ResourceType.ResourceCategory.FISH)
            throw new IllegalArgumentException("Not a fish");

        removeResource(player, fishType, 1);

        boolean hpFish = isHpFish(fishType);

        // Peixe de estamina → só estamina; peixe de vida → só HP. [REINOS_V2]
        int stamina = hpFish ? 0 : switch (fishType) {
            case SMALL_FISH    -> 10;
            case SALMON        -> 25;
            case TUNA          -> 40;
            case SHARK         -> 60;
            case LEGENDARY_FISH-> 80;
            default -> 0;
        };
        int hpHeal = !hpFish ? 0 : switch (fishType) {
            case CORAL_FISH  -> 15;
            case ANGEL_FISH  -> 30;
            case SPIRIT_FISH -> 50;
            case SACRED_FISH -> 70;
            case PHOENIX_FISH-> 90;
            default -> 0;
        };

        int newStamina;
        if (stamina > 0) {
            newStamina = Math.min(100, player.getCalculatedStamina() + stamina);
            player.setCurrentStamina(newStamina);
            player.setStaminaUpdatedAt(LocalDateTime.now());
            playerRepository.save(player);
        } else {
            newStamina = player.getCalculatedStamina();
        }

        // Peixe de vida cura HP até o teto de 90% — o restante (90→100%) e reviver de KO
        // exigem Templo (sink pago) ou regen, pra não furar o sink de cura. [AUDITORIA A5 / REINOS_V2]
        int newHp = warriorRepository.findByPlayer(player).map(w -> {
            int cur = w.getCalculatedHpPercent();
            if (hpHeal <= 0) return cur;
            int restored = cur >= FISH_HP_CAP ? cur : Math.min(FISH_HP_CAP, cur + hpHeal);
            w.setCurrentHpSnapshot(restored);
            w.setHpUpdatedAt(LocalDateTime.now());
            warriorRepository.save(w);
            return restored;
        }).orElse(100);

        log.info("[GatheringService] player={} action=consumeFish fish={} stamina={} hp={}",
                player.getId(), fishType, newStamina, newHp);
        return new FishResult(newStamina, newHp);
    }

    // ── Geração de drops (público para ZoneService) ──

    /** Retorna drops sem persistir — usado pela ZoneService (pool padrão, sem reino). */
    public List<ResourceDrop> collectGatheringDropsOnly(SkillType skillType, int level, int durationMinutes) {
        return rollDrops(skillType, level, durationMinutes, null);
    }

    private List<ResourceDrop> rollDrops(SkillType skill, int level, int duration,
                                         com.medieval.game.enums.Kingdom kingdom) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        List<ResourceDrop> drops = new ArrayList<>();

        if (skill == SkillType.FISHING) {
            int catches = Math.max(1, duration / 5);
            // Mar Abençoado → peixe de VIDA; demais reinos → peixe de ESTAMINA. [REINOS_V2]
            boolean hpPool = kingdom == com.medieval.game.enums.Kingdom.MAR_ABENCOADO;
            Map<ResourceType, Long> fishMap = new HashMap<>();
            for (int i = 0; i < catches; i++) {
                ResourceType fish = hpPool ? rollHpFish(level, rng) : rollFish(level, rng);
                fishMap.merge(fish, 1L, Long::sum);
            }
            fishMap.forEach((t, q) -> drops.add(new ResourceDrop(t, q)));

        } else if (skill == SkillType.MINING) {
            int ores = Math.max(1, duration / 10);
            ResourceType oreType = getBestOreForLevel(level);
            drops.add(new ResourceDrop(oreType, ores));
            // Gemas NÃO saem mais da mineração — agora vêm do Garimpo (Reinos V2).

        } else if (skill == SkillType.GARIMPO) {
            // Garimpo: cada rodada tenta achar um fragmento de joia (pode vir vazio).
            int rounds = Math.max(1, duration / 10);
            Map<ResourceType, Long> fragMap = new HashMap<>();
            for (int i = 0; i < rounds; i++) {
                ResourceType frag = rollGarimpoFragment(level, rng);
                if (frag != null) fragMap.merge(frag, 1L, Long::sum);
            }
            fragMap.forEach((t, q) -> drops.add(new ResourceDrop(t, q)));
        }

        return drops;
    }

    /** Fragmento de joia por nível de Garimpo (pode retornar null = veio vazio). */
    private ResourceType rollGarimpoFragment(int level, Random rng) {
        double roll = rng.nextDouble();
        if (level >= 80 && roll < 0.15) return ResourceType.DIAMOND_FRAGMENT;
        if (level >= 60 && roll < 0.30) return ResourceType.EMERALD_FRAGMENT;
        if (level >= 40 && roll < 0.45) return ResourceType.SAPPHIRE_FRAGMENT;
        if (level >= 20 && roll < 0.60) return ResourceType.RUBY_FRAGMENT;
        if (roll < 0.70)                return ResourceType.AMETHYST_FRAGMENT;
        return null; // garimpo sem sucesso nesta rodada
    }

    private ResourceType rollFish(int level, Random rng) {
        double roll = rng.nextDouble();
        if (level >= 80 && roll < 0.05) return ResourceType.LEGENDARY_FISH;
        if (level >= 60 && roll < 0.20) return ResourceType.SHARK;
        if (level >= 40 && roll < 0.40) return ResourceType.TUNA;
        if (level >= 20 && roll < 0.60) return ResourceType.SALMON;
        return ResourceType.SMALL_FISH;
    }

    /** Peixe de VIDA por nível de pesca (Reino Mar Abençoado). [REINOS_V2] */
    private ResourceType rollHpFish(int level, Random rng) {
        double roll = rng.nextDouble();
        if (level >= 80 && roll < 0.05) return ResourceType.PHOENIX_FISH;
        if (level >= 60 && roll < 0.20) return ResourceType.SACRED_FISH;
        if (level >= 40 && roll < 0.40) return ResourceType.SPIRIT_FISH;
        if (level >= 20 && roll < 0.60) return ResourceType.ANGEL_FISH;
        return ResourceType.CORAL_FISH;
    }

    private ResourceType getBestOreForLevel(int level) {
        if (level >= 80) return ResourceType.MITHRIL_ORE;
        if (level >= 60) return ResourceType.GOLD_ORE;
        if (level >= 40) return ResourceType.SILVER_ORE;
        if (level >= 20) return ResourceType.IRON_ORE;
        return ResourceType.COPPER_ORE;
    }

    @Transactional
    public void addSkillXp(SkillLevel skill, int xp) {
        skill.setExperience(skill.getExperience() + xp);
        while (skill.getLevel() < 100 && skill.getExperience() >= skill.expNeededForNextLevel()) {
            skill.setExperience(skill.getExperience() - skill.expNeededForNextLevel());
            skill.setLevel(skill.getLevel() + 1);
        }
        skillRepository.save(skill);
    }
}
