# Medieval Game — Plano de Testes

> Gerado por agente com base em `CLAUDE.md`, `FEATURES.md` e `USE_CASES.md`. Atualizar sempre que novas funcionalidades forem adicionadas ou regras de negócio modificadas.

---

## Introdução

### Estratégia de Testes

Este documento define o plano de testes do Medieval Game, cobrindo dois níveis de validação:

1. **Testes Unitários**: Validam a lógica de negócio de cada serviço em isolamento total, sem dependências de banco de dados, HTTP ou serviços externos.
2. **Testes de Integração / E2E**: Validam os fluxos completos via API REST, simulando chamadas reais de cliente com banco de dados em memória (H2).

A estratégia adota a pirâmide de testes: maior cobertura unitária para garantir velocidade e precisão de diagnóstico, complementada por testes de integração que cobrem os fluxos críticos de negócio de ponta a ponta.

### Ferramentas Recomendadas

| Tipo | Ferramentas |
|------|------------|
| Testes Unitários | JUnit 5 (`@Test`, `@ExtendWith`) + Mockito (`@Mock`, `@InjectMocks`, `when/verify`) |
| Testes de Integração | Spring Boot Test (`@SpringBootTest`) + MockMvc (`@AutoConfigureMockMvc`) ou REST Assured |
| Banco de dados (testes) | H2 in-memory (já configurado no perfil `dev`) |
| Cobertura | JaCoCo (meta: ≥ 80% nas classes de serviço) |
| Relatórios | Surefire Plugin (CI) |

