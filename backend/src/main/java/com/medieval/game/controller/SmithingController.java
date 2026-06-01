package com.medieval.game.controller;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.repository.SocketedGemRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.SmithingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/smithing")
@RequiredArgsConstructor
public class SmithingController {

    private final SmithingService    smithingService;
    private final GatheringService   gatheringService;
    private final PlayerService      playerService;
    private final SocketedGemRepository gemRepository;

    // Receitas disponíveis
    @GetMapping("/recipes")
    public ResponseEntity<?> getRecipes(Authentication auth) {
        Player player = getPlayer(auth);
        int smithingLevel = gatheringService.getOrCreateSkill(player, SkillType.SMITHING).getLevel();

        var refine = SmithingService.REFINE_RECIPES.stream().map(r -> Map.of(
            "type",        "refine",
            "ore",         r.ore().name(),
            "oreName",     r.ore().displayName,
            "bar",         r.bar().name(),
            "barName",     r.bar().displayName,
            "oreQty",      r.oreQty(),
            "bronzeCost",  r.bronzeCost(),
            "levelRequired", r.smithingLevelRequired(),
            "canCraft",    smithingLevel >= r.smithingLevelRequired()
        )).toList();

        var craft = SmithingService.CRAFT_RECIPES.stream().map(r -> Map.ofEntries(
            Map.entry("type",        "craft"),
            Map.entry("id",          r.id()),
            Map.entry("name",        r.name()),
            Map.entry("ingredients", r.ingredients().entrySet().stream().map(e -> Map.of(
                "resource", e.getKey().name(),
                "name",     e.getKey().displayName,
                "qty",      e.getValue()
            )).toList()),
            Map.entry("levelRequired", r.smithingLevel()),
            Map.entry("atk",         r.atk()),
            Map.entry("def",         r.def()),
            Map.entry("hp",          r.hp()),
            Map.entry("rarity",      r.rarity()),
            Map.entry("sockets",     r.sockets()),
            Map.entry("canCraft",    smithingLevel >= r.smithingLevel())
        )).toList();

        // Joias
        var gems = Arrays.stream(ResourceType.values())
            .filter(rt -> rt.category == ResourceType.ResourceCategory.FRAGMENT)
            .map(frag -> {
                ResourceType gem = gemForFragment(frag);
                return Map.of(
                    "type",         "gem",
                    "fragment",     frag.name(),
                    "fragmentName", frag.displayName,
                    "gem",          gem.name(),
                    "gemName",      gem.displayName,
                    "bonus",        SmithingService.GemBonus.of(gem)
                );
            }).toList();

        return ResponseEntity.ok(Map.of("refine", refine, "craft", craft, "gems", gems));
    }

    // Refinar ore → bar
    @PostMapping("/refine")
    public ResponseEntity<?> refine(@RequestBody RefineRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            smithingService.refineOre(player, req.oreType(), req.quantity());
            return ResponseEntity.ok(Map.of("message",
                req.quantity() + " barra(s) de " + req.oreType().displayName + " criadas!"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Craftar equipamento
    @PostMapping("/craft")
    public ResponseEntity<?> craft(@RequestBody CraftRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            InventoryItem item = smithingService.craftEquipment(player, req.recipeId());
            return ResponseEntity.ok(Map.of(
                "message", item.getName() + " criado com sucesso!",
                "sockets", item.getSockets()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Craftar joia (3 fragmentos → 1 joia)
    @PostMapping("/gem")
    public ResponseEntity<?> craftGem(@RequestBody GemRequest req, Authentication auth) {
        try {
            Player player = getPlayer(auth);
            smithingService.craftGem(player, req.fragmentType());
            return ResponseEntity.ok(Map.of("message", "Joia criada com sucesso!"));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Encaixar joia em item
    @PostMapping("/socket/{itemId}/{gemType}")
    public ResponseEntity<?> socket(@PathVariable Long itemId,
                                    @PathVariable ResourceType gemType,
                                    Authentication auth) {
        try {
            Player     player = getPlayer(auth);
            SocketedGem gem   = smithingService.socketGem(player, itemId, gemType);
            return ResponseEntity.ok(Map.of(
                "message",  gemType.displayName + " encaixada com sucesso!",
                "slotIndex",gem.getSlotIndex()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // Joias encaixadas em um item
    @GetMapping("/gems/{itemId}")
    public ResponseEntity<?> getGems(@PathVariable Long itemId, Authentication auth) {
        getPlayer(auth); // verifica autenticação
        var gems = gemRepository.findAllByItem(new InventoryItem() {{ setId(itemId); }})
                .stream().map(g -> Map.of(
                    "slot",    g.getSlotIndex(),
                    "gem",     g.getGemType().name(),
                    "gemName", g.getGemType().displayName
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

    record RefineRequest(ResourceType oreType, int quantity) {}
    record CraftRequest(String recipeId) {}
    record GemRequest(ResourceType fragmentType) {}
}
