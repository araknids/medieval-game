# Medieval Game — Casos de Uso

> Gerado por agente com base em `FEATURES.md` e `CLAUDE.md`. Atualizar sempre que novas funcionalidades forem adicionadas.

---

## Índice

| ID | Nome |
|----|------|
| UC-01 | Cadastrar Conta |
| UC-02 | Fazer Login |
| UC-03 | Solicitar Recuperação de Senha |
| UC-04 | Redefinir Senha via Link |
| UC-05 | Distribuir Pontos de Atributo do Guerreiro |
| UC-06 | Consultar HP e Aguardar Regeneração |
| UC-07 | Receber Buff no Templo |
| UC-08 | Liberar Guerreiro Travado |
| UC-09 | Gastar Moedas |
| UC-10 | Ganhar Moedas |
| UC-11 | Consumir Stamina em Atividade |
| UC-12 | Aguardar Regeneração de Stamina |
| UC-13 | Enviar Guerreiro em Missão |
| UC-14 | Coletar Recompensa de Missão |
| UC-15 | Abandonar Missão |
| UC-16 | Entrar na Arena PvP |
| UC-17 | Coletar Resultado da Arena |
| UC-18 | Entrar na Torre Infernal |
| UC-19 | Continuar ou Sair da Torre Infernal |
| UC-20 | Iniciar Trabalho |
| UC-21 | Coletar Recompensa de Trabalho |
| UC-22 | Cancelar Trabalho |
| UC-23 | Equipar Item do Inventário |
| UC-24 | Desequipar Item |
| UC-25 | Vender Item |
| UC-26 | Navegar na Loja |
| UC-27 | Comprar Item na Loja |
| UC-28 | Iniciar Sessão de Pesca |
| UC-29 | Coletar Resultado de Pesca |
| UC-30 | Cancelar Sessão de Pesca |
| UC-31 | Consumir Peixe para Restaurar Stamina |
| UC-32 | Iniciar Sessão de Mineração |
| UC-33 | Coletar Resultado de Mineração |
| UC-34 | Cancelar Sessão de Mineração |
| UC-35 | Refinar Minérios na Forja |
| UC-36 | Craftar Equipamento na Forja |
| UC-37 | Craftar Joia na Forja |
| UC-38 | Encaixar Joia em Socket de Item |
| UC-39 | Entrar em Zona como Coletor (Gatherer) |
| UC-40 | Entrar em Zona como Caçador (Hunter) |
| UC-41 | Coletar Resultado de Expedição em Zona |
| UC-42 | Curar Guerreiro no Templo |
| UC-43 | Aplicar Bênção (Buff) no Templo |
| UC-44 | Proteger Item no Templo |
| UC-45 | Remover Proteção de Item |
| UC-46 | Consultar Ranking da Torre Infernal |
| UC-47 | Consultar Ranking da Arena |

---

## UC-01 — Cadastrar Conta

**Ator:** Visitante (usuário sem conta)
**Pré-condições:** O usuário não possui conta cadastrada no sistema.
**Trigger:** O usuário acessa a página de cadastro e clica em "Registrar".

**Fluxo Principal:**
1. O sistema exibe o formulário de cadastro com os campos: username, email, senha e nome do guerreiro.
2. O usuário preenche todos os campos e submete o formulário.
3. O sistema valida os dados (username 3-20 caracteres, email válido, senha mínimo 6 caracteres, nome do guerreiro preenchido).
4. O sistema cria a conta do jogador no banco de dados.
5. O sistema cria automaticamente um guerreiro do tipo WARRIOR com stats base (ATK 15, DEF 12, HP 110) vinculado à conta.
6. O sistema adiciona 50 prata (5.000 bronze) ao saldo inicial do jogador.
7. O sistema adiciona 7 itens iniciais Comuns ao inventário do jogador (Elmo, Armadura, Espada, Escudo, Botas, Luvas, Calça — todos de Ferro/Couro).
8. O sistema envia um email de boas-vindas ao endereço informado via Brevo API.
9. O sistema retorna confirmação de cadastro ao usuário.

**Fluxo Alternativo:**
- FA1: Username já existe → o sistema retorna erro "Username já em uso" e solicita novo username.
- FA2: Email já cadastrado → o sistema retorna erro "Email já registrado".
- FA3: Senha com menos de 6 caracteres → o sistema retorna erro de validação.
- FA4: Serviço de email indisponível → conta é criada normalmente; email de boas-vindas não é enviado (falha silenciosa).

**Pós-condições:** Conta criada, guerreiro criado, inventário inicial populado, saldo de 50 prata disponível.
**Regras de Negócio:**
- Username: 3 a 20 caracteres.
- Senha mínima: 6 caracteres.
- Cada conta possui exatamente um guerreiro; não é possível criar múltiplos.
- Saldo inicial: 50 prata (não em ouro, não em bronze diretamente — convertido internamente para bronze).

---

## UC-02 — Fazer Login

**Ator:** Jogador (com conta cadastrada)
**Pré-condições:** O jogador possui uma conta ativa no sistema.
**Trigger:** O usuário acessa a tela de login e submete suas credenciais.

**Fluxo Principal:**
1. O usuário informa username e senha no formulário de login.
2. O sistema valida as credenciais contra o banco de dados.
3. O sistema gera um token JWT com validade de 7 dias.
4. O sistema retorna o JWT ao cliente.
5. O frontend armazena o token no `localStorage` do navegador.
6. O usuário é redirecionado para a tela principal do jogo.

**Fluxo Alternativo:**
- FA1: Username ou senha incorretos → o sistema retorna erro de autenticação (401) sem especificar qual campo está errado.
- FA2: Token JWT expirado em sessão anterior → o sistema rejeita requisições subsequentes com 401; usuário precisa fazer login novamente.

**Pós-condições:** Sessão autenticada ativa; token JWT válido por 7 dias salvo no navegador.
**Regras de Negócio:**
- Todas as requisições subsequentes devem incluir o header `Authorization: Bearer <JWT>`.
- O token não é renovável automaticamente; após 7 dias o usuário precisa fazer login novamente.

---

## UC-03 — Solicitar Recuperação de Senha

**Ator:** Jogador (com conta cadastrada, que esqueceu a senha)
**Pré-condições:** O jogador possui conta cadastrada com um email válido.
**Trigger:** O usuário clica em "Esqueci minha senha" e informa o email.

**Fluxo Principal:**
1. O usuário acessa a tela de recuperação de senha.
2. O usuário informa o endereço de email cadastrado.
3. O sistema verifica se o email existe na base de dados.
4. O sistema gera um token de reset único e com validade de 30 minutos.
5. O sistema envia um email contendo o link de reset via Brevo API.
6. O sistema exibe mensagem informando que, se o email existir, o link será enviado.

**Fluxo Alternativo:**
- FA1: Email não encontrado na base → o sistema exibe a mesma mensagem genérica (não revela se o email existe, por segurança).
- FA2: Serviço de email indisponível → o token é gerado mas o email não é entregue; o usuário não recebe o link.

**Pós-condições:** Token de reset gerado e associado à conta; email enviado ao usuário.
**Regras de Negócio:**
- O link de reset é válido por exatamente 30 minutos a partir do momento da geração.
- Um novo pedido de reset invalida tokens anteriores ainda não utilizados.

---

## UC-04 — Redefinir Senha via Link

**Ator:** Jogador (com link de reset válido)
**Pré-condições:** O jogador recebeu e acessou o link de reset dentro do prazo de 30 minutos.
**Trigger:** O usuário acessa o link de reset de senha recebido por email.

**Fluxo Principal:**
1. O usuário clica no link recebido por email.
2. O sistema valida o token contido no link (existência e validade temporal).
3. O sistema exibe o formulário de nova senha no navegador.
4. O usuário informa a nova senha (mínimo 6 caracteres) e confirma.
5. O sistema atualiza a senha na conta correspondente.
6. O sistema invalida o token de reset utilizado.
7. O sistema exibe mensagem de sucesso e redireciona para o login.

**Fluxo Alternativo:**
- FA1: Token expirado (mais de 30 minutos) → o sistema exibe erro "Link expirado" e orienta o usuário a solicitar novo reset.
- FA2: Token inválido ou já utilizado → o sistema exibe erro e bloqueia o acesso ao formulário.
- FA3: Nova senha com menos de 6 caracteres → o sistema retorna erro de validação sem salvar.

**Pós-condições:** Senha da conta atualizada; token de reset invalidado; usuário pode fazer login com a nova senha.
**Regras de Negócio:**
- Nova senha deve ter mínimo de 6 caracteres.
- Cada token de reset pode ser usado apenas uma vez.
- Token válido por 30 minutos.

---

## UC-05 — Distribuir Pontos de Atributo do Guerreiro

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro possui pontos de atributo não distribuídos (acumulados ao subir de nível).
**Trigger:** O jogador acessa a tela do guerreiro e clica para alocar pontos de atributo.

**Fluxo Principal:**
1. O sistema exibe os atributos disponíveis: Força, Destreza, Constituição e Sorte, junto com a quantidade de pontos disponíveis.
2. O jogador seleciona em qual atributo deseja investir e confirma.
3. O sistema valida que há pontos disponíveis suficientes.
4. O sistema aplica o efeito do atributo escolhido no guerreiro:
   - Força: +1 ATK por ponto.
   - Destreza: +1% evasão por ponto.
   - Constituição: +5 HP máximo e +0,5 DEF por ponto.
   - Sorte: +1% chance de drop por ponto.
5. O sistema decrementa os pontos disponíveis e salva.
6. O sistema retorna o estado atualizado do guerreiro.

**Fluxo Alternativo:**
- FA1: Sem pontos disponíveis → o sistema exibe mensagem informando que não há pontos para distribuir.

**Pós-condições:** Atributo do guerreiro atualizado permanentemente; pontos disponíveis decrementados.
**Regras de Negócio:**
- O guerreiro recebe 5 pontos de atributo a cada nível ganho.
- A alocação de atributos é irreversível; não há como redistribuir pontos.
- Ganho de nível: a cada level, o guerreiro também recebe automaticamente +2 ATK, +2 DEF e +15 HP de stats base.

---

## UC-06 — Consultar HP e Aguardar Regeneração

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro possui HP abaixo de 100%.
**Trigger:** O jogador acessa a tela do guerreiro para verificar o estado de saúde.

**Fluxo Principal:**
1. O sistema exibe o HP atual do guerreiro como porcentagem (0–100%).
2. O sistema calcula o HP atual com base no snapshot armazenado e no tempo decorrido desde a última atualização.
3. O sistema exibe o tempo estimado para regeneração completa.
4. O guerreiro regenera passivamente 100% de HP em 1 hora (regen linear contínua).
5. O jogador aguarda a regeneração ou utiliza o Templo para cura imediata (ver UC-42).

