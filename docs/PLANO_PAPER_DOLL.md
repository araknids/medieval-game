# Plano — Paper Doll (personagem em camadas)

> **Status:** backlog visual (não-código por enquanto). Implementar quando for trocar o sprite do
> guerreiro por um boneco montado a partir do equipamento.
> **Objetivo:** desenhar um **corpo-base nu** + empilhar peças de equipamento como **camadas de
> sprite** (PNG separado, mesmo tamanho de frame e mesmos quadros de animação). "Equipar item" no
> jogo vira "desenhar mais uma camada" — sem redesenhar o personagem inteiro.
> **Onde encaixa:** o motor já existe — `battleArena.js` (canvas 2D) e a vitrine do personagem.
> **Estilo-alvo (decisão de 2026-06-08):** pixel art **detalhado, proporções realistas** (corpo
> inteiro, musculatura, pose de pé) — NÃO o estilo chibi/16-bit pequeno (LPC/Mana Seed). Isso muda a
> arquitetura → ver **§0 (decisão de estilo)** antes de tudo.

---

## 0. Decisão de estilo — detalhado/realista × modularidade (LER PRIMEIRO)

O dono quer o visual das referências: personagens **detalhados, proporção real, corpo inteiro**.
Trade-off central:

> **Quanto mais detalhado e realista o corpo, mais caro fica o paper doll "puro".**
> Cada peça de armadura tem que ser desenhada encaixando na musculatura/sombreado/pose **daquele
> corpo específico**, em **todos os quadros de animação**. Por isso os packs modulares prontos são
> pequenos e estilizados — a padronização é o que viabiliza trocar camada. Não existe pack pronto
> barato que seja **detalhado + realista + modular** ao mesmo tempo.

⚠️ As referências têm cara de **IA ou comissão high-end** e são **personagens fixos por classe, não
paper doll**. Se for IA: (1) consistência **frame a frame** na animação é o ponto fraco da IA;
(2) **a Steam exige declarar uso de IA**; (3) licença de output de IA é nebulosa p/ venda.

### Os 3 caminhos
| Caminho | O que é | Custo | Veredito |
|---|---|---|---|
| **A. Paper doll completo detalhado** | base + cada item encaixado, todos os frames | 💸💸💸 | fiel, inviável solo agora |
| **B. "Looks" fixos por classe/tier** | 1 personagem inteiro por classe/nível de armadura (= como as refs são) | 💸 | bonito/barato, pouca granularidade |
| **C. Híbrido** ⭐ **escolhido** | base detalhada **fixa** por classe/sexo + poucas camadas fáceis (arma, elmo, escudo, capa) | 💸💸 | melhor equilíbrio |

### Decisão: **Híbrido (C)**
- **Camadas trocáveis (baratas de encaixar)** = peças "soltas" do corpo: **WEAPON, SHIELD, HELMET,
  cape/capa**. São as que ficam por cima sem depender da musculatura.
- **Parte do "look" fixo da classe/tier (não item-por-item)** = peças coladas no corpo: **ARMOR,
  PANTS, BOOTS, GLOVES, SHOULDER**. Variam por **tier de armadura/classe**, não por item individual.
- **Animação mínima primeiro:** só **idle + ataque** (a vitrine é a battle replay, não andar no mapa)
  — corta o custo de animação pela metade. Hurt/death depois.

> Consequência: a tabela da §2 ganha a coluna "camada trocável × look fixo". O paper-doll "puro"
> (item-por-item em tudo) fica como **objetivo de longo prazo** (Caminho A), se houver orçamento de arte.

---

## 1. Conceito

**Paper doll** = corpo-base + camadas de roupa/armadura/arma, todas com **layout idêntico**
(mesmo tamanho de frame, mesma ordem de quadros, mesma origem). Como tudo alinha, montar o
personagem é só uma sequência de `drawImage` na **ordem de z-index** correta:

```js
// todas as folhas: mesmo frameW/frameH e mesmo índice de animação → alinham sozinhas
ctx.drawImage(baseFrame,     x, y) // corpo nu
ctx.drawImage(pantsFrame,    x, y)
ctx.drawImage(bootsFrame,    x, y)
ctx.drawImage(armorFrame,    x, y)
ctx.drawImage(glovesFrame,   x, y)
ctx.drawImage(shoulderFrame, x, y)
ctx.drawImage(helmetFrame,   x, y)
ctx.drawImage(weaponFrame,   x, y) // z varia por animação (atrás no recuo, na frente no golpe)
```

