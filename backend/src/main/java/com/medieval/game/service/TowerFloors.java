package com.medieval.game.service;

/**
 * [TORRE_NARRATIVA] Catálogo dos 50 andares da Torre (S1). Cada andar tem uma ATMOSFERA própria e o(s)
 * MONSTRO(S) que o guardam (gauntlet sequencial); a cada 10 andares, um MVP de história. A subida é a
 * queda do Rei Arka contada de baixo pra cima. Texto exibido ao player = inglês (regra de UI). Ver
 * docs/LORE.md + docs/PLANO_QUESTS_LORE.md. Stats/balance ficam no TowerService (tunados pela sonda).
 */
public final class TowerFloors {

    private TowerFloors() {}

    /** Definição de um andar: atmosfera + monstros (gauntlet) OU um MVP (chefe de história). */
    public record FloorDef(String atmosphere, String[] monsters, String mvp) {
        public boolean isMvp() { return mvp != null; }
    }

    private static FloorDef floor(String atmosphere, String... monsters) {
        return new FloorDef(atmosphere, monsters, null);
    }
    private static FloorDef mvp(String mvp, String atmosphere) {
        return new FloorDef(atmosphere, new String[0], mvp);
    }

    // Índice = andar - 1 (1..50). 5 zonas de 9 + um MVP a cada 10.
    private static final FloorDef[] FLOORS = {
        // ── Zona 1 (1-9): Salões Baixos — a guarda caída ──────────────────────────
        floor("The first hall is cold for a place so high. The air doesn't fall from the windows — it rises, from a seam in the floor, smelling of tide and deep earth. There's a round mark on the stone the King ordered covered. It was his first command.", "Gate Sentry"),
        floor("Spears lean in racks no hand has touched in years. One of them is still warm.", "Hall Sentinel"),
        floor("The torches burn with a pale, low flame that gives no heat, and your shadow falls the wrong way.", "Pale Watchman"),
        floor("A guardroom: dice still on the table, chairs pushed back, as if everyone rose at once and walked up.", "Deserter's Husk", "Deserter's Husk"),
        floor("Banners of Coroa de Arka rot on the walls, the stitched crown gone black.", "Watchman's Husk", "Watchman's Husk"),
        floor("The stair narrows. Handprints climb the wall beside you — too many fingers on each.", "Crawling Dead"),
        floor("A chapel to no god you know. The candles weep wax that pools and crawls toward the stairs up.", "Wax-Eaten Priest"),
        floor("The dead here wear the garrison's colors and salute as you pass — then they reach for their blades.", "Loyal Unto Death", "Loyal Unto Death"),
        floor("The last hall before the upper gate stinks of old blood and new rust. Something big breathed here, recently.", "Gate-Breaker"),
        mvp("The Fallen Captain", "He held this hall to the last, and was not allowed to die. His sword is still raised. His eyes follow you — and beg. He had a name, once — Sor Bramm Holt, Captain of the Gate."),

        // ── Zona 2 (11-19): A Corte — a nobreza podre ─────────────────────────────
        floor("Gold leaf peels from the walls in sheets. The floor is carpeted in coins no one stooped to gather.", "Gilded Wretch"),
        floor("Mirrors line the gallery. In each one, your reflection is a single heartbeat slow.", "Mirror-Bound"),
        floor("Portraits of nobles, every painted face scratched out — all but the eyes.", "Faceless Courtier", "Faceless Courtier"),
        floor("A banquet long spoiled, the guests still seated, still chewing.", "Feast-Rotted", "Feast-Rotted"),
        floor("Perfume thick enough to choke, laid over something sweeter and far worse beneath.", "Powdered Corpse"),
        floor("The throne-room's antechamber. Petitioners knelt here so long their knees fused to the stone.", "The Kneeling", "The Kneeling"),
        floor("A counting-house: ledgers stacked to the ceiling, every page the same name, written a thousand times.", "Tally-Keeper"),
        floor("Jewels grow from the walls like mold. They turn to follow you, the way eyes do.", "Jewel-Crusted Horror"),
        floor("The court's champion-at-arms, who never lost a duel and never fought a real one.", "Duelist of Lies"),
        mvp("The Coin-Eaten", "He counted his gold while the city burned. The rot found him full — and made him fuller. He offers you a price to turn back. He cannot understand that you won't take it. His name was Lord Casnar Vane, the crown's Treasurer."),

        // ── Zona 3 (21-29): As Profundezas do Ritual ──────────────────────────────
        floor("Below the court the stone is warm. The stairs are wet, and the wet is red, and it flows up to meet you.", "Bleeding Acolyte"),
        floor("Censers swing on their own, breathing smoke that tastes of copper and old prayers.", "Censer-Wraith", "Censer-Wraith"),
        floor("A circle scored into the floor, scrubbed and re-cut a hundred times. The grooves run deep enough to drown in.", "Circle-Warden"),
        floor("The faithful kneel facing down, repeating a word with no mouth-shape you can make.", "The Chanting", "The Chanting", "The Chanting"),
        floor("Crystals grow from the altars, red at the core. One of them pulses in time with your heart.", "Crystal-Hearted"),
        floor("Vials of royal blood line every shelf, each labeled with a date. The last one is today's.", "Vintner of Blood"),
        floor("Something was born here and did not survive the birth. It is still trying.", "The Unborn Rite"),
        floor("The deeper altar. The blood doesn't pool — it climbs the walls, against the slope, toward the floors above.", "Altar-Thing", "Altar-Thing"),
        floor("A throne-shaped chair of bone and gold: a rehearsal seat. Whoever sat here practiced being a king.", "The Pretender"),
        mvp("The Crowned Echo", "It wears the King's shape, his walk, his voice — a hollow rehearsal for the thing at the top. It greets you by name, in the King's voice, and asks why you've come so far to kill a man you were sent to save. It owns no name of its own — only his."),

        // ── Zona 4 (31-39): A Sombra do Rei ───────────────────────────────────────
        floor("The light here comes from below now, the wrong shade of pale, and it casts no shadows at all.", "Shadeless One"),
        floor("The walls breathe in. Warm air pulls past you, downward, always downward.", "Breath-Taken"),
        floor("Things dredged up from somewhere deeper, wearing stone and gold like skin that doesn't fit.", "Dredged Horror", "Dredged Horror"),
        floor("The King's study. His notes cover every surface, in a hand that grows less human page by page.", "Ink-Drowned Scholar"),
        floor("A menagerie of the changed — courtiers, guards, priests — all melting toward one shape you cannot name.", "The Becoming", "The Becoming"),
        floor("A whisper in a voice almost like the King's promises you the thing you want most, and calls you by your own name.", "Honeyed Whisper"),
        floor("The air hums, low and steady — a heartbeat far too large to belong to anything that ought to live.", "Hum-Made-Flesh"),
        floor("The last of the King's true guard, who followed him down and could not follow him through.", "Threshold Guard", "Threshold Guard"),
        floor("A door scorched from the inside. Something tried to keep itself in. It failed.", "The Failed Seal"),
        mvp("The Xamã", "The man the sea spat back, years ago, declared dead and returned wrong. He lifts no hand against you. He only laughs, and bids you climb. \"Give the poor king his peace,\" he says. \"It's all I ever wanted.\" They called him Oren, the Drowned — the man who lit the rite."),

        // ── Zona 5 (41-49): O Limiar ──────────────────────────────────────────────
        floor("Past the Shaman, the tower stops being a tower. The stairs go on; the walls forget to.", "Edge-Walker"),
        floor("You can no longer tell up from down. The hum is in your teeth now.", "Vertigo-Thing", "Vertigo-Thing"),
        floor("Shapes that are almost people, almost beasts, almost nothing — still deciding what to be.", "The Undecided"),
        floor("The floor is skin-warm and yields like a held breath. Far below, something vast turns over in its sleep.", "Sleeper's Dream", "Sleeper's Dream"),
        floor("Royal blood, fresh, runs UP the stairs past you, eager, toward the top.", "The Eager Tide"),
        floor("A throne of the King's old banners, burned and rebuilt, burned and rebuilt.", "Ash-Crowned"),
        floor("The whispers stop. The silence is worse — it is listening back.", "The Listening Dark"),
        floor("A reflection of you climbs beside you, one step behind, smiling.", "The One Step Behind"),
        floor("The last door. Light leaks under it, the color of a wound. Beyond it, you hear a man weeping.", "The Weeping Threshold"),
        mvp("Rei Arka", "King Arka, wreathed in a light that is not his own — and beneath it, for a flicker, the man who founded a kingdom. He fights to ascend; he does not understand he is only a key. When he falls, he will beg. Arka, the Founder — the man beneath the key."),
    };

    /** Definição do andar (1..50). Acima de 50 reaproveita o 50 (não deveria acontecer na S1). */
    public static FloorDef forFloor(int floor) {
        int idx = Math.max(1, Math.min(floor, FLOORS.length)) - 1;
        return FLOORS[idx];
    }

    public static int maxFloor() { return FLOORS.length; }
}
