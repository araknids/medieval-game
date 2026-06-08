# PLANO — Batalha Animada (replay 2D estilo Domina) [BATALHA_ANIMADA]

> Status: **EM IMPLEMENTAÇÃO** (2026-06-08).
> ✅ **Feito:** `BattleEvent` + `BattleOutcome.events` no `BattleSimulator` (spawn/attack/crit/miss/dodge/
> extra/volley/heal/berserk/kiting/victory, com `hitZone` cabeça/corpo/perna e `element`). Combate
> byte-idêntico (RNG preservado); coberto por `BattleEventTest`.
> ⏳ **Próximo:** expor `battleEvents` + `scene` nas respostas (Arena + Zona-chefe) → depois o módulo de
> canvas `battleArena.js` (replay com placeholder, ≤10s, sangue, fundo por cena).
> Estende o combate atual (`BattleSimulator` / Arena / Zona-chefe / Torre) **sem mudar o motor**.

## Conceito

Hoje a luta é resolvida **na hora** no backend (`BattleSimulator.simulateDetailed`) e o jogador vê só
um **log de texto** (`renderBattleLog` no [app.js](../backend/src/main/resources/static/app.js#L128)).
A ideia é dar a cara do **Domina** (jogo de gladiador da Steam, feito em Java/LWJGL): os dois lutadores
**andam, se batem e um morre** — mas como **animação 2D no Canvas/JS**.

**Insight central — a animação é puramente COSMÉTICA.** O combate já é **determinístico no backend**
(sem timer, resolve numa chamada). O front **não simula nada**: ele **reproduz (replay)** um resultado
que já foi decidido. Isso é muito mais simples que o Domina (que tem combate em tempo real com física):
nós só precisamos **tocar a animação correspondente a cada evento** que o backend já produziu.

```
Backend (já existe)          Novo (frontend)
─────────────────            ───────────────
BattleSimulator resolve  →   recebe lista de EVENTOS estruturados
decide vencedor + HP     →   toca animação evento-a-evento (andar/atacar/levar dano/morrer)
                             barra de HP desce conforme o evento
                             botões: ⏩ velocidade · ⏭ pular (cai no resultado)
```

## Requisitos do dono (atualização jun/2026) — moldam o v1

### 1. Anima TODOS os turnos, mas a luta dura **≤ 10s**
Mesmo ~40 rounds têm que caber em **10 segundos ou menos**. O front **time-boxa** o replay:
`duraçãoPorEvento = clamp(MIN, MAX, ORÇAMENTO / nº de eventos)` — orçamento ~8–10s, piso pra dar pra ver
(ex.: ~120ms) e teto pra luta curta não arrastar (ex.: ~600ms). Luta longa → cada golpe rápido e seco;
luta curta → cada golpe respira. Controles **⏩ (2×/4×)** e **⏭ pular** continuam. Resolve a pergunta em aberto #3.

### 2. Ataques por **região: cabeça / corpo / perna**
Cada golpe acerta uma zona, dando variedade visual ao turno. → novo campo **`BattleEvent.hitZone`**
(`"head"|"body"|"legs"`), **sorteado no backend** (determinístico p/ replay). Peso sugerido: crit → mais
chance de **cabeça** (golpe decisivo); normal → **corpo**; golpe fraco/extra → **perna**. A zona define
**onde o atacante mira**, qual frame de **hit/flinch** o alvo toca, e **de onde o sangue jorra**.

### 3. **Sangue bem explícito (gore)**
- **Jato no hit:** partículas vermelhas saindo da `hitZone`; quantidade/tamanho **escala com o dano** (e
  com crit). Canvas 2D aguenta (sistema de partículas simples; pool de ~200).
- **Splatter no chão** acumula sob o alvo; **respingo na tela** (overlay vermelho que some) em crit/morte.
- **Morte:** jato grande + poça; o sprite cai.
- ⚠️ **Classificação Steam:** sangue explícito empurra pra rating **Mature / violência** (decisão consciente —
  você quer explícito). Sugiro um **toggle "reduzir sangue"** nas configs (acessibilidade + flexibiliza rating);
  default = explícito, como pedido.

### 4. **Fundo por local** (arena / floresta / …)
O cenário depende de ONDE é a luta. → o payload carrega **`scene`** (string); o canvas escolhe o background.
Mapa proposto (derivado no **serviço/controller** que dispara a luta — ele sabe kingdom/zone/arena):

| Origem da luta | `scene` | Fundo |
|---|---|---|
| Arena | `arena` | arena de gladiador (areia + arquibancada) |
| Zona FISHING / MAR_ABENCOADO | `coast` / `sea` | praia/costa · mar |
| Zona MINING / GRUTAS_DE_CRISTAL | `cave` | caverna/mina |
| Zona COMBAT (Fortaleza) | `fortress` | pátio amaldiçoado |
| Chefe errante | herda a `scene` da zona | — |
| Torre | `tower` | andar da torre |

v1 pode começar com **2–3 fundos** + um default; os demais entram depois.

## Decisão-chave: eventos estruturados (NÃO parsear o texto)

O log atual é **texto i18n** (`Messages.tr(...)`), feito pra LER, não pra máquina parsear. Tentar
animar regex-ando string traduzida é frágil e quebra no PT/EN. **A peça arquitetural deste plano** é
o backend expor, **junto** do log de texto, uma **lista de eventos estruturados** que o front consome.

### Novo: `BattleEvent` (DTO de máquina, NÃO traduzido)

```java
// record dentro de BattleSimulator (ou novo arquivo no mesmo package)
public record BattleEvent(
    int round,
    String type,        // "begin" | "attack" | "miss" | "dodge" | "crit" | "extra"
                        // | "volley" | "heal" | "berserk" | "pinned" | "backpedal"
                        // | "pointblank" | "death" | "victory"
    String actor,       // nome de quem age (atacante)
    String target,      // nome do alvo (null em begin/buff próprio)
    int    damage,      // dano aplicado (0 se miss/dodge/buff)
    int    targetHp,    // HP do alvo APÓS o evento
    int    targetMaxHp,
    String element,     // "SUPER" | "RESIST" | null  (pra tingir o golpe)
    String hitZone      // "head" | "body" | "legs" — região atingida (cosmético, sorteado no backend p/ replay)
) {}
```

`BattleOutcome` ganha um campo paralelo, **sem quebrar nada**:

```java
public record BattleOutcome(
    List<String> log,            // texto i18n (inalterado — fallback/acessibilidade)
    List<BattleEvent> events,    // NOVO — pra animação
    boolean firstWon, int firstHpFinal, int secondHpFinal) {}
```

No núcleo de `simulateDetailed`, **cada `log.add(...)` ganha um `events.add(new BattleEvent(...))`
ao lado** (mesma informação, forma estruturada). Como já temos `dmg`, `defAfter`, `def.maxHp`,
`isCrit`, `elemMult`, `round` em escopo no `attack()`/`attackRound()`, é só montar o record.
Combate inalterado — só **emite metadado em paralelo**.

> Toda variante `simulateDetailed` delega pro núcleo, então o evento nasce **num lugar só**.

### Exposição na API

- **Arena** ([ArenaService.java](../backend/src/main/java/com/medieval/game/service/ArenaService.java#L112)):
  hoje faz `match.setBattleLog(String.join("\n", battleLog))`. Adicionar `match.setBattleEvents(json)`
  (coluna nova `battle_events TEXT`, migração) **ou** devolver os eventos só na resposta do duelo
  (sem persistir — mais simples; replay só logo após a luta). **Proposto: não persistir no v1** —
  o `ArenaController` devolve `events` no corpo da resposta do fight; o histórico continua só texto.
- **Zona-chefe / Torre / Quest**: os controllers que hoje devolvem `battleLog` passam a devolver
  também `battleEvents`. A resposta do `collect`/`fight` ganha `battleEvents: [...]`.

## Escolha de biblioteca

| Opção | Veredito |
|-------|----------|
| **Canvas 2D puro** | ✅ **Recomendado p/ v1.** Zero dependência, combina com o stack vanilla JS atual. 2 sprites + barras de HP + projétil de flecha é tranquilo. |
| **PixiJS** (WebGL) | Upgrade futuro se quiser efeitos pesados (partículas, muitos sprites, guerra de guilda 3×5). Mais rápido, mas +dependência. |
| **Phaser** | Engine 2D completa (física/input) — **exagero** aqui: a luta é replay cosmético, não gameplay em tempo real. |

**Proposta:** Canvas puro no v1, encapsulado num módulo `battleArena.js` com API mínima
(`playBattle(canvas, events, {a, b, onDone})`) — se um dia trocar pra PixiJS, troca só o módulo.

## Assets (grátis, uso comercial)

Precisamos de spritesheets com, no mínimo, as animações: **idle, andar (walk), atacar (attack),
levar dano (hit/flinch), morrer (death)**. Fontes com licença que permite Steam:

- **itch.io** (Game assets → free) — vários packs de cavaleiro/guerreiro com essas 5 animações prontas.
- **OpenGameArt.org** — filtrar por **CC0** (sem atribuição).
- **LPC (Liberated Pixel Cup)** — personagens modulares (corpo + armadura + arma) com walk/slash/hurt/die.
- **Kenney.nl** — CC0, limpíssimo (mais cartoon; bom pra protótipo).

⚠️ Conferir licença pack-a-pack (CC0 = mais seguro; alguns pedem crédito).
**v1:** 1 sprite de melee + 1 de arqueiro (pro [KITING]) já fecham o protótipo. Tema por reino/elemento
fica pra depois (paleta/skin trocável por `kingdom`/`element` do evento).

## Fluxo de animação (mapeando evento → ação)

| Evento | Animação |
|--------|----------|
| `begin` | Dois sprites entram pelas bordas, **andam** até a distância de combate (arqueiro para mais longe). Barras de HP cheias. |
| `attack` (hit) | Atacante golpeia a **zona `hitZone`** (cabeça/corpo/perna); alvo toca **hit/flinch**; HP desce até `targetHp`; **jato de sangue** na zona (escala c/ dano) + número de dano flutuante. |
| `crit` | Igual + **screen shake** + flash + **sangue forte** (jato grande + respingo na tela). Crit tende à cabeça. |
| `extra` / `volley` | Atacante toca attack 2× rápido (golpe extra do AGI / Volley). |
| `miss` / `dodge` | Alvo dá um **passo/pulo pra trás** (sem dano). Texto "MISS"/"DODGE". |
| `element SUPER/RESIST` | Tinge o golpe (✨ chama / 🛡 escudo) conforme o campo `element`. |
| `heal` / `berserk` | Brilho no próprio sprite (verde cura / vermelho fúria). |
| `pinned`/`backpedal`/`pointblank` | [KITING] arqueiro: melee **avança e cola**; arqueiro **atira de perto** (flecha curta) e depois **recua**. Visualiza o kite. |
| `death` | Perdedor toca **death** e cai; vencedor toca idle/vitória. |
| `victory` | Pausa no vencedor + 🏆; modal de resultado padrão aparece. |

**Projétil do arqueiro:** `Combatant.ranged` já existe no backend — o evento de ataque de um arqueiro
dispara uma **flecha** (sprite simples voando do atacante ao alvo) antes de aplicar o hit.

**Controles:** velocidade (1×/2×/4×), **⏭ Pular** (vai direto ao último evento + resultado), e respeitar
quem quer só o texto (toggle "log" — reaproveita `renderBattleLog`). Acessibilidade: o texto continua
existindo sempre.

## O que muda no código

### Backend
- **`BattleSimulator`**: novo record `BattleEvent`; `BattleOutcome` ganha `List<BattleEvent> events`;
  núcleo `simulateDetailed` emite um evento ao lado de cada linha de log. **Motor inalterado.**
- **Controllers** (Arena, Zona, Torre, Quest): incluir `battleEvents` na resposta do fight/collect
  (serializa a lista; não precisa persistir no v1).

### Frontend
- **`battleArena.js`** (novo módulo): `playBattle(canvasEl, events, opts)` — carrega spritesheets,
  roda o loop de animação (`requestAnimationFrame`), desenha sprites + barras de HP + números.
- **`showCollectModal`** ([app.js:3890](../backend/src/main/resources/static/app.js#L3890)): quando a
  resposta traz `battleEvents`, renderiza um **`<canvas>`** no topo do modal e chama `playBattle(...)`;
  o `log` de texto vira uma aba/accordion abaixo (fallback). Sem eventos → comportamento atual.
- **Assets**: `static/assets/sprites/` (spritesheets + 1 JSON de frames por personagem).
- **CSS**: estilo do canvas, barras de HP, botões de velocidade/pular, screen-shake.

## Escopo

**v1 (protótipo jogável):**
- Eventos estruturados no `BattleSimulator` + exposição na **Arena** e no **Zona-chefe** (as lutas mais
  "evento único" e satisfatórias de assistir).
- Canvas puro, 1 sprite melee + 1 arqueiro, animações idle/walk/attack/hit/death, barra de HP,
  números de dano, crit shake, projétil de flecha, controles velocidade/pular.

**v2+ (futuro):**
- Estender pra **Torre** e **Quests** de combate.
- **Guerra de guilda 3×5** ([GUERRA_FORMACAO]) — o gauntlet por lane ficaria épico animado (provável
  hora de migrar pra PixiJS pela quantidade de sprites).
- Skins por **reino/elemento** (paleta trocável), armas refletindo o `WeaponType` equipado.
- Persistir `battle_events` pra **rever** lutas antigas no histórico da Arena.
- Habilidades ativas com VFX próprios (Shield Bash 💥, Volley ☄, Evasive Roll 🌀).

## Decisões propostas (a confirmar com o dono)

| Tema | Proposta |
|------|----------|
| Biblioteca | **Canvas 2D puro** no v1 (PixiJS só se/quando a guerra animada entrar). |
| Persistência dos eventos | **Não persistir** no v1 (replay logo após a luta; histórico segue só texto). |
| Onde estreia | **Arena + Zona-chefe** primeiro. |
| Estilo dos sprites | Pixel art medieval (pack CC0 do itch.io/OGA) — **curadoria de pack é decisão sua.** |
| Texto do log | **Mantido sempre** (fallback/acessibilidade), abaixo do canvas. |
| Duração | **≤10s** mesmo contando todos os turnos (time-box; ⏩/⏭ disponíveis). |
| Região de acerto | `hitZone` cabeça/corpo/perna **sorteada no backend** (determinístico p/ replay). |
| Sangue | **Explícito** (default) + toggle "reduzir sangue" nas configs. Implica rating Mature na Steam. |
| Fundo | Por `scene` (arena/floresta/mina/fortaleza/…), derivada do contexto da luta. |

## Perguntas em aberto

1. **Pular animação por padrão?** Quem farma 100× zona não quer ver replay toda vez. Sugestão: opção
   "auto-pular animação" nas configs (default = mostra; veterano desliga).
2. **Tamanho do canvas no mobile?** Definir um aspect-ratio fixo que caiba no modal atual.
3. **Quão fiel ao evento?** → **RESOLVIDO:** anima TODOS os turnos, time-boxado em **≤10s** (ritmo
   distribuído — ver Requisito #1). Sem condensar rounds: todos aparecem, só mais rápidos.

## Referências
- Domina (gladiador, Steam, Java/LWJGL, dev solo "bignic"): https://en.wikipedia.org/wiki/Domina_(video_game)
- Motor atual: [`BattleSimulator.java`](../backend/src/main/java/com/medieval/game/service/BattleSimulator.java)
- Render de log atual: [`app.js` `renderBattleLog`](../backend/src/main/resources/static/app.js#L128)
