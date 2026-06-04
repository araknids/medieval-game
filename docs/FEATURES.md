# Medieval Game — Funcionalidades Implementadas

> Documento de referência para todos os agentes. Atualizar sempre que uma feature for adicionada ou modificada.

---

## 1. Sistema de Contas

### 1.1 Cadastro
- Jogador fornece: username (3-20 chars), email, senha (min 6), nome do guerreiro
- Ao cadastrar: conta criada + guerreiro WARRIOR criado + 7 itens iniciais no inventário + 50 prata de saldo inicial
- Email de boas-vindas enviado via Brevo

### 1.2 Login
- Login por username + senha
- Retorna JWT válido por 7 dias
- Token salvo no localStorage do browser

### 1.3 Recuperação de Senha
- Jogador informa email → recebe link de reset (válido 30 min)
- Link abre formulário de nova senha no browser
- Email enviado via Brevo API

---

## 2. Guerreiro

### 2.1 Criação
- Um guerreiro por conta, classe única: WARRIOR
- Stats base: ATK 15, HP 110
- Level começa em 1; **sem cap de nível** (infinito)
- A cada nível: +**2 pontos de atributo** para distribuir livremente

### 2.2 Progressão de Nível (XP Exponencial)

Fórmula: `XP para subir do nível N = round(100 × N^1.8)`

| Nível | XP para subir | XP total acumulado |
|-------|--------------|-------------------|
| 2 | 100 | 100 |
| 10 | ~4.780 | ~17.000 |
| 20 | ~16.500 | ~100.000 |
| 50 | ~85.000 | ~1.200.000 |
| 95 | ~296.000 | ~26.000.000 |
| 100+ | crescente | infinito |

Sem teto — progressão exponencialmente mais difícil inspirada em Tibia.

### 2.3 Atributos — Sistema D&D (d20)

**2 pontos por nível, caps definidos por atributo:**

| Atributo | Cap | Efeito |
|---------|-----|--------|
| **Força (STR)** | 60 | +1 ATK por ponto · bônus de ataque: `floor(STR/20)` (+0 a +3) |
| **Destreza (DEX)** | 40 | +1 Armor Class por ponto · AC = `10 + DEX` (máx AC 50) |
| **Constituição (CON)** | sem cap | +8 HP por ponto — razão de continuar upando além dos outros caps |
| **Sorte (LUK)** | 50 | +1% drop · expande janela de crítico · Fortune Save |
| **Intelecto (INT)** | 40 | +0.5% chance sucesso Smithing · -0.2% custo bronze treino · +0.3% yield coleta |

**Efeito de cada atributo no combate:**

- **Força (STR)**: Controla bônus de ataque (`floor(STR/20)`, range 0-3) e variância de dano (`+floor(STR/10)`)
- **Destreza (DEX)**: Controla AC (Armor Class) — número que o atacante precisa superar para acertar
- **Sorte (LUK)**: Expande janela de crit (`crit em d20 ≥ 20 - floor(LUK/15)`, cap 20% crit); Fortune Save (`floor(LUK/10)%` de anular crit inimigo)

**Ao atingir todos os caps** (level ~95): cada nível adicional = 2 pontos em CON = +16 HP/nível. Veteranos têm muito mais HP — vantagem de tanque, não de dano.

### 2.4 HP com Regen Passiva
- Armazenado como % (0-100), regenera 100% em 1 hora automaticamente
- HP=0: guerreiro inconsciente, não pode entrar em combate
- Cura disponível no Templo

### 2.5 Buff Ativo (Templo)
- Free: um buff por vez, dura 1 hora
- VIP: 2 buffs ativos simultâneos
- Buffs: Força (+5 ATK), Agilidade (+5% AC), Defesa (+5 DEF), Vitalidade (+20 HP), Sorte (+5% drop)
- Buff é perdido ao ser derrotado em combate
- **Os buffs ativos entram de fato no combate** (`WarriorStatsService.combatStats` soma slot 1 + 2 +
  slot Bem Alimentado da Cozinha). Há ainda um 3º slot **Bem Alimentado** (refeição) — ver Cozinha (§26).

### 2.6 Liberar Guerreiro Travado
- Endpoint `POST /api/warrior/free` cancela todas sessões ativas e libera o guerreiro

---

## 3. Sistema de Moedas (3 Tier + VIP)

- **Bronze**: moeda base (menor)
- **Prata**: 100 bronze = 1 prata
- **Ouro**: 100 prata = 1 ouro (= 10.000 bronze)
- Display: `2🥇 30🥈 45🥉`
- Novos jogadores começam com 50 prata
- **SoulStone (💎)**: moeda VIP — ver seção 25

---

## 4. Stamina

- 0-100%, regenera 100% em 2 horas
- Consumida por: missões (10-50), arena (25), torre (25), zona (grátis)
- Guerreiro com stamina insuficiente não pode iniciar atividades

---

## 5. Missões (Quests)

### Tipos (Combate V2 — nichos: curtas = bronze, longas = XP)
| Missão | Duração | Bronze | XP | Stamina | Drop% | Bronze/⚡ | XP/⚡ |
|--------|---------|--------|-----|---------|-------|----------|------|
| Patrulha | 5 min | 180 | 40 | 10 | 10% | **18** | 4 |
| Masmorra | 10 min | 320 | 110 | 20 | 25% | 16 | 5.5 |
| Raid | 20 min | 480 | 320 | 35 | 40% | 13.7 | 9.1 |
| Caça ao Chefe | 30 min | 600 | 750 | 50 | 60% | 12 | **15** |

> **Nichos:** quer **bronze rápido** → Patrulha/Masmorra (melhor bronze por estamina e por tempo).
> Quer **subir de nível** → Raid/Caça ao Chefe (melhor XP). Nenhum tipo domina os dois eixos. [Combate V2]

### Mecânica
- Timer-based: envia guerreiro, volta depois para coletar
- Guerreiro fica `onMission=true` durante a missão
- Em modo dev `instant-complete=true` completa na hora
- Ao completar: tela de progresso mostra resultado com narrativa

