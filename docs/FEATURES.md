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
- **Subir nível**: gasta gold da guilda (`level × 1000`)
- **Dissolver**: remove todos os membros e apaga a guilda

### Economia da Guilda
- Membros podem **doar bronze** → convertido em gold da guilda
- Gold da guilda usado para subir nível
- Custo de nível: nível atual × 1.000 gold

### Ranking
- Guildas listadas por nível desc, depois gold desc

---

## 21. Funcionalidades Futuras Planejadas

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
