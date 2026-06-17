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
    private final com.medieval.game.service.AbilityService abilityService; // [MERCADOR] % self-crafted
    private final MountRepository    mountRepository;
    private final com.medieval.game.repository.PetRepository petRepository; // [PETS]
    private final com.medieval.game.service.Messages messages; // [I18N] nome/efeito dos atributos

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
                "displayName", messages.getOr("attr." + a.name() + ".name",   a.displayName), // [I18N]
                "icon",        a.icon,
                "effect",      messages.getOr("attr." + a.name() + ".effect", a.effect)        // [I18N]
        )).toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping("/attributes/{attribute}")
    public ResponseEntity<?> spendPoint(@PathVariable Attribute attribute, Authentication auth) {
        Player  player  = playerService.findById((Long) auth.getPrincipal());
        Warrior warrior = warriorService.spendPoint(player, attribute);
        return ResponseEntity.ok(buildResponse(warrior, player));
    }

    // ── [ONBOARDING] tela de boas-vindas: viu? / marcar como vista ──
    @GetMapping("/onboarding")
    public ResponseEntity<?> onboarding(Authentication auth) {
        Player p = playerService.findById((Long) auth.getPrincipal());
        return ResponseEntity.ok(java.util.Map.of("seen", p.isOnboardingSeen()));
    }

    @PostMapping("/onboarding/seen")
    public ResponseEntity<?> markOnboardingSeen(Authentication auth) {
        playerService.markOnboardingSeen((Long) auth.getPrincipal());
        return ResponseEntity.ok(java.util.Map.of("seen", true));
    }

    // ── Postura de combate (toggle livre) — vale em todo combate. [POSTURE] ──
    @GetMapping("/postures")
    public ResponseEntity<?> getPostures() {
        var list = Arrays.stream(com.medieval.game.enums.CombatPosture.values()).map(p -> Map.of(
                "id",          p.name(),
                "displayName", p.displayName,
                "atkMult",     p.atkMult(),
                "defMult",     p.defMult()
        )).toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping("/posture/{posture}")
    public ResponseEntity<?> setPosture(@PathVariable com.medieval.game.enums.CombatPosture posture, Authentication auth) {
        Player  player  = playerService.findById((Long) auth.getPrincipal());
        Warrior warrior = warriorService.setPosture(player, posture);
        return ResponseEntity.ok(buildResponse(warrior, player));
    }

    // [GENDER] A troca de sexo saiu daqui (era grátis): o sexo é escolhido na CRIAÇÃO e só muda
    // pagando SoulStone na tela do VIP (POST /api/vip/change-gender/{gender}). [OUTFITS_FEMALE]

    // ── Helper ──

    private WarriorResponse buildResponse(Warrior warrior, Player player) {
        // Bônus de itens equipados + joias (fonte única, mesmo cálculo do combate) [AUDITORIA A1/A9]
        WarriorStatsService.ItemBonus ib = statsService.equippedItemBonus(player);
        int bonusAtk = ib.atk();
        int bonusDef = ib.def();
        int bonusHp  = ib.hp();
        // [POSTURE] ATK/DEF/HP EFETIVO de combate (inclui postura + buffs + abilities + pet + taverna) → a ficha reflete o stance
        int[] cstats = statsService.combatStats(player, warrior);
        WarriorStatsService.StatSources[] srcs = statsService.combatBreakdown(player, warrior); // [FICHA_BONUS] de onde vem cada bônus

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

        // HP actual (with passive regen) — usa a janela de regen do Player (buff de novato). [BUFF_NOVATO]
        int newbieRegenMin = player.regenMinutes();
        int hpPercent = warrior.getCalculatedHpPercent(newbieRegenMin);

        // [TAVERNA] Buff da Taverna (badge no sidebar): % em todos os stats + tempo restante.
        int    tavernStacks = warrior.activeTavernStacks();
        double tavernBuffPct = tavernStacks * 0.01; // 0.01% por stack
        long   tavernBuffSecondsLeft = (tavernStacks > 0 && warrior.getTavernBuffExpiresAt() != null)
                ? Math.max(0, java.time.temporal.ChronoUnit.SECONDS.between(
                        java.time.LocalDateTime.now(), warrior.getTavernBuffExpiresAt()))
                : 0;

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
                    return new MountInfo(mt.name(), com.medieval.game.service.Messages.tr("mount." + mt.name() + ".name", mt.displayName), mt.icon, mt.staminaReductionPct,
                            mt.attackBonus, mt.defenseBonus, mt.healthBonus); })
                .orElse(null);

        // Pet equipado — exibido na ficha do personagem. [PETS]
        PetInfo equippedPet = petRepository.findByPlayerAndEquippedTrue(player)
                .map(p -> { var pt = p.getPetType();
                    return new PetInfo(pt.name(), com.medieval.game.service.Messages.tr("pet." + pt.name() + ".name", pt.displayName), pt.icon, pt.hpBonusPercent, pt.dexBonus); })
                .orElse(null);

        // [ELEMENTOS] Encantamentos elementais ativos (arma/armadura) + tempo restante.
        var wElem = warrior.getActiveWeaponElement();
        var aElem = warrior.getActiveArmorElement();
        long wElemSecs = wElem != null ? Math.max(0, java.time.temporal.ChronoUnit.SECONDS.between(
                java.time.LocalDateTime.now(), warrior.getWeaponElementUntil())) : 0;
        long aElemSecs = aElem != null ? Math.max(0, java.time.temporal.ChronoUnit.SECONDS.between(
                java.time.LocalDateTime.now(), warrior.getArmorElementUntil())) : 0;

        // [CLASSES_ARMAS] atributo de dano segue a arma equipada (arco→DEX, melee→STR)
        boolean rangedWeapon = statsService.isRangedWeaponEquipped(player);
        return new WarriorResponse(
                warrior.getId(), warrior.getName(),
                messages.getOr("class." + warrior.getWarriorClass().name() + ".name", warrior.getWarriorClass().displayName), // [I18N]
                warrior.getWarriorClass().name(), // id estável do enum p/ a UI decidir a Path Trial [CLASSES]
                warrior.getLevel(), warrior.getExperience(), warrior.expNeededForNextLevel(),
                warrior.getTotalBaseAttack(rangedWeapon),  warrior.getTotalBaseDefense(),  warrior.getTotalBaseHealth(),
                bonusAtk,                       bonusDef,                       bonusHp,
                warrior.getTotalBaseAttack(rangedWeapon)  + bonusAtk,
                warrior.getTotalBaseDefense() + bonusDef,
                warrior.getTotalBaseHealth()  + bonusHp,
                itemBonusAtk, itemBonusDef, itemBonusHp,
                buffAtk, buffDef, buffHp, buffEva,
                warrior.getStrength(), warrior.getDexterity(), warrior.getConstitution(), warrior.getLuck(), warrior.getIntellect(),
                warrior.getAgility(), // [REBALANCE]
                warrior.getAvailablePoints(), warrior.getAbilityPoints(), baseEvasion, totalEvasion,
                warrior.getArmorClass(), warrior.getAttackBonus(),
                player.getCalculatedStamina(), player.getMinutesToFullStamina(),
                bronze, silver, gold,
                player.getRankPoints(),
                hpPercent, warrior.isKnockedOut(newbieRegenMin),
                buffName, buffSecsLeft,
                player.getSoulStones(),
                isVip, vipExpiresAt,
                arenaFightsToday, arenaFightLimit,
                buff2Name, buff2SecsLeft,
                mealBuffName, mealBuffSecsLeft,
                equippedMount,
                warrior.getCombatPosture() != null ? warrior.getCombatPosture().name() : "BALANCED", // [POSTURE]
                equippedPet, // [PETS]
                wElem != null ? wElem.name() : "", wElemSecs, // [ELEMENTOS]
                aElem != null ? aElem.name() : "", aElemSecs,
                com.medieval.game.service.AchievementService.titleString(player), // [TITULOS]
                player.isNewbieBuffActive(), player.getNewbieBuffHoursLeft(), // [BUFF_NOVATO]
                tavernBuffPct, tavernBuffSecondsLeft, // [TAVERNA]
                cstats[0], cstats[1], cstats[2], // [POSTURE] ATK/DEF/HP efetivo de combate
                abilityService.selfCraftedStatBonusPct(player), // [MERCADOR]
                srcs[0], srcs[1], srcs[2], // [FICHA_BONUS] fontes do bônus (ATK/DEF/HP)
                player.getGender() != null ? player.getGender().name() : "MALE" // [OUTFITS_FEMALE] base/peças Male/Female
        );
    }

    /** Pet equipado exibido na ficha (null se nenhum). [PETS] */
    record PetInfo(String type, String displayName, String icon, int hpBonusPercent, int dexBonus) {}

    /** Montaria equipada exibida na ficha (null se nenhuma). [ESTABULO] */
    record MountInfo(String id, String name, String icon, int staminaReductionPct,
                     int attackBonus, int defenseBonus, int healthBonus) {}

    record WarriorResponse(Long id, String name, String warriorClass, String warriorClassId, int level,
                           long experience, long expNeeded,
                           int baseAttack,  int baseDefense,  int baseHealth,
                           int bonusAttack, int bonusDefense, int bonusHealth,
                           int totalAttack, int totalDefense, int totalHealth,
                           int itemBonusAttack, int itemBonusDefense, int itemBonusHealth,
                           int buffBonusAttack, int buffBonusDefense, int buffBonusHealth, int buffBonusEvasion,
                           int strength, int dexterity, int constitution, int luck, int intellect,
                           int agility, // [REBALANCE]
                           int availablePoints, int abilityPoints, int baseEvasion, int evasionChance,
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
                           MountInfo equippedMount,
                           String combatPosture,
                           PetInfo equippedPet,
                           String weaponElement, long weaponElementSecondsLeft, // [ELEMENTOS]
                           String armorElement,  long armorElementSecondsLeft,
                           String title, // [TITULOS] título ativo do jogador
                           boolean newbieBuffActive, long newbieBuffHoursLeft, // [BUFF_NOVATO]
                           double tavernBuffPct, long tavernBuffSecondsLeft, // [TAVERNA]
                           int combatAttack, int combatDefense, int combatHealth, // [POSTURE] efetivo (postura+buffs+abilities+pet)
                           int selfCraftedBonusPct, // [MERCADOR] +stats% em gear forjado por você
                           WarriorStatsService.StatSources atkSources, // [FICHA_BONUS] de onde vem o bônus de ATK
                           WarriorStatsService.StatSources defSources, // … DEF
                           WarriorStatsService.StatSources hpSources, // … HP
                           String gender) {} // [OUTFITS_FEMALE] MALE/FEMALE → base/peças do paper-doll
}
