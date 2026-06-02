# Game Design Document — Medieval Chronicles

**Versão:** 1.0  
**Data:** Junho 2026  
**Autor:** Rodrigo  
**Status:** Em desenvolvimento ativo

---

## 1. Visão Geral

### 1.1 Conceito

**Medieval Chronicles** é um RPG idle/browser de temática medieval. O jogador cria um guerreiro, gerencia sua progressão e o envia em missões, duelos e expedições com timers — voltando depois para colher as recompensas. O foco está na tomada de decisão estratégica (quando e para onde mandar o guerreiro) e na progressão de longo prazo (itens, nível, habilidades).

### 1.2 Referências

- **Torn City** (browser RPG assíncrono)
- **Runescape** (progressão de skills, economia de itens)
- **Ogame** (timers, idle progressivo)

### 1.3 Proposta de Valor

> "Um RPG medieval de navegador que respeita o tempo do jogador — você manda seu guerreiro, vive sua vida, e volta para ver o resultado."

- Sessões curtas e eficientes (decidir + coletar em minutos)
- Profundidade de progressão (itens, habilidades, pvp, economia)
- Sem pay-to-win — todos começam igual
- Narrativa emergente via logs de batalha e lore de itens

### 1.4 Plataformas

| Fase | Plataforma |
|------|-----------|
| Atual | Web Browser (Spring Boot + Vanilla JS) |
| Futuro | Cliente Godot (desktop) |
| Lançamento | Steam |

### 1.5 Público-Alvo

- Jogadores casuais que gostam de RPG mas não têm tempo para sessões longas
- Fãs de jogos medievais clássicos de browser (Tibia, Ogame, Torn)
- Jogadores com nostalgia de RPGs de navegador dos anos 2000
- Faixa etária: 18-35 anos

---

## 2. Loop de Gameplay

### 2.1 Loop Principal (Core Loop)

```
DECIDIR → ENVIAR → ESPERAR → COLETAR → CRESCER → DECIDIR
```

1. **Decidir**: Qual atividade priorizar? (missão, arena, trabalho, expedição)
2. **Enviar**: Guerreiro entra na atividade (timer começa)
3. **Esperar**: Jogador faz outras coisas enquanto o timer corre
4. **Coletar**: Volta ao jogo, vê o resultado (log de batalha, drops, recompensas)
5. **Crescer**: Usa bronze para comprar itens, distribui pontos de atributo, sobe skills
6. **Decidir**: Próxima atividade, ciclo recomeça

### 2.2 Loop Secundário (Progressão)

```
Bronze → Comprar/Craftar Itens → Stats mais altos → Missões melhores → Mais Bronze
```

- Melhorar o guerreiro desbloqueia conteúdo de maior risco/recompensa
- A curva de progressão nunca tem teto — sempre há um andar mais alto na Torre, uma zona mais perigosa

### 2.3 Loop de Recursos (Crafting)

```
Minerar/Pescar → Guardar Recursos → Refinar → Craftar → Equipar/Vender
```

- Alternativa à loja para conseguir equipamentos com sockets garantidos
- Joias extraídas de minérios encaixam em sockets para bônus permanentes

---

## 3. Sistemas de Jogo

### 3.1 Guerreiro

Cada conta tem um único guerreiro da classe **WARRIOR**.

**Stats base (nível 1):**
- ATK: 15 | DEF: 12 | HP: 110

**Progressão por nível:**
- +2 ATK, +2 DEF, +15 HP, +5 pontos de atributo

**Atributos distribuídos pelo jogador (irreversível):**

| Atributo | Efeito por Ponto |
|----------|-----------------|
| Força | +1 ATK |
| Destreza | +1% chance de evasão |
| Constituição | +5 HP, +0.5 DEF |
| Sorte | +1% chance de drop de item |

**Estados do guerreiro:**
- **Livre**: pode iniciar qualquer atividade
- **Em missão** (`onMission = true`): bloqueado até o timer acabar
- **Inconsciente** (`HP = 0`): não pode entrar em combate; precisa curar no Templo

