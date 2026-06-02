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
- Stats base: ATK 15, DEF 12, HP 110
- Level começa em 1; a cada nível: +2 ATK, +2 DEF, +15 HP, +5 pontos de atributo

### 2.2 Atributos (distribuídos pelo jogador)
- **Força**: +1 ATK por ponto
- **Destreza**: +1% evasão por ponto
- **Constituição**: +5 HP e +0.5 DEF por ponto
- **Sorte**: +1% chance de drop por ponto
- 5 pontos por nível, irreversível

### 2.3 HP com Regen Passiva
- Armazenado como % (0-100)
- Regenera 100% em 1 hora automaticamente
- HP=0: guerreiro inconsciente, não pode entrar em combate
- Cura disponível no Templo

### 2.4 Buff Ativo (Templo)
- Um buff por vez, dura 1 hora
- Buffs: Força (+5 ATK), Agilidade (+5% evasão), Defesa (+5 DEF), Vitalidade (+20 HP), Sorte (+5% drop)
- Buff é perdido ao ser derrotado em combate

### 2.5 Liberar Guerreiro Travado
- Endpoint `POST /api/warrior/free` cancela todas sessões ativas e libera o guerreiro

---

## 3. Sistema de Moedas (3 Tier)

- **Bronze**: moeda base (menor)
- **Prata**: 100 bronze = 1 prata
- **Ouro**: 100 prata = 1 ouro (= 10.000 bronze)
- Display: `2🥇 30🥈 45🥉`
- Futura 4ª moeda (VIP) planejada
- Novos jogadores começam com 50 prata

---

## 4. Stamina

- 0-100%, regenera 100% em 2 horas
- Consumida por: missões (10-50), arena (25), torre (25), zona (grátis)
- Guerreiro com stamina insuficiente não pode iniciar atividades

---

## 5. Missões (Quests)

### Tipos
| Missão | Duração | Bronze | XP | Stamina | Drop% |
|--------|---------|--------|-----|---------|-------|
| Patrulha | 5 min | 100 | 50 | 10 | 10% |
| Masmorra | 10 min | 250 | 150 | 20 | 25% |
| Raid | 20 min | 500 | 300 | 35 | 40% |
| Caça ao Chefe | 30 min | 1000 | 750 | 50 | 60% |

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

### Chefes por Andar
- Andares 1-3: Esqueleto, Goblin, Rato Gigante
- Andares 4-6: Aranha, Orc, Troll
- Andares 7-9: Zumbi, Vampiro, Golem
- Andares 10-12: Cavaleiro Negro, Arqueiro, Ogro
- Andares 13-15: Xamã, Wyvern, Lich Menor
- Andares 16+: Dragão, Titan, Lich Ancião, Guardiões Lendários

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
- Raridades: Comum (cinza), Incomum (verde), Raro (azul), Épico (roxo)
- Cada item tem: `description` (lore) e `origin` (onde foi encontrado) — gerados automaticamente
- Itens craftados têm 1-2 sockets garantidos

### Sockets e Joias
- Itens podem ter 0-3 sockets dependendo da raridade
- Joias encaixadas dão bônus permanentes: Rubi (+5 ATK), Safira (+5 DEF), Esmeralda (+20 HP), Diamante (+3/+3/+10), Ametista (+5% drop)

### Proteção (Templo)
- Até 3 itens podem ser protegidos (50 bronze cada)
- Itens protegidos NÃO caem em morte no Alto Risco

### Itens Iniciais
7 itens Comuns ao criar conta: Elmo, Armadura, Espada, Escudo, Botas, Luvas, Calça (todos de Ferro/Couro)

---

## 10. Loja (Comércio)

### Rotação Dinâmica
- 10 itens novos a cada 6 horas (mesma loja para todos ao mesmo tempo)
- Baseada em `rotationId = epochSeconds / 21600`
- Cada jogador pode comprar cada item uma vez por rotação

### Distribuição de Raridade por Sorteio
- 60% Comum, 25% Incomum, 12% Raro, 3% Épico
- Pool: 57+ itens cobrindo todos os 10 slots de equipamento

### Mercador
- Nome muda a cada rotação ("Gareth, o Mercador Andarilho", etc.)
- Timer mostra quando chega a próxima carroça

