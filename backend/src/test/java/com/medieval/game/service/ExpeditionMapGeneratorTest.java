package com.medieval.game.service;

import com.medieval.game.enums.ExpeditionNodeType;
import com.medieval.game.service.ExpeditionMapGenerator.Layer;
import com.medieval.game.service.ExpeditionMapGenerator.Map;
import com.medieval.game.service.ExpeditionMapGenerator.Node;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** [INCURSAO] Mapa procedural: determinístico, última camada = BOSS, 2-3 nós, ids únicos. */
class ExpeditionMapGeneratorTest {

    @Test
    void deterministicForSameSeed() {
        Map a = ExpeditionMapGenerator.generate(42L, 4, 2);
        Map b = ExpeditionMapGenerator.generate(42L, 4, 2);
        assertEquals(a, b, "mesmo seed → mesmo mapa (records iguais)");
    }

    @Test
    void differentSeedsDiffer() {
        Map a = ExpeditionMapGenerator.generate(1L, 5, 3);
        Map b = ExpeditionMapGenerator.generate(2L, 5, 3);
        assertNotEquals(a, b, "seeds diferentes tendem a gerar mapas diferentes");
    }

    @Test
    void lastLayerIsSingleBoss() {
        Map m = ExpeditionMapGenerator.generate(7L, 4, 1);
        Layer last = m.layers().get(m.layers().size() - 1);
        assertEquals(1, last.nodes().size(), "camada final tem 1 nó");
        assertEquals(ExpeditionNodeType.BOSS, last.nodes().get(0).type());
    }

    @Test
    void intermediateLayersHave2or3NodesAndUniqueIds() {
        Map m = ExpeditionMapGenerator.generate(99L, 5, 2);
        assertEquals(5, m.layers().size());
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < m.layers().size() - 1; i++) {
            Layer layer = m.layers().get(i);
            assertTrue(layer.nodes().size() >= 2 && layer.nodes().size() <= 3,
                    "camada " + i + " deve ter 2-3 nós");
            for (Node n : layer.nodes()) {
                assertNotEquals(ExpeditionNodeType.BOSS, n.type(), "BOSS só na última camada");
                assertTrue(ids.add(n.id()), "id de nó duplicado: " + n.id());
            }
        }
    }

    @Test
    void longRunsHaveACheckpoint() {
        // depth>=4 garante 1 CAMP no meio (ponto de bank). [INCURSAO]
        Map m = ExpeditionMapGenerator.generate(3L, 4, 1);
        boolean hasCamp = m.layers().stream().flatMap(l -> l.nodes().stream())
                .anyMatch(n -> n.type() == ExpeditionNodeType.CAMP);
        assertTrue(hasCamp, "run longa deve ter ao menos 1 CAMP (checkpoint)");
    }

    @Test
    void vipBonusTreasureAddsExactlyOneChest() {
        // [VIP] +1 baú garantido: o perk acrescenta exatamente 1 nó de TESOURO (mesmo seed/depth/tier).
        long seed = 55L;
        int base = countType(ExpeditionMapGenerator.generate(seed, 4, 2, 0), ExpeditionNodeType.TREASURE);
        int vip  = countType(ExpeditionMapGenerator.generate(seed, 4, 2, 1), ExpeditionNodeType.TREASURE);
        assertEquals(base + 1, vip, "VIP deve ganhar +1 baú");
    }

    @Test
    void bossIsTheStrongestNode() {
        // Invariante de balance: o CHEFE escala mais que qualquer nó normal (mais fundo = mais forte = pico no boss).
        Map m = ExpeditionMapGenerator.generate(12L, 5, 3);
        Node boss = m.layers().get(m.layers().size() - 1).nodes().get(0);
        int maxNormal = m.layers().stream().flatMap(l -> l.nodes().stream())
                .filter(n -> n.type() != ExpeditionNodeType.BOSS)
                .mapToInt(Node::monsterLevelBump).max().orElse(0);
        assertTrue(boss.monsterLevelBump() > maxNormal, "o chefe deve ser o nó mais forte da run");
    }

    @Test
    void bossReachableFromAnyColumn() {
        // [INCURSAO] regressão do soft-lock: o CHEFE (1 nó no índice 0) precisa ser alcançável vindo de
        // QUALQUER coluna da penúltima camada — inclusive a coluna 2 (abs(0-2)=2 > 1 sem o clamp).
        for (int prevCol = 0; prevCol <= 3; prevCol++) {
            assertTrue(ExpeditionMapGenerator.isReachable(prevCol, 0, 1),
                    "chefe deve ser alcançável vindo da coluna " + prevCol);
        }
    }

    @Test
    void firstLayerIsAlwaysReachable() {
        // 1ª camada (prevIdx = -1) não tem restrição de coluna.
        for (int i = 0; i < 4; i++)
            assertTrue(ExpeditionMapGenerator.isReachable(-1, i, 4), "1ª camada livre, coluna " + i);
    }

    @Test
    void everyLayerWidthHasAtLeastOneReachableNode() {
        // Invariante anti-soft-lock: de QUALQUER coluna anterior, p/ QUALQUER largura de camada (1-4),
        // existe ao menos 1 nó alcançável → a run nunca trava.
        for (int prevCol = 0; prevCol <= 3; prevCol++) {
            for (int width = 1; width <= 4; width++) {
                boolean any = false;
                for (int i = 0; i < width; i++)
                    if (ExpeditionMapGenerator.isReachable(prevCol, i, width)) { any = true; break; }
                assertTrue(any, "sem nó alcançável: coluna anterior=" + prevCol + " largura=" + width);
            }
        }
    }

    private static int countType(Map m, ExpeditionNodeType t) {
        return (int) m.layers().stream().flatMap(l -> l.nodes().stream()).filter(n -> n.type() == t).count();
    }
}
