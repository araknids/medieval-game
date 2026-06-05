# Game Design Document — Medieval Chronicles

**Versão:** 1.1 (atualizado 2026-06-05)  
**Autor:** Rodrigo  
**Status:** Em desenvolvimento ativo

---

## ⚠️ ATUALIZAÇÃO 2026-06-05 — Pilar mudou: Sem-Timer + PvP de Zona (leia primeiro)

O pilar do jogo deixou de ser "envia com timer, volta depois" e passou a ser **burst play centrado em estamina**: chega, gasta estamina em ações **instantâneas**, pega a recompensa na hora, sai e volta quando a estamina regenera (100% em 1h). Isso supera várias seções abaixo. Fonte da verdade: o código + `docs/PLANO_SEM_TIMER_PVP.md`.

- **Sem timers de atividade.** Missão/coleta/trabalho/zona/treino/arena = instantâneo. Sem espera, sem "coletar depois". Gate = estamina.
- **PvP de Zona com Flag (loot do late):** farmar zona 🟡/🔴 flagga o player 1h e expõe seus itens/recursos; quem entra na zona pode cruzar e saquear (matchmaking ±10 níveis). Tiers de risco/recompensa: 🟢SAFE (PvE), 🟡 (recursos+bronze), 🔴 (+ item + XP). Stash/Templo protegem; escudo pós-derrota.
- **Coleta = zona:** toda coleta roda pelo sistema de zona (PvP nas amarelas/vermelhas), com drops por reino.
- **Arena:** duelo instantâneo por ranking (sem loot).
- **Guerra de guild/territórios:** segue por ciclos agendados (não é "timer de atividade").

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
- ATK: 15 | HP: 110
- DEF: vem exclusivamente de equipamentos (não de atributo base)

**Progressão por nível:**
- +**2 pontos de atributo** por nível para distribuir livremente
- **Sem cap de nível** — curva exponencial inspirada em Tibia

**Curva de XP:** `XP para nível N = round(100 × N^1.8)`

**Atributos distribuídos pelo jogador — sistema d20:**

| Atributo | Cap | Efeito |
|---------|-----|--------|
| **Força (STR)** | 60 | +1 ATK/ponto; bônus de ataque `floor(STR/20)` (0-3) no d20 |
| **Destreza (DEX)** | 40 | +1 Armor Class/ponto (AC = 10 + DEX); torna mais difícil ser acertado |
| **Constituição (CON)** | ∞ | +8 HP/ponto — cresce infinitamente; razão de upar além dos outros caps |
| **Sorte (LUK)** | 50 | +1% drop; expande janela de crítico; Fortune Save contra crits inimigos |
| **Intelecto (INT)** | 40 | +0.5% sucesso Smithing; -0.2% custo bronze em treino; +0.3% yield coleta |

**Design de progressão:**
- Levels 1-95: distribui entre STR/DEX/LUK/INT até os caps
- Level 95+: todo ponto em CON (HP) para sempre
- Veterano = mais HP, não mais dano — evita one-shot em PvP late game

**Reset de atributos:**
- Compra permanente com SoulStones (ver seção VIP)
- Ou via evento de reset global do servidor quando há mudanças de sistema

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
| Patrulha | 5 min | 180 | 40 | 10 | 10% | Comum |
| Masmorra | 10 min | 320 | 110 | 20 | 25% | Comum/Incomum |
| Raid | 20 min | 480 | 320 | 35 | 40% | Incomum/Raro |
| Caça ao Chefe | 30 min | 600 | 750 | 50 | 60% | Raro/Épico (+Lendário ~6%) |

> Combate V2 — **nichos**: curtas (Patrulha/Masmorra) = melhor **bronze** por estamina/tempo;
> longas (Raid/Caça) = melhor **XP**. Nenhum tipo domina os dois eixos.

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
- **Sem XP loss na Arena** (apenas em zonas PvP/Alto Risco)

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

### 4.5 Zonas e Expedições (Loot com PvP por probabilidade)

O guerreiro vai a uma das três zonas por até 12 horas para **lootear** recursos.

| Zona | Level mín | Multiplicador de recursos | Risco PvP/h | Risco NPC/h |
|------|-----------|--------------------------|------------|------------|
| Zona Segura | 1 | ×1.0 | 0% | 15% |
| Zona PvP | 10 | ×1.5 | 20% | 25% |
| Zona Alto Risco | 20 | ×2.5 | 40% | 35% |

**Conceito — sem papéis separados:**
Não existe mais "Hunter" vs "Gatherer". **Todos entram para lootear.** O risco é ser **emboscado por outro jogador** que também está looteando na mesma zona. O matchmaking é por probabilidade, resolvido no `collect`.

**Resolução (modelo lazy, por hora):**
1. Rola PvP da zona → se rolou E há outro player `IN_PROGRESS` na zona → emboscada (luta até a morte). Se não há ninguém → cai pra NPC.
2. Rola NPC → PvE normal.

**Emboscada PvP (luta d20 até nocaute):**
- Atacante = quem está coletando; Alvo = player in-progress sorteado (HP real)
- Vencedor sobrevive com HP restante + rouba 15% do bronze do perdedor
- Perdedor morre (HP=0): perde 15% bronze, 10% XP, e (Alto Risco) 10% chance de item