### Configuração Base para Testes de Integração

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev") // H2 + instant-complete=true
@Transactional         // rollback após cada teste
class NomeControllerTest {
    @Autowired MockMvc mockMvc;
    // helper para obter token JWT via /api/auth/login
}
```

### Convenções

- Todos os testes de integração assumem que `app.dev.instant-complete=true` está ativo (timers zerados).
- Testes marcados como **Alta** prioridade devem ser executados em todo pipeline de CI.
- Testes marcados como **Média** devem ser executados antes de releases.
- Testes marcados como **Baixa** são candidatos a smoke tests e cobertura de bordas.
- Prefixo `TC-001` a `TC-050` = Unitários; `TC-051` em diante = Integração.

---

## Seção 1 — Testes Unitários

---

### TC-001 — addBronze converte automaticamente para prata e ouro

**Tipo:** Unitário
**UC Relacionado:** UC-09, UC-10
**Prioridade:** Alta

**Cenário:** Verificar que `player.addBronzeAmount(n)` normaliza corretamente bronze excedente em prata e prata excedente em ouro, mantendo os campos separados e consistentes.

**Pré-condições:** Objeto `Player` instanciado com `bronze=0`, `silver=0`, `gold=0`.

**Dados de Entrada:**
```
addBronzeAmount(10350)
// 10350 bronze = 1 ouro + 3 prata + 50 bronze
```

**Passos:**
1. Criar instância de `Player` com saldo zerado.
2. Chamar `player.addBronzeAmount(10350)`.
3. Verificar os campos `bronze`, `silver` e `gold` resultantes.

**Resultado Esperado:**
- `player.getGold()` == 1
- `player.getSilver()` == 3
- `player.getBronze()` == 50

**Resultado de Falha:** Qualquer campo com valor diferente do esperado, ou normalização não realizada (ex.: `bronze=10350`, `silver=0`, `gold=0`).

---

### TC-002 — addBronze acumula corretamente sobre saldo existente

**Tipo:** Unitário
**UC Relacionado:** UC-09, UC-10
**Prioridade:** Alta

**Cenário:** Verificar que adicionar bronze sobre um saldo já existente (com valores em todas as moedas) normaliza corretamente.

**Pré-condições:** Objeto `Player` com `bronze=80`, `silver=99`, `gold=0`.

**Dados de Entrada:**
```
addBronzeAmount(30)
// 80 + 30 = 110 bronze → +1 silver, 10 bronze sobrando
// 99 + 1 = 100 silver → +1 gold, 0 silver sobrando
```

**Passos:**
1. Criar instância de `Player` com `bronze=80`, `silver=99`, `gold=0`.
2. Chamar `player.addBronzeAmount(30)`.
3. Verificar os campos resultantes.

**Resultado Esperado:**
- `player.getGold()` == 1
- `player.getSilver()` == 0
- `player.getBronze()` == 10

**Resultado de Falha:** Cascata de normalização não disparada, resultando em `silver=100` sem converter para ouro.

---

### TC-003 — spendBronze falha com saldo insuficiente

**Tipo:** Unitário
**UC Relacionado:** UC-09
**Prioridade:** Alta

**Cenário:** Verificar que `playerService.spendBronze(player, n)` lança exceção (ou retorna erro) quando o saldo total do jogador é insuficiente para cobrir o custo.

**Pré-condições:** Objeto `Player` com `bronze=50`, `silver=0`, `gold=0` (total = 50 bronze). Mock de `PlayerRepository` configurado.

**Dados de Entrada:**
```
spendBronze(player, 100) // tenta gastar 100, possui apenas 50
```

**Passos:**
1. Instanciar `PlayerService` com mock de `PlayerRepository`.
2. Criar `Player` com saldo de 50 bronze.
3. Chamar `playerService.spendBronze(player, 100)`.
4. Capturar exceção lançada.

**Resultado Esperado:**
- Exceção do tipo `RuntimeException` (ou subclasse específica do domínio) é lançada.
- Mensagem da exceção indica bronze insuficiente.
- Campos `bronze`, `silver` e `gold` do player permanecem inalterados.

**Resultado de Falha:** Exceção não lançada, ou saldo alterado negativamente.

---

### TC-004 — spendBronze deduz corretamente de múltiplas moedas

**Tipo:** Unitário
**UC Relacionado:** UC-09
**Prioridade:** Alta

**Cenário:** Verificar que `spendBronze` desconta corretamente convertendo de prata para bronze quando necessário.

**Pré-condições:** `Player` com `bronze=20`, `silver=1`, `gold=0` (total = 120 bronze).

**Dados de Entrada:**
```
spendBronze(player, 70)
// 20 bronze insuficiente → converte 1 silver → total 120 → gasta 70 → resta 50 bronze
```

**Passos:**
1. Criar `Player` com `bronze=20`, `silver=1`, `gold=0`.
2. Chamar `playerService.spendBronze(player, 70)`.
3. Verificar o saldo restante.

**Resultado Esperado:**
- `player.getBronze()` == 50
- `player.getSilver()` == 0
- `player.getGold()` == 0

**Resultado de Falha:** Saldo incorreto ou exceção desnecessária lançada.

---

### TC-005 — getCalculatedStamina retorna valor correto após tempo decorrido

**Tipo:** Unitário
**UC Relacionado:** UC-11, UC-12
**Prioridade:** Alta

**Cenário:** Verificar que o cálculo de stamina baseado em snapshot + tempo decorrido retorna o valor correto. Stamina regenera 100% em 120 minutos.

**Pré-condições:** `Warrior` com `staminaSnapshot=0` e `staminaUpdatedAt` = 60 minutos atrás.

**Dados de Entrada:**
```
staminaSnapshot = 0
staminaUpdatedAt = agora - 60 minutos
// Regen: 100% em 120 min → 0,833%/min → 60 min × 0,833 = 50%
```

**Passos:**
1. Criar `Warrior` com `staminaSnapshot=0` e `staminaUpdatedAt` setado para 60 minutos no passado (usando `Instant.now().minusSeconds(3600)`).
2. Chamar `warrior.getCalculatedStamina()` (ou método equivalente no serviço).
3. Verificar o valor retornado.

**Resultado Esperado:**
- Valor retornado == 50 (ou aproximadamente 50, com tolerância de ±1).
- Valor não ultrapassa 100 mesmo se decorrido mais de 120 minutos.

**Resultado de Falha:** Valor incorreto, negativo ou acima de 100.

---

### TC-006 — getCalculatedHpPercent retorna valor correto após regen passiva

**Tipo:** Unitário
**UC Relacionado:** UC-06
**Prioridade:** Alta

**Cenário:** Verificar que o HP calculado com base em snapshot + tempo decorrido reflete a regen passiva correta. HP regenera 100% em 60 minutos.

**Pré-condições:** `Warrior` com `currentHpSnapshot=0` e `hpUpdatedAt` = 30 minutos atrás.

**Dados de Entrada:**
```
currentHpSnapshot = 0
hpUpdatedAt = agora - 30 minutos
// Regen: 100% em 60 min → 1,667%/min → 30 min × 1,667 = 50%
```

**Passos:**
1. Criar `Warrior` com HP snapshot zerado e timestamp 30 minutos atrás.
2. Chamar `warrior.getCalculatedHpPercent()`.
3. Verificar o valor retornado.

**Resultado Esperado:**
- Valor retornado == 50 (tolerância ±1).
- Valor não ultrapassa 100 independentemente do tempo decorrido.

**Resultado de Falha:** HP acima de 100%, abaixo de 0% ou cálculo de regen incorreto.

---

### TC-007 — getCalculatedHpPercent não ultrapassa 100% após longo período

**Tipo:** Unitário
**UC Relacionado:** UC-06
**Prioridade:** Média

**Cenário:** Verificar que o HP calculado está limitado a 100% mesmo quando o tempo decorrido desde o snapshot excede 60 minutos (tempo de regen total).

**Pré-condições:** `Warrior` com `currentHpSnapshot=50` e `hpUpdatedAt` = 2 horas atrás.

**Dados de Entrada:**
```
currentHpSnapshot = 50
hpUpdatedAt = agora - 120 minutos
// Regen de 50% → completaria em 30 min → há 90 min excedentes → ainda 100%
```

**Passos:**
1. Criar `Warrior` com snapshot de 50% e timestamp 2 horas atrás.
2. Chamar `warrior.getCalculatedHpPercent()`.

**Resultado Esperado:**
- Valor retornado == 100 (limitado pelo teto).

**Resultado de Falha:** Valor acima de 100%.

---

### TC-008 — applyDamagePercent define snapshot correto de HP

**Tipo:** Unitário
**UC Relacionado:** UC-06, UC-16, UC-18
**Prioridade:** Alta

**Cenário:** Verificar que ao aplicar dano percentual ao guerreiro, o `currentHpSnapshot` é salvo corretamente com o valor resultante e o timestamp é atualizado.

**Pré-condições:** `Warrior` com HP atual calculado em 80%.

**Dados de Entrada:**
```
applyDamagePercent(30) // reduz 30% do HP
// 80% - 30% = 50%
```

**Passos:**
1. Criar `Warrior` com HP calculado em 80%.
2. Chamar `warrior.applyDamagePercent(30)` (ou método equivalente).
3. Verificar `currentHpSnapshot` e que `hpUpdatedAt` foi atualizado para o momento atual.

**Resultado Esperado:**
- `warrior.getCurrentHpSnapshot()` == 50.
- `warrior.getHpUpdatedAt()` == timestamp próximo ao `Instant.now()`.

**Resultado de Falha:** Snapshot incorreto ou timestamp não atualizado.

---

### TC-009 — rollDrop respeita bônus de sorte do guerreiro

**Tipo:** Unitário
**UC Relacionado:** UC-13, UC-14
**Prioridade:** Alta

**Cenário:** Verificar que o método de drop de item da missão considera o bônus de sorte do guerreiro ao calcular a chance final de drop.

**Pré-condições:** Mock de `Random` ou uso de seed determinístico. `QuestService` instanciado com mocks de dependências. Missão do tipo PATROL (chance base 10%).

**Dados de Entrada:**
```
questType = PATROL (dropChance = 10%)
warrior.luckBonus = 10 (10 pontos de Sorte = +10% drop)
// Chance final = 20%
```

**Passos:**
1. Instanciar `QuestService` com mocks.
2. Configurar guerreiro com 10 pontos de Sorte.
3. Com seed que produza valor 15 (< 20%), chamar `rollDrop(warrior, PATROL)`.
4. Com seed que produza valor 25 (≥ 20%), chamar `rollDrop(warrior, PATROL)`.

**Resultado Esperado:**
- Chamada com valor 15: retorna `true` (drop ocorre).
- Chamada com valor 25: retorna `false` (drop não ocorre).

**Resultado de Falha:** Bônus de sorte ignorado; resultado inconsistente com a chance calculada.

---

### TC-010 — collectReward concede bronze e XP corretos por tipo de missão

**Tipo:** Unitário
**UC Relacionado:** UC-14
**Prioridade:** Alta

**Cenário:** Verificar que ao coletar recompensa de uma missão concluída, os valores de bronze e XP creditados correspondem exatamente ao tipo da missão.

**Pré-condições:** Mocks de `PlayerRepository` e `WarriorRepository`. Missão do tipo BOSS_HUNT já concluída.

**Dados de Entrada:**
```
questType = BOSS_HUNT
bronze esperado = 1000
XP esperado = 750
```

**Passos:**
1. Instanciar `QuestService` com mocks.
2. Criar sessão de missão BOSS_HUNT com status concluído.
3. Chamar `questService.collectReward(questSession, warrior, player)`.
4. Verificar que `player.addBronzeAmount(1000)` foi chamado.
5. Verificar que XP do guerreiro aumentou em 750.

**Resultado Esperado:**
- `player.addBronzeAmount` invocado com argumento 1000.
- `warrior.setExperience(xpAnterior + 750)` refletido após a coleta.

**Resultado de Falha:** Valores de bronze ou XP diferentes dos definidos para o tipo de missão.

---

### TC-011 — generateItem produz item com raridade correta por tipo de missão

**Tipo:** Unitário
**UC Relacionado:** UC-14
**Prioridade:** Média

**Cenário:** Verificar que o item gerado ao dropar em uma missão RAID tem raridade dentro dos pools permitidos (Incomum ou Raro).

**Pré-condições:** `QuestService` instanciado. Tipo de missão RAID.

**Dados de Entrada:**
```
questType = RAID
raridades permitidas = [UNCOMMON, RARE]
```

**Passos:**
1. Chamar `generateItem(RAID)` 20 vezes com seeds variados.
2. Verificar a raridade de cada item gerado.

**Resultado Esperado:**
- Todos os itens gerados possuem raridade `UNCOMMON` ou `RARE`.
- Nenhum item com raridade `COMMON` ou `EPIC` é gerado para RAID.

**Resultado de Falha:** Item com raridade fora do pool permitido para o tipo de missão.

---

### TC-012 — Arena: BattleSimulator determina corretamente o vencedor

**Tipo:** Unitário
**UC Relacionado:** UC-16, UC-17
**Prioridade:** Alta

**Cenário:** Verificar que o `BattleSimulator.simulate()` sempre produz um vencedor válido e que a última linha do log contém a tag `WINNER:` com o nome correto.

**Pré-condições:** Dois combatentes com stats fixos e determinísticos.

**Dados de Entrada:**
```
Combatente A: ATK=100, DEF=5, HP=110, evasion=0
Combatente B: ATK=5, DEF=5, HP=110, evasion=0
// A deve vencer com ampla vantagem de ATK
```

**Passos:**
1. Instanciar `BattleSimulator`.
2. Chamar `simulate(combatenteA, combatenteB)`.
3. Verificar que a lista de strings retornada não está vazia.
4. Verificar que a última linha contém `"WINNER:"`.
5. Verificar que o nome após `"WINNER:"` é o nome de A.

**Resultado Esperado:**
- Lista de log não vazia.
- Última linha começa com `"WINNER:"` seguido do nome do combatente A.
- Pelo menos uma linha de log de ataque está presente.

**Resultado de Falha:** Log vazio, sem tag `WINNER:`, ou vencedor incorreto dadas as stats.

---

### TC-013 — Arena: rank points aplicados corretamente em vitória

**Tipo:** Unitário
**UC Relacionado:** UC-17
**Prioridade:** Alta

**Cenário:** Verificar que ao processar um resultado de arena como vitória, o jogador recebe exatamente +25 rank points e +200 bronze.

**Pré-condições:** `ArenaService` com mocks. Jogador com `rankPoints=100` e `bronze=0`.

**Dados de Entrada:**
```
resultado = VITÓRIA
rankPoints inicial = 100
bronze inicial = 0
```

**Passos:**
1. Instanciar `ArenaService` com mocks.
2. Processar resultado de vitória para o jogador.
3. Verificar `rankPoints` e bronze do jogador.

**Resultado Esperado:**
- `player.getRankPoints()` == 125.
- `player.addBronzeAmount(200)` foi invocado.

**Resultado de Falha:** Rank points ou bronze não atualizados corretamente.

---

### TC-014 — Arena: rank points e HP aplicados corretamente em derrota

**Tipo:** Unitário
**UC Relacionado:** UC-17
**Prioridade:** Alta

**Cenário:** Verificar que em derrota na arena o jogador perde 15 rank points, recebe 50 bronze de consolação, HP do guerreiro vai a 0 e buff é removido.

**Pré-condições:** `ArenaService` com mocks. Jogador com `rankPoints=100`. Guerreiro com HP=80 e buff ativo.

**Dados de Entrada:**
```
resultado = DERROTA
rankPoints inicial = 100
HP guerreiro = 80%
buff ativo = STRENGTH
```

**Passos:**
1. Instanciar `ArenaService` com mocks.
2. Processar resultado de derrota.
3. Verificar rank points, bronze creditado, HP e buff do guerreiro.

**Resultado Esperado:**
- `player.getRankPoints()` == 85.
- Bronze: `addBronzeAmount(50)` invocado.
- `warrior.getCurrentHpSnapshot()` == 0.
- `warrior.getActiveBuff()` == null.

**Resultado de Falha:** Qualquer campo não atualizado conforme esperado.

---

### TC-015 — TowerService: bossForFloor retorna stats corretos para andares iniciais

**Tipo:** Unitário
**UC Relacionado:** UC-18
**Prioridade:** Alta

**Cenário:** Verificar que `TowerService.bossForFloor(floor)` retorna o chefe correto com nome e stats esperados para andares 1, 2 e 3 (Esqueleto, Goblin, Rato Gigante).

**Pré-condições:** `TowerService` instanciado sem dependências externas (lógica pura em memória).

**Dados de Entrada:**
```
floor = 1 → Esqueleto
floor = 2 → Goblin
floor = 3 → Rato Gigante
```

**Passos:**
1. Chamar `towerService.bossForFloor(1)`.
2. Verificar nome e stats.
3. Repetir para andares 2 e 3.

**Resultado Esperado:**
- Andar 1: chefe nomeado "Esqueleto" com stats proporcionais ao andar.
- Andar 2: chefe nomeado "Goblin".
- Andar 3: chefe nomeado "Rato Gigante".
- Stats (ATK, DEF, HP) crescem com o número do andar.

**Resultado de Falha:** Nome incorreto, stats zerados ou exceção lançada.

---

### TC-016 — TowerService: bossForFloor retorna chefe escalado para andares avançados

**Tipo:** Unitário
**UC Relacionado:** UC-18
**Prioridade:** Média

**Cenário:** Verificar que para andares 16+ os chefes (Dragão, Titan, Lich Ancião, Guardiões Lendários) aparecem com stats significativamente maiores do que os andares iniciais.

**Pré-condições:** `TowerService` instanciado.

**Dados de Entrada:**
```
floor = 16, 17, 18, 19
```

**Passos:**
1. Chamar `towerService.bossForFloor(16)` até `bossForFloor(19)`.
2. Verificar que os nomes correspondem aos chefes lendários.
3. Comparar HP do andar 16 com HP do andar 1.

**Resultado Esperado:**
- Andar 16 possui chefe do grupo lendário.
- Stats do andar 16 são substancialmente maiores que os do andar 1 (ex.: HP > HP_andar1 × 10).

**Resultado de Falha:** Stats idênticos entre andares baixos e altos, ou chefe incorreto.

---

### TC-017 — WorkService: goldReward calcula corretamente com bônus de nível de profissão

**Tipo:** Unitário
**UC Relacionado:** UC-21
**Prioridade:** Alta

**Cenário:** Verificar que `WorkService.calculateGoldReward(job, hours, profLevel)` aplica corretamente o bônus de +5% por nível de profissão acima do primeiro.

**Pré-condições:** `WorkService` instanciado com mocks. Emprego "Guarda da Nobreza" (65 bronze/h).

**Dados de Entrada:**
```
job = NOBLE_GUARD (65 bronze/h)
hours = 4
profLevel = 3
// Bônus = (3-1) × 5% = 10%
// Recompensa = 65 × 4 × 1.10 = 286 bronze
```

**Passos:**
1. Chamar `workService.calculateGoldReward(NOBLE_GUARD, 4, 3)`.
2. Verificar o valor retornado.

**Resultado Esperado:**
- Retorno == 286 bronze.

**Resultado de Falha:** Valor sem bônus (260) ou com bônus calculado incorretamente.

---

### TC-018 — WorkService: cancelWork calcula recompensa proporcional corretamente

**Tipo:** Unitário
**UC Relacionado:** UC-22
**Prioridade:** Alta

**Cenário:** Verificar que ao cancelar um trabalho com horas parcialmente completadas, a recompensa é calculada apenas sobre as horas inteiras concluídas.

**Pré-condições:** `WorkService` instanciado com mocks. Sessão de trabalho iniciada há 2h30min, emprego de 15 bronze/h, profLevel=1.

**Dados de Entrada:**
```
job = TAVERN_HELPER (15 bronze/h)
elapsedTime = 2h30min
profLevel = 1 (sem bônus)
// Horas inteiras = 2 → recompensa = 15 × 2 = 30 bronze
// 30min restantes são perdidos
```

**Passos:**
1. Criar sessão de trabalho com timestamp 2h30min atrás.
2. Chamar `workService.cancelWork(session, player)`.
3. Verificar o valor de bronze creditado.

**Resultado Esperado:**
- `player.addBronzeAmount(30)` invocado.
- Horas fracionadas (30 min) não são remuneradas.

**Resultado de Falha:** Bronze calculado com fração de hora (ex.: 37,5) ou nenhum bronze creditado.

---

### TC-019 — WorkService: cancelWork com 0 horas completas retorna 0 bronze

**Tipo:** Unitário
**UC Relacionado:** UC-22
**Prioridade:** Média

**Cenário:** Verificar que cancelar um trabalho antes de completar a primeira hora não concede nenhum bronze.

**Pré-condições:** Sessão de trabalho iniciada há 45 minutos.

**Dados de Entrada:**
```
elapsedTime = 45 minutos (< 1 hora completa)
```

**Passos:**
1. Criar sessão com timestamp 45 minutos atrás.
2. Chamar `workService.cancelWork(session, player)`.

**Resultado Esperado:**
- Nenhuma chamada a `player.addBronzeAmount()` com valor positivo.
- `goldEarned` == 0 no resultado retornado.

**Resultado de Falha:** Bronze creditado por fração de hora.

---

### TC-020 — GatheringService: rollFish retorna tipo correto conforme nível de pesca

**Tipo:** Unitário
**UC Relacionado:** UC-28, UC-29
**Prioridade:** Média

**Cenário:** Verificar que `GatheringService.rollFish(fishingLevel)` retorna tipos de peixes compatíveis com o nível de pesca do jogador (peixes raros só disponíveis em níveis mais altos).

**Pré-condições:** `GatheringService` instanciado. Nível de pesca = 1.

**Dados de Entrada:**
```
fishingLevel = 1
// Nível 1 só deve produzir peixes básicos (SMALL_FISH, SALMON)
// SHARK e LEGENDARY_FISH não devem aparecer em nível 1
```

**Passos:**
1. Chamar `gatheringService.rollFish(1)` 50 vezes.
2. Verificar que nenhum resultado é SHARK ou LEGENDARY_FISH.
3. Repetir com `fishingLevel=50` e verificar que SHARK e LEGENDARY_FISH podem aparecer.

**Resultado Esperado:**
- Nível 1: apenas SMALL_FISH e/ou SALMON.
- Nível alto: pool inclui SHARK e LEGENDARY_FISH.

**Resultado de Falha:** Peixe de nível alto aparece com nível de pesca 1.

---

### TC-021 — GatheringService: consumeFish restaura stamina correta por tipo de peixe

**Tipo:** Unitário
**UC Relacionado:** UC-31
**Prioridade:** Alta

**Cenário:** Verificar que cada tipo de peixe restaura a quantidade correta de stamina conforme definido nas regras de negócio.

**Pré-condições:** `GatheringService` instanciado com mock de `WarriorRepository`. Guerreiro com stamina em 0%.

**Dados de Entrada:**
```
SMALL_FISH → +10% stamina
SALMON → +25% stamina
TUNA → +40% stamina
SHARK → +60% stamina
LEGENDARY_FISH → +80% stamina
```

**Passos:**
1. Para cada tipo de peixe, criar guerreiro com stamina=0.
2. Chamar `gatheringService.consumeFish(warrior, fishType)`.
3. Verificar stamina resultante.

**Resultado Esperado:**
- SMALL_FISH: stamina == 10.
- SALMON: stamina == 25.
- TUNA: stamina == 40.
- SHARK: stamina == 60.
- LEGENDARY_FISH: stamina == 80.

**Resultado de Falha:** Qualquer valor de stamina incorreto para qualquer tipo de peixe.

---

### TC-022 — GatheringService: consumeFish limita stamina a 100%

**Tipo:** Unitário
**UC Relacionado:** UC-31
**Prioridade:** Média

**Cenário:** Verificar que consumir um peixe quando a stamina está em 70% e o peixe restaura 80% não ultrapassa 100%.

**Pré-condições:** Guerreiro com stamina atual = 70%.

**Dados de Entrada:**
```
stamina atual = 70%
LEGENDARY_FISH → +80% stamina
// 70 + 80 = 150 → limitado a 100
```

**Passos:**
1. Criar guerreiro com stamina=70%.
2. Chamar `gatheringService.consumeFish(warrior, LEGENDARY_FISH)`.
3. Verificar stamina resultante.

**Resultado Esperado:**
- `warrior.getStaminaSnapshot()` == 100 (não 150).

**Resultado de Falha:** Stamina acima de 100%.

---

### TC-023 — SmithingService: GemBonus.of retorna bônus correto por tipo de joia

**Tipo:** Unitário
**UC Relacionado:** UC-38
**Prioridade:** Alta

**Cenário:** Verificar que `GemBonus.of(gemType)` retorna os bônus de atributo corretos para cada tipo de joia.

**Pré-condições:** Nenhuma dependência externa.

**Dados de Entrada:**
```
RUBY → +5 ATK
SAPPHIRE → +5 DEF
EMERALD → +20 HP
DIAMOND → +3 ATK, +3 DEF, +10 HP
AMETHYST → +5% drop chance
```

**Passos:**
1. Chamar `GemBonus.of(GemType.RUBY)`.
2. Verificar `bonusAtk`, `bonusDef`, `bonusHp`.
3. Repetir para todos os tipos.

**Resultado Esperado:**
- RUBY: `bonusAtk=5`, `bonusDef=0`, `bonusHp=0`.
- SAPPHIRE: `bonusAtk=0`, `bonusDef=5`, `bonusHp=0`.
- EMERALD: `bonusAtk=0`, `bonusDef=0`, `bonusHp=20`.
- DIAMOND: `bonusAtk=3`, `bonusDef=3`, `bonusHp=10`.
- AMETHYST: `dropBonus=5%`.

**Resultado de Falha:** Qualquer bônus retornado com valor incorreto.

---

### TC-024 — SmithingService: totalGemBonus soma corretamente múltiplas joias

**Tipo:** Unitário
**UC Relacionado:** UC-38
**Prioridade:** Alta

**Cenário:** Verificar que a soma dos bônus de múltiplas joias encaixadas em um item está correta.

**Pré-condições:** Item com 3 sockets preenchidos: RUBY, RUBY, DIAMOND.

**Dados de Entrada:**
```
sockets = [RUBY (+5 ATK), RUBY (+5 ATK), DIAMOND (+3 ATK, +3 DEF, +10 HP)]
total esperado: ATK = 5+5+3 = 13, DEF = 3, HP = 10
```

**Passos:**
1. Criar item com 3 gems encaixadas: 2 RUBY e 1 DIAMOND.
2. Chamar `smithingService.totalGemBonus(item)`.
3. Verificar os totais de ATK, DEF e HP.

**Resultado Esperado:**
- `totalAtk` == 13.
- `totalDef` == 3.
- `totalHp` == 10.

**Resultado de Falha:** Soma incorreta em qualquer atributo.

---

### TC-025 — ZoneService: chance de encontro NPC calculada corretamente por zona

**Tipo:** Unitário
**UC Relacionado:** UC-39, UC-41
**Prioridade:** Alta

**Cenário:** Verificar que `ZoneService.calculateEncounterChance(zone, elapsedHours)` calcula corretamente a probabilidade de encontro de NPC por hora para cada zona.

**Pré-condições:** `ZoneService` instanciado com mocks.

**Dados de Entrada:**
```
SAFE_ZONE: 15% NPC/h → 1h → 15% chance
PVP_ZONE: 25% NPC/h → 2h → 50% acumulado (ou por evento independente)
HIGH_RISK_ZONE: 35% NPC/h → 1h → 35% chance
```

**Passos:**
1. Para cada zona, chamar `calculateEncounterChance(zone, 1)`.
2. Verificar o percentual retornado.

**Resultado Esperado:**
- SAFE_ZONE, 1h: 15%.
- PVP_ZONE, 1h: 25%.
- HIGH_RISK_ZONE, 1h: 35%.

**Resultado de Falha:** Percentuais trocados entre zonas ou incorretos.

---

### TC-026 — ZoneService: penalidade de derrota desconta 15% do bronze

**Tipo:** Unitário
**UC Relacionado:** UC-39, UC-41
**Prioridade:** Alta

**Cenário:** Verificar que ao calcular a penalidade de derrota em zona, exatamente 15% do bronze total do jogador é descontado.

**Pré-condições:** `ZoneService` com mocks. `Player` com `bronze=0`, `silver=2`, `gold=0` (total = 200 bronze).

**Dados de Entrada:**
```
totalBronze = 200
penalidade = 15% → 30 bronze
```

**Passos:**
1. Criar player com 200 bronze total.
2. Chamar `zoneService.applyDefeatPenalty(player, warrior)`.
3. Verificar que `playerService.spendBronze(player, 30)` foi chamado.

**Resultado Esperado:**
- Bronze descontado = 30 (15% de 200).
- HP do guerreiro = 0 após penalidade.
- Buff removido.

**Resultado de Falha:** Percentual diferente de 15% ou HP/buff não atualizados.

---

### TC-027 — TempleService: healCost retorna 0 para guerreiro de nível ≤ 10

**Tipo:** Unitário
**UC Relacionado:** UC-42
**Prioridade:** Alta

**Cenário:** Verificar que `TempleService.healCost(warrior)` retorna 0 para guerreiros de nível 1 a 10.

**Pré-condições:** `TempleService` instanciado.

**Dados de Entrada:**
```
warrior.level = 1, 5, 10 → custo = 0
```

**Passos:**
1. Para níveis 1, 5 e 10, chamar `templeService.healCost(warrior)`.
2. Verificar que todos retornam 0.

**Resultado Esperado:**
- `healCost` == 0 para todos os níveis ≤ 10.

**Resultado de Falha:** Custo diferente de 0 para qualquer nível no intervalo 1-10.

---

### TC-028 — TempleService: healCost retorna 100 bronze para guerreiro de nível > 10

**Tipo:** Unitário
**UC Relacionado:** UC-42
**Prioridade:** Alta

**Cenário:** Verificar que `TempleService.healCost(warrior)` retorna 100 (1 prata = 100 bronze) para guerreiros de nível 11 ou superior.

**Pré-condições:** `TempleService` instanciado.

**Dados de Entrada:**
```
warrior.level = 11, 20, 50 → custo = 100 bronze
```

**Passos:**
1. Para níveis 11, 20 e 50, chamar `templeService.healCost(warrior)`.
2. Verificar que todos retornam 100.

**Resultado Esperado:**
- `healCost` == 100 para todos os níveis > 10.

**Resultado de Falha:** Custo diferente de 100 ou custo escalado com o nível (não deveria).

---

### TC-029 — TempleService: applyBuff aplica buff e registra timestamp de expiração

**Tipo:** Unitário
**UC Relacionado:** UC-07, UC-43
**Prioridade:** Alta

**Cenário:** Verificar que ao aplicar um buff, o guerreiro recebe o buff correto com duração de 1 hora e o efeito correspondente é atribuído.

**Pré-condições:** `TempleService` com mocks. Guerreiro sem buff ativo. Player com 50 bronze.

**Dados de Entrada:**
```
buffType = STRENGTH (+5 ATK)
custo = 30 bronze
duracao = 1 hora
```

**Passos:**
1. Chamar `templeService.applyBuff(warrior, player, STRENGTH)`.
2. Verificar `warrior.getActiveBuff()` == STRENGTH.
3. Verificar `warrior.getBuffExpiresAt()` ≈ `Instant.now().plusSeconds(3600)`.
4. Verificar que `playerService.spendBronze(player, 30)` foi chamado.

**Resultado Esperado:**
- Buff ativo == STRENGTH.
- Expiração ~1 hora no futuro.
- Bronze debitado corretamente.

**Resultado de Falha:** Buff não atribuído, expiração incorreta ou custo não debitado.

---

### TC-030 — BattleSimulator: tag WINNER sempre presente na última linha do log

**Tipo:** Unitário
**UC Relacionado:** UC-16, UC-17, UC-18
**Prioridade:** Alta

**Cenário:** Verificar que independentemente do resultado do combate, a última linha do log retornado por `BattleSimulator.simulate()` contém a tag `WINNER:NomeDoCombatente`.

**Pré-condições:** `BattleSimulator` instanciado.

**Dados de Entrada:**
```
Simular 10 combates com stats variados
```

**Passos:**
1. Executar `battleSimulator.simulate()` com 10 pares diferentes de combatentes.
2. Para cada resultado, verificar a última linha da lista.

**Resultado Esperado:**
- Última linha sempre no formato `"WINNER:NomeDoCombatente"`.
- Nome referencia um dos dois combatentes (nunca texto livre ou vazio).

**Resultado de Falha:** Qualquer execução sem a tag `WINNER:` na última linha.

---

### TC-031 — BattleSimulator: vencedor correto baseado em ATK/DEF/HP

**Tipo:** Unitário
**UC Relacionado:** UC-16, UC-17, UC-18
**Prioridade:** Alta

**Cenário:** Com combatentes com diferença extrema de stats, verificar que o vencedor sempre é o combatente superior (sem aleatoriedade que inverta o resultado esperado).

**Pré-condições:** Dois combatentes com stats extremamente assimétricos.

**Dados de Entrada:**
```
Herói: ATK=1000, DEF=1000, HP=10000, evasion=0
Slime: ATK=1, DEF=0, HP=1, evasion=0
```

**Passos:**
1. Simular 20 combates entre Herói e Slime.
2. Verificar o vencedor em cada combate.

**Resultado Esperado:**
- Herói vence em 100% dos combates (20/20).

**Resultado de Falha:** Slime vence em qualquer das 20 simulações.

---

### TC-032 — ItemLoreGenerator: retorna lore não vazio para todas as combinações de raridade e tipo

**Tipo:** Unitário
**UC Relacionado:** UC-14, UC-27, UC-36
**Prioridade:** Alta

**Cenário:** Verificar que `ItemLoreGenerator` retorna texto de lore não vazio e não nulo para todas as combinações válidas de raridade (4 tiers) e categoria de item (2 categorias = arma e armadura).

**Pré-condições:** `ItemLoreGenerator` instanciado (sem dependências externas — lógica em memória).

**Dados de Entrada:**
```
raridades = [COMMON, UNCOMMON, RARE, EPIC]
categorias = [WEAPON, ARMOR]
// 4 × 2 = 8 combinações
```

**Passos:**
1. Para cada combinação de raridade e categoria, chamar `itemLoreGenerator.generateLore(rarity, category)`.
2. Verificar que o resultado não é null e não é string vazia.

**Resultado Esperado:**
- 8/8 combinações retornam string com pelo menos 10 caracteres.
- Nenhum retorno null ou vazio.

**Resultado de Falha:** Qualquer combinação retorna null, string vazia ou lança exceção.

---

### TC-033 — ItemLoreGenerator: retorna origem correta por fonte do item

**Tipo:** Unitário
**UC Relacionado:** UC-14, UC-27, UC-36
**Prioridade:** Média

**Cenário:** Verificar que o campo `origin` gerado pelo `ItemLoreGenerator` corresponde corretamente à fonte do item.

**Pré-condições:** `ItemLoreGenerator` instanciado.

**Dados de Entrada:**
```
fonte = QUEST (Caça ao Chefe) → "Encontrado durante: Caça ao Chefe."
fonte = SHOP → "Adquirido no Comércio de Mercador Viajante."
fonte = FORGE → "Forjado pelo próprio guerreiro."
fonte = INITIAL → "Equipamento inicial da guilda."
fonte = DROP → "Obtido após derrotar inimigo."
```

**Passos:**
1. Para cada fonte, chamar `generateOrigin(source, questType?)`.
2. Verificar que a string retornada corresponde ao texto esperado.

**Resultado Esperado:**
- Cada fonte produz o texto de origem correto.

**Resultado de Falha:** Texto de origem incorreto ou trocado entre fontes.

---

### TC-034 — SmithingService: receita de refino inválida lança exceção

**Tipo:** Unitário
**UC Relacionado:** UC-35
**Prioridade:** Média

**Cenário:** Verificar que tentar refinar com nível de Smithing insuficiente para o tipo de minério resulta em exceção de validação.

**Pré-condições:** `SmithingService` com mocks. Jogador com Smithing nível 1 tentando refinar Mithril (requer nível alto).

**Dados de Entrada:**
```
mineral = MITHRIL
smithingLevel = 1
```

**Passos:**
1. Chamar `smithingService.refine(player, MITHRIL, quantidade)`.
2. Capturar exceção.

**Resultado Esperado:**
- Exceção lançada indicando nível de Smithing insuficiente.
- Recursos do jogador não são alterados.

**Resultado de Falha:** Refino executado sem nível mínimo.

---

### TC-035 — PlayerService: warrior não pode iniciar atividade com HP=0

**Tipo:** Unitário
**UC Relacionado:** UC-13, UC-16, UC-18
**Prioridade:** Alta

**Cenário:** Verificar que qualquer serviço que inicia atividade de combate valida HP > 0 e rejeita guerreiro inconsciente.

**Pré-condições:** `QuestService`/`ArenaService`/`TowerService` com mocks. Guerreiro com `currentHpSnapshot=0`.

**Dados de Entrada:**
```
warrior.currentHpSnapshot = 0
tentativa de iniciar = qualquer atividade de combate
```

**Passos:**
1. Tentar `questService.startQuest(warrior, PATROL)` com HP=0.
2. Capturar exceção.

**Resultado Esperado:**
- Exceção lançada com mensagem indicando que o guerreiro está inconsciente.
- `warrior.isOnMission()` permanece false.

**Resultado de Falha:** Atividade iniciada com HP=0.

---

---

## Seção 2 — Testes de Integração / E2E

---

### TC-051 — Registro com dados válidos cria conta e guerreiro

**Tipo:** Integração
**UC Relacionado:** UC-01
**Prioridade:** Alta

**Cenário:** Registrar uma nova conta com dados válidos deve criar o jogador, o guerreiro associado e retornar 201 com token JWT.

**Pré-condições:** Banco H2 limpo. Nenhum usuário com o username "testworrior" cadastrado.

**Dados de Entrada:**
```json
POST /api/auth/register
{
  "username": "testworrior",
  "email": "test@example.com",
  "password": "senha123",
  "warriorName": "Kael"
}
```

**Passos:**
1. Enviar `POST /api/auth/register` com payload acima.
2. Verificar status HTTP.
3. Verificar corpo da resposta.
4. Chamar `GET /api/warrior` com token recebido.

**Resultado Esperado:**
- Status 201.
- Corpo contém `token` (string JWT não vazia).
- `GET /api/warrior` retorna guerreiro com `name="Kael"`, `atk=15`, `def=12`, `level=1`.
- Jogador possui 5000 bronze inicial (= 50 prata).

**Resultado de Falha:** Status diferente de 201, token ausente, guerreiro não criado ou stats incorretos.

---

### TC-052 — Registro com username duplicado retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-01
**Prioridade:** Alta

**Cenário:** Tentar registrar um segundo usuário com o mesmo username deve ser rejeitado com erro 400.

**Pré-condições:** Usuário "player1" já cadastrado no banco.

**Dados de Entrada:**
```json
POST /api/auth/register
{
  "username": "player1",
  "email": "outro@example.com",
  "password": "senha123",
  "warriorName": "Duplicado"
}
```

**Passos:**
1. Criar usuário "player1" via registro prévio.
2. Enviar segundo `POST /api/auth/register` com mesmo username.

**Resultado Esperado:**
- Status 400.
- Mensagem de erro indicando username já em uso.
- Nenhuma conta duplicada criada no banco.

**Resultado de Falha:** Status 201 (conta duplicada criada) ou status 500 (erro interno não tratado).

---

### TC-053 — Login com senha incorreta retorna 401

**Tipo:** Integração
**UC Relacionado:** UC-02
**Prioridade:** Alta

**Cenário:** Tentar fazer login com credenciais inválidas deve retornar 401 sem revelar qual campo está errado.

**Pré-condições:** Usuário "player1" cadastrado com senha "correta123".

**Dados de Entrada:**
```json
POST /api/auth/login
{
  "username": "player1",
  "password": "senhaerrada"
}
```

**Passos:**
1. Enviar `POST /api/auth/login` com senha incorreta.

**Resultado Esperado:**
- Status 401.
- Corpo NÃO revela se o username existe (não diz "senha incorreta" especificamente).
- Nenhum token retornado.

**Resultado de Falha:** Status 200 com token, status 400 (confusão de erros), ou mensagem revelando qual campo está errado.

---

### TC-054 — Recuperação de senha retorna 200 mesmo para email inexistente

**Tipo:** Integração
**UC Relacionado:** UC-03
**Prioridade:** Alta

**Cenário:** O endpoint de forgot-password deve sempre retornar 200 (mesmo que o email não exista), por segurança, sem revelar se o email está cadastrado.

**Pré-condições:** Banco pode ou não ter o email informado.

**Dados de Entrada:**
```json
POST /api/auth/forgot-password
{
  "email": "naoexiste@example.com"
}
```

**Passos:**
1. Enviar `POST /api/auth/forgot-password` com email não cadastrado.

**Resultado Esperado:**
- Status 200.
- Mensagem genérica como "Se o email existir, um link será enviado.".
- Nenhum token de reset criado no banco para email inexistente.

**Resultado de Falha:** Status 404 ou mensagem revelando que o email não existe.

---

### TC-055 — GET /api/warrior retorna dados completos do guerreiro

**Tipo:** Integração
**UC Relacionado:** UC-05, UC-06
**Prioridade:** Alta

**Cenário:** Após autenticação, `GET /api/warrior` deve retornar o estado completo do guerreiro, incluindo HP calculado, stamina calculada, buff ativo e campos de moeda normalizados.

**Pré-condições:** Usuário autenticado com guerreiro criado.

**Dados de Entrada:**
```
GET /api/warrior
Authorization: Bearer <token>
```

**Passos:**
1. Autenticar usuário.
2. Enviar `GET /api/warrior`.

**Resultado Esperado:**
- Status 200.
- Corpo contém: `name`, `level`, `atk`, `def`, `hp`, `currentHpPercent`, `stamina`, `activeBuff`, `bronze`, `silver`, `gold`, `availablePoints`.
- `currentHpPercent` entre 0 e 100.
- `stamina` entre 0 e 100.

**Resultado de Falha:** Status 401, 500, campos ausentes ou HP/stamina fora do intervalo 0-100.

---

### TC-056 — Distribuir ponto em STRENGTH reduz availablePoints e aumenta ATK

**Tipo:** Integração
**UC Relacionado:** UC-05
**Prioridade:** Alta

**Cenário:** Ao distribuir 1 ponto de atributo em STRENGTH, `availablePoints` deve diminuir em 1 e `atk` deve aumentar em 1.

**Pré-condições:** Usuário autenticado. Guerreiro com `availablePoints >= 1` (recém-criado possui 0; usar guerreiro que subiu de nível ou ajustar via setup).

**Dados de Entrada:**
```json
POST /api/warrior/attributes/STRENGTH
Authorization: Bearer <token>
{}
```

**Passos:**
1. Registrar novo usuário (começa com 0 pontos de nível 1).
2. Subir de nível via coleta de XP ou ajustar dados iniciais para ter `availablePoints=5`.
3. Anotar `atk` atual e `availablePoints`.
4. Enviar `POST /api/warrior/attributes/STRENGTH`.
5. Verificar guerreiro atualizado.

**Resultado Esperado:**
- Status 200.
- `atk` == atk_anterior + 1.
- `availablePoints` == pontos_anteriores - 1.

**Resultado de Falha:** ATK não aumentado, pontos não decrementados ou status 400/500.

---

### TC-057 — Distribuir 0 pontos de atributo retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-05
**Prioridade:** Média

**Cenário:** Tentar distribuir 0 pontos (ou valor inválido) deve retornar 400 com mensagem de erro.

**Pré-condições:** Usuário autenticado com `availablePoints >= 1`.

**Dados de Entrada:**
```json
POST /api/warrior/attributes/STRENGTH
{ "points": 0 }
```

**Passos:**
1. Enviar requisição com quantidade de pontos = 0.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando valor inválido.
- ATK e availablePoints não alterados.

**Resultado de Falha:** Status 200 ou ATK alterado com entrada inválida.

---

### TC-058 — POST /api/warrior/free libera guerreiro travado

**Tipo:** Integração
**UC Relacionado:** UC-08
**Prioridade:** Alta

**Cenário:** Ao chamar o endpoint de liberação, o guerreiro deve ter `onMission=false` mesmo que havia sessões ativas.

**Pré-condições:** Usuário autenticado. Guerreiro com `onMission=true` (iniciado em missão).

**Dados de Entrada:**
```
POST /api/warrior/free
Authorization: Bearer <token>
```

**Passos:**
1. Iniciar uma missão para travar o guerreiro.
2. Enviar `POST /api/warrior/free`.
3. Verificar estado do guerreiro.

**Resultado Esperado:**
- Status 200.
- `GET /api/warrior` retorna `onMission=false`.

**Resultado de Falha:** Guerreiro continua travado ou status 500.

---

### TC-059 — GET /api/quests/types retorna 4 tipos de missão com staminaCost

**Tipo:** Integração
**UC Relacionado:** UC-13
**Prioridade:** Alta

**Cenário:** O endpoint deve listar os 4 tipos de missão disponíveis com suas características completas, incluindo custo de stamina.

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/quests/types
Authorization: Bearer <token>
```

