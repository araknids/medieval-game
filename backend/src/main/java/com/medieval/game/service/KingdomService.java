package com.medieval.game.service;

import com.medieval.game.enums.*;
import com.medieval.game.model.*;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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
    private final ItemLoreGenerator            loreGenerator;
    private final TerritoryService             territoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

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
            TerritoryControl ctrl = territoryService.getTerritory(k.territory);
            String guildName = ctrl.isNeutral() ? null : ctrl.getControllingGuild().getName();
            boolean isMine   = myBonus.territory() == k.territory;
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

    public List<KingdomQuestType> getQuestsForKingdom(Kingdom kingdom) {
        return Arrays.stream(KingdomQuestType.values())
                .filter(q -> q.kingdom == kingdom)
                .toList();
    }

    @Transactional
    public KingdomActiveQuest startQuest(Player player, Kingdom kingdom, KingdomQuestType questType) {
        if (questType.kingdom != kingdom)
            throw new IllegalArgumentException("Quest does not belong to this kingdom.");

        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));

        if (warrior.isOnMission())
            throw new IllegalStateException("Your warrior is already busy.");

        playerService.consumeStamina(player, questType.staminaCost);

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
        return questRepo.save(quest);
    }

    @Transactional
    public CollectResult collectQuest(Player player, Long questId) {
        KingdomActiveQuest quest = questRepo.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found."));

        if (!quest.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This quest does not belong to you.");
        if (quest.getStatus() == QuestStatus.COLLECTED)
            throw new IllegalStateException("Reward already collected.");
        if (!quest.isReadyToCollect())
            throw new IllegalStateException("Quest not yet complete. " + quest.secondsRemaining() + "s remaining.");

        // Apply guild + territory bonuses
        Guild guild = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        int xpPct     = guild != null ? guild.xpBonus()    : 0;
        int bronzePct = guild != null ? guild.bronzeBonus() : 0;

        TerritoryService.TerritoryBonus terr = territoryService.getBonusForPlayer(player);
        xpPct     += terr.xpBonus() + terr.questXpBonus();
        bronzePct += terr.bronzeBonus();

        long totalBronze = quest.getBronzeReward() + Math.round(quest.getBronzeReward() * bronzePct / 100.0);
        long totalXp     = quest.getExpReward()    + Math.round(quest.getExpReward()    * xpPct     / 100.0);

        playerService.addGold(player, totalBronze);
        warriorService.addExperience(quest.getWarrior(), totalXp);

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });

        quest.setStatus(QuestStatus.COLLECTED);
        questRepo.save(quest);

        // Drop chance same logic as regular quests
        int guildDrop = guild != null ? guild.dropBonus() : 0;
        InventoryItem drop = rollDrop(player, quest.getQuestType().dropChance, guildDrop);
        return new CollectResult(quest, drop, totalBronze, totalXp);
    }

    @Transactional
    public void abandonQuest(Player player, Long questId) {
        KingdomActiveQuest quest = questRepo.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found."));

        if (!quest.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This quest does not belong to you.");
        if (quest.getStatus() != QuestStatus.IN_PROGRESS)
            throw new IllegalStateException("Quest cannot be abandoned.");

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });

        quest.setStatus(QuestStatus.ABANDONED);
        questRepo.save(quest);
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
        if (hours < 1 || hours > 12)
            throw new IllegalArgumentException("Training duration must be 1-12 hours.");

        if (trainingRepo.existsByPlayerAndStatus(player, TrainingStatus.IN_PROGRESS))
            throw new IllegalStateException("You are already training.");

        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));
        if (warrior.isOnMission())
            throw new IllegalStateException("Your warrior is busy.");

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
        return trainingRepo.save(session);
    }

    @Transactional
    public TrainingSession collectTraining(Player player, Long sessionId) {
        TrainingSession session = trainingRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found."));

        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This session does not belong to you.");
        if (session.getStatus() == TrainingStatus.COLLECTED)
            throw new IllegalStateException("Already collected.");
        if (!session.isReadyToCollect()) {
            long mins = java.time.Duration.between(LocalDateTime.now(), session.getFinishesAt()).toMinutes();
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
        return trainingRepo.save(session);
    }

    public Optional<TrainingSession> getCurrentTraining(Player player) {
        return trainingRepo.findByPlayerAndStatus(player, TrainingStatus.IN_PROGRESS);
    }

    @Transactional
    public void cancelTraining(Player player, Long sessionId) {
        TrainingSession session = trainingRepo.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Training session not found."));
        if (!session.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This session does not belong to you.");
        if (session.getStatus() != TrainingStatus.IN_PROGRESS)
            throw new IllegalStateException("Session is not in progress.");

        session.setStatus(TrainingStatus.CANCELLED);
        trainingRepo.save(session);

        warriorRepo.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepo.save(w);
        });
    }

    // ── Drop helper ───────────────────────────────────────────────────────────

    private InventoryItem rollDrop(Player player, int dropChance, int guildBonus) {
        var rng = new java.util.Random();
        Warrior warrior = warriorRepo.findByPlayer(player).orElse(null);
        int luck  = warrior != null ? warrior.getLuck() : 0;
        int total = dropChance + luck + guildBonus;
        if (rng.nextInt(100) >= total) return null;

        int rarity = dropChance >= 60 ? (rng.nextBoolean() ? 3 : 4)
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

        long price = switch (rarity) { case 2->150L; case 3->400L; case 4->1000L; default->25L; };
        String name   = itemName(type, rarity, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest("Kingdom Quest");
        return inventoryService.make(player, name, type, atk, def, hp, rarity, price, lore, origin);
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
            case 4 -> new String[]{"Legendary", "of the Dragon", "Cursed"};
            default -> new String[]{"of Iron", "of Leather", "of Wood"};
        };
        return bases[rng.nextInt(bases.length)] + " " + suffixes[rng.nextInt(suffixes.length)];
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    public record CollectResult(
            KingdomActiveQuest quest,
            InventoryItem droppedItem,
            long bronzeEarned,
            long xpEarned
    ) {}
}
