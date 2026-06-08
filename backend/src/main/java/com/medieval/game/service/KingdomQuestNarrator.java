package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Random;

/**
 * Gera a narrativa curta exibida ao coletar uma quest de reino, e sorteia o monstro temático do reino
 * quando há combate. [Quests V2] [I18N] template + nome de quest/reino/monstro resolvidos no idioma do
 * request (EN = a prosa daqui, default do getOr); o nome do monstro localizado propaga pro battle log.
 *
 * Três desfechos: PEACE (sem monstro), VICTORY (monstro derrotado), DEFEAT (monstro venceu).
 */
@Component
@RequiredArgsConstructor
public class KingdomQuestNarrator {

    private final Messages messages; // [I18N]

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

    /** Sorteia um monstro temático do reino (nome localizado p/ idioma do request → propaga ao log). [I18N] */
    public String pickMonster(Kingdom kingdom, Random rng) {
        String[] pool = MONSTERS.getOrDefault(kingdom, new String[]{"Wild Beast"});
        String en = pool[rng.nextInt(pool.length)];
        return messages.getOr("monster." + en.replace(' ', '_'), en);
    }

    /**
     * Monta a narrativa do desfecho.
     * @param encountered houve monstro?
     * @param defeated    o guerreiro venceu? (só relevante se encountered)
     * @param monster     nome do monstro (só relevante se encountered)
     */
    public String narrate(KingdomQuestType quest, boolean encountered, boolean defeated, String monster, Random rng) {
        // [I18N] nome da quest/reino localizados; o template vem do idioma do request (EN = a prosa daqui).
        String questName = messages.getOr("quest." + quest.name() + ".name", quest.displayName);
        String realm     = messages.getOr("kingdom." + quest.kingdom.name() + ".name", quest.kingdom.displayName);
        if (!encountered) {
            int i = rng.nextInt(PEACE.length);
            return String.format(messages.getOr("narrator.peace." + i, PEACE[i]), questName, realm);
        }
        String[] pool = defeated ? VICTORY : DEFEAT;
        int i = rng.nextInt(pool.length);
        String key = (defeated ? "narrator.victory." : "narrator.defeat.") + i;
        return String.format(messages.getOr(key, pool[i]), questName, realm, monster); // monster já localizado
    }
}
