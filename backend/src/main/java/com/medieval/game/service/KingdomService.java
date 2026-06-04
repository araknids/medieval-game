package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    private final VipService                   vipService;
    private final WarriorStatsService          statsService;
    private final BattleSimulator              battleSimulator;
    private final KingdomQuestNarrator         narrator;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // Vitrine de quests gira a cada 6h (igual à Loja). [Quests V2]
    private static final long QUEST_ROTATION_SECONDS = 21600;

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

    /** Todas as quests definidas para o reino (6 por reino). */
    public List<KingdomQuestType> allQuestsForKingdom(Kingdom kingdom) {
        return Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == kingdom)
                .toList();
    }

    /**
     * Vitrine de quests: mostra 2 das 6 do reino, revezando a cada 6h (janela global, igual à Loja).
     * Avança 1 posição por janela → cada quest aparece em 2 janelas consecutivas. [Quests V2]
     */
    public List<KingdomQuestType> getQuestsForKingdom(Kingdom kingdom) {
        long rotationId = java.time.Instant.now().getEpochSecond() / QUEST_ROTATION_SECONDS;
        return rotatingWindow(allQuestsForKingdom(kingdom), rotationId);
    }

    /** Janela de 2 quests para a rotação dada (determinística — testável). [Quests V2] */
    static List<KingdomQuestType> rotatingWindow(List<KingdomQuestType> all, long rotationId) {
        if (all.size() <= 2) return all;
        int start = (int) Math.floorMod(rotationId, all.size());
        return List.of(all.get(start), all.get((start + 1) % all.size()));
    }

    /** Segundos até a próxima rotação da vitrine de quests. */
    public long secondsUntilQuestRotation() {
        return QUEST_ROTATION_SECONDS - (java.time.Instant.now().getEpochSecond() % QUEST_ROTATION_SECONDS);
    }

    @Transactional
    public KingdomActiveQuest startQuest(Player player, Kingdom kingdom, KingdomQuestType questType) {
        log.info("[KingdomService] player={} action=startQuest kingdom={} questType={}", player.getId(), kingdom, questType);
        if (questType.kingdom != kingdom) {
            log.warn("[KingdomService] player={} REJECTED: Quest does not belong to this kingdom", player.getId());
            throw new IllegalArgumentException("Quest does not belong to this kingdom.");
        }

        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));

        if (warrior.isOnMission()) {
            log.warn("[KingdomService] player={} REJECTED: warrior is already busy", player.getId());
            throw new IllegalStateException("Your warrior is already busy.");
        }

        if (!instantComplete) playerService.consumeStamina(player, questType.staminaCost); // estamina ignorada no modo de teste [TESTE]

        warrior.setOnMission(true);
        warriorRepo.save(warrior);

        KingdomActiveQuest quest = new KingdomActiveQuest();
        quest.setPlayer(player);
        quest.setWarrior(warrior);
        quest.setKingdom(kingdom);
        quest.setQuestType(questType);
        quest.setBronzeReward(questType.bronzeReward);
        quest.setExpReward(questType.expReward);
        quest.setStartedAt(LocalDateTime.now());
        quest.setCompletesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusMinutes(questType.durationMinutes));
        KingdomActiveQuest saved = questRepo.save(quest);
        log.info("[KingdomService] player={} action=startQuest OK id={}", player.getId(), saved.getId());
        return saved;
    }

    @Transactional
    public CollectResult collectQuest(Player player, Long questId) {
        log.info("[KingdomService] player={} action=collectQuest questId={}", player.getId(), questId);
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
            throw new IllegalStateException("Quest not yet complete. " + quest.secondsRemaining() + "s remaining.");
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
        var rng = java.util.concurrent.ThreadLocalRandom.current();

        // Encontro de monstro: chance escala com a dificuldade, sorteada na coleta. [Quests V2]
        boolean encountered     = rng.nextInt(100) < qt.monsterChance;
        boolean monsterDefeated = true;             // paz conta como "sem derrota"
        String  monsterName     = null;
        List<String> battleLog  = List.of();

        if (encountered) {
            monsterName = narrator.pickMonster(quest.getKingdom(), rng);
            int[] s   = statsService.combatStats(player, warrior);
            int maxHp = s[2];
            int curHp = warrior.getCalculatedHpPercent() * maxHp / 100;
            int[] mob = questMobStats(warrior.getLevel(), qt, rng);

            BattleSimulator.BattleOutcome out = battleSimulator.simulateDetailed(
                warrior.getName(), s[0], s[1], curHp, s[3], s[4], s[5],
                monsterName, mob[0], mob[1], mob[2], mob[3], mob[4], mob[5]);

            monsterDefeated = out.firstWon();
            List<String> lg = new java.util.ArrayList<>(out.log());
            if (!lg.isEmpty()) lg.remove(lg.size() - 1); // remove tag WINNER
            battleLog = lg;

            inventoryService.wearEquippedItems(player); // lutar desgasta equipamento

            int finalPct = maxHp > 0 ? Math.max(0, out.firstHpFinal() * 100 / maxHp) : 0;
            warrior.setCurrentHpSnapshot(finalPct);    // 0 = nocauteado
            warrior.setHpUpdatedAt(LocalDateTime.now());
        }

        long totalBronze = 0, totalXp = 0;
        InventoryItem drop = null;

        if (monsterDefeated) { // travessia em paz OU monstro derrotado → recompensa cheia
            totalBronze = quest.getBronzeReward() + Math.round(quest.getBronzeReward() * bronzePct / 100.0);
            totalXp     = quest.getExpReward()    + Math.round(quest.getExpReward()    * xpPct     / 100.0);
            playerService.addGold(player, totalBronze);
            warriorService.addExperience(warrior, totalXp);
            int guildDrop = guild != null ? guild.dropBonus() : 0;
            drop = rollDrop(player, qt.dropChance, guildDrop);
        }

        warrior.setOnMission(false);
        warriorRepo.save(warrior);

        quest.setStatus(QuestStatus.COLLECTED);
        questRepo.save(quest);

        String narrative = narrator.narrate(qt, encountered, monsterDefeated, monsterName, rng);
        log.info("[KingdomService] player={} action=collectQuest OK encountered={} won={} bronze={} xp={} drop={}",
                player.getId(), encountered, monsterDefeated, totalBronze, totalXp, drop != null ? drop.getName() : "none");
        return new CollectResult(quest, drop, totalBronze, totalXp,
                narrative, encountered, monsterDefeated, monsterName, battleLog);
    }

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

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });

        quest.setStatus(QuestStatus.ABANDONED);
        questRepo.save(quest);
        log.info("[KingdomService] player={} action=abandonQuest OK questId={}", player.getId(), questId);
    }

    // ── Missão Instantânea VIP ───────────────────────────────────────────────

    /**
     * VIP-only: starts a quest and immediately collects it (skips the timer).
     * Consumes one instant-quest charge from the daily VIP allowance.
     * Returns the full CollectResult just like normal collectQuest().
     */
    @Transactional
    public CollectResult instantStartQuest(Player player, Kingdom kingdom, KingdomQuestType questType) {
        log.info("[KingdomService] player={} action=instantStartQuest kingdom={} questType={}",
                player.getId(), kingdom, questType);

        // Validates VIP + decrements daily counter (throws if limit reached or no VIP)
        vipService.consumeInstantQuest(player);

        // Start the quest (same validations as normal start)
        KingdomActiveQuest quest = startQuest(player, kingdom, questType);

        // Force-complete immediately (set completesAt to past so isReadyToCollect() = true)
        quest.setCompletesAt(LocalDateTime.now().minusSeconds(1));
        questRepo.save(quest);

        // Collect (same logic as normal collect)
        CollectResult result = collectQuest(player, quest.getId());
        log.info("[KingdomService] player={} action=instantStartQuest OK questId={} bronze={} xp={}",
                player.getId(), quest.getId(), result.bronzeEarned(), result.xpEarned());
        return result;
    }

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
        if (warrior.isOnMission()) {
            log.warn("[KingdomService] player={} REJECTED: warrior is busy", player.getId());
            throw new IllegalStateException("Your warrior is busy.");
        }

        long bronzeCost = (long) warrior.getLevel() * TRAINING_BRONZE_PER_HOUR_PER_LEVEL * hours;
        long xpReward   = (long) warrior.getLevel() * TRAINING_XP_PER_HOUR_PER_LEVEL    * hours;

        playerService.spendBronze(player, bronzeCost);

        warrior.setOnMission(true);
        warriorRepo.save(warrior);

        TrainingSession session = new TrainingSession();
        session.setPlayer(player);
        session.setHours(hours);
        session.setBronzeCost(bronzeCost);
        session.setXpReward(xpReward);
        session.setStartedAt(LocalDateTime.now());
        session.setFinishesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusHours(hours));
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
            throw new IllegalStateException("Still training. ~" + mins + " minutes remaining.");
        }

        warriorService.addExperience(
                warriorRepo.findByPlayer(player).orElseThrow(),
                session.getXpReward());

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });

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

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });
        log.info("[KingdomService] player={} action=cancelTraining OK sessionId={}", player.getId(), sessionId);
    }

    // ── Monstro da quest (escala com level + dificuldade) ─────────────────────
    // Calibrado para ser vencível por um guerreiro equipado; mais duro nas quests de tier alto.
    private int[] questMobStats(int level, KingdomQuestType qt, java.util.concurrent.ThreadLocalRandom rng) {
        double diff = 0.8 + (qt.durationMinutes / 30.0) * 0.6; // 5min→0.9 ... 30min→1.4
        int atk = (int) Math.round((3 + level * 2) * diff) + rng.nextInt(3);
        int def = (int) Math.round((1 + level)     * diff) + rng.nextInt(2);
        int hp  = (int) Math.round((40 + level * 12) * diff) + rng.nextInt(20);
        int dex = Math.min(level / 3, 14);
        int str = Math.min(level / 15, 3);
        int luk = Math.min(level / 5, 8);
        return new int[]{atk, def, hp, dex, str, luk};
    }

    // ── Drop helper ───────────────────────────────────────────────────────────

    private InventoryItem rollDrop(Player player, int dropChance, int guildBonus) {
        var rng = new java.util.Random();
        Warrior warrior = warriorRepo.findByPlayer(player).orElse(null);
        int luck  = warrior != null ? warrior.getLuck() : 0;
        int total = dropChance + luck + guildBonus;
        if (rng.nextInt(100) >= total) return null;

        // Top tier (dropChance>=60) tem ~5% de chance de Lendário (5). [ITENS_V2]
        int rarity = dropChance >= 60 ? (rng.nextInt(100) < 5 ? 5 : (rng.nextBoolean() ? 3 : 4))
                   : dropChance >= 40 ? (rng.nextBoolean() ? 2 : 3)
                   : dropChance >= 25 ? (rng.nextBoolean() ? 1 : 2)
                   : 1;

        com.medieval.game.enums.ItemType type =
                com.medieval.game.enums.ItemType.values()[rng.nextInt(
                com.medieval.game.enums.ItemType.values().length)];

        int maxAtk = rarity * 3, maxDef = rarity * 3, maxHp = rarity * 12;
        int atk = rng.nextInt(maxAtk + 1);
        int def = rng.nextInt(maxDef + 1);
        int hp  = rng.nextInt(maxHp  + 1);
        if (atk == 0 && def == 0 && hp == 0) { switch(rng.nextInt(3)){case 0->atk=1;case 1->def=1;default->hp=rarity*4;} }

        long price = switch (rarity) { case 2->150L; case 3->400L; case 4->1000L; case 5->2500L; default->25L; };
        String name   = itemName(type, rarity, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest("Kingdom Quest");

        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            return inventoryService.make(player, name, type, atk, def, hp, rarity, price, lore, origin);
        } else {
            mailService.sendItemMail(player, "Drop de Kingdom Quest.",
                    name, type, atk, def, hp, rarity, 0, lore, origin);
            log.info("[KingdomService] player={} bag full — item '{}' sent to mail", player.getId(), name);
            return null;
        }
    }

    private String itemName(com.medieval.game.enums.ItemType type, int rarity, java.util.Random rng) {
        String[] bases = switch (type) {
            case HELMET   -> new String[]{"Helm", "Helmet"};
            case ARMOR    -> new String[]{"Armor", "Breastplate"};
            case WEAPON   -> new String[]{"Sword", "Blade"};
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
            List<String> battleLog
    ) {}
}
