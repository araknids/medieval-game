package com.medieval.game.controller;

import com.medieval.game.enums.BuffType;
import com.medieval.game.enums.Element;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.TempleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/api/temple")
@RequiredArgsConstructor
public class TempleController {

    private final TempleService     templeService;
    private final PlayerService     playerService;
    private final WarriorRepository warriorRepository;
    private final GatheringService  gatheringService; // contagem de essências p/ a UI [ELEMENTOS]

    // Info do templo para o jogador atual
    @GetMapping
    public ResponseEntity<?> getInfo(Authentication auth) {
        Player  player  = getPlayer(auth);
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);

        int  regenMin = player.regenMinutes(); // [BUFF_NOVATO] janela de regen (buff de novato)
        int  hpPct  = warrior != null ? warrior.getCalculatedHpPercent(regenMin) : 100;
        long healCost = warrior != null ? templeService.healCost(warrior) : 0;
        long protectedCount = templeService.countProtected(player);

        String activeBuff    = warrior != null && warrior.hasActiveBuff()
                ? warrior.getActiveBuff().name() : null;
        long buffSecondsLeft = 0;
        if (warrior != null && warrior.hasActiveBuff()) {
            buffSecondsLeft = Math.max(0, ChronoUnit.SECONDS.between(
                    LocalDateTime.now(), warrior.getBuffExpiresAt()));
        }

        var buffs = Arrays.stream(BuffType.values()).map(b -> Map.of(
            "id",          b.name(),
            "displayName", com.medieval.game.service.Messages.tr("buff." + b.name() + ".name", b.displayName),
            "icon",        b.icon,
            "effect",      b.effect,
            "bronzeCost",  b.bronzeCost
        )).toList();

        long ssHealCdSecs = templeService.soulstoneHealCooldownSecs(player);

        boolean isVip          = player.isVip();
        long vipHealCdSecs     = isVip ? templeService.vipHealCooldownSecs(player) : -1;

        // Second buff info (VIP)
        String activeBuff2 = null;
        long   buff2SecsLeft = 0;
        if (warrior != null && warrior.hasActiveBuff2()) {
            activeBuff2 = warrior.getActiveBuff2().name();
            buff2SecsLeft = Math.max(0, ChronoUnit.SECONDS.between(
                    LocalDateTime.now(), warrior.getBuffExpiresAt2()));
        }

