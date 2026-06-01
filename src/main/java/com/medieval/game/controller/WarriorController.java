package com.medieval.game.controller;

import com.medieval.game.enums.Attribute;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WarriorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/warrior")
@RequiredArgsConstructor
public class WarriorController {

    private final WarriorService    warriorService;
    private final PlayerService     playerService;
    private final InventoryService  inventoryService;

    @GetMapping
    public ResponseEntity<WarriorResponse> getMyWarrior(Authentication auth) {
        Player  player  = playerService.findById((Long) auth.getPrincipal());
        Warrior warrior = warriorService.getWarrior(player);

        List<InventoryItem> equipped = inventoryService.getInventory(player)
                .stream().filter(InventoryItem::isEquipped).toList();

        int bonusAtk = equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
        int bonusDef = equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
        int bonusHp  = equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();

        return ResponseEntity.ok(WarriorResponse.from(warrior, bonusAtk, bonusDef, bonusHp,
                player.getCalculatedStamina(), player.getMinutesToFullStamina(), player));
    }

    // Lista atributos disponíveis (para o frontend montar a tela)
    @GetMapping("/attributes")
    public ResponseEntity<?> getAttributes() {
        var list = Arrays.stream(Attribute.values()).map(a -> Map.of(
                "id",          a.name(),
                "displayName", a.displayName,
                "icon",        a.icon,
                "effect",      a.effect
        )).toList();
        return ResponseEntity.ok(list);
    }

    // Gasta 1 ponto em um atributo (irreversível)
    @PostMapping("/attributes/{attribute}")
    public ResponseEntity<?> spendPoint(@PathVariable Attribute attribute, Authentication auth) {
        try {
            Player  player  = playerService.findById((Long) auth.getPrincipal());
            Warrior warrior = warriorService.spendPoint(player, attribute);

            List<InventoryItem> equipped = inventoryService.getInventory(player)
                    .stream().filter(InventoryItem::isEquipped).toList();
            int bonusAtk = equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
            int bonusDef = equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
            int bonusHp  = equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();

            return ResponseEntity.ok(WarriorResponse.from(warrior, bonusAtk, bonusDef, bonusHp,
                    player.getCalculatedStamina(), player.getMinutesToFullStamina(), player));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    record WarriorResponse(Long id, String name, String warriorClass, int level,
                           long experience, long expNeeded,
                           int baseAttack,  int baseDefense,  int baseHealth,
                           int bonusAttack, int bonusDefense, int bonusHealth,
                           int totalAttack, int totalDefense, int totalHealth,
                           int strength, int dexterity, int constitution, int luck,
                           int availablePoints, int evasionChance,
                           int stamina, long minutesToFullStamina,
                           long bronze, long silver, long gold,
                           int rankPoints,
                           boolean onMission) {
        static WarriorResponse from(Warrior w, int bonusAtk, int bonusDef, int bonusHp,
                                    int stamina, long minsToFull, Player player) {
            return new WarriorResponse(
                    w.getId(), w.getName(), w.getWarriorClass().displayName,
                    w.getLevel(), w.getExperience(), w.expNeededForNextLevel(),
                    w.getTotalBaseAttack(),  w.getTotalBaseDefense(),  w.getTotalBaseHealth(),
                    bonusAtk,                bonusDef,                  bonusHp,
                    w.getTotalBaseAttack()  + bonusAtk,
                    w.getTotalBaseDefense() + bonusDef,
                    w.getTotalBaseHealth()  + bonusHp,
                    w.getStrength(), w.getDexterity(), w.getConstitution(), w.getLuck(),
                    w.getAvailablePoints(), w.getEvasionChance(),
                    stamina, minsToFull,
                    player.getBronze(), player.getSilver(), player.getGold(),
                    player.getRankPoints(),
                    w.isOnMission()
            );
        }
    }
}
