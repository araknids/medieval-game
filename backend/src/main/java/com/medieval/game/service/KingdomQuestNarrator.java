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

    // [ENEMY_NAMES] Inimigos temáticos por reino, em 3 tiers (comum / elite / chefe). Cada nome foi
    // conferido contra o NAME_MAP do cliente (Monsters.gd): nome com PALAVRA DE BESTA inteira
    // (serpent/golem/wraith/kraken/spider/drowned…) vira MONSTRO no replay 3D; sem palavra de besta
    // (cavaleiro/bandido/pirata/cultista/capitão) vira HUMANOIDE (mesmo rig do player). Assim o MODELO
    // sempre casa o NOME. ELITE/BOSS usam BOSS_WORDS (ancient/elder/tyrant/…) p/ o cliente aumentar o porte.
    // Mix por bioma: praia=serpente/cranguejo/pirata; mina=golem/aranha/bandido; fortaleza=cavaleiro caído/ogro;
    // grutas=aberração de cristal/cultista; mar=afogado/sereia/peregrino.
    private static final Map<Kingdom, String[]> COMMON = Map.of(
        Kingdom.FISHING,           new String[]{"Sea Serpent", "Colossal Crab", "Gulper Fish", "Coastal Brigand", "Pirate Raider", "Harbor Cutthroat"},
        Kingdom.MINING,            new String[]{"Rock Spider", "Cave Bat Swarm", "Stone Golem", "Cave Brigand", "Mad Prospector", "Tunnel Cutthroat"},
        Kingdom.COMBAT,            new String[]{"Fallen Knight", "Cursed Headsman", "Renegade Captain", "Oathbroken Guard", "War Ogre", "Battlefield Wraith"},
        Kingdom.GRUTAS_DE_CRISTAL, new String[]{"Crystal Aberration", "Gem Slime", "Glimmering Bat", "Crazed Spelunker", "Cave Cultist", "Lost Delver"},
        Kingdom.MAR_ABENCOADO,     new String[]{"Cursed Drowned", "Shadow Siren", "Abyssal Serpent", "Fallen Pilgrim", "Cursed Sailor", "Heretic Priest"}
    );
    private static final Map<Kingdom, String[]> ELITE = Map.of(
        Kingdom.FISHING,           new String[]{"Young Kraken", "Abyssal Leviathan", "Great Sea Serpent", "Dread Pirate Captain"},
        Kingdom.MINING,            new String[]{"Greater Stone Golem", "Cavern Troll", "Deepworm", "Mountain Bandit Chief"},
        Kingdom.COMBAT,            new String[]{"Risen War Ogre", "Bound Bone Lich", "Dread Knight Commander", "Cursed Standard-Bearer"},
        Kingdom.GRUTAS_DE_CRISTAL, new String[]{"Greater Crystal Aberration", "Prismatic Crawler", "Greater Gem Slime", "Geode Hermit"},
        Kingdom.MAR_ABENCOADO,     new String[]{"Pale Leviathan", "Greater Drowned Horror", "Deep Siren", "Apostate Warden"}
    );
    private static final Map<Kingdom, String[]> BOSS = Map.of(
        Kingdom.FISHING,           new String[]{"Ancient Kraken", "Leviathan of the Abyss", "Elder Sea Serpent"},
        Kingdom.MINING,            new String[]{"Elder Stone Golem", "The Tunnel Behemoth", "Deep Tyrant Worm"},
        Kingdom.COMBAT,            new String[]{"The Fortress Warlord", "Tyrant of the Cursed Keep", "Ancient Bone Lich"},
        Kingdom.GRUTAS_DE_CRISTAL, new String[]{"Ancient Crystal Aberration", "The Prismatic Tyrant", "Elder Crystal Horror"},
        Kingdom.MAR_ABENCOADO,     new String[]{"The Drowned Tyrant", "Leviathan of the Blessed Deep", "Ancient Abyssal Serpent"}
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

    /** Inimigo comum temático do reino (nome localizado p/ idioma do request → propaga ao log). [I18N][ENEMY_NAMES] */
    public String pickMonster(Kingdom kingdom, Random rng) { return pickFrom(COMMON, kingdom, rng, "Wild Beast"); }

    /** Inimigo ELITE do reino (zona de alto risco, nó ELITE da Incursão). [ENEMY_NAMES] */
    public String pickElite(Kingdom kingdom, Random rng) { return pickFrom(ELITE, kingdom, rng, "Dire Beast"); }

    /** CHEFE do reino — nome à altura (nó BOSS da Incursão). [ENEMY_NAMES] */
    public String pickBoss(Kingdom kingdom, Random rng) { return pickFrom(BOSS, kingdom, rng, "Ancient Horror"); }

    /** Encontro temático de zona: tier de alto risco puxa os ELITE; senão os comuns. [ENEMY_NAMES] */
    public String pickZoneEnemy(Kingdom kingdom, boolean highRisk, Random rng) {
        return highRisk ? pickElite(kingdom, rng) : pickMonster(kingdom, rng);
    }

    // Sorteia + localiza; null-safe p/ reino nulo (Map.of.get(null) lançaria NPE).
    private String pickFrom(Map<Kingdom, String[]> pools, Kingdom kingdom, Random rng, String fallback) {
        String[] pool = (kingdom != null) ? pools.getOrDefault(kingdom, new String[]{fallback}) : new String[]{fallback};
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
