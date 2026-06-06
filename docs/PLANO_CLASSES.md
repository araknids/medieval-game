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

## Fora de escopo (futuro)

- **Magia / classe Mage** (usa o `INT` reservado).
- **Respec pago** / troca de classe depois (hoje é permanente).
- **Capar `CON` do Archer** pra cravar o glass-cannon no late-game.
- **Tipo de item "arco"** dedicado (hoje arco é só um `WEAPON` com nome temático).
- **Renomear** `WarriorClass` → `CharacterClass` e a coluna `warrior_class` (limpeza; alto
  churn em ~17 arquivos, adiado — `Warrior` segue sendo a entidade-personagem).
