package com.medieval.game.quest;

import com.medieval.game.enums.Attribute;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.quest.QuestDialog.QuestOption;
import com.medieval.game.quest.QuestOutcome.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Conteúdo das quests interativas (decisão única): história + opções por {@link KingdomQuestType}.
 * Texto voltado ao jogador em INGLÊS (i18n pro PT depois). Recompensas = multiplicadores da reward
 * base da quest. Quest sem entrada aqui = não-interativa (encontro aleatório). Ver docs/PLANO_QUESTS_INTERATIVAS.md.
 */
public final class InteractiveQuests {

    private InteractiveQuests() {}

    // DCs do roll d20 (1d20 + floor(attr/4) vs DC; nat 1 falha / nat 20 passa)
    private static final int EASY = 10, MEDIUM = 14, HARD = 18, EPIC = 22;

    private static final Map<KingdomQuestType, QuestDialog> DIALOGS = new EnumMap<>(KingdomQuestType.class);

    public static Optional<QuestDialog> dialogFor(KingdomQuestType qt) {
        return Optional.ofNullable(DIALOGS.get(qt));
    }

    public static boolean isInteractive(KingdomQuestType qt) {
        return DIALOGS.containsKey(qt);
    }

    // ── Helpers de autoria ──────────────────────────────────────────────────────
    private static QuestOutcome peace(double b, double x, int drop, String n) { return new Peaceful(b, x, drop, n); }
    private static QuestOutcome fight(double b, double x, int drop, String win, String lose) { return new Fight(b, x, drop, win, lose); }
    private static QuestOutcome check(Attribute a, int dc, QuestOutcome ok, QuestOutcome fail) { return new Check(a, dc, ok, fail); }
    private static QuestOption opt(String id, String label, String hint, QuestOutcome o) { return new QuestOption(id, label, hint, o); }
    private static void put(KingdomQuestType qt, String intro, QuestOption... opts) { DIALOGS.put(qt, new QuestDialog(intro, List.of(opts))); }

