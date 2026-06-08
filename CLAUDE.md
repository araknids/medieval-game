# Medieval Game — Contexto do Projeto para Agentes Claude

## Visão Geral

Jogo RPG idle/browser no estilo de jogos medievais antigos. O jogador cria um guerreiro, gasta **estamina** em ações **instantâneas** (missões, coleta, trabalho, zona, arena), pega a recompensa na hora e progride; **sem timers** — a estamina (regen 100% em 1h) é o gate. O projeto visa lançamento na Steam via cliente Godot (futuro), mas atualmente roda como web app.

**URL de produção:** `https://medieval-game-production.up.railway.app`

---

## Tech Stack

| Camada | Tecnologia |
|--------|-----------|
| Backend | Java 17, Spring Boot 3.2.5 |
| Segurança | Spring Security + JWT (Auth0 java-jwt 4.4.0) |
| Banco (dev) | H2 in-memory |
| Banco (prod) | PostgreSQL (Railway) |
| ORM | Spring Data JPA / Hibernate |
| Frontend | Vanilla JS + HTML + CSS (servido pelo Spring Boot) |
| Email | Brevo HTTP API |
| Hosting | Railway |
| Source Control | GitHub (araknids/medieval-game) |

---

## Estrutura do Projeto

```
backend/
├── src/main/java/com/medieval/game/
│   ├── config/           # JWT, Security, CORS, GlobalExceptionHandler, DataSeeder
│   ├── controller/       # REST controllers (um por domínio)
│   ├── enums/            # Todos os enums do sistema
│   ├── model/            # Entidades JPA
│   ├── repository/       # Spring Data JPA repositories
│   └── service/          # Lógica de negócio
├── src/main/resources/
│   ├── static/           # Frontend (index.html, app.js, style.css)
│   ├── application.properties
│   ├── application-dev.properties   # H2, instant-complete=true
│   └── application-prod.properties  # PostgreSQL, Railway env vars
```

---

## Decisões Arquiteturais Importantes

### open-in-view=false
`spring.jpa.open-in-view=false` está ativado em produção. Isso significa que entidades lazy-loaded NÃO podem ser acessadas fora de um `@Transactional`. Use `@EntityGraph` nos repositórios ou carregue os dados dentro da transação.

### Sistema de Moedas
Três moedas separadas no banco: `bronze`, `silver`, `gold`. 100 bronze = 1 prata, 100 prata = 1 ouro. Nunca use `player.setGold()` diretamente — use `player.addBronzeAmount(n)` ou `playerService.spendBronze(player, n)`.

### Sem Timer (estamina é o gate) — [SEM_TIMER]
O jogo é **instantâneo**: missão/coleta/zona/treino/arena resolvem na hora (sem `finishesAt` futuro; `=agora-1s` p/ evitar corrida de sub-segundo no collect). O custo é **estamina** (não tempo). `isReadyToCollect()` usa `>=` (`!isBefore`). **Sem `onMission`/"busy"** (removido 2026-06-05): não há bloqueio cruzado entre atividades — cada uma tem só seu guard de sessão única (torre/treino: `findByPlayerAndStatus(IN_PROGRESS)`; quest: `existsByPlayerAndStatus(IN_PROGRESS)`; zona auto-cancela pendurada). KO/HP é guard à parte. Não existe `/api/warrior/free`. Vários docs/PLANO_SEM_TIMER_PVP.md descrevem o modelo.

> **⚠️ Exceção: o Trabalho** ([WORK_IDLE], 2026-06-08) — o **Trabalho** saiu do modelo instantâneo e virou a **atividade IDLE** do jogo: `startWork` agora marca `finishesAt = agora + horas` (timer REAL; 1/2/6/12h), **não custa estamina** (o gate é tempo + oportunidade) e `collectWork` exige `isReadyToCollect()`. Enquanto trabalha (`WorkService.isWorking`), o jogador fica **BLOQUEADO** de aventurar: `WorkService.assertNotBusy(WorkSessionRepository, player)` (estático, recebe o repo p/ evitar dependência circular) é chamado em `ZoneService.enter`, `ArenaService.startFight`, `TowerService.enter`, `KingdomService.startQuest`, `GuildWarService.attack`, `ClassChangeService.attemptTrial`. Em **instant-complete** (teste) o trabalho volta a resolver na hora (`finishesAt=agora-1s`) p/ não travar o playtest. O resto do jogo continua instantâneo. Desenho: `docs/PLANO_RETENCAO_NOVATO.md`.

### Instant-Complete (flag de teste)
`app.dev.instant-complete=true` agora controla só o **bypass de estamina** (teste) — NÃO há mais timers p/ zerar. Em prod o flag pode estar ligado de propósito (teste solo). Em dev/teste o default é `true`.

