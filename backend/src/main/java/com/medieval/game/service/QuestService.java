package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.enums.QuestType;
import com.medieval.game.model.*;
import com.medieval.game.repository.ActiveQuestRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class QuestService {

    private final ActiveQuestRepository questRepository;
    private final WarriorRepository     warriorRepository;
    private final PlayerRepository      playerRepository;
    private final PlayerService         playerService;
    private final WarriorService        warriorService;
    private final InventoryService      inventoryService;
    private final MailService           mailService;
    private final ItemLoreGenerator     loreGenerator;
    private final TerritoryService      territoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    public record CollectResult(ActiveQuest quest, InventoryItem droppedItem) {}

    @Transactional
    public ActiveQuest sendOnQuest(Player player, QuestType questType) {
        log.info("[QuestService] player={} action=sendOnQuest questType={}", player.getId(), questType);
        Warrior warrior = warriorService.getWarrior(player);
        if (warrior.isOnMission()) {
            log.warn("[QuestService] player={} REJECTED: warrior is already on a mission", player.getId());
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
        quest.setCompletesAt(LocalDateTime.now()); // [SEM_TIMER] quest instantânea — coleta imediata
        quest.setGoldReward(questType.bronzeReward);
        quest.setExpReward(questType.expReward);
        quest.setStatus(QuestStatus.IN_PROGRESS);
        ActiveQuest saved = questRepository.save(quest);
        log.info("[QuestService] player={} action=sendOnQuest OK id={}", player.getId(), saved.getId());
        return saved;
    }

    public List<ActiveQuest> getActiveQuests(Player player) {
        return questRepository.findAllByPlayerAndStatusNotIn(player,
                java.util.List.of(QuestStatus.COLLECTED, QuestStatus.ABANDONED));
    }

    @Transactional
    public void abandonQuest(Player player, Long questId) {
        log.info("[QuestService] player={} action=abandonQuest questId={}", player.getId(), questId);
        ActiveQuest quest = questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found"));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            log.warn("[QuestService] player={} REJECTED: quest {} does not belong to this player", player.getId(), questId);
            throw new IllegalStateException("This quest does not belong to you");
        }
        if (quest.getStatus() != QuestStatus.IN_PROGRESS) {
            log.warn("[QuestService] player={} REJECTED: quest {} cannot be abandoned (status={})", player.getId(), questId, quest.getStatus());
            throw new IllegalStateException("Quest cannot be abandoned");
        }

        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        quest.setStatus(QuestStatus.ABANDONED);
        questRepository.save(quest);
        log.info("[QuestService] player={} action=abandonQuest OK questId={}", player.getId(), questId);
    }

    @Transactional
    public CollectResult collectReward(Player player, Long questId) {
        log.info("[QuestService] player={} action=collectReward questId={}", player.getId(), questId);
        ActiveQuest quest = questRepository.findById(questId)
                .orElseThrow(() -> new IllegalArgumentException("Quest not found: " + questId));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            log.warn("[QuestService] player={} REJECTED: quest {} does not belong to this player", player.getId(), questId);
            throw new IllegalStateException("This quest does not belong to you");
        }
        if (quest.getStatus() == QuestStatus.COLLECTED) {
            log.warn("[QuestService] player={} REJECTED: quest {} reward already collected", player.getId(), questId);
            throw new IllegalStateException("Reward already collected");
        }
        if (!quest.isReadyToCollect()) {
            long secsLeft = java.time.Duration.between(LocalDateTime.now(), quest.getCompletesAt()).getSeconds();
            log.warn("[QuestService] player={} REJECTED: quest {} not yet complete, {}s remaining", player.getId(), questId, secsLeft);
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
        log.info("[QuestService] player={} action=collectReward OK bronze={} xp={} drop={}", player.getId(), totalBronze, totalXp, drop != null ? drop.getName() : "none");
        return new CollectResult(quest, drop);
    }

    // ── Drop system ──

    private InventoryItem rollDrop(Player player, QuestType type, int guildDropBonus) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
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
            // BOSS_HUNT: ~6% Lendário (5), resto dividido entre Épico (4) e Raro (3). [ITENS_V2]
            case BOSS_HUNT -> { int r = rng.nextInt(100); yield r < 6 ? 5 : (r < 53 ? 4 : 3); }
        };

        return generateItem(player, rarity, rng, type.displayName);
    }

    private InventoryItem generateItem(Player player, int rarity, Random rng, String questName) {
        ItemType type = ItemType.values()[rng.nextInt(ItemType.values().length)];

        // Itens V3: nível do item = nível do guerreiro; stats escalam com nível × raridade. [ITENS_V3]
        int itemLevel = warriorRepository.findByPlayer(player).map(Warrior::getLevel).orElse(1);
        int[] s = inventoryService.rollItemStats(itemLevel, rarity);
        int atk = s[0], def = s[1], hp = s[2];

        long sellPrice = switch (rarity) {
            case 2 -> 150; case 3 -> 400; case 4 -> 1000; case 5 -> 2500; default -> 25;
        };

        String name   = itemName(type, rarity, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest(questName);

        if (inventoryService.bagSize(player) < player.getMaxInventorySlots()) {
            return inventoryService.make(player, name, type, atk, def, hp, rarity, sellPrice, itemLevel, lore, origin);
        } else {
            mailService.sendItemMail(player, "Drop de: " + questName,
                    name, type, atk, def, hp, rarity, 0, lore, origin);
            log.info("[QuestService] player={} bag full — item '{}' sent to mail", player.getId(), name);
            return null;
        }
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
            case 4 -> new String[]{"of the Dragon", "Cursed", "of Valor"};
            case 5 -> new String[]{"of the Ancients", "Mythic", "of Eternity"}; // Lendário [ITENS_V2]
            default -> new String[]{"of Iron", "of Leather", "of Wood"};
        };
        return bases[rng.nextInt(bases.length)] + " " + suffixes[rng.nextInt(suffixes.length)];
    }
}
