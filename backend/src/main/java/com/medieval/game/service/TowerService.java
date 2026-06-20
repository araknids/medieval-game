package com.medieval.game.service;

import com.medieval.game.enums.TowerStatus;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TowerService {

    private final TowerRunRepository      towerRunRepository;
    private final WarriorRepository       warriorRepository;
    private final PlayerRepository        playerRepository;
    private final BattleSimulator         battleSimulator;
    private final InventoryService        inventoryService;
    private final WarriorStatsService     statsService;
    private final PlayerService           playerService;
    private final AchievementService      achievementService; // [TITULOS]
    private final GatheringService        gatheringService;   // [MONSTER_CORE_BATALHA]
    private final Messages                messages;           // [I18N] atmosfera dos andares + escolha do Arka
    private final WorkSessionRepository   workSessionRepository; // [WORK_IDLE] trava enquanto trabalha

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    private static final int STAMINA_COST = 25;

    // ── [TORRE_NARRATIVA] Stats por andar (1 monstro / MVP). Tunável pela sonda; alvo ~1 andar por nível. ──
    /** [REBALANCE] Stats: dex = acerto (d20+dex/5), agi = esquiva/velocidade. */
    public record BossInfo(String name, int attack, int defense, int health, int dex, int agi, int luk) {}

    private BossInfo monster(int floor, String name, int hpDivisor, double atkMult, boolean mvp) {
        int atk = (int) Math.round((9 + floor * 1.3) * atkMult * (mvp ? 1.4 : 1.0));
        int def = (int) Math.round((4 + floor * 0.5) * (mvp ? 1.2 : 1.0));
        int hp  = (int) Math.round((50 + floor * 11) * (mvp ? 1.9 : 1.0) / hpDivisor);
        int dex = Math.min(10 + floor / 2, 40);
        int agi = Math.min(floor / 6, 10) + (mvp ? 3 : 0);
        int luk = Math.min(floor / 5, 15) + (mvp ? 3 : 0);
        return new BossInfo(name, atk, def, Math.max(1, hp), dex, agi, luk);
    }

    /** Andar: 1 MVP, ou N monstros — cada um com STATS CHEIOS (não divide mais o HP). [TORRE_GRUPO]
     *  Andar com +1 monstro = todos enfrentados ao MESMO TEMPO no climb (ver simulateGroup), não em sequência. */
    // [I18N] nome de combatente localizado (monster.<Nome_Com_Underscore>); EN = o nome do TowerFloors.
    // O nome localizado vira o BossInfo.name → propaga pro battle log (sem tocar o BattleSimulator).
    private String monName(String en) { return messages.getOr("monster." + en.replace(' ', '_'), en); }

    /** Sufixo romano simples (II, III, …) p/ desambiguar monstros iguais no grupo. [TORRE_GRUPO] */
    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    public List<BossInfo> monstersFor(int floor) {
        TowerFloors.FloorDef d = TowerFloors.forFloor(floor);
        if (d.isMvp()) return List.of(monster(floor,
                monName(d.mvp()) + messages.getOr("tower.floor_suffix", " (Floor {0})", floor), 1, 1.0, true)); // [I18N]
        String[] ms = d.monsters().length > 0 ? d.monsters() : new String[]{"Tower Horror"};
        List<BossInfo> out = new java.util.ArrayList<>(ms.length);
        for (String name : ms) out.add(monster(floor, monName(name), 1, 1.0, false)); // [TORRE_GRUPO] stats cheios p/ cada um
        return out;
    }

    /** Representante do andar (MVP ou 1º monstro) — preview de stats na UI. */
    public BossInfo bossForFloor(int floor) {
        return monstersFor(floor).get(0);
    }

    // [I18N] atmosfera do andar no idioma do request; EN = a prosa do TowerFloors (default do getOr).
    public String  floorAtmosphere(int floor) {
        return messages.getOr("tower.floor." + floor + ".atmosphere", TowerFloors.forFloor(floor).atmosphere());
    }
    public boolean isMvpFloor(int floor)       { return TowerFloors.forFloor(floor).isMvp(); }

    /** Preview do andar pra UI: atmosfera + nomes dos monstros + stats do representante + nível recomendado. */
    public record FloorView(int floor, String atmosphere, boolean isMvp, List<String> monsters,
                            BossInfo primary, int recommendedLevel) {}
    public FloorView floorView(int floor) {
        TowerFloors.FloorDef d = TowerFloors.forFloor(floor);
        List<String> names = d.isMvp() ? List.of(monName(d.mvp()))
                : java.util.Arrays.stream(d.monsters()).map(this::monName).toList(); // [I18N]
        return new FloorView(floor, floorAtmosphere(floor), d.isMvp(), names, bossForFloor(floor), recommendedLevel(floor)); // [I18N]
    }

    /** Nível recomendado: ~1 andar por nível (alvo de tuning; placeholder p/ playtest). [TORRE_NARRATIVA] */
    public static int recommendedLevel(int floor) {
        return Math.max(1, floor);
    }

    // ── Resultado de um combate ──
    public record FightResult(boolean won, int floor, long bronzeEarned, long expEarned,
                              List<String> log, String bossName, boolean runOver,
                              String atmosphere, boolean arkaChoicePending,
                              List<BattleSimulator.BattleEvent> events) {} // [BATALHA_ANIMADA]

    public Optional<TowerRun> getCurrentRun(Player player) {
        return towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS);
    }

    public List<Player> getRanking() {
        return getRanking(0, 20);
    }

    /** [PAGINACAO] Página do ranking (só quem subiu, ordenado por melhor andar) — limite/offset no DB. */
    public List<Player> getRanking(int page, int size) {
        return playerRepository.findByTowerBestFloorGreaterThanOrderByTowerBestFloorDesc(
                0, org.springframework.data.domain.PageRequest.of(Math.max(0, page), size));
    }

    @Transactional
    public TowerRun enter(Player player) {
        log.info("[TowerService] player={} action=enter", player.getId());
        if (towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS).isPresent()) {
            log.warn("[TowerService] player={} REJECTED: already inside the tower", player.getId());
            throw new IllegalStateException("You are already inside the tower");
        }

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));

        if (warrior.isKnockedOut()) {
            log.warn("[TowerService] player={} REJECTED: warrior is unconscious", player.getId());
            throw new IllegalStateException("Your warrior is unconscious. Visit the Temple to heal!");
        }

        WorkService.assertNotBusy(workSessionRepository, player); // [WORK_IDLE] não sobe a torre enquanto trabalha

        // Estamina ignorada quando instant-complete (modo de teste). [TESTE]
        if (!instantComplete) {
            int cost = playerService.discountStamina(player, STAMINA_COST); // [ESTABULO] desconto da montaria
            int stamina = player.getCalculatedStamina();
            if (stamina < cost) {
                log.warn("[TowerService] player={} REJECTED: insufficient stamina {}/{}", player.getId(), stamina, cost);
                throw new com.medieval.game.config.LocalizedException("error.tower_stamina", "Insufficient stamina ({0}/{1})", stamina, cost);
            }
            player.setCurrentStamina(stamina - cost);
            player.setStaminaUpdatedAt(java.time.LocalDateTime.now());
            playerRepository.save(player);
        }

        // Começa do andar seguinte ao melhor já completado (checkpoint)
        int startFloor = player.getTowerBestFloor() > 0
                ? player.getTowerBestFloor() + 1
                : 1;
        // [TORRE_NARRATIVA] A Torre tem 50 andares (S1). Quem já chegou ao topo a conquistou.
        if (startFloor > TowerFloors.maxFloor()) {
            throw new IllegalStateException("You stand atop the Tower. There is nothing above — only what waits below.");
        }

        TowerRun run = new TowerRun();
        run.setPlayer(player);
        run.setCurrentFloor(startFloor);
        run.setHighestFloor(player.getTowerBestFloor()); // já completados anteriormente
        TowerRun saved = towerRunRepository.save(run);
        log.info("[TowerService] player={} action=enter OK runId={} startFloor={}", player.getId(), saved.getId(), startFloor);
        return saved;
    }

    @Transactional
    public FightResult fight(Player player) {
        log.info("[TowerService] player={} action=climbToNextFloor", player.getId());
        // [TOWER_OPTLOCK] Re-carrega o player GERENCIADO nesta transação. O controller passa uma entidade
        // DETACHED (open-in-view=false); como fight() salva o player várias vezes (taxa de subida, bronze,
        // best-floor) + sub-serviços, re-salvar a detached fazia a @Version "andar pra trás" no merge →
        // OptimisticLockException sistemático no climb. Mesmo padrão do ClassChangeService.attemptTrial.
        player = playerRepository.findById(player.getId())
                .orElseThrow(() -> new IllegalStateException("Player not found"));
        TowerRun run = towerRunRepository.findByPlayerAndStatus(player, TowerStatus.IN_PROGRESS)
                .orElseThrow(() -> new IllegalStateException("You are not in the tower"));

        int floor = run.getCurrentFloor();
        TowerFloors.FloorDef fdef = TowerFloors.forFloor(floor);
        List<BossInfo> monsters = monstersFor(floor);
        String headline = fdef.isMvp() ? monName(fdef.mvp()) // [I18N]
                : (monsters.size() > 1 ? messages.getOr("tower.n_monsters", monsters.size() + " monsters", monsters.size())
                                       : monsters.get(0).name()); // get(0).name() já localizado

        // Climb fee (scalable sink) — the Tower stops being pure income. [AUDITORIA A3]
        long climbCost = (long) floor * 15;
        if (player.totalBronze() < climbCost) {
            log.warn("[TowerService] player={} REJECTED: insufficient bronze to climb (have={} need={})",
                    player.getId(), player.totalBronze(), climbCost);
            throw new com.medieval.game.config.LocalizedException("error.tower_bronze", "Not enough bronze to face floor {0} (cost {1} bronze).", floor, climbCost);
        }
        playerService.spendBronze(player, climbCost);

        Warrior warrior = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found"));
        int[] s = statsService.combatStats(player, warrior);
        boolean ranged = statsService.isRangedWeaponEquipped(player); // [KITING] arma ranged (arco), qualquer classe

        // [TORRE_NARRATIVA][TORRE_GRUPO] Atmosfera + a luta: 1 monstro = duelo; vários = TODOS ao mesmo tempo.
        List<String> battleLog = new java.util.ArrayList<>();
        List<BattleSimulator.BattleEvent> allEvents = new java.util.ArrayList<>(); // [BATALHA_ANIMADA]
        battleLog.add("🗼 Floor " + floor + " — " + floorAtmosphere(floor)); // [I18N]
        BattleSimulator.BattleOutcome out;
        if (monsters.size() == 1) {
            BossInfo m = monsters.get(0);
            out = battleSimulator.simulateDetailed(
                warrior.getName(), s[0], s[1], s[2], s[3], s[4], s[5],
                m.name(), m.attack(), m.defense(), m.health(), m.dex(), m.agi(), m.luk(),
                true, ranged, false); // PvE: timeout = derrota; melee [KITING]
        } else {
            // [TORRE_GRUPO] vários monstros = combate em grupo (atacam juntos); nomes únicos p/ o replay não colidir
            List<BattleSimulator.GroupFoe> foes = new java.util.ArrayList<>(monsters.size());
            java.util.Map<String, Integer> seen = new java.util.HashMap<>();
            for (BossInfo m : monsters) {
                int c = seen.merge(m.name(), 1, Integer::sum);
                String uniq = c == 1 ? m.name() : m.name() + " " + roman(c); // "Altar-Thing", "Altar-Thing II", …
                foes.add(new BattleSimulator.GroupFoe(uniq, m.attack(), m.defense(), m.health(), m.dex(), m.agi(), m.luk()));
            }
            out = battleSimulator.simulateGroup(
                warrior.getName(), s[0], s[1], s[2], s[3], s[4], s[5],
                foes, true, ranged); // PvE: timeout = derrota
        }
        List<String> lg = new java.util.ArrayList<>(out.log());
        lg.remove(lg.size() - 1); // tira a tag WINNER
        battleLog.addAll(lg);
        allEvents.addAll(out.events()); // [BATALHA_ANIMADA]
        boolean won = out.firstWon();

        inventoryService.wearEquippedItems(player); // desgaste de equipamento

        long bronzeEarned = 0, expEarned = 0;
        boolean arkaChoicePending = false;

        if (won) {
            bronzeEarned = (long) floor * 40;
            expEarned    = (long) floor * 20;
            player.addBronzeAmount(bronzeEarned);
            playerRepository.save(player);

            warrior.setExperience(warrior.getExperience() + expEarned);
            while (warrior.getExperience() >= warrior.expNeededForNextLevel()) {
                warrior.setExperience(warrior.getExperience() - warrior.expNeededForNextLevel());
                warrior.levelUp();
            }
            warriorRepository.save(warrior);

            long got = gatheringService.addResource(player, com.medieval.game.enums.ResourceType.MONSTER_CORE, 1 + floor / 5);
            if (got > 0) battleLog.add("🧩 +" + got + " Monster Core");

            run.setHighestFloor(floor);
            run.setCurrentFloor(floor + 1);
            if (floor > player.getTowerBestFloor()) {
                player.setTowerBestFloor(floor);
                playerRepository.save(player);
                achievementService.checkAndUnlock(player, true); // [TITULOS] Tower Climber/Conqueror
            }

            // [TORRE_NARRATIVA] Topo: derrotou o Rei Arka (andar 50) → a escolha (poupar/matar) + fim da S1.
            if (fdef.isMvp() && floor >= TowerFloors.maxFloor()) {
                run.setStatus(TowerStatus.EXITED); // a Torre acaba aqui
                arkaChoicePending = !achievementService.has(player, com.medieval.game.enums.Achievement.REGICIDE)
                                 && !achievementService.has(player, com.medieval.game.enums.Achievement.THE_MERCIFUL);
            }
        } else {
            run.setStatus(TowerStatus.DEFEATED);
            warrior.applyDamagePercent(100);
            warrior.clearBuff();
            warriorRepository.save(warrior);
        }

        towerRunRepository.save(run);
        log.info("[TowerService] player={} action=climb OK floor={} won={} mvp={} bronze={} xp={}",
                player.getId(), floor, won, fdef.isMvp(), bronzeEarned, expEarned);
        boolean runOver = run.getStatus() == TowerStatus.DEFEATED || run.getStatus() == TowerStatus.EXITED;
        return new FightResult(won, floor, bronzeEarned, expEarned, battleLog, headline,
                runOver, floorAtmosphere(floor), arkaChoicePending, allEvents); // [I18N][BATALHA_ANIMADA]
    }

    /**
     * [TORRE_NARRATIVA][TITULOS] Resolve a escolha no topo da Torre, depois de derrotar o Rei Arka:
     * poupar (→ The Merciful) ou matar (→ Regicide). Uma só vez. Os dois "abrem o portal" (fim da S1).
     */
    @Transactional
    public String resolveArkaChoice(Player player, boolean spare) {
        if (player.getTowerBestFloor() < TowerFloors.maxFloor())
            throw new IllegalStateException("You have not yet faced the King.");
        if (achievementService.has(player, com.medieval.game.enums.Achievement.REGICIDE)
                || achievementService.has(player, com.medieval.game.enums.Achievement.THE_MERCIFUL))
            throw new IllegalStateException("The choice is already made.");
        boolean granted = achievementService.grant(player,
                spare ? com.medieval.game.enums.Achievement.THE_MERCIFUL
                      : com.medieval.game.enums.Achievement.REGICIDE);
        log.info("[TowerService] player={} arkaChoice spare={} granted={}", player.getId(), spare, granted);
        return spare
            ? messages.getOr("tower.arka.spare", "You lower your blade. King Arka thanks you — and buries the ritual dagger in his own heart, over the mark on the floor. \"Worse things are coming,\" he breathes. The floor opens beneath you.")
            : messages.getOr("tower.arka.kill",  "You strike. The King's blood spills across the mark, and the floor gives way beneath you. Far below, something begins to wake.");
    }

}
