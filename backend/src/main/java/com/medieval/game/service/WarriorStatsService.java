package com.medieval.game.service;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import com.medieval.game.model.Mount;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.ItemAffixRepository;
import com.medieval.game.repository.MountRepository;
import com.medieval.game.repository.SocketedGemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fonte única de verdade para os bônus de equipamento do guerreiro
 * (itens equipados efetivos + joias encaixadas), consumida por Arena, Torre,
 * Zona e WarriorController. [AUDITORIA A1 — antes o cálculo estava duplicado em
 * 4 lugares e as joias contavam só na ficha, não no combate.]
 *
 * As joias dos itens equipados são carregadas em UMA query (findAllByItemIn),
 * eliminando o N+1 que existia em buildResponse/getInventory. [AUDITORIA A9]
 */
@Service
@RequiredArgsConstructor
public class WarriorStatsService {

    private final InventoryItemRepository inventoryRepository;
    private final SocketedGemRepository   gemRepository;
    private final ItemAffixRepository     affixRepository;
    private final MountRepository         mountRepository;
    private final com.medieval.game.repository.PetRepository petRepository; // bônus de HP do pet. [PETS]

    /** Bônus plano de ATK/DEF/HP dos itens equipados (base efetiva + joias + afixos planos). */
    public record ItemBonus(int atk, int def, int hp) {
        public static final ItemBonus NONE = new ItemBonus(0, 0, 0);
    }

    /** Bônus completo do gear: planos (atk/def/hp) + atributos de afixo (str/dex/luk). [ITENS_V2] */
    public record GearBonus(int atk, int def, int hp, int str, int dex, int luk) {
        static final GearBonus NONE = new GearBonus(0, 0, 0, 0, 0, 0);
    }

    /**
     * Soma TUDO do gear equipado numa só passada: itens efetivos + joias + afixos.
     * Itens quebrados (durabilidade 0) não contribuem (nem base, nem joias, nem afixos).
     * Joias e afixos carregados em batch (findAllByItemIn) — sem N+1. [AUDITORIA A9 / ITENS_V2]
     */
    public GearBonus equippedGear(Player player) {
        // Montaria equipada (Estábulo): bônus plano de ATK/DEF/HP — entra no combate e na ficha. [ESTABULO]
        Mount mount = mountRepository.findByPlayerAndEquippedTrue(player).orElse(null);
        int matk = mount != null ? mount.getMountType().attackBonus  : 0;
        int mdef = mount != null ? mount.getMountType().defenseBonus : 0;
        int mhp  = mount != null ? mount.getMountType().healthBonus  : 0;

        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isEquipped)
                .filter(i -> !i.isBroken())
                .toList();
        if (equipped.isEmpty()) return new GearBonus(matk, mdef, mhp, 0, 0, 0); // só a montaria (se houver)

        int atk = equipped.stream().mapToInt(InventoryItem::getEffectiveAttack).sum()  + matk;
        int def = equipped.stream().mapToInt(InventoryItem::getEffectiveDefense).sum() + mdef;
        int hp  = equipped.stream().mapToInt(InventoryItem::getEffectiveHealth).sum()  + mhp;
        int str = 0, dex = 0, luk = 0;

        // Joias de todos os itens equipados em uma única query (evita N+1)
        Map<Long, List<SocketedGem>> gemsByItem = gemRepository.findAllByItemIn(equipped).stream()
                .collect(Collectors.groupingBy(g -> g.getItem().getId()));
        for (InventoryItem item : equipped) {
            for (SocketedGem g : gemsByItem.getOrDefault(item.getId(), List.of())) {
                SmithingService.GemBonus b = SmithingService.GemBonus.of(g.getGemType());
                atk += b.atk(); def += b.def(); hp += b.hp();
            }
        }