        // [ELEMENTOS] Encantamentos ativos + os 4 elementos (com essência que o jogador tem).
        Element wElem = warrior != null ? warrior.getActiveWeaponElement() : null;
        Element aElem = warrior != null ? warrior.getActiveArmorElement()  : null;
        long wElemSecs = (warrior != null && wElem != null)
                ? Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), warrior.getWeaponElementUntil())) : 0;
        long aElemSecs = (warrior != null && aElem != null)
                ? Math.max(0, ChronoUnit.SECONDS.between(LocalDateTime.now(), warrior.getArmorElementUntil())) : 0;
        var elements = Arrays.stream(Element.values()).map(e -> Map.<String, Object>ofEntries(
            Map.entry("id",          e.name()),
            Map.entry("displayName", com.medieval.game.service.Messages.tr("element." + e.name() + ".name", e.displayName)),
            Map.entry("icon",        e.icon),
            Map.entry("beats",       com.medieval.game.service.Messages.tr("element." + e.beatsTarget().name() + ".name", e.beatsTarget().displayName)),
            Map.entry("weakTo",      com.medieval.game.service.Messages.tr("element." + e.losesTo().name() + ".name", e.losesTo().displayName)),
            // [ELEMENTOS] % de dano da roda (tunável no Element.multiplier; +25% vence / −25% perde)
            Map.entry("bonusPct",    (int) Math.round((Element.multiplier(e, e.beatsTarget()) - 1.0) * 100)),
            Map.entry("penaltyPct",  (int) Math.round((1.0 - Element.multiplier(e, e.losesTo())) * 100)),
            Map.entry("essence",     e.essence().name()),
            Map.entry("essenceName", com.medieval.game.service.Messages.tr("resource." + e.essence().name() + ".name", e.essence().displayName)),
            Map.entry("owned",       gatheringService.resourceQuantity(player, e.essence()))
        )).toList();

        return ResponseEntity.ok(Map.ofEntries(
            Map.entry("hpPercent",          hpPct),
            Map.entry("isKnockedOut",        warrior != null && warrior.isKnockedOut(regenMin)),
            Map.entry("healCost",            healCost),
            Map.entry("healFree",            healCost == 0),
            Map.entry("protectedCount",      protectedCount),
            Map.entry("maxProtected",        3),
            Map.entry("activeBuff",          activeBuff != null ? activeBuff : ""),
            Map.entry("buffSecondsLeft",     buffSecondsLeft),
            Map.entry("activeBuff2",         activeBuff2 != null ? activeBuff2 : ""),
            Map.entry("buff2SecondsLeft",    buff2SecsLeft),
            Map.entry("buffs",               buffs),
            Map.entry("soulStones",          player.getSoulStones()),
            Map.entry("ssHealCooldownSecs",  ssHealCdSecs),
            Map.entry("ssHealReady",         ssHealCdSecs == 0),
            Map.entry("isVip",               isVip),
            Map.entry("vipHealCooldownSecs", isVip ? vipHealCdSecs : -1L),
            Map.entry("vipHealReady",        isVip && vipHealCdSecs == 0),
            // [ELEMENTOS]
            Map.entry("elements",            elements),
            Map.entry("enchantCost",         100),
            Map.entry("weaponElement",       wElem != null ? wElem.name() : ""),
            Map.entry("weaponElementSecondsLeft", wElemSecs),
            Map.entry("armorElement",        aElem != null ? aElem.name() : ""),
            Map.entry("armorElementSecondsLeft",  aElemSecs)
        ));
    }

    // Curar
    @PostMapping("/heal")
    public ResponseEntity<?> heal(Authentication auth) {
        templeService.heal(getPlayer(auth));
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.warrior_healed", "Warrior healed! HP restored to 100%.")));
    }

    // Aplicar bênção
    @PostMapping("/buff/{buffType}")
    public ResponseEntity<?> applyBuff(@PathVariable BuffType buffType, Authentication auth) {
        templeService.applyBuff(getPlayer(auth), buffType);
        return ResponseEntity.ok(Map.of(
            "message", com.medieval.game.service.Messages.tr("toast.buff_on", "{0} activated for 1 hour!", com.medieval.game.service.Messages.tr("buff." + buffType.name() + ".name", buffType.displayName)),
            "buff",    buffType.name()
        ));
    }

    // Encantar arma/armadura com um elemento (buff 1h, custa essência + bronze) [ELEMENTOS]
    @PostMapping("/enchant/weapon/{element}")
    public ResponseEntity<?> enchantWeapon(@PathVariable Element element, Authentication auth) {
        templeService.enchantWeapon(getPlayer(auth), element);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("toast.enchant_weapon", "{0} Weapon enchanted with {1} for 1 hour!", element.icon, com.medieval.game.service.Messages.tr("element." + element.name() + ".name", element.displayName))));
    }

    @PostMapping("/enchant/armor/{element}")
    public ResponseEntity<?> enchantArmor(@PathVariable Element element, Authentication auth) {
        templeService.enchantArmor(getPlayer(auth), element);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("toast.enchant_armor", "{0} Armor enchanted with {1} for 1 hour!", element.icon, com.medieval.game.service.Messages.tr("element." + element.name() + ".name", element.displayName))));
    }

    // VIP — cura grátis (CD 10 min)
    @PostMapping("/vip-heal")
    public ResponseEntity<?> vipHeal(Authentication auth) {
        Player player = getPlayer(auth);
        templeService.vipHeal(player);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.vip_healed", "VIP Heal! HP restored to 100% for free.")));
    }

    // Proteger item
    @PostMapping("/protect/{itemId}")
    public ResponseEntity<?> protect(@PathVariable Long itemId, Authentication auth) {
        templeService.protectItem(getPlayer(auth), itemId);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.item_protected", "Item protected by the Temple!")));
    }

    // Remover proteção
    @PostMapping("/unprotect/{itemId}")
    public ResponseEntity<?> unprotect(@PathVariable Long itemId, Authentication auth) {
        templeService.unprotectItem(getPlayer(auth), itemId);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.protection_removed", "Protection removed.")));
    }

    // SoulStone — cura instantânea (1 💎, CD 30 min)
    @PostMapping("/soulstone-heal")
    public ResponseEntity<?> soulstoneHeal(Authentication auth) {
        Player player = getPlayer(auth);
        templeService.soulstoneHeal(player);
        return ResponseEntity.ok(Map.of(
            "message",    com.medieval.game.service.Messages.tr("msg.warrior_instant_healed", "Warrior instantly healed! HP restored to 100%."),
            "soulStones", player.getSoulStones()
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }
}
