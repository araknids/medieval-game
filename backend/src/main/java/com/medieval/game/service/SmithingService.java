package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.ResourceType.ResourceCategory;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SmithingService {

    private final GatheringService        gatheringService;
    private final InventoryItemRepository inventoryRepository;
    private final InventoryService        inventoryService;
    private final MailService             mailService;
    private final SocketedGemRepository   gemRepository;
    private final PlayerService           playerService;
    private final ItemLoreGenerator       loreGenerator;

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
        log.info("[SmithingService] player={} action=refineOre oreType={} quantity={}", player.getId(), oreType, quantity);
        // SEGURANÇA: quantidade negativa/zero faria spendBronze e removeResource creditarem
        // recurso/dinheiro (guardas "saldo < negativo" são sempre falsas). [AUDITORIA C2]
        if (quantity < 1 || quantity > 1000) {
            log.warn("[SmithingService] player={} REJECTED: invalid refine quantity {}", player.getId(), quantity);
            throw new IllegalArgumentException("Quantity must be between 1 and 1000.");
        }
        RefineRecipe recipe = REFINE_RECIPES.stream()
                .filter(r -> r.ore() == oreType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refining recipe not found"));

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        if (smithing.getLevel() < recipe.smithingLevelRequired()) {
            log.warn("[SmithingService] player={} REJECTED: smithing level {} too low for {} (required {})", player.getId(), smithing.getLevel(), oreType, recipe.smithingLevelRequired());
            throw new IllegalStateException("Smithing level too low. Required: " + recipe.smithingLevelRequired());
        }

        int batches = quantity; // 1 batch = recipe.oreQty() ores → 1 bar
        gatheringService.removeResource(player, oreType, (long) recipe.oreQty() * batches);
        playerService.spendBronze(player, recipe.bronzeCost() * batches);
        gatheringService.addResource(player, recipe.bar(), batches);

        int xp = batches * recipe.smithingLevelRequired() * 5;
        gatheringService.addSkillXp(smithing, xp);
        log.info("[SmithingService] player={} action=refineOre OK oreType={} batches={} bar={}", player.getId(), oreType, batches, recipe.bar());
    }

    // ── Craftar equipamento ──
    @Transactional
    public InventoryItem craftEquipment(Player player, String recipeId) {
        log.info("[SmithingService] player={} action=craftEquipment recipeId={}", player.getId(), recipeId);
        CraftRecipe recipe = CRAFT_RECIPES.stream()
                .filter(r -> r.id().equals(recipeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Recipe not found: " + recipeId));

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        if (smithing.getLevel() < recipe.smithingLevel()) {
            log.warn("[SmithingService] player={} REJECTED: smithing level {} too low for recipe {} (required {})", player.getId(), smithing.getLevel(), recipeId, recipe.smithingLevel());
            throw new IllegalStateException("Smithing level too low. Required: " + recipe.smithingLevel());
        }

        // Consome ingredientes
        recipe.ingredients().forEach((res, qty) ->
            gatheringService.removeResource(player, res, qty));

        // Detecta tipo pelo nome
        com.medieval.game.enums.ItemType itemType = recipe.name().toLowerCase().contains("armadura")
                ? com.medieval.game.enums.ItemType.ARMOR
                : com.medieval.game.enums.ItemType.WEAPON;

        String desc   = loreGenerator.generateLore(recipe.rarity(), itemType, new java.util.Random());
        String origin = loreGenerator.originFromSmithing();
        long   sell   = recipe.smithingLevel() * 50L;

        int xp = recipe.smithingLevel() * 10;
        gatheringService.addSkillXp(smithing, xp);

        InventoryItem result;
        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            result = inventoryService.make(player, recipe.name(), itemType,
                    recipe.atk(), recipe.def(), recipe.hp(), recipe.rarity(), sell, recipe.smithingLevel(), desc, origin); // Itens V3: nível = nível do recipe
            result.setSockets(recipe.sockets());
            inventoryRepository.save(result);
            log.info("[SmithingService] player={} action=craftEquipment OK recipeId={} itemId={} name={}", player.getId(), recipeId, result.getId(), result.getName());
        } else {
            mailService.sendItemMail(player, "Forjado na Oficina.",
                    recipe.name(), itemType, recipe.atk(), recipe.def(), recipe.hp(),
                    recipe.rarity(), recipe.sockets(), desc, origin);
            log.info("[SmithingService] player={} action=craftEquipment OK (sent to mail — bag full) recipeId={}", player.getId(), recipeId);
            result = null;
        }
        return result;
    }

    // ── Craftar joia (3 fragmentos → 1 joia) ──
    @Transactional
    public void craftGem(Player player, ResourceType fragmentType) {
        log.info("[SmithingService] player={} action=craftGem fragmentType={}", player.getId(), fragmentType);
        if (fragmentType.category != ResourceCategory.FRAGMENT) {
            log.warn("[SmithingService] player={} REJECTED: {} is not a gem fragment", player.getId(), fragmentType);
            throw new IllegalArgumentException("Not a gem fragment");
        }

        gatheringService.removeResource(player, fragmentType, 3);

        ResourceType gem = switch (fragmentType) {
            case RUBY_FRAGMENT      -> ResourceType.RUBY;
            case SAPPHIRE_FRAGMENT  -> ResourceType.SAPPHIRE;
            case EMERALD_FRAGMENT   -> ResourceType.EMERALD;
            case DIAMOND_FRAGMENT   -> ResourceType.DIAMOND;
            case AMETHYST_FRAGMENT  -> ResourceType.AMETHYST;
            default -> throw new IllegalArgumentException("Invalid fragment");
        };

        gatheringService.addResource(player, gem, 1);

        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        gatheringService.addSkillXp(smithing, 30);
        log.info("[SmithingService] player={} action=craftGem OK fragment={} gem={}", player.getId(), fragmentType, gem);
    }

    // ── Encaixar joia em item ──
    @Transactional
    public SocketedGem socketGem(Player player, Long itemId, ResourceType gemType) {
        log.info("[SmithingService] player={} action=socketGem itemId={} gemType={}", player.getId(), itemId, gemType);
        if (gemType.category != ResourceCategory.GEM) {
            log.warn("[SmithingService] player={} REJECTED: {} is not a gem", player.getId(), gemType);
            throw new IllegalArgumentException("Not a gem");
        }

        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[SmithingService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("Item does not belong to you");
        }

        List<SocketedGem> existing = gemRepository.findAllByItem(item);
        if (existing.size() >= item.getSockets()) {
            log.warn("[SmithingService] player={} REJECTED: no sockets available on item {} (used {}/{})", player.getId(), itemId, existing.size(), item.getSockets());
            throw new IllegalStateException("No sockets available on this item");
        }

        gatheringService.removeResource(player, gemType, 1);

        int slotIndex = existing.stream().mapToInt(SocketedGem::getSlotIndex).max().orElse(-1) + 1;

        SocketedGem gem = new SocketedGem();
        gem.setItem(item);
        gem.setGemType(gemType);
        gem.setSlotIndex(slotIndex);
        SocketedGem saved = gemRepository.save(gem);
        log.info("[SmithingService] player={} action=socketGem OK itemId={} gemType={} slot={}", player.getId(), itemId, gemType, slotIndex);
        return saved;
    }

    // ── Reparar durabilidade de um item (sink econômico) ──
    // Custo = pontos perdidos × raridade × 5 bronze. Restaura durabilidade para 100.
    @Transactional
    public InventoryItem repairItem(Player player, Long itemId) {
        log.info("[SmithingService] player={} action=repairItem itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[SmithingService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("Item does not belong to you");
        }

        int lostPoints = 100 - item.getDurability();
        if (lostPoints <= 0) {
            log.warn("[SmithingService] player={} REJECTED: item {} already at full durability", player.getId(), itemId);
            throw new IllegalStateException("Item is already at full durability");
        }

        long cost = (long) lostPoints * item.getRarity() * 5;
        playerService.spendBronze(player, cost);

        item.setDurability(100);
        InventoryItem saved = inventoryRepository.save(item);
        log.info("[SmithingService] player={} action=repairItem OK itemId={} restored={} cost={}", player.getId(), itemId, lostPoints, cost);
        return saved;
    }

    /** Custo de reparo (pontos perdidos × raridade × 5) — para exibição/validação. */
    public long repairCost(InventoryItem item) {
        return (long) (100 - item.getDurability()) * item.getRarity() * 5;
    }

    // Piso de qualidade da reforja: total mínimo = 45% do máximo possível para a raridade. [AUDITORIA A4]
    private static final double REFORGE_FLOOR_PCT = 0.45;

    // ── Reforjar item: re-rola os stats mantendo a raridade (sink econômico) ──
    // Custo = raridade³ × 500 bronze (encarecido para conter re-roll abusivo). [AUDITORIA A4]
    @Transactional
    public InventoryItem reforgeItem(Player player, Long itemId) {
        log.info("[SmithingService] player={} action=reforgeItem itemId={}", player.getId(), itemId);
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            log.warn("[SmithingService] player={} REJECTED: item {} does not belong to this player", player.getId(), itemId);
            throw new IllegalStateException("Item does not belong to you");
        }

        long cost = reforgeCost(item);
        playerService.spendBronze(player, cost);

        int rarity = item.getRarity();

        // Re-rola os stats com a mesma distribuição usada na geração de drops
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        int maxAtk = rarity * 3, maxDef = rarity * 3, maxHp = rarity * 12;
        int atk = rng.nextInt(maxAtk + 1);
        int def = rng.nextInt(maxDef + 1);
        int hp  = rng.nextInt(maxHp  + 1);

        // A4: piso de qualidade — uma reforja (cara) nunca entrega um item abaixo de ~45% do máximo
        // possível para a raridade. Protege itens de loja/craft de virarem lixo num re-roll ruim.
        int floorTotal = (int) Math.round((maxAtk + maxDef + maxHp) * REFORGE_FLOOR_PCT);
        int deficit = floorTotal - (atk + def + hp);
        if (deficit > 0) {
            hp = Math.min(maxHp, hp + deficit); // completa no HP (maior orçamento) até atingir o piso
        }

        item.setAttackBonus(atk);
        item.setDefenseBonus(def);
        item.setHealthBonus(hp);
        InventoryItem saved = inventoryRepository.save(item);
        inventoryService.rollAffixesFor(saved, false); // Itens V2: reforge re-rola os afixos (mantém o nome)
        log.info("[SmithingService] player={} action=reforgeItem OK itemId={} atk={} def={} hp={} cost={}", player.getId(), itemId, atk, def, hp, cost);
        return saved;
    }

    /** Custo de reforja (raridade³ × 500) — para exibição/validação. [AUDITORIA A4] */
    public long reforgeCost(InventoryItem item) {
        long r = item.getRarity();
        return r * r * r * 500;
    }

    // ── Joias de um item do próprio jogador (valida ownership) ── [AUDITORIA B2]
    @Transactional(readOnly = true)
    public List<SocketedGem> gemsForOwnedItem(Player player, Long itemId) {
        InventoryItem item = inventoryRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Item not found"));
        if (!item.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Item does not belong to you");
        }
        return gemRepository.findAllByItem(item);
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
