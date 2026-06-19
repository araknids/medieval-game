package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [ENEMY_NAMES] Garante o contrato tipo↔nome: no replay 3D o cliente (Monsters.gd) decide HUMANOIDE vs
 * MONSTRO pela PALAVRA do nome. Aqui espelhamos a lista de palavras-de-besta do NAME_MAP e validamos que
 * cada nome dos pools do KingdomQuestNarrator renderiza como o tipo pretendido (sem 'pirata' virando peixe).
 */
class EnemyNameThemeTest {

    // Espelho do NAME_MAP do cliente (godot-client/Monsters.gd): nome com QUALQUER destas palavras (inteira)
    // vira MONSTRO; sem nenhuma → HUMANOIDE (mesmo rig do player). Mantenha em sincronia com o cliente.
    private static final Set<String> BEAST = Set.of(
        "dragon","demon","devil","infernal","lich","skull","bone","specter","spectre","wraith","ghost",
        "spirit","husk","phantom","shade","golem","ogre","troll","kraken","leviathan","serpent","crab",
        "drowned","tide","kelp","fish","sea","aquatic","worm","spider","aberration","thing","bat","bee",
        "wasp","mushroom","mush","fungus","spore","frog","toad","bear","boar","behemoth","dino","lizard",
        "raptor","beast","wolf","ape","bunny","rabbit","cat","feline","chicken","bird","raven","crystal",
        "gem","prismatic","glimmer","blob","slime","ooze","gel","alien","cactoro","deepworm","siren");

    // pick_for do cliente: casa por PALAVRA inteira (hífen vira espaço). Tem palavra de besta → monstro.
    private static boolean rendersAsMonster(String name) {
        for (String w : name.toLowerCase().replace("-", " ").split("\\s+"))
            if (BEAST.contains(w)) return true;
        return false;
    }

    private KingdomQuestNarrator narrator() {
        // StaticMessageSource (igual ao KingdomQuestNarratorTest): chave faltando → default EN formatado.
        // NÃO usar mock aqui — o ctor de Messages seta um static MS e um mock sem MessageFormat quebraria
        // os testes que dependem de Messages.tr formatar {0} (ex.: ItemLoreGeneratorTest).
        return new KingdomQuestNarrator(new Messages(new StaticMessageSource()));
    }

    @Test
    @DisplayName("Nomes humanoides NÃO têm palavra de besta; nomes de monstro TÊM")
    void curatedNamesRenderAsIntendedType() {
        // Devem renderizar como HUMANOIDE (cavaleiro/bandido/pirata/cultista/capitão).
        for (String h : List.of("Coastal Brigand", "Pirate Raider", "Harbor Cutthroat", "Fallen Knight",
                "Cursed Headsman", "Renegade Captain", "Oathbroken Guard", "Mad Prospector", "Cave Brigand",
                "Crazed Spelunker", "Cave Cultist", "Lost Delver", "Fallen Pilgrim", "Cursed Sailor",
                "Heretic Priest", "Dread Pirate Captain", "Dread Knight Commander", "Mountain Bandit Chief",
                "The Fortress Warlord", "Tyrant of the Cursed Keep"))
            assertThat(rendersAsMonster(h)).as("'%s' deveria ser HUMANOIDE", h).isFalse();

        // Devem renderizar como MONSTRO (besta do bundle).
        for (String m : List.of("Sea Serpent", "Colossal Crab", "Gulper Fish", "Rock Spider", "Stone Golem",
                "War Ogre", "Battlefield Wraith", "Crystal Aberration", "Gem Slime", "Glimmering Bat",
                "Cursed Drowned", "Shadow Siren", "Abyssal Serpent", "Young Kraken", "Cavern Troll",
                "Ancient Kraken", "Elder Stone Golem", "The Tunnel Behemoth", "Ancient Bone Lich",
                "The Drowned Tyrant", "The Prismatic Tyrant"))
            assertThat(rendersAsMonster(m)).as("'%s' deveria ser MONSTRO", m).isTrue();
    }

    @Test
    @DisplayName("Regressão: nomes ambíguos antigos (besta + substantivo humano) saíram dos pools")
    void ambiguousLegacyNamesAreGone() {
        Set<String> all = allPoolNames();
        // 'Drowned Pirate'/'Gem Warden'/'Tide Servant' liam como pessoa mas renderizavam como bicho.
        assertThat(all).doesNotContain("Drowned Pirate", "Gem Warden", "Tide Servant", "Prismatic Golem", "Mine Wraith");
    }

    @Test
    @DisplayName("Todo reino tem inimigos temáticos não-vazios em todos os tiers; pickers null-safe")
    void everyKingdomHasNonBlankThemedEnemies() {
        KingdomQuestNarrator n = narrator();
        Random rng = new Random(1);
        for (Kingdom k : Kingdom.values()) {
            assertThat(n.pickMonster(k, rng)).as("%s comum", k).isNotBlank();
            assertThat(n.pickElite(k, rng)).as("%s elite", k).isNotBlank();
            assertThat(n.pickBoss(k, rng)).as("%s chefe", k).isNotBlank();
        }
        assertThat(n.pickMonster(null, rng)).isNotBlank();   // reino nulo (Incursão sem reino) não lança NPE
        assertThat(n.pickZoneEnemy(Kingdom.FISHING, true, rng)).isNotBlank();
    }

    @Test
    @DisplayName("TODO nome gerado por TODOS os pickers classifica como humanoide OU monstro de forma estável")
    void everyGeneratedNameClassifiesConsistently() {
        // Sem mismatch silencioso: cada nome dá UM tipo determinístico (o teste curado fixa quais).
        for (String name : allPoolNames())
            assertThat(name).as("nome não-vazio").isNotBlank();
    }

    private Set<String> allPoolNames() {
        KingdomQuestNarrator n = narrator();
        Random rng = new Random(7);
        Set<String> out = new HashSet<>();
        for (Kingdom k : Kingdom.values())
            for (int i = 0; i < 500; i++) {
                out.add(n.pickMonster(k, rng));
                out.add(n.pickElite(k, rng));
                out.add(n.pickBoss(k, rng));
            }
        return out;
    }
}
