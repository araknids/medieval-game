# PLANO — Sistema de Classes (Recruit → Trial → Warrior / Archer)

> **Status:** desenho aprovado, aguardando implementação. [CLASSES]
> Fonte da verdade pra esta feature. Quando implementado, atualizar o CLAUDE.md.

## Objetivo

O jogador deixa de nascer **Guerreiro**. Ele começa como **Recruit** (classe neutra,
sem especialização) e, ao chegar no **level 10**, faz uma **Trial de combate** (rito de
passagem) pra escolher seu caminho: **Warrior** (tank corpo-a-corpo) ou **Archer**
(arqueiro crit/esquiva). A escolha é **permanente**. Sem magia por enquanto — `INT` fica
reservado pra uma futura classe mágica (Mage).

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Classe inicial | **Recruit** (neutro, pré-especialização) |
| Como diferenciar | **Stats base + caps de atributo por classe** — NÃO mexe no `BattleSimulator` |
| Quest de troca | **Trial de combate** no Lv10 (instantâneo, reusa o motor de combate) |
| Permanência | **Permanente** (sem respec pago por enquanto) |

## Por que isso encaixa sem reescrever combate

O motor d20 ([BattleSimulator](../backend/src/main/java/com/medieval/game/service/BattleSimulator.java))
já mapeia dois arquétipos nos atributos existentes:

- `STR` → dano + bônus de acerto (`floor(STR/20)`), `CON` → HP (×8), `DEF` → mitigação % → **perfil Warrior**
- `DEX` → AC/esquiva (`10+DEX`; só crítico passa AC alta), `LUK` → janela de crit + Fortune Save → **perfil Archer**

Logo, basta dar a cada classe **base stats** e **caps de atributo** diferentes. Nenhuma
linha do `BattleSimulator` muda.

## As classes

| Classe  | ATK base | DEF base | HP base | STR cap | DEX cap | CON cap | LUK cap | INT cap | Identidade |
|---------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|---|
| **Recruit** | 12 | 10 | 100 | 40 | 40 | ∞ | 40 | 30 | Genérico, sem foco. Fase 1–9. |
| **Warrior** | 15 | 14 | 130 | **80** | 30 | ∞ | 40 | 20 | Tank confiável: STR/CON, muito HP/DEF, pouca esquiva. |
| **Archer**  | 18 |  9 |  95 | 50 | **55** | ∞ | **70** | 20 | Glass cannon: DEX (esquiva) + LUK (crit), pouco HP/DEF. |

> ⚠️ **Números iniciais — sujeitos a tuning no playtest solo.** A regra de balanço:
> Warrior empurra `STR`/`CON` (acerta sempre, tanka no peito); Archer empurra `DEX`/`LUK`
> (inimigo só acerta no crítico, mas Archer morre rápido quando leva crit). `CON` fica ∞
> pra todos na v1 (não quebra a progressão infinita de HP atual); capar o `CON` do Archer
> pra cravar o "glass cannon" no late-game fica como alavanca futura.

## Fluxo do jogador

```
Registro ─► Recruit (Lv1)
   │  joga normal: quests, zonas, work, coleta… ganha XP e pontos de atributo
   ▼
Lv10 ─► destrava a "Path Trial"
   │  escolhe o caminho (Warrior OU Archer) e enfrenta o Guardião daquele caminho
   ▼
Vence a Trial ─► vira a classe (PERMANENTE)
   • base ATK/DEF/HP trocados pelos da nova classe
   • RESPEC GRÁTIS: devolve TODOS os pontos gastos (atributos zeram, availablePoints volta)
   • (opcional) recompensa: arma temática inicial (Warrior→espada, Archer→arco)
Perde a Trial ─► nada muda; pode tentar de novo (combate custa HP/risco de KO, como qualquer luta)
```

- **Antes do Lv10:** o Recruit joga tudo normalmente. Card do guerreiro mostra dica
  "Choose your path at level 10".
- **Respec grátis na troca** é essencial: os caps mudam, então o jogador precisa
  re-alocar os pontos pra nova classe.

## A Trial (combate)

- Reusa `BattleSimulator` (instantâneo, modelo SEM_TIMER).
- O jogador **escolhe o caminho** e enfrenta o **Guardião** daquele caminho:
  - **Trial of the Blade** (Warrior) — guardião pesado de corpo-a-corpo.
  - **Trial of the Bow** (Archer) — guardião ágil.