---

## 11. Habilidades (Pesca / Mineração / Forja)

### Skills
- 3 habilidades por jogador (sem restrição de especialização)
- Level 1-100, XP para próximo nível = level × 100
- Multiplicador de XP e recursos por zona

### Pesca
- Sessão timer: 5/10/20/30/40 min
- Produz peixes: Peixe Pequeno, Salmão, Atum, Tubarão, Peixe Lendário
- Peixes consumidos restauram stamina: +10/+25/+40/+60/+80

### Mineração
- Sessão timer: 10/20/30/45/60 min
- Produz minérios: Cobre, Ferro, Prata, Ouro, Mithril
- Chance de fragmentos de joias por tipo de minério

### Forja (Smithing)
- **Refinar**: 5 minérios + bronze → 1 barra (custo escala por nível)
- **Craftar equipamento**: barras → item com sockets garantidos
- **Craftar joia**: 3 fragmentos do mesmo tipo → 1 joia

---

## 12. Inventário de Recursos

Tipos: Peixes, Minérios, Fragmentos, Barras, Joias, Materiais

Cada tipo tem:
- `displayName`, `category`, `quantity` (stackável)
- Separado do inventário de equipamentos

---

## 13. Zonas e Expedições

### Zonas
| Zona | Level mín | Multiplicador | NPC %/h | PvP %/h |
|------|-----------|--------------|---------|---------|
| Zona Segura | 1 | ×1.0 | 15% | 0% |
| Zona PvP | 10 | ×1.5 | 25% | 20% |
| Zona Alto Risco | 20 | ×2.5 | 35% | 40% |

### Mecânica Gatherer
- Escolhe zona, habilidade (pesca/mineração), duração (30 min a 12 h)
- Ao coletar: recursos + XP com multiplicador da zona
- NPCs e hunters podem atacar durante a expedição

### Mecânica Hunter
- Patrulha uma zona por 1-6 h
- Sistema casa automaticamente com gatherers ativos
- Vitória: rouba 15% do bronze do gatherer (recebe 50% do roubado)
- Derrota: stamina 0, cooldown

### Consequências de Derrota
- HP = 0 + stamina = 0 + buff perdido
- Perde 15% do bronze
- **Alto Risco**: 10% de chance de perder 1 item equipado (que não esteja protegido)

### NPCs por Zona
- Segura: Lobo, Bandoleiro, Urso, Javali
- PvP: Mercenário Corrupto, Orc, Cavaleiro Renegado
- Alto Risco: Demônio Menor, Lich, Dragão Jovem
- Stats: level do guerreiro +0 a +3 (nunca garante vitória)

---

## 14. Templo

### Cura
- Restaura HP para 100% instantaneamente
- Grátis se guerreiro ≤ level 10
- Custa 1 prata (100 bronze) se level > 10

### Bênçãos (Buffs)
| Buff | Efeito | Custo |
|------|--------|-------|
| Força | +5 ATK | 30 bronze |
| Agilidade | +5% evasão | 30 bronze |
| Defesa | +5 DEF | 30 bronze |
| Vitalidade | +20 HP máximo | 30 bronze |
| Sorte | +5% drop chance | 50 bronze |

- Um buff por vez, dura 1 hora
- Perdido ao ser derrotado

### Proteção de Itens
- Máximo 3 itens, 50 bronze por item, permanente
- Itens protegidos não caem na Zona de Alto Risco

---

## 15. Sistema de Combate (BattleSimulator)

Reutilizado por Arena, Torre e Zona. Gera log dinâmico:
- Textos de ataque variados ("avança ferozmente e desfere um golpe no peito...")
- Partes do corpo aleatórias
- Evasões com texto descritivo
- HP exibido em vermelho a cada ação
- Tag interna `WINNER:Nome` removida antes de exibir

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

### Ciclo de Batalhas (automático — 00h, 06h, 12h, 18h UTC)
- Território neutro: guilda luta NPCs (1 NPC por membro); se múltiplas guildas venceram, lutam entre si
- Território controlado: defensores lutam contra cada atacante em sequência
  - Defensores regeneram HP entre lutas, exceto após a última
  - Debuff de streak não se aplica na rodada atual

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