**Fluxo Alternativo:**
- FA1: HP = 0 (guerreiro inconsciente) → o sistema exibe aviso de que o guerreiro está inconsciente e não pode participar de combate; sugere cura no Templo.

**Pós-condições:** HP do guerreiro reflete o valor atualizado com o tempo de regen passiva.
**Regras de Negócio:**
- HP armazenado como porcentagem (campo `currentHpSnapshot`) com base em snapshot + tempo decorrido.
- Taxa de regeneração: 100% em 60 minutos (≈ 1,67% por minuto).
- Guerreiro com HP = 0 não pode entrar em missões, arena, torre ou zona como combatente.

---

## UC-07 — Receber Buff no Templo

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro não possui buff ativo; o jogador possui bronze suficiente para o buff escolhido.
**Trigger:** O jogador acessa o Templo e seleciona uma bênção para aplicar.

**Fluxo Principal:**
1. O sistema exibe os buffs disponíveis com seus custos e efeitos.
2. O jogador seleciona o buff desejado e confirma o pagamento.
3. O sistema verifica o saldo do jogador.
4. O sistema debita o custo do buff em bronze.
5. O sistema aplica o buff ao guerreiro com duração de 1 hora a partir do momento da aplicação.
6. O sistema retorna o estado atualizado do guerreiro.

**Fluxo Alternativo:**
- FA1: Guerreiro já possui buff ativo → o sistema informa que apenas um buff pode estar ativo por vez.
- FA2: Saldo insuficiente → o sistema retorna erro de bronze insuficiente.

**Pós-condições:** Buff ativo no guerreiro por 1 hora; bronze debitado.
**Regras de Negócio:**
- Apenas um buff pode estar ativo por vez.
- Duração do buff: 1 hora.
- O buff é perdido imediatamente ao ser derrotado em combate (arena, torre ou zona).
- Custos: Força, Agilidade, Defesa, Vitalidade = 30 bronze cada; Sorte = 50 bronze.
- Efeitos: Força (+5 ATK), Agilidade (+5% evasão), Defesa (+5 DEF), Vitalidade (+20 HP máximo), Sorte (+5% drop chance).

---

## UC-08 — Liberar Guerreiro Travado

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está marcado como `onMission=true` por alguma sessão corrompida ou bug.
**Trigger:** O jogador acessa a opção de liberar guerreiro travado e confirma a ação.

**Fluxo Principal:**
1. O jogador acessa a opção "Liberar Guerreiro" disponível na interface.
2. O sistema exibe aviso de que a ação cancela todas as sessões ativas sem recompensa.
3. O jogador confirma a ação.
4. O sistema chama `POST /api/warrior/free` e cancela todas as sessões ativas do guerreiro (missão, arena, trabalho, torre, coleta).
5. O sistema define `onMission=false` no guerreiro.
6. O sistema retorna confirmação de liberação.

**Fluxo Alternativo:**
- FA1: Guerreiro já está livre (`onMission=false`) → o sistema informa que o guerreiro já está disponível.

**Pós-condições:** Guerreiro disponível para novas atividades; todas as sessões anteriores canceladas sem recompensa.
**Regras de Negócio:**
- A liberação não concede recompensas parciais por sessões canceladas.
- O recurso existe para recuperação de estados inconsistentes e deve ser usado com cautela.

---

## UC-09 — Gastar Moedas

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui saldo suficiente na(s) moeda(s) necessária(s).
**Trigger:** O jogador confirma uma ação que exige pagamento em bronze, prata ou ouro.

**Fluxo Principal:**
1. O sistema identifica o custo da ação solicitada (em bronze como unidade base).
2. O sistema verifica o saldo total do jogador (convertendo bronze + prata × 100 + ouro × 10.000).
3. O sistema debita o valor via `playerService.spendBronze(player, n)`.
4. O sistema atualiza os campos `bronze`, `silver` e `gold` do jogador conforme necessário para manter a normalização.
5. O sistema confirma a transação e prossegue com a ação.

**Fluxo Alternativo:**
- FA1: Saldo insuficiente → o sistema retorna erro e a ação não é executada.

**Pós-condições:** Saldo do jogador reduzido pelo custo da ação.
**Regras de Negócio:**
- As três moedas são armazenadas separadamente: `bronze`, `silver`, `gold`.
- Taxa de conversão: 100 bronze = 1 prata; 100 prata = 1 ouro.
- Nunca alterar `gold` diretamente; sempre usar `player.addBronzeAmount()` ou `playerService.spendBronze()`.
- O saldo exibido ao usuário é normalizado: ex. `2🥇 30🥈 45🥉`.

---

## UC-10 — Ganhar Moedas

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; uma ação recompensável foi concluída (missão, arena, torre, trabalho, venda de item).
**Trigger:** O sistema processa a conclusão de uma atividade recompensável.

**Fluxo Principal:**
1. O sistema calcula a recompensa em bronze da atividade concluída.
2. O sistema adiciona o valor via `player.addBronzeAmount(n)`.
3. O sistema normaliza o saldo (converte excesso de bronze em prata e excesso de prata em ouro automaticamente).
4. O sistema salva o saldo atualizado.
5. O sistema exibe o saldo atualizado ao jogador.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Saldo do jogador aumentado; valores normalizados entre as três moedas.
**Regras de Negócio:**
- Toda adição de moeda deve passar por `player.addBronzeAmount()` para garantir normalização correta.
- Nunca somar diretamente ao campo `gold`.

---

## UC-11 — Consumir Stamina em Atividade

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro possui stamina suficiente para a atividade escolhida.
**Trigger:** O jogador inicia uma atividade que consome stamina (missão, arena ou torre).

**Fluxo Principal:**
1. O sistema verifica a stamina atual do guerreiro (calculada por snapshot + tempo decorrido).
2. O sistema verifica se a stamina atual é suficiente para a atividade.
3. O sistema debita a stamina correspondente à atividade.
4. O sistema inicia a atividade.

**Fluxo Alternativo:**
- FA1: Stamina insuficiente → o sistema bloqueia o início da atividade e exibe erro informando a stamina necessária.

**Pós-condições:** Stamina do guerreiro reduzida conforme o custo da atividade; atividade iniciada.
**Regras de Negócio:**
- Stamina: 0–100%, regenera 100% em 2 horas.
- Custo por atividade: Patrulha (10), Masmorra (20), Raid (35), Caça ao Chefe (50), Arena (25), Torre (25).
- Expedições em zona são gratuitas em termos de stamina de entrada, mas derrota no combate pode levar stamina a 0.

---

## UC-12 — Aguardar Regeneração de Stamina

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro possui stamina abaixo de 100%.
**Trigger:** O jogador consulta a stamina atual do guerreiro.

**Fluxo Principal:**
1. O sistema calcula a stamina atual com base no snapshot e no tempo decorrido.
2. O sistema exibe a stamina atual em porcentagem.
3. O sistema exibe o tempo estimado para stamina completa.
4. O guerreiro regenera passivamente 100% de stamina em 2 horas (regen linear contínua).

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Stamina exibida ao jogador reflete o valor atualizado com regen passiva.
**Regras de Negócio:**
- Taxa de regeneração: 100% em 120 minutos (≈ 0,83% por minuto).
- Não há item ou ação para acelerar a regen de stamina, exceto consumir peixes (ver UC-31).

---

## UC-13 — Enviar Guerreiro em Missão

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível (`onMission=false`); o guerreiro possui HP > 0; o guerreiro possui stamina suficiente para o tipo de missão escolhido.
**Trigger:** O jogador seleciona um tipo de missão e confirma o envio.

**Fluxo Principal:**
1. O sistema exibe os tipos de missão disponíveis com duração, recompensa em bronze, XP, custo de stamina e chance de drop.
2. O jogador seleciona o tipo de missão desejado.
3. O sistema valida HP > 0 e stamina suficiente.
4. O sistema debita a stamina correspondente.
5. O sistema cria o registro da missão com timestamp de início e duração prevista.
6. O sistema define `onMission=true` no guerreiro.
7. O sistema retorna confirmação com o timer de retorno.

**Fluxo Alternativo:**
- FA1: Guerreiro já em missão (`onMission=true`) → o sistema retorna erro informando que o guerreiro já está ocupado.
- FA2: HP = 0 → o sistema bloqueia o envio e sugere cura no Templo.
- FA3: Stamina insuficiente → o sistema bloqueia e informa o custo necessário.

**Pós-condições:** Guerreiro em missão; stamina debitada; timer ativo; `onMission=true`.
**Regras de Negócio:**
- Tipos de missão: Patrulha (5 min, 100 bronze, 50 XP, 10 stamina, 10% drop), Masmorra (10 min, 250 bronze, 150 XP, 20 stamina, 25% drop), Raid (20 min, 500 bronze, 300 XP, 35 stamina, 40% drop), Caça ao Chefe (30 min, 1000 bronze, 750 XP, 50 stamina, 60% drop).
- Em ambiente de desenvolvimento com `instant-complete=true`, o timer é zerado imediatamente.

---

## UC-14 — Coletar Recompensa de Missão

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está em missão; o timer da missão foi concluído.
**Trigger:** O jogador acessa a tela de missões e clica em "Coletar".

**Fluxo Principal:**
1. O sistema verifica que o timer da missão foi completado.
2. O sistema calcula as recompensas: bronze + XP do tipo de missão.
3. O sistema verifica se houve drop de item (chance base do tipo de missão + bônus de Sorte do guerreiro).
4. Se houver drop: o sistema gera o item com raridade correspondente ao tipo de missão, lore e origem automáticos, e adiciona ao inventário.
5. O sistema adiciona o bronze e o XP ao jogador/guerreiro.
6. O sistema define `onMission=false` no guerreiro.
7. O sistema exibe a tela de resultado com narrativa temática e, se houver item, texto especial em roxo.

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante e bloqueia a coleta.

**Pós-condições:** Recompensas creditadas; guerreiro disponível; item no inventário (se houver drop).
**Regras de Negócio:**
- Raridade do drop por tipo de missão: Patrulha = Comum; Masmorra = Comum/Incomum; Raid = Incomum/Raro; Caça ao Chefe = Raro/Épico.
- A chance de drop é: chance base + (pontos de Sorte × 1%).
- Itens gerados por missão possuem origem "Encontrado durante: [tipo da missão]".
- O XP pode levar o guerreiro a subir de nível, concedendo +2 ATK, +2 DEF, +15 HP e +5 pontos de atributo.

