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
        log.info("[GatheringService] player={} action=startGathering skillType={} duration={}", player.getId(), skillType, durationMinutes);
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

        SkillLevel skill = getOrCreateSkill(player, skillType);
        int xpReward = durationMinutes * (skill.getLevel() / 10 + 2);

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        GatheringSession session = new GatheringSession();
        session.setPlayer(player);
        session.setSkillType(skillType);
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

        List<ResourceDrop> drops = rollDrops(session.getSkillType(), skill.getLevel(), session.getDurationMinutes());

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

    @Transactional
    public FishResult consumeFish(Player player, ResourceType fishType) {
        if (fishType.category != ResourceType.ResourceCategory.FISH)
            throw new IllegalArgumentException("Not a fish");

        removeResource(player, fishType, 1);

        int stamina = switch (fishType) {
            case SMALL_FISH    -> 10;
            case SALMON        -> 25;
            case TUNA          -> 40;
            case SHARK         -> 60;
            case LEGENDARY_FISH-> 80;
            default -> 0;
        };
        int hpHeal = switch (fishType) {
            case SMALL_FISH    -> 5;
            case SALMON        -> 15;
            case TUNA          -> 30;
            case SHARK         -> 50;
            case LEGENDARY_FISH-> 100;
            default -> 0;
        };

        int newStamina = Math.min(100, player.getCalculatedStamina() + stamina);
        player.setCurrentStamina(newStamina);
        player.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);

        // Restore HP on the warrior (capped at 100%)
        int newHp = warriorRepository.findByPlayer(player).map(w -> {
            int restored = Math.min(100, w.getCalculatedHpPercent() + hpHeal);
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

    /** Retorna drops sem persistir — usado pela ZoneService */
    public List<ResourceDrop> collectGatheringDropsOnly(SkillType skillType, int level, int durationMinutes) {
        return rollDrops(skillType, level, durationMinutes);
    }

    private List<ResourceDrop> rollDrops(SkillType skill, int level, int duration) {
        Random rng = new Random();
        List<ResourceDrop> drops = new ArrayList<>();

        if (skill == SkillType.FISHING) {
            int catches = Math.max(1, duration / 5);
            Map<ResourceType, Long> fishMap = new HashMap<>();
            for (int i = 0; i < catches; i++) {
                ResourceType fish = rollFish(level, rng);
                fishMap.merge(fish, 1L, Long::sum);
            }
            fishMap.forEach((t, q) -> drops.add(new ResourceDrop(t, q)));

        } else if (skill == SkillType.MINING) {
            int ores = Math.max(1, duration / 10);
            ResourceType oreType = getBestOreForLevel(level);
            drops.add(new ResourceDrop(oreType, ores));

            // Chance de fragmento de joia
            ResourceType fragment = rollFragment(oreType, rng);
            if (fragment != null) drops.add(new ResourceDrop(fragment, 1));
        }

        return drops;
    }

    private ResourceType rollFish(int level, Random rng) {
        double roll = rng.nextDouble();
        if (level >= 80 && roll < 0.05) return ResourceType.LEGENDARY_FISH;
        if (level >= 60 && roll < 0.20) return ResourceType.SHARK;
        if (level >= 40 && roll < 0.40) return ResourceType.TUNA;
        if (level >= 20 && roll < 0.60) return ResourceType.SALMON;
        return ResourceType.SMALL_FISH;
    }

    private ResourceType getBestOreForLevel(int level) {
        if (level >= 80) return ResourceType.MITHRIL_ORE;
        if (level >= 60) return ResourceType.GOLD_ORE;
        if (level >= 40) return ResourceType.SILVER_ORE;
        if (level >= 20) return ResourceType.IRON_ORE;
        return ResourceType.COPPER_ORE;
    }

    private ResourceType rollFragment(ResourceType ore, Random rng) {
        double roll = rng.nextDouble();
        return switch (ore) {
            case IRON_ORE   -> roll < 0.08 ? ResourceType.RUBY_FRAGMENT     : null;
            case SILVER_ORE -> roll < 0.06 ? ResourceType.SAPPHIRE_FRAGMENT  : null;
            case GOLD_ORE   -> roll < 0.04 ? ResourceType.EMERALD_FRAGMENT   : null;
            case MITHRIL_ORE-> roll < 0.02 ? ResourceType.DIAMOND_FRAGMENT   : null;
            default         -> roll < 0.03 ? ResourceType.AMETHYST_FRAGMENT  : null;
        };
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