### PvP de Zona com Flag + Tiers — [PVP_FLAG]
Farmar uma zona 🟡PVP/🔴HIGH_RISK = instantâneo + **flagga o player 1h** (`Player.pvpFlaggedZone/Until`). Outro player farmando a mesma zona (±10 níveis) pode **cruzar e saquear** o flagged (matchmaking `PlayerRepository.findFlaggedInZone`). Tiers: 🟢SAFE (só PvE NPC), 🟡 (**−10% bronze + perde XP**; recursos/gear seguros, sem lock — `raidVictim`), 🔴 (50% recursos + 15% bronze + item travado `InventoryItem.pvpLocked` (35%) + XP pro killer). [FORTALEZA_ZONAS] **Só a vermelha** trava/arrisca recurso e item (item travado não vende/stasha/guarda; recurso não stasha — `StashService` checa `pvpFlaggedZone==HIGH_RISK`). **Toda coleta** (Pesca/Mineração/Mar Abençoado/Grutas) passa pelo ZoneService (drops por reino via `ZoneActivity.kingdom`). `/api/gathering` ficou só p/ skills, inventário de recursos e consumo de peixe (skills/resources/consume) — o antigo fluxo de **sessão de coleta** (`GatheringSession`, `/api/gathering/start|collect|cancel|current`) foi **removido** junto com o legado de quest (`/api/quests`, `ActiveQuest`).

### Classes (Recruit → Trial → Warrior/Archer) — [CLASSES]
Todo personagem **nasce `RECRUIT`** (neutro). No **Lv10** destrava a **Path Trial** (`/api/class`, `ClassChangeService`): escolhe o caminho e enfrenta o Guardião dele num combate instantâneo (reusa `BattleSimulator`). **Custo** ([TRIAL_CUSTO]): precisa **TER 100 Monster Core** (bag **+** stash, via `GatheringService.resourceQuantityTotal`/`removeResourceTotal`) p/ encarar o Guardião — **só consome ao VENCER** (perder mantém o estoque). `ClassInfo` expõe `monsterCoreCost/Have` p/ a UI. **Fonte de Monster Core** ([MONSTER_CORE_BATALHA]): além da caçada na Fortaleza, dropa de **toda batalha PvE vencida** — encontro de NPC durante a coleta/mineração (`ZoneService.fightNpc` → `PvpResult.monsterCore`, mesclado nos drops via `withMonsterCore`), chefe errante, **arena** (`ArenaService`) e **torre** (`TowerService`), todos via `gatheringService.addResource` (cap pela bag). Números são placeholders. Vencer = vira `WARRIOR` (tank) ou `ARCHER` (crit/esquiva), **permanente**, com **respec grátis** (devolve todos os pontos de atributo). A diferença entre classes é **só stats base + caps de atributo por classe** (`WarriorClass.baseAttack/…/capFor()`) — o motor de combate **não muda**. `WarriorService.spendPoint` usa o cap da classe. `INT` fica reservado p/ uma futura classe Mage (sem magia ainda). Soft-wipe volta todos pra `RECRUIT`. Números das classes + Guardião são **placeholders p/ tuning no playtest**. Desenho: `docs/PLANO_CLASSES.md`.

**Armas por classe** ([CLASSES_ARMAS]): Warrior/Recruit só equipam arma **corpo-a-corpo** (espada/machado), Archer só **arco**. Trava no `InventoryService.equip()`. A categoria (`WeaponCategory MELEE/RANGED`) é guardada em `InventoryItem.weaponCategory` e **derivada do nome** da arma no `make()` (nome com `bow`/`arco` → RANGED); `null` (item legado) = MELEE. Loja (`ShopService.buildSlot`) e Forja (`SmithingService.craftRecipesFor`) mostram arco p/ Archer e espada p/ os demais; loot (`KingdomService.itemName`) idem. Virar Archer na Trial desequipa a espada e dá um "Hunting Bow".

**Classe Mercador** ([MERCADOR]): 3ª classe (`WarriorClass.MERCHANT`) — **classe de economia**. Combatente um pouco mais fraco (stats no meio: 15/11/115, LUK 60), usa **só machado/marreta** (`WeaponType.MACE` novo; trava por TIPO via `WarriorClass.canEquip(WeaponType)` — Merchant = AXE/MACE). Triângulo-alvo: Archer›Warrior›Merchant›Archer. Skills focadas em **economia** (passivas que os serviços consultam via `AbilityService`): Haggler (+LUK +% venda → `InventoryService.sell`), Treasure Hunter (+% drop → `KingdomService.rollDrop`), Master Craftsman (+% craft → `SmithingService` **+ +2.5%/nível nos stats de gear que o próprio Mercador forjou** — `InventoryItem.craftedBy`/`isSelfCraftedBy`, `AbilityService.selfCraftedStatBonusPct`, aplicado em `WarriorStatsService.equippedGear`), Prospector (+% coleta → `ZoneService.resolveGathering`) + Crushing Blow (1 ativa de combate). A Path Trial tem 3 caminhos (Merchant Guardian); virar Merchant desequipa armas não-blunt e dá um machado inicial. Loja/forja oferecem machado/marreta p/ Merchant (`craftRecipesFor(WarriorClass)` filtra por `canEquip`). Desenho: `docs/PLANO_CLASSE_MERCADOR.md`.