- Tunado pra ser um **teste justo** de um Recruit Lv10 bem-construído (números no playtest).
- Vitória aplica a troca. Derrota aplica o dano normal de PvE (pode dar KO) e libera retry.
- **Não** é preciso vencer o guardião do caminho oposto — só o do caminho escolhido.

> Números iniciais do Guardião (placeholder, ajustar): ~Lv10–12, stats no nível de um
> Recruit forte. Definir no playtest junto com o tuning das classes.

## O que muda no código

### Novo
- **`WarriorClass`** ([enum](../backend/src/main/java/com/medieval/game/enums/WarriorClass.java)):
  adicionar `RECRUIT` e `ARCHER`; adicionar campos de **caps** por classe
  (`strCap, dexCap, conCap, lukCap, intCap`) além dos `base*` já existentes.
- **`ClassChangeService`** + **`ClassController`** (`/api/class`):
  - `GET /api/class` → classe atual, se a Trial está disponível (`level≥10 && class==RECRUIT`),
    os dois caminhos (nome, descrição, base stats, caps) e preview do Guardião.
  - `POST /api/class/trial/{path}` (`WARRIOR`|`ARCHER`) → roda o combate; se vencer:
    seta a classe, aplica base stats novos, **respec grátis**, (opcional) dá item; retorna
    log da batalha + resultado. Segue o padrão de HP/KO de `TowerService`/`ArenaService`.

