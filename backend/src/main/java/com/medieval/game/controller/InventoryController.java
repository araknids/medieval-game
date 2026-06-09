package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.repository.ItemAffixRepository;
import com.medieval.game.repository.SocketedGemRepository;
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
        return ResponseEntity.ok(
            items.stream()
                .map(i -> ItemResponse.from(i,
                        gemsByItem.getOrDefault(i.getId(), List.of()),
                        affixesByItem.getOrDefault(i.getId(), List.of()), player.getId()))
                .toList()
        );
    }

    @PostMapping("/{id}/equip")
    public ResponseEntity<?> equip(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        InventoryItem item = inventoryService.equip(p, id);
        return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item), affixRepository.findAllByItem(item), p.getId()));
    }

    @PostMapping("/{id}/unequip")
    public ResponseEntity<?> unequip(@PathVariable Long id, Authentication auth) {
        Player p = getPlayer(auth);
        InventoryItem item = inventoryService.unequip(p, id);
        return ResponseEntity.ok(ItemResponse.from(item, gemRepository.findAllByItem(item), affixRepository.findAllByItem(item), p.getId()));
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
                        String weaponCategory, boolean selfCrafted) { // [MERCADOR] forjado por você

        static ItemResponse from(InventoryItem i, List<SocketedGem> socketedGems, List<ItemAffix> itemAffixes, Long playerId) {
            List<GemSlot> gems = socketedGems.stream()
                    .map(g -> new GemSlot(g.getSlotIndex(), g.getGemType().name(), com.medieval.game.service.Messages.tr("gem." + g.getGemType().name() + ".name", g.getGemType().displayName)))
                    .toList();
            List<AffixLine> affixes = itemAffixes.stream()
                    .map(a -> new AffixLine(a.getAffix().name(), a.getAffix().word,
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
                i.effectiveWeaponCategory() != null ? i.effectiveWeaponCategory().name() : null, // [CLASSES_ARMAS]
                playerId != null && i.isSelfCraftedBy(playerId) // [MERCADOR] forjado por você
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