**Tipos de arma** ([CLASSES_ARMAS]): 7 tipos (`WeaponType`) com **mesmo budget, distribuição diferente** — Sword(ATK+DEF), Greatsword(ATK puro), Axe(ATK+LUK), Spear(ATK+STR), Short Bow(ATK+DEX), Long Bow(ATK puro), Crossbow(ATK+LUK). O tipo é inferido do NOME; `make()` **sobrescreve** os stats da arma com `WeaponType.stats(itemLevel, rarity)` (atk/def/str/dex/luk; arma não dá HP). `InventoryItem` ganhou `strBonus/dexBonus/lukBonus` base (somados no `equippedGear`). Forja gera recipes por tier×tipo (`CraftRecipe.itemLevel` separado do `smithingLevel`); mail preserva `item_level` (arma recalcula stats no claim). Combate inalterado (STR=ATK+acerto, DEX=AC, LUK=crit). Números são placeholders p/ tuning.

**Stats/nível do drop** ([ITEM_DROP_LEVEL]): stats de **não-armas** são ALEATÓRIOS (`InventoryService.rollItemStats` usa rng → mesmo item+nível+raridade pode ter stats diferentes); **armas** são DETERMINÍSTICAS pelo tipo (`WeaponType.stats`). Afixos (raridade ≥2) são aleatórios. O **nível do item dropado** = **nível do MONSTRO morto** (`KingdomService.questMobLevel` = nível do jogador × dificuldade da quest 0.8–1.4), não mais o nível do jogador — quests mais difíceis dropam itens de nível mais alto (podem exigir o jogador subir de nível pra equipar). Quest interativa (sem combate) → nível do jogador.

### Atributos & Combate — papéis claros, sem AC — [REBALANCE]
Redesenho dos atributos (saiu o "AC = 10 + DEX" que criava parede/one-shot — ver `docs/AUDITORIA_BALANCE.md`). Papéis: **STR**=dano melee, **CON**=HP (`+8/pt`), **DEX**=**acerto** (+ **dano do arco** no Archer), **AGI** (novo atributo `Warrior.agility`)=**golpes extra + esquiva**, **LUK**=crítico, **INT**=reservado (Mage). **Dano por classe** (`WarriorClass.damageAttribute()`): Archer escala dano com **DEX** (Força não aumenta dano de arco), melee com **STR** — `Warrior.getTotalBaseAttack()` + afixo-de-dano do gear seguem isso. O **d20 continua**. No `BattleSimulator`: acerto = `d20 + DEX_atk/5 − AGI_def/8 ≥ 11` (`HIT_DC`); **crit** (`roll ≥ critThreshold(LUK)`) **fura a esquiva** e dá **×1.5** (`CRIT_MULT`, era ×2 = one-shot); **golpe extra** por round = chance `clamp(0,90,(AGI_atk−AGI_def)×1.5)` (`attackRound`). DEF = mitigação (`ATK×100/(100+DEF)`, inalterado). No `combatStats` o array `[atk,def,hp,dex,agi,luk]` mudou o **slot 4 de strBonus→agi** (buff de evasão do Templo + AGI do pet alimentam agi; STR saiu do acerto). Caps por classe em `WarriorClass` (novo `agiCap`); passiva **Agility**→AGI, **Eagle Eye**→LUK. Migração: `warriors.agility` (default 0, `SchemaMigrator`). **Kiting** ([KITING]): Arqueiro (`WarriorClass.isRanged()`) vs melee — quando o melee **cola** (chance `MELEE_CLOSE_CHANCE`), o arqueiro atira de perto com **dano reduzido** (`ARCHER_CLOSE_DMG`) e depois **perde um turno** recuando (`Side.pinned` 0→2→1→0). `Combatant/Side.ranged` é passado em todos os call sites (Arena/Zona/Torre/Quest/Guerra; NPC=melee). Resultado: triângulo Archer›Warrior›Merchant›Archer (~55–63% cada, sem gear). **Números são placeholders** — valida com `CombatBalanceProbeTest` (sonda: pior-vs-melhor build, acerto×esquiva, kiting, dano máx). Desenho: `docs/PLANO_REBALANCE_COMBATE.md`.

### Elementos (encantamento + áreas de zona) — [ELEMENTOS]
4 elementos (`Element`: FIRE/WATER/EARTH/AIR) numa **roda RPS** (FOGO→AR→TERRA→ÁGUA→FOGO). No combate, **arma do atacante × armadura do defensor**: vence → ×1.25, perde → ×0.75, neutro/sem-encanto → ×1.0 (`Element.multiplier`, aplicado por golpe no `BattleSimulator` via sobrecarga com elementos). Monstro usa 1 elemento como arma E armadura. **Encantamento** é um **buff temporário (1h)** no `Warrior` (`weaponElement`/`armorElement` + `*Until`, getters `getActive*Element()`, limpos no `clearBuff()`/KO) — NÃO fica no item. Feito no Templo (`TempleService.enchantWeapon/Armor`, `/api/temple/enchant/{weapon|armor}/{element}`): consome **1 essência** do elemento + bronze. **Essências** são `ResourceType` (FIRE/WATER/EARTH/AIR_ESSENCE, categoria `ESSENCE`) que dropam das **áreas de elemento** das zonas (`ZoneActivity.element`; cada bioma de coleta tem as 4 áreas; os monstros da área usam aquele elemento). Integrado em Zona (PvE+PvP) e Arena; Torre ainda neutra. Números (±25%, custo, drop) são placeholders. Desenho: `docs/PLANO_ELEMENTOS.md`.