**Emboscadas múltiplas:**
- Só pode ser re-emboscado se venceu as anteriores (continua vivo)
- Cada vitória defensiva: -5% cumulativo na chance de nova emboscada (anti-farm)
- HP carrega entre emboscadas (regen passiva ou consumindo peixe)

**Notificação + decisão:**
- Mail automático ao alvo a cada ataque sofrido (atacante, bronze, item, HP, vivo/morto)
- Dialog "Continuar ou Recolher?" ao voltar ao jogo (só se sobreviveu)

**NPCs por Zona (PvE em paralelo):**
- Segura: Lobo, Bandoleiro, Urso, Javali
- PvP: Mercenário Corrupto, Orc, Cavaleiro Renegado
- Alto Risco: Demônio Menor, Lich, Dragão Jovem

**Consequências de derrota (atacante ou alvo):**
- HP=0, stamina=0, buff perdido, perde 15% do bronze (metade ao vencedor)
- Perde 10% do XP do nível atual (pode dropar nível, mínimo 1)
- Alto Risco: 10% de chance de perder 1 item equipado não-protegido

---

## 5. Habilidades de Coleta

4 habilidades independentes por jogador (Pesca, Mineração, Garimpo, Forja). Level 1-100.

> **Coleta gasta estamina (Reinos V2):** pescar/minerar/garimpar consomem estamina proporcional à
> duração (~metade dos minutos, mínimo 5) em produção. Cria o loop pescar-comer-coletar.

### 5.1 Pesca — dois tipos de peixe por reino (Reinos V2)

A pesca rende **peixe de estamina** ou **peixe de vida**, dependendo do reino:

| Reino | Peixes | Efeito |
|-------|--------|--------|
| Desfiladeiro do Osso | Pequeno/Salmão/Atum/Tubarão/Lendário | **Só estamina** (+10/+25/+40/+60/+80) |
| Mar Abençoado | Coral/Anjo/Espírito/Sagrado/Fênix | **Só HP** (+15/+30/+50/+70/+90%), **teto 90%** |

- Peixe de vida cura até **90%** — fechar (90→100%) e reviver de KO exigem Templo/regen
  (decisão de balance: não furar o sink de cura do Templo).
- Durações: 5/10/20/30/40 min. Importantes para se recuperar entre emboscadas em zonas PvP.

### 5.2 Mineração

- Produz **só minério**: Cobre, Ferro, Prata, Ouro, Mithril
- (Reinos V2) Gemas **não saem mais** da mineração — migraram para o Garimpo
- Durações: 10/20/30/45/60 min

### 5.3 Garimpo (Reinos V2)

- Skill nova; atividade do reino **Grutas de Cristal**
- Cada rodada pode achar um **fragmento de joia** (ou vir vazia) — escala por nível:
  Ametista → Rubi (20) → Safira (40) → Esmeralda (60) → Diamante (80)
- Fragmentos viram joias na Forja (3 do mesmo tipo → 1 joia)

### 5.4 Forja (Smithing)

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

| Raridade | Cor | Sockets máx | Afixos | Fonte típica |
|----------|-----|-------------|--------|--------------|
| Comum | Cinza | 0 | 0 | Patrulha, Loja |
| Incomum | Verde | 1 | 1 | Masmorra, Loja |
| Raro | Azul | 2 | 2 | Raid, Loja |
| Épico | Roxo | 3 | 3 | Caça ao Chefe, Forja |
| **Lendário** | Dourado | 3 | 4 | Caça ao Chefe (~6%), raid de reino (~5%) |

### 6.2.1 Afixos (Itens V2 — Fase A)

Cada item rola afixos aleatórios pela raridade (tabela acima). Um afixo dá **stat plano** (ATK/DEF/HP)
ou **atributo** (+STR/+DEX/+LUK, reusando o sistema D&D). Prefixo vira adjetivo no nome
("Sharp Sword of Steel"); todos aparecem como linhas no card. **Reforjar na Forja re-rola os afixos**
(dreno de bronze, caça ao roll perfeito). Sets de itens (bônus de conjunto) ficam pra Fase B.
Detalhe: `docs/PLANO_ITENS_V2.md`.

### 6.3 Lore de Itens

Todo item gerado automaticamente recebe:
- **Descrição (lore)**: texto baseado em raridade e tipo do item
- **Origem**: onde foi encontrado (missão, loja, forjado, drop, inicial)

### 6.4 Bag (Mochila)

- Jogadores free têm **10 slots** na bag
- Jogadores VIP (SoulStone) têm **20 slots** — ver seção 19
- Slots de equipamento (10 peças equipadas) **não** contam contra o limite da bag
- Se a bag estiver cheia ao receber um item (drop, quest, loja), o item é perdido com mensagem de aviso

### 6.5 Durabilidade (Sink econômico)

- Cada item de equipamento tem durabilidade **0–100** (começa cheia)
- Perde **1 a 10 pontos aleatórios por batalha** (arena, torre, zona, emboscada), nos itens equipados
- Item em durabilidade **0 não dá nenhum bônus** (ATK/DEF/HP/joias zerados) até ser reparado — não quebra permanentemente
- Reparo no Ferreiro (Commerce): `pontos perdidos × raridade × 5 bronze`
- Reforja (re-roll de stats): `raridade² × 200 bronze`, mantém a raridade
- Design: dreno contínuo que pressiona quem mais farma combate, escalando com a qualidade do gear