O equip do jogador (já existe no backend) decide **quais camadas** desenhar.

---

## 2. Slots do backend → camadas do sprite

Slots reais em `ItemType` (enum), classificados pela **decisão híbrida (§0)**:

| `ItemType` | Tratamento | z-index | Observação |
|---|---|---|---|
| (corpo-base) | 🧍 fixo por classe/sexo | **0** | sprite detalhado; pele/sexo do personagem |
| `WEAPON` | 🔄 **camada trocável** | z dinâmico | mão de ataque; ver §3 (tipo de arma) |
| `SHIELD` | 🔄 **camada trocável** | z dinâmico | mão de defesa; frente/trás por animação |
| `HELMET` | 🔄 **camada trocável** | 60 | "solto" da cabeça; cobre/recorta o cabelo |
| (capa/cape) | 🔄 **camada trocável** | −5 / dinâmico | atrás do corpo; barata de encaixar |
| `ARMOR` | 🎽 **look fixo por tier** | (no corpo) | varia por tier de armadura, não por item |
| `PANTS` | 🎽 look fixo por tier | (no corpo) | idem |
| `BOOTS` | 🎽 look fixo por tier | (no corpo) | idem |
| `GLOVES` | 🎽 look fixo por tier | (no corpo) | idem |
| `SHOULDER` | 🎽 look fixo por tier | (no corpo) | idem |
| `RING` | ❌ sem visual | — | só ícone/stat na UI |
| `NECKLACE` | ❌ sem visual | — | idem (camada de pescoço = opcional futuro) |
| (cabelo) | 🧍 fixo | 55 | abaixo do elmo; recorta quando há `HELMET` |

> **🔄 trocável** = PNG por item, desenhado por cima (fácil). **🎽 look fixo por tier** = o conjunto
> de corpo (peito/calça/bota/luva/ombro) é uma **arte só por tier/classe**, não um item por camada —
> é o que torna o estilo detalhado viável (§0). WEAPON e SHIELD precisam de **z dinâmico por quadro**
> (arma vai atrás no "armar", à frente no impacto).
>
> **Caminho A (futuro):** se houver orçamento de arte, ARMOR/PANTS/BOOTS/GLOVES/SHOULDER migram de
> "look fixo" pra camada trocável item-por-item (paper-doll puro).

---

## 3. Camada de arma × `WeaponType`

O jogo tem 7 tipos de arma ([CLASSES_ARMAS]) e categoria MELEE/RANGED (`WeaponCategory`). A camada
de arma **não é uma só** — cada tipo precisa do próprio PNG + animação de ataque coerente:

| `WeaponType` | Categoria | Camada / animação |
|---|---|---|
| Sword | MELEE | slash |
| Greatsword | MELEE | slash pesado (2 mãos) |
| Axe | MELEE | slash |
| Spear | MELEE | thrust (estocada) |
| Short Bow / Long Bow | RANGED | bow draw + quiver |
| Crossbow | RANGED | aim/shoot |
| Mace (Merchant) | MELEE | blunt |

Mínimo viável: **1 animação de melee genérica + 1 de ranged**, e refinar por tipo depois.
A `WeaponCategory` já guardada em `InventoryItem.weaponCategory` resolve qual usar.

---

## 4. Fontes de asset (resumo — ver discussão)