### Drop de Itens
- Chance por quest type + bônus de Sorte do guerreiro
- Raridade: Patrulha=Comum, Masmorra=Comum/Incomum, Raid=Incomum/Raro, Caça=Raro/Épico
- Item recebe lore e origem automaticamente

### Abandonar Missão
- `POST /api/quests/{id}/abandon`: não recebe recompensa, guerreiro liberado imediatamente

---

## 6. Arena PvP

### Mecânica
- Assíncrono: guerreiro patrulha, combate resolvido automaticamente
- Custa 25 stamina
- Oponente: jogador real da mesma zona OU NPC gerado
- Timer: 1 minuto em prod (instantâneo em dev)
- Ao coletar: vê log detalhado do combate

### Limite Diário de Lutas
| Status | Lutas/dia |
|--------|-----------|
| Free | 5 |
| VIP | 10 |

- Reset à meia-noite UTC
- Tentativa além do limite retorna erro com contador restante

### Resultado
- Vitória: +200 bronze, +25 rank points
- Derrota: +50 bronze (consolação), -15 rank points
- Derrota: HP do guerreiro vai a 0, buff perdido

### Ranking
- Top 20 jogadores por rank points
- Exibe nome do guerreiro (não login)

---

## 7. Torre Infernal

### Mecânica
- Dungeoncom andares progressivos
- Ao entrar (custa 25 stamina): luta automática contra chefe do andar
- Ganhar: avança andar, recebe recompensa, escolhe continuar ou sair
- Perder: expulso, HP=0, buff perdido
- Checkpoint: na próxima entrada, começa do andar seguinte ao melhor completo

### Chefes por Andar (curva Combate V2)
- Andares 1-3: Esqueleto, Goblin, Rato Gigante · 4-6: Aranha, Orc, Troll · 7-9: Zumbi, Vampiro, Golem
- 10-12: Cavaleiro Negro, Arqueiro, Ogro · 13-15: Xamã, Wyvern, Lich Menor · 16+: Dragão, Titan, Lich Ancião…
- **Stats do chefe (andar `f`):** ATK `12+5f` · DEF `5+3f` · HP `120+45f` · AC `10+min(f/2,8)` ·
  strBonus `min(f/10,3)` · luk `min(f,18)`.
- **É de verdade um chefe:** com a mitigação % + timeout=derrota (Combate V2), um **lvl1 pelado PERDE o Andar 1**
  (validado em `TowerBalanceTest`). Precisa investir (nível/atributos/gear) pra avançar.
- **Nível recomendado** por andar exibido na UI: `≈ andar × 3` (`TowerService.recommendedLevel`).

### Recompensas
- Bronze: andar × 40
- XP: andar × 20

### Ranking
- Por melhor andar já completado (histórico permanente)

---

## 8. Trabalho

### Empregos
| Emprego | Bronze/h | Level mínimo | XP/h |
|---------|---------|-------------|------|
| Ajudante da Taverna | 15 | 0 (Lv.1) | 3 |
| Cuidador dos Estábulos | 20 | 0 (Lv.1) | 4 |
| Carregador de Mercadorias | 30 | Lv.1 | 6 |
| Ajudante do Ferreiro | 45 | Lv.2 | 8 |
| Guarda da Nobreza | 65 | Lv.3 | 12 |
| Mercenário Local | 100 | Lv.5 | 18 |

### Mecânica
- Timer 1-12 horas, warrior fica onMission
- **Nível mínimo = nível do guerreiro** (não da profissão)
- Cada profissão tem XP e nível separados (+5% bônus por nível)
- Cancelar: recebe proporcional às horas completas
- Pode abandonar perdendo tudo

---

## 9. Inventário e Equipamentos

### Slots de Equipamento (10)
Capacete, Armadura, Espada, Escudo, Calça, Bota, Luva, Ombreira, Colar, Anel

### Itens
- Raridades: Comum (cinza), Incomum (verde), Raro (azul), Épico (roxo), **Lendário (dourado)** — Itens V2
- Cada item tem: `description` (lore) e `origin` (onde foi encontrado) — gerados automaticamente
- Itens craftados têm 1-2 sockets garantidos

### Nível de Item (Itens V3)
- Todo item tem um **nível fixo** (`itemLevel`). **Poder = nível × multiplicador de raridade**
  (Comum 1.0 · Incomum 1.2 · Raro 1.45 · Épico 1.75 · Lendário 2.1) → **lvl100 Comum > lvl1 Épico**.
  A raridade continua dando **afixos + sockets + o multiplicador** — no mesmo nível, Épico > Comum.
- **Requisito pra equipar:** só equipa se `itemLevel ≤ nível do guerreiro` (UI mostra 🔒 Lv.X e bloqueia).
- **De onde vem o nível:** drop de quest/reino = **nível do guerreiro** (loot escala com você);
  **loja = nível do jogador ±5** (gear básico de nível); craft = nível do recipe; iniciais = 1.
- **Loja vende só Comum/Incomum** (Itens V3) — gear básico no teu nível; **Raro/Épico/Lendário só dropam.**

### Afixos (Itens V2 — Fase A)
- Cada item rola **afixos aleatórios** pela raridade: Comum 0 · Incomum 1 · Raro 2 · Épico 3 · **Lendário 4**
- Afixo concede **stat plano** (ATK/DEF/HP) **ou atributo** (+STR/+DEX/+LUK), reusando o sistema D&D
  (STR→+1 ATK/pt, DEX→AC, LUK→crit/drop). **Magnitude escala com o NÍVEL do item** (raridade dá só
  ~+15%/tier) — um Lendário Lv1 tem afixos minúsculos; um Comum Lv40 vence. [Itens V3]
- **Prefixo** vira adjetivo no nome ("Sharp Sword of Steel"); todos os afixos aparecem como linhas no card.
- Pool: prefixos (Sharp/Heavy/Sturdy/Brutal/Swift/Lucky) + sufixos (of the Tiger/Turtle/Bear/Ox/Fox/Cat).
- **Reforjar na Forja re-rola os afixos** (mantém o nome) — caça ao roll perfeito + dreno de bronze.
- Afixos de item **quebrado** (durabilidade 0) não contam, como base e joias.