### Habilidades de Classe (Abilities) — [HABILIDADES]
Distintas das **profissões** (`SkillType`). Cada level dá **1 `abilityPoint`** (`Warrior.levelUp()`), separado dos 2 de atributo. Gasta-se em `ClassAbility` da **árvore da classe** (Warrior/Archer; Recruit acumula e gasta após a Trial), cada uma até **lv10** (`AbilityService.learn`, `/api/abilities`). **Passivas** (Toughness/Weapon Mastery/Eagle Eye/Agility) entram no `WarriorStatsService.combatStats` via `AbilityService.passiveStatBonus`. **Ativas** (Shield Bash/Second Wind/Berserk; Precise Shot/Volley/Evasive Roll) disparam no `BattleSimulator` com **cooldown fixo em rounds** (efeito escala com o nível) — o simulador ganhou `Combatant`/`ActiveAbility` + `simulate(Combatant,Combatant,bool)`; `AbilityService.activeLoadout(warrior)` monta o kit; Arena e Zona passam o kit do player (NPC = vazio). **Respec**: grátis no soft-wipe, pago (`/api/abilities/respec`, bronze) a qualquer hora. Tabela `warrior_abilities` (auto-criada) + coluna `ability_points`. Números são placeholders. **Quest checks** (ex.: "Precise Shot ≥ 5") = futuro (nível já fica gravado). Desenho: `docs/PLANO_HABILIDADES.md`.

### Guerra de Território — formação 3×5 [GUERRA_FORMACAO]
A batalha de guild por território (`TerritoryService`) usa **formação**: o líder posiciona até 15 membros num **tabuleiro 3×5** (`Player.warLane` 0–2 / `warDepth` 0–4; `GuildService.setWarFormation`, `POST /api/guild/war-formation`). Cada **coluna (lane) é um gauntlet**: frente vs frente, o vencedor segue com o **HP REAL restante** (`BattleOutcome.firstHpFinal`) contra o próximo fresco da coluna; vence quem leva **2 das 3 lanes** (`guildBrawl(Fighter[][], Fighter[][])`). Células vazias = **auto-fill** por roster→frescor→poder (`buildFormation`). A guerra usa o **combate completo** (elementos + ativas via `Fighter.toCombatant()`). Cansaço de guerra (`Warrior.warFatigue`) e debuff de defensor (streak) continuam multiplicando atk/def/dex. [GUERRA_ROSTER] Desenho: `docs/PLANO_GUERRA_FORMACAO.md`.

### Encontros de zona + Chefe errante — [ZONA_CHEFE]
O nível do monstro normal no `fightNpc` escala por tier (`ZoneService.monsterLevelFor`): 🟢 `player+0..3`; 🟡 `+0..3` & 30% elite (`+4..8`); 🔴 `+0..3` & 50% elite (`+6..15`) — reforça o risco/recompensa da vermelha. No `collect` (qualquer expedição não-HUNTING), antes do encontro normal, rola um **chefe errante** ("escapou da Torre"): chance por tier 🟢0.5% / 🟡1.5% / 🔴3% (`bossChancePerMille`). Se sair, a expedição **pausa** em `ZoneActivityStatus.BOSS_PENDING` (guarda `bossLevel = player+1..20` e `bossName`) e o `collect` devolve `bossPending:true` + `bossName`/`bossLevel`/`fleeChance` — **sem aplicar coleta ainda**. O jogador então chama **`POST /api/zones/{id}/boss/flee`** (teste de stat da classe: Warrior=STR, Archer=DEX, Merchant=LUK; `fleeChance = clamp(20..90, 30+stat)`; sucesso completa a expedição, **falha → cai na luta**) ou **`/boss/fight`** (combate completo via `BattleSimulator`; chefe = `npcStatsByLevel(bossLevel)` com ATK/DEF×1.5 e HP×2 + elemento da área). **Vitória** = 1 item **garantido no nível do chefe** (`rollBossLoot`: 25% Lendário / 40% Épico / 35% Raro; mail se bag cheia) + XP/bronze bônus + coleta normal; **derrota** = `DEFEATED` + KO + penalidade do tier (reusa `defeat`). O roll do chefe é gateado por `app.zone.boss-enabled` (default `true`; **`false` nos testes** p/ collect determinístico — fuga/luta exercitada direto via `resolveBoss*` em `ZoneBossIntegrationTest`). Números são placeholders p/ tuning. Desenho: `docs/PLANO_ZONA_CHEFE.md`.

