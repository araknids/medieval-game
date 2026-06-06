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
    private final AbilityService          abilityService; // +sucesso de craft do Mercador (Master Craftsman) [MERCADOR]
    private final ItemLoreGenerator       loreGenerator;

    // ── Success rate de craft/socket (melhora com o nível de Forja). [PROFISSAO_SUCCESS] ──
    private static final int  CRAFT_BASE_PCT      = 70;  // % no nível EXATO da receita
    private static final int  CRAFT_STEP_PCT      = 5;   // +%/nível acima da receita (teto 100)
    private static final int  SOCKET_BASE_PCT     = 50;  // % de encaixe no 1º slot
    private static final int  SOCKET_SLOT_PENALTY = 10;  // −%/slot já ocupado (2º/3º mais difícil)
    private static final long SOCKET_FEE_BRONZE   = 150; // taxa por tentativa de encaixe (perdida na falha)

    /** Chance de sucesso (%) do craft p/ o nível de Forja dado. */
    public int craftSuccessPct(int smithingLevel, CraftRecipe recipe) {
        return Math.min(100, CRAFT_BASE_PCT + (smithingLevel - recipe.smithingLevel()) * CRAFT_STEP_PCT);
    }

    /** Chance de sucesso (%) de encaixar uma joia no slot {@code slotIndex} (0-based). */
    public int socketSuccessPct(int smithingLevel, int slotIndex) {
        return Math.min(100, Math.max(5, SOCKET_BASE_PCT + smithingLevel - slotIndex * SOCKET_SLOT_PENALTY));
    }

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

    // ── Receitas de craft de equipamento (bronzeCost = taxa por tentativa, perdida na falha) ──
    // itemLevel = nível de poder/equip do item (SEPARADO do smithingLevel = gate de skill). [CLASSES_ARMAS]
    public record CraftRecipe(String id, String name,
                              Map<ResourceType, Integer> ingredients,
                              int smithingLevel, long bronzeCost,
                              int atk, int def, int hp, int rarity, int sockets, int itemLevel) {}

    // Geradas por tier de material × tipo de arma (+ 1 armadura por tier). [CLASSES_ARMAS]
    // Arma: stats vêm do perfil do WeaponType (make() sobrescreve) → atk/def/hp aqui = 0.
    // Armadura: stats fixos por tier (sem perfil de tipo).
    private record MatTier(String en, String pt, ResourceType bar, int smithingLevel,
                           long weaponCost, long armorCost, int rarity, int sockets, int itemLevel,
                           int armorDef, int armorHp) {}
    private static final List<MatTier> MAT_TIERS = List.of(
        new MatTier("copper",  "Cobre",   ResourceType.COPPER_BAR,   1,  100,  150, 1, 0,  8,  5, 15),
        new MatTier("iron",    "Ferro",   ResourceType.IRON_BAR,    20,  400,  500, 2, 1, 19, 10, 25),
        new MatTier("silver",  "Prata",   ResourceType.SILVER_BAR,  40,  800,  900, 3, 2, 30, 16, 40),
        new MatTier("gold",    "Ouro",    ResourceType.GOLD_BAR,    60, 1200, 1300, 3, 2, 45, 22, 55),
        new MatTier("mithril", "Mithril", ResourceType.MITHRIL_BAR, 80, 1600, 1700, 4, 3, 60, 28, 70)
    );
    private record WeaponKind(String idKey, String pt) {}
    private static final List<WeaponKind> WEAPON_KINDS = List.of(
        new WeaponKind("sword",      "Espada"),
        new WeaponKind("greatsword", "Montante"),
        new WeaponKind("axe",        "Machado"),
        new WeaponKind("mace",       "Marreta"), // [MERCADOR]
        new WeaponKind("spear",      "Lança"),
        new WeaponKind("shortbow",   "Arco Curto"),
        new WeaponKind("longbow",    "Arco Longo"),
        new WeaponKind("crossbow",   "Besta")
    );

    public static final List<CraftRecipe> CRAFT_RECIPES = buildRecipes();

    private static List<CraftRecipe> buildRecipes() {
        List<CraftRecipe> list = new java.util.ArrayList<>();
        for (MatTier m : MAT_TIERS) {
            for (WeaponKind w : WEAPON_KINDS) {
                list.add(new CraftRecipe(m.en() + "_" + w.idKey(), w.pt() + " de " + m.pt(),
                        Map.of(m.bar(), 3), m.smithingLevel(), m.weaponCost(),
                        0, 0, 0, m.rarity(), m.sockets(), m.itemLevel())); // arma: stats via perfil no make()
            }
            list.add(new CraftRecipe(m.en() + "_armor", "Armadura de " + m.pt(),
                    Map.of(m.bar(), 5), m.smithingLevel() + 5, m.armorCost(),
                    0, m.armorDef(), m.armorHp(), m.rarity(), m.sockets(), m.itemLevel()));
        }
        return List.copyOf(list);
    }

    /** Recipes visíveis p/ a classe: armaduras + as armas que a classe consegue equipar (tier-ordered). [CLASSES_ARMAS/MERCADOR] */
    public static List<CraftRecipe> craftRecipesFor(com.medieval.game.enums.WarriorClass cls) {
        return CRAFT_RECIPES.stream()
                .filter(r -> isArmorRecipe(r)
                        || cls.canEquip(com.medieval.game.enums.WeaponType.fromName(r.name())))
                .sorted(java.util.Comparator.comparingInt(CraftRecipe::smithingLevel))
                .toList();
    }

    private static boolean isArmorRecipe(CraftRecipe r) {
        return r.name().toLowerCase().contains("armadura");
    }

    // Resultados (success/falha) p/ o controller exibir ✅/❌. [PROFISSAO_SUCCESS]
    public record CraftResult(boolean success, boolean mailed, int successPct, InventoryItem item, String message) {}
    public record SocketResult(boolean success, int successPct, SocketedGem gem, String message) {}

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

    // ── Craftar equipamento (com chance de falha que melhora com o nível). [PROFISSAO_SUCCESS] ──
    @Transactional
    public CraftResult craftEquipment(Player player, String recipeId) {
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

        // Pré-checa materiais ANTES de cobrar a taxa/rolar (senão a falha cobraria sem ter como craftar)
        for (var e : recipe.ingredients().entrySet()) {
            if (gatheringService.resourceQuantity(player, e.getKey()) < e.getValue()) {
                log.warn("[SmithingService] player={} REJECTED: missing {} for recipe {}", player.getId(), e.getKey(), recipeId);
                throw new IllegalStateException("Not enough " + e.getKey().displayName + ".");
            }
        }

        int successPct = Math.min(100, craftSuccessPct(smithing.getLevel(), recipe)
                + abilityService.craftSuccessBonus(player)); // [MERCADOR] Master Craftsman
        // Taxa em bronze — paga sempre (é o "custo" perdido na falha). Lança se não tiver saldo.
        playerService.spendBronze(player, recipe.bronzeCost());

        boolean success = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < successPct;
        if (!success) {
            // Falha: materiais NÃO consumidos; só a taxa foi perdida; XP reduzido.
            gatheringService.addSkillXp(smithing, Math.max(1, recipe.smithingLevel() * 3));
            log.info("[SmithingService] player={} action=craftEquipment FAIL recipeId={} successPct={}", player.getId(), recipeId, successPct);
            return new CraftResult(false, false, successPct, null,
                    "Forging failed (" + successPct + "% chance). Materials kept — only the bronze fee was lost.");
        }

        // Sucesso: consome ingredientes + cria item + XP cheio.
        recipe.ingredients().forEach((res, qty) -> gatheringService.removeResource(player, res, qty));

        com.medieval.game.enums.ItemType itemType = recipe.name().toLowerCase().contains("armadura")
                ? com.medieval.game.enums.ItemType.ARMOR
                : com.medieval.game.enums.ItemType.WEAPON;

        String desc   = loreGenerator.generateLore(recipe.rarity(), itemType, new java.util.Random());
        String origin = loreGenerator.originFromSmithing();
        long   sell   = recipe.smithingLevel() * 50L;
        gatheringService.addSkillXp(smithing, recipe.smithingLevel() * 10);

        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            // itemLevel do tier (não o smithingLevel): armas recalculam stats pelo perfil no make(). [CLASSES_ARMAS]
            InventoryItem result = inventoryService.make(player, recipe.name(), itemType,
                    recipe.atk(), recipe.def(), recipe.hp(), recipe.rarity(), sell, recipe.itemLevel(), desc, origin);
            result.setSockets(recipe.sockets());
            inventoryRepository.save(result);
            log.info("[SmithingService] player={} action=craftEquipment OK recipeId={} itemId={} name={}", player.getId(), recipeId, result.getId(), result.getName());
            return new CraftResult(true, false, successPct, result, "Crafted successfully!");
        } else {
            mailService.sendItemMail(player, "Forjado na Oficina.",
                    recipe.name(), itemType, recipe.atk(), recipe.def(), recipe.hp(),
                    recipe.rarity(), recipe.itemLevel(), recipe.sockets(), desc, origin);
            log.info("[SmithingService] player={} action=craftEquipment OK (mail — bag full) recipeId={}", player.getId(), recipeId);
            return new CraftResult(true, true, successPct, null, "Crafted! Bag was full — sent to your mailbox.");
        }
    }

    // ── Craftar joia (3 fragmentos → 1 joia) ──
    @Transactional
    public void craftGem(Player player, ResourceType fragmentType) {
        log.info("[SmithingService] player={} action=craftGem fragmentType={}", player.getId(), fragmentType);
        if (fragmentType.category != ResourceCategory.FRAGMENT) {
            log.warn("[SmithingService] player={} REJECTED: {} is not a gem fragment", player.getId(), fragmentType);
            throw new IllegalArgumentException("Not a gem fragment");
        }

        // Gate de nível por fragmento (Rubi 20, Safira 40, Esmeralda 60, Diamante 80). [PROFISSAO_SUCCESS]
        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        if (smithing.getLevel() < fragmentType.levelRequired) {
            log.warn("[SmithingService] player={} REJECTED: smithing level {} too low for gem {} (required {})", player.getId(), smithing.getLevel(), fragmentType, fragmentType.levelRequired);
            throw new IllegalStateException("Smithing level too low. Required: " + fragmentType.levelRequired);
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

        gatheringService.addSkillXp(smithing, 30);
        log.info("[SmithingService] player={} action=craftGem OK fragment={} gem={}", player.getId(), fragmentType, gem);
    }

    // ── Encaixar joia em item (com chance de falha que melhora com o nível). [PROFISSAO_SUCCESS] ──
    @Transactional
    public SocketResult socketGem(Player player, Long itemId, ResourceType gemType) {
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
        if (gatheringService.resourceQuantity(player, gemType) < 1) {
            log.warn("[SmithingService] player={} REJECTED: no {} in bag", player.getId(), gemType);
            throw new IllegalStateException("You don't have that gem.");
        }

        int slotIndex = existing.size(); // próximo slot (0-based)
        SkillLevel smithing = gatheringService.getOrCreateSkill(player, SkillType.SMITHING);
        int successPct = socketSuccessPct(smithing.getLevel(), slotIndex);

        // Taxa em bronze por tentativa — perdida na falha (a joia NÃO some).
        playerService.spendBronze(player, SOCKET_FEE_BRONZE);

        boolean success = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < successPct;
        if (!success) {
            gatheringService.addSkillXp(smithing, 5);
            log.info("[SmithingService] player={} action=socketGem FAIL itemId={} slot={} successPct={}", player.getId(), itemId, slotIndex, successPct);
            return new SocketResult(false, successPct, null,
                    "Socketing failed (" + successPct + "% chance). Gem kept — only the bronze fee was lost.");
        }

        gatheringService.removeResource(player, gemType, 1);
        SocketedGem gem = new SocketedGem();
        gem.setItem(item);
        gem.setGemType(gemType);
        gem.setSlotIndex(slotIndex);
        SocketedGem saved = gemRepository.save(gem);
        gatheringService.addSkillXp(smithing, 15);
        log.info("[SmithingService] player={} action=socketGem OK itemId={} gemType={} slot={}", player.getId(), itemId, gemType, slotIndex);
        return new SocketResult(true, successPct, saved, "Gem socketed!");
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