### Tier Lendário (Itens V2)
- Raridade 5, cor dourada; **sempre com 3 sockets** e **4 afixos**. Stats escalam (orçamento × raridade).
- Fontes raras: **Caça ao Chefe** (~6%) e **raid de reino** top-tier (~5%). Topo da cadeia de loot.

### Sockets e Joias
- Itens podem ter 0-3 sockets dependendo da raridade
- Joias encaixadas dão bônus permanentes: Rubi (+5 ATK), Safira (+5 DEF), Esmeralda (+20 HP), Diamante (+3/+3/+10), Ametista (+5% drop)

### Durabilidade (dreno econômico)
- Cada item de equipamento tem **durabilidade 0–100** (começa em 100)
- Perde **1 a 10 pontos aleatórios por batalha** (arena, torre, zona, emboscada) — aplicado aos itens equipados
- Item com durabilidade **0 NÃO dá bônus** (ATK/DEF/HP/joias zerados até reparar) — não quebra permanentemente
- Reparo no Ferreiro (Forja): `pontos perdidos × raridade × 5 bronze`
  - Comum: 5/ponto (reparo cheio ~500) · Épico: 20/ponto (~2.000)
- Sink contínuo que escala com a qualidade do gear

### Proteção (Templo)
- Até 3 itens podem ser protegidos (50 bronze cada)
- Itens protegidos NÃO caem em morte no Alto Risco

### Bag (Mochila) — unificada (Inventário V2)
- Capacidade: **30 slots** free, **50** para VIP ou expandida com SoulStone (ver seção 25).
- **Bag unificada:** itens E recursos dividem o mesmo pool. **Cada recurso ocupa 1 slot POR UNIDADE**
  (5 salmões = 5 slots), todos os tipos (peixe/minério/fragmento/barra/gema/material).
- Itens equipados **não** contam contra o limite da bag.
- Coleta/drop **respeita o limite**: adiciona só o que cabe, o excedente é **perdido** (use o Stash).

### Stash (Inventário V2)
- Armazenamento extra de **100 slots** (mesma contagem por unidade), fora da bag.
- **Taxa fixa de 50 bronze por operação** de depositar OU retirar (1 item, ou um recurso de um tipo).
- Itens/recursos no stash não contam na bag e não podem ser equipados/consumidos até retornar.
- UI: botão **🏛 Stash** na aba de inventário abre o painel Bag ↔ Stash. Endpoints `/api/stash/**`.

### Itens Iniciais
7 itens Comuns ao criar conta: Elmo, Armadura, Espada, Escudo, Botas, Luvas, Calça (todos de Ferro/Couro)

---

## 10. Loja (Comércio)

### Rotação Dinâmica
- 10 itens novos a cada 6 horas (nomes/tipos iguais p/ todos; **nível/stats escalam com o jogador**)
- Baseada em `rotationId = epochSeconds / 21600`
- Cada jogador pode comprar cada item uma vez por rotação

### Sorteio (Itens V3)
- **Só Comum (65%) e Incomum (35%)** — gear básico de nível. **Raro/Épico/Lendário só dropam.**
- **Nível do item = nível do jogador ±5**; stats escalam com o nível (geração determinística por rotação,
  então preview == compra). Preço curado do template mantido.
- Pool de nomes/tipos cobrindo os 10 slots de equipamento.

### Mercador
- Nome muda a cada rotação ("Gareth, o Mercador Andarilho", etc.)
- Timer mostra quando chega a próxima carroça

---

## 11. Habilidades (Pesca / Mineração / Garimpo / Forja)

### Skills
- 4 habilidades por jogador, sem restrição de especialização: **Pesca, Mineração, Garimpo, Forja**
- Level 1-100, XP para próximo nível = level × 100
- Multiplicador de XP e recursos por zona/reino
- **Coletar gasta estamina** (Reinos V2): pescar/minerar/garimpar consomem estamina proporcional à
  duração (~metade dos minutos, mínimo 5). **Ignorado quando `instant-complete` (modo de teste)** —
  igual a quests/arena/torre; em produção (flag off) é cobrado normalmente. Fecha o loop pescar→comer→coletar.
  - O **botão de cada duração mostra o custo** (ex.: `30min · 15⚡`) antes de iniciar.
- **Lore dinâmica na coleta** (Reinos V2): ao coletar, mostra uma frase curta de ambientação
  (em inglês), variando por skill (pesca/mineração/garimpo) e citando o reino — o equivalente ao
  log de batalha para a coleta. Gerada no backend (`GatheringNarrator`), aleatória de um pool por skill.

### Pesca
- Sessão timer: 5/10/20/30/40 min
- **Haul** = `max(1, duração/10)` peixes (Combate V2: reduzido p/ a pesca não ser fonte infinita de estamina).
- **Dois tipos de peixe, por reino** (Reinos V2):
  - **Desfiladeiro do Osso (peixe de ESTAMINA)** — Peixe Pequeno/Salmão/Atum/Tubarão/Peixe Lendário,
    restauram **só estamina** (+5/+8/+11/+14/+18, Combate V2: achatado e reduzido — o tier vale por
    **venda/cozinha**, não por estamina; a pesca virou top-up leve ≈ regen passiva, não fountain).
  - **Mar Abençoado (peixe de VIDA)** — Coral/Anjo/Espírito/Sagrado/Fênix, restauram **só HP**
    (+15/+30/+50/+70/+90%), **com teto de 90%** — o resto (90→100%) e reviver de KO exigem Templo/regen
    (pra não furar o sink de cura do Templo).

### Mineração
- Sessão timer: 10/20/30/45/60 min
- Produz **só minério**: Cobre, Ferro, Prata, Ouro, Mithril
- (Reinos V2) Gemas **não saem mais** da mineração — agora vêm do Garimpo.