### Fortaleza Maldita — 3 zonas de caçada + elementos — [FORTALEZA_ZONAS]
A `Kingdom.COMBAT` virou um reino com a **mesma cara dos de coleta**, mas a "coleta" é **caçar monstros**: 3 tiers 🟢SAFE/🟡PVP/🔴HIGH_RISK (antes só tinha 🟡/🔴) + **picker de elemento** (as 4 áreas, igual à coleta). 🟡/🔴 mantêm PvP por flag/raid + item-lock. **Caçada instantânea** (role `COMBAT`, ~10⚡, `staminaCostFor` agora trata COMBAT como coleta `/2`; só `HUNTING` legado usa `/8`). `ZoneService.collect` para COMBAT usa **`resolveCombatHunt`** (via `resolveZoneDrops`): Monster Core sempre + Beast Hide (chance×tier) + **essência do elemento** + XP/bronze **por-kill** (`level×12/×10 × multiplicador do tier`, aplicado no `applyDropsAndRewards`) + **`rollCombatItemDrop`** (chance de item 🟢3/🟡6/🔴10% no nível do monstro, surge como `lootItemName`). O **chefe errante** [ZONA_CHEFE] já roda p/ COMBAT (role≠HUNTING). O antigo **"Hunt Beasts"** (`CombatPveService` + `POST /api/world/{kingdom}/raid`) foi **removido** (junto com `CovilRaidTest`); Monster Core/Beast Hide agora dropam das zonas. **Training Hall** mantido (XP por bronze, à parte). Números são placeholders. Desenho: `docs/PLANO_FORTALEZA_ZONAS.md`.

### Achievements + Títulos — [TITULOS]
Sistema de achievements de 1ª classe + **títulos** exibidos antes do nick, visíveis pros outros. Catálogo no enum `Achievement` (`category, title, displayName, description, metric, threshold`) — desbloqueia quando `valor(metric) >= threshold` (lógica genérica no `AchievementService`, sem lambda no enum, padrão tipo `KingdomQuestType`). `AchievementMetric` mapeia pra stats que já existem (nível, arenaWins, rankPoints, towerBestFloor, totalBronze, classe, guilda/líder). Desbloqueios rastreados em `PlayerAchievement` (tabela `player_achievements`, auto-criada; único por player+achievement). O jogador **escolhe** 1 título ativo (`Player.activeTitle` = `Achievement.name()`; coluna `players.active_title`); `AchievementService.selectTitle` valida desbloqueio. **`AchievementService.titleString(player)`** é **estático e puro** (só lê `activeTitle`, sem DB/lazy) → usado nos DTOs de ranking/guilda/guerra/header sem N+1. Endpoints: `GET /api/achievements` (lista + título ativo, roda `checkAndUnlock` lazy silencioso) e `POST /api/achievements/title` (`{id}` ou null). **Gatilhos** de `checkAndUnlock(player, true)` (manda mail "🏆 Achievement unlocked") em: level-up (`WarriorService.addExperience`), arena (`ArenaService.fight`), torre (`TowerService`), Trial de classe (`ClassChangeService`), criar/entrar/transferir guilda (`GuildService`). Soft-wipe apaga `player_achievements` + zera `activeTitle`. Arena-opponent (modal) ficou fora do v1. Números/nomes são placeholders. Desenho: `docs/PLANO_TITULOS.md`.

### Casa de Leilão — [LEILAO]
Mercado **entre jogadores** por preço fixo (buyout), no mesmo servidor. `AuctionService` + `AuctionController` (`/api/auction`: `GET` browse, `GET /mine`, `POST /list`, `POST /buy/{id}`, `POST /cancel/{id}`) + `AuctionListing` (tabela `auction_listings`) + `AuctionScheduler` (expira de hora em hora + no boot; lazy-on-read também). **Postar**: taxa **5% adiantada** (queima, `spendBronze`) + máx **10 listagens ativas** + janela de **2 dias**; o item vira `InventoryItem.listed=true` (sai da bag, não vende/stasha/guarda/equipa) e **só muda de dono na venda**. Não dá pra listar item equipado/stashed/guarded/pvpLocked. **Comprar** (buyout): paga o preço; **15% queimado** na venda → vendedor recebe **85%** do preço (líquido ≈80% contando os 5% adiantados); precisa de slot livre na bag; não compra a própria listagem. **Cancelar/expirar**: item volta pro vendedor. Números (5%/15%, 2 dias, 10) são placeholders. Desenho: `docs/PLANO_LEILAO.md`.

### VIP / SoulStone — [VIP]
**SoulStone** é uma **4ª moeda** (premium), separada do trio bronze/silver/gold: `Player.soulStones` (int, não passa pelo `addBronzeAmount`/`spendBronze`). `VipService` + `VipController` (`/api/vip/status`, `/api/vip/buy`): gasta SoulStone pra virar **VIP** por um período (`Player.vipExpiresAt`, `isVip()`). Perks do VIP: **bag maior** (`getMaxInventorySlots` 50 vs 30), **mais lutas de arena/dia** (`getArenaFightLimit` 10 vs 5), **cura grátis com CD** (`lastVipHealAt`) + cura instantânea via SoulStone (`lastSoulstoneHealAt`). Soft-wipe zera soulStones/VIP. Números são placeholders.

