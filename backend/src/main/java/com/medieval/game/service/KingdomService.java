package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.quest.InteractiveQuests;
import com.medieval.game.quest.QuestDialog;
import com.medieval.game.quest.QuestDialog.QuestOption;
import com.medieval.game.quest.QuestOutcome;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class KingdomService {

    // Bronze cost per training hour = warrior level × this multiplier
    private static final int TRAINING_BRONZE_PER_HOUR_PER_LEVEL = 10;
    // XP per training hour = warrior level × this multiplier
    private static final int TRAINING_XP_PER_HOUR_PER_LEVEL = 25;

    private final KingdomActiveQuestRepository questRepo;
    private final TrainingSessionRepository    trainingRepo;
    private final WarriorRepository            warriorRepo;
    private final PlayerRepository             playerRepository;
    private final PlayerService                playerService;
    private final WarriorService               warriorService;
    private final InventoryService             inventoryService;
    private final MailService                  mailService;
    private final ItemLoreGenerator            loreGenerator;
    private final TerritoryService             territoryService;
    private final WarriorStatsService          statsService;
    private final BattleSimulator              battleSimulator;
    private final KingdomQuestNarrator         narrator;
    private final PetService                   petService; // quest rara da Luna. [PETS]
    private final AbilityService               abilityService; // +drop do Mercador (Treasure Hunter) [MERCADOR]
    private final Messages                     messages;       // [I18N] desfechos de quest interativa por idioma

    // ── Quest rara da Luna (pet): aparição + chance de pity. [PETS] ──
    private static final int  LUNA_WINDOW_DENOM = 12;      // ~1 a cada 12 janelas de 12h (~1x por semana) — evento raro [QUESTS_LORE]
    private static final int  LUNA_BASE_PPM     = 100;     // 0.01% base (em ppm)
    private static final int  LUNA_STEP_PPM     = 50;      // +0.005% por tentativa
    private static final int  LUNA_CAP_PPM      = 10_000;  // teto 1%

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // [DAILY_QUESTS] Vitrine de quests = daily: gira E reseta a cada 12h (janela global fixa,
    // 00:00/12:00 UTC). epoch/43200 alinha exatamente nesses horários. Ver docs/PLANO_QUESTS.md.
    // NÃO confundir com o 21600 do território/guild-war (getAllKingdomStatus) nem com a Loja.
    private static final long QUEST_ROTATION_SECONDS = 43200;

    // ── Kingdom status overview ───────────────────────────────────────────────

    public record KingdomStatus(
            Kingdom kingdom,
            String controllingGuild,
            boolean isMine,
            int xpBonus,
            int bronzeBonus,
            int exclusiveBonus,
            long secsUntilBattle,
            int defenseStreak
    ) {}

    public List<KingdomStatus> getAllKingdomStatus(Player player) {
        TerritoryService.TerritoryBonus myBonus = territoryService.getBonusForPlayer(player);
        long secsUntilNext = 21600 - (java.time.Instant.now().getEpochSecond() % 21600);

        return Arrays.stream(Kingdom.values()).map(k -> {
            // Reino sem guerra de guild (ex.: Garimpo/Covil) → sem dados de território. [REINOS_V2]
            if (!territoryService.isWarKingdom(k)) {
                return new KingdomStatus(k, null, false, 0, 0, 0, 0, 0);
            }
            TerritoryControl ctrl = territoryService.getTerritory(k);
            String guildName = ctrl.isNeutral() ? null : ctrl.getControllingGuild().getName();
            boolean isMine   = myBonus.territory() == k;
            return new KingdomStatus(
                k,
                guildName,
                isMine,
                isMine ? myBonus.xpBonus()     : 0,
                isMine ? myBonus.bronzeBonus()  : 0,
                isMine ? myBonus.territory() != null ? myBonus.territory().exclusiveBonus : 0 : 0,
                secsUntilNext,
                ctrl.getDefenseStreak()
            );
        }).toList();
    }

    // ── Kingdom quests ────────────────────────────────────────────────────────

    /** Todas as quests definidas para o reino (6 por reino). A Luna é especial e fica fora da rotação. [PETS] */
    public List<KingdomQuestType> allQuestsForKingdom(Kingdom kingdom) {
        return Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == kingdom && q != KingdomQuestType.RESCUE_STRAY_DOG)
                .toList();
    }

    /** A janela atual é uma "janela da Luna" para este player? Determinístico (~1 a cada 4 janelas). [PETS] */
    public boolean isLunaWindow(Player player) {
        long h = player.getId() * 2654435761L + currentQuestWindowId();
        return Math.floorMod(h, LUNA_WINDOW_DENOM) == 0;
    }

    /** A quest da Luna deve aparecer na vitrine agora? (janela da Luna + ainda não tem a Luna). [PETS] */
    public boolean lunaQuestActive(Player player) {
        return isLunaWindow(player) && !petService.owns(player, PetType.LUNA);
    }

    /**
     * Vitrine de quests (daily): mostra 2 das 6 do reino, revezando a cada 12h (janela global fixa).
     * Avança 1 posição por janela → cada quest aparece em 2 janelas consecutivas. [DAILY_QUESTS]
     */
    public List<KingdomQuestType> getQuestsForKingdom(Kingdom kingdom) {
        return rotatingWindow(allQuestsForKingdom(kingdom), currentQuestWindowId());
    }

    /** Janela de 2 quests para a rotação dada (determinística — testável). [Quests V2] */
    static List<KingdomQuestType> rotatingWindow(List<KingdomQuestType> all, long rotationId) {
        if (all.size() <= 2) return all;
        int start = (int) Math.floorMod(rotationId, all.size());
        return List.of(all.get(start), all.get((start + 1) % all.size()));
    }

    /** Id da janela diária de 12h atual (epoch/43200). Reset/rotação acontecem quando isto muda. [DAILY_QUESTS] */
    public long currentQuestWindowId() {
        return java.time.Instant.now().getEpochSecond() / QUEST_ROTATION_SECONDS;
    }

    /** Segundos até o próximo reset/rotação diária (boundary de 12h). */
    public long secondsUntilQuestRotation() {
        return QUEST_ROTATION_SECONDS - (java.time.Instant.now().getEpochSecond() % QUEST_ROTATION_SECONDS);
    }

    /** Player já atingiu o limite desta daily na janela atual? Limite = 1× (normal) ou 2× (VIP). [DAILY_QUESTS] */
    public boolean isQuestDoneThisPeriod(Player player, KingdomQuestType questType) {
        int limit = player.isVip() ? 2 : 1; // [VIP] VIP pode fazer 1× a mais por janela
        long done = questRepo.countByPlayerAndQuestTypeAndStatusAndCompletedWindowId(
                player, questType, QuestStatus.COLLECTED, currentQuestWindowId());
        return done >= limit;
    }

    @Transactional
    public KingdomActiveQuest startQuest(Player player, Kingdom kingdom, KingdomQuestType questType) {
        log.info("[KingdomService] player={} action=startQuest kingdom={} questType={}", player.getId(), kingdom, questType);
        // A quest da Luna aparece em qualquer reino → bypassa o check de reino. [PETS]
        if (questType != KingdomQuestType.RESCUE_STRAY_DOG && questType.kingdom != kingdom) {
            log.warn("[KingdomService] player={} REJECTED: Quest does not belong to this kingdom", player.getId());
            throw new IllegalArgumentException("Quest does not belong to this kingdom.");
        }

        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));

        // [SEM_TIMER] uma quest ativa por vez (substitui o antigo guard onMission e fecha o bypass
        // do daily-lock: sem isto dava pra startar a mesma quest 2x antes de coletar). [DAILY_QUESTS]
        if (questRepo.existsByPlayerAndStatus(player, QuestStatus.IN_PROGRESS)) {
            log.warn("[KingdomService] player={} REJECTED: já tem quest em progresso", player.getId());
            throw new IllegalStateException("You already have a quest in progress. Collect it first.");
        }

        // [DAILY_QUESTS] daily 1x por janela de 12h — cobre normal E VIP instant (que chama este start)
        if (isQuestDoneThisPeriod(player, questType)) {
            log.warn("[KingdomService] player={} REJECTED: daily quest {} já feita nesta janela", player.getId(), questType);
            throw new IllegalStateException("You already did this daily quest. Comes back on the next reset.");
        }

        if (!instantComplete) playerService.consumeStamina(player, questType.staminaCost); // estamina ignorada no modo de teste [TESTE]

        KingdomActiveQuest quest = new KingdomActiveQuest();
        quest.setPlayer(player);
        quest.setWarrior(warrior);
        quest.setKingdom(kingdom);
        quest.setQuestType(questType);
        quest.setBronzeReward(questType.bronzeReward);
        quest.setExpReward(questType.expReward);
        quest.setStartedAt(LocalDateTime.now());
        // [SEM_TIMER] quest de reino instantânea (já pronta). -1s evita a corrida de sub-segundo
        // do isReadyToCollect quando o collect vem logo após o start (igual ao fix da zona). [FLAKE_FIX]
        quest.setCompletesAt(LocalDateTime.now().minusSeconds(1));
        KingdomActiveQuest saved = questRepo.save(quest);
        log.info("[KingdomService] player={} action=startQuest OK id={}", player.getId(), saved.getId());
        return saved;
    }

    /** Coleta padrão (não-interativa) — compat. */
    public CollectResult collectQuest(Player player, Long questId) {
        return collectQuest(player, questId, null);
    }

    @Transactional
    public CollectResult collectQuest(Player player, Long questId, String optionId) {
        log.info("[KingdomService] player={} action=collectQuest questId={} option={}", player.getId(), questId, optionId);
        KingdomActiveQuest quest = questRepo.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found."));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            log.warn("[KingdomService] player={} REJECTED: quest {} does not belong to this player", player.getId(), questId);
            throw new IllegalStateException("This quest does not belong to you.");
        }
        if (quest.getStatus() == QuestStatus.COLLECTED) {
            log.warn("[KingdomService] player={} REJECTED: quest {} reward already collected", player.getId(), questId);
            throw new IllegalStateException("Reward already collected.");
        }
        if (!quest.isReadyToCollect()) {
            log.warn("[KingdomService] player={} REJECTED: quest {} not yet complete, {}s remaining", player.getId(), questId, quest.secondsRemaining());
            throw new com.medieval.game.config.LocalizedException("error.quest_not_complete", "Quest not yet complete. {0}s remaining.", quest.secondsRemaining());
        }

        // Bônus de guild + território (aplicados só se houver recompensa)
        Guild guild = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        int xpPct     = guild != null ? guild.xpBonus()    : 0;
        int bronzePct = guild != null ? guild.bronzeBonus() : 0;
        TerritoryService.TerritoryBonus terr = territoryService.getBonusForPlayer(player);
        xpPct     += terr.xpBonus() + terr.questXpBonus();
        bronzePct += terr.bronzeBonus();

        KingdomQuestType qt = quest.getQuestType();
        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // [PETS] Quest rara da Luna: sem loot, só a chance de pet (pity escalante).
        if (qt == KingdomQuestType.RESCUE_STRAY_DOG) {
            return collectLunaQuest(player, quest, optionId, rng);
        }

        OutcomeResult res;
        if (InteractiveQuests.isInteractive(qt)) {
            // [QUESTS_INTERATIVAS] a escolha do jogador decide o desfecho (substitui o monsterChance)
            QuestDialog dialog = InteractiveQuests.dialogFor(qt).orElseThrow();
            if (optionId == null || optionId.isBlank()) {
                log.warn("[KingdomService] player={} REJECTED: interactive quest {} needs a choice", player.getId(), qt);
                throw new IllegalArgumentException("This quest requires you to make a choice.");
            }
            QuestOption option = dialog.options().stream().filter(o -> o.id().equals(optionId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid choice for this quest."));
            log.info("[KingdomService] player={} interactive quest={} chose option={}", player.getId(), qt, optionId);
            // [I18N] keyBase do desfecho: questdlg.<QT>.out.<optionId> (+ .ok/.fail no Check, + .text/.win/.lose)
            res = resolveOutcome(option.outcome(), player, warrior, qt, rng,
                    "questdlg." + qt.name() + ".out." + optionId);
        } else {
            // Não-interativa: encontro de monstro aleatório (escala com a dificuldade). [Quests V2]
            res = new OutcomeResult();
            res.encountered = rng.nextInt(100) < qt.monsterChance;
            res.bronzeMult = 1; res.xpMult = 1; res.dropChance = qt.dropChance;
            if (res.encountered) {
                MonsterFight fr = fightQuestMonster(player, warrior, qt, rng);
                res.won = fr.won(); res.monsterName = fr.monsterName(); res.battleLog = fr.battleLog();
                res.monsterLevel = fr.monsterLevel(); // [ITEM_DROP_LEVEL] drop sai no nível do monstro
            }
            res.rewarded = res.won;
            res.narrative = narrator.narrate(qt, res.encountered, res.won, res.monsterName, rng);
        }

        long totalBronze = 0, totalXp = 0;
        InventoryItem drop = null;
        if (res.rewarded) {
            totalBronze = Math.round(qt.bronzeReward * res.bronzeMult * (1 + bronzePct / 100.0));
            totalXp     = Math.round(qt.expReward    * res.xpMult     * (1 + xpPct     / 100.0));
            playerService.addGold(player, totalBronze);
            warriorService.addExperience(warrior, totalXp);
            int guildDrop = guild != null ? guild.dropBonus() : 0;
            // [ITEM_DROP_LEVEL] item sai no nível do monstro morto; sem combate (quest interativa) → nível do jogador.
            int dropLevel = res.monsterLevel > 0 ? res.monsterLevel : warrior.getLevel();
            drop = rollDrop(player, res.dropChance, guildDrop, dropLevel);
        }

        warriorRepo.save(warrior); // persiste HP/desgaste do combate da quest

        quest.setStatus(QuestStatus.COLLECTED);
        // [DAILY_QUESTS] coletar = consumir a daily (conta pro limite da janela: 1× normal, 2× VIP).
        quest.setCompletedWindowId(currentQuestWindowId());
        questRepo.save(quest);

        log.info("[KingdomService] player={} action=collectQuest OK interactive={} encountered={} won={} bronze={} xp={} drop={} roll={}",
                player.getId(), InteractiveQuests.isInteractive(qt), res.encountered, res.won, totalBronze, totalXp,
                drop != null ? drop.getName() : "none", res.roll);
        return new CollectResult(quest, drop, totalBronze, totalXp,
                res.narrative, res.encountered, res.won, res.monsterName, res.battleLog, res.roll, null);
    }

    /** Coleta da quest rara da Luna: sem loot; rola a chance de pet (pity escalante). [PETS] */
    private CollectResult collectLunaQuest(Player player, KingdomActiveQuest quest, String optionId, ThreadLocalRandom rng) {
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalArgumentException("This quest requires you to make a choice.");
        }
        // marca como coletada (consome a daily desta janela) qualquer que seja a escolha
        quest.setStatus(QuestStatus.COLLECTED);
        quest.setCompletedWindowId(currentQuestWindowId());
        questRepo.save(quest);

        if ("leave".equals(optionId)) {
            log.info("[KingdomService] player={} luna quest: walked away", player.getId());
            return new CollectResult(quest, null, 0, 0,
                    messages.getOr("questdlg.RESCUE_STRAY_DOG.out.leave.text",
                            "You walk past the whimpering stray and continue on your way."),
                    false, false, null, null, null, null);
        }
        // "help": sem loot, rola a chance de pet
        if (petService.owns(player, PetType.LUNA)) {
            return new CollectResult(quest, null, 0, 0,
                    messages.getOr("questdlg.RESCUE_STRAY_DOG.out.help.owned",
                            "You help the little stray. She's already safe with you."),
                    false, false, null, null, null, null);
        }
        int attempts  = player.getPetPityAttempts();
        int chancePpm = Math.min(LUNA_CAP_PPM, LUNA_BASE_PPM + LUNA_STEP_PPM * attempts);
        String pct    = String.format(java.util.Locale.US, "%.4f%%", chancePpm / 10_000.0);
        boolean got   = rng.nextInt(1_000_000) < chancePpm;

        if (got) {
            petService.grant(player, PetType.LUNA);
            log.info("[KingdomService] player={} LUNA ACQUIRED (attempts={} chance={})", player.getId(), attempts, pct);
            return new CollectResult(quest, null, 0, 0,
                    messages.getOr("questdlg.RESCUE_STRAY_DOG.out.help.got",
                            "You nurse the sick dog through the night. By dawn she's on her feet, tail wagging — and she won't leave your side. 🐶 Luna is now your companion!"),
                    false, false, null, null, null, "Luna");
        }
        player.setPetPityAttempts(attempts + 1);
        playerRepository.save(player);
        log.info("[KingdomService] player={} luna quest: helped, no pet (chance={} attemptsNow={})", player.getId(), pct, attempts + 1);
        return new CollectResult(quest, null, 0, 0,
                messages.getOr("questdlg.RESCUE_STRAY_DOG.out.help.nopet",
                        "You nurse the sick dog back to health. She licks your hand gratefully and trots off into the wild. (Bond chance was {0})", pct),
                false, false, null, null, null, null);
    }

    // ── Resolução de outcome interativo (recursivo p/ Check) [QUESTS_INTERATIVAS] ──

    // [I18N] keyBase identifica o desfecho (questdlg.<QT>.out.<optId>[.ok|.fail]); o sufixo final
    // (.text/.win/.lose) vem do tipo resolvido. EN = a prosa do catálogo (default do getOr).
    private OutcomeResult resolveOutcome(QuestOutcome outcome, Player player, Warrior warrior,
                                         KingdomQuestType qt, ThreadLocalRandom rng, String keyBase) {
        if (outcome instanceof QuestOutcome.Peaceful p) {
            OutcomeResult r = new OutcomeResult();
            r.rewarded = true; r.bronzeMult = p.bronzeMult(); r.xpMult = p.xpMult();
            r.dropChance = p.dropChance(); r.narrative = messages.getOr(keyBase + ".text", p.narrative());
            return r;
        }
        if (outcome instanceof QuestOutcome.Fight f) {
            MonsterFight fr = fightQuestMonster(player, warrior, qt, rng);
            OutcomeResult r = new OutcomeResult();
            r.encountered = true; r.won = fr.won(); r.rewarded = fr.won();
            r.bronzeMult = f.bronzeMult(); r.xpMult = f.xpMult(); r.dropChance = f.dropChance();
            r.narrative = fr.won() ? messages.getOr(keyBase + ".win",  f.winNarrative())
                                   : messages.getOr(keyBase + ".lose", f.loseNarrative());
            r.monsterName = fr.monsterName(); r.battleLog = fr.battleLog();
            return r;
        }
        if (outcome instanceof QuestOutcome.Check c) {
            int mod = attrValue(warrior, c.attr()) / 4;             // 1d20 + floor(attr/4) vs DC
            int d20 = rng.nextInt(20) + 1;
            boolean passed = d20 == 20 || (d20 != 1 && d20 + mod >= c.dc()); // nat 1 falha / nat 20 passa
            RollInfo roll = new RollInfo(attrAbbrev(c.attr()), d20, mod, c.dc(), passed);
            log.info("[KingdomService] player={} attr-check {} d20={}+{} vs DC{} -> {}",
                    player.getId(), roll.attr(), d20, mod, c.dc(), passed ? "PASS" : "FAIL");
            OutcomeResult r = resolveOutcome(passed ? c.onSuccess() : c.onFail(), player, warrior, qt, rng,
                    keyBase + (passed ? ".ok" : ".fail"));
            r.roll = roll;
            return r;
        }
        throw new com.medieval.game.config.LocalizedException("error.unknown_outcome", "Unknown outcome type: {0}", outcome);
    }

    /** Roda uma luta contra o monstro temático da quest; aplica HP/desgaste. */
    private MonsterFight fightQuestMonster(Player player, Warrior warrior, KingdomQuestType qt, ThreadLocalRandom rng) {
        String monsterName = narrator.pickMonster(qt.kingdom, rng);
        int[] s   = statsService.combatStats(player, warrior);
        int maxHp = s[2];
        int curHp = warrior.getCalculatedHpPercent() * maxHp / 100;
        int monsterLevel = questMobLevel(warrior.getLevel(), qt); // [ITEM_DROP_LEVEL]
        int[] mob = questMobStats(warrior.getLevel(), qt, rng);

        BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
            warrior.getName(), s[0], s[1], curHp, s[3], s[4], s[5],
            monsterName, mob[0], mob[1], mob[2], mob[3], mob[4], mob[5],
            true, warrior.getWarriorClass().isRanged(), false); // PvE: timeout = derrota; [KITING] monstro = melee

        boolean won = out.firstWon();
        List<String> lg = new ArrayList<>(out.log());
        if (!lg.isEmpty()) lg.remove(lg.size() - 1); // remove tag WINNER

        inventoryService.wearEquippedItems(player); // lutar desgasta equipamento
        int finalPct = maxHp > 0 ? Math.max(0, out.firstHpFinal() * 100 / maxHp) : 0;
        warrior.setCurrentHpSnapshot(finalPct);     // 0 = nocauteado
        warrior.setHpUpdatedAt(LocalDateTime.now());
        return new MonsterFight(won, monsterName, lg, monsterLevel);
    }

    private static int attrValue(Warrior w, Attribute a) {
        return switch (a) {
            case STRENGTH     -> w.getStrength();
            case DEXTERITY    -> w.getDexterity();
            case CONSTITUTION -> w.getConstitution();
            case AGILITY      -> w.getAgility();
            case LUCK         -> w.getLuck();
            case INTELLECT    -> w.getIntellect();
        };
    }

    private static String attrAbbrev(Attribute a) {
        return switch (a) {
            case STRENGTH -> "STR"; case DEXTERITY -> "DEX"; case CONSTITUTION -> "CON";
            case AGILITY -> "AGI"; case LUCK -> "LUCK"; case INTELLECT -> "INT";
        };
    }

    /** Estado mutável da resolução de um outcome. */
    private static final class OutcomeResult {
        boolean rewarded = false, encountered = false, won = true;
        double bronzeMult = 1, xpMult = 1; int dropChance = 0;
        String narrative = "", monsterName = null;
        List<String> battleLog = List.of();
        RollInfo roll = null;
        int monsterLevel = 0; // [ITEM_DROP_LEVEL] nível do monstro morto → vira o nível do item dropado (0 = sem monstro)
    }

    private record MonsterFight(boolean won, String monsterName, List<String> battleLog, int monsterLevel) {}

    @Transactional
    public void abandonQuest(Player player, Long questId) {
        log.info("[KingdomService] player={} action=abandonQuest questId={}", player.getId(), questId);
        KingdomActiveQuest quest = questRepo.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found."));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            log.warn("[KingdomService] player={} REJECTED: quest {} does not belong to this player", player.getId(), questId);
            throw new IllegalStateException("This quest does not belong to you.");
        }
        if (quest.getStatus() != QuestStatus.IN_PROGRESS) {
            log.warn("[KingdomService] player={} REJECTED: quest {} cannot be abandoned (status={})", player.getId(), questId, quest.getStatus());
            throw new IllegalStateException("Quest cannot be abandoned.");
        }

        quest.setStatus(QuestStatus.ABANDONED);
        questRepo.save(quest);
        log.info("[KingdomService] player={} action=abandonQuest OK questId={}", player.getId(), questId);
    }

    // [QUESTS_INTERATIVAS] instant-start VIP removido: as dailies agora são interativas (exigem escolha).
    // O perk de VIP virou "1× a mais por daily" (ver isQuestDoneThisPeriod).

    public List<KingdomActiveQuest> getActiveQuests(Player player, Kingdom kingdom) {
        return questRepo.findByPlayerAndKingdomAndStatusNot(player, kingdom, QuestStatus.COLLECTED)
                .stream().filter(q -> q.getStatus() != QuestStatus.ABANDONED).toList();
    }

    public List<KingdomActiveQuest> getAllActiveQuests(Player player) {
        return questRepo.findByPlayerAndStatusNotOrderByStartedAtDesc(player, QuestStatus.COLLECTED)
                .stream().filter(q -> q.getStatus() != QuestStatus.ABANDONED).toList();
    }

    // ── Training (Combat Kingdom only) ────────────────────────────────────────

    @Transactional
    public TrainingSession startTraining(Player player, int hours) {
        log.info("[KingdomService] player={} action=startTraining hours={}", player.getId(), hours);
        if (hours < 1 || hours > 12) {
            log.warn("[KingdomService] player={} REJECTED: training duration invalid hours={}", player.getId(), hours);
            throw new IllegalArgumentException("Training duration must be 1-12 hours.");
        }

        if (trainingRepo.existsByPlayerAndStatus(player, TrainingStatus.IN_PROGRESS)) {
            log.warn("[KingdomService] player={} REJECTED: already training", player.getId());
            throw new IllegalStateException("You are already training.");
        }

        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));

        long bronzeCost = (long) warrior.getLevel() * TRAINING_BRONZE_PER_HOUR_PER_LEVEL * hours;
        long xpReward   = (long) warrior.getLevel() * TRAINING_XP_PER_HOUR_PER_LEVEL    * hours;

        playerService.spendBronze(player, bronzeCost);

        TrainingSession session = new TrainingSession();
        session.setPlayer(player);
        session.setHours(hours);
        session.setBronzeCost(bronzeCost);
        session.setXpReward(xpReward);
        session.setStartedAt(LocalDateTime.now());
        session.setFinishesAt(LocalDateTime.now().minusSeconds(1)); // [SEM_TIMER] treino instantâneo; -1s evita corrida de sub-segundo [FLAKE_FIX]
        TrainingSession saved = trainingRepo.save(session);
        log.info("[KingdomService] player={} action=startTraining OK id={} xpReward={}", player.getId(), saved.getId(), xpReward);
        return saved;
    }

    @Transactional
    public TrainingSession collectTraining(Player player, Long sessionId) {
        log.info("[KingdomService] player={} action=collectTraining sessionId={}", player.getId(), sessionId);
        TrainingSession session = trainingRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found."));

        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[KingdomService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you.");
        }
        if (session.getStatus() == TrainingStatus.COLLECTED) {
            log.warn("[KingdomService] player={} REJECTED: session {} already collected", player.getId(), sessionId);
            throw new IllegalStateException("Already collected.");
        }
        if (!session.isReadyToCollect()) {
            long mins = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).toMinutes();
            log.warn("[KingdomService] player={} REJECTED: session {} still in progress, ~{}min remaining", player.getId(), sessionId, mins);
            throw new com.medieval.game.config.LocalizedException("error.still_training", "Still training. ~{0} minutes remaining.", mins);
        }

        warriorService.addExperience(
                warriorRepo.findByPlayer(player).orElseThrow(),
                session.getXpReward());

        session.setStatus(TrainingStatus.COLLECTED);
        TrainingSession result = trainingRepo.save(session);
        log.info("[KingdomService] player={} action=collectTraining OK xp={}", player.getId(), session.getXpReward());
        return result;
    }

    public Optional<TrainingSession> getCurrentTraining(Player player) {
        return trainingRepo.findByPlayerAndStatus(player, TrainingStatus.IN_PROGRESS);
    }

    @Transactional
    public void cancelTraining(Player player, Long sessionId) {
        log.info("[KingdomService] player={} action=cancelTraining sessionId={}", player.getId(), sessionId);
        TrainingSession session = trainingRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found."));
        if (!session.getPlayer().getId().equals(player.getId())) {
            log.warn("[KingdomService] player={} REJECTED: session {} does not belong to this player", player.getId(), sessionId);
            throw new IllegalStateException("This session does not belong to you.");
        }
        if (session.getStatus() != TrainingStatus.IN_PROGRESS) {
            log.warn("[KingdomService] player={} REJECTED: session {} is not in progress (status={})", player.getId(), sessionId, session.getStatus());
            throw new IllegalStateException("Session is not in progress.");
        }

        session.setStatus(TrainingStatus.CANCELLED);
        trainingRepo.save(session);
        log.info("[KingdomService] player={} action=cancelTraining OK sessionId={}", player.getId(), sessionId);
    }

    // ── Monstro da quest (escala com level + dificuldade) ─────────────────────
    // Calibrado para ser vencível por um guerreiro equipado; mais duro nas quests de tier alto.
    /** Nível do monstro da quest = nível do jogador × dificuldade (duração). Vira o nível do item dropado. [ITEM_DROP_LEVEL] */
    private int questMobLevel(int level, KingdomQuestType qt) {
        double diff = 0.8 + (qt.durationMinutes / 30.0) * 0.6;
        return Math.max(1, (int) Math.round(level * diff));
    }

    private int[] questMobStats(int level, KingdomQuestType qt, java.util.concurrent.ThreadLocalRandom rng) {
        double diff = 0.8 + (qt.durationMinutes / 30.0) * 0.6; // 5min→0.9 ... 30min→1.4
        int atk = (int) Math.round((3 + level * 2) * diff) + rng.nextInt(3);
        int def = (int) Math.round((1 + level)     * diff) + rng.nextInt(2);
        int hp  = (int) Math.round((40 + level * 12) * diff) + rng.nextInt(20);
        int dex = Math.min(level / 6, 8);  // AC = 10+dex, cap 18 (bounded accuracy) [COMBATE_V2]
        int str = Math.min(level / 15, 3);
        int luk = Math.min(level / 5, 8);
        return new int[]{atk, def, hp, dex, str, luk};
    }

    // ── Drop helper ───────────────────────────────────────────────────────────

    private InventoryItem rollDrop(Player player, int dropChance, int guildBonus, int dropLevel) {
        var rng = new java.util.Random();
        Warrior warrior = warriorRepo.findByPlayer(player).orElse(null);
        int luck  = warrior != null ? warrior.getLuck() : 0;
        int total = dropChance + luck + guildBonus + abilityService.dropChanceBonus(player); // [MERCADOR] Treasure Hunter
        if (rng.nextInt(100) >= total) return null;

        // Top tier (dropChance>=60) tem ~5% de chance de Lendário (5). [ITENS_V2]
        int rarity = dropChance >= 60 ? (rng.nextInt(100) < 5 ? 5 : (rng.nextBoolean() ? 3 : 4))
                   : dropChance >= 40 ? (rng.nextBoolean() ? 2 : 3)
                   : dropChance >= 25 ? (rng.nextBoolean() ? 1 : 2)
                   : 1;

        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(
                com.medieval.game.enums.ItemType.values().length)];

        // [ITEM_DROP_LEVEL] nível do item = nível do MONSTRO morto (não mais do jogador); stats escalam por nível×raridade.
        int itemLevel = Math.max(1, dropLevel);
        int[] s = inventoryService.rollItemStats(itemLevel, rarity);
        int atk = s[0], def = s[1], hp = s[2];

        long price = switch (rarity) { case 2->150L; case 3->400L; case 4->1000L; case 5->2500L; default->25L; };
        boolean isArcher = warrior != null && warrior.getWarriorClass() == com.medieval.game.enums.WarriorClass.ARCHER;
        String name   = itemName(type, rarity, isArcher, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest("Kingdom Quest");

        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            return inventoryService.make(player, name, type, atk, def, hp, rarity, price, itemLevel, lore, origin);
        } else {
            mailService.sendItemMail(player, "Drop de Kingdom Quest.",
                    name, type, atk, def, hp, rarity, itemLevel, 0, lore, origin);
            log.info("[KingdomService] player={} bag full — item '{}' sent to mail", player.getId(), name);
            return null;
        }
    }

    private String itemName(com.medieval.game.enums.ItemType type, int rarity, boolean isArcher, java.util.Random rng) {
        String[] bases = switch (type) {
            case HELMET   -> new String[]{"Helm", "Helmet"};
            case ARMOR    -> new String[]{"Armor", "Breastplate"};
            // [CLASSES_ARMAS] Varia o TIPO dentro da categoria da classe (o nome → WeaponType no make()).
            case WEAPON   -> isArcher ? new String[]{"Short Bow", "Long Bow", "Crossbow"}
                                      : new String[]{"Sword", "Greatsword", "Axe", "Spear"};
            case SHIELD   -> new String[]{"Shield", "Buckler"};
            case BOOTS    -> new String[]{"Boots", "Greaves"};
            case GLOVES   -> new String[]{"Gloves", "Gauntlets"};
            case PANTS    -> new String[]{"Pants", "Leggings"};
            case SHOULDER -> new String[]{"Shoulder", "Pauldron"};
            case NECKLACE -> new String[]{"Necklace", "Amulet"};
            case RING     -> new String[]{"Ring", "Signet"};
        };
        String[] suffixes = switch (rarity) {
            case 2 -> new String[]{"of Steel", "of Chainmail", "of Silver"};
            case 3 -> new String[]{"of the Elves", "of the Warrior", "Enchanted"};
            case 4 -> new String[]{"of the Dragon", "Cursed", "of Valor"};
            case 5 -> new String[]{"of the Ancients", "Mythic", "of Eternity"}; // Lendário [ITENS_V2]
            default -> new String[]{"of Iron", "of Leather", "of Wood"};
        };
        return bases[rng.nextInt(bases.length)] + " " + suffixes[rng.nextInt(suffixes.length)];
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public record CollectResult(
            KingdomActiveQuest quest,
            InventoryItem droppedItem,
            long bronzeEarned,
            long xpEarned,
            String narrative,
            boolean monsterEncountered,
            boolean monsterDefeated,
            String monsterName,
            List<String> battleLog,
            RollInfo roll,             // [QUESTS_INTERATIVAS] null se não houve teste de atributo
            String acquiredPet         // [PETS] nome do pet ganho (ex.: "Luna") ou null
    ) {}

    /** Resultado do roll d20 de um teste de atributo (pro modal). [QUESTS_INTERATIVAS] */
    public record RollInfo(String attr, int rolled, int mod, int dc, boolean passed) {}
}