### Garimpo (Reinos V2)
- Skill nova; atividade do reino **Grutas de Cristal**
- Sessão timer (igual mineração); cada rodada pode achar um **fragmento de joia** (ou vir vazia)
- Fragmentos por nível: Ametista → Rubi (20) → Safira (40) → Esmeralda (60) → Diamante (80)
- Fragmentos viram joias na Forja (3 do mesmo tipo → 1 joia)

### Forja (Smithing)
- **Refinar**: 5 minérios + bronze → 1 barra (custo escala por nível)
- **Craftar equipamento**: barras → item com sockets garantidos
- **Craftar joia**: 3 fragmentos do mesmo tipo → 1 joia
- **Reparar item**: restaura durabilidade — `pontos perdidos × raridade × 5 bronze`
- **Reforjar item (re-roll)**: re-rola os stats do item mantendo a raridade — `raridade² × 200 bronze`
  - Comum 200 · Incomum 800 · Raro 1.800 · Épico 3.200 — dreno de late-game sem teto

---

## 12. Inventário de Recursos

Tipos: Peixes, Minérios, Fragmentos, Barras, Joias, Materiais

Cada tipo tem:
- `displayName`, `category`, `quantity` (stackável)
- Separado do inventário de equipamentos

---

## 13. Zonas e Expedições (Loot com PvP por probabilidade)

### Zonas
| Zona | Level mín | Multiplicador | NPC %/h | PvP %/h |
|------|-----------|--------------|---------|---------|
| Zona Segura | 1 | ×1.0 | 15% | 0% |
| Zona PvP | 10 | ×1.5 | 25% | 20% |
| Zona Alto Risco | 20 | ×2.5 | 35% | 40% |

### Conceito
Todos os jogadores entram na zona para **lootear** (coletar recursos). Não existe mais o papel de "Hunter" — o risco é ser **emboscado por outro jogador que também está looteando** na mesma zona. O matchmaking é por **probabilidade**, resolvido no `collect`.

### Resolução no Collect (modelo lazy, por hora de expedição)
```
Para cada hora da expedição:
  1. Rola probabilidade de PvP da zona (PVP=20%, HIGH_RISK=40%, SAFE=0%)
     → SE rolou E há outro player IN_PROGRESS na mesma zona:
        sorteia 1 oponente → EMBOSCADA (luta d20 até a morte)
     → SE não há ninguém na zona → cai pra NPC (PvE)
  2. Rola probabilidade de NPC (PVP=25%, HIGH_RISK=35%, SAFE=15%) → PvE
```

### Emboscada PvP (luta até a morte/nocaute)
- Atacante = quem está coletando agora (rolou o encontro)
- Alvo = outro player com expedição `IN_PROGRESS` na zona (HP **real** na hora)
- Luta d20 completa até alguém chegar a 0 HP

| Resultado | Vencedor | Perdedor |
|-----------|----------|----------|
| — | Sobrevive com HP que sobrou · rouba **15% do bronze** do perdedor (recebe 50% do roubado) | Morre (HP=0) · perde 15% bronze · **-10% XP** · (HIGH_RISK) 10% chance de perder 1 item equipado não-protegido |

### Emboscadas Múltiplas
- Você só pode ser emboscado de novo se **venceu** as anteriores (sobreviveu)
- Cada vitória defensiva dá **-5% cumulativo** na chance de nova emboscada (anti-farm/assédio)
- HP **carrega** entre emboscadas — regenera no tempo real (passivo) ou consumindo peixe

### Notificação ao Alvo
- Todo ataque sofrido gera **mail automático**: quem atacou, bronze perdido, item roubado (se houve), HP atual (0% se morreu), e se sobreviveu ou morreu
- Ao voltar ao jogo com expedição ainda ativa: **dialog "Continuar ou Recolher?"** (só se sobreviveu)
  - Continuar → expedição segue até o timer
  - Recolher → collect imediato do que já tem
- Se morreu numa emboscada → expedição encerra; vê "Você morreu na expedição" no login

### Consequências de Derrota (vale pra atacante e alvo)
- HP = 0 + stamina = 0 + buff perdido
- Perde 15% do bronze (metade vai pro vencedor)
- Perde 10% do XP do nível atual (pode dropar nível, mínimo 1)
- **Alto Risco**: 10% de chance de perder 1 item equipado não-protegido

### NPCs por Zona (PvE — continua em paralelo ao PvP)
- Segura: Lobo, Bandoleiro, Urso, Javali
- PvP: Mercenário Corrupto, Orc, Cavaleiro Renegado
- Alto Risco: Demônio Menor, Lich, Dragão Jovem
- Stats: level do guerreiro +0 a +3 (nunca garante vitória)

---

## 14. Templo

### Cura
- Restaura HP para 100% instantaneamente
- Grátis se guerreiro ≤ level 10
- **Custo escalável por nível** se level > 10: `nível × 10 bronze` (lv50=500, lv100=1.000) — dreno de late-game
- **VIP**: Grátis, cooldown de 10 minutos (`lastVipHealAt` no Player)

### Bênçãos (Buffs)
| Buff | Efeito | Custo |
|------|--------|-------|
| Força | +5 ATK | 30 bronze |
| Agilidade | +5% evasão | 30 bronze |
| Defesa | +5 DEF | 30 bronze |
| Vitalidade | +20 HP máximo | 30 bronze |
| Sorte | +5% drop chance | 50 bronze |

- Free: um buff por vez, dura 1 hora
- **VIP: 2 buffs ativos simultâneos** (`activeBuff2` + `buffExpiresAt2` no Warrior)
- Buff perdido ao ser derrotado

### Proteção de Itens
- Máximo 3 itens, 50 bronze por item, permanente
- Itens protegidos não caem na Zona de Alto Risco

---

## 15. Sistema de Combate — d20 (BattleSimulator)

Reutilizado por Arena, Torre e Zonas. Inspirado em D&D 5e (Bounded Accuracy).

### Fluxo de Cada Rodada

