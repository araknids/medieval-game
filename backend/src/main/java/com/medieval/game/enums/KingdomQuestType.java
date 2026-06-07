package com.medieval.game.enums;

public enum KingdomQuestType {

    // 6 quests per realm (Reinos V2). The UI shows 2 at a time, rotating (see KingdomService).
    // [QUESTS_LORE] Names + flavor reskinned to seed the lore in the margins (docs/PLANO_QUESTS_LORE.md):
    // each realm carries a layer of the truth — subtle, never spelled out, paying off in the Tower.
    // params: (kingdom, displayName, durationMinutes, bronzeReward, expReward, staminaCost, dropChance, monsterChance, flavor)

    // ── Bone Gorge (FISHING) — the sea has a will; it already spat a man back (Shaman seed) ──
    PATROL_COAST     (Kingdom.FISHING, "Generous Waters",            5,  100,  50, 5, 10, 15, "The nets come up full before you even cast. The sea wants you fed — and wants you to stay."),
    EXPLORE_REEFS    (Kingdom.FISHING, "The Reef That Grew",        10,  250, 150, 7, 20, 30, "This reef wasn't on last year's charts. It's closer to shore now. It'll be closer still next season."),
    SALVAGE_THE_WRECK(Kingdom.FISHING, "What the Tide Gathers",     15,  400, 250, 9, 30, 45, "Every wreck on this coast drifted to the same cove, prows all pointing the same way — inward, and down."),
    CLEAR_PIRATE_COVE(Kingdom.FISHING, "The Reaching Dead",         20,  600, 400, 11, 40, 60, "The pirates didn't drown fleeing the beasts. They died reaching for the deep, hands out to the water, like they were called."),
    DEEP_SEA_RAID    (Kingdom.FISHING, "Too Deep",                  25,  800, 575, 13, 50, 75, "The further down you dive, the warmer the water gets. A cold sea shouldn't have a warm heart beating somewhere below."),
    HUNT_SEA_MONSTER (Kingdom.FISHING, "The Man the Sea Spat Back", 30, 1000, 750, 15, 60, 90, "The old hands tell of a fisherman lost years ago, declared dead — until the tide laid him on the sand, breathing, changed. He walked inland and never stopped. The sea keeps nothing it means to use."),

    // ── Black Iron Mines (MINING) — the riches come from something alive below ──
    ESCORT_MINERS     (Kingdom.MINING, "Deeper",                 5,  100,  50, 5, 10, 15, "Every season they sink the shaft lower, and every season the ore is richer for it. No one wonders where it ends."),
    CLEAR_CAVES       (Kingdom.MINING, "The Hum",              10,  250, 150, 7, 20, 30, "The lowest galleries hum — low and steady, almost a heartbeat. The veterans swear they stopped hearing it years ago."),
    SHORE_UP_TUNNELS  (Kingdom.MINING, "The Warm Walls",       15,  400, 250, 9, 30, 45, "The walls down here are warm to the touch, and they give a little when you brace them — as if they'd rather not be propped."),
    RETRIEVE_LOST_ORE (Kingdom.MINING, "The Bleeding Vein",    20,  600, 400, 11, 40, 60, "Where the pick bites, the ore runs red and wet before it dries — as if the mountain bleeds where you wound it."),
    PURGE_INFESTATION (Kingdom.MINING, "What Grows Below",     25,  800, 575, 13, 50, 75, "The things in the bottom shaft didn't wander in. They grew there, out of the walls, wearing the shape of the rock."),
    DEFEAT_CAVE_BEAST (Kingdom.MINING, "What Stirs in the Stone", 30, 1000, 750, 15, 60, 90, "At the end of the vein the wall is smooth — not rock. It yields when you press it. The ore here doesn't break free; it weeps."),

    // ── Cursed Fortress (COMBAT) — the corruption flows from the Tower; the King serves something ──
    DEFEND_WALLS     (Kingdom.COMBAT, "The Wrong Side",        5,  100,  50, 5, 10, 15, "The monsters don't lay siege to the fortress. They pour out of it — from the gate that faces the King's tower."),
    CLEAR_DUNGEON    (Kingdom.COMBAT, "Those Who Stayed",     10,  250, 150, 7, 20, 30, "The prisoners in the dungeon are the old garrison. They didn't desert — they were changed where they stood, every face turned toward the Tower."),
    PATROL_RAMPARTS  (Kingdom.COMBAT, "In the Tower's Shadow", 15, 400, 250, 9, 30, 45, "From the ramparts you can see it: no light in the King's tower for a long, long time now — only a faint glow from the lowest floor, the wrong shade of pale."),
    RAID_ENCAMPMENT  (Kingdom.COMBAT, "The Faithful",        20,  600, 400, 11, 40, 60, "It isn't an enemy army camped at the gates. It's pilgrims and soldiers who walked to the Tower and knelt. They will not let you pass to it."),
    BREACH_THE_KEEP  (Kingdom.COMBAT, "The Last Door",       25,  800, 575, 13, 50, 75, "The keep's lord sealed it from the inside, they say, 'to keep something in.' Whatever it was, it seeps under the door all the same."),
    HUNT_WARLORD     (Kingdom.COMBAT, "The Cursed Knight",   30, 1000, 750, 15, 60, 90, "The strongest of the fallen was the King's own champion, set to guard the Tower. He doesn't fight to beat you — he fights to keep you OUT."),