---

## 7. Economia

### 7.1 Sistema de Moedas (3 Tier + VIP)

```
100 bronze = 1 prata
100 prata  = 1 ouro (= 10.000 bronze)
💎 SoulStone = moeda VIP separada (ver seção 19)
```

- Novos jogadores começam com **50 prata**
- Display: `2🥇 30🥈 45🥉`
- SoulStone (💎) é obtida por compra, não por gameplay regular

### 7.2 Fontes de Bronze

| Fonte | Ganho típico |
|-------|-------------|
| Patrulha | 100 bronze |
| Caça ao Chefe | 1.000 bronze |
| Arena (vitória) | 200 bronze |
| Torre (andar 10) | 400 bronze |
| Trabalho (Mercenário, 8h) | 800 bronze |
| Vender item Épico | 500-2.000 bronze |

### 7.3 Gastos de Bronze (Sumidouros / Sinks)

| Gasto | Custo | Tipo |
|-------|-------|------|
| Cura no Templo (lv > 10) | **nível × 10** (escala) | repetível |
| Buff de força | 30 bronze | repetível |
| Proteger item | 50 bronze | único (3 slots) |
| Refinar minério (Cobre) | 50 bronze | repetível |
| **Reparar equipamento** | pontos perdidos × raridade × 5 | **repetível (contínuo)** |
| **Reforjar item (re-roll)** | raridade² × 200 | **repetível (late-game)** |
| **Manutenção de território** | 500 × (1 + streak×0.1) guild gold | **repetível por ciclo** |
| Compra na loja (Comum) | 40-150 bronze | 1×/rotação |
| Compra na loja (Épico) | 1.000-3.000 bronze | 1×/rotação |
| Treino (Fortaleza) | nível × 10 / hora | repetível |

### 7.4 Filosofia Anti-Inflação

O income (quest ~2k/h, zona de combate ~11k/coleta em alto nível) é repetível e escala. Para o servidor não inflar, os **sinks também precisam escalar com a riqueza/progresso**:

- **Durabilidade + Reparo**: dreno contínuo proporcional à qualidade do gear (perde 1-10 pts/batalha)
- **Cura escalável**: pega o late-game (lv100 paga 1.000/cura)
- **Reforja**: ralo dos ricos — min-maxer queima bronze atrás do roll perfeito (sem teto)
- **Manutenção de território**: controla o end-game de guild (quanto mais segura, mais caro)

Princípio: **não cortar income** (frustra), mas **dar drenos escaláveis** onde gastar.

### 7.5 Loja (Comércio)

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
- Guild gold é usado para subir nível da guilda **e pagar manutenção de território**
- Não há conversão de volta (doação é irreversível)

**Manutenção de território (sink de guild):**
- Guilda que controla um território paga manutenção a cada ciclo de guerra (6h)
- Custo = `500 × (1 + defenseStreak × 0.1)` guild gold — escala com o tempo de domínio
- Tesouro insuficiente → território vira neutro (perde por inadimplência), streak zera
- Força guildas dominantes a manter doações ativas para sustentar o domínio

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

### 8.7 Ranking de Doações

Exibido no painel da guilda abaixo da lista de membros. Mostra quem mais contribuiu para o tesouro da guilda na sessão atual.

- **Campo:** `Player.guildDonatedBronze` — acumula o total doado pelo jogador à guilda atual
- **Reset:** zerado ao entrar em nova guilda, sair ou a guilda ser dissolvida
- **Ordenação:** descendente por bronze doado
- **Exibição:** nome do guerreiro + total doado (formato bronze/prata/ouro)

### 8.9 Futuro (requer guilda)

- **Masmorra em grupo**: membros entram individualmente, batalha resolvida quando todos confirmam
- **Dominação de castelo**: disputa territorial por zona, timer global, recompensas coletivas

---

## 9. Templo

Local de recuperação e fortalecimento.

### 8.1 Cura de HP

- Restaura HP para 100% instantaneamente
- Grátis para guerreiros nível ≤ 10
- **Custo escalável: `nível × 10 bronze`** para nível > 10 (lv50=500, lv100=1.000) — sink de late-game

### 8.2 Bênçãos (Buffs)

- Um buff por vez (VIP: 2), dura **1 hora**
- Buff é **perdido ao ser derrotado** em qualquer combate
- Ativado no Templo por bronze
- **Os buffs entram de fato no combate** (somados em `WarriorStatsService.combatStats`). Além dos 2
  slots do Templo, existe um 3º slot **"Bem Alimentado"** vindo da **Cozinha** (refeições) — ver §3.x.

### 8.3 Proteção de Itens

- Máximo 3 itens protegidos simultaneamente
- Custo: 50 bronze por item (permanente até desproteger)
- Itens protegidos **não caem** na Zona de Alto Risco

### 8.4 Cura Instantânea via SoulStone

- Cura HP para 100% imediatamente, sem custo de bronze
- Custa **1 SoulStone** por uso
- Cooldown de **30 minutos** entre usos
- Disponível mesmo para guerreiros acima do nível 10 (não substitui a cura bronze — é adicional)
- Ver seção 19 para detalhes do sistema SoulStone