```
1. Atacante rola d20 (resultado 1-20)
2. Adiciona bônus de ataque: floor(STR / 20)
3. Compara com AC do defensor: 10 + DEX do defensor
4. Se (d20 + bônus) ≥ AC → ACERTA
   Se (d20 + bônus) < AC → ERRA
5. Natural 20 → Crítico (dano dobrado)
   Natural 1  → Fumble (miss automático, ignora STR)
6. Dano quando acerta (Combate V2): MITIGAÇÃO % → `ATK × 100/(100+DEF)` (mín. 1); crítico ×2 depois
7. HP do defensor reduzido; próxima rodada começa
```

**Combate V2 — mitigação % e teto de rounds:**
- Dano = `round(ATK × 100/(100+DEF))`, mínimo 1. DEF dá redução com retornos decrescentes —
  **nunca zera o dano, nunca vira inútil** (antes era `ATK − DEF` com piso 1, que virava imunidade).
- **Teto de 40 rounds:** **PvE** (Torre/Combate PvE/Quest de Reino) → se o chefe não morrer, o
  **desafiante PERDE** (precisa de dano, não só HP). **PvP** (Arena/Guerra/Emboscada) → desempate por **% de HP**.
- Detalhe e racional: `docs/PLANO_COMBATE_V2.md`.

### Tabelas de Referência

**Bônus de ataque por STR:**
| STR | Bônus |
|-----|-------|
| 0-19 | +0 |
| 20-39 | +1 |
| 40-59 | +2 |
| 60 (cap) | +3 |

**AC por DEX:**
| DEX | AC |
|-----|-----|
| 0 | 10 |
| 10 | 20 |
| 20 | 30 |
| 40 (cap) | 50 |

**Exemplos de chance de acerto (d20):**
| Situação | Precisa de | Chance |
|----------|-----------|--------|
| 0 STR vs DEX 0 (AC 10) | 10+ | 55% |
| 0 STR vs DEX 40 (AC 50) | 50+ (impossível sem bônus) | 5% (só crit natural) |
| STR 60 (+3) vs DEX 40 (AC 50) | 47 → não alcança | ainda difícil |
| STR 60 (+3) vs DEX 20 (AC 30) | 27 → 20+3=23 → ainda difícil | ~5% (só 20 natural) |

> **Nota de design**: DEX alto + STR baixo = praticamente invulnerável. Builds focadas em STR compensam pelo aumento de dano quando acertam. Equilíbrio intencional — guerreiros tanque (CON) vs agressivos (STR) vs esquivos (DEX).

### Crítico e Fumble

- **Crítico** (natural 20): dano dobrado. Com LUK expande a janela:
  - 0 LUK: crit apenas no 20 (5%)
  - 15 LUK: crit no 19-20 (10%)
  - 30 LUK: crit no 18-20 (15%)
  - 45+ LUK: crit no 17-20 (20%, cap)
- **Fumble** (natural 1): miss automático independente de bônus

### Fortune Save (LUK)

Quando recebe um crítico: `floor(LUK/10)%` de chance de transformá-lo em hit normal.
- 0 LUK: 0%
- 30 LUK: 3%
- 50 LUK: 5% (cap em 10% com 100 LUK, mas LUK cap é 50)

### Log de Batalha

- Textos de ataque variados por resultado (acerto, miss, crítico, fumble)
- HP exibido após cada ação
- Tag interna `WINNER:Nome` removida antes de exibir ao jogador

### XP Loss em Morte por PvP

Quando derrotado em Zona PvP ou Alto Risco:
- Perde **10% do XP necessário para o nível atual**
- Pode dropar de nível (mínimo: nível 1)
- Itens equipados protegidos no Templo não caem (Alto Risco)
- Aparece no modal de resultado: "💀 -X XP"

---

## 16. Lore de Itens

Gerado automaticamente por `ItemLoreGenerator`:
- **Descrição (lore)**: baseada em raridade + tipo (4 tiers × 2 categorias = 8 pools de textos)
- **Origem**: onde foi encontrado
  - Quest: "Encontrado durante: Caça ao Chefe."
  - Loja: "Adquirido no Comércio de Mercador Viajante."
  - Forja: "Forjado pelo próprio guerreiro."
  - Inicial: "Equipamento inicial da guilda."
  - Drop: "Obtido após derrotar inimigo."

---

## 17. Email

Enviado via Brevo HTTP API (não SMTP, evita bloqueios de hosting):
- **Boas-vindas**: ao criar conta
- **Reset de senha**: link válido 30 min

---

## 18. Ranking Global

- **Arena**: rank points, atualizado a cada batalha
- **Torre Infernal**: melhor andar completado (histórico permanente)
- Ambos exibem nome do guerreiro (não username do login)

---

## 19. Log de Batalha e Narrativa

### Batalha
- Textos dinâmicos gerados em memória (arrays de frases)
- HP em vermelho, evasões em azul, vitória em dourado
- Separadores visuais entre rodadas

### Missões
- Texto narrativo aleatório por tipo de quest ("As trevas da masmorra foram varridas...")
- Texto especial roxo se item dropado ("Ao vasculhar os destroços...")

---

## 20. Guildas

### Criação
- Custa **100 bronze** para criar
- Nome único, 3-30 caracteres
- Criador torna-se líder automaticamente

### Mecânica de Membros
- Capacidade: `10 + level × 5` (nível 1 = 15 membros)
- Entrar: qualquer jogador sem guilda pode entrar se houver vaga
- Sair: membro sai livremente; líder só sai se for o único membro (dissolve) ou transferir liderança

### Ações do Líder
- **Expulsar membro**: remove qualquer membro da guilda
- **Transferir liderança**: passa o cargo para outro membro
- **Subir nível**: gasta gold da guilda (`(level-1) × 1000`)
- **Dissolver**: remove todos os membros e apaga a guilda

### Bônus Passivos por Nível
Aplicados automaticamente em quests e trabalho para todos os membros:

| Nível | Membros | XP Bonus | Drop Bonus | Bronze Bonus |
|-------|---------|----------|------------|--------------|
| 1 | 15 | +0% | +0% | +0% |
| 2 | 20 | +5% | +0% | +0% |
| 3 | 25 | +10% | +3% | +0% |
| 4 | 30 | +15% | +5% | +5% |
| 5 | 35 | +20% | +7% | +10% |
| 6+ | +5/lv | +20% (cap) | +7% (cap) | +10% (cap) |