### Mercado Steam / Mercador Azul — [MERCADO_STEAM] (só fundação)
Plano + scaffold pra **vender item de jogo na Steam** (futuro, exige appid + cliente Godot). Modelo real: o **Mercador Azul** (NPC) recebe o item → backend **concede o itemdef ao inventário Steam** do jogador (server-side, via Web API `IInventoryService` + publisher/asset-server key) → revenda no **Community Market** da Steam (UI da Steam), vendedor recebe **Steam Wallet** (não saca como dinheiro). Está **construído e inerte** (`app.steam.enabled=false`): `Player.steamId` (migração), o **seam** `SteamMarketProvider` + `StubSteamMarketProvider` (loga/simula), flags `app.steam.*`, e a **fatia de consignação completa**: `Consignment`(`HELD→LINKED→SOLD/RETURNED`)+repo, `InventoryItem.consigned` (sai da bag, guards em `InventoryService`/`AuctionService`, soft-wipe apaga antes dos itens), `SteamItemMapping`, `BlueMerchantService` (consign/cancel/linkSteam/state) + `BlueMerchantController` (`/api/blue-merchant`), UI (aba 🔵 no Comércio). O **escrow funciona já** (sem Steam); com `enabled=true` o stub simula a exportação (vira LINKED). Falta p/ valer na Steam (F1+, precisa appid+Godot): `WebApiSteamMarketProvider` real, validar auth ticket no `linkSteam`, catálogo de itemdefs, venda→SOLD via poll/webhook. Testes: `BlueMerchantTest`/`BlueMerchantSteamOnTest`. Desenho completo: `docs/PLANO_MERCADO_STEAM.md`.

### HP do Guerreiro
HP é armazenado como porcentagem (0-100) em `warrior.currentHpSnapshot`. Usa o mesmo padrão da stamina (snapshot + tempo decorrido). Regen: 100% em 1 hora. Guerreiro com HP=0 está inconsciente e não pode entrar em combate.

### Retenção do Novato — buff + daily + work idle — [BUFF_NOVATO][DAILY]
Pacote pra segurar o recém-chegado no **primeiro penhasco de estamina** (desenho: `docs/PLANO_RETENCAO_NOVATO.md`). Três frentes:

**Buff de Novato** ([BUFF_NOVATO]): nos **primeiros 3 dias da conta** (`Player.createdAt`, sem coluna nova), estamina **e** HP regeneram 100% em **15 min** (em vez de 60). `Player.regenMinutes()`/`isNewbieBuffActive()`/`getNewbieBuffHoursLeft()` derivam a janela; `getCalculatedStamina`/`getMinutesToFullStamina` usam. O HP fica no `Warrior`: `getCalculatedHpPercent(int regenMinutes)` recebe a janela; o no-arg lê `player.regenMinutes()` com fallback 60 se detached; os controllers (`Warrior`/`Temple`) passam `player.regenMinutes()` explícito (player em escopo). `WarriorResponse` expõe `newbieBuffActive`/`newbieBuffHoursLeft` (badge no sidebar). **Soft-wipe reseta `createdAt`** → re-concede o buff.

**Trabalho idle** ([WORK_IDLE]): ver a exceção no [SEM_TIMER] acima.

**Daily Reward** ([DAILY]): `DailyRewardService`/`DailyRewardController` (`/api/daily-reward/status|claim`) — **ciclo de 7 dias** dando **peixe de stamina** (escala dia 1→7, dia 7 = lendário + bronze). `Player.lastDailyClaimDate`/`dailyStreak` (migração `SchemaMigrator.patchPlayerDailyRewardColumns`). Reset por comparação de data (sem scheduler); faltar um dia zera o streak (cicla por `(streak-1)%7`). UI: aba 🎁 + popup no login + badge no nav. Entrega via `gatheringService.addResource`; **o que não couber na bag vai por mail de recurso**. Soft-wipe zera os campos.

**Mail de recurso**: `Mail` ganhou `resourceType`/`resourceQty`/`resourceCollected` (migração em `patchMailItemColumns`); `MailService.sendResourceMail` + `claimResource(player, id, gatheringService)` (recebe o service por parâmetro, igual ao `claimItem`, sem ciclo); `POST /api/mail/{id}/claim-resource`. Reaproveitável p/ qualquer recompensa de recurso, não só a daily.

### Luna interrompe a missão (pet) — [LUNA_INTERRUPT]
A Luna (pet) deixou de ser uma **quest avulsa** rara e passou a **interromper missões normais**: no `KingdomService.collectQuest`, antes de resolver, rola `shouldLunaInterrupt(player)` (só sem a Luna + `LUNA_INTERRUPT_PER_MILLE`≈8% + flag `app.luna.interrupt-enabled`). Se interromper, a missão entra em `QuestStatus.LUNA_PENDING` (guarda `KingdomActiveQuest.pendingOptionId` — migração) e devolve `CollectResult.lunaPending=true` — espelha o chefe errante [ZONA_CHEFE]. O jogador decide via **`POST /api/world/{kingdom}/quests/{id}/luna/{help|ignore}`**: **ignore** (terminar) → `resolveLunaIgnore` retoma a resolução normal (`resolveAndReward` com a escolha guardada, recompensa intacta); **help** (ajudar) → `resolveLunaHelp` abre mão da recompensa, marca COLLECTED e roda `rollLunaHelp` (pity escalante `LUNA_BASE/STEP/CAP_PPM`): pega a Luna ou **pity++ + texto de afeição** ("começando a gostar de você"). A flag fica **false nos testes** (collect determinístico; o teste exercita `resolveLunaHelp/Ignore` direto, igual ao `ZoneBossIntegrationTest`). Removidos: `lunaQuestActive`/`isLunaWindow` + a vitrine da Luna no controller. A `RESCUE_STRAY_DOG` continua no enum só pelos textos i18n. Números são placeholders. Desenho: `docs/PLANO_LUNA_INTERRUPCAO.md`.