---

## 10. Sistema de Combate — d20 (D&D-inspired)

O `BattleSimulator` é compartilhado por Arena, Torre e Zonas. Sistema inspirado em D&D 5e com **Bounded Accuracy** — DEX não pode tornar um personagem invulnerável.

### 10.1 Conceito: Bounded Accuracy

O problema do sistema antigo (evasão linear 1:1) era que 100 DEX = 100% evasão = invulnerável. A solução D&D: **d20 com bônus limitados**. O dado sempre tem chance de acertar ou errar, independente dos atributos.

### 10.2 Fluxo de uma Rodada

```
1. Atacante rola d20 (resultado 1-20, uniforme)
2. Adiciona bônus de ataque: floor(STR / 20)   [range: 0 a +3]
3. Compara com AC do defensor: 10 + DEX do defensor
4. Se (d20 + bônus) ≥ AC → ACERTA
   Se (d20 + bônus) < AC → ERRA
5. Natural 20 (roll de 20) → Crítico: dano dobrado
   Natural 1  (roll de 1)  → Fumble: miss automático
6. Dano quando acerta: (ATK - DEF do defensor, mín. 1) + floor(STR/10)
7. HP do defensor reduzido; próxima rodada
```

### 10.3 Referência de Probabilidades

```
Chance de acertar = (21 - max(1, AC - bônus)) / 20

Exemplos:
• DEX 0 (AC 10) vs STR 0 (bônus +0): precisa 10+ → 55%
• DEX 20 (AC 30) vs STR 0 (+0): precisa 30+ → apenas natural 20 = 5%
• DEX 20 (AC 30) vs STR 60 (+3): precisa 27 → apenas natural 20 = 5%
• DEX 0 (AC 10) vs STR 60 (+3): precisa 7+ → 70%
```

> DEX alto + atacante fraco = muito difícil de acertar. Mas com STR alta e crits, ainda existe chance.

### 10.4 Crítico e Fumble

| Roll | Resultado |
|------|-----------|
| **20 (natural)** | Crítico — dano × 2 |
| **1 (natural)** | Fumble — miss automático |

**LUK expande a janela de crítico:**
- 0 LUK: crit apenas no 20 (5%)
- 15 LUK: crit no 19-20 (10%)
- 30 LUK: crit no 18-20 (15%)
- 45+ LUK: crit no 17-20 (20%, cap)

**Fortune Save (LUK) — contra críticos inimigos:**
- Ao receber um crítico: `floor(LUK/10)%` chance de converter para hit normal
- 0 LUK = 0%, 50 LUK = 5% (cap de LUK é 50)

### 10.5 XP Loss em Morte por PvP

Quando derrotado em Zona PvP ou Alto Risco:
- Perde **10% do XP necessário para o nível atual**
- Pode dropar de nível (mínimo: nível 1, nunca vai abaixo)
- Itens protegidos no Templo não caem (apenas Alto Risco)
- Visível no modal de resultado: "💀 -X XP"

### 10.6 Log de Batalha

- Textos variados por resultado (acerto, miss, crítico, fumble)
- Partes do corpo aleatórias
- HP exibido em vermelho após dano; vitória em dourado
- Tag interna `WINNER:Nome` removida antes de exibir

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

## 14. Guerra de Territórios

### 14.1 Conceito

Sistema de PvP em escala de guilda. Por padrão, **3 dos 5 reinos** são territórios de guild-war
(definido pela config `app.kingdoms.war-territories` = `FISHING,MINING,COMBAT`) e podem ser dominados
por guildas, concedendo bônus permanentes a seus membros enquanto o domínio for mantido. Batalhas
acontecem automaticamente 4 vezes por dia, criando conflito constante e rotação de poder.

> **Reinos V2 — unificação:** o enum `Territory` foi **removido** e fundido em `Kingdom`
> (território == reino, mesmo id). Cada `Kingdom` carrega seus dados de NPC/batalha e `exclusiveBonus`.
> Ligar guerra em mais reinos = trocar a config, sem deploy.

### 14.2 Os Três Territórios de Guerra (dos 5 reinos)

| Reino (território) | Tema | Bônus Exclusivo | NPC Neutro |
|------------|------|-----------------|------------|
| **Fortaleza Maldita** (COMBAT) | Fortaleza amaldiçoada por um lich antigo | +10% XP extra em quests | Cavaleiros Amaldiçoados |
| **Minas de Ferro Negro** (MINING) | Minas ancestrais de metal raro | +20% yield de mineração | Golens de Ferro |
| **Desfiladeiro do Osso** (FISHING) | Passagem estratégica entre reinos | +20% yield de pesca | Esqueletos Guerreiros |

Os outros dois reinos (Grutas de Cristal, Mar Abençoado) são **zonas abertas** — sem guild-war por padrão.

**Bônus base** para membros da guilda dominante em qualquer território: **+10% XP** e **+10% bronze** em todas as ações.

### 14.3 Regras de Participação

- Guilda **sem território**: líder pode declarar ataque em qualquer território antes do próximo ciclo
- Guilda **com território**: defende automaticamente — não pode atacar outro território
- Uma guilda só pode dominar **um território por vez**
- Guerreiros com HP > 0 participam com seu HP atual
- Guerreiros com HP = 0 (inconscientes) não participam