**Passos:**
1. Enviar `GET /api/quests/types`.

**Resultado Esperado:**
- Status 200.
- Array com exatamente 4 itens.
- Cada item contém: `type`, `durationMinutes`, `bronzeReward`, `xpReward`, `staminaCost`, `dropChance`.
- PATROL: `staminaCost=10`, DUNGEON: `staminaCost=20`, RAID: `staminaCost=35`, BOSS_HUNT: `staminaCost=50`.

**Resultado de Falha:** Lista com número diferente de 4 itens, campos ausentes ou valores incorretos.

---

### TC-060 — POST /api/quests/start coloca guerreiro em missão

**Tipo:** Integração
**UC Relacionado:** UC-13
**Prioridade:** Alta

**Cenário:** Iniciar uma missão deve criar a sessão, debitar stamina e marcar guerreiro como onMission.

**Pré-condições:** Usuário autenticado. Guerreiro disponível com stamina ≥ 10. HP > 0.

**Dados de Entrada:**
```json
POST /api/quests/start
Authorization: Bearer <token>
{ "questType": "PATROL" }
```

**Passos:**
1. Anotar stamina atual do guerreiro.
2. Enviar `POST /api/quests/start` com tipo PATROL.
3. Verificar resposta e estado do guerreiro.

**Resultado Esperado:**
- Status 200 ou 201.
- Resposta contém ID da missão e `endsAt` (timestamp).
- `GET /api/warrior` retorna `onMission=true`.
- Stamina decrementada em 10.