### 3.2 Stamina

- 0-100%, regenera 100% em 2 horas passivamente
- Consumida por: missões (10-50), arena (25), torre (25)
- Expedições em zonas não consomem stamina diretamente
- Guerreiro com stamina insuficiente não pode iniciar a atividade

### 3.3 HP com Regen Passiva

- Armazenado como % (0-100), regenera 100% em 1 hora passivamente
- Regen começa a partir de 99% (qualquer dano inicia a regeneração)
- HP=0: guerreiro inconsciente, não entra em combate até curar

---

## 4. Atividades

### 4.1 Missões (Quests)

Timer-based. O guerreiro vai e volta. O jogador coleta depois.

| Missão | Duração | Bronze | XP | Stamina | Drop% | Drop Raridade |
|--------|---------|--------|-----|---------|-------|--------------|
| Patrulha | 5 min | 100 | 50 | 10 | 10% | Comum |
| Masmorra | 10 min | 250 | 150 | 20 | 25% | Comum/Incomum |
| Raid | 20 min | 500 | 300 | 35 | 40% | Incomum/Raro |
| Caça ao Chefe | 30 min | 1.000 | 750 | 50 | 60% | Raro/Épico |

- Drop: quantidade de sorte do guerreiro soma ao % base
- Item gerado com lore e origem automáticos
- Possível abandonar sem recompensa para liberar o guerreiro

### 4.2 Arena PvP

Duelo assíncrono contra outro jogador (ou NPC se não houver oponente real).

- Custa **25 stamina**
- Timer: 1 minuto em produção (instantâneo em dev)
- Combate resolvido automaticamente pelo `BattleSimulator`
- Resultado coletado depois; log completo de batalha exibido

**Recompensas:**
- Vitória: +200 bronze, +25 rank points
- Derrota: +50 bronze (consolação), -15 rank points
- Derrota: HP=0, buff perdido

**Ranking**: Top 20 por rank points, exibe nome do guerreiro

### 4.3 Torre Infernal

Dungeon com andares progressivos. Cada andar tem um chefe único.

- Entra a qualquer momento (custa **25 stamina**)
- Luta automática contra o chefe do andar atual
- Vitória: avança de andar, recebe bronze + XP, pode continuar ou sair
- Derrota: expulso, HP=0, buff perdido. Próxima entrada começa no andar seguinte ao melhor completo

**Chefes por Andar:**

| Andares | Chefes |
|---------|--------|
| 1-3 | Esqueleto, Goblin, Rato Gigante |
| 4-6 | Aranha Gigante, Orc, Troll |
| 7-9 | Zumbi, Vampiro, Golem de Pedra |
| 10-12 | Cavaleiro Negro, Arqueiro Sombrio, Ogro |
| 13-15 | Xamã das Trevas, Wyvern, Lich Menor |
| 16+ | Dragão Ancião, Titan, Lich Ancião, Guardiões Lendários |

**Recompensas**: bronze = andar × 40 | XP = andar × 20

**Ranking**: melhor andar já completado (histórico permanente)

### 4.4 Trabalho

Emprego temporário por 1-12 horas. Renda passiva em bronze.

| Emprego | Bronze/h | Level mín (guerreiro) | XP/h |
|---------|---------|----------------------|------|
| Ajudante da Taverna | 15 | 1 | 3 |
| Cuidador dos Estábulos | 20 | 1 | 4 |
| Carregador de Mercadorias | 30 | 1 | 6 |
| Ajudante do Ferreiro | 45 | 2 | 8 |
| Guarda da Nobreza | 65 | 3 | 12 |
| Mercenário Local | 100 | 5 | 18 |

- Cada profissão tem XP e nível separados (+5% de bônus por nível)
- Cancelar: recebe proporcional às horas completas

### 4.5 Zonas e Expedições

O guerreiro vai a uma das três zonas por até 12 horas.