- `xpBonus    = min(20, (level-1) × 5)` %
- `dropBonus  = min(7, max(0, level-2) × 2)` %
- `bronzeBonus= min(10, max(0, level-3) × 5)` %

### Economia da Guilda
- Membros podem **doar bronze** → convertido em gold da guilda
- Gold da guilda usado para subir nível
- Custo de nível: `(level-1) × 1000` guild gold

### Ranking de Guildas
- Guildas listadas por nível desc, depois gold desc

### Ranking de Doações (dentro da guilda)
- Exibido no painel da guilda, abaixo da lista de membros
- Mostra cada membro e quanto doou ao tesouro na sessão atual
- Ordenado por `guildDonatedBronze` descrescente
- Valor exibido no formato bronze/prata/ouro
- `Player.guildDonatedBronze` é zerado ao entrar/sair/guilda dissolvida

---

## 21. Guerra de Territórios

### Territórios

| Território | Bônus base | Bônus exclusivo |
|------------|------------|-----------------|
| Fortaleza Maldita | +10% XP, +10% bronze | +10% XP extra em quests |
| Minas de Ferro Negro | +10% XP, +10% bronze | +20% yield mineração |
| Desfiladeiro do Osso | +10% XP, +10% bronze | +20% yield pesca |

### Regras de controle
- Uma guilda domina no máximo 1 território
- Guilda dominante defende automaticamente — não pode atacar outro território
- Declaração de ataque feita pelo líder antes do próximo ciclo de 6h

### Manutenção de Território (dreno econômico de guild)
- A cada ciclo de guerra (6h), a guilda dominante paga manutenção do **tesouro (guild gold)**
- Custo = `500 × (1 + defenseStreak × 0.1)` — quanto mais tempo segura, mais caro
- Se o tesouro **não cobrir** → território vira **neutro** (perde por inadimplência) e streak zera
- Cria tensão econômica no end-game: segurar território exige guild com economia ativa

### Ciclo de Batalhas (automático — 00h, 06h, 12h, 18h UTC)

**1 atacante:**
- Neutro: luta NPCs → vence → domina
- Controlado: luta defensores → vence → domina

**Múltiplos atacantes — 2 Fases:**

**Fase 1 — Todos lutam contra os defensores originais de forma independente:**
- Cada guilda atacante luta separadamente contra os defensores
- Defensores recuperam HP entre lutas (exceto após a última)
- HP restante de cada atacante é salvo no banco (HP Fase 1)
- Debuff de streak aplicado nos defensores em todas as lutas da Fase 1

**Fase 2 — Desempate aleatório entre vencedores da Fase 1:**
- Vencedores embaralhados aleatoriamente (sem vantagem por quem declarou primeiro)
- Cada luta usa o HP Fase 1 de ambas as guilds (sem carry-over entre lutas)
- Vencedora da última luta domina o território

### Mecânica Guild Brawl
- Todos os membros com HP > 0 participam
- Pares aleatórios 1v1 via BattleSimulator
- Vencedor de cada 1v1 entra na próxima briga → 2v1
- HP dos guerreiros atualizado no banco após a batalha

### Debuff do Defensor
- `defenseStreak`: incrementa a cada rodada que o defensor mantém o território
- Debuff = `min(50, defenseStreak × 5)`% de ATK e DEF na próxima rodada
- Reset completo quando território troca de dono

### NPCs para território neutro
- Cavaleiros Amaldiçoados (Fortaleza Maldita) — alta DEF
- Golens de Ferro (Minas de Ferro Negro) — alto HP
- Esqueletos Guerreiros (Desfiladeiro do Osso) — balanceados, alta evasão
- Stats baseados na média dos membros atacantes × fator de dificuldade

### Unificação Kingdom/Território + flag de guild-war (Reinos V2)
- O enum `Territory` foi **removido** e fundido em `Kingdom` — **território == reino** (mesmo id).
- Cada `Kingdom` carrega seus dados de batalha (NPC, mults) e `exclusiveBonus`.
- **Flag de guild-war:** config `app.kingdoms.war-territories` (default `FISHING,MINING,COMBAT`) define
  quais reinos são contestáveis. Os demais (Grutas de Cristal, Mar Abençoado) são **zonas abertas**.
  Ligar guerra em mais reinos = trocar a config, sem deploy de código.

### Entidades
- `Kingdom` (enum): FISHING, MINING, COMBAT, GRUTAS_DE_CRISTAL, MAR_ABENCOADO (territórios de guerra = os 3 primeiros, por config)
- `TerritoryControl`: guilda dominante, defenseStreak, dominantSince (campo `territory` tipado `Kingdom`)
- `TerritoryDeclaration`: guilda atacante, reino alvo, timestamp
- `TerritoryBattleLog`: resultado, log de batalha, vencedor, data

---

## 22. Sistema de Correio (Mail)

### Envio
- Remetente digita o **username exato** do destinatário (não nome do guerreiro)
- Escreve uma mensagem (máx. 500 caracteres)
- Pode incluir **gold** opcional (valor em gold inteiro ≥ 0)
- Custo fixo: **1 gold por carta** (descontado na hora do envio)
- O gold anexado é transferido ao destinatário quando ele resgata a carta

### Recebimento
- Cartas aparecem na caixa de entrada ordenadas pela mais recente
- Indicador visual de não lidas (badge com contagem)
- Ao abrir a carta: marcada como lida
- Se há gold anexado: botão "Collect gold" transfere para o saldo
- Carta pode ser deletada após lida

### Regras
- Não pode enviar para si mesmo
- Destinatário deve existir (erro amigável se não encontrar)
- Remetente precisa ter: 1 gold (taxa) + gold anexado
- Limite de mensagem: 500 caracteres

