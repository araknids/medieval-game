package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;

/**
 * Gera a narrativa curta (em inglês) exibida ao coletar uma quest de reino, e
 * sorteia o monstro temático do reino quando há combate. Sem estado, testável. [Quests V2]
 *
 * Três desfechos: PEACE (sem monstro), VICTORY (monstro derrotado), DEFEAT (monstro venceu).
 */
@Component
public class KingdomQuestNarrator {

    // Monstros comuns por reino (chefes ficam na Torre).
    private static final Map<Kingdom, String[]> MONSTERS = Map.of(
        Kingdom.FISHING,           new String[]{"Sea Serpent", "Colossal Crab", "Drowned Pirate", "Young Kraken"},
        Kingdom.MINING,            new String[]{"Stone Golem", "Deepworm", "Rock Spider", "Mine Wraith"},
        Kingdom.COMBAT,            new String[]{"Fallen Knight", "War Ogre", "Cursed Headsman", "Renegade Captain"},
        Kingdom.GRUTAS_DE_CRISTAL, new String[]{"Crystal Aberration", "Prismatic Golem", "Gem Warden", "Glimmering Bat"},
        Kingdom.MAR_ABENCOADO,     new String[]{"Cursed Drowned", "Shadow Siren", "Tide Servant", "Pale Leviathan"}
    );

    private static final String[] PEACE = {
        "You completed '%s' in the %s without incident and returned with the job done.",
        "'%s' went smoothly — no trouble found along the way through the %s.",
        "The %2$s was quiet today; you finished '%1$s' and headed back unscathed."
    };

    private static final String[] VICTORY = {
        "Midway through '%1$s', a %3$s attacked — after a hard fight, you cut it down and claimed your reward.",
        "A %3$s barred your path during '%1$s', but you bested it in combat and pressed on to finish the job.",
        "'%1$s' nearly went wrong when a %3$s struck, yet you stood your ground in the %2$s and won."
    };

    private static final String[] DEFEAT = {
        "A %3$s ambushed you during '%1$s' and forced you to retreat, wounded. No reward this time.",
        "'%1$s' ended in disaster — a %3$s overpowered you. You barely escaped the %2$s, empty-handed.",
        "The %3$s proved too strong during '%1$s'. You fled with your life but nothing else."
    };

    /** Sorteia um monstro temático do reino. */
    public String pickMonster(Kingdom kingdom, Random rng) {
        String[] pool = MONSTERS.getOrDefault(kingdom, new String[]{"Wild Beast"});
        return pool[rng.nextInt(pool.length)];
    }

    /**
     * Monta a narrativa do desfecho.
     * @param encountered houve monstro?
     * @param defeated    o guerreiro venceu? (só relevante se encountered)
     * @param monster     nome do monstro (só relevante se encountered)
     */
    public String narrate(KingdomQuestType quest, boolean encountered, boolean defeated, String monster, Random rng) {
        String questName = quest.displayName;
        String realm     = quest.kingdom.displayName;
        if (!encountered) {
            return String.format(PEACE[rng.nextInt(PEACE.length)], questName, realm);
        }
        String[] pool = defeated ? VICTORY : DEFEAT;
        return String.format(pool[rng.nextInt(pool.length)], questName, realm, monster);
    }
}