| Zona | Level mín | Multiplicador de recursos | Risco PvP/h |
|------|-----------|--------------------------|------------|
| Zona Segura | 1 | ×1.0 | 0% |
| Zona PvP | 10 | ×1.5 | 20% |
| Zona Alto Risco | 20 | ×2.5 | 40% |

**Papéis:**
- **Gatherer**: coleta pesca ou mineração com multiplicador da zona. Pode ser atacado por hunters e NPCs
- **Hunter**: caça gatherers ativos. Vitória rouba 15% do bronze do alvo

**NPCs por Zona:**
- Segura: Lobo, Bandoleiro, Urso, Javali
- PvP: Mercenário Corrupto, Orc, Cavaleiro Renegado
- Alto Risco: Demônio Menor, Lich, Dragão Jovem

**Consequências de derrota:**
- HP=0, stamina=0, buff perdido, perde 15% do bronze
- Alto Risco: 10% de chance de perder 1 item equipado não-protegido

---

## 5. Habilidades de Coleta

3 habilidades independentes por jogador. Level 1-100.

### 5.1 Pesca

| Duração | Peixe | Stamina restaurada |
|---------|-------|--------------------|
| 5 min | Peixe Pequeno | +10 |
| 10 min | Salmão | +25 |
| 20 min | Atum | +40 |
| 30 min | Tubarão | +60 |
| 40 min | Peixe Lendário | +80 |

- Peixes são consumíveis de stamina

### 5.2 Mineração

- Produz: Cobre, Ferro, Prata, Ouro, Mithril
- Chance de fragmentos de joias por tipo de minério
- Durações: 10/20/30/45/60 min

### 5.3 Forja (Smithing)

- **Refinar**: 5 minérios + bronze → 1 barra (custo escala por tier)
- **Craftar equipamento**: barras → item forjado com sockets garantidos
- **Craftar joia**: 3 fragmentos do mesmo tipo → 1 joia

**Joias e bônus:**

| Joia | ATK | DEF | HP | Efeito especial |
|------|-----|-----|----|----------------|
| Rubi | +5 | — | — | — |
| Safira | — | +5 | — | — |
| Esmeralda | — | — | +20 | — |
| Diamante | +3 | +3 | +10 | — |
| Ametista | — | — | — | +5% drop chance |

---

## 6. Inventário e Equipamentos

### 6.1 Slots de Equipamento (10)

Capacete · Armadura · Espada · Escudo · Calça · Bota · Luva · Ombreira · Colar · Anel

### 6.2 Raridades

| Raridade | Cor | Sockets máx | Fonte típica |
|----------|-----|-------------|--------------|
| Comum | Cinza | 0 | Patrulha, Loja |
| Incomum | Verde | 1 | Masmorra, Loja |
| Raro | Azul | 2 | Raid, Loja |
| Épico | Roxo | 3 | Caça ao Chefe, Forja |

### 6.3 Lore de Itens

Todo item gerado automaticamente recebe:
- **Descrição (lore)**: texto baseado em raridade e tipo do item
- **Origem**: onde foi encontrado (missão, loja, forjado, drop, inicial)

---

## 7. Economia

### 7.1 Sistema de Moedas (3 Tier)

```
100 bronze = 1 prata
100 prata  = 1 ouro (= 10.000 bronze)
```

- Novos jogadores começam com **50 prata**
- Display: `2🥇 30🥈 45🥉`
- Futura 4ª moeda VIP planejada (para conteúdo late-game)

### 7.2 Fontes de Bronze

| Fonte | Ganho típico |
|-------|-------------|
| Patrulha | 100 bronze |
| Caça ao Chefe | 1.000 bronze |
| Arena (vitória) | 200 bronze |
| Torre (andar 10) | 400 bronze |
| Trabalho (Mercenário, 8h) | 800 bronze |
| Vender item Épico | 500-2.000 bronze |

### 7.3 Gastos de Bronze

| Gasto | Custo |
|-------|-------|
| Cura no Templo (lv > 10) | 100 bronze |
| Buff de força | 30 bronze |
| Proteger item | 50 bronze |
| Refinar minério (Cobre) | 50 bronze |
| Compra na loja (Comum) | 40-150 bronze |
| Compra na loja (Épico) | 1.000-3.000 bronze |

