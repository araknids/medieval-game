package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.ResourceType.ResourceCategory;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SmithingService {

    private final GatheringService        gatheringService;
    private final InventoryItemRepository inventoryRepository;
    private final SocketedGemRepository   gemRepository;
    private final PlayerService           playerService;

    // ── Gem bonuses ──
    public record GemBonus(int atk, int def, int hp) {
        public static GemBonus of(ResourceType gem) {
            return switch (gem) {
                case RUBY     -> new GemBonus(5, 0,  0);
                case SAPPHIRE -> new GemBonus(0, 5,  0);
                case EMERALD  -> new GemBonus(0, 0, 20);
                case DIAMOND  -> new GemBonus(3, 3, 10);
                case AMETHYST -> new GemBonus(0, 0,  0); // luck bonus handled elsewhere
                default       -> new GemBonus(0, 0,  0);
            };
        }
    }

    // ── Receitas de refino (ore → bar) ──
    public record RefineRecipe(ResourceType ore, ResourceType bar,
                               int oreQty, long bronzeCost, int smithingLevelRequired) {}

    public static final List<RefineRecipe> REFINE_RECIPES = List.of(
        new RefineRecipe(ResourceType.COPPER_ORE, ResourceType.COPPER_BAR, 5,   50,  1),
        new RefineRecipe(ResourceType.IRON_ORE,   ResourceType.IRON_BAR,   5,  100, 20),
        new RefineRecipe(ResourceType.SILVER_ORE, ResourceType.SILVER_BAR, 5,  250, 40),
        new RefineRecipe(ResourceType.GOLD_ORE,   ResourceType.GOLD_BAR,   5,  500, 60),
        new RefineRecipe(ResourceType.MITHRIL_ORE,ResourceType.MITHRIL_BAR,5, 1000, 80)
    );

    // ── Receitas de craft de equipamento ──
    public record CraftRecipe(String id, String name,
                              Map<ResourceType, Integer> ingredients,
                              int smithingLevel,
                              int atk, int def, int hp, int rarity, int sockets) {}

    public static final List<CraftRecipe> CRAFT_RECIPES = List.of(
        new CraftRecipe("iron_sword",    "Espada de Ferro Forjada",
            Map.of(ResourceType.IRON_BAR, 3), 20, 10, 0, 0, 2, 1),
        new CraftRecipe("iron_armor",    "Armadura de Ferro Forjada",
            Map.of(ResourceType.IRON_BAR, 5), 25, 0, 10, 25, 2, 1),
        new CraftRecipe("silver_sword",  "Espada de Prata Forjada",
            Map.of(ResourceType.SILVER_BAR, 3), 40, 16, 0, 0, 3, 2),
        new CraftRecipe("silver_armor",  "Armadura de Prata Forjada",
            Map.of(ResourceType.SILVER_BAR, 5), 45, 0, 16, 40, 3, 2),
        new CraftRecipe("gold_sword",    "Espada de Ouro Forjada",
            Map.of(ResourceType.GOLD_BAR, 3), 60, 22, 0, 0, 3, 2),
        new CraftRecipe("mithril_sword", "Espada de Mithril Forjada",
            Map.of(ResourceType.MITHRIL_BAR, 3), 80, 28, 0, 0, 4, 3),
        new CraftRecipe("mithril_armor", "Armadura de Mithril Forjada",
            Map.of(ResourceType.MITHRIL_BAR, 5), 85, 0, 28, 70, 4, 3)
    );

    // ── Refinar ore → bar ──
    @Transactional
    public void refineOre(Player player, ResourceType oreType, int quantity) {
        RefineRecipe recipe = REFINE_RECIPES.stream()
                .filter(r -> r.ore() == oreType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receita de refino não encontrada"));

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        if (smithing.getLevel() < recipe.smithingLevelRequired()) {
            throw new IllegalStateException("Nível de Forja insuficiente. Necessário: " + recipe.smithingLevelRequired());
        }

        int batches = quantity; // 1 batch = recipe.oreQty() ores → 1 bar
        gatheringService.removeResource(player, oreType, (long) recipe.oreQty() * batches);
        playerService.spendBronze(player, recipe.bronzeCost() * batches);
        gatheringService.addResource(player, recipe.bar(), batches);

        int xp = batches * recipe.smithingLevelRequired() * 5;
        gatheringService.addSkillXp(smithing, xp);
    }

    // ── Craftar equipamento ──
    @Transactional
    public InventoryItem craftEquipment(Player player, String recipeId) {
        CraftRecipe recipe = CRAFT_RECIPES.stream()
                .filter(r -> r.id().equals(recipeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Receita não encontrada: " + recipeId));

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        if (smithing.getLevel() < recipe.smithingLevel()) {
            throw new IllegalStateException("Nível de Forja insuficiente. Necessário: " + recipe.smithingLevel());
        }

        // Consome ingredientes
        recipe.ingredients().forEach((res, qty) ->
            gatheringService.removeResource(player, res, qty));

        // Cria o item
        InventoryItem item = new InventoryItem();
        item.setPlayer(player);
        item.setName(recipe.name());
        item.setType(com.medieval.game.enums.ItemType.WEAPON); // simplificado por enquanto
        item.setAttackBonus(recipe.atk());
        item.setDefenseBonus(recipe.def());
        item.setHealthBonus(recipe.hp());
        item.setRarity(recipe.rarity());
        item.setSockets(recipe.sockets());
        item.setSellPrice(recipe.smithingLevel() * 50L);

        // Detecta tipo pelo nome
        if (recipe.name().toLowerCase().contains("armadura")) {
            item.setType(com.medieval.game.enums.ItemType.ARMOR);
        }

        int xp = recipe.smithingLevel() * 10;
        gatheringService.addSkillXp(smithing, xp);

        return inventoryRepository.save(item);
    }

    // ── Craftar joia (3 fragmentos → 1 joia) ──
    @Transactional
    public void craftGem(Player player, ResourceType fragmentType) {
        if (fragmentType.category != ResourceCategory.FRAGMENT) {
            throw new IllegalArgumentException("Não é um fragmento");
        }

        gatheringService.removeResource(player, fragmentType, 3);

        ResourceType gem = switch (fragmentType) {
            case RUBY_FRAGMENT      -> ResourceType.RUBY;
            case SAPPHIRE_FRAGMENT  -> ResourceType.SAPPHIRE;
            case EMERALD_FRAGMENT   -> ResourceType.EMERALD;
            case DIAMOND_FRAGMENT   -> ResourceType.DIAMOND;
            case AMETHYST_FRAGMENT  -> ResourceType.AMETHYST;
            default -> throw new IllegalArgumentException("Fragmento inválido");
        };

        gatheringService.addResource(player, gem, 1);

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        gatheringService.addSkillXp(smithing, 30);
    }

    // ── Encaixar joia em item ──
    @Transactional
    public SocketedGem socketGem(Player player, Long itemId, ResourceType gemType) {
        if (gemType.category != ResourceCategory.GEM) {
            throw new IllegalArgumentException("Não é uma joia");
        }

        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item não encontrado"));
        if (!item.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("Item não é seu");

        List<SocketedGem> existing = gemRepository.findAllByItem(item);
        if (existing.size() >= item.getSockets())
            throw new IllegalStateException("Não há sockets disponíveis neste item");

        gatheringService.removeResource(player, gemType, 1);

        int slotIndex = existing.stream().mapToInt(SocketedGem::getSlotIndex).max().orElse(-1) + 1;

        SocketedGem gem = new SocketedGem();
        gem.setItem(item);
        gem.setGemType(gemType);
        gem.setSlotIndex(slotIndex);
        return gemRepository.save(gem);
    }

    // ── Calcula bonus total de joias de um item ──
    public GemBonus totalGemBonus(InventoryItem item) {
        List<SocketedGem> gems = gemRepository.findAllByItem(item);
        int atk = 0, def = 0, hp = 0;
        for (SocketedGem g : gems) {
            GemBonus b = GemBonus.of(g.getGemType());
            atk += b.atk(); def += b.def(); hp += b.hp();
        }
        return new GemBonus(atk, def, hp);
    }
}
