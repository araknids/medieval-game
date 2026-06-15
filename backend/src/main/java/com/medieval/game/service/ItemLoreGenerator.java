package com.medieval.game.service;

import com.medieval.game.enums.ItemType;
import org.springframework.stereotype.Component;

import java.util.Random;

@Component
public class ItemLoreGenerator {

    // ── Lore by rarity × type ──

    private static final String[][] COMMON_LORE = {
        // WEAPON
        {"A functional weapon with nothing special about it. Gets the job done in everyday battles.",
         "Hastily forged by a village blacksmith. Not pretty, but it cuts.",
         "Worn from use, but still sharp enough for battle."},
        // ARMOR (everything else)
        {"Basic protection — simple but functional. Has seen better days.",
         "Sewn by skilled hands but with modest materials.",
         "Sturdy enough to survive a new adventurer's first battles."},
    };

    private static final String[][] UNCOMMON_LORE = {
        // WEAPON
        {"Forged by a skilled craftsman who learned the trade from a master. Has seen many battles.",
         "Has the perfect balance between offense and durability. Not the work of an amateur.",
         "Said to have belonged to a guard soldier who lost it in a skirmish on the roads."},
        // ARMOR
        {"Built with care and superior quality materials. Offers real protection.",
         "Equipment worthy of a veteran soldier. Has survived many battles.",
         "Crafted with refined technique. Whoever wore it before knew what they were doing."},
    };

    private static final String[][] RARE_LORE = {
        // WEAPON
        {"They say this weapon belonged to a legendary warrior who disappeared mysteriously in the Northern Lands.",
         "It emits a faint glow in moonlight. Forged with rare metals from a now-abandoned mine.",
         "Ancient engravings decorate its blade. No one knows exactly what they mean."},
        // ARMOR
        {"Made with ancient techniques nearly forgotten. It emanates an aura of restrained power.",
         "The battle marks on it are countless. It survived where others perished.",
         "Created by an armorer of legendary reputation. Worth far more than it appears."},
    };

    private static final String[][] EPIC_LORE = {
        // WEAPON
        {"An artifact of immeasurable power. They say it was bathed in the blood of an ancient dragon.",
         "The name of this weapon echoes through the centuries. Warriors fell to their knees just seeing it.",
         "Created in a forge forgotten by the gods, it holds within it a fragment of epic battles."},
        // ARMOR
        {"Created by the gods' own forgers, it carries the blessing of the ancient warriors.",
         "Only the most worthy are considered deserving of wearing it.",
         "No living blacksmith can reproduce this work. It is a relic of another age."},
    };

    // ── Origin strings ──

    // [I18N_ITENS] Lore + origem traduzem pelo locale do request (chaves itemlore.*/itemorigin.* em
    // messages_pt.properties; EN = o default no código). Sem request (testes) → EN.
    public String generateLore(int rarity, ItemType type, Random rng) {
        String poolName;
        String[][] pool;
        switch (rarity) {
            case 4 -> { pool = EPIC_LORE;     poolName = "epic"; }
            case 3 -> { pool = RARE_LORE;     poolName = "rare"; }
            case 2 -> { pool = UNCOMMON_LORE; poolName = "uncommon"; }
            default -> { pool = COMMON_LORE;  poolName = "common"; }
        }
        int idx = (type == ItemType.WEAPON) ? 0 : 1;
        String cat = (idx == 0) ? "weapon" : "armor";
        String[] texts = pool[idx];
        int i = rng.nextInt(texts.length);
        return Messages.tr("itemlore." + poolName + "." + cat + "." + i, texts[i]);
    }

    public String originFromQuest(String questDisplayName) {
        return Messages.tr("itemorigin.quest", "Found during: {0}.", questDisplayName);
    }

    public String originFromShop(String merchantName) {
        return Messages.tr("itemorigin.shop", "Acquired at {0}''s Trading Post.", merchantName);
    }

    public String originFromZone(String zoneName) {
        return Messages.tr("itemorigin.zone", "Found while exploring {0}.", zoneName);
    }

    public String originFromSmithing() {
        return Messages.tr("itemorigin.smithing", "Forged by the warrior at the Temple's anvil.");
    }

    public String originStarter() {
        return Messages.tr("itemorigin.starter", "Starter equipment provided by the adventurers' guild.");
    }

    public String originDrop(String bossOrEnemyName) {
        return Messages.tr("itemorigin.drop", "Obtained after defeating {0}.", bossOrEnemyName);
    }
}