### 14.4 Ciclo de Batalhas (a cada 6h — 00h, 06h, 12h, 18h UTC)

**Território neutro + 1 atacante:**
A guilda ataca NPCs em quantidade igual ao número de membros dela. Vitória = domínio imediato.

**Território neutro + múltiplos atacantes:**
Cada guilda luta seus próprios NPCs separadamente. Quem vencer luta entre si (ordem de declaração); último sobrevivente domina.

**Território controlado + 1 atacante:**
Defensor luta contra atacante. Vencedor fica/toma o território.

**Território controlado + múltiplos atacantes — 2 Fases:**

**Fase 1 — Todos os atacantes lutam contra os defensores originais de forma independente:**
- Cada guild atacante luta contra os defensores originais (Guild X) separadamente
- Defensores **recuperam HP entre cada luta** da Fase 1 (exceto após a última)
- O HP restante de cada atacante após a Fase 1 é salvo no banco (**HP Fase 1**)
- Guilds que venceram os defensores avançam para a Fase 2

**Fase 2 — Desempate aleatório entre os vencedores da Fase 1:**
- Vencedores são **embaralhados aleatoriamente** (sem vantagem por ordem de declaração)
- Cada luta do desempate: ambas as guilds entram com o **HP Fase 1** (do banco)
- O HP **não** carrega entre lutas do desempate — cada luta reseta ao HP Fase 1
- "Você lutou com o defensor e ficou com o que sobrou" — mesma condição inicial para todos
- Vencedora da última luta domina o território

**Se todos os atacantes perderem na Fase 1:** defensor mantém, streak +1
**Se apenas um atacante vencer na Fase 1:** toma o território diretamente (sem Fase 2)

### 14.5 Mecânica de Batalha (Guild Brawl)

```
1. Coleta todos os membros de cada lado com HP > 0
2. Aplica debuff de defesa (se streak > 0 da rodada anterior)
3. Sorteia pares aleatórios (A1 vs B1, A2 vs B2...)
4. Resolve cada par via BattleSimulator (1v1 sequencial)
5. Vencedor do par entra no próximo combate ativo → 2v1
6. Continua até um lado ser eliminado
7. Atualiza HP de todos os guerreiros no banco
```

### 14.6 Debuff do Defensor (stack por rodadas consecutivas)

| Streak (defesas vencidas) | Debuff na próxima rodada |
|--------------------------|--------------------------|
| 0 | Nenhum (1ª defesa) |
| 1 | -5% ATK e DEF |
| 2 | -10% |
| 3 | -15% |
| N | min(50%, N × 5%) |

Reset completo quando o território troca de dono.

### 14.7 NPCs para Territórios Neutros

| Território | NPC | Estilo de combate |
|------------|-----|-------------------|
| Fortaleza Maldita | Cavaleiro Amaldiçoado | Alta DEF, moderado ATK |
| Minas de Ferro Negro | Golem de Ferro | Altíssimo HP, baixo ATK |
| Desfiladeiro do Osso | Esqueleto Guerreiro | Balanceado, alta evasão |

Stats do NPC: média dos warriors da guilda atacante × fator de dificuldade do território. Contagem: 1 NPC por membro da guilda atacante.

### 14.8 Endpoints

| Método | Rota | Ação |
|--------|------|------|
| GET | `/api/territory` | Lista os 3 territórios e status atual |
| POST | `/api/territory/{territory}/declare` | Declara ataque (requer guilda sem território) |
| GET | `/api/territory/{territory}/history` | Histórico de batalhas do território |
| GET | `/api/territory/my` | Status do território da guilda do jogador |

---

## 15. World Tab — 5 Reinos (Reinos V2) ✅ Implementado

> **Status:** Implementado (Fases 1-4, 410 testes verdes). A aba "World" substitui Taverna, Expedições,
> Habilidades (coleta) e Territórios, organizada em **5 reinos** interdependentes. O plano original previa
> 6 reinos; o "Covil das Feras" virou a caçada PvE dentro da Fortaleza Maldita.

### 15.1 Conceito Central

O mundo é organizado por **localização** em vez de por mecânica. Cada reino tem uma especialidade que força troca com os outros — nenhum é autossuficiente.

```
Pesca (estamina)  → Desfiladeiro do Osso → estamina premium    → todo mundo precisa
Pesca (vida)      → Mar Abençoado        → recupera HP (cap 90%)→ todo mundo precisa
Mineração         → Minas de Ferro Negro → minério = equipamento→ todo mundo precisa
Garimpo           → Grutas de Cristal    → fragmentos = joias   → quem forja
Combate           → Fortaleza Maldita    → XP + caçada PvE + war→ todo mundo quer
```

> **Coleta gasta estamina** (pesca/mineração/garimpo) em produção — fecha o loop pescar-comer-coletar.
> O custo aparece no botão de cada duração, e o resultado da coleta traz uma **lore curta** (variando
> por skill + reino) — o equivalente ao log de batalha para a coleta (`GatheringNarrator`).

> **Quests V2 (todos os reinos):** cada reino tem **6 quests**; a UI mostra **2 por vez**, revezando a
> cada 6h. Na coleta há uma **chance de encontro de monstro** (escala com a dificuldade) — é preciso
> **vencer o combate** para receber a recompensa; perder zera a recompensa e fere o guerreiro. A coleta
> sempre volta com uma **narrativa** curta (paz / vitória / derrota). Detalhe: `docs/PLANO_QUESTS_REINO.md`.