### Taverna (aba do Comércio): beber + buff stackável + chat + avisos — [TAVERNA]
Aba 🍺 no Comércio com 4 partes (`TavernService`/`TavernController` em `/api/tavern`; desenho: `docs/PLANO_TAVERNA.md`):
- **Beber** (`POST /drink {success}`): cobra **1 bronze sempre** (sem estamina); um **minigame de timing** roda no front e manda o resultado. Sucesso → **+1 stack** de buff. *(Confiança no cliente: o gate real é o bronze.)*
- **Buff stackável** ([TAVERNA]): `Warrior.tavernBuffStacks` + `tavernBuffExpiresAt` (migração). Multiplica **TODOS** os 6 stats por `(1 + stacks×0.01%)` no `WarriorStatsService.combatStats` (fonte de verdade do combate). Cap `TAVERN_BUFF_CAP=10000` (=100%). **Cada gole renova os 5 min INTEIROS** (treadmill — para 5min = perde tudo). Some no `clearBuff()` (KO). `WarriorResponse.tavernBuffPct/tavernBuffSecondsLeft` → badge 🍺 no sidebar.
- **Chat** (`GET /feed?since=` + `POST /chat`): tabela `tavern_messages` (`TavernMessage`/repo), **por-servidor** (1 banco por deploy [SERVIDORES]). **Tempo real = polling** (~4s, sem WebSocket/SSE — não há infra). Cooldown anti-spam em memória; render escapado (XSS); prune mantendo ~200.
- **Avisos globais**: `TavernService.announce(text)` salva msg de sistema no MESMO feed (destacada). v1: **marco de garrafas** (`Player.bottlesDrunk`, marcos `[10,25,…]`). Os gatilhos de **level-up** (`WarriorService.addExperience`) e **drop lendário** (`KingdomService.rollDrop`/`ZoneService.rollBossLoot`) ficam mapeados como follow-up (ligar "mais pra frente"). Soft-wipe apaga `tavern_messages` + zera `bottlesDrunk`/buff. Números são placeholders.

### Currency Safety
`InventoryService.sell()`, `WorkService.collectWork()` e `WorkService.cancelWork()` usam `player.addBronzeAmount()` para evitar somar no campo gold diretamente.

---

## Padrões de Código

### Controller → Service → Repository
Nunca pule camadas. Controllers chamam Services, Services chamam Repositories.

### Autenticação
Todos os endpoints (exceto `/api/auth/**`, arquivos estáticos) requerem `Authorization: Bearer <JWT>`. O `auth.getPrincipal()` retorna o `Long playerId`.

### Respostas de Moeda
O `WarriorController.buildResponse()` normaliza bronze/silver/gold automaticamente antes de retornar (trata casos de silver>100 por migration).

### Battle Log
O `BattleSimulator.simulate()` retorna uma lista de strings. A última linha contém uma tag `WINNER:NomeDoBoss|NomeDoGuerreiro` que deve ser removida antes de exibir ao usuário.

---

## Como Rodar Localmente

```bash
# Pré-requisitos: Java 17+, Maven
cd backend
mvn spring-boot:run
# Acessa em: http://localhost:8080
# H2 Console: http://localhost:8080/h2-console
# Login: adm / adm123
```

### Testes — H2 (rápido) + Postgres (Testcontainers) — [TESTE_POSTGRES]
```bash
mvn test               # padrão: H2 in-memory (rápido, sem Docker)
mvn test -Ppostgres    # Postgres REAL via Testcontainers (perfil Spring 'pgtest') — precisa do Docker LIGADO
```
O `-Ppostgres` sobe um container `postgres:16` (URL `jdbc:tc:postgresql:16:///`) e roda a MESMA suíte no
Postgres, pegando o que o H2 não pega (SchemaMigrator com `DO $$`, check-constraints de enum, índices). O
CI roda **os dois** (`.github/workflows/ci.yml`: job H2 + job `test-postgres`). Config em
`src/test/resources/application-pgtest.properties` (+ `DataSeeder` roda em `dev` e `pgtest`).

---

## Variáveis de Ambiente (Produção Railway)

| Variável | Descrição |
|----------|-----------|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `JWT_SECRET` | String longa aleatória |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | PostgreSQL |
| `MAIL_ENABLED` | `true` para ativar email |
| `BREVO_API_KEY` | Chave da API Brevo |
| `BREVO_FROM_EMAIL` | Email remetente verificado |
| `APP_BASE_URL` | URL base do app |
| `SERVER_ID` / `SERVER_NAME` / `SERVER_ENV` | [SERVIDORES] identidade do servidor/realm (deploy separado); `env` = dev/test/prod. Exposto em `GET /api/server-info` (público) |