---

## UC-15 — Abandonar Missão

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está atualmente em missão.
**Trigger:** O jogador seleciona a missão em andamento e clica em "Abandonar".

**Fluxo Principal:**
1. O sistema exibe a missão em andamento e a opção de abandono com aviso de que não haverá recompensa.
2. O jogador confirma o abandono.
3. O sistema cancela a missão via `POST /api/quests/{id}/abandon`.
4. O sistema define `onMission=false` no guerreiro imediatamente.
5. O sistema não concede bronze, XP ou itens.
6. O sistema confirma o abandono e exibe o guerreiro como disponível.

**Fluxo Alternativo:**
- FA1: Missão já concluída (timer zerado) → o sistema redireciona o jogador para a tela de coleta em vez de abandonar.

**Pós-condições:** Missão cancelada; guerreiro disponível; nenhuma recompensa concedida; stamina já gasta não é devolvida.
**Regras de Negócio:**
- O abandono é imediato e não requer aguardar o timer.
- A stamina consumida ao iniciar a missão não é reembolsada.

---

## UC-16 — Entrar na Arena PvP

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível (`onMission=false`); o guerreiro possui HP > 0; o guerreiro possui ao menos 25 de stamina.
**Trigger:** O jogador acessa a Arena e clica em "Entrar na Arena".

**Fluxo Principal:**
1. O sistema verifica HP > 0 e stamina ≥ 25.
2. O sistema debita 25 de stamina.
3. O sistema busca um oponente: preferencialmente um jogador real da mesma zona; caso não encontre, gera um NPC.
4. O sistema cria a sessão de arena com timer de 1 minuto (em produção).
5. O sistema define `onMission=true` no guerreiro.
6. O sistema retorna confirmação com o timer de resolução.

**Fluxo Alternativo:**
- FA1: Guerreiro ocupado → o sistema informa que o guerreiro já está em atividade.
- FA2: HP = 0 → o sistema bloqueia a entrada e sugere cura no Templo.
- FA3: Stamina < 25 → o sistema bloqueia e informa que são necessários 25 de stamina.

**Pós-condições:** Sessão de arena criada; guerreiro em patrulha; stamina debitada; combate será resolvido automaticamente.
**Regras de Negócio:**
- Custo: 25 stamina.
- Resolução assíncrona: o combate é calculado automaticamente pelo BattleSimulator.
- Oponente pode ser jogador real ou NPC gerado pelo sistema.
- Timer: 1 minuto em produção; instantâneo em dev com `instant-complete=true`.

---

## UC-17 — Coletar Resultado da Arena

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há uma sessão de arena concluída aguardando coleta.
**Trigger:** O jogador acessa a Arena e clica em "Coletar Resultado".

**Fluxo Principal:**
1. O sistema verifica que o timer de arena foi concluído.
2. O sistema processa o resultado do combate gerado pelo BattleSimulator.
3. O sistema determina o resultado (vitória ou derrota).
4. Em caso de **vitória**: adiciona 200 bronze e +25 rank points ao jogador.
5. Em caso de **derrota**: adiciona 50 bronze (consolação), decrementa 15 rank points, define HP = 0 e remove o buff ativo do guerreiro.
6. O sistema define `onMission=false` no guerreiro.
7. O sistema exibe o log detalhado do combate (sem a tag interna `WINNER:`).

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante.

**Pós-condições:** Recompensa creditada; rank points atualizados; HP e buff atualizados conforme resultado.
**Regras de Negócio:**
- Vitória: +200 bronze, +25 rank points.
- Derrota: +50 bronze (consolação), -15 rank points, HP = 0, buff perdido.
- Os rank points afetam o ranking global de Arena (ver UC-47).
- O log de batalha exibe a tag `WINNER:` removida antes de ser exibido ao usuário.

---

## UC-18 — Entrar na Torre Infernal

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível; o guerreiro possui HP > 0; o guerreiro possui ao menos 25 de stamina.
**Trigger:** O jogador acessa a Torre Infernal e clica em "Entrar".

**Fluxo Principal:**
1. O sistema identifica o andar atual do guerreiro (próximo ao melhor andar já completado, ou andar 1 se for a primeira vez).
2. O sistema verifica HP > 0 e stamina ≥ 25.
3. O sistema debita 25 de stamina.
4. O sistema exibe o chefe do andar atual com seus atributos.
5. O BattleSimulator resolve o combate automaticamente.
6. Em caso de **vitória**: o sistema concede as recompensas do andar (bronze = andar × 40; XP = andar × 20) e oferece a opção de continuar ou sair (ver UC-19).
7. Em caso de **derrota**: o sistema expulsa o guerreiro, define HP = 0, remove o buff ativo e define `onMission=false`.

**Fluxo Alternativo:**
- FA1: Guerreiro ocupado → o sistema informa que o guerreiro já está em atividade.
- FA2: HP = 0 → o sistema bloqueia a entrada.
- FA3: Stamina < 25 → o sistema bloqueia e informa o custo.

**Pós-condições (vitória):** Recompensas creditadas; andar avançado; guerreiro aguarda decisão de continuar ou sair.
**Pós-condições (derrota):** HP = 0; buff perdido; guerreiro expulso da Torre.
**Regras de Negócio:**
- Custo de entrada: 25 stamina por andar tentado.
- Recompensa: bronze = andar × 40; XP = andar × 20.
- Checkpoint: na próxima entrada, o guerreiro começa do andar imediatamente posterior ao seu melhor andar já completado.
- Progressão de chefes: andares 1–3 (Esqueleto, Goblin, Rato Gigante), 4–6 (Aranha, Orc, Troll), 7–9 (Zumbi, Vampiro, Golem), 10–12 (Cavaleiro Negro, Arqueiro, Ogro), 13–15 (Xamã, Wyvern, Lich Menor), 16+ (Dragão, Titan, Lich Ancião, Guardiões Lendários).

---

## UC-19 — Continuar ou Sair da Torre Infernal

**Ator:** Jogador
**Pré-condições:** O guerreiro acabou de vencer um andar da Torre Infernal e está aguardando a decisão do jogador.
**Trigger:** O sistema apresenta ao jogador as opções "Continuar" ou "Sair" após a vitória em um andar.

**Fluxo Principal — Continuar:**
1. O jogador seleciona "Continuar".
2. O sistema verifica se o guerreiro ainda possui stamina suficiente (25) para o próximo andar.
3. O sistema debita mais 25 de stamina.
4. O sistema avança para o próximo andar e repete o fluxo de UC-18 a partir do passo 4.

**Fluxo Principal — Sair:**
1. O jogador seleciona "Sair".
2. O sistema registra o andar atual como checkpoint.
3. O sistema define `onMission=false` no guerreiro.
4. O sistema confirma a saída e exibe o melhor andar alcançado.

**Fluxo Alternativo:**
- FA1: Stamina insuficiente ao tentar continuar → o sistema informa que não há stamina suficiente e o guerreiro é automaticamente retirado da Torre (equivalente a sair).

**Pós-condições:** Se saiu: guerreiro disponível, checkpoint atualizado. Se continuou: próximo combate iniciado com custo de stamina já debitado.
**Regras de Negócio:**
- Cada andar adicional custa mais 25 de stamina.
- O melhor andar completado é registrado permanentemente e usado no ranking da Torre (ver UC-46).

---

## UC-20 — Iniciar Trabalho

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível; o guerreiro possui nível mínimo exigido pelo emprego; o guerreiro possui HP > 0.
**Trigger:** O jogador acessa a tela de Trabalho, seleciona um emprego e uma duração, e confirma.

**Fluxo Principal:**
1. O sistema exibe os empregos disponíveis com recompensa por hora, level mínimo do guerreiro e XP por hora.
2. O jogador seleciona o emprego desejado.
3. O sistema filtra apenas empregos cujo nível mínimo é igual ou inferior ao nível atual do guerreiro.
4. O jogador seleciona a duração (1 a 12 horas).
5. O sistema valida as condições (guerreiro disponível, nível suficiente).
6. O sistema cria a sessão de trabalho com timestamp de início, emprego e duração.
7. O sistema define `onMission=true` no guerreiro.
8. O sistema retorna confirmação com o timer de conclusão.

**Fluxo Alternativo:**
- FA1: Guerreiro em outra atividade → o sistema bloqueia e informa que o guerreiro está ocupado.
- FA2: Nível do guerreiro insuficiente para o emprego → o sistema não exibe o emprego ou retorna erro de restrição.

**Pós-condições:** Sessão de trabalho ativa; guerreiro ocupado; timer em andamento.
**Regras de Negócio:**
- Empregos disponíveis: Ajudante da Taverna (15 bronze/h, 3 XP/h, nível 1), Cuidador dos Estábulos (20 bronze/h, 4 XP/h, nível 1), Carregador de Mercadorias (30 bronze/h, 6 XP/h, nível 1), Ajudante do Ferreiro (45 bronze/h, 8 XP/h, nível 2), Guarda da Nobreza (65 bronze/h, 12 XP/h, nível 3), Mercenário Local (100 bronze/h, 18 XP/h, nível 5).
- **Nível mínimo refere-se ao nível do guerreiro**, não ao nível da profissão.
- Cada profissão possui nível e XP separados; a cada nível de profissão, o bônus de rendimento aumenta +5%.
- Duração selecionável: 1 a 12 horas.
- O trabalho não consome stamina.

---

## UC-21 — Coletar Recompensa de Trabalho

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há uma sessão de trabalho concluída.
**Trigger:** O timer do trabalho expirou e o jogador clica em "Coletar".

**Fluxo Principal:**
1. O sistema verifica que o timer de trabalho foi completado.
2. O sistema calcula o total de bronze e XP da profissão com base nas horas trabalhadas e no bônus de nível da profissão.
3. O sistema adiciona o bronze ao saldo do jogador via `player.addBronzeAmount()`.
4. O sistema adiciona o XP ao nível da profissão correspondente.
5. O sistema define `onMission=false` no guerreiro.
6. O sistema exibe o resumo da recompensa.

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante e bloqueia a coleta.

**Pós-condições:** Bronze e XP de profissão creditados; guerreiro disponível.
**Regras de Negócio:**
- O bônus de nível da profissão é de +5% por nível acima do primeiro.
- XP de profissão é separado do XP de guerreiro; o trabalho não concede XP ao guerreiro.

---

## UC-22 — Cancelar Trabalho

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há uma sessão de trabalho ativa ainda não concluída.
**Trigger:** O jogador acessa o trabalho em andamento e escolhe a opção de cancelar.

