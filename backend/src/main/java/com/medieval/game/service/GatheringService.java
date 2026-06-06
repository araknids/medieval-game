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

    private final SkillLevelRepository        skillRepository;
    private final ResourceInventoryRepository resourceRepository;
    private final WarriorRepository           warriorRepository;
    private final PlayerRepository            playerRepository;
    private final ConcurrentEntityCreator     entityCreator;
    private final InventoryService            inventoryService; // bag space (Inventário V2)

    // ── Habilidades ──

    public SkillLevel getOrCreateSkill(Player player, SkillType skillType) {
        return skillRepository.findByPlayerAndSkillType(player, skillType)
                .orElseGet(() -> {
                    try {
                        return entityCreator.createSkill(player, skillType); // tx própria (REQUIRES_NEW)
                    } catch (org.springframework.dao.DataIntegrityViolationException e) {
                        // criada concorrentemente por outra requisição → relê a linha existente. [AUDITORIA M15]
                        return skillRepository.findByPlayerAndSkillType(player, skillType).orElseThrow();
                    }
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

    /** Recursos NA BAG (stashed=false), com quantidade > 0. */
    public List<ResourceInventory> getResources(Player player) {
        return resourceRepository.findAllByPlayerAndStashed(player, false).stream()
                .filter(r -> r.getQuantity() > 0).toList();
    }

    /**
     * Adiciona recurso à BAG respeitando o limite (Inventário V2: cada unidade = 1 slot).
     * Adiciona só o que cabe; retorna a quantidade efetivamente adicionada (o excedente é perdido).
     */
    @Transactional
    public long addResource(Player player, ResourceType type, long qty) {
        if (qty < 0) throw new IllegalArgumentException("qty must be >= 0"); // [AUDITORIA C2]
        long toAdd = Math.min(qty, inventoryService.bagSpaceLeft(player));
        if (toAdd <= 0) return 0; // bag cheia — nada adicionado
        ResourceInventory inv = resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, false)
                .orElseGet(() -> {
                    ResourceInventory r = new ResourceInventory();
                    r.setPlayer(player); r.setResourceType(type); r.setStashed(false);
                    return r;
                });
        inv.setQuantity(inv.getQuantity() + toAdd);
        resourceRepository.save(inv);
        return toAdd;
    }

    /** Quantidade de um recurso na BAG (0 se não tiver) — usado p/ pré-checar craft/socket. [PROFISSAO_SUCCESS] */
    public long resourceQuantity(Player player, ResourceType type) {
        return resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, false)
                .map(ResourceInventory::getQuantity).orElse(0L);
    }

    @Transactional
    public void removeResource(Player player, ResourceType type, long qty) {
        if (qty < 0) throw new IllegalArgumentException("qty must be >= 0"); // [AUDITORIA C2]
        ResourceInventory inv = resourceRepository.findByPlayerAndResourceTypeAndStashed(player, type, false)
                .orElseThrow(() -> new IllegalStateException("Resource not found"));
        if (inv.getQuantity() < qty) throw new IllegalStateException("Insufficient quantity of " + type.displayName);
        inv.setQuantity(inv.getQuantity() - qty);
        resourceRepository.save(inv);
    }

    // ── Sessão de coleta ──

    public record ResourceDrop(ResourceType type, long quantity) {}
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
        // Combate V2: restauro ACHATADO e reduzido — a pesca deixa de ser fonte infinita de estamina.
        // O tier do peixe agora vale por VENDA e COZINHA, não por estamina (que é só um top-up leve). [COMBATE_V2]
        int stamina = hpFish ? 0 : switch (fishType) {
            case SMALL_FISH    -> 5;
            case SALMON        -> 8;
            case TUNA          -> 11;
            case SHARK         -> 14;
            case LEGENDARY_FISH-> 18;
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

    /** Drops sem persistir, com o pool do REINO (peixe de vida no Mar Abençoado, etc.). [UNIFICAÇÃO_ZONA] */
    public List<ResourceDrop> collectGatheringDropsOnly(SkillType skillType, int level, int durationMinutes,
                                                        com.medieval.game.enums.Kingdom kingdom) {
        return rollDrops(skillType, level, durationMinutes, kingdom);
    }

    private List<ResourceDrop> rollDrops(SkillType skill, int level, int duration,
                                         com.medieval.game.enums.Kingdom kingdom) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        List<ResourceDrop> drops = new ArrayList<>();

        if (skill == SkillType.FISHING) {
            int catches = Math.max(1, duration / 10 + level / 25); // + nível → mais haul. [PROFISSAO_SUCCESS]
            // Mar Abençoado → peixe de VIDA; demais reinos → peixe de ESTAMINA. [REINOS_V2]
            boolean hpPool = kingdom == com.medieval.game.enums.Kingdom.MAR_ABENCOADO;
            Map<ResourceType, Long> fishMap = new HashMap<>();
            for (int i = 0; i < catches; i++) {
                ResourceType fish = hpPool ? rollHpFish(level, rng) : rollFish(level, rng);
                fishMap.merge(fish, 1L, Long::sum);
            }
            fishMap.forEach((t, q) -> drops.add(new ResourceDrop(t, q)));

        } else if (skill == SkillType.MINING) {
            int ores = Math.max(1, duration / 10 + level / 25); // + nível → mais haul. [PROFISSAO_SUCCESS]
            ResourceType oreType = getBestOreForLevel(level);
            drops.add(new ResourceDrop(oreType, ores));
            // Gemas NÃO saem mais da mineração — agora vêm do Garimpo (Reinos V2).

        } else if (skill == SkillType.GARIMPO) {
            // Garimpo: cada rodada tenta achar um fragmento de joia (pode vir vazio).
            int rounds = Math.max(1, duration / 10 + level / 25); // + nível → mais rodadas. [PROFISSAO_SUCCESS]
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

    /** Próximo nível que libera um tier melhor de recurso (0 = tudo liberado). Só p/ coleta. [PROFISSAO_SUCCESS] */
    public int nextTierLevel(int level) {
        for (int t : new int[]{20, 40, 60, 80}) if (level < t) return t;
        return 0;
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
