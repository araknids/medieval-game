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

    /** Libera o guerreiro travado sem sessão ativa */
    @PostMapping("/free")
    public ResponseEntity<?> freeWarrior(Authentication auth) {
        Player player = playerService.findById((Long) auth.getPrincipal());
        boolean freed = warriorService.freeIfStuck(player);
        if (!freed) return ResponseEntity.ok(Map.of("message", "Warrior was already free."));
        return ResponseEntity.ok(buildResponse(warriorService.getWarrior(player), player));
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

        // Buff ativo
        int buffAtk = 0, buffDef = 0, buffHp = 0, buffEva = 0;
        String buffName = "";
        long   buffSecsLeft = 0;
        if (warrior.hasActiveBuff()) {
            var buff = warrior.getActiveBuff();
            buffAtk = buff.atkBonus; buffDef = buff.defBonus;
            buffHp  = buff.hpBonus; buffEva = buff.evasionBonus;
            buffName = buff.icon + " " + buff.displayName;
            buffSecsLeft = Math.max(0,
                java.time.temporal.ChronoUnit.SECONDS.between(
                    java.time.LocalDateTime.now(), warrior.getBuffExpiresAt()));
        }
        // Keep item+gem bonus separate from buff bonus for color-coding in the UI
        int itemBonusAtk = bonusAtk;
        int itemBonusDef = bonusDef;
        int itemBonusHp  = bonusHp;

        bonusAtk += buffAtk; bonusDef += buffDef; bonusHp += buffHp;
        int baseEvasion  = warrior.getEvasionChance(); // from dexterity, before buff
        int totalEvasion = baseEvasion + buffEva;

        // HP actual (with passive regen)
        int hpPercent = warrior.getCalculatedHpPercent();

        // Normalise currency
        long total   = player.getBronze() + player.getSilver() * 100L + player.getGold() * 10_000L;
        long gold    = total / 10_000L;
        long silver  = (total % 10_000L) / 100L;
        long bronze  = total % 100L;

        // VIP fields
        boolean isVip        = player.isVip();
        String vipExpiresAt  = isVip && player.getVipExpiresAt() != null
                               ? player.getVipExpiresAt().toString() : "";
        int arenaFightsToday = player.getArenaFightsToday();
        int arenaFightLimit  = player.getArenaFightLimit();
        int instantQuestsToday = player.getVipInstantQuestsToday();

        // Buff2 (VIP second slot)
        String buff2Name    = "";
        long   buff2SecsLeft = 0;
        if (warrior.hasActiveBuff2()) {
            var buff2 = warrior.getActiveBuff2();
            buff2Name    = buff2.icon + " " + buff2.displayName;
            buff2SecsLeft = Math.max(0,
                java.time.temporal.ChronoUnit.SECONDS.between(
                    java.time.LocalDateTime.now(), warrior.getBuffExpiresAt2()));
        }

        return new WarriorResponse(
                warrior.getId(), warrior.getName(), warrior.getWarriorClass().displayName,
                warrior.getLevel(), warrior.getExperience(), warrior.expNeededForNextLevel(),
                warrior.getTotalBaseAttack(),  warrior.getTotalBaseDefense(),  warrior.getTotalBaseHealth(),
                bonusAtk,                       bonusDef,                       bonusHp,
                warrior.getTotalBaseAttack()  + bonusAtk,
                warrior.getTotalBaseDefense() + bonusDef,
                warrior.getTotalBaseHealth()  + bonusHp,
                itemBonusAtk, itemBonusDef, itemBonusHp,
                buffAtk, buffDef, buffHp, buffEva,
                warrior.getStrength(), warrior.getDexterity(), warrior.getConstitution(), warrior.getLuck(), warrior.getIntellect(),
                warrior.getAvailablePoints(), baseEvasion, totalEvasion,
                warrior.getArmorClass(), warrior.getAttackBonus(),
                player.getCalculatedStamina(), player.getMinutesToFullStamina(),
                bronze, silver, gold,
                player.getRankPoints(),
                hpPercent, warrior.isKnockedOut(),
                buffName, buffSecsLeft,
                warrior.isOnMission(),
                player.getSoulStones(),
                isVip, vipExpiresAt,
                arenaFightsToday, arenaFightLimit, instantQuestsToday,
                buff2Name, buff2SecsLeft
        );
    }

    record WarriorResponse(Long id, String name, String warriorClass, int level,
                           long experience, long expNeeded,
                           int baseAttack,  int baseDefense,  int baseHealth,
                           int bonusAttack, int bonusDefense, int bonusHealth,
                           int totalAttack, int totalDefense, int totalHealth,
                           int itemBonusAttack, int itemBonusDefense, int itemBonusHealth,
                           int buffBonusAttack, int buffBonusDefense, int buffBonusHealth, int buffBonusEvasion,
                           int strength, int dexterity, int constitution, int luck, int intellect,
                           int availablePoints, int baseEvasion, int evasionChance,
                           int armorClass, int attackBonus,
                           int stamina, long minutesToFullStamina,
                           long bronze, long silver, long gold,
                           int rankPoints,
                           int hpPercent, boolean isKnockedOut,
                           String activeBuff, long buffSecondsLeft,
                           boolean onMission,
                           int soulStones,
                           boolean isVip, String vipExpiresAt,
                           int arenaFightsToday, int arenaFightLimit, int instantQuestsToday,
                           String activeBuff2, long buff2SecondsLeft) {}
}