### Entidade
- `Mail`: id, senderPlayerId, senderName (warrior name), recipientPlayerId, message, goldAmount, sentAt, readAt (null = unread), collectedAt (null = not collected)

### Endpoints
| Método | Rota | Ação |
|--------|------|------|
| GET | `/api/mail/inbox` | Lista cartas recebidas (mais recentes primeiro) |
| GET | `/api/mail/sent` | Lista cartas enviadas |
| POST | `/api/mail/send` | Envia carta `{recipientUsername, message, goldAmount}` |
| POST | `/api/mail/{id}/collect` | Resgata gold da carta |
| DELETE | `/api/mail/{id}` | Deleta carta da caixa de entrada |

---

## 23. World Tab — 5 Reinos (Reinos V2) ✅ Implementado

Aba **World** organiza as atividades em 5 reinos. Quests, zonas de coleta, treino, guerra de guild e
caçada PvE ficam **dentro de cada reino**. (A Forja/Smithing fica no Commerce.)

### Os 5 reinos

| Reino | Ícone | Atividade | Loot / Função | Guild-war? |
|-------|-------|-----------|---------------|-----------|
| Desfiladeiro do Osso | 🎣 | Pesca | Peixe de **ESTAMINA** | ✅ |
| Minas de Ferro Negro | ⛏ | Mineração | **Só minério** | ✅ |
| Fortaleza Maldita | ⚔ | Combate (guerra + treino + **caçada PvE**) | PvP de guild + treino + farm de mobs | ✅ |
| Grutas de Cristal | 🔎 | **Garimpo** | Fragmentos de joia | ❌ aberto |
| Mar Abençoado | 🐟 | Pesca | Peixe de **VIDA** (cap 90%) | ❌ aberto |

> Quais reinos são guild-war vem da config `app.kingdoms.war-territories` (default: os 3 primeiros).

### Quests por reino (Quests V2)
- **6 quests por reino** (30 no total), tiers de dificuldade crescente. A UI mostra **2 por vez**,
  revezando a cada **6h** (janela global, igual à Loja; avança 1 por janela). `GET /api/world/{kingdom}/quests`
  retorna a vitrine de 2 — separadas das quests clássicas de `/api/quests`.
- **Encontro de monstro na coleta:** chance escala com a dificuldade da quest (~15%→90%). Se aparecer,
  roda um combate (reusa o `BattleSimulator`); **é preciso vencer** para receber a recompensa.
  Perder → 0 XP/bronze/drop e o guerreiro fica com o HP que sobrou (pode ficar nocauteado).
- **Lore narrada na coleta:** texto curto (em inglês) contando o desfecho — travessia em paz,
  monstro derrotado ou derrota. Gerado pelo `KingdomQuestNarrator` (monstros temáticos por reino).

### 🎣 Desfiladeiro do Osso / 🐟 Mar Abençoado — Reinos de Pesca
| Zona | Level | Risco |
|------|-------|-------|
| Segura | 1+ | Nenhum |
| PvP | 10+ | PvP (cosmético por enquanto) |
| Alto risco | 20+ | PvP + raros |

- Desfiladeiro → peixe de estamina; Mar Abençoado → peixe de vida (cura até 90%).

### ⛏ Minas de Ferro Negro / 🔎 Grutas de Cristal — Reinos de Coleta
- Minas → só minério (Cobre→Mithril). Grutas → fragmentos de joia (Garimpo).
- Mesmas 3 zonas (Segura / PvP / Alto risco). Coletar gasta estamina (custo mostrado no botão) e
  exibe uma **lore curta** no resultado da coleta.

### ⚔ Fortaleza Maldita — Reino do Combate
| Recurso | Detalhe |
|---------|---------|
| **Treino** | Paga bronze → guerreiro treina X horas → coleta **XP puro** (timer estilo Work) |
| **Zonas de combate** | Campo de Batalha (PvP, Lv.10+) e Zona de Guerra (HIGH_RISK, Lv.20+) |
| **Caçada PvE** | `POST /api/world/COMBAT/raid` — mobs escalam com o nível, custa 15⚡; vitória rende gold (lv×10), XP (lv×12) e materiais (Núcleo de Fera sempre, Pele de Fera 25%) |
| **Guild War** | Declarar ataque / ver status fica dentro da tela do reino |

> A caçada PvE era o reino "Covil das Feras" no plano original; como tinha só essa mecânica, foi
> fundida na Fortaleza Maldita.

### Caçada PvE (antigo Covil das Feras)
- Repetível; reusa o BattleSimulator. Chefes (boss) ficam reservados para a Torre.
- Drop de materiais: `MONSTER_CORE` (1 + level/25, sempre na vitória) e `BEAST_HIDE` (25%).

### Interdependência forçada
| Necessidade | Fonte |
|-------------|-------|
| Estamina | Peixe de estamina → Desfiladeiro |
| Recuperar HP (até 90%) | Peixe de vida → Mar Abençoado |
| Equipamento | Forja (Commerce) ← Minério ← Minas |
| Joias | Forja ← Fragmentos ← Garimpo (Grutas) |
| XP/Level rápido | Treino + Quests + Caçada ← Fortaleza |

---

## 24. Funcionalidades Futuras Planejadas

- [ ] Cliente Godot (Steam)
- [ ] Mercado entre jogadores (integração Steam Marketplace)
- [ ] Masmorra em grupo (requer guilda)
- [ ] Dominação de Castelo (requer guilda)
- [ ] Mais classes de guerreiro
- [ ] Mais zonas e biomas
- [ ] Sistema de crafting avançado
- [ ] Eventos temporários
- [ ] SoulStone — cosmético/social (títulos, frames de guilda, lore customizado)
- [ ] SoulStone — métodos de ganho adicionais (login diário, conquistas, eventos)

---

## 25. SoulStone 💎 — Moeda VIP e SoulStone Shop

### Visão Geral

Moeda premium da conta (não do personagem). Obtida via compra (futuro: Stripe/Steam). Separada em **Status VIP** (principal), **compras permanentes** e **consumíveis**.

### Formas de Ganhar

