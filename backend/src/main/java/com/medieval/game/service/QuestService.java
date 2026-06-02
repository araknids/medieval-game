package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.enums.QuestType;
import com.medieval.game.model.*;
import com.medieval.game.repository.ActiveQuestRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class QuestService {

    private final ActiveQuestRepository questRepository;
    private final WarriorRepository     warriorRepository;
    private final PlayerRepository      playerRepository;
    private final PlayerService         playerService;
    private final WarriorService        warriorService;
    private final InventoryService      inventoryService;
    private final ItemLoreGenerator     loreGenerator;
    private final TerritoryService      territoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    public record CollectResult(ActiveQuest quest, InventoryItem droppedItem) {}

    @Transactional
    public ActiveQuest sendOnQuest(Player player, QuestType questType) {
        Warrior warrior = warriorService.getWarrior(player);
        if (warrior.isOnMission()) {
            throw new IllegalStateException("This warrior is already on a mission");
        }

        if (!instantComplete) {
            playerService.consumeStamina(player, questType.staminaCost);
        }

        warrior.setOnMission(true);
        warriorRepository.save(warrior);

        ActiveQuest quest = new ActiveQuest();
        quest.setPlayer(player);
        quest.setWarrior(warrior);
        quest.setQuestType(questType);
        quest.setStartedAt(LocalDateTime.now());
        quest.setCompletesAt(instantComplete
                ? LocalDateTime.now()
                : LocalDateTime.now().plusMinutes(questType.durationMinutes));
        quest.setGoldReward(questType.bronzeReward);
        quest.setExpReward(questType.expReward);
        quest.setStatus(QuestStatus.IN_PROGRESS);
        return questRepository.save(quest);
    }

    public List<ActiveQuest> getActiveQuests(Player player) {
        return questRepository.findAllByPlayerAndStatusNotIn(player,
                java.util.List.of(QuestStatus.COLLECTED, QuestStatus.ABANDONED));
    }

    @Transactional
    public void abandonQuest(Player player, Long questId) {
        ActiveQuest quest = questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found"));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("This quest does not belong to you");
        }
        if (quest.getStatus() != QuestStatus.IN_PROGRESS) {
            throw new IllegalStateException("Quest cannot be abandoned");
        }

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        quest.setStatus(QuestStatus.ABANDONED);
        questRepository.save(quest);
    }

    @Transactional
    public CollectResult collectReward(Player player, Long questId) {
        ActiveQuest quest = questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found: " + questId));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("This quest does not belong to you");
        }
        if (quest.getStatus() == QuestStatus.COLLECTED) {
            throw new IllegalStateException("Reward already collected");
        }
        if (!quest.isReadyToCollect()) {
            long secsLeft = java.time.Duration.between(LocalDateTime.now(), quest.getCompletesAt()).getSeconds();
            throw new IllegalStateException("Quest not yet complete. " + secsLeft + "s");
        }

        // Apply guild passive bonuses
        Guild guild      = playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
        int xpPct        = guild != null ? guild.xpBonus()    : 0;
        int bronzePct    = guild != null ? guild.bronzeBonus() : 0;
        int guildDropPct = guild != null ? guild.dropBonus()   : 0;

        // Apply territory bonuses (stack with guild bonuses)
        TerritoryService.TerritoryBonus territory = territoryService.getBonusForPlayer(player);
        xpPct     += territory.xpBonus()     + territory.questXpBonus();
        bronzePct += territory.bronzeBonus();
        // territory does not add drop bonus directly, only guild luck does

        long totalBronze = quest.getGoldReward() + Math.round(quest.getGoldReward() * bronzePct / 100.0);
        long totalXp     = quest.getExpReward()  + Math.round(quest.getExpReward()  * xpPct     / 100.0);

        playerService.addGold(player, totalBronze);
        warriorService.addExperience(quest.getWarrior(), totalXp);

        // Reload warrior to clear mission flag
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        quest.setStatus(QuestStatus.COLLECTED);
        questRepository.save(quest);

        InventoryItem drop = rollDrop(player, quest.getQuestType(), guildDropPct);
        return new CollectResult(quest, drop);
    }

    // ── Drop system ──

    private InventoryItem rollDrop(Player player, QuestType type, int guildDropBonus) {
        Random rng = new Random();
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);
        int luckBonus = warrior != null ? warrior.getLuck() : 0;

        int dropChance = switch (type) {
            case PATROL    -> 10;
            case DUNGEON   -> 25;
            case RAID      -> 40;
            case BOSS_HUNT -> 60;
        } + luckBonus + guildDropBonus;

        if (rng.nextInt(100) >= dropChance) return null;

        int rarity = switch (type) {
            case PATROL    -> 1;
            case DUNGEON   -> rng.nextBoolean() ? 1 : 2;
            case RAID      -> rng.nextBoolean() ? 2 : 3;
            case BOSS_HUNT -> rng.nextBoolean() ? 3 : 4;
        };

        return generateItem(player, rarity, rng, type.displayName);
    }

    private InventoryItem generateItem(Player player, int rarity, Random rng, String questName) {
        ItemType type = ItemType.values()[rng.nextInt(ItemType.values().length)];

        // Stats escalam com raridade
        int maxAtk = rarity * 3;
        int maxDef = rarity * 3;
        int maxHp  = rarity * 12;

        int atk = rng.nextInt(maxAtk + 1);
        int def = rng.nextInt(maxDef + 1);
        int hp  = rng.nextInt(maxHp  + 1);

        // Garante pelo menos 1 stat
        if (atk == 0 && def == 0 && hp == 0) {
            switch (rng.nextInt(3)) {
                case 0 -> atk = 1;
                case 1 -> def = 1;
                default -> hp = rarity * 4;
            }
        }

        long sellPrice = switch (rarity) {
            case 2 -> 150; case 3 -> 400; case 4 -> 1000; default -> 25;
        };

        String name   = itemName(type, rarity, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest(questName);
        return inventoryService.make(player, name, type, atk, def, hp, rarity, sellPrice, lore, origin);
    }

    private String itemName(ItemType type, int rarity, Random rng) {
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
}