**Fluxo Principal:**
1. O sistema exibe o trabalho em andamento, horas completadas e o valor proporcional acumulado.
2. O jogador seleciona "Cancelar" e recebe as opções: "Cancelar com recompensa proporcional" ou "Abandonar (sem recompensa)".
3. O jogador escolhe cancelar com recompensa proporcional.
4. O sistema calcula o bronze proporcional às horas inteiras já completadas.
5. O sistema adiciona o bronze proporcional via `player.addBronzeAmount()`.
6. O sistema encerra a sessão e define `onMission=false` no guerreiro.

**Fluxo Alternativo:**
- FA1: Jogador escolhe abandonar sem recompensa → o sistema cancela a sessão sem conceder nada e libera o guerreiro imediatamente.

**Pós-condições:** Sessão de trabalho encerrada; guerreiro disponível; recompensa proporcional concedida (ou nenhuma, se abandonou).
**Regras de Negócio:**
- Cancelamento com recompensa: paga apenas pelas horas inteiras concluídas (horas fracionadas são perdidas).
- Abandono: nenhuma recompensa, guerreiro liberado imediatamente.

---

## UC-23 — Equipar Item do Inventário

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o item a ser equipado está no inventário e não está equipado; o slot correspondente está vazio ou tem outro item equipado.
**Trigger:** O jogador acessa o inventário, seleciona um item e clica em "Equipar".

**Fluxo Principal:**
1. O sistema exibe o inventário com os itens disponíveis e os slots de equipamento atuais.
2. O jogador seleciona um item e confirma "Equipar".
3. O sistema verifica se o slot correspondente ao tipo do item está vazio.
4. Se o slot estiver vazio: o sistema equipa o item, aplicando seus bônus ao guerreiro.
5. Se o slot já estiver ocupado: o sistema desequipa o item atual (enviando-o de volta ao inventário) e equipa o novo item.
6. O sistema retorna o estado atualizado do guerreiro com os novos stats.

**Fluxo Alternativo:**
- FA1: Item está marcado como protegido (guarded) → o sistema verifica se a proteção impede a troca (apenas impede queda em morte, não impede troca voluntária).

**Pós-condições:** Item equipado no slot correspondente; bônus do item aplicados ao guerreiro; item anterior (se houver) devolvido ao inventário.
**Regras de Negócio:**
- Slots disponíveis (10): Capacete, Armadura, Espada, Escudo, Calça, Bota, Luva, Ombreira, Colar, Anel.
- Sockets de joias encaixadas no item equipado contribuem com seus bônus permanentes enquanto o item estiver equipado.

---

## UC-24 — Desequipar Item

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o item está equipado em algum slot do guerreiro.
**Trigger:** O jogador acessa o painel de equipamentos, seleciona um item equipado e clica em "Desequipar".

**Fluxo Principal:**
1. O sistema exibe os itens atualmente equipados nos slots.
2. O jogador seleciona o item que deseja desequipar e confirma.
3. O sistema remove o item do slot de equipamento.
4. O sistema remove os bônus do item do guerreiro.
5. O sistema adiciona o item de volta ao inventário disponível.
6. O sistema retorna o estado atualizado do guerreiro.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Slot de equipamento vazio; item no inventário disponível; bônus do item removidos do guerreiro.
**Regras de Negócio:**
- Não há custo para desequipar um item.
- Joias encaixadas no item saem junto com o item; seus bônus são removidos quando o item é desequipado.

---

## UC-25 — Vender Item

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o item está no inventário (não equipado).
**Trigger:** O jogador seleciona um item no inventário e clica em "Vender".

**Fluxo Principal:**
1. O sistema exibe o valor de venda do item.
2. O jogador confirma a venda.
3. O sistema remove o item do inventário.
4. O sistema adiciona o bronze da venda ao saldo do jogador via `player.addBronzeAmount()`.
5. O sistema retorna o saldo atualizado e o inventário sem o item.

**Fluxo Alternativo:**
- FA1: Item está equipado → o sistema impede a venda e solicita que o item seja desequipado primeiro.
- FA2: Item está protegido (guarded) → o sistema avisa que o item está protegido e pede confirmação adicional para vender ou bloqueia a venda.

**Pós-condições:** Item removido do inventário; bronze creditado ao saldo do jogador.
**Regras de Negócio:**
- Apenas itens não equipados podem ser vendidos diretamente.
- O valor de venda é definido pelo sistema com base na raridade do item.
- A adição de bronze usa `player.addBronzeAmount()` para garantir normalização correta.

---

## UC-26 — Navegar na Loja

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado.
**Trigger:** O jogador acessa a seção "Loja" no jogo.

**Fluxo Principal:**
1. O sistema exibe os 10 itens disponíveis na rotação atual da loja.
2. O sistema exibe o nome do mercador atual (ex.: "Gareth, o Mercador Andarilho").
3. O sistema exibe um timer indicando quando a próxima rotação ocorrerá.
4. O sistema exibe para cada item: nome, raridade, bônus, preço e se já foi comprado pelo jogador nesta rotação.
5. O jogador navega pelos itens disponíveis.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Jogador visualiza a oferta atual sem alteração no estado do sistema.
**Regras de Negócio:**
- A loja rotaciona a cada 6 horas (mesma rotação para todos os jogadores simultaneamente).
- Rotação calculada por `rotationId = epochSeconds / 21600`.
- Distribuição de raridade: 60% Comum, 25% Incomum, 12% Raro, 3% Épico.
- Pool de itens: 57+ itens cobrindo todos os 10 slots de equipamento.

---

## UC-27 — Comprar Item na Loja

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o item desejado está disponível na rotação atual; o jogador não comprou esse item nesta rotação; o jogador possui bronze suficiente.
**Trigger:** O jogador seleciona um item na loja e clica em "Comprar".

**Fluxo Principal:**
1. O sistema verifica que o item não foi comprado pelo jogador nesta rotação.
2. O sistema verifica o saldo do jogador.
3. O sistema debita o bronze do preço do item.
4. O sistema adiciona o item ao inventário do jogador com lore e origem gerados automaticamente ("Adquirido no Comércio de Mercador Viajante.").
5. O sistema marca o item como comprado pelo jogador nesta rotação.
6. O sistema retorna o inventário atualizado e o saldo restante.

**Fluxo Alternativo:**
- FA1: Item já comprado nesta rotação → o sistema bloqueia a compra e informa que o item já foi adquirido.
- FA2: Saldo insuficiente → o sistema retorna erro de bronze insuficiente.

**Pós-condições:** Item no inventário do jogador; bronze debitado; compra registrada para a rotação atual.
**Regras de Negócio:**
- Cada jogador pode comprar cada item da loja apenas uma vez por rotação de 6 horas.
- Na próxima rotação, todos os itens voltam a estar disponíveis para compra.
- Itens comprados na loja recebem origem "Adquirido no Comércio de Mercador Viajante."

---

## UC-28 — Iniciar Sessão de Pesca

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível (`onMission=false`).
**Trigger:** O jogador acessa a seção de Habilidades, seleciona Pesca, escolhe uma duração e confirma.

**Fluxo Principal:**
1. O sistema exibe as opções de duração para pesca: 5, 10, 20, 30 ou 40 minutos.
2. O jogador seleciona a duração desejada e confirma.
3. O sistema valida que o guerreiro está disponível.
4. O sistema cria a sessão de pesca com timestamp de início e duração.
5. O sistema define `onMission=true` no guerreiro.
6. O sistema retorna confirmação com o timer de conclusão.

**Fluxo Alternativo:**
- FA1: Guerreiro em outra atividade → o sistema bloqueia o início.

**Pós-condições:** Sessão de pesca ativa; guerreiro ocupado; timer em andamento.
**Regras de Negócio:**
- A pesca não consome stamina.
- Durações disponíveis: 5, 10, 20, 30, 40 minutos.
- O multiplicador de XP e recursos pode variar conforme a zona em que o guerreiro está.
- Pesca produz: Peixe Pequeno, Salmão, Atum, Tubarão, Peixe Lendário.

---

## UC-29 — Coletar Resultado de Pesca

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há sessão de pesca concluída.
**Trigger:** O timer da sessão de pesca expirou e o jogador clica em "Coletar".

**Fluxo Principal:**
1. O sistema verifica que o timer de pesca foi completado.
2. O sistema calcula a quantidade e os tipos de peixes obtidos com base na duração, nível de Pesca e zona.
3. O sistema adiciona os peixes ao inventário de recursos do jogador.
4. O sistema concede XP de Pesca ao jogador.
5. O sistema define `onMission=false` no guerreiro.
6. O sistema exibe o resumo da coleta.

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante.

**Pós-condições:** Peixes adicionados ao inventário de recursos; XP de Pesca creditado; guerreiro disponível.
**Regras de Negócio:**
- XP necessário para subir de nível na habilidade: `level atual × 100`.
- O multiplicador de recursos e XP é aplicado conforme a zona (Segura ×1,0; PvP ×1,5; Alto Risco ×2,5).

---

## UC-30 — Cancelar Sessão de Pesca

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há sessão de pesca ativa ainda não concluída.
**Trigger:** O jogador acessa a sessão de pesca em andamento e opta por cancelar.

**Fluxo Principal:**
1. O sistema exibe a sessão em andamento com o tempo decorrido.
2. O jogador confirma o cancelamento.
3. O sistema encerra a sessão sem conceder recursos ou XP.
4. O sistema define `onMission=false` no guerreiro.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Sessão cancelada; guerreiro disponível; nenhum recurso ou XP concedido.
**Regras de Negócio:**
- O cancelamento não concede recompensa parcial.

---

## UC-31 — Consumir Peixe para Restaurar Stamina

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui pelo menos um peixe no inventário de recursos; o guerreiro possui stamina abaixo de 100%.
**Trigger:** O jogador acessa o inventário de recursos, seleciona um peixe e clica em "Usar".

**Fluxo Principal:**
1. O sistema exibe os peixes disponíveis no inventário de recursos com o bônus de stamina de cada tipo.
2. O jogador seleciona o peixe que deseja consumir e confirma.
3. O sistema remove 1 unidade do peixe do inventário de recursos.
4. O sistema adiciona o bônus de stamina correspondente ao guerreiro (limitado a 100%).
5. O sistema retorna a stamina atualizada.

**Fluxo Alternativo:**
- FA1: Stamina já em 100% → o sistema informa que a stamina está cheia e bloqueia o consumo (ou permite mas sem efeito).