**Resultado de Falha:** Guerreiro não marcado como onMission ou stamina não debitada.

---

### TC-061 — POST /api/quests/start com guerreiro ocupado retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-13
**Prioridade:** Alta

**Cenário:** Tentar iniciar uma missão quando o guerreiro já está em atividade deve ser rejeitado.

**Pré-condições:** Guerreiro já em missão (`onMission=true`).

**Dados de Entrada:**
```json
POST /api/quests/start
{ "questType": "PATROL" }
```

**Passos:**
1. Iniciar missão para travar guerreiro.
2. Tentar iniciar segunda missão.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o guerreiro está ocupado.

**Resultado de Falha:** Segunda missão iniciada ou status 500.

---

### TC-062 — POST /api/quests/start com stamina insuficiente retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-13
**Prioridade:** Alta

**Cenário:** Tentar iniciar uma missão BOSS_HUNT (custa 50 stamina) com apenas 30 de stamina disponível deve ser rejeitado.

**Pré-condições:** Guerreiro com stamina atual = 30.

**Dados de Entrada:**
```json
POST /api/quests/start
{ "questType": "BOSS_HUNT" }
```

**Passos:**
1. Forçar stamina do guerreiro para 30 (via snapshot ou consumo prévio).
2. Tentar iniciar BOSS_HUNT.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando stamina insuficiente (necessita 50, possui 30).

**Resultado de Falha:** Missão iniciada com stamina insuficiente.

---

### TC-063 — POST /api/quests/{id}/collect retorna goldEarned e expEarned

**Tipo:** Integração
**UC Relacionado:** UC-14
**Prioridade:** Alta

**Cenário:** Com `instant-complete=true` (dev), coletar missão imediatamente após iniciar deve retornar os valores de bronze e XP ganhos.

**Pré-condições:** Usuário autenticado. Guerreiro disponível. Perfil dev ativo.

**Dados de Entrada:**
```json
POST /api/quests/start → { "questType": "PATROL" }
POST /api/quests/{id}/collect
```

**Passos:**
1. Iniciar missão PATROL e anotar ID.
2. Coletar imediatamente (instant-complete).
3. Verificar resposta.

**Resultado Esperado:**
- Status 200.
- Resposta contém `goldEarned` == 100 (valor bronze da Patrulha) e `expEarned` == 50.
- `GET /api/warrior` retorna `onMission=false`.

**Resultado de Falha:** Campos ausentes, valores incorretos ou guerreiro ainda marcado como onMission.

---

### TC-064 — POST /api/quests/{id}/collect já coletado retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-14
**Prioridade:** Alta

**Cenário:** Tentar coletar a mesma missão duas vezes deve ser rejeitado na segunda tentativa.

**Pré-condições:** Missão já coletada.

**Dados de Entrada:**
```
POST /api/quests/{id}/collect (segunda vez)
```

**Passos:**
1. Iniciar e coletar missão.
2. Tentar coletar novamente com o mesmo ID.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que a missão já foi coletada.

**Resultado de Falha:** Segunda coleta com sucesso (duplicação de recompensa).

---

### TC-065 — POST /api/quests/{id}/abandon libera guerreiro sem recompensa

**Tipo:** Integração
**UC Relacionado:** UC-15
**Prioridade:** Alta

**Cenário:** Abandonar missão em andamento deve liberar o guerreiro imediatamente sem conceder bronze, XP ou item.

**Pré-condições:** Guerreiro em missão ativa.

**Dados de Entrada:**
```
POST /api/quests/{id}/abandon
```

**Passos:**
1. Iniciar missão e anotar bronze inicial.
2. Abandonar missão.
3. Verificar estado e saldo.

**Resultado Esperado:**
- Status 200.
- `GET /api/warrior` retorna `onMission=false`.
- Bronze do jogador não alterado (sem recompensa).
- Status da missão = ABANDONED.

**Resultado de Falha:** Recompensa concedida, guerreiro ainda travado ou status incorreto.

---

### TC-066 — POST /api/arena/fight cria sessão de arena e marca guerreiro

**Tipo:** Integração
**UC Relacionado:** UC-16
**Prioridade:** Alta

**Cenário:** Entrar na arena deve criar sessão de combate, debitar 25 stamina e marcar guerreiro como onMission.

**Pré-condições:** Guerreiro disponível, HP > 0, stamina ≥ 25.

**Dados de Entrada:**
```
POST /api/arena/fight
Authorization: Bearer <token>
```

**Passos:**
1. Anotar stamina inicial.
2. Enviar `POST /api/arena/fight`.
3. Verificar resposta e estado do guerreiro.

**Resultado Esperado:**
- Status 200 ou 201.
- Resposta contém ID da sessão de arena.
- `GET /api/warrior` retorna `onMission=true`.
- Stamina decrementada em 25.

**Resultado de Falha:** Sessão não criada ou stamina não debitada.

---

### TC-067 — POST /api/arena/{id}/collect retorna log de batalha e rank change

**Tipo:** Integração
**UC Relacionado:** UC-17
**Prioridade:** Alta

**Cenário:** Coletar resultado de arena deve retornar o log de batalha completo (sem tag WINNER), resultado (won/lost) e variação de rank points.

**Pré-condições:** Sessão de arena concluída (instant-complete em dev).

**Dados de Entrada:**
```
POST /api/arena/{id}/collect
```

**Passos:**
1. Entrar na arena e anotar ID.
2. Coletar resultado.
3. Verificar corpo da resposta.

**Resultado Esperado:**
- Status 200.
- Resposta contém: `battleLog` (lista de strings), `won` (boolean), `rankChange` (+25 ou -15).
- Nenhuma linha do `battleLog` contém a tag `WINNER:`.
- Bronze do jogador alterado conforme resultado.

**Resultado de Falha:** Tag WINNER visível ao cliente, campos ausentes ou bronze não atualizado.

---

### TC-068 — POST /api/tower/enter resolve combate e retorna FightResult

**Tipo:** Integração
**UC Relacionado:** UC-18
**Prioridade:** Alta

**Cenário:** Entrar na torre deve resolver automaticamente o combate contra o chefe e retornar o resultado completo.

**Pré-condições:** Guerreiro disponível, HP > 0, stamina ≥ 25.

**Dados de Entrada:**
```
POST /api/tower/enter
```

**Passos:**
1. Entrar na torre.
2. Verificar resposta.

**Resultado Esperado:**
- Status 200.
- Resposta contém: `floor`, `bossName`, `won` (boolean), `bronzeEarned`, `xpEarned`, `battleLog`.
- Se vitória: `bronzeEarned` == `floor × 40`.

**Resultado de Falha:** Status 500, campos ausentes ou bronze incorreto.

---

### TC-069 — POST /api/tower/enter com HP=0 retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-18
**Prioridade:** Alta

**Cenário:** Guerreiro inconsciente (HP=0) não pode entrar na Torre Infernal.

**Pré-condições:** Guerreiro com HP=0 (snapshot zerado manualmente ou após derrota).

**Dados de Entrada:**
```
POST /api/tower/enter
```

**Passos:**
1. Forçar HP do guerreiro para 0.
2. Tentar entrar na torre.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o guerreiro está inconsciente.

**Resultado de Falha:** Combate iniciado com HP=0.

---

### TC-070 — POST /api/tower/exit encerra sessão de torre e libera guerreiro

**Tipo:** Integração
**UC Relacionado:** UC-19
**Prioridade:** Média

**Cenário:** Após vencer um andar, o jogador pode optar por sair da torre, liberando o guerreiro.

**Pré-condições:** Guerreiro acabou de vencer um andar da torre (em dev, instant-complete).

**Dados de Entrada:**
```
POST /api/tower/exit
```

**Passos:**
1. Entrar na torre e vencer.
2. Chamar exit.
3. Verificar estado do guerreiro.

**Resultado Esperado:**
- Status 200.
- `GET /api/warrior` retorna `onMission=false`.

**Resultado de Falha:** Guerreiro continua travado ou andar não salvo como checkpoint.

---

### TC-071 — GET /api/work/jobs retorna todos os empregos com profLevel

**Tipo:** Integração
**UC Relacionado:** UC-20
**Prioridade:** Alta