| Método | Status |
|--------|--------|
| Compra direta (Stripe/Steam) | Planejado — admin endpoint por enquanto para testes |
| Login diário consecutivo | Futuro (+1 💎 a cada 7 dias) |
| Conquistas | Futuro |
| Eventos sazonais | Futuro |

---

### Status VIP (principal oferta)

| Campo | Valor |
|-------|-------|
| Custo | **15 💎** |
| Duração | **30 dias** |
| Renovação | Empilha (+30 dias se já ativo) |
| Bag | Expansão 20 slots **inclusa** |

**Benefícios VIP:**

| Benefício | Free | VIP |
|-----------|------|-----|
| Cura HP no Templo | Paga bronze | **Grátis, CD 10 min** |
| Missões instantâneas | 0/dia | **2/dia** (botão ⚡ Skip) |
| Lutas de Arena | 5/dia | **10/dia** |
| Buffs ativos | 1 | **2 simultâneos** |
| Bag | 10 slots | **20 slots** |

**Missão Instantânea VIP:**
- Botão "⚡ Instant (N restantes)" aparece nos quest cards
- Clicando, a quest inicia e conclui imediatamente
- Modal de collect abre com XP, bronze e drop — igual ao fluxo normal
- Counter reseta à meia-noite UTC

---

### Compras Permanentes (one-time)

| Feature | Custo | Detalhe |
|---------|-------|---------|
| Expandir Bag | 3 💎 | 10 slots → 20 slots (incluso no VIP) |
| Resetar atributos do guerreiro | 5 💎 | Redistribui todos os pontos alocados |
| Trocar nome do guerreiro | 2 💎 | Uma compra = uma troca |

### Consumíveis (gastam toda vez que usar)

| Feature | Custo | Cooldown / Limite |
|---------|-------|-------------------|
| Cura instantânea de HP (SoulStone) | 1 💎 | CD 30 min |
| Pular metade do CD de treino/work | 1 💎 | Uma vez por sessão ativa |

### Cosmético / Social (planejado para o futuro)
- Título exibido no perfil
- Frame especial no card da guilda
- Lore customizado para um item

### SoulStone Shop (aba no Commerce)
- Mostra VIP status atual e dias restantes
- Botão "Comprar VIP" ou "Renovar VIP (+30 dias)"
- Lista compras permanentes disponíveis
- Lista consumíveis

### Mecânica de Saldo
- Campo `soulStones` em `Player` (escopo de conta, não de personagem)
- Nunca pode ficar negativo
- Toda operação valida saldo antes de debitar

### Entidades
- `Player.soulStones` — saldo atual
- `Player.vipExpiresAt` — timestamp de expiração VIP (null = sem VIP)
- `Player.lastVipHealAt` — CD de 10 min da cura VIP grátis
- `Player.arenaFightsToday` + `Player.lastArenaFightDate` — limite diário de arena
- `Player.vipInstantQuestsToday` + `Player.lastVipQuestDate` — counter de missões instantâneas
- `Player.lastSoulstoneHealAt` — CD de 30 min da cura por SoulStone
- `Player.inventoryExpanded` — flag de bag expandida
- `Warrior.activeBuff2` + `Warrior.buffExpiresAt2` — segundo slot de buff (VIP)

### Endpoints
| Método | Rota | Ação |
|--------|------|------|
| POST | `/api/vip/buy` | Compra/renova VIP (15 💎, 30 dias) |
| GET  | `/api/vip/status` | Status VIP + benefícios restantes do dia |
| POST | `/api/temple/vip-heal` | Cura grátis VIP (CD 10 min) |
| POST | `/api/temple/soulstone-heal` | Cura por SoulStone (1 💎, CD 30 min) |
| POST | `/api/world/{kingdom}/quests/instant-start` | Missão instantânea VIP (2/dia) |
| POST | `/api/inventory/expand` | Expande bag 10→20 (3 💎, perm) |
| GET  | `/api/inventory/slots` | Info de slots atual |
| POST | `/api/warrior/rename` | Troca nome (2 💎, perm) |
| POST | `/api/warrior/reset-attributes` | Reseta atributos (5 💎, perm) |
| POST | `/api/admin/grant-soulstones` | Dar SoulStones (testes/admin) |

---

## 26. Cozinha (Cooking) ✅

Transforma peixes em **refeições** que dão um **buff de combate temporário** no slot **"Bem Alimentado"**
(separado dos 2 slots do Templo — empilha). Cozinhar é **instantâneo** (sem skill nem timer), na aba
**🍳 Cozinha** do Commerce.

- **10 receitas** (enum `Meal`): linha **ofensiva** (peixe de estamina do Desfiladeiro → +ATK/+DEF) e
  **defensiva** (peixe de vida do Mar Abençoado → +HP/+DEF/+evasão). Ex.: Filé de Salmão (+10 ATK/+5 DEF, 40min),
  Prato Lendário (+18/+12/+40, 60min), Assado da Fênix (+100 HP/+15 DEF/+8% evasão, 60min).
- **Cozinhar:** consome o peixe da receita → +1 refeição no estoque (`MealInventory`).
- **Comer:** consome 1 refeição → aplica o buff por X min (substitui a refeição ativa anterior).
- **Combate:** o buff entra no `combatStats` enquanto ativo. **Perdido na derrota/KO** (junto com os do Templo).
- **Balanço:** ~1.5-2× mais forte que o Templo, mas custa **peixe** (estamina + tempo de coleta) — sink de esforço.

### Endpoints
| Método | Rota | Ação |
|--------|------|------|
| GET | `/api/cooking/recipes` | Receitas + ingrediente/efeito/duração + se dá pra cozinhar |
| GET | `/api/cooking/meals` | Refeições cozidas em estoque |
| POST | `/api/cooking/cook` | Cozinha `{meal}` (consome peixe) |
| POST | `/api/cooking/eat` | Come `{meal}` (aplica o buff Bem Alimentado) |

---

## Status do Projeto

**Em testes com grupo fechado.** Backend deployado no Railway (PostgreSQL). Frontend servido pelo mesmo servidor Spring Boot.
