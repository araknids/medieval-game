package com.medieval.game.controller;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.AchievementService;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.WarriorStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [LEADERBOARDS] Perfil público read-only de outro jogador (inspeção): atributos + stats de combate
 * efetivos + itens EQUIPADOS no mesmo shape do inventário (p/ a UI reusar o tooltip rico). Sem dados
 * sensíveis (bag/stash/moeda). Acesso exige JWT (security config); qualquer jogador pode inspecionar.
 */
@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
public class ProfileController {

    private final PlayerRepository playerRepository;
    private final WarriorRepository warriorRepository;
    private final InventoryItemRepository inventoryRepository;
    private final WarriorStatsService statsService;

    @GetMapping("/{id}/profile")
    @Transactional(readOnly = true)
    public ResponseEntity<?> profile(@PathVariable Long id) {
        Player target = playerRepository.findById(id).orElse(null);
        if (target == null) return ResponseEntity.notFound().build();
        Warrior w = warriorRepository.findByPlayer(target).orElse(null);
        if (w == null) return ResponseEntity.notFound().build();

        com.medieval.game.service.CombatStats cs = statsService.combatStats(target, w);

        List<Map<String, Object>> equipped = new ArrayList<>();
        for (InventoryItem i : inventoryRepository.findAllByPlayer(target)) {
            if (i.isEquipped()) equipped.add(itemMap(i));
        }

        return ResponseEntity.ok(Map.of(
                "playerId",    target.getId(),
                "warriorName", w.getName(),
                "title",       AchievementService.titleString(target),
                "level",       w.getLevel(),
                "classId",     w.getWarriorClass() != null ? w.getWarriorClass().name().toLowerCase() : "recruit",
                "gender",      target.getGender() != null ? target.getGender().name().toLowerCase() : "male",
                "attributes",  Map.of("str", w.getStrength(), "dex", w.getDexterity(),
                                      "con", w.getConstitution(), "agi", w.getAgility(), "luk", w.getLuck()),
                "combat",      Map.of("atk", cs.atk(), "def", cs.def(), "hp", cs.hp(),
                                      "dex", cs.dex(), "agi", cs.agi(), "luk", cs.luk()),
                "equipped",    equipped
        ));
    }

    // Mesmo conjunto de campos que o cliente já lê no inventário (ItemTooltipCard) → hover idêntico.
    private Map<String, Object> itemMap(InventoryItem i) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",            i.getId());
        m.put("name",          i.getName());
        m.put("type",          i.getType().name());
        m.put("rarity",        i.getRarity());
        m.put("attackBonus",   i.getAttackBonus());
        m.put("defenseBonus",  i.getDefenseBonus());
        m.put("healthBonus",   i.getHealthBonus());
        m.put("strBonus",      i.getStrBonus());
        m.put("dexBonus",      i.getDexBonus());
        m.put("lukBonus",      i.getLukBonus());
        m.put("sockets",       i.getSockets());
        m.put("itemLevel",     i.getItemLevel());
        m.put("durability",    i.getDurability());
        m.put("equipped",      true);
        m.put("weaponCategory", i.effectiveWeaponCategory() != null ? i.effectiveWeaponCategory().name() : null);
        m.put("outfitTheme",   i.getOutfitTheme() != null ? i.getOutfitTheme()
                : InventoryService.outfitThemeFor(i.getName()));
        m.put("description",   i.getDescription() != null ? i.getDescription() : "");
        return m;
    }
}