**Cenário:** O endpoint deve listar todos os empregos disponíveis com nível de profissão atual do jogador, remuneração e disponibilidade conforme nível do guerreiro.

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/work/jobs
```

**Passos:**
1. Enviar `GET /api/work/jobs`.

**Resultado Esperado:**
- Status 200.
- Array com 6 empregos.
- Cada item contém: `jobType`, `bronzePerHour`, `minWarriorLevel`, `xpPerHour`, `profLevel`, `available` (boolean).
- Para guerreiro nível 1: empregos com `minWarriorLevel > 1` têm `available=false`.

**Resultado de Falha:** Lista incompleta, campos ausentes ou disponibilidade calculada incorretamente.

---

### TC-072 — POST /api/work/start abaixo do nível mínimo retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-20
**Prioridade:** Alta

**Cenário:** Tentar iniciar emprego "Ajudante do Ferreiro" (requer nível 2 do guerreiro) com guerreiro nível 1 deve ser rejeitado.

**Pré-condições:** Guerreiro nível 1.

**Dados de Entrada:**
```json
POST /api/work/start
{ "jobType": "BLACKSMITH_HELPER", "hours": 4 }
```

**Passos:**
1. Tentar iniciar emprego com nível insuficiente.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando nível mínimo necessário.

**Resultado de Falha:** Trabalho iniciado sem o nível mínimo.

---

### TC-073 — POST /api/work/{id}/collect credita bronze e XP de profissão

**Tipo:** Integração
**UC Relacionado:** UC-21
**Prioridade:** Alta

**Cenário:** Coletar trabalho concluído deve adicionar bronze ao saldo e XP à profissão correspondente.

**Pré-condições:** Sessão de trabalho concluída (instant-complete). Emprego TAVERN_HELPER (15 bronze/h, 3 XP/h), 2 horas.

**Dados de Entrada:**
```
POST /api/work/{id}/collect
```

**Passos:**
1. Iniciar trabalho TAVERN_HELPER por 2 horas.
2. Coletar imediatamente (instant-complete).
3. Verificar bronze e XP de profissão.

**Resultado Esperado:**
- Bronze adicionado == 30 (ou com bônus de profLevel se > 1).
- XP de profissão incrementado em 6.
- `onMission=false`.

**Resultado de Falha:** Bronze ou XP de profissão incorretos.

---

### TC-074 — POST /api/work/{id}/cancel com 0 horas completas retorna goldEarned=0

**Tipo:** Integração
**UC Relacionado:** UC-22
**Prioridade:** Alta

**Cenário:** Cancelar trabalho antes de completar a primeira hora deve retornar `goldEarned=0`.

**Pré-condições:** Trabalho iniciado há menos de 1 hora (sem instant-complete para este teste — usar mock de tempo ou ajustar sessão).

**Dados de Entrada:**
```
POST /api/work/{id}/cancel
```

**Passos:**
1. Iniciar trabalho com timestamp manipulado para 30 min atrás.
2. Cancelar trabalho.
3. Verificar resposta.

**Resultado Esperado:**
- Status 200.
- `goldEarned` == 0 no corpo da resposta.
- Bronze do jogador não alterado.

**Resultado de Falha:** Bronze creditado por fração de hora.

---

### TC-075 — GET /api/inventory retorna itens com description, origin, guarded e gems

**Tipo:** Integração
**UC Relacionado:** UC-23, UC-25
**Prioridade:** Alta

**Cenário:** O inventário deve retornar todos os campos relevantes dos itens, incluindo lore, origem, status de proteção e joias encaixadas.

**Pré-condições:** Usuário autenticado com itens iniciais no inventário.

**Dados de Entrada:**
```
GET /api/inventory
```

**Passos:**
1. Enviar `GET /api/inventory`.

**Resultado Esperado:**
- Status 200.
- Array com ao menos 7 itens (iniciais).
- Cada item contém: `id`, `name`, `rarity`, `slot`, `equipped`, `guarded`, `description`, `origin`, `gems`.
- `description` e `origin` não são nulos nem vazios.

**Resultado de Falha:** Campos ausentes, description/origin nulos ou lista vazia.

---

### TC-076 — POST /api/inventory/{id}/equip marca item como equipado

**Tipo:** Integração
**UC Relacionado:** UC-23
**Prioridade:** Alta

**Cenário:** Equipar um item deve marcar `equipped=true` e aplicar seus bônus ao guerreiro.

**Pré-condições:** Item no inventário, não equipado.

**Dados de Entrada:**
```
POST /api/inventory/{id}/equip
```

**Passos:**
1. Selecionar item não equipado do inventário.
2. Chamar equip.
3. Verificar estado do item e do guerreiro.

**Resultado Esperado:**
- Status 200.
- Item com `equipped=true`.
- Bônus do item refletidos nos stats do guerreiro.

**Resultado de Falha:** Item não marcado como equipado ou stats não atualizados.

---

### TC-077 — POST /api/inventory/{id}/sell em item equipado retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-25
**Prioridade:** Alta

**Cenário:** Não deve ser possível vender um item que está equipado.

**Pré-condições:** Item equipado no guerreiro.

**Dados de Entrada:**
```
POST /api/inventory/{id}/sell
```

**Passos:**
1. Equipar item.
2. Tentar vender o mesmo item.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o item está equipado e deve ser desequipado antes de vender.
- Item permanece no inventário e equipado.

**Resultado de Falha:** Item vendido enquanto equipado.

---

### TC-078 — GET /api/shop retorna 10 itens com rotationId e secondsUntilNext

**Tipo:** Integração
**UC Relacionado:** UC-26
**Prioridade:** Alta

**Cenário:** O endpoint da loja deve retornar exatamente 10 itens da rotação atual, junto com metadados de rotação.

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/shop
```

**Passos:**
1. Enviar `GET /api/shop`.

**Resultado Esperado:**
- Status 200.
- Corpo contém: `rotationId` (número), `secondsUntilNext` (> 0 e ≤ 21600), `merchantName` (string), `items` (array de 10).
- Cada item contém: `id`, `name`, `rarity`, `price`, `alreadyBought` (boolean).

**Resultado de Falha:** Número de itens diferente de 10, campos ausentes ou `secondsUntilNext` ≤ 0.

---

### TC-079 — POST /api/shop/buy/{id} adiciona item ao inventário e desconta gold

**Tipo:** Integração
**UC Relacionado:** UC-27
**Prioridade:** Alta

**Cenário:** Comprar item na loja deve adicionar ao inventário com lore gerado e debitar o bronze correto.

**Pré-condições:** Usuário com saldo suficiente. Item disponível na rotação atual e não comprado.

**Dados de Entrada:**
```
POST /api/shop/buy/{itemId}
```

**Passos:**
1. Listar itens da loja.
2. Selecionar item com preço dentro do saldo disponível.
3. Comprar item.
4. Verificar inventário e saldo.

**Resultado Esperado:**
- Status 200.
- Item presente no `GET /api/inventory`.
- `origin` do item == "Adquirido no Comércio de Mercador Viajante.".
- Bronze debitado corretamente.

**Resultado de Falha:** Item não no inventário, bronze não debitado ou origin incorreto.

---

### TC-080 — POST /api/shop/buy/{id} mesmo item duas vezes na mesma rotação retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-27
**Prioridade:** Alta

**Cenário:** Cada item da loja pode ser comprado apenas uma vez por rotação pelo mesmo jogador.

**Pré-condições:** Item já comprado na rotação atual.

**Dados de Entrada:**
```
POST /api/shop/buy/{id} (segunda compra)
```

**Passos:**
1. Comprar item.
2. Tentar comprar o mesmo item novamente.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o item já foi adquirido nesta rotação.

**Resultado de Falha:** Segunda compra processada (duplicação de item).

---

### TC-081 — GET /api/gathering/skills retorna 4 skills com level e XP

**Tipo:** Integração
**UC Relacionado:** UC-28, UC-32
**Prioridade:** Alta

**Cenário:** O endpoint deve retornar as 4 habilidades (Pesca, Mineração, **Garimpo**, Forja) com nível e XP atual de cada uma. (Reinos V2 adicionou `GARIMPO`.)

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/gathering/skills
```

**Passos:**
1. Enviar `GET /api/gathering/skills`.

**Resultado Esperado:**
- Status 200.
- Array com 4 habilidades: FISHING, MINING, GARIMPO, SMITHING.
- Cada habilidade contém: `skillType`, `level`, `currentXp`, `xpToNextLevel`.

**Resultado de Falha:** Lista incompleta, campos ausentes ou nível/XP negativos.

---

### TC-082 — POST /api/gathering/start FISHING 5min cria sessão de pesca

**Tipo:** Integração
**UC Relacionado:** UC-28
**Prioridade:** Alta

**Cenário:** Iniciar sessão de pesca por 5 minutos deve criar a sessão e marcar guerreiro como onMission.

**Pré-condições:** Guerreiro disponível.

**Dados de Entrada:**
```json
POST /api/gathering/start
{ "skillType": "FISHING", "durationMinutes": 5 }
```

**Passos:**
1. Enviar requisição de início de pesca.

**Resultado Esperado:**
- Status 200 ou 201.
- Resposta contém ID da sessão e `endsAt`.
- `GET /api/warrior` retorna `onMission=true`.
- (Reinos V2) Em produção a pesca **debita estamina** (~metade dos minutos, mín. 5). Em dev/test
  (`instant-complete`) o débito é pulado, então a estamina não muda nos testes de integração.

**Resultado de Falha:** Sessão não criada, guerreiro não marcado ou estamina debitada em modo instant.

---

### TC-083 — POST /api/gathering/{id}/collect retorna drops array

**Tipo:** Integração
**UC Relacionado:** UC-29
**Prioridade:** Alta

**Cenário:** Coletar sessão de pesca concluída deve retornar os peixes obtidos e XP de habilidade.

**Pré-condições:** Sessão de pesca concluída (instant-complete em dev).

**Dados de Entrada:**
```
POST /api/gathering/{id}/collect
```

**Passos:**
1. Iniciar pesca por 5 min.
2. Coletar imediatamente.

**Resultado Esperado:**
- Status 200.
- Resposta contém `drops` (array de peixes, pode ser vazio dependendo do RNG) e `xpEarned` (> 0).
- `GET /api/warrior` retorna `onMission=false`.

**Resultado de Falha:** Campos ausentes, XP não creditado ou guerreiro ainda onMission.

---

### TC-084 — POST /api/gathering/consume/SMALL_FISH restaura +10 stamina

**Tipo:** Integração
**UC Relacionado:** UC-31
**Prioridade:** Alta

**Cenário:** Consumir um Peixe Pequeno deve restaurar exatamente 10% de stamina.

**Pré-condições:** Jogador com ao menos 1 SMALL_FISH no inventário de recursos. Guerreiro com stamina = 0.

**Dados de Entrada:**
```
POST /api/gathering/consume/SMALL_FISH
```

**Passos:**
1. Garantir que SMALL_FISH está no inventário.
2. Forçar stamina = 0.
3. Consumir peixe.
4. Verificar stamina.

**Resultado Esperado:**
- Status 200.
- Stamina do guerreiro == 10 após consumo.
- Quantidade de SMALL_FISH no inventário decrementada em 1.

**Resultado de Falha:** Stamina não alterada, valor incorreto ou peixe não consumido do inventário.

---

### TC-085 — POST /api/gathering/consume/SALMON com quantidade=0 retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-31
**Prioridade:** Alta

**Cenário:** Tentar consumir SALMON sem ter nenhum no inventário deve ser rejeitado.

**Pré-condições:** Jogador sem SALMON no inventário de recursos.

**Dados de Entrada:**
```
POST /api/gathering/consume/SALMON
```

**Passos:**
1. Verificar que não há SALMON no inventário.
2. Tentar consumir SALMON.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que não há SALMON disponível.

**Resultado de Falha:** Stamina alterada sem o recurso disponível.

---

### TC-086 — POST /api/smithing/refine abaixo do nível de Smithing requerido retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-35
**Prioridade:** Alta

**Cenário:** Tentar refinar Mithril com nível de Smithing 1 deve ser rejeitado.

**Pré-condições:** Jogador com Smithing nível 1. Possui 5 unidades de Mithril.

**Dados de Entrada:**
```json
POST /api/smithing/refine
{ "mineralType": "MITHRIL", "quantity": 1 }
```

**Passos:**
1. Enviar requisição de refino com nível insuficiente.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando nível de Smithing insuficiente.
- Minério e bronze do jogador não alterados.

**Resultado de Falha:** Refino executado sem o nível necessário.

---

### TC-087 — POST /api/smithing/craft receita válida adiciona item com sockets ao inventário

**Tipo:** Integração
**UC Relacionado:** UC-36
**Prioridade:** Alta

**Cenário:** Usar uma receita válida de craft com nível de Smithing suficiente deve criar item com 1-2 sockets garantidos.

**Pré-condições:** Jogador com barras suficientes e nível de Smithing compatível.

**Dados de Entrada:**
```json
POST /api/smithing/craft
{ "recipeId": "IRON_SWORD" }
```

**Passos:**
1. Garantir barras de ferro e nível de Smithing mínimo.
2. Craftar espada de ferro.
3. Verificar item no inventário.

**Resultado Esperado:**
- Status 200.
- Item presente no inventário com `sockets >= 1`.
- `origin` == "Forjado pelo próprio guerreiro.".
- Barras de ferro decrementadas.

**Resultado de Falha:** Item sem sockets, origin incorreto ou recursos não consumidos.

---

### TC-088 — POST /api/smithing/gem com 3 RUBY_FRAGMENT cria 1 RUBY

**Tipo:** Integração
**UC Relacionado:** UC-37
**Prioridade:** Alta

**Cenário:** Combinar 3 fragmentos de Rubi deve consumir os fragmentos e criar 1 Rubi no inventário de recursos.

**Pré-condições:** Jogador com 3 RUBY_FRAGMENT no inventário de recursos.

**Dados de Entrada:**
```json
POST /api/smithing/gem
{ "fragmentType": "RUBY_FRAGMENT" }
```

**Passos:**
1. Garantir 3 RUBY_FRAGMENT no inventário.
2. Enviar requisição de craft de joia.
3. Verificar inventário de recursos.

**Resultado Esperado:**
- Status 200.
- 3 RUBY_FRAGMENT consumidos.
- 1 RUBY adicionado ao inventário de recursos.

**Resultado de Falha:** Fragmentos não consumidos, joia não criada ou joia do tipo errado.

---

### TC-089 — POST /api/smithing/gem com apenas 2 fragmentos retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-37
**Prioridade:** Alta

**Cenário:** Tentar criar joia com apenas 2 fragmentos (mínimo é 3) deve ser rejeitado.

**Pré-condições:** Jogador com apenas 2 RUBY_FRAGMENT.

**Dados de Entrada:**
```json
POST /api/smithing/gem
{ "fragmentType": "RUBY_FRAGMENT" }
```

**Passos:**
1. Garantir apenas 2 fragmentos.
2. Tentar craft de joia.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando fragmentos insuficientes (necessita 3).

**Resultado de Falha:** Joia criada com apenas 2 fragmentos.

---

### TC-090 — POST /api/smithing/socket/{itemId}/RUBY encaixa joia e deduz recurso

**Tipo:** Integração
**UC Relacionado:** UC-38
**Prioridade:** Alta

**Cenário:** Encaixar Rubi em socket vazio de item deve atualizar o item com a joia e remover o Rubi do inventário de recursos.

**Pré-condições:** Jogador com 1 RUBY no inventário de recursos. Item com ao menos 1 socket vazio no inventário.

**Dados de Entrada:**
```
POST /api/smithing/socket/{itemId}/RUBY
```

**Passos:**
1. Selecionar item com socket vazio.
2. Encaixar Rubi.
3. Verificar item e inventário de recursos.

**Resultado Esperado:**
- Status 200.
- Item retorna com RUBY na lista de gems encaixadas.
- RUBY removido do inventário de recursos.

**Resultado de Falha:** Gem não encaixada, RUBY não consumido ou item errado modificado.

---

### TC-091 — POST /api/smithing/socket em item sem sockets retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-38
**Prioridade:** Alta

**Cenário:** Tentar encaixar joia em item que não possui sockets disponíveis deve ser rejeitado.

**Pré-condições:** Item sem sockets (ou todos preenchidos). Jogador com 1 RUBY no inventário.

**Dados de Entrada:**
```
POST /api/smithing/socket/{itemId}/RUBY
```

**Passos:**
1. Selecionar item sem sockets.
2. Tentar encaixar joia.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o item não possui sockets disponíveis.
- RUBY permanece no inventário.

**Resultado de Falha:** Joia encaixada em item sem socket.

---

### TC-092 — GET /api/zones retorna 3 zonas com multiplier e encounter rates

**Tipo:** Integração
**UC Relacionado:** UC-39
**Prioridade:** Alta

**Cenário:** O endpoint de zonas deve retornar as 3 zonas com seus multiplicadores de recursos e taxas de encontro.

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/zones
```

