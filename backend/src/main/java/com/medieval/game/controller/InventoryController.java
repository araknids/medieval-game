package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.ItemAffixRepository;
import com.medieval.game.repository.SocketedGemRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService      inventoryService;
    private final PlayerService         playerService;
    private final SocketedGemRepository gemRepository;
    private final ItemAffixRepository   affixRepository;
    private final WarriorRepository     warriorRepository; // [ITEM_PROV] resolve o nome do forjador

    /** [ITEM_PROV] Mapa craftedBy(playerId) → nome do guerreiro, p/ a lista de inventário (batch, sem N+1). */
    private Map<Long, String> crafterNames(List<InventoryItem> items) {
        List<Long> ids = items.stream().map(InventoryItem::getCraftedBy).filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return warriorRepository.findByPlayer_IdIn(ids).stream()
                .collect(Collectors.toMap(w -> w.getPlayer().getId(), Warrior::getName, (a, b) -> a));
    }

    /** [ITEM_PROV] Nome do forjador de um item só (equip/unequip); "" se não-forjado. */
    private String crafterName(InventoryItem item) {
        Long cb = item.getCraftedBy();
        if (cb == null) return "";
        return warriorRepository.findByPlayer_IdIn(List.of(cb)).stream().findFirst().map(Warrior::getName).orElse("");
    }

    @GetMapping
    public ResponseEntity<List<ItemResponse>> getInventory(Authentication auth) {
        Player player = getPlayer(auth);
        List<InventoryItem> items = inventoryService.getInventory(player);
        // A9: carrega joias e afixos de TODOS os itens em 1 query cada (evita N+1).
        Map<Long, List<SocketedGem>> gemsByItem = items.isEmpty()
                ? Map.of()
                : gemRepository.findAllByItemIn(items).stream()
                        .collect(Collectors.groupingBy(g -> g.getItem().getId()));
        Map<Long, List<ItemAffix>> affixesByItem = items.isEmpty()
                ? Map.of()
                : affixRepository.findAllByItemIn(items).stream()
                        .collect(Collectors.groupingBy(a -> a.getItem().getId()));
        Map<Long, String> crafters = crafterNames(items); // [ITEM_PROV] nome do forjador (batch)
        return ResponseEntity.ok(
            items.stream()
                .map(i -> ItemResponse.from(i,
                        gemsByItem.getOrDefault(i.getId(), List.of()),
                        affixesByItem.getOrDefault(i.getId(), List.of()), player.getId(),
                        i.getCraftedBy() != null ? crafters.getOrDefault(i.getCraftedBy(), "") : ""))
                .toList()
        );
    }

    @PostMapping("/{id}/equip")
    public ResponseEntity<?> equip(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        InventoryItem item = inventoryService.equip(p, id);
        return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item), affixRepository.findAllByItem(item), p.getId(), crafterName(item)));
    }

    @PostMapping("/{id}/unequip")
    public ResponseEntity<?> unequip(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        InventoryItem item = inventoryService.unequip(p, id);
        return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item), affixRepository.findAllByItem(item), p.getId(), crafterName(item)));
    }

    @PostMapping("/{id}/sell")
    public ResponseEntity<?> sell(@PathVariable Long id, Authentication auth) {
        Player        player = getPlayer(auth);
        InventoryItem item   = inventoryService.sell(player, id);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.item_sold", "{0} sold!", item.getName()),
            "goldEarned", item.getSellPrice(),
            "gold",       player.getGold()
        ));
    }

    // GET /api/inventory/slots — info de bag para o frontend
    @GetMapping("/slots")
    public ResponseEntity<?> slots(Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(Map.of(
            "bagSize",           inventoryService.bagSize(player),
            "maxSlots",          player.getMaxInventorySlots(),
            "inventoryExpanded", player.isInventoryExpanded(),
            "soulStones",        player.getSoulStones()
        ));
    }

    // POST /api/inventory/expand — expande bag (3 SoulStones, permanente)
    @PostMapping("/expand")
    public ResponseEntity<?> expand(Authentication auth) {
        Player player = getPlayer(auth);
        inventoryService.expandInventory(player);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.inventory_expanded", "Inventory expanded to {0} slots!", player.getMaxInventorySlots()),
            "maxSlots",   player.getMaxInventorySlots(),
            "soulStones", player.getSoulStones()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record ItemResponse(Long id, String name, String type, String typeDisplay,
                        int attackBonus, int defenseBonus, int healthBonus,
                        int strBonus, int dexBonus, int lukBonus,
                        int rarity, String rarityName, long sellPrice,
                        int sockets, List<GemSlot> gems, List<AffixLine> affixes,
                        boolean equipped, boolean guarded,
                        String description, String origin,
                        int durability, int itemLevel, boolean pvpLocked,
                        int powerPct, // [DESGASTE] poder do item (0-100; multiplica os stats no combate)
                        String weaponCategory, boolean selfCrafted, // [MERCADOR] forjado por você
                        String outfitTheme, // [OUTFITS_CLASSE] tema visual da armadura (do ITEM)
                        String craftedByName) { // [ITEM_PROV] nome do forjador (vazio se não-forjado / forjado por você)

        static ItemResponse from(InventoryItem i, List<SocketedGem> socketedGems, List<ItemAffix> itemAffixes, Long playerId, String craftedByName) {
            List<GemSlot> gems = socketedGems.stream()
                    .map(g -> new GemSlot(g.getSlotIndex(), g.getGemType().name(), com.medieval.game.service.Messages.tr("gem." + g.getGemType().name() + ".name", g.getGemType().displayName)))
                    .toList();
            List<AffixLine> affixes = itemAffixes.stream()
                    .map(a -> new AffixLine(a.getAffix().name(),
                            com.medieval.game.service.Messages.word(a.getAffix().word), // [I18N_ITENS] afixo no idioma do request
                            a.getAffix().stat.name(), a.getMagnitude()))
                    .toList();
            return new ItemResponse(
                i.getId(), i.getName(),
                i.getType().name(), com.medieval.game.service.Messages.tr("itemtype." + i.getType().name() + ".name", i.getType().displayName),
                i.getAttackBonus(), i.getDefenseBonus(), i.getHealthBonus(),
                i.getStrBonus(), i.getDexBonus(), i.getLukBonus(),
                i.getRarity(), rarityName(i.getRarity()), i.getSellPrice(),
                i.getSockets(), gems, affixes,
                i.isEquipped(), i.isGuarded(),
                i.getDescription() != null ? i.getDescription() : "",
                i.getOrigin()      != null ? i.getOrigin()      : "",
                i.getDurability(), i.getItemLevel(), i.isPvpLocked(),
                i.getPowerPct(), // [DESGASTE]
                i.effectiveWeaponCategory() != null ? i.effectiveWeaponCategory().name() : null, // [CLASSES_ARMAS]
                playerId != null && i.isSelfCraftedBy(playerId), // [MERCADOR] forjado por você
                i.getOutfitTheme() != null ? i.getOutfitTheme() : com.medieval.game.service.InventoryService.outfitThemeFor(i.getName()), // [OUTFITS_CLASSE] fallback p/ legado
                craftedByName != null ? craftedByName : "" // [ITEM_PROV] nome do forjador resolvido pelo controller
            );
        }

        static String rarityName(int r) {
            return switch (r) {
                case 2 -> "Incomum"; case 3 -> "Raro"; case 4 -> "Épico"; case 5 -> "Lendário"; default -> "Comum";
            };
        }
    }

    record GemSlot(int slot, String gem, String gemName) {}

    // Itens V2: linha de afixo p/ a UI — word ("Sharp"/"of the Bear"), stat (ATK…), magnitude.
    record AffixLine(String affix, String word, String stat, int magnitude) {}
}