### Servidores / Realms — [SERVIDORES]
Multi-servidor = **deploys separados** (1 app + 1 Postgres por servidor), não há coluna `realm`. O app já é "multi-tenant by deploy": front usa caminho relativo, config toda por env var, JWT por instância, `localStorage` por origem. Criar `test`/`prod1`/`prod2` = novo serviço + banco + env vars (`SERVER_*`, `JWT_SECRET` único, `PG*`, CORS/URL próprios). Contas são **por servidor**. O front tem `GET /api/server-info` + `/servers.json` → seletor de servidor na tela de login (botões que abrem a URL do servidor) + badge do servidor no header (cor por `env`). Desenho + passo a passo no Railway: `docs/PLANO_SERVIDORES.md`.

---

## Migrações Recorrentes

Ao adicionar campos `NOT NULL` em tabelas existentes no PostgreSQL, sempre adicionar `DEFAULT`:
```java
@Column(columnDefinition = "integer default 0")
private int myField = 0;
```
Ou rodar SQL manual antes do deploy:
```sql
ALTER TABLE tabela ADD COLUMN IF NOT EXISTS campo tipo NOT NULL DEFAULT valor;
```

Check constraints do PostgreSQL precisam ser atualizados ao adicionar valores em enums:
```sql
ALTER TABLE tabela DROP CONSTRAINT tabela_coluna_check;
ALTER TABLE tabela ADD CONSTRAINT tabela_coluna_check CHECK (coluna IN ('VAL1','VAL2','NOVO'));
```

---

## Arquivos-Chave por Domínio

| Domínio | Service | Controller | Notas |
|---------|---------|-----------|-------|
| Autenticação | `PlayerService` | `AuthController` | JWT, registro, reset de senha |
| Guerreiro | `WarriorService` | `WarriorController` | Stats, atributos, HP, buff |
| Classes | `ClassChangeService` | `ClassController` | Recruit→Trial(Lv10)→Warrior/Archer/Merchant; caps por classe; respec. [CLASSES][MERCADOR] |
| Habilidades | `AbilityService` | `AbilityController` | Árvore por classe (passivas + ativas c/ cooldown); 1 ponto/level; respec. [HABILIDADES] |
| Missões | `KingdomService` | `KingdomController` | Instantâneo (gate=estamina), drops, narrativa. Missões vivas = as do reino (`/api/world/{kingdom}/quests`). O legado `QuestService`/`QuestController`/`/api/quests` foi **removido**. |
| Arena PvP | `ArenaService` | `ArenaController` | Duelo instantâneo por ranking (1 chamada resolve tudo) |
| Torre | `TowerService` | `TowerController` | Andares, chefes escalonados |
| Trabalho | `WorkService` | `WorkController` | Por profissão, level separado |
| Inventário | `InventoryService` | `InventoryController` | Equip, sell, sockets, guarded |
| Loja | `ShopService` | `ShopController` | Rotação 6h, raridade, compra única |
| Casa de Leilão | `AuctionService` | `AuctionController` | Mercado entre players (buyout); taxa 5%+15%, 2 dias, máx 10. [LEILAO] |
| VIP / SoulStone | `VipService` | `VipController` | 4ª moeda (SoulStone), status VIP (cura/bag/limites). [VIP] |
| Recompensa Diária | `DailyRewardService` | `DailyRewardController` | Login diário, ciclo de 7 dias (peixe de stamina), streak, popup+aba+badge. [DAILY] |
| Taverna | `TavernService` | `TavernController` | Beber (1 bronze + minigame) → buff stackável; chat + avisos globais (polling). [TAVERNA] |
| Coleta | `GatheringService` | `GatheringController` | Roller de drops por reino + consumo de peixe. A coleta em si roda pelo ZoneService (unificada). |
| Forja | `SmithingService` | `SmithingController` | Refino, craft, joias, sockets |
| Zonas | `ZoneService` | `ZoneController` | Coleta + combate instantâneos; PvP por flag/tiers, raid, item-lock; drops por reino (`kingdom`) |
| Templo | `TempleService` | `TempleController` | Cura HP, buffs, proteção de itens |
| Achievements/Títulos | `AchievementService` | `AchievementController` | Desbloqueio por marco + título escolhido, exibido pros outros. [TITULOS] |
| Email | `EmailService` | — | Brevo HTTP API |
| Batalha | `BattleSimulator` | — | Reutilizado por Arena, Torre, Zona |
| Lore de Itens | `ItemLoreGenerator` | — | Textos gerados em memória |

---

## Documentos Relacionados

- `docs/PLANO_SEM_TIMER_PVP.md` — **fonte da verdade atual** do modelo sem-timer + PvP de zona (flag, tiers, item-lock, coleta unificada). Mantido atualizado.
- `docs/FEATURES.md`, `docs/GDD.md`, `docs/USE_CASES.md`, `docs/TEST_PLAN.md` — ⚠️ **parcialmente desatualizados** (escritos antes do sem-timer/PvP de zona/coleta unificada; descrevem timers e zonas "coming soon"). Use o código + o PLANO acima como verdade.