**Passos:**
1. Enviar `GET /api/zones`.

**Resultado Esperado:**
- Status 200.
- Array com 3 zonas: SAFE, PVP, HIGH_RISK.
- Cada zona contém: `type`, `minLevel`, `resourceMultiplier`, `npcEncounterRatePerHour`, `pvpEncounterRatePerHour`.
- SAFE: `resourceMultiplier=1.0`, `npcEncounterRatePerHour=15`, `pvpEncounterRatePerHour=0`.
- PVP: `resourceMultiplier=1.5`, `npcEncounterRatePerHour=25`, `pvpEncounterRatePerHour=20`.
- HIGH_RISK: `resourceMultiplier=2.5`, `npcEncounterRatePerHour=35`, `pvpEncounterRatePerHour=40`.

**Resultado de Falha:** Menos de 3 zonas, valores incorretos ou campos ausentes.

---

### TC-093 — POST /api/zones/enter SAFE GATHERING FISHING 30min cria sessão

**Tipo:** Integração
**UC Relacionado:** UC-39
**Prioridade:** Alta

**Cenário:** Entrar na Zona Segura como Gatherer de pesca por 30 minutos deve criar a sessão corretamente.

**Pré-condições:** Guerreiro disponível, nível ≥ 1.

**Dados de Entrada:**
```json
POST /api/zones/enter
{
  "zone": "SAFE",
  "role": "GATHERER",
  "skill": "FISHING",
  "durationMinutes": 30
}
```

**Passos:**
1. Enviar requisição de entrada em zona.

**Resultado Esperado:**
- Status 200 ou 201.
- Resposta contém ID da sessão, zona, papel e `endsAt`.
- `GET /api/warrior` retorna `onMission=true`.

**Resultado de Falha:** Sessão não criada ou campos ausentes na resposta.

---

### TC-094 — POST /api/zones/enter com HP=0 retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-39
**Prioridade:** Alta

**Cenário:** Guerreiro inconsciente não pode entrar em zona.

**Pré-condições:** Guerreiro com HP=0.

**Dados de Entrada:**
```json
POST /api/zones/enter
{ "zone": "SAFE", "role": "GATHERER", "skill": "FISHING", "durationMinutes": 30 }
```

**Passos:**
1. Forçar HP=0.
2. Tentar entrar em zona.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando guerreiro inconsciente.

**Resultado de Falha:** Sessão criada com guerreiro inconsciente.

---

### TC-095 — POST /api/zones/{id}/collect retorna drops com multiplicador da zona aplicado

**Tipo:** Integração
**UC Relacionado:** UC-41
**Prioridade:** Alta

**Cenário:** Coletar expedição em zona deve retornar os recursos com o multiplicador da zona aplicado sobre a produção base.

**Pré-condições:** Sessão de zona SAFE concluída (instant-complete).

**Dados de Entrada:**
```
POST /api/zones/{id}/collect
```

**Passos:**
1. Entrar na Zona Segura como Gatherer de pesca.
2. Coletar resultado.
3. Verificar drops e XP.

**Resultado Esperado:**
- Status 200.
- Resposta contém `drops`, `xpEarned`, `zoneMultiplier`.
- `zoneMultiplier` == 1.0 para Zona Segura.
- `onMission=false` após coleta.

**Resultado de Falha:** Multiplicador não presente na resposta ou XP calculado incorretamente.

---

### TC-096 — GET /api/temple retorna hpPercent, healCost, buffs e protectedCount

**Tipo:** Integração
**UC Relacionado:** UC-42, UC-43, UC-44
**Prioridade:** Alta

**Cenário:** O endpoint do Templo deve retornar o estado completo de saúde, custo de cura, opções de buff e contagem de itens protegidos.

**Pré-condições:** Usuário autenticado.

**Dados de Entrada:**
```
GET /api/temple
```

**Passos:**
1. Enviar `GET /api/temple`.

**Resultado Esperado:**
- Status 200.
- Corpo contém: `hpPercent`, `healCost`, `availableBuffs` (lista), `activeBuffName`, `protectedCount` (0 a 3).
- `hpPercent` entre 0 e 100.

**Resultado de Falha:** Campos ausentes ou valores fora do intervalo esperado.

---

### TC-097 — POST /api/temple/heal com HP=100 retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-42
**Prioridade:** Alta

**Cenário:** Tentar curar guerreiro com HP já em 100% deve ser rejeitado.

**Pré-condições:** Guerreiro com HP=100%.

**Dados de Entrada:**
```
POST /api/temple/heal
```

**Passos:**
1. Verificar que HP é 100%.
2. Tentar curar.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando que o guerreiro já está com HP pleno.

**Resultado de Falha:** Status 200 (cura desnecessária processada sem erro).

---

### TC-098 — POST /api/temple/heal para guerreiro nível ≤ 10 é gratuito e restaura HP

**Tipo:** Integração
**UC Relacionado:** UC-42
**Prioridade:** Alta

**Cenário:** Guerreiro de nível 1-10 pode curar sem custo em bronze, com HP restaurado para 100%.

**Pré-condições:** Guerreiro nível 1 com HP < 100%. Saldo bronze = 0.

**Dados de Entrada:**
```
POST /api/temple/heal
```

**Passos:**
1. Forçar HP do guerreiro para 50%.
2. Curar no Templo.

**Resultado Esperado:**
- Status 200.
- `hpPercent` == 100 após cura.
- Bronze do jogador não alterado (cura gratuita).

**Resultado de Falha:** Bronze debitado para nível ≤ 10 ou HP não restaurado.

---

### TC-099 — POST /api/temple/heal para guerreiro nível > 10 custa 100 bronze

**Tipo:** Integração
**UC Relacionado:** UC-42
**Prioridade:** Alta

**Cenário:** Guerreiro de nível 11+ deve pagar 100 bronze para curar, com HP restaurado para 100%.

**Pré-condições:** Guerreiro nível 11 com HP < 100%. Saldo ≥ 100 bronze.

**Dados de Entrada:**
```
POST /api/temple/heal
```

**Passos:**
1. Anotar bronze inicial.
2. Forçar HP para 50%.
3. Curar no Templo.
4. Verificar bronze e HP.

**Resultado Esperado:**
- Status 200.
- HP == 100%.
- Bronze decrementado em 100.

**Resultado de Falha:** Cura gratuita para nível > 10 ou HP não restaurado.

---

### TC-100 — POST /api/temple/buff/STRENGTH aplica buff e debita bronze

**Tipo:** Integração
**UC Relacionado:** UC-07, UC-43
**Prioridade:** Alta

**Cenário:** Aplicar buff STRENGTH deve adicionar +5 ATK ao guerreiro por 1 hora e debitar 30 bronze.

**Pré-condições:** Guerreiro sem buff ativo. Jogador com ≥ 30 bronze.

**Dados de Entrada:**
```
POST /api/temple/buff/STRENGTH
```

**Passos:**
1. Anotar ATK e bronze inicial.
2. Aplicar buff STRENGTH.
3. Verificar guerreiro.

**Resultado Esperado:**
- Status 200.
- `activeBuff` == "STRENGTH".
- ATK do guerreiro == ATK_anterior + 5.
- Bronze decrementado em 30.
- `buffExpiresAt` ≈ agora + 1 hora.

**Resultado de Falha:** ATK não aumentado, bronze não debitado ou buff não registrado.

---

### TC-101 — POST /api/temple/protect/{itemId} marca item como guarded=true

**Tipo:** Integração
**UC Relacionado:** UC-44
**Prioridade:** Alta

**Cenário:** Proteger item no Templo deve marcar `guarded=true` e debitar 50 bronze.

**Pré-condições:** Jogador com item no inventário (não protegido). Saldo ≥ 50 bronze. `protectedCount < 3`.

**Dados de Entrada:**
```
POST /api/temple/protect/{itemId}
```

**Passos:**
1. Selecionar item não protegido.
2. Anotar bronze e protectedCount.
3. Proteger item.
4. Verificar inventário e templo.

**Resultado Esperado:**
- Status 200.
- Item com `guarded=true` no `GET /api/inventory`.
- Bronze decrementado em 50.
- `protectedCount` incrementado em 1.

**Resultado de Falha:** Item não marcado como protegido, bronze não debitado ou contagem incorreta.

---

### TC-102 — POST /api/temple/protect 4º item retorna 400

**Tipo:** Integração
**UC Relacionado:** UC-44
**Prioridade:** Alta

**Cenário:** Tentar proteger um 4º item quando o limite de 3 já foi atingido deve ser rejeitado.

**Pré-condições:** Jogador com 3 itens já protegidos e 1 item adicional não protegido.

**Dados de Entrada:**
```
POST /api/temple/protect/{itemId} (4º item)
```

**Passos:**
1. Proteger 3 itens.
2. Tentar proteger 4º item.

**Resultado Esperado:**
- Status 400.
- Mensagem indicando limite máximo de 3 itens protegidos atingido.

**Resultado de Falha:** 4º item protegido (limite violado).

---

### TC-103 — GET /api/tower/ranking retorna lista com warriorName e bestFloor

**Tipo:** Integração
**UC Relacionado:** UC-46
**Prioridade:** Alta

**Cenário:** O ranking da Torre Infernal deve retornar os jogadores ordenados pelo melhor andar completado, exibindo nome do guerreiro.

**Pré-condições:** Ao menos 1 jogador com andar completado na torre.

**Dados de Entrada:**
```
GET /api/tower/ranking
```

**Passos:**
1. Entrar na torre e completar ao menos 1 andar.
2. Enviar `GET /api/tower/ranking`.

**Resultado Esperado:**
- Status 200.
- Array de jogadores com `warriorName` e `bestFloor`.
- Lista ordenada de forma decrescente por `bestFloor`.
- `warriorName` não é o username de login.

**Resultado de Falha:** Lista vazia (mesmo com jogador que completou andar), campos ausentes ou ordenação incorreta.

---

### TC-104 — GET /api/arena/rank retorna lista com warriorName e rankPoints

**Tipo:** Integração
**UC Relacionado:** UC-47
**Prioridade:** Alta

**Cenário:** O ranking da Arena deve retornar os top 20 jogadores ordenados por rank points, exibindo nome do guerreiro.

**Pré-condições:** Ao menos 1 jogador com batalha de arena coletada.

**Dados de Entrada:**
```
GET /api/arena/rank
```

**Passos:**
1. Completar batalha de arena.
2. Enviar `GET /api/arena/rank`.

**Resultado Esperado:**
- Status 200.
- Array com no máximo 20 entradas.
- Cada entrada contém `warriorName` e `rankPoints`.
- Lista ordenada de forma decrescente por `rankPoints`.

**Resultado de Falha:** Mais de 20 entradas, campos ausentes ou ordenação incorreta.

---

---

## Testes de Integração — Guildas (TC-105 a TC-116)

### TC-105: GET /api/guild sem guilda → inGuild:false
**Tipo:** Integração | **Classe:** GuildIntegrationTest

### TC-106: POST /api/guild → cria guilda com isLeader:true e 1 membro
**Tipo:** Integração | **Pré:** 100 bronze disponível (50 prata inicial)

### TC-107: POST /api/guild nome duplicado → 400
**Tipo:** Integração | **Pré:** Guilda com mesmo nome já existe

### TC-108: POST /api/guild estando em guilda → 400
**Tipo:** Integração

### TC-109: GET /api/guild/list → retorna array com campos name e level
**Tipo:** Integração

### TC-110: POST /api/guild/join/{id} segundo player → inGuild:true, 2 membros
**Tipo:** Integração

### TC-111: POST /api/guild/join já em guilda → 400
**Tipo:** Integração

### TC-112: POST /api/guild/leave membro → inGuild:false
**Tipo:** Integração

### TC-113: POST /api/guild/leave líder com membros → 400
**Tipo:** Integração

### TC-114: POST /api/guild/kick/{id} → membro expulso
**Tipo:** Integração | **Pré:** Executado pelo líder

### TC-115: POST /api/guild/donate → guildGold aumenta
**Tipo:** Integração

### TC-116: DELETE /api/guild → guilda dissolvida, inGuild:false
**Tipo:** Integração | **Pré:** Executado pelo líder

---

---

## Testes Unitários — Bônus de Guilda (TC-036 a TC-040)

### TC-036: Guild.xpBonus() level 1 → 0%
**Tipo:** Unitário | **Classe:** GuildModelTest  
`new Guild(level=1).xpBonus() == 0`

### TC-037: Guild.xpBonus() level 3 → 10%
**Tipo:** Unitário  
`new Guild(level=3).xpBonus() == 10`

### TC-038: Guild.xpBonus() cap em level 5+ → 20%
**Tipo:** Unitário  
`new Guild(level=6).xpBonus() == 20` (não ultrapassa o cap)

### TC-039: Guild.dropBonus() level 2 → 0%, level 3 → 2%, level 5 → 6%
**Tipo:** Unitário  
Verifica fórmula `max(0, level-2)×2` com cap 7