        // Afixos de todos os itens equipados em uma única query (Itens V2). [ITENS_V2]
        Map<Long, List<ItemAffix>> affByItem = affixRepository.findAllByItemIn(equipped).stream()
                .collect(Collectors.groupingBy(a -> a.getItem().getId()));
        for (InventoryItem item : equipped) {
            for (ItemAffix a : affByItem.getOrDefault(item.getId(), List.of())) {
                int m = a.getMagnitude();
                switch (a.getAffix().stat) {
                    case ATK -> atk += m;
                    case DEF -> def += m;
                    case HP  -> hp  += m;
                    case STR -> str += m;
                    case DEX -> dex += m;
                    case LUK -> luk += m;
                }
            }
        }
        return new GearBonus(atk, def, hp, str, dex, luk);
    }

    /** Compat: bônus plano (atk/def/hp) do gear equipado, já incluindo afixos planos. */
    public ItemBonus equippedItemBonus(Player player) {
        GearBonus g = equippedGear(player);
        return new ItemBonus(g.atk(), g.def(), g.hp());
    }

    /**
     * Stats totais de combate no formato do BattleSimulator:
     * [atk, def, hp, dex, strBonus, luk]. Inclui base + atributos + itens + joias + buffs ativos
     * (slots do Templo + slot "Bem Alimentado" da refeição). [A1 / COZINHA]
     */
    public int[] combatStats(Player player, Warrior warrior) {
        GearBonus g = equippedGear(player);          // planos + atributos de afixo [ITENS_V2]
        int[] buff = activeBuffBonuses(warrior);     // {atk, def, hp, eva}
        // Postura: tradeoff ATK/DEF aplicado por ÚLTIMO, sobre o total (base+gear+buff). [POSTURE]
        com.medieval.game.enums.CombatPosture posture = warrior.getCombatPosture() != null
                ? warrior.getCombatPosture() : com.medieval.game.enums.CombatPosture.BALANCED;
        int atk = (int) Math.round((warrior.getTotalBaseAttack()  + g.atk() + g.str() + buff[0]) * posture.atkMult());
        int def = (int) Math.round((warrior.getTotalBaseDefense() + g.def() + buff[1]) * posture.defMult());
        // Pet equipado: bônus de combate (empilha com base+gear+buff+montaria). [PETS]
        var pet = petRepository.findByPlayerAndEquippedTrue(player).map(com.medieval.game.model.Pet::getPetType).orElse(null);
        int petHpPct = pet != null ? pet.hpBonusPercent : 0; // % no HP final
        int petDex   = pet != null ? pet.dexBonus       : 0; // AGI plana
        int hp = (int) Math.round((warrior.getTotalBaseHealth() + g.hp() + buff[2]) * (1 + petHpPct / 100.0));
        return new int[]{
            atk,                                                       // [0] ATK (afixo STR = +1 ATK/pt) × postura
            def,                                                       // [1] DEF × postura
            hp,                                                        // [2] HP (base+gear+buff) × pet
            warrior.getDexterity()        + g.dex() + buff[3] + petDex,// [3] dex → AC = 10 + dex (+ AGI do pet)
            (warrior.getStrength() + g.str()) / 20,                    // [4] floor(STR efetivo/20)
            warrior.getLuck()             + g.luk()                    // [5] luk → crit window + Fortune Save
        };
    }

    /** Soma os bônus dos buffs ATIVOS (Templo slot 1 + 2 + refeição). [COZINHA] */
    private int[] activeBuffBonuses(Warrior w) {
        int atk = 0, def = 0, hp = 0, eva = 0;
        if (w.hasActiveBuff()) {
            var b = w.getActiveBuff();
            atk += b.atkBonus; def += b.defBonus; hp += b.hpBonus; eva += b.evasionBonus;
        }
        if (w.hasActiveBuff2()) {
            var b = w.getActiveBuff2();
            atk += b.atkBonus; def += b.defBonus; hp += b.hpBonus; eva += b.evasionBonus;
        }
        if (w.hasMealBuff()) {
            var m = w.getMealBuff();
            atk += m.atkBonus; def += m.defBonus; hp += m.hpBonus; eva += m.evasionBonus;
        }
        return new int[]{atk, def, hp, eva};
    }
}
