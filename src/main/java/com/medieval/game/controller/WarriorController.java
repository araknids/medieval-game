package com.medieval.game.controller;

import com.medieval.game.enums.Attribute;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.SmithingService;
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
    private final SmithingService   smithingService;

    @GetMapping
    public ResponseEntity<WarriorResponse> getMyWarrior(Authentication auth) {
        Player  player  = playerService.findById((Long) auth.getPrincipal());
        Warrior warrior = warriorService.getWarrior(player);
        return ResponseEntity.ok(buildResponse(warrior, player));
    }

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

    @PostMapping("/attributes/{attribute}")
    public ResponseEntity<?> spendPoint(@PathVariable Attribute attribute, Authentication auth) {
        try {
            Player  player  = playerService.findById((Long) auth.getPrincipal());
            Warrior warrior = warriorService.spendPoint(player, attribute);
            return ResponseEntity.ok(buildResponse(warrior, player));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helper ──

    private WarriorResponse buildResponse(Warrior warrior, Player player) {
        List<InventoryItem> equipped = inventoryService.getInventory(player)
                .stream().filter(InventoryItem::isEquipped).toList();

        int bonusAtk = equipped.stream().mapToInt(InventoryItem::getAttackBonus).sum();
        int bonusDef = equipped.stream().mapToInt(InventoryItem::getDefenseBonus).sum();
        int bonusHp  = equipped.stream().mapToInt(InventoryItem::getHealthBonus).sum();

        // Soma bônus das joias encaixadas nos itens equipados
        for (InventoryItem item : equipped) {
            SmithingService.GemBonus gem = smithingService.totalGemBonus(item);
            bonusAtk += gem.atk();
            bonusDef += gem.def();
            bonusHp  += gem.hp();
        }

        // Normaliza a moeda: silver pode ter > 100 por migração ou seeds diretas
        long total   = player.getBronze() + player.getSilver() * 100L + player.getGold() * 10_000L;
        long gold    = total / 10_000L;
        long silver  = (total % 10_000L) / 100L;
        long bronze  = total % 100L;

        return new WarriorResponse(
                warrior.getId(), warrior.getName(), warrior.getWarriorClass().displayName,
                warrior.getLevel(), warrior.getExperience(), warrior.expNeededForNextLevel(),
                warrior.getTotalBaseAttack(),  warrior.getTotalBaseDefense(),  warrior.getTotalBaseHealth(),
                bonusAtk,                       bonusDef,                       bonusHp,
                warrior.getTotalBaseAttack()  + bonusAtk,
                warrior.getTotalBaseDefense() + bonusDef,
                warrior.getTotalBaseHealth()  + bonusHp,
                warrior.getStrength(), warrior.getDexterity(), warrior.getConstitution(), warrior.getLuck(),
                warrior.getAvailablePoints(), warrior.getEvasionChance(),
                player.getCalculatedStamina(), player.getMinutesToFullStamina(),
                bronze, silver, gold,
                player.getRankPoints(),
                warrior.isOnMission()
        );
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
                           boolean onMission) {}
}