### 7.4 Loja (Comércio)

- 10 itens novos a cada **6 horas** (sincronizado para todos os jogadores)
- Cada item pode ser comprado uma vez por rotação por jogador
- Distribuição: 60% Comum, 25% Incomum, 12% Raro, 3% Épico
- Pool de 57+ itens cobrindo todos os 10 slots
- Mercador tem nome e frase temática randomizados a cada rotação

---

## 8. Guildas

### 8.1 Conceito

Grupos sociais permanentes que conectam jogadores, criam senso de pertencimento e desbloqueiam conteúdo cooperativo futuro (masmorras em grupo, dominação de castelo).

### 8.2 Criação e Acesso

- Criar guilda custa **100 bronze** (barreira mínima para evitar spam)
- Nome único no servidor, 3-30 caracteres
- Um jogador só pode pertencer a **uma guilda por vez**
- Sem restrição de nível para entrar ou criar

### 8.3 Hierarquia

| Papel | Permissões |
|-------|-----------|
| **Líder** | Todas as ações: expulsar, transferir liderança, dissolver, subir nível |
| **Membro** | Entrar, sair, doar bronze |

- Liderança é transferida explicitamente ou ao líder sair como único membro (dissolve)
- Líder não pode sair com outros membros ativos — deve transferir ou dissolver

### 8.4 Capacidade, Nível e Bônus Passivos

Ao subir de nível, a guilda aumenta a capacidade de membros **e concede bônus passivos permanentes a todos os membros**, aplicados automaticamente em quests e trabalho.

| Nível | Membros | XP Bonus | Drop Bonus | Bronze Bonus | Custo (guild gold) |
|-------|---------|----------|------------|--------------|-------------------|
| 1 | 15 | +0% | +0% | +0% | — |
| 2 | 20 | +5% | +0% | +0% | 1.000 |
| 3 | 25 | +10% | +3% | +0% | 2.000 |
| 4 | 30 | +15% | +5% | +5% | 3.000 |
| 5 | 35 | +20% | +7% | +10% | 4.000 |
| 6+ | +5/lv | +20% (cap) | +7% (cap) | +10% (cap) | (N-1)×1.000 |

**Fórmulas:**
- `xpBonus    = min(20, (level - 1) × 5)` %
- `dropBonus  = min(7,  max(0, level - 2) × 2)` %
- `bronzeBonus= min(10, max(0, level - 3) × 5)` %

**Onde são aplicados:**
- **XP bonus**: quests (collectReward), trabalho (collectWork)
- **Drop bonus**: chance de drop de item em quests
- **Bronze bonus**: recompensa bronze em quests e trabalho

### 8.5 Economia da Guilda

- Membros doam bronze → acumula como **guild gold**
- Guild gold é usado exclusivamente para subir nível da guilda
- Não há conversão de volta (doação é irreversível)

### 8.6 Endpoints

| Método | Rota | Ação |
|--------|------|------|
| GET | `/api/guild` | Info da guilda do jogador |
| GET | `/api/guild/list` | Lista todas as guildas |
| POST | `/api/guild` | Criar guilda |
| POST | `/api/guild/join/{id}` | Entrar |
| POST | `/api/guild/leave` | Sair |
| POST | `/api/guild/kick/{playerId}` | Expulsar membro (líder) |
| POST | `/api/guild/transfer/{playerId}` | Transferir liderança |
| POST | `/api/guild/donate` | Doar bronze |
| POST | `/api/guild/levelup` | Subir nível (líder) |
| DELETE | `/api/guild` | Dissolver (líder) |

### 8.7 Futuro (requer guilda)

- **Masmorra em grupo**: membros entram individualmente, batalha resolvida quando todos confirmam
- **Dominação de castelo**: disputa territorial por zona, timer global, recompensas coletivas

---

## 9. Templo