### 15.2 Abas removidas / reorganizadas

| Aba atual | Destino |
|-----------|---------|
| Taverna | **Removida** — quests vivem dentro de cada reino |
| Expedições | **Removida** — zones vivem dentro de cada reino |
| Habilidades (pesca/mine) | **Removida** — gathering vive dentro de cada reino |
| Territórios | **Removida** — integrada na tela de cada reino |
| Habilidades (Forja/Smithing) | **Vai para Commerce** |
| **World** | **Criada** — tela principal com os 5 reinos |

Resultado: -4 abas +1 = interface muito mais limpa.

---

### 15.3 Reino da Pesca — Desfiladeiro do Osso 🎣

**Identidade:** recurso de estamina, economia de alimentos, futuro sistema de cozinha.

| Zona | Level mín. | Atividade | Risco |
|------|-----------|-----------|-------|
| Porto Seguro | 1 | Pesca básica, peixes comuns | Nenhum |
| Costa Selvagem | 10 | Pesca com risco de hunters (players) | PvP |
| Mar Profundo | 20 | Peixes raros, criaturas perigosas | PvP + monstros |

**Quests do reino (6, vitrine de 2 rotacionando 6h) — exemplos:** Patrulhe a Costa, Caça ao Monstro Marinho

**Loop de valor:**
```
Pesca → Peixes de estamina (Desfiladeiro) / Peixes de vida (Mar Abençoado)
      → Cozinha → Refeição → buff de combate "Bem Alimentado"
```

**Sistema de Cozinha ✅ (implementado):**
- Peixes viram **refeições** (aba 🍳 Cozinha no Commerce) que dão um **buff de combate** no slot
  **"Bem Alimentado"** (empilha com os 2 do Templo), ~1.5-2× mais fortes que os do Templo.
- Linha **ofensiva** (peixe de estamina) e **defensiva** (peixe de vida). Cozinhar é instantâneo (sem skill).
- Custa peixe (estamina + tempo de coleta); perdido na derrota. Detalhe: `docs/PLANO_COZINHA.md`.
- *Futuro:* refeições de guild (feast), buffs de utilidade (XP/yield), ingredientes raros.

---

### 15.4 Reino da Mineração — Minas de Ferro Negro ⛏

**Identidade:** matéria-prima para toda a cadeia de equipamento do jogo.

| Zona | Level mín. | Atividade | Risco |
|------|-----------|-----------|-------|
| Mina Aberta | 1 | Mineração básica, minérios comuns | Nenhum |
| Túneis Profundos | 10 | Mineração com risco de hunters | PvP |
| Minas Proibidas | 20 | Minérios raros, alta periculosidade | PvP + monstros |

**Quests do reino (6, vitrine de 2 rotacionando 6h) — exemplos:** Escolta os Mineiros, Derrote a Besta das Cavernas

**Loop de valor:**
```
Mineração → Minério → Forja (Commerce) → Equipamento → todos os reinos precisam
```

---

### 15.4b Reino do Garimpo — Grutas de Cristal 🔎 (Reinos V2)

**Identidade:** fonte exclusiva de fragmentos de joia (gemas saíram da mineração). Zona aberta (sem guild-war).

| Zona | Level mín. | Atividade | Risco |
|------|-----------|-----------|-------|
| Veio Raso | 1 | Garimpo básico | Nenhum |
| Grutas Profundas | 10 | Garimpo com risco (cosmético) | PvP |
| Caverna Proibida | 20 | Fragmentos raros | PvP |

**Quests do reino (6, vitrine de 2 rotacionando 6h) — exemplos:** Guard the Crystal Veins, Slay the Crystal Beast

**Loop:** `Garimpo → Fragmentos → Forja → Joias → sockets → todo build`

---

### 15.4c Reino da Pesca de Vida — Mar Abençoado 🐟 (Reinos V2)

**Identidade:** peixe que restaura **HP** (até 90%) em vez de estamina. Zona aberta (sem guild-war).

| Zona | Level mín. | Atividade | Risco |
|------|-----------|-----------|-------|
| Enseada Sagrada | 1 | Pesca de vida básica | Nenhum |
| Recife Profundo | 10 | Pesca com risco (cosmético) | PvP |
| Abismo Abençoado | 20 | Peixes lendários de vida | PvP |

**Quests do reino (6, vitrine de 2 rotacionando 6h) — exemplos:** Cleanse the Tides, Guard the Sacred Reef

---

### 15.5 Reino do Combate — Fortaleza Maldita ⚔

**Identidade:** progressão de XP acelerada, combate puro, caçada PvE e guerra de guild.

| Zona | Level mín. | Atividade | Risco |
|------|-----------|-----------|-------|
| Arena de Treino | 1 | **Treino pago** — paga bronze, ganha EXP passivo (timer, como Work mas para XP) | Nenhum |
| Campo de Batalha | 10 | Caça a monstros + PvP com players | PvP |
| Zona de Guerra | 20 | Monstros e players simultaneamente | PvP + monstros |