### TC-040: Guild.bronzeBonus() level 3 → 0%, level 4 → 5%, level 5 → 10%
**Tipo:** Unitário  
Verifica fórmula `max(0, level-3)×5` com cap 10

---

## Testes de Integração — Bônus de Guilda (TC-117 a TC-120)

### TC-117: Quest reward com guilda level 2 → XP +5% vs sem guilda
**Tipo:** Integração | **Pré:** Player em guilda level 2; coleta PATROL

### TC-118: Quest reward com guilda level 1 → sem diferença de XP
**Tipo:** Integração | **Pré:** Player em guilda level 1 (sem bônus)

### TC-119: Work reward com guilda level 4 → bronze +5% e XP +15%
**Tipo:** Integração | **Pré:** Player em guilda level 4; coleta trabalho

### TC-120: GET /api/guild retorna xpBonus, dropBonus, bronzeBonus no payload
**Tipo:** Integração | **Verifica:** campos de bônus presentes na resposta da guilda

---

---

## Testes de Integração — Donation Rank (TC-121 a TC-123)

### TC-121: GET /api/guild → donationRank presente no payload
**Tipo:** Integração  
Após criar guilda, `donationRank` deve ser array (vazio ou com membros).

### TC-122: Donate → donationRank atualiza com valor correto
**Tipo:** Integração  
Membro doa 50 bronze → `donationRank[0].donatedBronze == 50`, `donationRank[0].isMe == true`.

### TC-123: Sair da guilda zera guildDonatedBronze ao entrar em outra
**Tipo:** Integração  
Player doa, sai e entra em nova guilda → `donationRank[0].donatedBronze == 0`.

---

---

## Testes Unitários — Guerra de Territórios (TC-041 a TC-048)

### TC-041: defenseStreak 0 → debuff 0%
**Tipo:** Unitário | **Classe:** TerritoryServiceTest
`TerritoryControl(streak=0).debuffPercent() == 0`

### TC-042: defenseStreak 1 → debuff 5%
`TerritoryControl(streak=1).debuffPercent() == 5`

### TC-043: defenseStreak 10 → debuff 50% (cap)
`TerritoryControl(streak=10).debuffPercent() == 50`

### TC-044: NPC stats gerados com base na média dos atacantes
Stats dos NPCs = média dos warriors atacantes × fator do território.

### TC-045: Guild Brawl — lado com mais membros vence NPC equilibrado
2 warriors (50 ATK, 50 DEF, 100 HP) vs 2 NPCs equivalentes — resultado não-determinístico mas não deve crashar.

### TC-046: Guild Brawl — 2v1 (vencedor do 1v1 entra na briga seguinte)
Verifica que ao final de um 1v1 o vencedor combate o próximo oponente disponível.

### TC-047: Defender HP restaurado entre lutas (exceto a última)
Após luta 1, HP dos defensores = HP pré-luta. Após luta 2 (última), HP não restaurado.

### TC-048: TerritoryDeclaration duplicada → rejeitada
Guilda já tem declaração PENDING para o mesmo ciclo → lança exceção.

---

## Testes de Integração — Guerra de Territórios (TC-124 a TC-133)

### TC-124: GET /api/territory → lista os 3 territórios de guerra com status
Sem guilda dominante → todos neutros. (Reinos V2: ids são `Kingdom` — FISHING/MINING/COMBAT por config.)

### TC-125: POST /api/territory/COMBAT/declare → declaração criada
**Pré:** Líder de guilda sem território. (Reinos V2: o path usa o id `Kingdom` `COMBAT`, antes `FORTALEZA_MALDITA`.)

### TC-126: POST /api/territory/declare com guilda que já controla território → 400
Guilda dominante não pode declarar ataque.

### TC-127: POST /api/territory/declare duplicada → 400
Mesma guilda declara duas vezes no mesmo ciclo.

### TC-128: GET /api/territory/my → retorna status e bônus do território da guilda

### TC-129: Resolução automática — território neutro + 1 atacante → guilda domina
Simular resolução de batalha via TerritoryService (chamada direta, sem scheduler).

### TC-130: Resolução — território neutro + NPC → guilda grande vence NPCs fracos

### TC-131: Resolução — defensor mantém, defenseStreak +1

### TC-132: Resolução — atacante vence, streak zera para o novo dono

### TC-133: Bônus de território aplicado em quest collect (GET /api/quests collect)
Membros de guilda dominante recebem XP e bronze com +10% base.

---

---

## Integration Tests — Mail System (TC-134 to TC-141)

### TC-134: GET /api/mail/inbox returns empty array for new player
### TC-135: POST /api/mail/send → letter created, sender gold reduced by 1 + goldAmount
**Pre:** Sender has ≥ 2 gold (1 fee + 1 attached)
### TC-136: POST /api/mail/send to self → 400
### TC-137: POST /api/mail/send to non-existent user → 400
### TC-138: POST /api/mail/send with insufficient funds → 400
### TC-139: GET /api/mail/inbox after receiving → letter present with correct fields
### TC-140: POST /api/mail/{id}/collect → gold transferred to recipient
### TC-141: DELETE /api/mail/{id} → letter removed from inbox

---

---

## Unit Tests — World / 5 Reinos (TC-049 to TC-052) — Reinos V2

### TC-049: KingdomQuestType is kingdom-specific
**Type:** Unit | **Class:** KingdomServiceTest
Quests de FISHING não aparecem para MINING ou COMBAT.

### TC-050: Cada reino tem exatamente 6 quests (Quests V2)
**Type:** Unit
Itera os 5 reinos; cada um tem 6 quests definidas no `KingdomQuestType`.

### TC-050b: Vitrine rotativa mostra 2 das 6 (Quests V2)
**Type:** Unit | **Class:** KingdomServiceTest
`KingdomService.rotatingWindow(all, rotationId)` retorna 2 quests consecutivas; ao longo das janelas
(6h cada) cobre todas as 6.

### TC-051: Kingdom unificado carrega dados de território (NPC + bônus)
**Type:** Unit
Reinos de guerra (FISHING/MINING/COMBAT) têm npcName e exclusiveBonus > 0; Grutas/Mar têm bônus 0.

### TC-052: COMBAT sem primarySkill; FISHING/MINING têm
**Type:** Unit
`Kingdom.COMBAT.primarySkill == null`; pesca/mineração têm skill de coleta.

---

## Integration Tests — World / 5 Reinos (TC-142 to TC-157) — Reinos V2

### TC-142: GET /api/world → returns 5 kingdoms with status
Cada reino tem: kingdom, displayName, icon, controllingGuild (ou ""), isMine, bônus, zonas.
(Covil das Feras fundido na Fortaleza → 5 reinos.)

### TC-143: GET /api/world/{kingdom}/quests → vitrine de 2 (Quests V2)
O endpoint retorna a vitrine de 2 quests (de um pool de 6 por reino, rotacionando a cada 6h).

### TC-144/145: POST /api/world/{kingdom}/quests/start → quest iniciada / warrior busy → 400
Mesma mecânica das quests clássicas, mas quest específica do reino; consome estamina.

### TC-146: POST /api/gathering/start → sessão de coleta (via World)
DESFILADEIRO/MAR → FISHING; MINAS → MINING; GRUTAS → GARIMPO. Em produção, consome estamina.

### TC-147: gather abaixo do nível da zona → bloqueado
Player abaixo do nível da zona não acessa.

### TC-148: POST /api/world/COMBAT/training/start → treino iniciado
Bronze debitado, sessão criada com XP reward. (Path agora usa o id `COMBAT`.)

### TC-149: training/start sem bronze → 400
### TC-150: POST /api/world/COMBAT/training/{id}/collect → XP awarded
Sem bronze nem itens — XP puro.

### TC-151: GET /api/world → mostra bônus da guilda no território dela
Guilda controlando MINING → card de Minas mostra bônus ativo.

### TC-152: POST /api/world/{kingdom}/quests/start com quest de outro reino → 400
Quest de MINING em FISHING é rejeitada.

### TC-157: GET /api/world/COMBAT/quests → vitrine de 2
Fortaleza também retorna a vitrine de 2 (de 6).

### Quest V2 — encontro de monstro na coleta
- Coleta sempre retorna `narrative` não-vazio + `monsterEncountered`/`monsterDefeated`.
- Vencer o monstro → XP/bronze creditados; perder → 0 recompensa. **Class:** `KingdomQuestCombatTest`.

### Caçada PvE (Fortaleza Maldita — antigo Covil das Feras)
- `POST /api/world/COMBAT/raid` → 200 com `won`/`beast`/materiais (vitória rende gold/XP/Núcleo de Fera).
- `POST /api/world/{outro reino}/raid` → 400 (caçada só na Fortaleza). **Class:** `CovilRaidTest`.

---

---

## Unit Tests — Territory War Brawl Mechanics (TC-053 to TC-061)
**Class:** `TerritoryWarTest`

### TC-053: Vastly superior attacker wins (×10 repeated)
Stats: ATK 100/DEF 50/HP 300 vs ATK 5/DEF 2/HP 20 → attacker always wins

### TC-054: Vastly superior defender wins (×10 repeated)
Inverse of TC-053 → defender always wins

### TC-055: 2v1 — two attackers beat one defender majority of runs
10 runs with equal fighters → attackers win >5 times

### TC-056: Phase 1 HP — fresh fighter beats 1 HP fighter
Fresh fighter (200 HP) vs tired fighter (1 HP) → fresh wins >7/10 runs

### TC-057: Phase 2 tiebreaker — identical Phase 1 HP, neither guaranteed winner
30 runs with identical fighters → each wins at least once (non-deterministic)

### TC-058: Phase 2 tiebreaker — better Phase 1 HP wins majority
200 HP vs 80 HP → 200 HP wins majority (>5/10)

### TC-059: Battle log always has start and outcome lines
Log contains "The battle begins!" and "Attackers have conquered" or "Defenders held"

### TC-060: Empty attackers → defenders win immediately
### TC-061: Empty defenders → attackers win immediately

---

## Integration Tests — Warrior Exclusivity & Sequence (TC-153 to TC-160)
**Class:** `WarriorExclusivityTest`

These tests verify that a warrior cannot perform 2 simultaneous tasks and that
collecting one task correctly frees the warrior for the next (catches state bugs).

### TC-153: After collecting kingdom quest → warrior free → can start next quest
Sequence: start quest → collect → start another quest → 200 (not 400)

### TC-154: Cannot start 2 kingdom quests simultaneously
Start quest FISHING → start quest MINING → 400

### TC-155: Cannot start Work while on Kingdom quest (cross-system)
### TC-156: Cannot start Kingdom quest while Working (cross-system)
### TC-157: Cannot start Kingdom quest while Training at Fortaleza (cross-system)
### TC-158: Cannot start Kingdom quest while gathering (cross-system)
### TC-159: After Work collected → warrior free → can start kingdom quest
### TC-160: After abandoning quest → warrior free → can start another quest

---

## Regression Tests — Zone Orphan State (TC-096, TC-097)
**Class:** `ZoneIntegrationTest` — added after production bug

### TC-096: freeIfStuck clears IN_PROGRESS zone → re-enter works
Enter zone → /api/warrior/free → enter zone again → 200 (not "already on expedition")

### TC-097: Zone enter auto-cancels orphaned expedition when warrior is free
Enter zone → free warrior → enter different zone → ZoneService detects inconsistency
and auto-cancels the orphan before creating the new zone

---

---

## Integration Tests — SoulStone VIP Currency (TC-161 to TC-175)
**Class:** `SoulStoneIntegrationTest extends BaseIntegrationTest`

### Saldo e Admin

### TC-161: GET /api/warrior/me → soulStones = 0 para novo jogador
Novo jogador registrado → `GET /api/warrior/me` → `soulStones = 0`.

### TC-162: POST /api/admin/grant-soulstones → saldo aumenta
Grant 5 stones → `GET /api/warrior/me` → `soulStones = 5`.

### TC-163: POST /api/admin/grant-soulstones com amount=0 → 400
amount ≤ 0 → 400 error.

### TC-164: POST /api/admin/grant-soulstones com amount=101 → 400
amount > 100 → 400 error.

---

### Cura Instantânea

### TC-165: Soulstone heal sem stones → 400
HP < 100, stones = 0 → `POST /api/temple/soulstone-heal` → 400 "Not enough SoulStones".

### TC-166: Soulstone heal com HP cheio → 400
Grant 5 stones, HP = 100 → soulstone-heal → 400 "already has full HP".

### TC-167: Soulstone heal válido → HP 100%, stones -1
Grant 5 stones, HP reduzido via arena/defeat → soulstone-heal → 200, `hpPercent = 100`, `soulStones = 4`.

### TC-168: Soulstone heal em CD → 400 com mensagem de tempo
Usar soulstone-heal → usar novamente imediatamente → 400 "on cooldown".

### TC-169: GET /api/temple → ssHealCooldownSecs e ssHealReady presentes
Após uso → `ssHealCooldownSecs > 0`, `ssHealReady = false`.
Sem uso → `ssHealCooldownSecs = 0`, `ssHealReady = true`.

---

### Expansão de Bag

### TC-170: Expand sem stones → 400
`POST /api/inventory/expand` sem stones → 400 "Not enough SoulStones".

### TC-171: Expand com stones suficientes → maxSlots = 20
Grant 3 stones → expand → 200, `maxSlots = 20`, `inventoryExpanded = true`, `soulStones = 0`.

### TC-172: Expand duas vezes → 400
Grant 10 stones → expand → expand novamente → 400 "already expanded".

### TC-173: GET /api/inventory/slots → campos presentes
`slots.bagSize`, `slots.maxSlots`, `slots.inventoryExpanded`, `slots.soulStones` todos presentes.

### TC-174: Bag cheia (10 slots) bloqueia novo item via quest
Preencher 10 itens na bag (via make) → iniciar e coletar quest com drop garantido → item não adicionado, erro retornado.

### TC-175: Bag expandida (20 slots) aceita além de 10
Expand bag → adicionar 15 itens → bag aceita (não rejeita até 20).

---

---

## Integration Tests — VIP Status System (TC-176 to TC-192)
**Class:** `VipIntegrationTest extends BaseIntegrationTest`

### Compra VIP

### TC-176: Comprar VIP → vipExpiresAt ~30 dias no futuro
Grant 15 SS → `POST /api/vip/buy` → 200, response tem `vipExpiresAt`, `isVip=true`.

