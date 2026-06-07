package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.WarriorClass;
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
    private final AchievementService  achievementService; // [TITULOS]
    private final GatheringService    gatheringService;   // [TRIAL_CUSTO] custo de Monster Core
    private final Messages            messages;            // [I18N] lore da Trial por idioma do request

    /** Level mínimo pra destravar a Trial. */
    public static final int TRIAL_LEVEL = 10;

    /** [TRIAL_CUSTO] Custo da Path Trial: precisa TER tantos Monster Core p/ tentar; só consome ao VENCER. */
    public static final int TRIAL_MONSTER_CORE_COST = 100;

    // ── Guardiões da Trial (placeholder, ajustar no playtest) ──
    // Cada caminho tem um guardião com o "sabor" do arquétipo: o da Lâmina é tanky de corpo-a-corpo,
    // o do Arco é ágil/evasivo com crit. Stats no formato [atk, def, hp, dex(acerto), agi(esquiva), luk]. [REBALANCE]
    // ⚠ A Trial é feita por um RECRUIT Lv10 (só 18 pontos). A AGI do guardião precisa ficar BAIXA
    // (~3–8), senão o Recruit não consegue acertar (cada 8 de AGI = −1 no acerto inimigo). Re-tunado
    // pelo ClassTrialBalanceTest. [CLASSES]
    private static final Guardian BLADE_GUARDIAN    = new Guardian("Blade Guardian",    15, 12, 115, 10, 3,  5);
    private static final Guardian BOW_GUARDIAN      = new Guardian("Bow Guardian",      15,  6, 100, 14, 8, 15);
    private static final Guardian MERCHANT_GUARDIAN = new Guardian("Caravan Guardian",  15, 10, 110, 12, 5, 10); // [MERCADOR]

    private static Guardian guardianFor(WarriorClass path) {
        return switch (path) {
            case ARCHER   -> BOW_GUARDIAN;
            case MERCHANT -> MERCHANT_GUARDIAN;
            default       -> BLADE_GUARDIAN;
        };
    }

    /** Stats do guardião como array [atk,def,hp,dex,agi,luk] — seam p/ o teste de balance. [CLASSES] */
    static int[] guardianStats(WarriorClass path) {
        Guardian g = guardianFor(path);
        return new int[]{ g.atk(), g.def(), g.hp(), g.dex(), g.agi(), g.luk() };
    }

    /** Estado da escolha de classe pra UI (classe atual + se a Trial está liberada + os caminhos). */
    @Transactional(readOnly = true)
    public ClassInfo info(Player playerArg) {
        Player  player = playerRepository.findById(playerArg.getId()).orElseThrow();
        Warrior w      = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        WarriorClass current = w.getWarriorClass();
        boolean available = !current.isSpecialized() && w.getLevel() >= TRIAL_LEVEL;
        long cores = gatheringService.resourceQuantityTotal(player, com.medieval.game.enums.ResourceType.MONSTER_CORE); // [TRIAL_CUSTO]
        return new ClassInfo(
                current.name(), current.displayName, w.getLevel(), TRIAL_LEVEL, available,
                TRIAL_MONSTER_CORE_COST, cores,
                List.of(pathOf(WarriorClass.WARRIOR), pathOf(WarriorClass.ARCHER), pathOf(WarriorClass.MERCHANT)));
    }

    private ClassPath pathOf(WarriorClass c) {
        Guardian g = guardianFor(c);
        String desc = switch (c) {
            case WARRIOR  -> "Frontline tank. STR & CON, heavy HP and armor — relies on raw mitigation, not dodge.";
            case ARCHER   -> "Glass cannon. DEX & LUK — high evasion and frequent crits, but fragile when hit.";
            case MERCHANT -> "Economy class (axe & mace). A bit weaker in a fight, but its skills boost loot, crafting and trade — snowballs through wealth.";
            default       -> "";
        };
        return new ClassPath(c.name(), c.displayName, c.baseAttack, c.baseDefense, c.baseHealth,
                c.strCap, c.dexCap, c.lukCap, g.name(), desc,
                messages.get("class.trial." + c.name() + ".intro")); // [TRIAL_NARRATIVA][I18N]
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
            throw new IllegalArgumentException("Choose a path: Warrior, Archer or Merchant.");
        if (w.getWarriorClass().isSpecialized())
            throw new IllegalStateException("You have already chosen your path — class change is permanent.");
        if (w.getLevel() < TRIAL_LEVEL)
            throw new IllegalStateException("Reach level " + TRIAL_LEVEL + " to take the Path Trial.");
        if (w.isKnockedOut())
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal first!");
        // [TRIAL_CUSTO] Precisa TER 100 Monster Core (bag + stash) p/ encarar o Guardião (só consome se vencer).
        long cores = gatheringService.resourceQuantityTotal(player, com.medieval.game.enums.ResourceType.MONSTER_CORE);
        if (cores < TRIAL_MONSTER_CORE_COST)
            throw new IllegalStateException("You need " + TRIAL_MONSTER_CORE_COST + " Monster Core to face the Guardian (you have "
                    + cores + "). Hunt in the Cursed Fortress to gather them.");

        int[]    c = statsService.combatStats(player, w);
        Guardian g = guardianFor(path);
        // PvE: firstLosesOnTimeout=true → o jogador PRECISA matar o guardião (não basta sobreviver).
        BattleSimulator.BattleOutcome outcome = battleSimulator.simulateDetailed(
                w.getName(), c[0], c[1], c[2], c[3], c[4], c[5],
                g.name(),    g.atk(), g.def(), g.hp(), g.dex(), g.agi(), g.luk(),
                true);

        inventoryService.wearEquippedItems(player); // desgaste de durabilidade, como nos outros combates

        List<String> battleLog = new ArrayList<>(outcome.log());
        battleLog.remove(battleLog.size() - 1); // remove a tag interna WINNER
        boolean won = outcome.firstWon();

        // [TRIAL_NARRATIVA][I18N] desfecho narrado (flavor no modal), no idioma do request
        String narrative = messages.get("class.trial." + path.name() + (won ? ".victory" : ".defeat"));

        if (won) {
            // [TRIAL_CUSTO] consome os Monster Core (bag+stash) SÓ na vitória (perder mantém o estoque)
            gatheringService.removeResourceTotal(player, com.medieval.game.enums.ResourceType.MONSTER_CORE, TRIAL_MONSTER_CORE_COST);
            applyClassChange(w, path);
            // [CLASSES_ARMAS/MERCADOR] Archer/Merchant têm armas restritas: desequipa o que não usam
            // (ficaria travado) e dá uma arma inicial da classe.
            if (path == WarriorClass.ARCHER || path == WarriorClass.MERCHANT) {
                inventoryService.unequipWeaponsNotUsable(player, path);
                grantStarterWeapon(player, path);
            }
            w.applyDamagePercent(10); // leve desgaste pela luta (vitória)
            battleLog.add("🎖 Trial passed! You are now " + path.displayName + ". Attribute points refunded — reallocate them for your new path.");
        } else {
            w.applyDamagePercent(100); // derrota = KO
            w.clearBuff();
            battleLog.add("✖ The " + g.name() + " bested you. Heal up and try again when you're ready.");
        }
        warriorRepository.save(w);
        if (won) achievementService.checkAndUnlock(player, true); // [TITULOS] título da classe escolhida

        log.info("[ClassChangeService] player={} trial path={} won={}", player.getId(), path, won);
        return new TrialResult(won, path.name(), path.displayName, battleLog, narrative);
    }

    /** Arma inicial da classe (make-or-mail: não pode dar throw e abortar a troca). [CLASSES_ARMAS/MERCADOR] */
    private void grantStarterWeapon(Player player, WarriorClass path) {
        String name = path == WarriorClass.ARCHER ? "Hunting Bow" : "Worn Hatchet"; // arco / machado inicial
        String desc = path == WarriorClass.ARCHER
                ? "A simple hunting bow — an archer's first weapon."
                : "A merchant's trusty hatchet.";
        String origin = "Path Trial";
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
        int spent = w.getStrength() + w.getDexterity() + w.getConstitution() + w.getAgility() + w.getLuck() + w.getIntellect();
        w.setStrength(0); w.setDexterity(0); w.setConstitution(0); w.setAgility(0); w.setLuck(0); w.setIntellect(0);
        w.setAvailablePoints(w.getAvailablePoints() + spent);
    }

    // ── Records de saída (serializados direto como JSON) ──

    public record ClassPath(String id, String displayName, int baseAttack, int baseDefense, int baseHealth,
                            int strCap, int dexCap, int lukCap, String trialName, String description,
                            String intro) {} // [TRIAL_NARRATIVA] história antes do combate

    public record ClassInfo(String currentClass, String currentClassName, int level, int trialLevel,
                            boolean available, int monsterCoreCost, long monsterCoreHave, // [TRIAL_CUSTO]
                            List<ClassPath> paths) {}

    public record TrialResult(boolean won, String classId, String className, List<String> log,
                              String narrative) {} // [TRIAL_NARRATIVA] desfecho narrado (vitória/derrota)

    private record Guardian(String name, int atk, int def, int hp, int dex, int agi, int luk) {}
}