**Quests do reino (6, vitrine de 2 rotacionando 6h) — exemplos:** Defenda as Muralhas, Caça ao Senhor da Guerra

**Treino (mecânica):**
- Paga uma quantia de bronze → personagem "treina" por X horas (timer)
- Ao coletar: recebe XP puro (sem bronze, sem itens)
- Mais eficiente em XP/hora que missões, mas sem outras recompensas
- Custo escala com o level do guerreiro

**Caçada PvE (Reinos V2 — antigo Covil das Feras):**
- `POST /api/world/COMBAT/raid` — caçada repetível contra mobs que escalam com o nível
- Custa 15⚡; vitória rende gold (lv×10), XP (lv×12) e materiais (Núcleo de Fera sempre, Pele de Fera 25%)
- Reusa o BattleSimulator; chefes (boss) ficam reservados para a Torre

**Loop de valor:**
```
Treino + Caçada → EXP → Level → desbloqueia zonas de alto risco nos outros reinos
Zona de Guerra → combate mais intenso → itens de alta raridade
```

---

### 15.6 Navegação da aba World

```
[World Tab]
  ├── Card: Desfiladeiro do Osso 🎣
  │   → Clica → tela do reino:
  │       ├── Status do reino (guilda dominante, bônus, war timer)
  │       ├── 🍺 Taverna → quests do reino (timer-based)
  │       ├── 🎣 Porto Seguro → pesca (timer-based)
  │       ├── 🌊 Costa Selvagem → pesca PvP (lv10+)
  │       ├── 🦈 Mar Profundo → pesca high-risk (lv20+)
  │       └── ⚔ Declarar Guerra (se guilda presente)
  │
  ├── Card: Minas de Ferro Negro ⛏
  │   → idem, com zonas de mineração
  │
  └── Card: Fortaleza Maldita ⚔
      → idem, com treino e zonas de combate
```

### 15.7 Interdependência forçada

Nenhum jogador pode ser autossuficiente dentro de um único reino:

| Necessidade | Fonte |
|-------------|-------|
| Estamina rápida | Peixes (Desfiladeiro) |
| Equipamento | Forja (Commerce) ← Minério (Minas) |
| EXP/Level | Treino + Quests (Fortaleza) |
| Recursos de crafting | Minas |
| Buffs premium (futuro) | Cozinha (Desfiladeiro) |

### 15.8 Guerra de Territórios (integrada)

O sistema atual de Guild War permanece com as mesmas mecânicas, mas a declaração de ataque e o status do território ficam visíveis **dentro da tela do reino** em vez de em aba separada. Cada reino ainda tem seus bônus exclusivos para a guilda dominante:

- **Desfiladeiro:** +10% XP + +10% bronze + +20% yield de pesca
- **Minas:** +10% XP + +10% bronze + +20% yield de mineração
- **Fortaleza:** +10% XP + +10% bronze + +10% XP extra em quests

### 15.9 Futuras expansões que o design já comporta

| Expansão | Reino | Quando |
|----------|-------|--------|
| Sistema de Cozinha | Desfiladeiro | v0.5 |
| Refeições de guild (feast) | Desfiladeiro | v0.5 |
| Crafting de elite (mithril+) | Minas | v0.4 |
| Torneio de arena entre guildas | Fortaleza | v0.6 |
| Missões de reino cruzadas | Todos | v0.5 |

---

## 16. Plano de Lançamento

### 14.1 Estado Atual (v0.2 — Web)

- Sistemas implementados e funcionais (incluindo Guildas, Reinos V2, sinks econômicos)
- 410 testes automatizados (unitários + integração)
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

### 14.x i18n — Fase 1 concluída / TODO Phase 2

**Implementado (Fase 1):**
- Sistema base: `lang/en.json`, `lang/pt.json`, função `t()`, toggle EN↔PT
- Navbar, títulos e descrições de todas as abas
- Painel do guerreiro (stats, status badges)
- Quest types, work jobs, zone names, territory names, buff names — todos via `t()` com fallback ao displayName do backend
- Botões de quest, arena, tower, temple, skills, zones, shop, guild, mail

**Phase 2 concluída.** Todas as strings de conteúdo traduzidas para inglês:

- [x] `BattleSimulator.java` — textos de ataque, partes do corpo, evasões, vitória
- [x] `ItemLoreGenerator.java` — lore por raridade/tipo (Common/Uncommon/Rare/Epic × Weapon/Armor) e origens
- [x] `ItemType.java` — displayNames agora em inglês (Helmet, Armor, Weapon, etc.)
- [x] `item.typeDisplay` no frontend — usa `t('item.type.'+type)||fallback`
- [x] `item.rarityName` no frontend — usa `t('inventory.rarity.'+rarity)||fallback`
- [x] Quest narratives (PATROL/DUNGEON/RAID/BOSS_HUNT) — traduzidas no frontend
- [x] Drop narratives — traduzidas no frontend

**TODO Phase 3 — Expansão de idiomas:**

| Item | Quando |
|------|--------|
| `es.json` (espanhol) | Após lançamento na Steam |
| `fr.json` (francês) | Idem |
| Detecção automática do idioma do browser | Com `navigator.language` |
| Backend i18n via `Accept-Language` header | Somente se o jogo escalar para múltiplas regiões |

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

## 17. Considerações Técnicas

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