### TC-177: Comprar VIP sem SS suficiente → 400
Grant 10 SS → buy → 400 "Not enough SoulStones".

### TC-178: Renovar VIP empilha dias
Grant 30 SS → buy VIP → buy VIP de novo → `vipExpiresAt` ≈ now + 60 dias.

### TC-179: Comprar VIP inclui expansão de bag
Grant 15 SS → buy VIP → `GET /api/inventory/slots` → `maxSlots = 20`.

### TC-180: GET /api/vip/status retorna isVip + benefícios restantes
Grant 15 SS → buy → `GET /api/vip/status` → `isVip=true`, `instantQuestsRemaining=2`, `arenaFightsRemaining=10`.

---

### Cura VIP Grátis

### TC-181: VIP heal grátis → HP 100%, sem bronze
Grant 15 SS → buy VIP → dano no warrior → `POST /api/temple/vip-heal` → 200, HP=100, bronze unchanged.

### TC-182: VIP heal sem VIP → 400
Sem VIP → vip-heal → 400 "VIP required".

### TC-183: VIP heal em CD (10 min) → 400
Buy VIP → dano → vip-heal → vip-heal de novo → 400 "on cooldown".

### TC-184: VIP heal com HP cheio → 400
Buy VIP → vip-heal com HP=100 → 400 "already full HP".

---

### Missão Instantânea VIP

### TC-185: Instant quest → quest concluída imediatamente, rewards retornados
Grant 15 SS → buy VIP → `POST /api/world/FISHING/quests/instant-start` com `{questType:"PATROL_COAST"}` → 200, response tem bronzeEarned + xpEarned.

### TC-186: Instant quest sem VIP → 400
Sem VIP → instant-start → 400 "VIP required".

### TC-187: Instant quest decrementa counter
Buy VIP → instant-start → `GET /api/vip/status` → `instantQuestsRemaining=1`.

### TC-188: Instant quest esgotado (2/dia) → 400
Buy VIP → instant-start → instant-start → instant-start → 400 "Daily instant quest limit reached (2/2)".

---

### Arena Daily Limit

### TC-189: Free player → arena funciona até 5 lutas/dia
Novo jogador → 5x arena → 5ª luta OK → 6ª luta → 400 "Daily fight limit reached (5/5)".

### TC-190: VIP player → arena funciona até 10 lutas/dia
Buy VIP → counter zerado → limite = 10 (verificado via `GET /api/vip/status` → `arenaFightsRemaining=10`).

---

### Dois Buffs VIP

### TC-191: VIP pode ter 2 buffs simultâneos
Buy VIP → apply buff FORCA → apply buff DEFESA → 200 (ambos aceitos).

### TC-192: Free player não pode ter 2 buffs
Sem VIP → apply buff FORCA → apply buff DEFESA → 400 "VIP required for second buff slot".

---

---

## Unit Tests — d20 Combat System (TC-193 to TC-200)
**Class:** `BattleSimulatorTest` (extend existing)

### TC-193: Natural 20 always crits regardless of AC
Simulate with defender AC 999 and attacker roll forced to 20 → hit is a crit.

### TC-194: Natural 1 always misses regardless of STR bonus
Simulate with defender AC 1 and attacker STR 100 (max bonus) → miss (fumble).

### TC-195: Attack roll formula — STR 60 gives +3 bonus
`floor(60 / 20) = 3` → verify bônus de ataque = 3.

### TC-196: AC formula — DEX 40 gives AC 50
`10 + 40 = 50` → verify defender AC = 50.

### TC-197: Hit chance — DEX 0 vs STR 0: ~55%
Run 1000 simulations, verify hit rate between 50-60%.

### TC-198: Hit chance — DEX 40 (AC 50) vs STR 0 (+0): only crits hit
Run 100 simulations, verify only natural-20 hits land (~5% rate).

### TC-199: Fortune Save triggers at correct LUK threshold
LUK 30 → Fortune Save chance = 3%. Simulate 1000 enemy crits, verify ~3% converted.

### TC-200: Crit window expands with LUK correctly
LUK 15 → crit on 19-20. Verify crit rate ~10% in large simulation.

---

## Integration Tests — XP Loss on PvP Death (TC-201 to TC-205)
**Class:** `XpLossIntegrationTest extends BaseIntegrationTest`

### TC-201: XP loss 10% of current level threshold on PvP death
Player at level 5 with 2000 XP → xpRequired for level 5 = ~3000 → loss = 300 XP → ends at 1700.

### TC-202: Level drop when XP falls below level threshold
Player at level 5, XP exactly at threshold (0 into the level) → PvP death → drops to level 4.

### TC-203: Cannot drop below level 1
Player at level 1, minimum XP → PvP death → stays at level 1, XP stays at 0.

### TC-204: No XP loss in Arena (only PvP zones)
Lose Arena fight → XP unchanged.

### TC-205: Exponential XP curve — level 10 requires correct threshold
`round(100 * 10^1.8) = round(100 * 63.1) = 6310` XP to go from 9 to 10.

---

## Unit Tests — XP Curve (TC-206 to TC-208)
**Class:** `WarriorServiceTest` (extend existing)

### TC-206: XP formula for level 2 = 100
`round(100 * 1^1.8) = 100`

### TC-207: XP formula for level 10 = ~6310
`round(100 * 9^1.8) ≈ 6310`

### TC-208: Level up with 2 attribute points per level
Level up → `availablePoints` increases by 2 (not 5 as before).

---

---

## Integration Tests — Zone Ambush PvP (TC-209 to TC-218)
**Class:** `ZoneAmbushIntegrationTest extends BaseIntegrationTest`

### TC-209: Player IN_PROGRESS appears in zone opponent pool
Two players enter PVP zone → repository finds each as a candidate for the other.

### TC-210: findOpponentInZone excludes self
Player A in PVP zone → opponent query for A never returns A.

### TC-211: SAFE zone never triggers PvP (0% chance)
Enter SAFE zone with another player present → collect 100x → no PvP encounters logged.

### TC-212: No opponent in zone → falls back to NPC
Single player in PVP zone → PvP roll has no target → resolves as NPC (PvE).

### TC-213: Ambush winner steals 15% of loser's bronze
Force ambush (deterministic) → winner gains 50% of stolen, loser loses 15%.

### TC-214: Ambush loser dies (HP=0) and loses XP
Force ambush where target loses → target HP=0, XP reduced by 10% of level threshold.

### TC-215: Ambush loser in HIGH_RISK may lose equipped item
Force ambush loss in HIGH_RISK → item-loss roll executed (10% chance path covered).

### TC-216: Anti-ambush buff reduces chance by 5% per win
After 1 defensive win → subsequent ambush chance is base - 5%.

### TC-217: Ambush generates mail to the target
After being ambushed → target inbox has a system mail with attacker name + bronze lost.

### TC-218: Survivor sees continue/collect decision; dead does not
Target survives → expedition still IN_PROGRESS (can continue). Target dies → status DEFEATED.

---

## Integration Tests — Territory War Cycle (TC-219 to TC-228)
**Class:** `TerritoryWarCycleIntegrationTest extends BaseIntegrationTest`

### TC-219: Single guild attacks neutral → wins NPCs → takes territory
Declare + resolve → guild becomes controllingGuild.

### TC-220: Single guild attacks held territory → beats defenders → takes over
Guild B attacks Guild A's territory, B wins → B is new holder, streak reset to 0.

### TC-221: All attackers lose → defender holds + streak +1
Weak attackers vs strong defender → defender keeps territory, defenseStreak increments.

### TC-222: Multiple winners → Phase 2 tiebreaker decides holder
3 guilds beat defender in Phase 1 → Phase 2 bracket → exactly one final holder.

### TC-223: Phase 1 HP carries into Phase 2 (no full heal)
Verify Phase 2 fighters use Phase 1 remaining HP from DB.

### TC-224: Declaration only by guild leader
Non-leader declare → 400. Leader declare → 200.

### TC-225: Guild already holding cannot declare attack
Holder guild declares → rejected (must defend).

### TC-226: Territory bonus applied to controlling guild members
Holder's members get +10% XP / +10% bronze via getBonusForPlayer.

### TC-227: Exclusive bonus per territory (fishing/mining/quest XP)
Each territory's exclusiveBonus maps to the right activity.

### TC-228: Defense streak debuff caps at 50%
streak ≥ 10 → debuffPercent = 50 (already unit-tested; integration verifies via resolve).

---

*Updated 2026-06-03. Total: 309 tests. Ambush: TC-209-218 · Territory cycle: TC-219-228.*

---

## Economic Sinks — Durability, Repair, Reforge, Heal scaling, Territory upkeep (TC-239 to TC-252)

### Durability + Repair (TC-239-244)
**Class:** `DurabilityIntegrationTest` / `InventoryServiceTest`

### TC-239: Battle reduces equipped item durability by 1-10
After an arena/zone fight, equipped items lose between 1 and 10 durability points.

### TC-240: Item at 0 durability gives no bonus
Set item durability to 0 → warrior total stats exclude that item's ATK/DEF/HP.

### TC-241: Durability never goes negative
Repeated battles floor durability at 0, not below.

### TC-242: Repair restores durability to 100
POST repair → durability = 100, bonuses reapply.

### TC-243: Repair cost = lostPoints × rarity × 5
Item rarity 4 with 50 lost points → cost 50×4×5 = 1000 bronze; balance debited.

### TC-244: Repair with insufficient bronze → 400
No bronze → repair rejected.

### Reforge (TC-245-247)

### TC-245: Reforge cost = rarity² × 200
Epic (r4) reforge → 3200 bronze debited.

### TC-246: Reforge re-rolls stats keeping rarity
After reforge, item rarity unchanged; stats within the rarity's range.

### TC-247: Reforge with insufficient bronze → 400

### Heal scaling (TC-248-249)

### TC-248: Heal cost = level × 10 for level > 10
Level 50 warrior heal → 500 bronze debited.

### TC-249: Heal free for level ≤ 10
Level 5 warrior heal → 0 cost.

### Territory upkeep (TC-250-252)

### TC-250: Upkeep cost = 500 × (1 + streak×0.1)
Holding guild with streak 3 → upkeep 650 guild gold deducted at cycle.

### TC-251: Treasury covers upkeep → territory kept
Guild with enough guild gold pays → still controls territory.

### TC-252: Treasury cannot cover → territory becomes neutral
Guild with insufficient guild gold → territory reverts to neutral, streak resets.

---

## Seção — Reinos V2 (cobertura)

Mudanças do Reinos V2 e onde estão cobertas no código (a numeração TC-xxx do código segue a própria
suíte; abaixo resumo por área):

| Área | O que verifica | Arquivo de teste |
|------|----------------|------------------|
| Unificação Kingdom/Território | `Territory` removido; território == reino; campos de batalha absorvidos | `KingdomServiceTest` (tc051), `TerritoryServiceTest`, `TerritoryIntegrationTest` |
| Flag de guild-war | só reinos da config são contestáveis (3 de 5) | `TerritoryIntegrationTest`, `TerritoryCatchUpIntegrationTest` |
| 5 reinos no World | `GET /api/world` → 5 reinos; `/quests` → vitrine de 2 por reino | `WorldIntegrationTest` (tc142, tc143, tc157) |
| 6 quests por reino | cada reino tem exatamente 6 quests | `KingdomServiceTest` (tc050) |
| Vitrine rotativa (Quests V2) | `getQuestsForKingdom` mostra 2 das 6, revezando a cada 6h | `KingdomServiceTest` (tc050b — `rotatingWindow`) |
| Combate na coleta (Quests V2) | encontro de monstro: vencer → XP/bronze; perder → 0 recompensa; narrativa sempre presente | `KingdomQuestCombatTest`, `KingdomQuestNarratorTest` |
| Garimpo | nova skill GARIMPO; fragmentos de joia; mineração sem gemas | `GatheringIntegrationTest`, `GatheringServiceTest` |
| Split de peixe | peixe de estamina (só estamina) vs peixe de vida (só HP, cap 90%) | `GatheringIntegrationTest`, `ZoneAmbushIntegrationTest` |
| Estamina na coleta | custo proporcional (~metade dos min, mín. 5); pulado em instant | `GatheringServiceTest` (`staminaCostFor`) |
| Caçada PvE (Fortaleza) | `POST /api/world/COMBAT/raid` rende gold/XP/materiais; só em COMBAT | `CovilRaidTest` |

---

## Seção — Cozinha (cobertura)

| Área | Verifica | Arquivo |
|------|----------|---------|
| Cozinhar | consome o peixe certo, +1 refeição; sem peixe → 400 | `CookingIntegrationTest` |
| Comer | consome 1 refeição, seta o buff Bem Alimentado; sem refeição → 400 | `CookingIntegrationTest` |
| Buff no combate | `combatStats` soma o buff de refeição (e os 2 do Templo) quando ativo | `CookingIntegrationTest` |
| Some na derrota | `clearBuff()` limpa o slot Bem Alimentado | `CookingIntegrationTest` |
| Receitas | `GET /api/cooking/recipes` lista as 10 receitas com efeito/canCook | `CookingIntegrationTest` |

## Seção — Auditoria (fechamento de deferrals)

| Item | Verifica | Arquivo |
|------|----------|---------|
| BL-5 (@Valid DTOs) | payload inválido → 400 em Smithing/Zone/Mail/Guild | `DtoValidationTest` |
| BL-1 (retry emboscada) | collect refaz sob 409, esgota → relança | `ZoneCollectCoordinatorTest` |
| M6 (JWT no reset) | token antigo → 401 após reset; novo login → 200 | `JwtInvalidationTest` |
| M15 (getOrCreate concorrente) | idempotência + recovery do conflito | `ConcurrentCreateTest` |
| A4 (piso da reforja) | total ≥ 45% do máx em 30 reforjas | `EconomicSinksIntegrationTest` |

---

*Updated 2026-06-03. Total: 441 tests passing. Reinos V2: 5 reinos unificados, Garimpo, split de peixe
(estamina/vida), estamina na coleta, caçada PvE na Fortaleza, flag de guild-war. Quests V2: 6 quests/reino
com vitrine rotativa de 2 (6h), encontro de monstro na coleta e lore narrada. Cozinha: peixe → refeição →
buff de combate (slot Bem Alimentado); buffs agora entram no combate. Auditoria: BL-1/BL-5/M6/M15/A4 + A9
fechados. Economic sinks TC-239-252 em `EconomicSinksIntegrationTest`/`InventoryItemDurabilityTest`.*