### Entidades
- `Territory` (enum): FORTALEZA_MALDITA, MINAS_DE_FERRO_NEGRO, DESFILADEIRO_DO_OSSO
- `TerritoryControl`: guilda dominante, defenseStreak, dominantSince
- `TerritoryDeclaration`: guilda atacante, território alvo, timestamp
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

## 23. World Tab — 3 Reinos 🚧 Planejado

Substitui as abas Taverna, Expedições, Habilidades (pesca/mineração) e Territórios por uma única aba **World** organizada em 3 reinos interdependentes.

### Abas removidas / reorganizadas

| Antes | Depois |
|-------|--------|
| Taverna (quests) | Dentro de cada reino → Taverna do reino |
| Expedições (zones) | Dentro de cada reino → zonas por tipo |
| Habilidades pesca/mine | Dentro de cada reino → gathering do reino |
| Territórios | Integrado na tela de cada reino |
| Smithing/Forja | Move para Commerce |

### Estrutura dos 3 Reinos

#### 🎣 Desfiladeiro do Osso — Reino da Pesca

| Zona | Level | Risco | Atividade |
|------|-------|-------|-----------|
| Porto Seguro | 1+ | Nenhum | Pesca básica |
| Costa Selvagem | 10+ | PvP | Pesca com hunters |
| Mar Profundo | 20+ | PvP + monstros | Peixes raros |

- **Quests:** Patrulhe a Costa, Explore os Recifes, Raid do Mar Profundo, Caça ao Monstro Marinho
- **Futuro:** Sistema de Cozinha → refeições com buffs premium, bônus de guild

#### ⛏ Minas de Ferro Negro — Reino da Mineração

| Zona | Level | Risco | Atividade |
|------|-------|-------|-----------|
| Mina Aberta | 1+ | Nenhum | Mineração básica |
| Túneis Profundos | 10+ | PvP | Mineração com hunters |
| Minas Proibidas | 20+ | PvP + monstros | Minérios raros |

- **Quests:** Escolta os Mineiros, Limpe as Cavernas, Recupere o Minério Raro, Derrote a Besta das Cavernas

#### ⚔ Fortaleza Maldita — Reino do Combate

| Zona | Level | Risco | Atividade |
|------|-------|-------|-----------|
| Arena de Treino | 1+ | Nenhum | **Treino pago** — paga bronze, ganha EXP (timer) |
| Campo de Batalha | 10+ | PvP | Monstros + players |
| Zona de Guerra | 20+ | PvP + monstros | Combate intenso |

- **Quests:** Defenda as Muralhas, Limpe a Masmorra, Raid ao Acampamento, Caça ao Senhor da Guerra
- **Treino:** nova mecânica — paga bronze, guerreiro treina por X horas, coleta XP puro

### Interdependência forçada

| Necessidade | Fonte |
|-------------|-------|
| Estamina premium | Peixes → Desfiladeiro |
| Equipamento | Forja (Commerce) ← Minério ← Minas |
| EXP/Level rápido | Treino + Quests ← Fortaleza |

### Mecânica de Treino (nova — Fortaleza)

- Player paga X bronze → guerreiro fica "treinando" por Y horas
- Timer como Work, mas recompensa é **XP puro** (sem bronze, sem itens)
- Mais eficiente em XP/hora que quests; sem outras recompensas
- Custo e XP escalam com o nível do guerreiro

### Guild War integrada

- Sistema atual de Guild War permanece idêntico
- Declarar ataque e ver status ficam **dentro da tela do reino**
- Cada reino mantém seus bônus exclusivos para guilda dominante

---

## 24. Funcionalidades Futuras Planejadas

- [ ] Cliente Godot (Steam)
- [ ] Mercado entre jogadores (integração Steam Marketplace)
- [ ] Masmorra em grupo (requer guilda)
- [ ] Dominação de Castelo (requer guilda)
- [ ] 4ª moeda VIP
- [ ] Mais classes de guerreiro
- [ ] Mais zonas e biomas
- [ ] Sistema de crafting avançado
- [ ] Eventos temporários

---

## Status do Projeto

**Em testes com grupo fechado.** Backend deployado no Railway (PostgreSQL). Frontend servido pelo mesmo servidor Spring Boot.