## 19. SoulStone 💎 — Moeda VIP e Status VIP

### 19.1 Conceito e Filosofia

SoulStone é a moeda premium do jogo. O princípio central é **conforto sem poder** — nenhuma compra com SoulStone deve dar vantagem de combate direta. Tudo que pode ser comprado reduz fricção, mas não aumenta stats permanentemente nem cria pay-to-win.

A moeda pertence à **conta** (Player), não ao personagem (Warrior).

### 19.2 Como Ganhar

| Método | Status |
|--------|--------|
| Compra direta (Stripe / Steam) | Planejado — admin endpoint para testes agora |
| Login diário consecutivo | Futuro — +1 💎 a cada 7 dias sem falhar |
| Conquistas in-game | Futuro — matar boss X, alcançar rank Y |
| Eventos sazonais | Futuro |

### 19.3 Status VIP — Principal Oferta

O Status VIP é a principal compra no SoulStone Shop. É temporário (30 dias) e renovável.

| Campo | Valor |
|-------|-------|
| Custo | **15 💎** |
| Duração | **30 dias** |
| Renovação | Empilha: se já ativo, adiciona +30 dias |
| Bag | Expansão 20 slots **inclusa na compra** |

**Benefícios VIP vs Free:**

| Benefício | Free | VIP | Como funciona |
|-----------|------|-----|---------------|
| Cura HP no Templo | Paga bronze | **Grátis, CD 10 min** | `lastVipHealAt` no Player |
| Missões instantâneas | 0/dia | **2/dia** | Counter diário + reset meia-noite UTC |
| Lutas de Arena | **5/dia** | **10/dia** | Counter diário + reset meia-noite UTC |
| Buffs ativos | 1 | **2 simultâneos** | `activeBuff2` + `buffExpiresAt2` no Warrior |
| Bag | 10 slots | **20 slots** | Incluso no VIP, sem custo adicional |

**Missão Instantânea VIP — Fluxo:**
1. Player abre detalhe de um reino no World tab
2. Quest card mostra `Start Quest` E `⚡ Instant (N restantes)`
3. Clicando em "⚡ Instant": backend inicia + conclui a quest imediatamente
4. Modal de collect abre com XP, bronze e drop — igual ao fluxo normal de coleta
5. Counter decrementado; reseta à meia-noite UTC

**Arena Daily Limit:**
- Free players têm **5 lutas/dia**
- VIP players têm **10 lutas/dia**
- Reset à meia-noite UTC
- Erros claros mostram quantas lutas restam

### 19.4 SoulStone Shop (aba no Commerce)

Tela dedicada dentro do Commerce com:
- Status VIP atual (dias restantes ou "Sem VIP")
- Botão "Comprar VIP" (15 💎) ou "Renovar VIP +30 dias"
- Compras permanentes disponíveis
- Consumíveis disponíveis

### 19.5 Compras Permanentes (one-time)

| Compra | Custo | Efeito |
|--------|-------|--------|
| Expandir Bag | 3 💎 | 10 → 20 slots (incluso no VIP) |
| Resetar atributos | 5 💎 | Redistribui todos os pontos investidos |
| Trocar nome do guerreiro | 2 💎 | Uma compra = uma troca |

### 19.6 Consumíveis (gastam toda vez)

| Consumível | Custo | Cooldown |
|------------|-------|----------|
| Cura instantânea (SoulStone) | 1 💎 | CD 30 min |
| Pular metade do CD de treino/work | 1 💎 | Uma vez por sessão |

### 19.7 Cosmético / Social *(planejado)*

- Título no perfil e ranking
- Frame especial no card da guilda
- Lore customizado para um item

### 19.8 Princípios de Design

1. **Nenhuma SoulStone compra stats permanentes** — só conveniência e tempo
2. **Todos os conteúdos acessíveis sem VIP** — VIP elimina fricção, não cria muros
3. **Daily limits têm reset garantido** — contadores independentes de horário de uso
4. **Consumíveis têm CD** — evita dominância de quem tem muitas pedras
5. **Renovação empilha** — incentiva compra antecipada sem punir o jogador

### 19.9 Modelo de Dados

| Campo | Tipo | Entidade | Notas |
|-------|------|----------|-------|
| `soulStones` | `int` | `Player` | Saldo atual |
| `vipExpiresAt` | `LocalDateTime` | `Player` | null = sem VIP |
| `lastVipHealAt` | `LocalDateTime` | `Player` | CD 10 min da cura grátis VIP |
| `lastSoulstoneHealAt` | `LocalDateTime` | `Player` | CD 30 min da cura por SS |
| `arenaFightsToday` | `int` | `Player` | Lutas do dia (reset diário) |
| `lastArenaFightDate` | `LocalDate` | `Player` | Data do último reset de arena |
| `vipInstantQuestsToday` | `int` | `Player` | Missões instantâneas usadas hoje |
| `lastVipQuestDate` | `LocalDate` | `Player` | Data do último reset de quests |
| `inventoryExpanded` | `boolean` | `Player` | Flag de bag expandida |
| `activeBuff2` | `BuffType` | `Warrior` | Segundo buff ativo (VIP) |
| `buffExpiresAt2` | `LocalDateTime` | `Warrior` | Expiração do segundo buff |

---

## 18. Glossário

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
