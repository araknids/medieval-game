package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.enums.WeaponCategory;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Path Trial: no Lv10 o RECRUIT escolhe um caminho (WARRIOR/ARCHER) e enfrenta o Guardião
 * daquele caminho num combate instantâneo (reusa o BattleSimulator). Vencer = vira a classe,
 * PERMANENTE, com respec grátis (devolve todos os pontos de atributo). [CLASSES]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassChangeService {

    private final PlayerRepository    playerRepository;
    private final WarriorRepository   warriorRepository;
    private final WarriorStatsService statsService;
    private final BattleSimulator     battleSimulator;
    private final InventoryService    inventoryService;
    private final MailService         mailService;

    /** Level mínimo pra destravar a Trial. */
    public static final int TRIAL_LEVEL = 10;

    // ── Guardiões da Trial (placeholder, ajustar no playtest) ──
    // Cada caminho tem um guardião com o "sabor" do arquétipo: o da Lâmina é tanky de corpo-a-corpo,
    // o do Arco é ágil/evasivo com crit. Stats no formato [atk, def, hp, dex(AC=10+dex), strBonus, luk].
    private static final Guardian BLADE_GUARDIAN = new Guardian("Blade Guardian", 22, 14, 160,  8, 2,  5);
    private static final Guardian BOW_GUARDIAN   = new Guardian("Bow Guardian",   20,  8, 130, 18, 2, 20);

    private Guardian guardianFor(WarriorClass path) {
        return path == WarriorClass.ARCHER ? BOW_GUARDIAN : BLADE_GUARDIAN;
    }

    /** Estado da escolha de classe pra UI (classe atual + se a Trial está liberada + os caminhos). */
    @Transactional(readOnly = true)
    public ClassInfo info(Player playerArg) {
        Player  player = playerRepository.findById(playerArg.getId()).orElseThrow();
        Warrior w      = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        WarriorClass current = w.getWarriorClass();
        boolean available = !current.isSpecialized() && w.getLevel() >= TRIAL_LEVEL;
        return new ClassInfo(
                current.name(), current.displayName, w.getLevel(), TRIAL_LEVEL, available,
                List.of(pathOf(WarriorClass.WARRIOR), pathOf(WarriorClass.ARCHER)));
    }

    private ClassPath pathOf(WarriorClass c) {
        Guardian g = guardianFor(c);
        String desc = c == WarriorClass.WARRIOR
                ? "Frontline tank. STR & CON, heavy HP and armor — relies on raw mitigation, not dodge."
                : "Glass cannon. DEX & LUK — high evasion and frequent crits, but fragile when hit.";
        return new ClassPath(c.name(), c.displayName, c.baseAttack, c.baseDefense, c.baseHealth,
                c.strCap, c.dexCap, c.lukCap, g.name(), desc);
    }

    /**
     * Tenta a Trial do caminho escolhido. Vitória → vira a classe (permanente) + respec grátis +
     * base stats da classe. Derrota → KO (como qualquer combate PvE perdido), classe inalterada.
     */
    @Transactional
    public TrialResult attemptTrial(Player playerArg, WarriorClass path) {
        Player  player = playerRepository.findById(playerArg.getId()).orElseThrow();
        Warrior w      = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (path == null || !path.isSpecialized())
            throw new IllegalArgumentException("Choose a path: Warrior or Archer.");
        if (w.getWarriorClass().isSpecialized())
            throw new IllegalStateException("You have already chosen your path — class change is permanent.");
        if (w.getLevel() < TRIAL_LEVEL)
            throw new IllegalStateException("Reach level " + TRIAL_LEVEL + " to take the Path Trial.");
        if (w.isKnockedOut())
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal first!");

        int[]    c = statsService.combatStats(player, w);
        Guardian g = guardianFor(path);
        // PvE: firstLosesOnTimeout=true → o jogador PRECISA matar o guardião (não basta sobreviver).
        BattleSimulator.BattleOutcome outcome = battleSimulator.simulateDetailed(
                w.getName(), c[0], c[1], c[2], c[3], c[4], c[5],
                g.name(),    g.atk(), g.def(), g.hp(), g.dex(), g.strBonus(), g.luk(),
                true);

        inventoryService.wearEquippedItems(player); // desgaste de durabilidade, como nos outros combates

        List<String> battleLog = new ArrayList<>(outcome.log());
        battleLog.remove(battleLog.size() - 1); // remove a tag interna WINNER
        boolean won = outcome.firstWon();

        if (won) {
            applyClassChange(w, path);
            // [CLASSES_ARMAS] Archer só usa arco: desequipa a espada (ficaria travada) e dá um arco inicial.
            if (path == WarriorClass.ARCHER) {
                inventoryService.unequipWeaponsNotMatching(player, WeaponCategory.RANGED);
                grantStarterBow(player);
            }
            w.applyDamagePercent(10); // leve desgaste pela luta (vitória)
            battleLog.add("🎖 Trial passed! You are now " + path.displayName + ". Attribute points refunded — reallocate them for your new path.");
        } else {
            w.applyDamagePercent(100); // derrota = KO
            w.clearBuff();
            battleLog.add("✖ The " + g.name() + " bested you. Heal up and try again when you're ready.");
        }
        warriorRepository.save(w);

        log.info("[ClassChangeService] player={} trial path={} won={}", player.getId(), path, won);
        return new TrialResult(won, path.name(), path.displayName, battleLog);
    }

    /** Arco inicial pro novo Archer (make-or-mail: não pode dar throw e abortar a troca). [CLASSES_ARMAS] */
    private void grantStarterBow(Player player) {
        String name = "Hunting Bow", desc = "A simple hunting bow — an archer's first weapon.", origin = "Path Trial";
        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            inventoryService.make(player, name, ItemType.WEAPON, 5, 0, 0, 1, 20, 1, desc, origin);
        } else {
            mailService.sendItemMail(player, "Your Path Trial reward.", name, ItemType.WEAPON, 5, 0, 0, 1, 1, 0, desc, origin);
        }
    }

    /** Aplica a troca: classe + base stats + respec grátis (devolve todos os pontos gastos). */
    private void applyClassChange(Warrior w, WarriorClass path) {
        w.setWarriorClass(path);
        w.setAttack(path.baseAttack);
        w.setDefense(path.baseDefense);
        w.setHealth(path.baseHealth);
        int spent = w.getStrength() + w.getDexterity() + w.getConstitution() + w.getLuck() + w.getIntellect();
        w.setStrength(0); w.setDexterity(0); w.setConstitution(0); w.setLuck(0); w.setIntellect(0);
        w.setAvailablePoints(w.getAvailablePoints() + spent);
    }

    // ── Records de saída (serializados direto como JSON) ──

    public record ClassPath(String id, String displayName, int baseAttack, int baseDefense, int baseHealth,
                            int strCap, int dexCap, int lukCap, String trialName, String description) {}

    public record ClassInfo(String currentClass, String currentClassName, int level, int trialLevel,
                            boolean available, List<ClassPath> paths) {}

    public record TrialResult(boolean won, String classId, String className, List<String> log) {}

    private record Guardian(String name, int atk, int def, int hp, int dex, int strBonus, int luk) {}
}
