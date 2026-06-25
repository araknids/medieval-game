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

    // [DESGASTE] Cada reparo corrói 1–5% do poder; abaixo de REPAIR_FLOOR o item não repara mais (só desmontar).
    // [DESMONTAGEM] Reparo/craft-de-arma/encaixe passam a consumir Peças (SCRAP). Números = placeholders.
    private static final int REPAIR_WEAR_MIN = 1, REPAIR_WEAR_MAX = 5, REPAIR_FLOOR = 50;
    private static final long SOCKET_SCRAP   = 2;   // Peças por encaixe de joia (consumidas só no sucesso)

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
        new RefineRecipe(ResourceType.IRON_ORE,   ResourceType.IRON_BAR,   5,  100, 12),
        new RefineRecipe(ResourceType.SILVER_ORE, ResourceType.SILVER_BAR, 5,  250, 24),
        new RefineRecipe(ResourceType.GOLD_ORE,   ResourceType.GOLD_BAR,   5,  500, 36),
        new RefineRecipe(ResourceType.MITHRIL_ORE,ResourceType.MITHRIL_BAR,5, 1000, 45)
    );

    // ── Receitas de craft de equipamento (bronzeCost = taxa por tentativa, perdida na falha) ──
    // itemLevel = nível de poder/equip do item (SEPARADO do smithingLevel = gate de skill). [CLASSES_ARMAS]
    public record CraftRecipe(String id, String name,
                              com.medieval.game.enums.ItemType type, String material,
                              Map<ResourceType, Integer> ingredients,
                              int smithingLevel, long bronzeCost,
                              int atk, int def, int hp, int rarity, int sockets, int itemLevel) {}

    // Geradas por tier de material × tipo de arma (+ 1 armadura por tier). [CLASSES_ARMAS]
    // Arma: stats vêm do perfil do WeaponType (make() sobrescreve) → atk/def/hp aqui = 0.
    // Armadura: stats fixos por tier (sem perfil de tipo).
    private record MatTier(String en, String pt, ResourceType bar, int smithingLevel,
                           long weaponCost, long armorCost, int rarity, int sockets, int itemLevel,
                           int armorDef, int armorHp) {}
    // [BALANCE] Níveis de forja rescalados: o topo (mithril armor = base+5) cai em smithing 50 (era 85) → menos farm.
    private static final List<MatTier> MAT_TIERS = List.of(
        new MatTier("copper",  "Cobre",   ResourceType.COPPER_BAR,   1,  100,  150, 1, 0,  8,  5, 15),
        new MatTier("iron",    "Ferro",   ResourceType.IRON_BAR,    12,  400,  500, 2, 1, 19, 10, 25),
        new MatTier("silver",  "Prata",   ResourceType.SILVER_BAR,  24,  800,  900, 3, 2, 30, 16, 40),
        new MatTier("gold",    "Ouro",    ResourceType.GOLD_BAR,    36, 1200, 1300, 3, 2, 45, 22, 55),
        new MatTier("mithril", "Mithril", ResourceType.MITHRIL_BAR, 45, 1600, 1700, 4, 3, 60, 28, 70)
    );
    private record WeaponKind(String idKey, String pt) {}
    private static final List<WeaponKind> WEAPON_KINDS = List.of(
        new WeaponKind("sword",      "Espada"),
        new WeaponKind("greatsword", "Montante"),
        new WeaponKind("axe",        "Machado"),
        new WeaponKind("mace",       "Marreta"), // [MERCADOR]
        new WeaponKind("spear",      "Lança"),
        new WeaponKind("shortbow",   "Arco Curto"),
        new WeaponKind("longbow",    "Arco Longo")   // [NO_CROSSBOW] besta removida (sem modelo 3D)
    );
    // [FORJA_ARMADURA] Peças de armadura por tier — def/hp escalam o valor-base do peito (armorDef/armorHp).
    private record ArmorKind(com.medieval.game.enums.ItemType type, String idKey, String pt, double defFrac, double hpFrac, int bars) {}
    private static final List<ArmorKind> ARMOR_KINDS = List.of(
        new ArmorKind(com.medieval.game.enums.ItemType.ARMOR,    "armor",    "Armadura", 1.00, 1.00, 5),
        new ArmorKind(com.medieval.game.enums.ItemType.HELMET,   "helmet",   "Elmo",     0.60, 0.60, 3),
        new ArmorKind(com.medieval.game.enums.ItemType.PANTS,    "pants",    "Calça",    0.70, 0.70, 4),
        new ArmorKind(com.medieval.game.enums.ItemType.SHOULDER, "shoulder", "Ombreira", 0.55, 0.50, 3),
        new ArmorKind(com.medieval.game.enums.ItemType.BOOTS,    "boots",    "Botas",    0.50, 0.45, 3),
        new ArmorKind(com.medieval.game.enums.ItemType.GLOVES,   "gloves",   "Luvas",    0.45, 0.45, 3),
        new ArmorKind(com.medieval.game.enums.ItemType.SHIELD,   "shield",   "Escudo",   0.80, 0.30, 4)
    );
    // [FORJA_ARMADURA] Acessórios por tier — atk/def + hp (anel ofensivo, colar defensivo). atk escala o itemLevel.
    private record AccessoryKind(com.medieval.game.enums.ItemType type, String idKey, String pt, double atkFrac, double defFrac, double hpFrac) {}
    private static final List<AccessoryKind> ACCESSORY_KINDS = List.of(
        new AccessoryKind(com.medieval.game.enums.ItemType.RING,     "ring",     "Anel",  0.40, 0.00, 0.50),
        new AccessoryKind(com.medieval.game.enums.ItemType.NECKLACE, "necklace", "Colar", 0.00, 0.40, 0.60)
    );

    public static final List<CraftRecipe> CRAFT_RECIPES = buildRecipes();

    private static List<CraftRecipe> buildRecipes() {
        List<CraftRecipe> list = new java.util.ArrayList<>();
        for (MatTier m : MAT_TIERS) {
            // Armas (perfil de stats vem do WeaponType no make() → atk/def/hp aqui = 0)
            for (WeaponKind w : WEAPON_KINDS) {
                list.add(new CraftRecipe(m.en() + "_" + w.idKey(), w.pt() + " de " + m.pt(),
                        com.medieval.game.enums.ItemType.WEAPON, m.en(),
                        Map.of(m.bar(), 3), m.smithingLevel(), m.weaponCost(),
                        0, 0, 0, m.rarity(), m.sockets(), m.itemLevel()));
            }
            // Armadura completa (peito, elmo, calça, ombreira, botas, luvas, escudo)
            for (ArmorKind a : ARMOR_KINDS) {
                int def = Math.max(1, (int) Math.round(m.armorDef() * a.defFrac()));
                int hp  = Math.max(1, (int) Math.round(m.armorHp()  * a.hpFrac()));
                list.add(new CraftRecipe(m.en() + "_" + a.idKey(), a.pt() + " de " + m.pt(),
                        a.type(), m.en(),
                        Map.of(m.bar(), a.bars()), m.smithingLevel() + 5, m.armorCost(),
                        0, def, hp, m.rarity(), m.sockets(), m.itemLevel()));
            }
            // Acessórios (anel, colar)
            for (AccessoryKind ac : ACCESSORY_KINDS) {
                int atk = (int) Math.round(m.itemLevel() * ac.atkFrac());
                int def = (int) Math.round(m.armorDef()  * ac.defFrac());
                int hp  = (int) Math.round(m.armorHp()   * ac.hpFrac());
                list.add(new CraftRecipe(m.en() + "_" + ac.idKey(), ac.pt() + " de " + m.pt(),
                        ac.type(), m.en(),
                        Map.of(m.bar(), 2), m.smithingLevel() + 2, Math.round(m.armorCost() * 0.8),
                        atk, def, hp, m.rarity(), 0, m.itemLevel()));
            }
        }
        return List.copyOf(list);
    }

    /** Todas as recipes (tier-ordered). A trava de arma por classe foi removida → todas visíveis. [CLASSES_ARMAS] */
    public static List<CraftRecipe> craftRecipesFor(com.medieval.game.enums.WarriorClass cls) {
        return CRAFT_RECIPES.stream()
                .sorted(java.util.Comparator.comparingInt(CraftRecipe::smithingLevel))
                .toList();
    }

    // Resultados (success/falha) p/ o controller exibir ✅/❌. [PROFISSAO_SUCCESS]
    public record CraftResult(boolean success, boolean mailed, int successPct, InventoryItem item, String message) {}
    public record SocketResult(boolean success, int successPct, SocketedGem gem, String message) {}

    // ── Refinar ore → bar ──
    @Transactional
    /** [REFINE_FEEDBACK] Resultado do refino p/ a UI mostrar o XP ganho e o level-up de Forja. */
    public record RefineResult(String barType, int batches, int xpGained, int smithingLevel, boolean leveledUp) {}

    public RefineResult refineOre(Player player, ResourceType oreType, int quantity) {
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
            throw new com.medieval.game.config.LocalizedException("error.smithing_level_low", "Smithing level too low. Required: {0}", recipe.smithingLevelRequired());
        }

        int batches = quantity; // 1 batch = recipe.oreQty() ores → 1 bar
        gatheringService.removeResource(player, oreType, (long) recipe.oreQty() * batches);
        playerService.spendBronze(player, recipe.bronzeCost() * batches);
        gatheringService.addResource(player, recipe.bar(), batches);

        int xp = batches * (recipe.smithingLevelRequired() * 8 + 50); // [BALANCE] mais XP/refino → menos farm
        int levelBefore = smithing.getLevel();
        gatheringService.addSkillXp(smithing, xp);
        log.info("[SmithingService] player={} action=refineOre OK oreType={} batches={} bar={} xp={} level={}",
                player.getId(), oreType, batches, recipe.bar(), xp, smithing.getLevel());
        return new RefineResult(recipe.bar().name(), batches, xp, smithing.getLevel(), smithing.getLevel() > levelBefore);
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
            throw new com.medieval.game.config.LocalizedException("error.smithing_level_low", "Smithing level too low. Required: {0}", recipe.smithingLevel());
        }

        // Pré-checa materiais ANTES de cobrar a taxa/rolar (senão a falha cobraria sem ter como craftar)
        for (var e : recipe.ingredients().entrySet()) {
            if (gatheringService.resourceQuantity(player, e.getKey()) < e.getValue()) {
                log.warn("[SmithingService] player={} REJECTED: missing {} for recipe {}", player.getId(), e.getKey(), recipeId);
                throw new com.medieval.game.config.LocalizedException("error.smithing_material", "Not enough {0}.", e.getKey().displayName);
            }
        }
        // [DESMONTAGEM] Craft de ARMA também consome Peças (SCRAP) — pré-checa junto dos materiais.
        long craftScrap = craftWeaponScrap(recipe);
        if (craftScrap > 0 && gatheringService.resourceQuantity(player, ResourceType.SCRAP) < craftScrap) {
            log.warn("[SmithingService] player={} REJECTED: not enough Salvage to forge weapon {} (need {})", player.getId(), recipeId, craftScrap);
            throw new com.medieval.game.config.LocalizedException("error.craft_scrap",
                    "Not enough Salvage to forge this weapon (need {0}). Dismantle items to get Salvage.", craftScrap);
        }

        int successPct = Math.min(100, craftSuccessPct(smithing.getLevel(), recipe)
                + abilityService.craftSuccessBonus(player)); // [MERCADOR] Master Craftsman
        // Taxa em bronze — paga sempre (é o "custo" perdido na falha). Lança se não tiver saldo.
        playerService.spendBronze(player, recipe.bronzeCost());

        boolean success = java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < successPct;
        if (!success) {
            // Falha: materiais NÃO consumidos; só a taxa foi perdida; XP reduzido.
            gatheringService.addSkillXp(smithing, Math.max(1, recipe.smithingLevel() * 8 + 20)); // [BALANCE] menos farm
            log.info("[SmithingService] player={} action=craftEquipment FAIL recipeId={} successPct={}", player.getId(), recipeId, successPct);
            return new CraftResult(false, false, successPct, null,
                    "Forging failed (" + successPct + "% chance). Materials kept — only the bronze fee was lost.");
        }

        // Sucesso: consome ingredientes (+ Peças se for arma) + cria item + XP cheio.
        recipe.ingredients().forEach((res, qty) -> gatheringService.removeResource(player, res, qty));
        if (craftScrap > 0) gatheringService.removeResource(player, ResourceType.SCRAP, craftScrap); // [DESMONTAGEM]

        com.medieval.game.enums.ItemType itemType = recipe.type();

        String desc   = loreGenerator.generateLore(recipe.rarity(), itemType, new java.util.Random());
        String origin = loreGenerator.originFromSmithing();
        long   sell   = recipe.smithingLevel() * 50L;
        gatheringService.addSkillXp(smithing, recipe.smithingLevel() * 25 + 40); // [BALANCE] mais XP/craft → menos farm

        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            // itemLevel do tier (não o smithingLevel): armas recalculam stats pelo perfil no make(). [CLASSES_ARMAS]
            InventoryItem result = inventoryService.make(player, recipe.name(), itemType,
                    recipe.atk(), recipe.def(), recipe.hp(), recipe.rarity(), sell, recipe.itemLevel(), desc, origin);
            result.setSockets(recipe.sockets());
            result.setCraftedBy(player.getId()); // [MERCADOR] marca o forjador p/ o bônus de self-crafted
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
            throw new com.medieval.game.config.LocalizedException("error.smithing_level_low", "Smithing level too low. Required: {0}", fragmentType.levelRequired);
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
        // [DESMONTAGEM] Encaixar joia consome Peças (SCRAP) — só no sucesso, mas pré-checa aqui.
        if (gatheringService.resourceQuantity(player, ResourceType.SCRAP) < SOCKET_SCRAP) {
            log.warn("[SmithingService] player={} REJECTED: not enough Salvage to socket (need {})", player.getId(), SOCKET_SCRAP);
            throw new com.medieval.game.config.LocalizedException("error.socket_scrap",
                    "Not enough Salvage to set a gem (need {0}). Dismantle items to get Salvage.", SOCKET_SCRAP);
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
        gatheringService.removeResource(player, ResourceType.SCRAP, SOCKET_SCRAP); // [DESMONTAGEM] Peças do encaixe
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

        // [DESGASTE] Piso: abaixo de REPAIR_FLOOR o item está gasto demais — só dá pra desmontar.
        if (item.getPowerPct() < REPAIR_FLOOR) {
            log.warn("[SmithingService] player={} REJECTED: item {} too worn ({}% power)", player.getId(), itemId, item.getPowerPct());
            throw new com.medieval.game.config.LocalizedException("error.repair_worn_out",
                    "This item is too worn to repair ({0}% power). Dismantle it for Salvage.", item.getPowerPct());
        }

        int lostPoints = 100 - item.getDurability();
        if (lostPoints <= 0) {
            log.warn("[SmithingService] player={} REJECTED: item {} already at full durability", player.getId(), itemId);
            throw new IllegalStateException("Item is already at full durability");
        }

        // [DESMONTAGEM] Reparo agora custa Peças (SCRAP) + bronze. Pré-checa as Peças antes de cobrar o bronze.
        long scrapCost = repairScrapCost(item);
        if (gatheringService.resourceQuantity(player, ResourceType.SCRAP) < scrapCost) {
            log.warn("[SmithingService] player={} REJECTED: not enough Salvage to repair item {} (need {})", player.getId(), itemId, scrapCost);
            throw new com.medieval.game.config.LocalizedException("error.repair_scrap",
                    "Not enough Salvage to repair (need {0}). Dismantle items to get Salvage.", scrapCost);
        }

        long cost = repairCost(item);
        playerService.spendBronze(player, cost);
        gatheringService.removeResource(player, ResourceType.SCRAP, scrapCost);

        item.setDurability(100);
        // [DESGASTE] cada reparo corrói 1–5% do poder (multiplica os stats no combate; o piso trava o PRÓXIMO reparo).
        int wear = REPAIR_WEAR_MIN + java.util.concurrent.ThreadLocalRandom.current().nextInt(REPAIR_WEAR_MAX - REPAIR_WEAR_MIN + 1);
        item.setPowerPct(Math.max(0, item.getPowerPct() - wear));
        InventoryItem saved = inventoryRepository.save(item);
        log.info("[SmithingService] player={} action=repairItem OK itemId={} restored={} bronze={} scrap={} power={}",
                player.getId(), itemId, lostPoints, cost, scrapCost, saved.getPowerPct());
        return saved;
    }

    /** Custo de reparo em BRONZE (pontos perdidos × raridade × 5) — para exibição/validação. */
    public long repairCost(InventoryItem item) {
        return (long) (100 - item.getDurability()) * item.getRarity() * 5;
    }

    /** [DESMONTAGEM] Peças (SCRAP) pra reparar — escala com a raridade (placeholder). */
    public long repairScrapCost(InventoryItem item) {
        return Math.max(1, item.getRarity());
    }

    /** [DESMONTAGEM] Peças (SCRAP) pra forjar uma ARMA (0 p/ não-arma). Placeholder. Público p/ a DTO de receita. */
    public long craftWeaponScrap(CraftRecipe r) {
        return r.type() == com.medieval.game.enums.ItemType.WEAPON ? Math.max(2, r.rarity() * 2L) : 0;
    }

    // ── Desmontar item → Peças (SCRAP) [DESMONTAGEM] ──
    public record DismantleResult(int scrap, int added, int mailed) {}

    /** Desmonta o item (InventoryService valida/deleta e devolve a qtd) e credita as Peças; overflow vai por mail. */
    @Transactional
    public DismantleResult dismantleItem(Player player, Long itemId) {
        log.info("[SmithingService] player={} action=dismantle itemId={}", player.getId(), itemId);
        int qty = inventoryService.dismantle(player, itemId);
        long added  = gatheringService.addResource(player, ResourceType.SCRAP, qty);
        long mailed = qty - added;
        if (mailed > 0)
            mailService.sendResourceMail(player, "Desmontagem (bolsa cheia)", ResourceType.SCRAP, (int) mailed);
        return new DismantleResult(qty, (int) added, (int) mailed);
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
        inventoryService.rollAffixesFor(saved, true); // [AFIXOS_NOME] reforge re-rola os afixos E renomeia p/ bater (strip + rebuild)
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
