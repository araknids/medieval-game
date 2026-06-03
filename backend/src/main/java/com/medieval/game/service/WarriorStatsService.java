package com.medieval.game.service;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.SocketedGem;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
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

    /** Bônus agregado de ATK/DEF/HP dos itens equipados (base efetiva + joias). */
    public record ItemBonus(int atk, int def, int hp) {
        public static final ItemBonus NONE = new ItemBonus(0, 0, 0);
    }

    /**
     * Soma o bônus dos itens equipados do jogador. Itens quebrados (durabilidade 0)
     * não contribuem — nem a base nem as joias.
     */
    public ItemBonus equippedItemBonus(Player player) {
        List<InventoryItem> equipped = inventoryRepository.findAllByPlayer(player).stream()
                .filter(InventoryItem::isEquipped)
                .filter(i -> !i.isBroken())
                .toList();
        if (equipped.isEmpty()) return ItemBonus.NONE;

        int atk = equipped.stream().mapToInt(InventoryItem::getEffectiveAttack).sum();
        int def = equipped.stream().mapToInt(InventoryItem::getEffectiveDefense).sum();
        int hp  = equipped.stream().mapToInt(InventoryItem::getEffectiveHealth).sum();

        // Joias de todos os itens equipados em uma única query (evita N+1)
        Map<Long, List<SocketedGem>> gemsByItem = gemRepository.findAllByItemIn(equipped).stream()
                .collect(Collectors.groupingBy(g -> g.getItem().getId()));
        for (InventoryItem item : equipped) {
            for (SocketedGem g : gemsByItem.getOrDefault(item.getId(), List.of())) {
                SmithingService.GemBonus b = SmithingService.GemBonus.of(g.getGemType());
                atk += b.atk(); def += b.def(); hp += b.hp();
            }
        }
        return new ItemBonus(atk, def, hp);
    }

    /**
     * Stats totais de combate no formato do BattleSimulator:
     * [atk, def, hp, dex, strBonus, luk]. Inclui base + atributos + itens + joias.
     */
    public int[] combatStats(Player player, Warrior warrior) {
        ItemBonus b = equippedItemBonus(player);
        return new int[]{
            warrior.getTotalBaseAttack()  + b.atk(),
            warrior.getTotalBaseDefense() + b.def(),
            warrior.getTotalBaseHealth()  + b.hp(),
            warrior.getDexterity(),    // [3] dex → AC = 10 + dex
            warrior.getAttackBonus(),  // [4] floor(STR/20)
            warrior.getLuck()          // [5] luk → crit window + Fortune Save
        };
    }
}
