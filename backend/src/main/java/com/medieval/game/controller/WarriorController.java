package com.medieval.game.controller;

import com.medieval.game.enums.Attribute;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.MountRepository;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WarriorService;
import com.medieval.game.service.WarriorStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/warrior")
@RequiredArgsConstructor
public class WarriorController {

    private final WarriorService     warriorService;
    private final PlayerService      playerService;
    private final WarriorStatsService statsService;
    private final MountRepository    mountRepository;

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
        Player  player  = playerService.findById((Long) auth.getPrincipal());
        Warrior warrior = warriorService.spendPoint(player, attribute);
        return ResponseEntity.ok(buildResponse(warrior, player));
    }

    // ── Helper ──

    private WarriorResponse buildResponse(Warrior warrior, Player player) {
        // Bônus de itens equipados + joias (fonte única, mesmo cálculo do combate) [AUDITORIA A1/A9]
        WarriorStatsService.ItemBonus ib = statsService.equippedItemBonus(player);
        int bonusAtk = ib.atk();
        int bonusDef = ib.def();
        int bonusHp  = ib.hp();

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
        // Meal buff (slot "Bem Alimentado") — também entra no poder exibido + nos campos de buff. [COZINHA]
        String mealBuffName = "";
        long   mealBuffSecsLeft = 0;
        if (warrior.hasMealBuff()) {
            var m = warrior.getMealBuff();
            buffAtk += m.atkBonus; buffDef += m.defBonus; buffHp += m.hpBonus; buffEva += m.evasionBonus;
            mealBuffName = m.icon + " " + m.displayName;
            mealBuffSecsLeft = Math.max(0,
                java.time.temporal.ChronoUnit.SECONDS.between(
                    java.time.LocalDateTime.now(), warrior.getMealBuffExpiresAt()));
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

        // Montaria equipada (Estábulo) — exibida na ficha do personagem. [ESTABULO]
        MountInfo equippedMount = mountRepository.findByPlayerAndEquippedTrue(player)
                .map(m -> { var mt = m.getMountType();
                    return new MountInfo(mt.name(), mt.displayName, mt.icon, mt.staminaReductionPct,
                            mt.attackBonus, mt.defenseBonus, mt.healthBonus); })
                .orElse(null);

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
                player.getSoulStones(),
                isVip, vipExpiresAt,
                arenaFightsToday, arenaFightLimit,
                buff2Name, buff2SecsLeft,
                mealBuffName, mealBuffSecsLeft,
                equippedMount
        );
    }

    /** Montaria equipada exibida na ficha (null se nenhuma). [ESTABULO] */
    record MountInfo(String id, String name, String icon, int staminaReductionPct,
                     int attackBonus, int defenseBonus, int healthBonus) {}

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
                           int soulStones,
                           boolean isVip, String vipExpiresAt,
                           int arenaFightsToday, int arenaFightLimit,
                           String activeBuff2, long buff2SecondsLeft,
                           String mealBuff, long mealBuffSecondsLeft,
                           MountInfo equippedMount) {}
}