**Pós-condições:** Stamina do guerreiro aumentada; peixe removido do inventário de recursos.
**Regras de Negócio:**
- Restauração de stamina por tipo de peixe: Peixe Pequeno (+10%), Salmão (+25%), Atum (+40%), Tubarão (+60%), Peixe Lendário (+80%).
- A stamina não pode ultrapassar 100%; o excedente é perdido.

---

## UC-32 — Iniciar Sessão de Mineração

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível (`onMission=false`).
**Trigger:** O jogador acessa Habilidades, seleciona Mineração, escolhe duração e confirma.

**Fluxo Principal:**
1. O sistema exibe as opções de duração para mineração: 10, 20, 30, 45 ou 60 minutos.
2. O jogador seleciona a duração e confirma.
3. O sistema valida que o guerreiro está disponível.
4. O sistema cria a sessão de mineração com timestamp de início e duração.
5. O sistema define `onMission=true` no guerreiro.
6. O sistema retorna confirmação com o timer de conclusão.

**Fluxo Alternativo:**
- FA1: Guerreiro em outra atividade → o sistema bloqueia o início.

**Pós-condições:** Sessão de mineração ativa; guerreiro ocupado; timer em andamento.
**Regras de Negócio:**
- A mineração não consome stamina.
- Durações disponíveis: 10, 20, 30, 45, 60 minutos.
- Minérios produzidos: Cobre, Ferro, Prata, Ouro, Mithril.
- Há chance de obter fragmentos de joias dependendo do tipo de minério.

---

## UC-33 — Coletar Resultado de Mineração

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há sessão de mineração concluída.
**Trigger:** O timer da sessão de mineração expirou e o jogador clica em "Coletar".

**Fluxo Principal:**
1. O sistema verifica que o timer de mineração foi completado.
2. O sistema calcula os minérios obtidos com base na duração, nível de Mineração e zona.
3. O sistema verifica a chance de fragmentos de joias para cada minério coletado.
4. O sistema adiciona minérios e eventuais fragmentos ao inventário de recursos.
5. O sistema concede XP de Mineração ao jogador.
6. O sistema define `onMission=false` no guerreiro.
7. O sistema exibe o resumo da coleta.

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante.

**Pós-condições:** Minérios e fragmentos adicionados ao inventário; XP de Mineração creditado; guerreiro disponível.
**Regras de Negócio:**
- XP necessário para subir nível: `level atual × 100`.
- Fragmentos de joias têm chance variável por tipo de minério.
- O multiplicador de recursos e XP é aplicado conforme a zona.

---

## UC-34 — Cancelar Sessão de Mineração

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há sessão de mineração ativa ainda não concluída.
**Trigger:** O jogador acessa a sessão de mineração em andamento e opta por cancelar.

**Fluxo Principal:**
1. O sistema exibe a sessão em andamento.
2. O jogador confirma o cancelamento.
3. O sistema encerra a sessão sem conceder minérios ou XP.
4. O sistema define `onMission=false` no guerreiro.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Sessão cancelada; guerreiro disponível; nenhum recurso ou XP concedido.
**Regras de Negócio:**
- Cancelamento não concede recompensa parcial.

---

## UC-35 — Refinar Minérios na Forja

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui ao menos 5 unidades do minério a ser refinado; o jogador possui bronze suficiente para o custo de refinamento.
**Trigger:** O jogador acessa a Forja, seleciona "Refinar" e escolhe o tipo e quantidade de minério.

**Fluxo Principal:**
1. O sistema exibe os tipos de minério disponíveis no inventário de recursos com a quantidade suficiente para refinar (mínimo 5 por barra).
2. O jogador seleciona o tipo de minério e a quantidade de barras que deseja produzir.
3. O sistema calcula o custo em bronze (escala com o nível do minério).
4. O sistema verifica saldo e quantidade de minério suficientes.
5. O sistema debita o bronze e os minérios do inventário.
6. O sistema adiciona as barras correspondentes ao inventário de recursos.
7. O sistema concede XP de Smithing.
8. O sistema retorna o inventário atualizado.

**Fluxo Alternativo:**
- FA1: Minérios insuficientes (menos de 5) → o sistema bloqueia e informa a quantidade necessária.
- FA2: Bronze insuficiente → o sistema bloqueia e informa o custo.

**Pós-condições:** Minérios consumidos; barras adicionadas ao inventário; bronze debitado; XP de Smithing creditado.
**Regras de Negócio:**
- Taxa de conversão: 5 minérios + bronze = 1 barra.
- O custo em bronze escala com o nível/tier do minério.
- Tipos de barra: Cobre, Ferro, Prata, Ouro, Mithril (correspondentes aos minérios).

---

## UC-36 — Craftar Equipamento na Forja

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui barras suficientes do tipo necessário; o jogador possui o nível de Smithing necessário para a receita.
**Trigger:** O jogador acessa a Forja, seleciona "Craftar Equipamento" e escolhe a receita desejada.

**Fluxo Principal:**
1. O sistema exibe as receitas de equipamento disponíveis com os requisitos (tipo de barra, quantidade, nível de Smithing mínimo).
2. O jogador seleciona a receita que deseja executar.
3. O sistema valida barras suficientes e nível de Smithing.
4. O sistema consome as barras do inventário de recursos.
5. O sistema gera o equipamento com 1 a 2 sockets garantidos.
6. O sistema gera lore e origem automaticamente ("Forjado pelo próprio guerreiro.").
7. O sistema adiciona o item ao inventário de equipamentos.
8. O sistema concede XP de Smithing.
9. O sistema retorna o inventário atualizado.

**Fluxo Alternativo:**
- FA1: Barras insuficientes → o sistema bloqueia e informa a quantidade necessária.
- FA2: Nível de Smithing insuficiente → o sistema bloqueia e informa o nível necessário.

**Pós-condições:** Barras consumidas; equipamento adicionado ao inventário com sockets garantidos; XP de Smithing creditado.
**Regras de Negócio:**
- Itens craftados sempre possuem 1 a 2 sockets (diferente de itens dropados ou comprados que podem ter 0 sockets).
- O número de sockets varia conforme a raridade do item craftado.
- Origem do item: "Forjado pelo próprio guerreiro."

---

## UC-37 — Craftar Joia na Forja

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui ao menos 3 fragmentos do mesmo tipo de joia.
**Trigger:** O jogador acessa a Forja, seleciona "Craftar Joia" e escolhe o tipo de fragmento.

**Fluxo Principal:**
1. O sistema exibe os tipos de fragmento disponíveis em quantidade suficiente (mínimo 3 do mesmo tipo).
2. O jogador seleciona o tipo de fragmento e confirma.
3. O sistema consome 3 fragmentos do tipo selecionado.
4. O sistema gera 1 joia correspondente ao tipo de fragmento.
5. O sistema adiciona a joia ao inventário de recursos.
6. O sistema concede XP de Smithing.
7. O sistema retorna o inventário atualizado.

**Fluxo Alternativo:**
- FA1: Menos de 3 fragmentos do tipo → o sistema bloqueia e informa a quantidade necessária.

**Pós-condições:** 3 fragmentos consumidos; 1 joia adicionada ao inventário de recursos; XP de Smithing creditado.
**Regras de Negócio:**
- Receita: 3 fragmentos do mesmo tipo → 1 joia.
- Tipos de joia e efeitos: Rubi (+5 ATK), Safira (+5 DEF), Esmeralda (+20 HP), Diamante (+3 ATK/+3 DEF/+10 HP), Ametista (+5% drop chance).

---

## UC-38 — Encaixar Joia em Socket de Item

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui ao menos uma joia no inventário de recursos; o item alvo possui pelo menos um socket vazio.
**Trigger:** O jogador acessa o inventário, seleciona um item com socket vazio e escolhe encaixar uma joia.

**Fluxo Principal:**
1. O sistema exibe os itens do inventário que possuem sockets vazios.
2. O jogador seleciona o item e em seguida a joia que deseja encaixar.
3. O sistema valida que o item possui socket disponível e que a joia está no inventário.
4. O sistema remove a joia do inventário de recursos.
5. O sistema encaixa a joia no socket do item e aplica o bônus permanentemente enquanto o item estiver equipado.
6. O sistema retorna o item atualizado com o socket preenchido.

**Fluxo Alternativo:**
- FA1: Item sem sockets vazios → o sistema informa que o item não possui slots disponíveis.
- FA2: Joia não disponível no inventário → o sistema bloqueia a ação.

**Pós-condições:** Joia encaixada no item; bônus da joia ativo (se item estiver equipado); joia removida do inventário de recursos.
**Regras de Negócio:**
- Sockets por raridade: itens Comuns/Incomuns/Raros/Épicos podem ter 0–3 sockets conforme raridade.
- Itens craftados possuem 1–2 sockets garantidos.
- Uma vez encaixada, a joia não pode ser removida sem destruir o item ou usar um recurso especial (se implementado no futuro).
- Os bônus da joia se aplicam apenas enquanto o item estiver equipado.

---

## UC-39 — Entrar em Zona como Coletor (Gatherer)

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível; o guerreiro atende ao nível mínimo da zona desejada; o guerreiro possui HP > 0.
**Trigger:** O jogador acessa Zonas, seleciona uma zona, escolhe habilidade de coleta (pesca ou mineração) e duração, e confirma como Gatherer.

**Fluxo Principal:**
1. O sistema exibe as zonas disponíveis com nível mínimo, multiplicador de recursos, taxa de ataque de NPC e taxa de ataque PvP.
2. O jogador seleciona a zona desejada.
3. O sistema verifica o nível mínimo do guerreiro para a zona.
4. O jogador seleciona a habilidade de coleta (pesca ou mineração) e a duração (30 min a 12 horas).
5. O sistema cria a sessão de expedição como Gatherer.
6. O sistema define `onMission=true` no guerreiro.
7. O sistema informa as taxas de risco (NPC e Hunter PvP) da zona escolhida.

**Fluxo Alternativo:**
- FA1: Nível do guerreiro abaixo do mínimo da zona → o sistema bloqueia e indica o nível mínimo necessário.
- FA2: Guerreiro em outra atividade → o sistema bloqueia.

**Pós-condições:** Expedição ativa como Gatherer; guerreiro ocupado; timer em andamento; sujeito a ataques de NPCs e Hunters durante a expedição.
**Regras de Negócio:**
- Zonas: Segura (nível 1, ×1,0, 15% NPC/h, 0% PvP), PvP (nível 10, ×1,5, 25% NPC/h, 20% PvP/h), Alto Risco (nível 20, ×2,5, 35% NPC/h, 40% PvP/h).
- Entrada em zona como Gatherer é gratuita em termos de stamina.
- Durante a expedição, NPCs e Hunters podem atacar automaticamente com base nas taxas da zona.
- Derrota durante a expedição: HP = 0, stamina = 0, buff perdido, perde 15% do bronze; em Alto Risco, 10% de chance de perder 1 item equipado não protegido.