Local de recuperação e fortalecimento.

### 8.1 Cura de HP

- Restaura HP para 100% instantaneamente
- Grátis para guerreiros nível ≤ 10
- 100 bronze para guerreiros nível > 10

### 8.2 Bênçãos (Buffs)

- Um buff por vez, dura **1 hora**
- Buff é **perdido ao ser derrotado** em qualquer combate
- Ativado no Templo por bronze

### 8.3 Proteção de Itens

- Máximo 3 itens protegidos simultaneamente
- Custo: 50 bronze por item (permanente até desproteger)
- Itens protegidos **não caem** na Zona de Alto Risco

---

## 10. Sistema de Combate

O `BattleSimulator` é compartilhado por Arena, Torre e Zonas.

### 9.1 Fluxo de uma Rodada

```
1. Atacante tenta acertar
2. Defensor tenta evadir (chance = Destreza%)
3. Se não evadir: dano = ATK do atacante - DEF do defensor (mín. 1)
4. HP do defensor reduzido
5. Turno passa para o outro combatente
6. Combate termina quando HP de um chega a 0
```

### 9.2 Log de Batalha

- Textos de ataque variados ("avança ferozmente", "desfere um golpe", etc.)
- Partes do corpo aleatórias ("no peito", "no ombro", "na cabeça")
- Evasões com texto descritivo
- HP exibido em vermelho após cada dano
- Vitória em texto dourado

---

## 11. Narrativa e Mundo

### 10.1 Ambientação

Reino medieval genérico em conflito constante. O jogador é um aventureiro anônimo que chega à cidade e busca fama e fortuna. Não há uma história linear — a narrativa emerge das ações do jogador.

### 10.2 Lore Emergente

- Logs de batalha únicos a cada combate (textos gerados aleatoriamente)
- Cada item tem sua história de origem (onde foi encontrado, por quem)
- Textos narrativos nas missões ("As trevas da masmorra foram varridas...")
- Nome e personalidade do mercador muda a cada rotação

### 10.3 Tom

Medieval fantástico sem seriedade excessiva. Linguagem direta, textos curtos. Foco na competição entre jogadores e na progressão — não em cutscenes ou diálogos longos.

---

## 12. Interface (UI)

### 11.1 Estrutura de Telas

```
[Header] — Nome do guerreiro | Bronze / Prata / Ouro | Stamina | HP
[Navbar] — Guerreiro | Missões | Arena | Torre | Trabalho | Habilidades | Inventário | Loja | Zonas | Templo
[Content] — Área principal da seção atual
```

### 11.2 Princípios de UI

- Informação visível sem precisar clicar: status atual, timers, saldo
- Feedback imediato após cada ação
- Log de batalha legível, rolável, com cores por tipo de evento
- Mobile-friendly (responsivo)

### 11.3 Telas Principais

| Tela | Função |
|------|--------|
| Guerreiro | Stats, atributos, HP/stamina, buff ativo |
| Missões | Escolher tipo, ver ativas, coletar, abandonar |
| Arena | Ver ranking, iniciar duelo, coletar resultado |
| Torre | Andar atual, boss info, entrar, sair |
| Trabalho | Escolher emprego, horas, cancelar |
| Habilidades | Pesca, mineração, forja — iniciar sessão, coletar, refinar, craftar |
| Inventário | Itens equipados, itens no bag, vender, equipar, encaixar joias |
| Loja | Itens do mercador, timer da rotação, comprar |
| Zonas | Escolher zona e papel, coletar expedição |
| Templo | Curar, proteger itens, ativar buffs |

---

## 13. Progressão do Jogador

### 12.1 Linha do Tempo Esperada

| Fase | Level | Foco |
|------|-------|------|
| Iniciante | 1-5 | Patrulhas, aprender sistemas, equipar itens iniciais |
| Desenvolvimento | 5-15 | Arena, Torre (andares 1-10), craftar primeiros itens |
| Mid-game | 15-30 | Zona PvP, Raid/Caça ao Chefe, joias nos equipamentos |
| Late-game | 30+ | Zona Alto Risco, Torre 16+, otimizar builds |

