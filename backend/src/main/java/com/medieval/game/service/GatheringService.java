package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

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
                .orElseThrow(() -> new IllegalStateException("Recurso não encontrado"));
        if (inv.getQuantity() < qty) throw new IllegalStateException("Quantidade insuficiente de " + type.displayName);
        inv.setQuantity(inv.getQuantity() - qty);
        resourceRepository.save(inv);
    }

    // ── Sessão de coleta ──

    public Optional<GatheringSession> getCurrentSession(Player player) {
        return sessionRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS);
    }

    @Transactional
    public GatheringSession startGathering(Player player, SkillType skillType, int durationMinutes) {
        if (sessionRepository.findByPlayerAndStatus(player, GatheringStatus.IN_PROGRESS).isPresent()) {
            throw new IllegalStateException("Você já está coletando");
        }
        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Guerreiro não encontrado"));
        if (warrior.isOnMission()) throw new IllegalStateException("Seu guerreiro está ocupado");

        int minDuration = skillType == SkillType.FISHING ? 5 : 10;
        if (durationMinutes < minDuration || durationMinutes > 60) {
            throw new IllegalArgumentException("Duração inválida");
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
        return sessionRepository.save(session);
    }

    public record ResourceDrop(ResourceType type, long quantity) {}

    @Transactional
    public List<ResourceDrop> collectGathering(Player player, Long sessionId) {
        GatheringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));
        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Sessão não é sua");
        if (session.getStatus() == GatheringStatus.COLLECTED)
            throw new IllegalStateException("Já coletado");
        if (!session.isReadyToCollect()) {
            long secs = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).getSeconds();
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
        return drops;
    }

    @Transactional
    public void cancelGathering(Player player, Long sessionId) {
        GatheringSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Sessão não encontrada"));
        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Sessão não é sua");
        if (session.getStatus() != GatheringStatus.IN_PROGRESS)
            throw new IllegalStateException("Sessão já finalizada");

        session.setStatus(GatheringStatus.CANCELLED);
        sessionRepository.save(session);
        warriorRepository.findByPlayer(player).ifPresent(w -> { w.setOnMission(false); warriorRepository.save(w); });
    }

    // ── Consumir peixe (restaura stamina) ──

    @Transactional
    public int consumeFish(Player player, ResourceType fishType) {
        if (fishType.category != ResourceType.ResourceCategory.FISH)
            throw new IllegalArgumentException("Não é um peixe");

        removeResource(player, fishType, 1);

        int stamina = switch (fishType) {
            case SMALL_FISH    -> 10;
            case SALMON        -> 25;
            case TUNA          -> 40;
            case SHARK         -> 60;
            case LEGENDARY_FISH-> 80;
            default -> 0;
        };

        int current = player.getCalculatedStamina();
        int newStamina = Math.min(100, current + stamina);
        player.setCurrentStamina(newStamina);
        player.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);
        return newStamina;
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