---

## UC-40 — Entrar em Zona como Caçador (Hunter)

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro está disponível; o guerreiro atende ao nível mínimo da zona; o guerreiro possui HP > 0; a zona deve suportar PvP (Zona PvP ou Alto Risco).
**Trigger:** O jogador acessa Zonas, seleciona uma zona com PvP habilitado, escolhe duração de patrulha e entra como Hunter.

**Fluxo Principal:**
1. O sistema exibe as zonas disponíveis com suporte a Hunter (Zona PvP e Alto Risco).
2. O jogador seleciona a zona e a duração da patrulha (1 a 6 horas).
3. O sistema verifica o nível do guerreiro para a zona.
4. O sistema cria a sessão de Hunter na zona.
5. O sistema define `onMission=true` no guerreiro.
6. Durante a patrulha, o sistema casa automaticamente o Hunter com Gatherers ativos na mesma zona.
7. O BattleSimulator resolve os combates automaticamente contra Gatherers encontrados.

**Fluxo Alternativo:**
- FA1: Zona Segura selecionada → o sistema informa que a Zona Segura não permite combate PvP entre jogadores.
- FA2: Guerreiro em atividade → o sistema bloqueia.
- FA3: Nível insuficiente para a zona → o sistema bloqueia.

**Pós-condições:** Sessão de Hunter ativa; guerreiro patrulhando; combates resolvidos automaticamente contra Gatherers encontrados.
**Regras de Negócio:**
- Vitória como Hunter: rouba 15% do bronze do Gatherer derrotado, recebendo 50% do valor roubado.
- Derrota como Hunter: stamina = 0, cooldown aplicado.
- O Hunter pode enfrentar múltiplos Gatherers durante a patrulha.
- Duração da patrulha: 1 a 6 horas.

---

## UC-41 — Coletar Resultado de Expedição em Zona

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; há sessão de expedição (Gatherer ou Hunter) concluída.
**Trigger:** O timer da expedição expirou e o jogador clica em "Coletar".

**Fluxo Principal — Gatherer:**
1. O sistema verifica que o timer de expedição foi completado.
2. O sistema calcula os recursos coletados (pesca ou mineração) com o multiplicador da zona e nível da habilidade.
3. O sistema adiciona os recursos ao inventário do jogador.
4. O sistema concede XP da habilidade correspondente.
5. O sistema define `onMission=false` no guerreiro.
6. O sistema exibe o resumo: recursos coletados, XP ganho e eventuais combates sofridos durante a expedição.

**Fluxo Principal — Hunter:**
1. O sistema verifica que o timer de patrulha foi completado.
2. O sistema exibe o resultado dos combates realizados durante a patrulha.
3. Se houve vitórias: exibe o bronze roubado dos Gatherers.
4. O sistema define `onMission=false` no guerreiro.
5. O sistema exibe o resumo total da patrulha.

**Fluxo Alternativo:**
- FA1: Timer ainda não concluído → o sistema informa o tempo restante.

**Pós-condições:** Recursos ou bronze creditados conforme o papel; XP creditado (Gatherer); guerreiro disponível.
**Regras de Negócio:**
- Gatherer derrotado durante a expedição perde 15% do bronze acumulado até o momento do ataque.
- Em Zona de Alto Risco, derrota pode resultar em perda de 1 item equipado não protegido (10% de chance).
- O multiplicador de recursos da zona é aplicado sobre a produção base da habilidade.

---

## UC-42 — Curar Guerreiro no Templo

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro possui HP abaixo de 100%.
**Trigger:** O jogador acessa o Templo e seleciona "Curar".

**Fluxo Principal:**
1. O sistema exibe o HP atual do guerreiro e o custo de cura.
2. O jogador confirma a cura.
3. O sistema verifica o saldo do jogador (se aplicável).
4. O sistema debita o custo (se nível > 10).
5. O sistema restaura o HP do guerreiro para 100% instantaneamente.
6. O sistema retorna o estado atualizado do guerreiro.

**Fluxo Alternativo:**
- FA1: Guerreiro já com HP = 100% → o sistema informa que o guerreiro está com saúde plena e não há necessidade de cura.
- FA2: Saldo insuficiente (nível > 10) → o sistema bloqueia e informa o custo necessário.

**Pós-condições:** HP do guerreiro restaurado para 100%; bronze debitado (se aplicável).
**Regras de Negócio:**
- Cura é gratuita para guerreiros de nível 1 a 10.
- Cura custa 1 prata (100 bronze) para guerreiros de nível 11 em diante.
- A cura é instantânea, sem timer.

---

## UC-43 — Aplicar Bênção (Buff) no Templo

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o guerreiro não possui buff ativo; o jogador possui bronze suficiente.
**Trigger:** O jogador acessa o Templo e seleciona uma bênção para aplicar.

*(Ver detalhamento completo em UC-07 — este caso de uso é equivalente.)*

**Fluxo Principal:**
1. O sistema exibe as bênçãos disponíveis com efeito e custo.
2. O jogador seleciona a bênção e confirma.
3. O sistema verifica a ausência de buff ativo e o saldo disponível.
4. O sistema debita o bronze e aplica o buff por 1 hora.
5. O sistema retorna o estado atualizado do guerreiro.

**Fluxo Alternativo:**
- FA1: Buff ativo existente → o sistema informa que apenas um buff por vez é permitido.
- FA2: Saldo insuficiente → o sistema retorna erro.

**Pós-condições:** Buff ativo no guerreiro por 1 hora; bronze debitado.
**Regras de Negócio:** (Iguais ao UC-07)
- Força (+5 ATK) = 30 bronze; Agilidade (+5% evasão) = 30 bronze; Defesa (+5 DEF) = 30 bronze; Vitalidade (+20 HP) = 30 bronze; Sorte (+5% drop) = 50 bronze.
- Buff perdido em caso de derrota em combate.
- Um buff por vez, duração 1 hora.

---

## UC-44 — Proteger Item no Templo

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o jogador possui um item no inventário que deseja proteger; o total de itens protegidos é inferior a 3; o jogador possui ao menos 50 bronze.
**Trigger:** O jogador acessa o Templo, seleciona "Proteção de Itens" e escolhe um item para proteger.

**Fluxo Principal:**
1. O sistema exibe os itens do inventário disponíveis para proteção e quantos slots de proteção restam (máximo 3).
2. O jogador seleciona o item que deseja proteger e confirma.
3. O sistema verifica o limite de itens protegidos (máximo 3) e o saldo do jogador.
4. O sistema debita 50 bronze.
5. O sistema marca o item como protegido (guarded = true).
6. O sistema retorna o inventário atualizado.

**Fluxo Alternativo:**
- FA1: Limite de 3 itens protegidos já atingido → o sistema informa que o máximo foi alcançado e que é necessário remover a proteção de um item antes de adicionar outro.
- FA2: Saldo insuficiente → o sistema retorna erro de bronze insuficiente.
- FA3: Item já está protegido → o sistema informa e bloqueia duplicação.

**Pós-condições:** Item marcado como protegido; 50 bronze debitados.
**Regras de Negócio:**
- Custo: 50 bronze por item.
- Limite: máximo de 3 itens protegidos simultaneamente por jogador.
- A proteção é permanente até ser removida manualmente (ver UC-45).
- Itens protegidos NÃO são perdidos em caso de derrota na Zona de Alto Risco (onde existe chance de perda de item equipado).

---

## UC-45 — Remover Proteção de Item

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado; o item possui proteção ativa (guarded = true).
**Trigger:** O jogador acessa o Templo, seleciona a gestão de proteções e remove a proteção de um item.

**Fluxo Principal:**
1. O sistema exibe os itens atualmente protegidos (até 3).
2. O jogador seleciona o item cujo efeito de proteção deseja remover e confirma.
3. O sistema remove a marcação de protegido do item (guarded = false).
4. O sistema libera o slot de proteção.
5. O sistema retorna o inventário atualizado.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Item sem proteção; slot de proteção liberado para uso futuro.
**Regras de Negócio:**
- A remoção da proteção não reembolsa o bronze pago.
- Após a remoção, o item fica sujeito à perda em combate na Zona de Alto Risco.

---

## UC-46 — Consultar Ranking da Torre Infernal

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado.
**Trigger:** O jogador acessa a seção de Ranking da Torre Infernal.

**Fluxo Principal:**
1. O sistema exibe a lista dos melhores andares alcançados por todos os jogadores, ordenada do maior para o menor andar completado.
2. O sistema exibe o nome do guerreiro (não o username de login) para cada posição.
3. O sistema destaca a posição do guerreiro do jogador logado (se estiver no top).
4. O jogador visualiza a tabela.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Nenhuma alteração no estado do sistema; o jogador visualiza o ranking.
**Regras de Negócio:**
- O ranking é baseado no melhor andar já completado pelo guerreiro (histórico permanente; não é resetado).
- O ranking exibe o nome do guerreiro, não o username de login.
- Empates são ordenados por critério secundário (ex.: data de conquista).

---

## UC-47 — Consultar Ranking da Arena

**Ator:** Jogador
**Pré-condições:** O jogador está autenticado.
**Trigger:** O jogador acessa a seção de Ranking da Arena.

**Fluxo Principal:**
1. O sistema exibe o top 20 jogadores ordenados por rank points (do maior para o menor).
2. O sistema exibe o nome do guerreiro e os rank points de cada jogador.
3. O sistema destaca a posição do guerreiro do jogador logado (se estiver no top 20).
4. O jogador visualiza o ranking.

**Fluxo Alternativo:**
- Não aplicável.

**Pós-condições:** Nenhuma alteração no estado do sistema; o jogador visualiza o ranking.
**Regras de Negócio:**
- O ranking exibe apenas o top 20 jogadores.
- Rank points: vitória na arena = +25; derrota = -15.
- Rank points podem ser negativos; mínimo não definido explicitamente.
- O ranking exibe o nome do guerreiro, não o username de login.
- Atualizado em tempo real a cada batalha de arena coletada.

---

---

## Sistema de Guildas

### UC-48: Criar Guilda
**Ator:** Jogador sem guilda  
**Pré-condições:** Jogador autenticado, sem guilda, com ≥ 100 bronze.

**Fluxo Principal:**
1. Jogador informa nome (3-30 chars) e descrição opcional.
2. Sistema valida nome único e saldo.
3. Sistema debita 100 bronze, cria a guilda e define o jogador como líder.
4. Sistema retorna dados da guilda com `isLeader: true`.