### Pro estilo detalhado/realista (alvo — §0)
| Fonte | Tipo | Nota |
|---|---|---|
| [Pixel Art Full-Body FANTASTIC Characters (Fab)](https://www.fab.com/listings/25611ae0-281b-4810-9df0-9ccb59c39f54) | 150 personagens inteiros, até 1024×1024 | **Looks fixos** (Caminho B); alta-res + corpo inteiro. **Não é modular** |
| [6000+ Heavy Armor RPG Modular (PIXEL_1992)](https://pixel-1992.itch.io/6000-fantasy-pixel-art-heavy-armor-ultimate-rpg-pack) | armadura modular 128×128/64×64 (torso/elmo/luva/bota) | camadas de armadura detalhadas (combina c/ Caminho A/C) |
| [Modular RPG Characters (edermunizz)](https://edermunizz.itch.io/pixel-art-modular-rpg-characters) | base + camadas (PNG/PSD/Aseprite) | mais detalhado que LPC; bom meio-termo |
| **Comissão / artista próprio** | sob medida | único jeito de um paper-doll **detalhado E realista** de verdade (Caminho A) |
| **IA (Aseprite + modelo)** | gerado | ⚠️ inconsistência frame-a-frame + **declaração obrigatória na Steam** + licença nebulosa |

### Estilizado/pequeno (referência — NÃO é o alvo, mas modularidade pronta)
| Fonte | Licença | Nota |
|---|---|---|
| [Mana Seed Character Base](https://seliel-the-shaper.itch.io/character-base) | comercial limpa | paper doll pronto, mas **chibi 32px** (não é o estilo querido) |
| [Universal LPC Generator](https://liberatedpixelcup.github.io/Universal-LPC-Spritesheet-Character-Generator/) | **CC-BY-SA / GPL** ⚠️ | ShareAlike viral — risco p/ jogo comercial fechado |

> ⚠️ **Licença é gate de lançamento.** Evitar LPC (CC-BY-SA/GPL) como base do jogo comercial.
> Preferir packs com **licença comercial limpa** (Fab/itch.io pagos) ou arte própria/comissionada.
> **Guardar `assets/CREDITS.md`** com licença + crédito de cada pack desde o dia 1 (Valve cobra).
> Se usar IA em qualquer parte → **declarar no Steamworks** (campo obrigatório de conteúdo gerado por IA).

---

## 5. Requisitos técnicos (pra tudo alinhar)

1. **Mesmo frame size** em todas as folhas (ex.: 64×64 ou 128×128) — a base manda.
2. **Mesma grade de animação** (mesma ordem/quantidade de quadros por ação: idle, walk, attack, hurt,
   death). Camada que não cobre uma ação = problema; preferir packs que cobrem todas.
3. **Mesma origem/ponto de ancoragem** — desenhar tudo no mesmo (x,y).
4. **z-index por animação** pra arma/escudo (tabela de exceções por ação).
5. **Recorte do cabelo** quando há `HELMET` (ou usar elmos que cobrem).
6. **Tom de pele / sexo** = escolha do personagem → seleciona a folha-base.

---

## 6. Plano de implementação (quando for fazer)

> Plano alinhado ao **Híbrido (§0)**: base detalhada fixa + camadas trocáveis (arma/escudo/elmo/capa);
> corpo de armadura = look por tier. Animação **idle + ataque** primeiro.

- **Fase 0 — Prototipar layering:** 1 base detalhada (idle + ataque) + 2 camadas trocáveis (weapon +
  helmet), `drawImage` em z-order no canvas. Provar que alinha no estilo detalhado.
- **Fase 1 — Looks fixos por tier:** definir N tiers de armadura (corpo inteiro) por classe/sexo;
  o equip do jogador (ARMOR/PANTS/… ) **escolhe o look**, não soma camadas.
- **Fase 2 — Camadas trocáveis:** WEAPON + SHIELD + HELMET + capa por cima do look; z dinâmico de
  arma/escudo por quadro.
- **Fase 3 — Variedade de arma** (slash/thrust/bow) por `WeaponType`/`WeaponCategory`.
- **Fase 4 — Cosmético:** tons de pele/cabelo + skins vendáveis (liga com [POSICIONAMENTO] C1).
- **Fase 5 (longo prazo, Caminho A):** migrar corpo de armadura de "look fixo" → camada
  item-por-item, **se houver orçamento de arte** (paper-doll puro detalhado).

---

## 8. Ferramenta escolhida — PixelLab (IA) + plano de áudio

**Decisão (2026-06-08):** gerar a arte via **[PixelLab](https://www.pixellab.ai/)** (IA de pixel art).
Tem **MCP oficial** ([github.com/pixellab-code/pixellab-mcp](https://github.com/pixellab-code/pixellab-mcp))
→ com o token ligado no Claude Code, o agente gera os sprites e liga no `battleArena.js` daqui ("vibe
coding"). Config: `claude mcp add --transport http pixellab https://api.pixellab.ai/mcp --header "Authorization: Bearer TOKEN"`.

### Licença / Steam
- ✅ **Você é dono dos direitos** das criações; uso comercial incluído nos planos pagos. Única
  restrição: não treinar outros modelos com as imagens; uso programático só pela API oficial.
- ⚠️ **Declarar uso de IA no Steamworks** (campo obrigatório).

### Limites de tamanho (IMPORTANTE — planejar a arte em torno disso)
- **Animação trava em 128×128** (esqueleto até 256×256), **qualquer tier**.
- **Cena/imagem estática** até **400×400** (Tier 2 "Artisan", ~$22–29/mês).
- Sprite maior = menos frames por geração (32px→16 frames; 128px→4 frames). Modelos bons = ~40 créditos.
- **Conclusão:** personagens de batalha em **~128×128**; backgrounds estáticos até 400×400.

### O que o PixelLab NÃO faz
- ❌ **Áudio.** É só pixel art. **Som da batalha** = arquivo externo (ElevenLabs / Pixabay / itch.io)
  + **eu ligo no `battleArena.js`** (Web Audio, SFX sincronizado aos `battleEvents`). Ver [BATALHA_ANIMADA].
- ❌ Efeito de **desmembramento** pronto — gera só as **peças**; o efeito é código ([DESMEMBRAMENTO]).

### Roteiro de teste no site (validar antes de produzir — economiza crédito)
1. **1 corpo-base** (idle, 2 frames) → o estilo detalhado saiu bom?
2. **1 ataque** (4 frames) nesse corpo → **consistência frame-a-frame** aguentou? *(teste decisivo)*
3. **1 background** (arena) → o fundo ficou no clima?
4. **Inpainting trocando a arma** → paper doll é viável?

### Prompts prontos (colar no site — EN, estilo grimdark)
**Corpo-base (1 por sexo):**
```
Muscular male warrior, bare-chested, realistic human proportions, side view,
standing combat idle stance, dark gritty grimdark medieval fantasy, detailed
shading, moody torchlight, transparent background
```
```
Athletic female warrior, leather chest wrap, realistic human proportions, side
view, combat idle stance, grimdark medieval fantasy, detailed shading, transparent background
```
**Looks por classe (Caminho B):**
```
Warrior in worn iron plate armor, heavy pauldrons, side view, realistic proportions,
battle-ready stance, dark grimdark medieval, weathered metal, transparent background
```
```
Archer in dark leather armor with hood, quiver on back, side view, realistic
proportions, agile stance, grimdark medieval fantasy, transparent background
```
```
Merchant-mercenary in reinforced traveler's coat, side view, holding a heavy mace,
realistic proportions, gritty medieval, transparent background
```
**Camadas trocáveis (item solto):**
```
Iron longsword, side view game item, blood on the blade, dark medieval, transparent background
```
```
Battle-worn steel helmet with face guard, side view game item, grimdark medieval, transparent background
```
```
Round wooden shield with iron rim, battle-damaged, side view game item, dark medieval, transparent background
```
**Animações (rodar sobre o corpo-base):** `subtle breathing idle, 2 frames` ·
`overhead sword slash attack, 4 frames` · `draw bow and release arrow, 4 frames` ·
`flinch and stagger backward from hit, 2 frames` · `collapse to the ground defeated, blood splatter, 4 frames`

**Backgrounds (1 por `scene` da battle replay):**
```
arena    → Blood-stained medieval arena pit, sandy ground, wooden palisade, ominous overcast sky, side-scrolling battle backdrop, dark gritty pixel art
fortress → Cursed fortress courtyard, dark stone walls, burning torches, drifting fog, side-view battle background, grimdark pixel art
cave     → Crystal cavern, glowing blue crystals, damp jagged rock, side-view battle background, moody pixel art
coast    → Rocky gray coastline, cold crashing sea, overcast sky, side-view battle background, somber pixel art
sea      → Blessed shallow sea shore, turquoise water, soft light, side-view battle background, pixel art
tower    → Dark tower interior, worn stone stairs, torchlight and shadows, side-view battle background, grimdark pixel art
```
**Peças de gore ([DESMEMBRAMENTO]):**
```
Severed muscular human arm, dripping blood at the cut, side view game item, dark gritty pixel art, transparent background
```
```
Severed human leg, bloody stump, side view game item, grimdark pixel art, transparent background
```
```
Blood splatter and spray effect, multiple frames, dark red, side view, pixel art VFX, transparent background
```
```
Warrior collapsing dismembered, heavy blood spray, death animation, 4 frames, side view, grimdark medieval pixel art, transparent background
```
```
Muscular male warrior with right arm severed, bloody shoulder stump, side view, combat stance, grimdark medieval pixel art, transparent background
```

---

## 9. Dependências / o que já existe

- ✅ `ItemType` (10 slots) + `WeaponType`/`WeaponCategory` ([CLASSES_ARMAS]) → fonte de quais camadas.
- ✅ `InventoryItem` com equip por slot → estado do boneco.
- ✅ `battleArena.js` (canvas 2D, sprites + fundo por local) → motor onde as camadas entram.
- ✅ Catálogo cosmético no SoulStone ([POSICIONAMENTO] C1) → skins de camada vendáveis (futuro).
