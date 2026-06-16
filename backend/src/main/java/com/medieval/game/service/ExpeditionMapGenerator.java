package com.medieval.game.service;

import com.medieval.game.enums.ExpeditionNodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * [INCURSAO] Gerador determinístico do mapa ramificado de uma Incursão (Delve).
 *
 * <p>Puro (sem Spring), seedável → testável. Gera {@code depth} camadas; cada camada tem 2-3 nós; a
 * última é um único BOSS. Pesos de tipo de nó escalam por camada e tier. Garante 1 CAMP (checkpoint)
 * no meio das runs longas. Números são placeholders p/ tuning. Ver docs/PLANO_INCURSAO.md §5.
 */
public final class ExpeditionMapGenerator {

    private ExpeditionMapGenerator() {}

    /** Um nó do mapa. {@code monsterLevelBump} soma ao nível-base do monstro (mais fundo = mais forte). */
    public record Node(String id, ExpeditionNodeType type, int monsterLevelBump) {}

    /** Uma camada do mapa (0-based). A última camada tem 1 nó BOSS. */
    public record Layer(int index, List<Node> nodes) {}

    /** O mapa serializável (vira {@code ExpeditionRun.mapJson}). */
    public record Map(int depth, int tier, List<Layer> layers) {}

    /**
     * Gera um mapa determinístico a partir do seed (= id da run). {@code depth} camadas (mín. 2),
     * última = BOSS; 2-3 nós por camada intermediária; pesos por camada/tier.
     */
    public static Map generate(long seed, int depth, int tier) {
        return generate(seed, depth, tier, 0);
    }

    /**
     * Como {@link #generate(long, int, int)} mas injeta {@code bonusTreasure} nós de TESOURO extras numa
     * camada intermediária (perk VIP: +1 baú garantido por run). [VIP][INCURSAO]
     */
    public static Map generate(long seed, int depth, int tier, int bonusTreasure) {
        int d = Math.max(2, depth);
        int t = Math.max(1, tier);
        Random rng = new Random(seed);
        int mid = d / 2;

        List<List<Node>> raw = new ArrayList<>(d);
        for (int layer = 0; layer < d; layer++) {
            List<Node> nodes = new ArrayList<>();
            if (layer == d - 1) {
                // Camada final: 1 chefe.
                nodes.add(new Node(nodeId(layer, 0), ExpeditionNodeType.BOSS, bossBump(d, t)));
            } else {
                int count = 2 + rng.nextInt(2); // 2 ou 3
                for (int i = 0; i < count; i++) {
                    ExpeditionNodeType type;
                    if (d >= 4 && layer == mid && i == 0) {
                        type = ExpeditionNodeType.CAMP; // checkpoint garantido no meio das runs longas
                    } else {
                        type = pickType(rng, layer, t);
                    }
                    nodes.add(new Node(nodeId(layer, i), type, bumpFor(type, layer, t)));
                }
            }
            raw.add(nodes);
        }

        // [VIP] baú(s) extra(s): camada intermediária (1..d-2), sem lotar (máx 4 nós/camada).
        for (int b = 0; b < bonusTreasure && d > 2; b++) {
            int layer = 1 + rng.nextInt(d - 2);
            List<Node> nodes = raw.get(layer);
            if (nodes.size() < 4) {
                nodes.add(new Node(nodeId(layer, nodes.size()), ExpeditionNodeType.TREASURE,
                        bumpFor(ExpeditionNodeType.TREASURE, layer, t)));
            }
        }

        List<Layer> layers = new ArrayList<>(d);
        for (int layer = 0; layer < d; layer++) {
            layers.add(new Layer(layer, List.copyOf(raw.get(layer))));
        }
        return new Map(d, t, List.copyOf(layers));
    }

    static String nodeId(int layer, int idx) { return "L" + layer + "N" + idx; }

    /** Sorteio ponderado do tipo de nó. ELITE só a partir da 2ª camada e mais comum fundo/alto tier. */
    private static ExpeditionNodeType pickType(Random rng, int layer, int tier) {
        // pares (tipo, peso)
        int wCombat   = 50;
        int wEvent    = 18;
        int wTreasure = layer > 0 ? 15 : 0;                       // [INCURSAO] nunca tesouro no 1º round
        int wCamp     = layer > 0 ? 10 : 0;                       // sem checkpoint na 1ª camada
        int wElite    = layer >= 1 ? (layer * 3 + tier * 4) : 0;  // escala fundo/tier
        int total = wCombat + wEvent + wTreasure + wCamp + wElite;
        int roll = rng.nextInt(total);
        if ((roll -= wCombat)   < 0) return ExpeditionNodeType.COMBAT;
        if ((roll -= wEvent)    < 0) return ExpeditionNodeType.EVENT;
        if ((roll -= wTreasure) < 0) return ExpeditionNodeType.TREASURE;
        if ((roll -= wCamp)     < 0) return ExpeditionNodeType.CAMP;
        return ExpeditionNodeType.ELITE;
    }

    private static int bumpFor(ExpeditionNodeType type, int layer, int tier) {
        return switch (type) {
            case ELITE -> layer + 2 + tier;
            case CAMP, TREASURE -> 0;       // CAMP não luta; TREASURE só luta se armadilhar (usa layer)
            default -> layer;               // COMBAT, EVENT
        };
    }

    private static int bossBump(int depth, int tier) {
        return depth + tier * 2 + 2;
    }
}