### Alterado
- **`WarriorService.spendPoint`** ([linhas 87–93](../backend/src/main/java/com/medieval/game/service/WarriorService.java#L87-L93)):
  caps hardcoded (STR 60 / DEX 40 / LUK 50 / INT 40) passam a vir de
  `warrior.getWarriorClass().xxxCap` (caps por classe).
- **`AuthController.register`** ([linha 57](../backend/src/main/java/com/medieval/game/controller/AuthController.java#L57)):
  `WarriorClass.WARRIOR` → `WarriorClass.RECRUIT`.
- **`MaintenanceService`** (soft-wipe, [linha ~102](../backend/src/main/java/com/medieval/game/service/MaintenanceService.java#L102)):
  reseta a classe pra `RECRUIT` + base do Recruit (assim cada wipe re-testa o onboarding).
- **`SchemaMigrator`**: atualizar o check constraint de `warriors.warrior_class` pra aceitar
  `('RECRUIT','WARRIOR','ARCHER')` (drop + re-add, idempotente). Sem coluna nova
  (a disponibilidade da Trial é derivada de `level` + `class`).
- **Frontend** (`app.js` / `index.html`):
  - Card do guerreiro mostra a classe (Recruit/Warrior/Archer).
  - `class==RECRUIT && level≥10` → banner/botão **"⚔ Choose your Path"** abre o painel da Trial
    (mostra os dois caminhos com stats/caps/descrição + botão "Attempt Trial" → chama o endpoint,
    exibe log + resultado).
  - `class==RECRUIT && level<10` → dica "Choose your path at level 10".

## Migração / chars existentes

- **Novos registros** → `RECRUIT`.
- **Chars já existentes** (o char de teste em prod é `WARRIOR`): ficam como estão — já
  "escolheram". Sem reset forçado. (O soft-wipe, quando rodar, manda todos pro Recruit.)
- **DataSeeder (admin):** mantém `WARRIOR` (pra testar endgame) — ou virar `RECRUIT`
  pontualmente pra testar o fluxo da Trial. Decidir na hora.
- **DB:** só o check constraint do `warrior_class` precisa aceitar os novos valores.
  `@Enumerated(STRING)` já guarda o nome do enum; nenhuma coluna nova.

## Testes (a escrever)

`ClassChangeTest`:
- Recruit Lv<10 → Trial indisponível (rejeita).
- Recruit Lv10 → Trial disponível.
- Vence Trial → classe setada, base stats trocados, **atributos devolvidos**
  (`availablePoints` = total gasto, atributos zerados), não dá pra refazer (já especializado).
- Já é `WARRIOR`/`ARCHER` → Trial rejeitada.
- Perde Trial → classe inalterada.

Ajustar testes que assumem registro = `WARRIOR` (o registro agora cai em `RECRUIT`; tests
que criam guerreiro explicitamente via `WarriorClass.WARRIOR` continuam valendo).

## Iteração: Armas por classe (arco vs espada) — [CLASSES_ARMAS]

> Status: **implementado** (trava real). `WeaponClassTest` cobre make/equip/troca.

**Regra:** Guerreiro só equipa arma **corpo-a-corpo** (espada/machado/lança); Arqueiro só
arma **à distância** (arco). Recruit usa corpo-a-corpo (kit inicial). Trava no `equip()`.

### Modelo
- **`WeaponCategory { MELEE, RANGED }`** (enum novo).
- **`InventoryItem.weaponCategory`** (coluna nova, nullable; `null` = arma legada → tratada
  como MELEE). É **derivada do NOME** da arma dentro do `make()` — nome com palavra de arco
  (`bow/longbow/shortbow/crossbow/recurve/sling`) → RANGED; senão MELEE. Assim **todas** as
  fontes de arma (starter, loja, forja, loot, mail) já saem com a categoria certa **sem mexer
  na assinatura do `make()`** nem nas tabelas `Object[][]` da loja.
- **`WarriorClass.weaponCategory`**: RECRUIT/WARRIOR = MELEE, ARCHER = RANGED.
- **`equip()`**: se `type==WEAPON` e `categoria(item) != classe.weaponCategory` → rejeita com
  mensagem clara ("Archers can only wield bows." / "Warriors can't use bows."). `null`→MELEE.

### Troca de classe
- Virar **ARCHER**: desequipa qualquer arma melee equipada (senão fica travada) e dá um
  **arco inicial** ("Hunting Bow") pra não ficar sem arma. Virar **WARRIOR**: nada muda
  (já era melee).

### Conteúdo de arco (paridade com a linha de espada)
- **Loja**: o slot de arma fica **consciente de classe** — arqueiro vê arcos (mesmos
  stats/preço do tier, só troca o nome p/ um arco). Guerreiro vê espadas. (Não mostra arma
  que a classe não usa.)
- **Forja**: linha de arcos paralela aos recipes de espada (copper/iron/… bow).
- **Loot de quest/zona**: `itemName(WEAPON)` passa a escolher nome de arco p/ arqueiro
  (→ RANGED) e de espada p/ o resto (→ MELEE), via a classe do recebedor.

### Combate
- Arco = **+ATK plano**, igual espada (sem escala por atributo ainda). "Arco escala com DEX"
  fica como futuro — o motor de combate continua intocado.

### Migração / legado
- Coluna `weapon_category` nullable (SchemaMigrator). Armas legadas (`null`) = MELEE no código
  (todo arqueiro é novo; todo item antigo é espada → correto). Sem backfill SQL.

### Testes
- `equip` rejeita arma de outra classe; arqueiro equipa arco; guerreiro não equipa arco.
- Troca p/ ARCHER desequipa a espada e entrega o arco inicial.
- `make()` infere categoria pelo nome (espada→MELEE, arco→RANGED).
- Loja oferece arco p/ arqueiro; loot dá arco p/ arqueiro.

## Iteração: Tipos de arma com perfil de stats — [CLASSES_ARMAS]

> Status: **implementado**. `WeaponTypeTest` + `WeaponClassTest` cobrem perfil/inferência/make.

7 tipos de arma, **mesmo budget de poder, distribuição diferente** (ninguém é mais forte):

| Tipo | Categoria | Perfil (frações do budget) |
|------|-----------|----------------------------|
| **Sword**      | MELEE  | ATK .70 · DEF .30 (versátil) |
| **Greatsword** | MELEE  | ATK 1.0 (dano puro)          |
| **Axe**        | MELEE  | ATK .75 · LUK .25 (crit)     |
| **Spear**      | MELEE  | ATK .75 · STR .25 (acerto)   |
| **Short Bow**  | RANGED | ATK .75 · DEX .25 (esquiva)  |
| **Long Bow**   | RANGED | ATK 1.0 (dano puro)          |
| **Crossbow**   | RANGED | ATK .75 · LUK .25 (crit)     |

Regra: quem não tem secundário (Greatsword/Long Bow) põe tudo em ATK. Armas **não dão HP**
(identidade ofensiva; HP fica em armadura/outros slots).

### Modelo
- **`WeaponType`** enum: tipo + categoria + frações de stat + `fromName()` (EN+PT) + `stats(itemLevel, rarity)`
  → `{atk,def,hp,str,dex,luk}` deterministico (budget = `itemLevel × rarityMult × 0.6`, mín. 1 ATK).
- **`InventoryItem`**: ganha `strBonus/dexBonus/lukBonus` (colunas novas, default 0) + `getEffectiveStr/Dex/Luk`.
- **`make()`**: p/ arma, **sobrescreve** os stats com `WeaponType.fromName(name).stats(itemLevel, rarity)`
  (atk/def/hp/str/dex/luk + categoria). Toda fonte (loja/loot/forja/starter/mail-claim) se auto-perfila
  só pelo nome+nível — sem mudar a assinatura do make.
- **`WarriorStatsService.equippedGear`**: soma o str/dex/luk **base** das armas (hoje só somava de afixo).
- **Mail**: passa a preservar `item_level` (coluna nova) — senão a arma reconstruída no claim cairia
  p/ nível 1 e o override recalcularia stats minúsculos.
- **Loja/Loot/Forja**: variam o tipo de arma (nome) dentro da categoria da classe; stats vêm do perfil.
  Forja gera recipes por tier × tipo (`CraftRecipe` ganha `itemLevel` separado do `smithingLevel`).
- **Display**: bag (`ItemResponse`), loja (`ShopItem`) e forja mostram STR/DEX/LUK + nome do tipo.

### Combate
Inalterado — o secundário entra via os stats já existentes (STR=ATK+acerto, DEX=AC, LUK=crit).
"Escala por DEX no arco" segue fora de escopo.

## Fora de escopo (futuro)

- **Magia / classe Mage** (usa o `INT` reservado).
- **Respec pago** / troca de classe depois (hoje é permanente).
- **Capar `CON` do Archer** pra cravar o glass-cannon no late-game.
- **Arco escalar com DEX** (hoje arco = +ATK plano, igual espada). [CLASSES_ARMAS]
- **Renomear** `WarriorClass` → `CharacterClass` e a coluna `warrior_class` (limpeza; alto
  churn em ~17 arquivos, adiado — `Warrior` segue sendo a entidade-personagem).

---

## Path Trial narrativa (intro + desfecho) — [TRIAL_NARRATIVA]

> Adendo 2026-06-07. A Trial deixa de ser "clica e luta": ganha **história antes** do
> combate e **desfecho narrado depois**, no estilo da quest interativa (caixa de intro +
> opções). Sem mudar combate nem balance.

### Fluxo novo
1. **Choose your Path** (modal atual) — os 3 cards de classe. O botão de cada card vira
   **"Choose this Path"** (antes era "Attempt {trialName}" e já disparava a luta).
2. Ao escolher uma classe → **modal de história** (intro daquele caminho): o recruta sobe
   ao campo de provas, o Guardião o desafia. Duas opções (igual ao roll/opções da quest
   diária): **"⚔ Step forward — begin the Trial"** (dispara o combate) e **"← Step back"**
   (volta pros cards). A intro relembra o custo (Monster Core) e a permanência.
3. **Combate** (inalterado — `BattleSimulator` vs Guardião do caminho).
4. **Resultado** com **desfecho narrado** (`note` no modal de coleta): vitória conta que ele
   "provou ser um guerreiro feroz / arqueiro paciente / mercador durão e passou no teste";
   derrota narra o KO + "volte quando estiver pronto". O battle log continua disponível.

### Onde mora o texto
- **`ClassTrialLore`** (`com.medieval.game.quest`, no estilo do `InteractiveQuests`): por
  `WarriorClass`, um record `TrialLore(intro, victory, defeat)`. Texto ao jogador em **inglês**
  (i18n pro PT depois). É o único arquivo de conteúdo novo.
- **`ClassChangeService`**:
  - `ClassPath` ganha `String intro` → preenchido por `ClassTrialLore.forPath(c).intro()` em `pathOf`.
  - `TrialResult` ganha `String narrative` → vitória/derrota de `ClassTrialLore` no `attemptTrial`.
  - (Adicionar campo no fim do record não quebra os testes — eles leem via `attemptTrial`, não constroem.)
- **`app.js`**: cacheia o `info`; card → `chooseClassPath(id)` (modal de intro) → `attemptClassTrial(id)`
  passa `note: data.narrative` pro `showCollectModal`.

Números/combate **inalterados** — é só camada de narrativa.
