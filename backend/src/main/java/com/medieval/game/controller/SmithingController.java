package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.SmithingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/smithing")
@RequiredArgsConstructor
public class SmithingController {

    private final SmithingService    smithingService;
    private final GatheringService   gatheringService;
    private final PlayerService      playerService;
    private final WarriorRepository  warriorRepository;

    // Receitas disponíveis
    @GetMapping("/recipes")
    public ResponseEntity<?> getRecipes(Authentication auth) {
        Player player = getPlayer(auth);
        int smithingLevel = gatheringService.getOrCreateSkill(player, SkillType.SMITHING).getLevel();

        var refine = SmithingService.REFINE_RECIPES.stream().map(r -> Map.of(
            "type",        "refine",
            "ore",         r.ore().name(),
            "oreName",     com.medieval.game.service.Messages.tr("resource." + r.ore().name() + ".name", r.ore().displayName),
            "bar",         r.bar().name(),
            "barName",     com.medieval.game.service.Messages.tr("resource." + r.bar().name() + ".name", r.bar().displayName),
            "oreQty",      r.oreQty(),
            "bronzeCost",  r.bronzeCost(),
            "levelRequired", r.smithingLevelRequired(),
            "canCraft",    smithingLevel >= r.smithingLevelRequired()
        )).toList();

        // [FORJA_ARMADURA] Todas as recipes (trava de arma por classe removida): armas, armadura completa
        // e acessórios. Arma usa o perfil do WeaponType; armadura/acessório usam os stats fixos do recipe.
        // Manda slot/category/material p/ a mini-aba de filtro do front.
        WarriorClass cls = warriorRepository.findByPlayer(player).map(Warrior::getWarriorClass).orElse(WarriorClass.RECRUIT);
        var craft = SmithingService.craftRecipesFor(cls).stream().map(r -> {
            boolean isWeapon = r.type() == com.medieval.game.enums.ItemType.WEAPON;
            com.medieval.game.enums.WeaponType wt = isWeapon ? com.medieval.game.enums.WeaponType.fromName(r.name()) : null;
            int[] st = isWeapon ? wt.stats(r.itemLevel(), r.rarity())
                                : new int[]{ r.atk(), r.def(), r.hp(), 0, 0, 0 };
            String category = isWeapon ? "weapon"
                            : (r.type() == com.medieval.game.enums.ItemType.RING
                            || r.type() == com.medieval.game.enums.ItemType.NECKLACE) ? "accessory" : "armor";
            return Map.<String,Object>ofEntries(
                Map.entry("type",        "craft"),
                Map.entry("id",          r.id()),
                Map.entry("name",        r.name()),
                Map.entry("slot",        r.type().name()),   // ItemType p/ o comparativo (compareHtml)
                Map.entry("category",    category),          // weapon | armor | accessory (filtro)
                Map.entry("material",    r.material()),      // copper|iron|silver|gold|mithril (filtro)
                Map.entry("weaponType",  isWeapon ? com.medieval.game.service.Messages.tr("weapontype." + wt.name() + ".name", wt.displayName) : ""),
                Map.entry("ingredients", r.ingredients().entrySet().stream().map(e -> Map.of(
                    "resource", e.getKey().name(),
                    "name",     com.medieval.game.service.Messages.tr("resource." + e.getKey().name() + ".name", e.getKey().displayName),
                    "qty",      e.getValue()
                )).toList()),
                Map.entry("levelRequired", r.smithingLevel()),
                Map.entry("bronzeCost",  r.bronzeCost()),                              // [PROFISSAO_SUCCESS] taxa por tentativa
                Map.entry("scrapCost",   smithingService.craftWeaponScrap(r)),         // [DESMONTAGEM] Peças p/ forjar arma (0 = não-arma)
                Map.entry("successPct",  smithingService.craftSuccessPct(smithingLevel, r)), // chance no nível atual
                Map.entry("atk",         st[0]),
                Map.entry("def",         st[1]),
                Map.entry("hp",          st[2]),
                Map.entry("str",         st[3]),
                Map.entry("dex",         st[4]),
                Map.entry("luk",         st[5]),
                Map.entry("itemLevel",   r.itemLevel()),
                Map.entry("rarity",      r.rarity()),
                Map.entry("sockets",     r.sockets()),
                Map.entry("canCraft",    smithingLevel >= r.smithingLevel()),
                Map.entry("outfitTheme", com.medieval.game.service.InventoryService.outfitThemeFor(r.name())) // [OUTFITS_CLASSE] preview == item forjado
            );
        }).toList();

        // Joias
        var gems = Arrays.stream(ResourceType.values())
            .filter(rt -> rt.category == ResourceType.ResourceCategory.FRAGMENT)
            .map(frag -> {
                ResourceType gem = gemForFragment(frag);
                return Map.of(
                    "type",         "gem",
                    "fragment",     frag.name(),
                    "fragmentName", com.medieval.game.service.Messages.tr("resource." + frag.name() + ".name", frag.displayName),
                    "gem",          gem.name(),
                    "gemName",      com.medieval.game.service.Messages.tr("gem." + gem.name() + ".name", gem.displayName),
                    "bonus",        SmithingService.GemBonus.of(gem)
                );
            }).toList();

        return ResponseEntity.ok(Map.of("refine", refine, "craft", craft, "gems", gems));
    }