### 12.2 Desbloqueios por Nível

| Nível | Desbloqueio |
|-------|-------------|
| 1 | Tudo básico: missões, arena, trabalho, pesca |
| 1 | Trabalho: Taverna, Estábulos |
| 1 | Mineração (10 min mínimo) |
| 2 | Trabalho: Ajudante do Ferreiro |
| 3 | Trabalho: Guarda da Nobreza |
| 5 | Trabalho: Mercenário Local |
| 10 | Zona PvP (expedição) |
| 20 | Zona de Alto Risco (expedição) |

---

## 14. Plano de Lançamento

### 14.1 Estado Atual (v0.2 — Web)

- 21 sistemas implementados e funcionais (incluindo Guildas)
- 147 testes automatizados (80 unitários + 67 integração)
- CI/CD via GitHub Actions (mvn test em cada push)
- Deploy automático na Railway com PostgreSQL
- Sem monetização

### 14.2 Próximos Passos (v0.3 — Conteúdo Cooperativo)

- [ ] Tutorial in-game para novos jogadores
- [x] ~~Sistema de guild / clã~~ ✓ implementado
- [ ] Masmorra em grupo (requer guilda)
- [ ] Dominação de Castelo (requer guilda)
- [ ] Mais tipos de itens e bosses
- [ ] Balanceamento de economia (baseado em dados reais de uso)
- [ ] Notificações quando timer termina (push notification ou email)

### 14.3 Fase Godot (v1.0 — Cliente Desktop)

- [ ] Cliente Godot com assets visuais 2D medievais
- [ ] Mesma API REST como backend (sem reescrever lógica)
- [ ] Animações de combate (battle log vira cenas animadas)
- [ ] Sons e trilha sonora

### 14.4 Steam (v1.0 — Lançamento)

- [ ] Página na Steam com screenshots e trailer
- [ ] Conquistas Steam (Tower floor 10, Arena rank top 10, etc.)
- [ ] Modelo: Free to Play, sem pay-to-win
- [ ] Possível DLC cosmético (skins de guerreiro, nome especial do mercador)

---

## 15. Considerações Técnicas

### 14.1 Stack Atual

| Camada | Tecnologia |
|--------|-----------|
| Backend | Java 17 + Spring Boot 3.2.5 |
| Banco (prod) | PostgreSQL (Railway) |
| Banco (dev/test) | H2 in-memory |
| Frontend atual | Vanilla JS + HTML/CSS |
| Frontend futuro | Godot (cliente desktop) |
| Email | Brevo HTTP API |
| CI | GitHub Actions (mvn test) |

### 14.2 Decisões de Design com Impacto Técnico

- **Sem open-in-view**: lazy loading gerenciado explicitamente via `@EntityGraph`
- **Timers no servidor**: o cliente nunca confia em horários locais
- **instant-complete=true em dev**: torna todos os timers zero para testes rápidos
- **3 moedas separadas no banco**: nunca misturar diretamente, sempre usar `addBronzeAmount()`

---

## 16. Glossário

| Termo | Definição |
|-------|-----------|
| **Idle** | Estilo de jogo onde o progresso continua sem o jogador ativo |
| **onMission** | Flag que bloqueia o guerreiro de iniciar novas atividades |
| **Stamina** | Recurso consumível que limita quantas atividades o guerreiro faz por sessão |
| **Socket** | Slot em um item para encaixar uma joia e ganhar bônus |
| **Lore** | Texto de história/flavor de um item gerado automaticamente |
| **Hunter** | Papel em zonas: caça outros jogadores que estão coletando |
| **Gatherer** | Papel em zonas: coleta recursos (pesca ou mineração) |
| **BattleSimulator** | Módulo compartilhado que resolve todos os combates do jogo |
| **Buff** | Bônus temporário de 1 hora aplicado no Templo |
| **Instant-complete** | Modo dev que zera todos os timers para testar sem esperar |