    // ── Crystal Grottoes (GRUTAS_DE_CRISTAL) — it isn't a thing, it's a mind; it speaks; it has the faithful ──
    GUARD_CRYSTAL_VEINS   (Kingdom.GRUTAS_DE_CRISTAL, "What Dreams the Gems",    5,  100,  50, 5, 10, 15, "The crystals grow back overnight, in the very same shapes, as if something below keeps dreaming them up."),
    MAP_THE_GROTTO        (Kingdom.GRUTAS_DE_CRISTAL, "Mapping What Moves",     10,  250, 150, 7, 20, 30, "The grotto is never the same twice. The tunnels rearrange when no one is watching — always leading further down."),
    EXTRACT_GEODES        (Kingdom.GRUTAS_DE_CRISTAL, "Crystallized Blood",     15,  400, 250, 9, 30, 45, "Crack a fresh geode and the inside is red and slick before it sets. The gems are blood, caught and hardened in the dark."),
    SEAL_THE_FISSURE      (Kingdom.GRUTAS_DE_CRISTAL, "The Breathing Fissure",  20,  600, 400, 11, 40, 60, "No cold draft comes out of the fissure. Warm air goes IN, breath after slow breath, as though the cavern is drawing it down."),
    CLEANSE_CRYSTAL_HORROR(Kingdom.GRUTAS_DE_CRISTAL, "The Ones Who Listened",  25,  800, 575, 13, 50, 75, "The prospectors here put down their tools and knelt, repeating a word no one taught them. The crystal gave them a voice, and a master to obey."),
    SLAY_CRYSTAL_BEAST    (Kingdom.GRUTAS_DE_CRISTAL, "The Voice in the Stone", 30, 1000, 750, 15, 60, 90, "It doesn't roar. It speaks — your name, the things you want — and for one breath you want to lower the blade and simply listen."),

    // ── Blessed Sea (MAR_ABENCOADO) — the blessing is the leak; the drowned reach for the deep ──
    CLEANSE_THE_TIDES (Kingdom.MAR_ABENCOADO, "The Tide That Won't Stay Clean", 5, 100,  50, 5, 10, 15, "You clear the rot, and by morning the tide has laid down more — gently, patiently, like it has all the time in the world."),
    BLESS_THE_SHALLOWS(Kingdom.MAR_ABENCOADO, "Already Blessed",       10,  250, 150, 7, 20, 30, "The priests bless the water. The water was already brighter than any prayer they know."),
    ESCORT_PILGRIMS   (Kingdom.MAR_ABENCOADO, "The Pilgrims",          15,  400, 250, 9, 30, 45, "They come from every shore to drink the healing tide. Not one asks why a dead sea would want to keep them alive."),
    PURIFY_THE_REEF   (Kingdom.MAR_ABENCOADO, "The Pale Reef",         20,  600, 400, 11, 40, 60, "The coral here is the wrong color — pale, and warm, and it swells and settles, slow, like something breathing in its sleep."),
    BANISH_THE_DROWNED(Kingdom.MAR_ABENCOADO, "The Drowned",           25,  800, 575, 13, 50, 75, "They don't come to kill. They pass you by, hands out to the surf, wanting back to the deep. You cut them down, and they seem to thank you."),
    GUARD_SACRED_REEF (Kingdom.MAR_ABENCOADO, "Below the Sacred Reef", 30, 1000, 750, 15, 60, 90, "Under the holiest water the light comes from below, not above. The fish gather there, still as glass, all of them facing down."),

    // ── Quest RARA da Luna: aparece às vezes em qualquer reino; sem loot, só a chance de pet. [PETS] ──
    RESCUE_STRAY_DOG  (Kingdom.FISHING, "A Stray in Need", 5, 0, 0, 0, 0, 0, "A half-drowned dog shivers on the rocks. The tide keeps trying to take it back. You don't let it.");

    public final Kingdom  kingdom;
    public final String   displayName;
    public final int      durationMinutes;
    public final long     bronzeReward;
    public final long     expReward;
    public final int      staminaCost;
    public final int      dropChance;    // %
    public final int      monsterChance; // % chance de encontro de monstro na coleta (escala com a dificuldade)
    public final String   flavor;        // [QUESTS_LORE] linha narrativa exibida no card (semeia a lore)

    KingdomQuestType(Kingdom kingdom, String displayName, int durationMinutes,
                     long bronzeReward, long expReward, int staminaCost, int dropChance, int monsterChance,
                     String flavor) {
        this.kingdom         = kingdom;
        this.displayName     = displayName;
        this.durationMinutes = durationMinutes;
        this.bronzeReward    = bronzeReward;
        this.expReward       = expReward;
        this.staminaCost     = staminaCost;
        this.dropChance      = dropChance;
        this.monsterChance   = monsterChance;
        this.flavor          = flavor;
    }
}
