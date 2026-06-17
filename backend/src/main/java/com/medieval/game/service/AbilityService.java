package com.medieval.game.service;

import com.medieval.game.enums.ClassAbility;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.model.WarriorAbility;
import com.medieval.game.repository.WarriorAbilityRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Habilidades de classe: aprender (1 ponto/level, cap lv10), respec (grátis na troca de classe/wipe,
 * pago a qualquer hora) e os bônus passivos pro combate. [HABILIDADES]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbilityService {

    private final WarriorRepository        warriorRepository;
    private final WarriorAbilityRepository abilityRepository;
    private final PlayerService            playerService;

    private static final long RESPEC_COST = 500; // bronze (placeholder p/ tuning)

    public Warrior warriorOf(Player player) {
        return warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
    }

    /** Mapa habilidade → nível aprendido do guerreiro. */
    public Map<ClassAbility, Integer> levels(Warrior w) {
        Map<ClassAbility, Integer> m = new EnumMap<>(ClassAbility.class);
        for (WarriorAbility wa : abilityRepository.findByWarrior(w)) m.put(wa.getAbility(), wa.getLevel());
        return m;
    }

    @Transactional
    public Warrior learn(Player player, ClassAbility ability) {
        log.info("[AbilityService] player={} action=learn ability={}", player.getId(), ability);
        Warrior w = warriorOf(player);
        if (ability.owner != w.getWarriorClass())
            throw new IllegalStateException("That ability isn't available to your class.");
        if (w.getAbilityPoints() < 1)
            throw new IllegalStateException("No ability points available.");
        WarriorAbility wa = abilityRepository.findByWarriorAndAbility(w, ability).orElse(null);
        int cur = wa != null ? wa.getLevel() : 0;
        if (cur >= ClassAbility.MAX_LEVEL)
            throw new IllegalStateException(ability.displayName + " is already at max level (" + ClassAbility.MAX_LEVEL + ").");
        if (wa == null) {
            wa = new WarriorAbility();
            wa.setWarrior(w);
            wa.setAbility(ability);
            wa.setLevel(1);
        } else {
            wa.setLevel(cur + 1);
        }
        abilityRepository.save(wa);
        w.setAbilityPoints(w.getAbilityPoints() - 1);
        return warriorRepository.save(w);
    }

    /** Respec pago: devolve todos os pontos gastos, zera as habilidades, custa bronze. */
    @Transactional
    public void respec(Player player) {
        log.info("[AbilityService] player={} action=respec", player.getId());
        Warrior w = warriorOf(player);
        List<WarriorAbility> learned = abilityRepository.findByWarrior(w);
        int refund = learned.stream().mapToInt(WarriorAbility::getLevel).sum();
        if (refund == 0) throw new IllegalStateException("No abilities to reset.");
        playerService.spendBronze(player, RESPEC_COST); // lança se não tiver saldo (rollback)
        abilityRepository.deleteAll(learned);
        w.setAbilityPoints(w.getAbilityPoints() + refund);
        warriorRepository.save(w);
        log.info("[AbilityService] player={} action=respec OK refunded={}", player.getId(), refund);
    }

    /** Reset GRÁTIS (troca de classe / soft-wipe): devolve pontos e apaga as habilidades. Não salva o warrior. */
    @Transactional
    public void resetFree(Warrior w) {
        List<WarriorAbility> learned = abilityRepository.findByWarrior(w);
        int refund = learned.stream().mapToInt(WarriorAbility::getLevel).sum();
        if (!learned.isEmpty()) abilityRepository.deleteAll(learned);
        w.setAbilityPoints(w.getAbilityPoints() + refund);
    }

    /** Kit de habilidades ATIVAS do guerreiro p/ o BattleSimulator (efeito + cooldown + magnitude do nível). */
    public List<BattleSimulator.ActiveAbility> activeLoadout(Warrior w) {
        List<BattleSimulator.ActiveAbility> out = new java.util.ArrayList<>();
        for (var e : levels(w).entrySet()) {
            ClassAbility a = e.getKey();
            if (a.isActive())
                out.add(new BattleSimulator.ActiveAbility(a.effect, a.cooldown, a.magnitude(e.getValue()), a.name().toLowerCase())); // id = skill_<id> no replay [HABILIDADES]
        }
        return out;
    }

    /** Soma das passivas no formato do combatStats: [atk, def, hp, dex, strBonus, luk]. */
    public int[] passiveStatBonus(Warrior w) {
        int[] sum = new int[6];
        for (var e : levels(w).entrySet()) {
            if (!e.getKey().isActive()) {
                int[] b = e.getKey().passiveBonus(e.getValue());
                for (int i = 0; i < 6; i++) sum[i] += b[i];
            }
        }
        return sum;
    }

    public long respecCost() { return RESPEC_COST; }

    // ── Economia (Mercador): getters consultados pelos serviços de loot/craft/venda/coleta. [MERCADOR] ──

    private int economy(Player player, ClassAbility ability) {
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        if (w == null || w.getWarriorClass() != ability.owner) return 0;
        int level = levels(w).getOrDefault(ability, 0);
        return level * ability.economyPerLevel();
    }

    /** +% no preço de venda (Haggler). */
    public int sellPriceBonusPct(Player player)   { return economy(player, ClassAbility.HAGGLER); }
    /** +% (pontos) na chance de drop de item (Treasure Hunter). */
    public int dropChanceBonus(Player player)     { return economy(player, ClassAbility.TREASURE_HUNTER); }
    /** +% (pontos) no sucesso de craft (Master Craftsman). */
    public int craftSuccessBonus(Player player)   { return economy(player, ClassAbility.MASTER_CRAFTSMAN); }
    /** +% no rendimento de coleta de recursos (Prospector). */
    public int gatherYieldBonusPct(Player player) { return economy(player, ClassAbility.PROSPECTOR); }

    /**
     * [MERCADOR] +% nos stats de itens que o PRÓPRIO Mercador forjou e equipou (Master Craftsman).
     * Escala 2.5%/nível → +25% no nível 10. 0 se não for Mercador / sem a skill. Números placeholder.
     */
    public int selfCraftedStatBonusPct(Player player) {
        Warrior w = warriorRepository.findByPlayer(player).orElse(null);
        if (w == null || w.getWarriorClass() != com.medieval.game.enums.WarriorClass.MERCHANT) return 0;
        int level = levels(w).getOrDefault(ClassAbility.MASTER_CRAFTSMAN, 0);
        return level * 25 / 10; // 2.5%/nível
    }
}
