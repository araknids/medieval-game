# Plano — Quests de Reino V2 (rotação + combate + narrativa)

> Documento de design. Decisões tomadas com o dono em 2026-06-03.
> Status: **✅ IMPLEMENTADO** (lore em inglês). 418 testes verdes. FEATURES/GDD/USE_CASES/TEST_PLAN sincronizados.

---

## Visão

Hoje cada reino tem **2 quests fixas** — fica repetitivo. A proposta:

1. **6 quests por reino** (30 no total), com a UI mostrando **2 por vez**, revezando a cada 6h.
2. **Encontro de monstro** durante a quest: na coleta há uma chance (escala com a dificuldade) de
   ter aparecido um monstro; é preciso **vencer o combate** para receber a recompensa.
3. **Lore narrada na coleta**: um texto curto conta o que aconteceu (paz, vitória ou derrota).

Objetivo: variedade + tensão + narrativa, reaproveitando o motor de combate (`BattleSimulator`) já existente.

---

## Decisões fechadas (2026-06-03)

1. **Rotação:** janela **global de 6h** (igual à Loja). Não é por jogador — todos veem a mesma dupla.
2. **Chance de monstro:** **escala com a dificuldade** da quest (~15% nas curtas → ~90% nas longas).
3. **Derrota no combate:** **sem recompensa** (0 XP / 0 bronze / 0 drop) **+ dano**: o guerreiro fica
   com o HP que sobrou da luta (pode ficar nocauteado e precisar do Templo).

### Decisões menores (assumidas — mudáveis)

- **Encontro sorteado na coleta** (suspense — o jogador não sabe de antemão se vai ter luta).
- **Iniciar quest NÃO trava na dupla visível.** A rotação é só da *vitrine* (`GET /quests` retorna 2).
  Começar aceita qualquer das 6 do reino — evita testes dependentes de relógio e não quebra quest em
  andamento quando a vitrine vira. (Posso travar depois, se quiser que a rotação seja "dura".)
- **Drop e desgaste de equipamento** só acontecem quando há combate e vitória. Quest em paz não
  desgasta equipamento.
- **Lore em inglês.** Todo o conteúdo do jogo está em inglês por enquanto; a tradução PT-BR vem
  depois (i18n Fase 2). Nomes das quests também em inglês, exibidos pelo `displayName`.

---

## 1. As 6 quests por reino

Tiers de dificuldade crescente (duração / bronze / XP / estamina / drop% / **chance de monstro**):

| Tier | Duração | Bronze | XP | Estamina | Drop% | Monstro% |
|------|---------|--------|-----|----------|-------|----------|
| 1 | 5 min  | 100  | 50  | 10 | 10% | 15% |
| 2 | 10 min | 250  | 150 | 16 | 20% | 30% |
| 3 | 15 min | 400  | 250 | 22 | 30% | 45% |
| 4 | 20 min | 600  | 400 | 30 | 40% | 60% |
| 5 | 25 min | 800  | 575 | 40 | 50% | 75% |
| 6 | 30 min | 1000 | 750 | 50 | 60% | 90% |

Os tiers 1 e 6 reaproveitam as constantes atuais (`PATROL_COAST`, `HUNT_SEA_MONSTER`, etc.) — os
tiers 2-5 são novos. Nomes propostos:

| Reino | T1 | T2 | T3 | T4 | T5 | T6 |
|-------|----|----|----|----|----|----|
| 🎣 Desfiladeiro do Osso | Patrol the Coast | Explore the Reefs | Salvage the Wreck | Clear the Pirate Cove | Deep Sea Raid | Hunt the Sea Monster |
| ⛏ Minas de Ferro Negro | Escort the Miners | Clear the Caves | Shore Up the Tunnels | Retrieve the Lost Ore | Purge the Infestation | Defeat the Cave Beast |
| ⚔ Fortaleza Maldita | Defend the Walls | Clear the Dungeon | Patrol the Ramparts | Raid the Encampment | Breach the Keep | Hunt the Warlord |
| 🔎 Grutas de Cristal | Guard the Crystal Veins | Map the Grotto | Extract the Geodes | Seal the Fissure | Cleanse the Crystal Horror | Slay the Crystal Beast |
| 🐟 Mar Abençoado | Cleanse the Tides | Bless the Shallows | Escort the Pilgrims | Purify the Reef | Banish the Drowned | Guard the Sacred Reef |

> `KingdomQuestType` ganha um campo novo: `int monsterChance`. (`dropChance` já existe.)

---

## 2. Rotação (janela global de 6h)

```
rotationId = epochSeconds / 21600        // 21600s = 6h, igual à Loja
start      = rotationId % 6              // avança 1 por janela
vitrine    = [ quests[start], quests[(start+1) % 6] ]   // 2 quests do reino, ordenadas por tier
```

- Avança **1 posição** por janela → 6 janelas distintas (cobre tudo em 36h), cada quest aparece em
  2 janelas consecutivas. Mais variedade que avançar de 2 em 2.
- `getQuestsForKingdom(kingdom)` passa a retornar **a dupla da vitrine** (2). Um método interno
  `allQuestsForKingdom(kingdom)` retorna as 6 (para validação/regras).
- `GET /api/world/{kingdom}/quests` → 2 (a vitrine). `secsUntilRotation` pode ir junto (opcional).

---

## 3. Encontro de monstro (na coleta)

No `collectQuest`:

1. Rola `monsterChance` da quest. Se **não** der monstro → travessia em paz, recompensa cheia.
2. Se der monstro: sorteia um monstro temático do reino e roda o combate
   (`WarriorStatsService.combatStats` + `BattleSimulator.simulateDetailed`, igual à caçada da Fortaleza).
   - **Vitória** → recompensa cheia (bronze + XP + drop) + desgaste de equipamento + HP final salvo.
   - **Derrota** → **0 recompensa**; HP do guerreiro = o que sobrou (pode ficar KO). Equipamento desgasta.
3. A quest é coletada (sem `onMission` — removido em 2026-06-05; a quest só usa o guard "uma em progresso por vez").

**Escalonamento do monstro** (vencível por guerreiro equipado, mais duro nas quests altas):

```
diff = 0.8 + (durationMinutes / 30.0) * 0.6     // 5min→0.9 ... 30min→1.4
atk  = (3 + level*2) * diff
def  = (1 + level)   * diff
hp   = (40 + level*12) * diff
dex  = min(level/3, 14)   str = min(level/15, 3)   luk = min(level/5, 8)
```

**Monstros temáticos por reino:**

| Reino | Monstros |
|-------|----------|
| Desfiladeiro do Osso | Serpente Marinha, Caranguejo Colossal, Pirata Afogado, Kraken Jovem |
| Minas de Ferro Negro | Golem de Pedra, Verme das Profundezas, Aranha Rochosa, Espírito da Mina |
| Fortaleza Maldita | Cavaleiro Caído, Ogro de Guerra, Carrasco Amaldiçoado, Capitão Renegado |
| Grutas de Cristal | Aberração de Cristal, Golem Prismático, Guardião de Gemas, Morcego Cintilante |
| Mar Abençoado | Afogado Maldito, Sereia Sombria, Servo das Marés, Leviatã Pálido |

> Chefes (boss) continuam reservados para a Torre — aqui são mobs comuns.

---

## 4. Narrativa na coleta (`KingdomQuestNarrator`)

Componente novo que monta um texto curto (**em inglês**) a partir de: reino + quest + desfecho + nome
do monstro. Três desfechos:

- **Peace** (sem monstro): "You patrolled the coast without incident and returned with the job done."
- **Victory** (monstro derrotado): "On the way, a Sea Serpent surged from the depths — after a hard
  fight, you struck it down and claimed your reward."
- **Defeat** (monstro venceu): "A Young Kraken ambushed you in the deep and forced you to retreat
  wounded. No reward this time."

Variações por reino + alguns templates aleatórios pra não repetir. Testável em unidade (sem RNG no core).

---

## 5. Mudanças técnicas

| Camada | Mudança |
|--------|---------|
| `enums/KingdomQuestType` | 6 por reino (30 valores) + campo `int monsterChance` |
| `service/KingdomQuestNarrator` (novo) | gera a lore por reino/desfecho + pool de monstros |
| `service/KingdomService` | `getQuestsForKingdom` → vitrine de 2 (rotação 6h); `allQuestsForKingdom` (6); combate + narrativa no `collectQuest`; novas deps `WarriorStatsService`, `BattleSimulator`, `KingdomQuestNarrator`; `CollectResult` ganha `narrative`, `monsterEncountered`, `monsterDefeated`, `monsterName`, `battleLog` |
| `controller/KingdomController` | mapeia os campos novos do `CollectResult` no JSON do collect e do instant-start |
| `model/KingdomActiveQuest` | **sem mudança** (encontro é sorteado na coleta, não persistido) |
| Frontend `app.js` | `collectKingdomQuest` mostra a lore + (se houve) resultado/registro do combate; `showCollectModal` ganha um parâmetro `note` (parágrafo de narrativa); caminho de derrota exibe modal vermelho sem recompensa |

### Migração / DB
- Os novos valores de `KingdomQuestType` entram na coluna `kingdom_active_quests.quest_type`.
- **Já coberto:** o `SchemaMigrator.dropStaleEnumCheckConstraints()` derruba o check dessa coluna no
  boot, então os novos valores não esbarram em check constraint defasado. Sem soft-wipe necessário.

---

## 6. Plano de testes

| Área | Verifica |
|------|----------|
| Rotação | `getQuestsForKingdom` retorna 2; `allQuestsForKingdom` retorna 6; a dupla muda conforme a janela de 6h (testar com relógio injetável ou método determinístico por `rotationId`) |
| Contagem | cada reino tem exatamente 6 quests definidas (`KingdomQuestType`) |
| Narrador | os 3 desfechos geram texto não-vazio e coerente (unit, sem RNG no core) |
| Escala do monstro | `questMobStats` cresce com level e dificuldade (unit) |
| Combate na coleta | vitória → XP/bronze creditados; derrota → 0 recompensa + HP reduzido (integração; guerreiro fraco vs quest de alta chance, observando o caminho de derrota) |
| Coleta sempre narra | resposta do collect sempre traz `narrative` não-vazio |
| Regressão | `WorldIntegrationTest` (vitrine = 2), quests existentes (PATROL_COAST etc. continuam válidas) |

---

## 7. Fora de escopo (agora)

- Travar o "iniciar quest" na dupla visível (rotação dura).
- Rotação por jogador / re-sorteio a cada coleta.
- Chefes especiais nessas quests (ficam na Torre — Fase 5).
- Cozinha / refeições e outras expansões já listadas no GDD.