**Fluxo Alternativo:**
- Nome duplicado ou saldo insuficiente → 400 com mensagem de erro.

**Pós-condições:** Guilda criada, jogador associado como líder.  
**Regras:** Nome único; custo 100 bronze; criador é o líder.

---

### UC-49: Entrar em Guilda
**Ator:** Jogador sem guilda  
**Pré-condições:** Jogador sem guilda; guilda com vagas disponíveis.

**Fluxo Principal:**
1. Jogador visualiza lista de guildas.
2. Jogador clica em "Entrar" em uma guilda com vaga.
3. Sistema associa o jogador à guilda.

**Fluxo Alternativo:**
- Jogador já em guilda ou guilda cheia → 400.

**Pós-condições:** Jogador é membro da guilda.

---

### UC-50: Sair da Guilda
**Ator:** Membro (não-líder)  
**Pré-condições:** Jogador é membro de uma guilda e não é o líder.

**Fluxo Principal:**
1. Jogador clica em "Sair da Guilda".
2. Sistema remove a associação.

**Fluxo Alternativo:**
- Líder com outros membros → 400 (deve transferir ou dissolver).
- Líder único → dissolve automaticamente.

**Pós-condições:** Jogador sem guilda.

---

### UC-51: Expulsar Membro (Líder)
**Ator:** Líder da guilda  
**Pré-condições:** Jogador é líder; alvo é membro da mesma guilda.

**Fluxo Principal:**
1. Líder clica em "Expulsar" ao lado de um membro.
2. Sistema confirma e remove o membro.

**Fluxo Alternativo:**
- Tentar expulsar a si mesmo → 400.

**Pós-condições:** Membro removido da guilda.

---

### UC-52: Transferir Liderança
**Ator:** Líder da guilda  
**Pré-condições:** Alvo é membro da mesma guilda.

**Fluxo Principal:**
1. Líder seleciona membro e clica em "Transferir Liderança".
2. Sistema atualiza `leaderId` da guilda.

**Pós-condições:** Novo líder definido; líder anterior vira membro comum.

---

### UC-53: Doar Bronze para Guilda
**Ator:** Membro da guilda  
**Pré-condições:** Jogador em guilda, com bronze suficiente.

**Fluxo Principal:**
1. Membro informa quantidade de bronze.
2. Sistema debita do jogador e adiciona ao gold da guilda.

**Pós-condições:** Gold da guilda aumentado; saldo do jogador reduzido.

---

### UC-54: Subir Nível da Guilda (Líder)
**Ator:** Líder da guilda  
**Pré-condições:** Gold da guilda ≥ `level × 1000`.

**Fluxo Principal:**
1. Líder clica em "Subir Nível".
2. Sistema debita gold da guilda e incrementa o nível.
3. Capacidade máxima de membros aumenta em 5.

**Pós-condições:** Nível da guilda incrementado.

---

### UC-55: Dissolver Guilda (Líder)
**Ator:** Líder da guilda  
**Pré-condições:** Jogador é líder.

**Fluxo Principal:**
1. Líder confirma dissolução.
2. Sistema remove todos os membros e apaga a guilda.

**Pós-condições:** Guilda inexistente; todos os membros sem guilda.

---

---

### UC-56: Receber Bônus Passivo da Guilda em Quest
**Ator:** Membro de guilda nível ≥ 2  
**Pré-condições:** Jogador em guilda com level ≥ 2; coleta uma quest concluída.

**Fluxo Principal:**
1. Jogador coleta recompensa de quest.
2. Sistema calcula `xpBonus = min(20, (guildLevel-1)×5)%`.
3. Sistema calcula `bronzeBonus = min(10, max(0, guildLevel-3)×5)%`.
4. Sistema calcula `dropBonus = min(7, max(0, guildLevel-2)×2)%`.
5. XP, bronze e drop chance são multiplicados pelos bônus antes de serem atribuídos.
6. Resposta inclui `guildBonusXp`, `guildBonusBronze`, `guildBonusDrop` para exibição.

**Fluxo Alternativo:**
- Guilda nível 1 → bônus = 0%, comportamento idêntico a sem guilda.

**Pós-condições:** Jogador recebe XP e bronze com bônus aplicado.  
**Regras:** Bônus são caps: XP 20%, drop 7%, bronze 10%. Aplicado em quests e trabalho.

---

### UC-57: Receber Bônus Passivo da Guilda em Trabalho
**Ator:** Membro de guilda nível ≥ 2  
**Pré-condições:** Jogador em guilda; coleta trabalho concluído.

**Fluxo Principal:**
1. Jogador coleta recompensa de trabalho.
2. Sistema aplica `xpBonus` e `bronzeBonus` da guilda sobre XP e gold ganhos.
3. Recompensa final = base × (1 + bonus%).

**Pós-condições:** Jogador recebe mais XP e bronze que o base.

---

---

### UC-58: Visualizar Ranking de Doações da Guilda
**Ator:** Membro da guilda  
**Pré-condições:** Jogador pertence a uma guilda.

**Fluxo Principal:**
1. Jogador abre a aba Guilda.
2. Sistema exibe painel da guilda com ranking de doações ao final.
3. Cada membro aparece com seu nome e total doado (bronze/prata/ouro).
4. Lista ordenada do maior para o menor doador.

**Fluxo Alternativo:**
- Nenhum membro doou ainda → exibe "No donations yet."

**Pós-condições:** Somente leitura; nenhum estado alterado.  
**Regras:** `guildDonatedBronze` é zerado ao entrar, sair ou guilda ser dissolvida.

---

---

## Guerra de Territórios

### UC-59: Visualizar Status dos Territórios
**Ator:** Qualquer jogador autenticado

**Fluxo:**
1. Jogador acessa aba Territórios.
2. Sistema exibe os 3 territórios com: guilda dominante (ou "Neutro"), defenseStreak, bônus ativos e tempo até próxima batalha.

**Pós-condições:** Apenas leitura.

---

### UC-60: Declarar Ataque a Território
**Ator:** Líder de guilda sem território

**Pré-condições:** Jogador é líder; guilda não controla nenhum território; nenhuma declaração ativa para esse ciclo.

**Fluxo:**
1. Líder seleciona território alvo e confirma ataque.
2. Sistema valida: guilda sem território, território disponível para ataque, não há declaração duplicada.
3. Sistema registra `TerritoryDeclaration` com status PENDING.
4. Na próxima janela de 6h, a batalha é resolvida automaticamente.

**Fluxo Alternativo:**
- Guilda já controla território → 400 (deve defender).
- Declaração duplicada → 400.

---

### UC-61: Resolução Automática de Batalha de Território
**Ator:** Sistema (scheduler a cada 6h)

**Fluxo:**
1. Scheduler dispara em 00h, 06h, 12h ou 18h UTC.
2. Para cada território com declarações pendentes:
   a. Compila membros defensores (HP > 0) e aplicar debuff se streak > 0.
   b. Para cada atacante (em ordem de declaração):
      - Resolve Guild Brawl via BattleSimulator.
      - Se há outro atacante: defensores recuperam HP ao estado pré-batalha.
      - Se é o último atacante: defensores não recuperam HP.
   c. Se todos os atacantes foram derrotados: defenseStreak +1, territoryControl atualizado.
   d. Se algum atacante venceu: TerritoryControl atualizado para a guilda vencedora, streak = 0.
3. Para território neutro: gera NPCs, resolve batalha, vencedor domina.
4. Registra TerritoryBattleLog para cada batalha.
5. Notifica resultado (exibido no painel ao abrir a aba).

---

### UC-62: Guild Brawl (Mecânica de Batalha)
**Ator:** Sistema (disparado pela resolução de UC-61)

**Fluxo:**
1. Coleta membros de cada lado com HP > 0.
2. Aplica debuff percentual (se aplicável) nos stats dos defensores.
3. Sorteia pares aleatórios.
4. Resolve cada par 1v1 via BattleSimulator; vencedor entra na próxima briga (2v1).
5. Continua até um lado ser eliminado.
6. Atualiza HP de todos os guerreiros participantes no banco.
7. Retorna lado vencedor e log consolidado.

---

### UC-63: Receber Bônus de Território
**Ator:** Membro de guilda dominante

**Pré-condições:** Guilda do jogador domina um território.

**Fluxo:**
1. Jogador coleta recompensa de quest, trabalho ou coleta.
2. Sistema verifica se a guilda do jogador controla algum território.
3. Aplica: +10% XP, +10% bronze (base), mais bônus exclusivo do território.
4. Bônus acumulam com os bônus de nível de guilda.

---

### UC-64: Cancelar Declaração de Ataque
**Ator:** Líder de guilda

**Pré-condições:** Existe declaração PENDING para o ciclo atual.

**Fluxo:**
1. Líder cancela a declaração.
2. Sistema muda status para CANCELLED.
3. Guilda não participa da próxima batalha.

---

---

## Sistema de Correio

### UC-65: Send Letter
**Actor:** Authenticated player
**Pre-conditions:** Player has ≥ 1 gold (fee) + gold amount to attach.

**Flow:**
1. Player types recipient username, message (≤ 500 chars), and optional gold amount.
2. System validates: recipient exists, not self, sufficient funds.
3. System deducts 1 gold (fee) + goldAmount from sender.
4. System saves Mail record with status unread/uncollected.
5. System returns success message.

**Alternate flow:**
- Recipient not found → 400.
- Insufficient funds → 400.
- Self-send → 400.

**Post-conditions:** Mail record created; sender's gold reduced.

---

### UC-66: Read Inbox
**Actor:** Authenticated player

**Flow:**
1. Player opens mail tab.
2. System returns list of received letters sorted by sentAt desc.
3. Player opens a letter → marked as read.

**Post-conditions:** Letter.readAt set.

---

### UC-67: Collect Gold from Letter
**Actor:** Authenticated player
**Pre-conditions:** Letter has goldAmount > 0 and collectedAt == null.

**Flow:**
1. Player clicks "Collect gold" on a letter.
2. System transfers goldAmount to recipient's balance.
3. System sets letter.collectedAt.

**Alternate flow:**
- Already collected → 400.
- Letter belongs to other player → 400.

**Post-conditions:** Gold transferred; letter marked collected.

---

### UC-68: Delete Letter
**Actor:** Authenticated player (recipient)

**Flow:**
1. Player deletes a letter from inbox.
2. System removes the Mail record.

**Pre-conditions:** Player is the recipient of the letter.

---

---

## World Tab — 3 Reinos 🚧 Planejado

### UC-69: View World — Kingdom Overview
**Actor:** Authenticated player