    // Refinar ore → bar
    @PostMapping("/refine")
    public ResponseEntity<?> refine(@Valid @RequestBody RefineRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        SmithingService.RefineResult res = smithingService.refineOre(player, req.oreType(), req.quantity());
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("toast.bars_created", "{0} {1} bar(s) created!", req.quantity(), com.medieval.game.service.Messages.tr("resource." + req.oreType().name() + ".name", req.oreType().displayName)),
            "barType",       res.barType(),       // [REFINE_FEEDBACK] p/ o ícone res_<tipo> no toast
            "batches",       res.batches(),
            "xpGained",      res.xpGained(),
            "smithingLevel", res.smithingLevel(),
            "leveledUp",     res.leveledUp()));
    }

    // Craftar equipamento (pode falhar — success rate cresce com o nível de Forja). [PROFISSAO_SUCCESS]
    @PostMapping("/craft")
    public ResponseEntity<?> craft(@Valid @RequestBody CraftRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        SmithingService.CraftResult res = smithingService.craftEquipment(player, req.recipeId());
        return ResponseEntity.ok(Map.of(
            "success",    res.success(),
            "successPct", res.successPct(),
            "message",    res.message(),
            "mailed",     res.mailed(),
            "sockets",    res.item() != null ? res.item().getSockets() : 0
        ));
    }

    // Craftar joia (3 fragmentos → 1 joia)
    @PostMapping("/gem")
    public ResponseEntity<?> craftGem(@Valid @RequestBody GemRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        smithingService.craftGem(player, req.fragmentType());
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.gem_created", "Gem created successfully!")));
    }

    // Encaixar joia em item (pode falhar — success rate cresce com o nível). [PROFISSAO_SUCCESS]
    @PostMapping("/socket/{itemId}/{gemType}")
    public ResponseEntity<?> socket(@PathVariable Long itemId,
                                    @PathVariable ResourceType gemType,
                                    Authentication auth) {
        Player player = getPlayer(auth);
        SmithingService.SocketResult res = smithingService.socketGem(player, itemId, gemType);
        return ResponseEntity.ok(Map.of(
            "success",    res.success(),
            "successPct", res.successPct(),
            "message",    res.message(),
            "slotIndex",  res.gem() != null ? res.gem().getSlotIndex() : -1
        ));
    }

    // Reparar durabilidade de um item
    @PostMapping("/repair/{itemId}")
    public ResponseEntity<?> repair(@PathVariable Long itemId, Authentication auth) {
        Player player = getPlayer(auth);
        InventoryItem item = smithingService.repairItem(player, itemId);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.item_repaired", "{0} repaired! Durability 100%.", item.getName()),
            "durability", item.getDurability()
        ));
    }

    // [DESMONTAGEM] Desmontar item → Peças (Salvage). Joias são perdidas.
    @PostMapping("/dismantle/{itemId}")
    public ResponseEntity<?> dismantle(@PathVariable Long itemId, Authentication auth) {
        Player player = getPlayer(auth);
        var r = smithingService.dismantleItem(player, itemId);
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("msg.item_dismantled", "Dismantled into {0} Salvage.", r.scrap()),
            "scrap",   r.scrap(),
            "added",   r.added(),
            "mailed",  r.mailed()
        ));
    }

    // Reforjar item (re-rola stats mantendo raridade)
    @PostMapping("/reforge/{itemId}")
    public ResponseEntity<?> reforge(@PathVariable Long itemId, Authentication auth) {
        Player player = getPlayer(auth);
        InventoryItem item = smithingService.reforgeItem(player, itemId);
        return ResponseEntity.ok(Map.of(
            "message",      com.medieval.game.service.Messages.tr("msg.item_reforged", "{0} reforged!", item.getName()),
            "attackBonus",  item.getAttackBonus(),
            "defenseBonus", item.getDefenseBonus(),
            "healthBonus",  item.getHealthBonus()
        ));
    }

    // Joias encaixadas em um item
    @GetMapping("/gems/{itemId}")
    public ResponseEntity<?> getGems(@PathVariable Long itemId, Authentication auth) {
        var gems = smithingService.gemsForOwnedItem(getPlayer(auth), itemId)
                .stream().map(g -> Map.of(
                    "slot",    g.getSlotIndex(),
                    "gem",     g.getGemType().name(),
                    "gemName", com.medieval.game.service.Messages.tr("gem." + g.getGemType().name() + ".name", g.getGemType().displayName)
                )).toList();
        return ResponseEntity.ok(gems);
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private ResourceType gemForFragment(ResourceType frag) {
        return switch (frag) {
            case RUBY_FRAGMENT      -> ResourceType.RUBY;
            case SAPPHIRE_FRAGMENT  -> ResourceType.SAPPHIRE;
            case EMERALD_FRAGMENT   -> ResourceType.EMERALD;
            case DIAMOND_FRAGMENT   -> ResourceType.DIAMOND;
            case AMETHYST_FRAGMENT  -> ResourceType.AMETHYST;
            default -> ResourceType.RUBY;
        };
    }

    record RefineRequest(@NotNull ResourceType oreType, @Min(1) @Max(100000) int quantity) {}
    record CraftRequest(@NotBlank String recipeId) {}
    record GemRequest(@NotNull ResourceType fragmentType) {}
}
