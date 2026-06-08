package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.SkillType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates a short, dynamic flavour line shown when a gathering session is collected —
 * the gathering counterpart to the battle log. Purely cosmetic.
 *
 * <p>English literals for the EN launch. Each line is a stable translation unit: when the
 * backend migrates to codes (see PLANO_I18N), these become keys + a {place} param.
 */
final class GatheringNarrator {

    private GatheringNarrator() {}

    private static final String[] FISHING = {
        "You cast your line into the waters of {place} and waited as the current tugged at the bait.",
        "A shadow circled beneath the surface of {place} before striking the hook.",
        "The water broke as you hauled your catch onto the rocks, scales flashing in the light.",
        "Long patient hours by {place} finally rewarded you with a steady haul.",
        "The reel screamed as something heavy fought you all the way to the shore.",
    };

    private static final String[] MINING = {
        "You drove your pick into the black rock of {place} until a seam finally gave way.",
        "Dust and sparks filled the tunnel as the ore came loose in your hands.",
        "Each swing echoed through the shaft before the vein crumbled into your sack.",
        "The lantern flickered as you chipped the last chunk of ore free from the wall.",
        "Deep in {place}, your pick found metal where there was only stone.",
    };

    private static final String[] GARIMPO = {
        "You sifted the silt of {place} for hours until a glint caught the light.",
        "Crouched by the stream, you washed away the grit and found something hard and bright.",
        "The pan swirled again and again until a shard of crystal settled at the bottom.",
        "Among the gravel of {place}, a sliver of gemstone winked back at you.",
        "Patient panning turned a handful of mud into a fragment worth keeping.",
    };

    /** Linha de flavor da coleta, com {place}=nome do reino. [I18N] resolve no idioma do request (gather.<skill>.<i>);
     *  EN = o literal (com {place}→{0}); o nome do reino também vem localizado. Messages.tr é estático. */
    static String narrate(SkillType skill, Kingdom kingdom) {
        String prefix; String[] pool;
        switch (skill) {
            case MINING  -> { prefix = "gather.mining.";  pool = MINING;  }
            case GARIMPO -> { prefix = "gather.garimpo."; pool = GARIMPO; }
            default      -> { prefix = "gather.fishing."; pool = FISHING; } // FISHING e qualquer outra → pesca
        }
        int i = ThreadLocalRandom.current().nextInt(pool.length);
        String place = kingdom != null
                ? Messages.tr("kingdom." + kingdom.name() + ".name", kingdom.displayName)
                : Messages.tr("gather.wilds", "the wilds");
        return Messages.tr(prefix + i, pool[i].replace("{place}", "{0}"), place);
    }
}