    static {
        // ── Quest RARA da Luna (cachorra). O collect é special-cased no KingdomService (chance de pet,
        //    sem loot); os outcomes abaixo são placeholders e NÃO são resolvidos. [PETS] ──
        put(KingdomQuestType.RESCUE_STRAY_DOG,
            "A trembling stray dog lies in the mud, sick and whimpering. She looks up at you with tired, hopeful eyes.",
            opt("help",  "Help the dog", "no reward — but maybe a loyal friend", peace(0, 0, 0, "You tend to her.")),
            opt("leave", "Walk away",    "",                                      peace(0, 0, 0, "You leave her be.")));

        // ── Fishing — Bone Gorge ────────────────────────────────────────────────
        put(KingdomQuestType.PATROL_COAST,
            "Smugglers are unloading crates on the misty shore.",
            opt("hail",  "Hail them openly",       "", peace(0.8, 0.8, 8,  "They slip you a bribe to look the other way. Easy coin.")),
            opt("ambush","Ambush the smugglers",   "", fight(1.8, 1.6, 30, "You break their ring and seize the haul.", "They were ready for you — you limp away empty-handed.")),
            opt("tally", "Slip in and tally cargo", "DEX " + EASY, check(Attribute.DEXTERITY, EASY,
                         peace(1.5, 1.4, 20, "You count every crate unseen and sell the intel."),
                         fight(1.4, 1.3, 20, "A plank creaks — they spot you and draw steel.", "Caught flat-footed, you barely escape."))));

        put(KingdomQuestType.EXPLORE_REEFS,
            "Strange lights pulse beneath the reef.",
            opt("mark", "Mark the spot and leave", "", peace(0.8, 0.8, 8,  "You chart the lights and report back safely.")),
            opt("dive", "Dive into the dark water", "", fight(1.8, 1.6, 30, "A lurking eel strikes — and loses.", "The deep nearly keeps you for good.")),
            opt("read", "Read the currents",        "INT " + EASY, check(Attribute.INTELLECT, EASY,
                        peace(1.5, 1.4, 20, "You trace the safe channels and recover sunken treasure."),
                        fight(1.4, 1.3, 20, "A riptide drags you into the eel's lair.", "The reef takes its toll."))));

        put(KingdomQuestType.SALVAGE_THE_WRECK,
            "A half-sunk galleon groans on the rocks, its hold still full.",
            opt("float", "Take what floats free", "", peace(0.8, 0.8, 8,  "You gather the drifting cargo without fuss.")),
            opt("pry",   "Pry open the hold",     "", fight(1.8, 1.6, 35, "Trapped scavengers lunge — you cut them down.", "The hold floods around you as you flee.")),
            opt("brace", "Brace the hull, search", "STR " + MEDIUM, check(Attribute.STRENGTH, MEDIUM,
                         peace(1.6, 1.4, 25, "You hold the buckling hull and strip it clean."),
                         fight(1.4, 1.3, 20, "The hull gives — and so do the scavengers inside.", "The wreck shifts and pins you; you escape with nothing."))));

        put(KingdomQuestType.CLEAR_PIRATE_COVE,
            "The cove reeks of rum and gunpowder — pirates, and plenty of them.",
            opt("toll",  "Negotiate a toll",       "", peace(0.8, 0.8, 8,  "Coin changes hands and they let you pass.")),
            opt("storm", "Storm the cove",          "", fight(1.9, 1.7, 35, "You scatter the crew and claim the cove.", "Outnumbered, you're driven back to the surf.")),
            opt("lines", "Cut their mooring lines", "DEX " + MEDIUM, check(Attribute.DEXTERITY, MEDIUM,
                         fight(2.2, 1.9, 40, "With ships adrift, the panicked pirates are easy prey.", "They rally despite the chaos and overwhelm you."),
                         fight(1.5, 1.4, 25, "A lookout spots you mid-cut — the fight is on.", "Caught at the ropes, you're beaten back."))));

        put(KingdomQuestType.DEEP_SEA_RAID,
            "A reaver fleet anchors off the gorge, raiding at dawn.",
            opt("hold",   "Raise the alarm and hold", "", fight(1.8, 1.6, 35, "You hold the line until the reavers break.", "The line buckles and you fall back, wounded.")),
            opt("sink",   "Sink the flagship by night", "DEX " + HARD, check(Attribute.DEXTERITY, HARD,
                          peace(2.2, 1.9, 30, "You scuttle the flagship; the fleet flees in disarray."),
                          fight(1.5, 1.4, 25, "An alarm bell rings — the deck swarms with reavers.", "You're hauled from the water and barely live."))));

        put(KingdomQuestType.HUNT_SEA_MONSTER,
            "The leviathan surfaces, jaws wide enough to swallow a longboat.",
            opt("face", "Face it head-on",        "", fight(1.6, 1.5, 40, "You drive your blade through its eye. It sinks.", "Its jaws close — you're spat out broken on the rocks.")),
            opt("lure", "Lure it onto the shoals", "INT " + HARD, check(Attribute.INTELLECT, HARD,
                        fight(2.2, 2.0, 45, "Beached and thrashing, the leviathan falls quickly.", "It thrashes free and turns on you with full fury."),
                        fight(1.4, 1.3, 35, "It refuses the bait and meets you in deep water.", "The deep swallows your gambit — and nearly you."))));

        // ── Mining — Black Iron Mines ───────────────────────────────────────────
        put(KingdomQuestType.ESCORT_MINERS,
            "Miners refuse to enter — they swear something moved in the dark.",
            opt("walk",  "Walk them in, torches high", "", peace(0.8, 0.8, 8,  "Light enough, and the dark stays empty. Job done.")),
            opt("hunt",  "Hunt whatever's down there", "", fight(1.8, 1.6, 30, "You corner the thing and end it.", "It ambushes from a side-shaft and you're forced out.")),
            opt("listen","Listen to the rock",         "INT " + EASY, check(Attribute.INTELLECT, EASY,
                         peace(1.5, 1.4, 20, "You hear it move and lead the miners safely around."),
                         fight(1.4, 1.3, 20, "You read it wrong — it drops on you from above.", "The dark gets the better of you."))));

        put(KingdomQuestType.CLEAR_CAVES,
            "Cave-ins sealed a tunnel — and something is digging back out.",
            opt("shore", "Shore it up and retreat", "", peace(0.8, 0.8, 8,  "You seal the breach and pull everyone back.")),
            opt("dig",   "Dig through and meet it", "", fight(1.8, 1.6, 30, "You break through and put the digger down.", "It bursts out first and routs you.")),
            opt("seam",  "Find the weak seam",      "STR " + EASY, check(Attribute.STRENGTH, EASY,
                         peace(1.6, 1.4, 20, "You crack the seam and collapse the thing's tunnel on it."),
                         fight(1.4, 1.3, 20, "The rock won't give — and the digger reaches you.", "Buried debris pins you as it closes in."))));

        put(KingdomQuestType.SHORE_UP_TUNNELS,
            "Support beams are splintering; the whole level could drop.",
            opt("brace", "Brace what you can",       "", peace(0.9, 0.9, 8,  "You wedge the beams and buy the level another season.")),
            opt("clear", "Clear the nesting beasts", "", fight(1.8, 1.6, 30, "You clear the cracks of crawling things.", "The nest swarms and drives you out.")),
            opt("heave", "Heave a pillar into place","STR " + MEDIUM, check(Attribute.STRENGTH, MEDIUM,
                         peace(1.6, 1.4, 25, "You set the fallen pillar back and steady the whole gallery."),
                         fight(1.4, 1.3, 20, "The pillar slips — and stirs whatever nests below.", "It pins your leg; you barely crawl free."))));

        put(KingdomQuestType.RETRIEVE_LOST_ORE,
            "A cart of black iron vanished down a side shaft.",
            opt("track", "Track the cart's trail", "DEX " + EASY, check(Attribute.DEXTERITY, EASY,
                         peace(1.5, 1.4, 20, "You follow the ruts to a stash and haul it back."),
                         fight(1.4, 1.3, 20, "The trail leads straight into a gremlin warren.", "You lose the trail — and your footing."))),
            opt("fight", "Fight the gremlin hoarders", "", fight(1.8, 1.6, 30, "You rout the gremlins and reclaim the ore.", "The little wretches overwhelm you in the dark.")));

        put(KingdomQuestType.PURGE_INFESTATION,
            "The lower mines crawl with chittering things.",
            opt("smoke",    "Smoke them out",        "", peace(0.9, 0.9, 8,  "Smoke fills the shafts; the swarm flees the mine.")),
            opt("wade",     "Wade in, blade first",  "", fight(1.9, 1.7, 35, "You carve a path through the swarm.", "The tide of vermin drags you down.")),
            opt("collapse", "Collapse the nest tunnel","STR " + HARD, check(Attribute.STRENGTH, HARD,
                            peace(2.0, 1.8, 30, "One mighty blow drops the ceiling on the whole nest."),
                            fight(1.5, 1.4, 25, "The charge fails and the nest pours out at you.", "The collapse nearly buries you with them."))));

        put(KingdomQuestType.DEFEAT_CAVE_BEAST,
            "A hulking thing of stone and tusk blocks the deepest vein.",
            opt("strike", "Strike it down",        "", fight(1.6, 1.5, 40, "You bring the beast crashing down.", "Its tusk catches you and the dark goes quiet.")),
            opt("plates", "Spot the cracked plates","INT " + HARD, check(Attribute.INTELLECT, HARD,
                          fight(2.2, 2.0, 45, "You strike the flaw and shatter it apart.", "The flaw was a feint; it gores you in return."),
                          fight(1.4, 1.3, 35, "You misjudge its hide and trade blows the hard way.", "It tramples your plan and you with it."))));

        // ── Combat — Cursed Fortress ────────────────────────────────────────────
        put(KingdomQuestType.DEFEND_WALLS,
            "Raiders mass at the gate as the horn sounds.",
            opt("hold",   "Hold the wall",     "", fight(1.8, 1.6, 30, "You throw the raiders off the wall.", "The wall is breached and you're swept back.")),
            opt("parley", "Parley for time",   "LUCK " + EASY, check(Attribute.LUCK, EASY,
                          peace(1.4, 1.4, 15, "Your words buy time; reinforcements arrive and the raiders melt away."),
                          fight(1.4, 1.3, 20, "They answer your parley with a charge.", "The talk fails and the gate falls on you."))));

        put(KingdomQuestType.CLEAR_DUNGEON,
            "The dungeon below the keep still holds the old garrison's ghosts.",
            opt("sweep", "Sweep it cell by cell", "", fight(1.8, 1.6, 30, "You clear every cell of the restless dead.", "Something old corners you in the dark.")),
            opt("map",   "Map the safe route",    "INT " + MEDIUM, check(Attribute.INTELLECT, MEDIUM,
                         peace(1.5, 1.4, 20, "You chart a path around the worst of it and recover the lost cache."),
                         fight(1.4, 1.3, 20, "A wrong turn drops you among the dead.", "The maze swallows your torch and your nerve."))));

        put(KingdomQuestType.PATROL_RAMPARTS,
            "Something scaled the ramparts in the night.",
            opt("patrol", "Patrol and confront it", "", fight(1.8, 1.6, 30, "You catch the intruder on the wall and end it.", "It strikes from the shadows and routs you.")),
            opt("track",  "Track the claw marks",   "DEX " + MEDIUM, check(Attribute.DEXTERITY, MEDIUM,
                          peace(1.6, 1.4, 25, "You trail it to its roost and deal with it cleanly."),
                          fight(1.4, 1.3, 20, "The trail doubles back — it's behind you.", "You lose it in the dark, and it finds you."))));

        put(KingdomQuestType.RAID_ENCAMPMENT,
            "An enemy camp sprawls below the walls, lightly watched.",
            opt("charge", "Charge the camp",         "", fight(1.9, 1.7, 35, "You burn the camp and scatter its warband.", "They form up fast and beat you back.")),
            opt("sentry", "Slit the sentries first", "DEX " + HARD, check(Attribute.DEXTERITY, HARD,
                          fight(2.2, 1.9, 40, "With the watch dead, you fall on a sleeping camp.", "A sentry cries out — the camp wakes angry."),
                          fight(1.5, 1.4, 25, "A blade misses its mark and the alarm goes up.", "You're cut down before the first tent."))));

        put(KingdomQuestType.BREACH_THE_KEEP,
            "The inner keep is barred; the warlord's banner flies above.",
            opt("batter","Batter the gate down", "STR " + HARD, check(Attribute.STRENGTH, HARD,
                         peace(2.0, 1.8, 25, "The gate splinters and the defenders flee before you."),
                         fight(1.4, 1.3, 25, "The gate holds just long enough for them to sally out.", "The ram shatters and you with it."))),
            opt("court", "Fight through the courtyard", "", fight(1.7, 1.5, 35, "You cut through the courtyard to the keep.", "The courtyard becomes your trap.")));

        put(KingdomQuestType.HUNT_WARLORD,
            "The warlord stands atop the keep, blade already drawn.",
            opt("duel", "Duel the warlord",          "", fight(1.6, 1.5, 40, "Steel rings, and the warlord falls.", "The warlord's blade finds you first.")),
            opt("goad", "Goad him into a charge",    "LUCK " + HARD, check(Attribute.LUCK, HARD,
                        fight(2.2, 2.0, 45, "He charges blind with rage — and onto your blade.", "He sees the trap and makes you pay for it."),
                        fight(1.4, 1.3, 35, "He laughs off your taunts and comes on guard.", "Your goading only sharpens his fury."))));

        // ── Crystal Grottoes — Prospecting ──────────────────────────────────────
        put(KingdomQuestType.GUARD_CRYSTAL_VEINS,
            "Thieves chip away at the glowing veins by lantern-light.",
            opt("drive", "Drive them off",        "", fight(1.8, 1.6, 30, "You scatter the thieves into the dark.", "They turn their tools on you and you flee.")),
            opt("catch", "Catch them red-handed",  "DEX " + EASY, check(Attribute.DEXTERITY, EASY,
                         peace(1.5, 1.4, 20, "You corner them with their loot and reclaim it all."),
                         fight(1.4, 1.3, 20, "They hear you coming and turn to fight.", "They slip away with the haul — and your pride."))));

        put(KingdomQuestType.MAP_THE_GROTTO,
            "The grotto twists into a maze of mirrored crystal.",
            opt("chart", "Chart it carefully",         "INT " + MEDIUM, check(Attribute.INTELLECT, MEDIUM,
                         peace(1.6, 1.4, 25, "You map every mirrored turn and find an untouched vein."),
                         fight(1.4, 1.3, 20, "The mirrors fool you into a nest of crawlers.", "You lose yourself in the reflections."))),
            opt("push",  "Push through what blocks you", "", fight(1.8, 1.6, 30, "You smash past whatever the grotto throws at you.", "The maze and its denizens get the better of you.")));

        put(KingdomQuestType.EXTRACT_GEODES,
            "Massive geodes line a ledge over a black drop.",
            opt("slow", "Work the ledge slowly", "", peace(0.9, 0.9, 8,  "Patient work fills your pack with geodes.")),
            opt("pry",  "Pry the biggest free",  "STR " + MEDIUM, check(Attribute.STRENGTH, MEDIUM,
                        peace(1.6, 1.4, 25, "You wrench the great geode loose — a fortune in crystal."),
                        fight(1.4, 1.3, 20, "The ledge cracks and wakes something below.", "You nearly follow the geode into the dark."))));

        put(KingdomQuestType.SEAL_THE_FISSURE,
            "A fissure leaks a cold, humming light — and shapes move within.",
            opt("wall",  "Wall it off",            "", peace(0.9, 0.9, 8,  "You seal the fissure with stone and mortar.")),
            opt("cull",  "Cull what crawls out",   "", fight(1.8, 1.6, 30, "You put down everything that crawls free.", "More come than you can handle.")),
            opt("seal",  "Read the resonance",     "INT " + HARD, check(Attribute.INTELLECT, HARD,
                         peace(2.0, 1.8, 30, "You tune the seal to the fissure's hum and close it perfectly."),
                         fight(1.5, 1.4, 25, "The resonance lashes back and the shapes pour out.", "The hum scrambles your mind; you stumble away."))));

        put(KingdomQuestType.CLEANSE_CRYSTAL_HORROR,
            "A horror of living crystal drags itself toward the surface.",
            opt("shatter","Shatter it",            "", fight(1.8, 1.6, 35, "You break the horror into glittering shards.", "Its shards cut you to ribbons.")),
            opt("core",   "Find its fractured core","INT " + HARD, check(Attribute.INTELLECT, HARD,
                          fight(2.2, 2.0, 45, "You strike the core and it collapses in on itself.", "The core was a decoy — it engulfs you."),
                          fight(1.4, 1.3, 35, "You can't find the flaw and must break it the hard way.", "It reforms faster than you can shatter it."))));

        put(KingdomQuestType.SLAY_CRYSTAL_BEAST,
            "The crystal beast wakes, its facets blazing.",
            opt("slay",  "Slay it",                 "", fight(1.6, 1.5, 40, "You shatter the beast facet by facet.", "Its blaze blinds you and its claws do the rest.")),
            opt("blind", "Blind it with its light", "LUCK " + EPIC, check(Attribute.LUCK, EPIC,
                         fight(2.3, 2.1, 45, "You catch its glare in a shard and turn it blind — then strike.", "The light scatters wrong and sears your eyes instead."),
                         fight(1.4, 1.3, 35, "The trick fails and you face it head-on.", "Dazzled by your own gambit, you fall."))));

        // ── Blessed Sea — sacred waters ─────────────────────────────────────────
        put(KingdomQuestType.CLEANSE_THE_TIDES,
            "A foulness taints the sacred shallows.",
            opt("rite",  "Perform the rite",        "INT " + EASY, check(Attribute.INTELLECT, EASY,
                         peace(1.5, 1.4, 20, "Your rite purges the taint and the waters run clear."),
                         fight(1.4, 1.3, 20, "The rite falters and the foulness takes shape.", "The taint clings to you and drags you under."))),
            opt("drive", "Drive out the befouler",  "", fight(1.8, 1.6, 30, "You drive the foul thing from the shallows.", "It pulls you into the murk.")));

        put(KingdomQuestType.BLESS_THE_SHALLOWS,
            "Pilgrims wait for the waters to be blessed, but something lurks offshore.",
            opt("bless", "Bless the waters in peace", "", peace(0.9, 0.9, 8,  "The blessing holds and the pilgrims rejoice.")),
            opt("clear", "Clear the lurker first",    "", fight(1.8, 1.6, 30, "You drive off the lurker, then bless the calm.", "The lurker drags you down before the rite.")),
            opt("calm",  "Calm them, read the tide",  "LUCK " + MEDIUM, check(Attribute.LUCK, MEDIUM,
                         peace(1.6, 1.4, 25, "You time the rite to a kind tide; the pilgrims bless your name."),
                         fight(1.4, 1.3, 20, "The tide turns foul and so does the mood.", "Panic spreads and the lurker strikes."))));

        put(KingdomQuestType.ESCORT_PILGRIMS,
            "Pilgrims must cross the drowned causeway by dusk.",
            opt("guide", "Guide them across",        "DEX " + MEDIUM, check(Attribute.DEXTERITY, MEDIUM,
                         peace(1.6, 1.4, 25, "You read the safe stones and bring them all across dry."),
                         fight(1.4, 1.3, 20, "A pilgrim slips and the deep stirs.", "The causeway floods and scatters your charges."))),
            opt("guard", "Guard the rear from the deep","", fight(1.8, 1.6, 30, "You hold the rear until the last pilgrim is across.", "Something from the deep takes the stragglers — and nearly you.")));

        put(KingdomQuestType.PURIFY_THE_REEF,
            "The reef's glow has dimmed to a sickly grey.",
            opt("channel","Channel the blessing",   "INT " + MEDIUM, check(Attribute.INTELLECT, MEDIUM,
                         peace(1.6, 1.4, 25, "Light returns to the reef in a slow, golden bloom."),
                         fight(1.4, 1.3, 20, "The blessing curdles and something rises from the rot.", "The grey takes hold and you with it."))),
            opt("burn",  "Burn out the rot",         "", fight(1.8, 1.6, 30, "You cut the rot out at its writhing source.", "The rot fights back and spreads over you.")));

        put(KingdomQuestType.BANISH_THE_DROWNED,
            "The drowned rise from the sacred deep, dripping and silent.",
            opt("banish","Banish them with the rite","INT " + HARD, check(Attribute.INTELLECT, HARD,
                         peace(2.0, 1.8, 30, "Your rite unmakes them; they sink back to rest."),
                         fight(1.5, 1.4, 25, "The rite breaks and the drowned close in.", "Cold hands pull you toward the deep."))),
            opt("force", "Put them down by force",   "", fight(1.8, 1.6, 30, "You scatter the drowned with steel and salt.", "They are too many, and the deep is patient.")));

        put(KingdomQuestType.GUARD_SACRED_REEF,
            "A leviathan of the deep coils around the sacred reef.",
            opt("defend","Defend the reef",          "", fight(1.6, 1.5, 40, "You drive the leviathan back into the abyss.", "The leviathan's coils close, and the reef runs red.")),
            opt("sing",  "Sing the old verse to lull it","LUCK " + EPIC, check(Attribute.LUCK, EPIC,
                         fight(2.3, 2.1, 45, "The old verse stills it — and you strike while it dreams.", "The verse cracks and wakes it to wrath."),
                         fight(1.4, 1.3, 35, "Your voice falters and the leviathan stirs angry.", "The deep drowns your song and nearly you."))));
    }
}