**Flow:**
1. Player opens World tab.
2. System displays 3 kingdom cards (Desfiladeiro, Minas, Fortaleza) with: controlling guild, player's guild bonus (if any), next war timer.
3. Player selects a kingdom.

---

### UC-70: Enter Kingdom — View Kingdom Detail
**Actor:** Any player

**Flow:**
1. Player clicks a kingdom card.
2. System displays the kingdom detail view with available zones based on player level.
3. Zones above player's level are locked with lock icon.

---

### UC-71: Start Kingdom Quest
**Actor:** Player with available warrior (not on mission)
**Pre-conditions:** Warrior not busy, sufficient stamina.

**Flow:**
1. Player opens kingdom tavern tab within the kingdom view.
2. System lists quests specific to that kingdom.
3. Player selects quest → warrior goes on mission (same timer mechanic as current quests).

**Alternate:**
- Warrior busy or insufficient stamina → 400.

---

### UC-72: Gather Resources in Kingdom Zone
**Actor:** Player (lv requirement met for chosen zone)

**Flow:**
1. Player selects a zone within the kingdom (e.g., Porto Seguro for fishing).
2. Chooses duration (fishing: 5-40min; mining: 10-60min).
3. Warrior starts gathering session. Timer runs.
4. Player collects on completion.

**PvP zones (lv10+):** player may be attacked by hunters while gathering.

---

### UC-73: Train at Fortaleza (Combat Kingdom)
**Actor:** Player in Fortaleza Maldita
**Pre-conditions:** Warrior available, sufficient bronze.

**Flow:**
1. Player opens Arena de Treino.
2. Selects training duration (1-12h).
3. System calculates bronze cost and XP reward based on warrior level.
4. Player confirms → warrior trains (timer). Warrior marked as busy.
5. Player collects: receives XP only (no bronze, no items).

**Alternate:**
- Insufficient bronze → 400.

---

### UC-74: Declare Guild War from Kingdom View
**Actor:** Guild leader (guild without territory)
**Pre-conditions:** Same as UC-60.

**Flow:**
1. Player opens kingdom detail view.
2. Clicks "Declare Attack" button within the kingdom's war section.
3. System registers declaration (same mechanic as UC-60, just accessed from kingdom UI).

---

### UC-75: View Kingdom Zone with PvP (Hunter Role)
**Actor:** Player lv10+

**Flow:**
1. Player selects a PvP zone in any kingdom (lv10+ required).
2. Can enter as **Gatherer** (gathering resources with risk) OR **Hunter** (hunting other players).
3. Resolution same as current Zone system.

---

*Updated 2026-06-02. World/3 Kingdoms: UC-69 to UC-75.*

---

## SoulStone 💎 — Moeda VIP

### UC-76: Verificar Saldo de SoulStones
**Actor:** Jogador autenticado

**Pre-conditions:** Jogador logado.

**Flow:**
1. Jogador abre o sidebar ou qualquer tela que exiba o saldo.
2. Sistema retorna `soulStones` junto com os dados do guerreiro (`GET /api/warrior/me`).
3. Saldo exibido no sidebar como `💎 N SoulStone(s)` quando > 0.

**Regras:**
- Saldo pertence à conta (Player), não ao personagem
- Saldo nunca pode ser negativo

---

### UC-77: Cura Instantânea de HP via SoulStone
**Actor:** Jogador com HP < 100% e saldo ≥ 1 SoulStone

**Pre-conditions:** Guerreiro com HP incompleto, cooldown expirado (ou nunca usado).

**Flow:**
1. Jogador acessa o Templo.
2. Sistema exibe botão "💎 Cura Instantânea (1 SoulStone)" se HP < 100% e CD = 0.
3. Jogador clica no botão.
4. Sistema valida: saldo ≥ 1, CD expirado, HP < 100%.
5. HP restaurado para 100%, 1 SoulStone debitado, `lastSoulstoneHealAt` atualizado.
6. Botão exibe countdown do CD (30 min) após uso.

**Exceções:**
- Saldo insuficiente → erro "Not enough SoulStones"
- CD ativo → erro "Instant heal on cooldown. Wait Xm Ys"
- HP já cheio → erro "Warrior already has full HP"

---

### UC-78: Expandir Bag (Mochila)
**Actor:** Jogador free com bag não expandida e saldo ≥ 3 SoulStones

**Pre-conditions:** `inventoryExpanded = false`.

**Flow:**
1. Jogador acessa Inventário.
2. Vê barra de slots "7/10" e botão "💎 Expand (3 SoulStones)".
3. Clica no botão.
4. Sistema valida: não expandida, saldo ≥ 3.
5. `inventoryExpanded = true`, 3 SoulStones debitados.
6. Bag passa a ter limite de 20 slots. Barra exibe "7/20".

**Exceções:**
- Já expandida → erro "Inventory already expanded to 20 slots"
- Saldo insuficiente → erro "Not enough SoulStones. Required: 3"

---

### UC-79: Receber Item com Bag Cheia
**Actor:** Qualquer jogador

**Pre-conditions:** Bag no limite máximo de slots (10 ou 20 conforme VIP).

**Flow:**
1. Jogador completa quest/arena/drop e seria para receber item.
2. Sistema conta itens não-equipados na bag.
3. Bag está no limite → item não é adicionado ao inventário.
4. Sistema retorna aviso "Inventory full (X slots). Sell items or expand with SoulStones."

---

### UC-80: Dar SoulStones via Admin (Teste)
**Actor:** Jogador autenticado (qualquer — admin endpoint temporário)

**Pre-conditions:** Jogador logado.

**Flow:**
1. Request `POST /api/admin/grant-soulstones` com `{"amount": N}`.
2. Sistema valida amount (1-100).
3. N SoulStones adicionados ao saldo do jogador.

*Updated 2026-06-02. SoulStone: UC-76 to UC-80.*

---

## VIP Status

### UC-81: Comprar Status VIP
**Actor:** Jogador com saldo ≥ 15 SoulStones

**Pre-conditions:** Logado, sem VIP ou VIP já ativo (renovação).

**Flow:**
1. Jogador abre a aba SoulStone Shop no Commerce.
2. Vê painel VIP com status atual e botão "Comprar VIP (15 💎)" ou "Renovar +30 dias".
3. Clica no botão.
4. Sistema valida saldo ≥ 15.
5. Debita 15 SoulStones.
6. Define `vipExpiresAt = now() + 30 dias` (ou acrescenta 30 dias se já ativo).
7. Define `inventoryExpanded = true` (bag 20 slots inclusa).
8. Retorna data de expiração e saldo atualizado.

**Exceções:**
- Saldo insuficiente → erro "Not enough SoulStones. Required: 15"

---

### UC-82: Cura Grátis VIP no Templo
**Actor:** Jogador VIP ativo com HP < 100%

**Pre-conditions:** `vipExpiresAt > now()`, HP < 100%, CD de 10 min expirado.

**Flow:**
1. Jogador abre o Templo.
2. Vê botão "💎 VIP Heal (grátis, CD 10 min)" além do botão normal.
3. Clica no botão VIP.
4. Sistema valida: VIP ativo, CD expirado, HP < 100%.
5. HP restaurado para 100%, sem custo de bronze.
6. `lastVipHealAt` atualizado.

**Exceções:**
- VIP expirado → botão não aparece
- CD ativo → botão mostra countdown ("Pronto em 7m 23s")
- HP já cheio → botão desabilitado

---

### UC-83: Missão Instantânea VIP
**Actor:** Jogador VIP com `vipInstantQuestsToday < 2`

**Pre-conditions:** VIP ativo, guerreiro livre, missões instantâneas do dia disponíveis.

**Flow:**
1. Jogador abre detalhe de um reino no World tab.
2. Quest card mostra dois botões: "Start Quest" e "⚡ Instant (N restantes)".
3. Jogador clica em "⚡ Instant".
4. Sistema valida: VIP ativo, counter < 2, guerreiro livre, stamina suficiente.
5. Quest iniciada E concluída imediatamente.
6. Modal de collect abre com XP, bronze e drop.
7. Counter `vipInstantQuestsToday` decrementado.

**Exceções:**
- VIP expirado → botão não aparece
- Counter = 0 → botão desabilitado "0 restantes hoje"
- Guerreiro busy → botão desabilitado

---

### UC-84: Limite Diário de Arena
**Actor:** Qualquer jogador

**Pre-conditions:** Jogador logado.

**Flow (tentativa dentro do limite):**
1. Jogador entra na Arena.
2. Sistema verifica `arenaFightsToday < limite` (5 free / 10 VIP).
3. Luta iniciada normalmente.
4. `arenaFightsToday` incrementado.
5. UI mostra "3/5 lutas hoje" (ou "8/10 VIP").

**Flow (limite atingido):**
1. Jogador tenta entrar na Arena com counter no limite.
2. Sistema rejeita com "Daily fight limit reached (5/5). Resets at midnight UTC."

**Reset:**
- Se `lastArenaFightDate != today`, zeramos `arenaFightsToday` antes de validar.

---

### UC-85: Dois Buffs Ativos Simultâneos (VIP)
**Actor:** Jogador VIP no Templo

**Pre-conditions:** VIP ativo, primeiro buff já ativo.

**Flow:**
1. Jogador abre o Templo com um buff já ativo.
2. Vê lista de bênçãos com segundo slot disponível (VIP).
3. Seleciona um buff diferente do primeiro.
4. Sistema valida: VIP ativo, segundo slot vazio ou buff diferente do 1º.
5. `activeBuff2` e `buffExpiresAt2` definidos no Warrior.
6. UI mostra ambos os buffs ativos com seus timers.

**Exceções:**
- Free player → segundo slot bloqueado
- Tentar aplicar o mesmo buff em ambos os slots → erro

---

### UC-86: Verificar Status VIP no SoulStone Shop
**Actor:** Qualquer jogador

**Flow:**
1. Jogador abre aba SoulStone Shop no Commerce.
2. Vê: saldo de SS, status VIP (ativo com X dias / expirado / sem VIP), benefícios ativos.
3. Vê contadores do dia: missões instantâneas (N/2), lutas de arena (N/5 ou N/10).

---

### UC-87: Expiração Automática do VIP
**Actor:** Sistema

**Trigger:** Qualquer request após `vipExpiresAt < now()`

**Flow:**
1. Player faz request.
2. Backend calcula `isVip()`: retorna false se `vipExpiresAt == null || vipExpiresAt.isBefore(now())`.
3. Benefícios VIP não são aplicados.
4. UI exibe "VIP expirado em DD/MM" no SoulStone Shop.

*Updated 2026-06-03. VIP: UC-81 to UC-87.*
