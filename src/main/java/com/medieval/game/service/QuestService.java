package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import com.medieval.game.enums.QuestStatus;
import com.medieval.game.enums.QuestType;
import com.medieval.game.model.*;
import com.medieval.game.repository.ActiveQuestRepository;
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
    private final PlayerService         playerService;
    private final WarriorService        warriorService;
    private final InventoryService      inventoryService;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    public record CollectResult(ActiveQuest quest, InventoryItem droppedItem) {}

    @Transactional
    public ActiveQuest sendOnQuest(Player player, QuestType questType) {
        Warrior warrior = warriorService.getWarrior(player);
        if (warrior.isOnMission()) {
            throw new IllegalStateException("Este guerreiro já está em missão");
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
        quest.setGoldReward(questType.goldReward);
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
                .orElseThrow(() -> new IllegalArgumentException("Missão não encontrada"));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Esta missão não é sua");
        }
        if (quest.getStatus() != QuestStatus.IN_PROGRESS) {
            throw new IllegalStateException("Missão não pode ser abandonada");
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
                .orElseThrow(() -> new IllegalArgumentException("Missão não encontrada: " + questId));

        if (!quest.getPlayer().getId().equals(player.getId())) {
            throw new IllegalStateException("Esta missão não é sua");
        }
        if (quest.getStatus() == QuestStatus.COLLECTED) {
            throw new IllegalStateException("Recompensa já coletada");
        }
        if (!quest.isReadyToCollect()) {
            long secsLeft = java.time.Duration.between(LocalDateTime.now(), quest.getCompletesAt()).getSeconds();
            throw new IllegalStateException("Missão ainda não concluída. Faltam " + secsLeft + "s");
        }

        playerService.addGold(player, quest.getGoldReward());
        warriorService.addExperience(quest.getWarrior(), quest.getExpReward());

        // Carrega o guerreiro diretamente pelo player para garantir instância gerenciada
        warriorRepository.findByPlayer(player).ifPresent(w -> {
            w.setOnMission(false);
            warriorRepository.save(w);
        });

        quest.setStatus(QuestStatus.COLLECTED);
        questRepository.save(quest);

        InventoryItem drop = rollDrop(player, quest.getQuestType());
        return new CollectResult(quest, drop);
    }

    // ── Drop system ──

    private InventoryItem rollDrop(Player player, QuestType type) {
        Random rng = new Random();
        Warrior warrior = warriorRepository.findByPlayer(player).orElse(null);
        int luckBonus = warrior != null ? warrior.getLuck() : 0;

        int dropChance = switch (type) {
            case PATROL    -> 10;
            case DUNGEON   -> 25;
            case RAID      -> 40;
            case BOSS_HUNT -> 60;
        } + luckBonus;

        if (rng.nextInt(100) >= dropChance) return null;

        int rarity = switch (type) {
            case PATROL    -> 1;
            case DUNGEON   -> rng.nextBoolean() ? 1 : 2;
            case RAID      -> rng.nextBoolean() ? 2 : 3;
            case BOSS_HUNT -> rng.nextBoolean() ? 3 : 4;
        };

        return generateItem(player, rarity, rng);
    }

    private InventoryItem generateItem(Player player, int rarity, Random rng) {
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

        return inventoryService.make(player, itemName(type, rarity, rng), type,
                atk, def, hp, rarity, sellPrice);
    }

    private String itemName(ItemType type, int rarity, Random rng) {
        String[] bases = switch (type) {
            case HELMET   -> new String[]{"Elmo", "Capacete"};
            case ARMOR    -> new String[]{"Armadura", "Couraça"};
            case WEAPON   -> new String[]{"Espada", "Lâmina"};
            case SHIELD   -> new String[]{"Escudo", "Broquel"};
            case BOOTS    -> new String[]{"Botas", "Grevas"};
            case GLOVES   -> new String[]{"Luvas", "Manoplas"};
            case PANTS    -> new String[]{"Calça", "Perneiras"};
            case SHOULDER -> new String[]{"Ombreira", "Espaldeiras"};
            case NECKLACE -> new String[]{"Colar", "Amuleto"};
            case RING     -> new String[]{"Anel", "Sigilo"};
        };
        String[] suffixes = switch (rarity) {
            case 2 -> new String[]{"de Aço", "de Malha", "de Prata"};
            case 3 -> new String[]{"Élfico", "do Guerreiro", "Encantado"};
            case 4 -> new String[]{"Lendário", "do Dragão", "Amaldiçoado"};
            default -> new String[]{"de Ferro", "de Couro", "de Madeira"};
        };
        return bases[rng.nextInt(bases.length)] + " " + suffixes[rng.nextInt(suffixes.length)];
    }
}
