# Plano — Paper Doll (personagem em camadas)

> **Status:** backlog visual (não-código por enquanto). Implementar quando for trocar o sprite do
> guerreiro por um boneco montado a partir do equipamento.
> **Objetivo:** desenhar um **corpo-base nu** + empilhar cada peça de equipamento como uma **camada
> de sprite** (PNG separado, mesmo tamanho de frame e mesmos quadros de animação). "Equipar item" no
> jogo vira "desenhar mais uma camada" — sem redesenhar o personagem inteiro.
> **Onde encaixa:** o motor já existe — `battleArena.js` (canvas 2D) e a vitrine do personagem.

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

Slots reais em `ItemType` (enum) + acessórios sem representação visual:

| `ItemType` | Vira camada? | z-index | Observação |
|---|---|---|---|
| (corpo-base) | ✅ sempre | **0** | sprite nu; pele/sexo do personagem |
| `PANTS` | ✅ | 10 | |
| `BOOTS` | ✅ | 20 | |
| `ARMOR` | ✅ | 30 | peça central (peito) |
| `GLOVES` | ✅ | 40 | |
| `SHOULDER` | ✅ | 50 | ombreira por cima do peito |
| `HELMET` | ✅ | 60 | normalmente esconde/cobre o cabelo |
| `SHIELD` | ✅ | z dinâmico | mão de defesa; muda de frente/trás por animação |
| `WEAPON` | ✅ | z dinâmico | mão de ataque; ver §3 (tipo de arma) |
| `RING` | ❌ | — | acessório de stat; sem camada (cap. ícone na UI) |
| `NECKLACE` | ❌ | — | idem (ou camada futura no pescoço, opcional) |
| (cabelo) | ✅ opcional | 55 | abaixo do elmo; some/recorta quando há `HELMET` |

> z-index são valores-base; SHIELD e WEAPON precisam de **z dinâmico por quadro de animação**
> (ex.: arma vai pra trás do corpo no frame de "armar o golpe", pra frente no impacto).

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

| Fonte | Licença | Nota |
|---|---|---|
| [Mana Seed Character Base](https://seliel-the-shaper.itch.io/character-base) (Seliel) | comercial limpa | ⭐ recomendado p/ Steam; feito p/ paper doll; base grátis + armas pagas |
| [Universal LPC Generator](https://liberatedpixelcup.github.io/Universal-LPC-Spritesheet-Character-Generator/) | **CC-BY-SA / GPL** ⚠️ | ótimo p/ prototipar; **ShareAlike é viral** — cuidado em jogo comercial fechado |
| [LPC Character Bases](https://opengameart.org/content/lpc-character-bases) | idem ⚠️ | corpos nus |
| [Pixel Medieval Kingdom](https://ismartal.itch.io/pixel-medieval-kingdom) | conferir | medieval explícito, modular |
| [Modular RPG Characters (edermunizz)](https://edermunizz.itch.io/pixel-art-modular-rpg-characters) | conferir | PNG + Aseprite |

> ⚠️ **Licença é gate de lançamento.** LPC é CC-BY-SA/GPL (ShareAlike pode forçar abrir os derivados).
> Pra Steam comercial fechado, preferir Mana Seed / packs itch.io com licença comercial limpa.
> **Guardar `assets/CREDITS.md`** com licença + crédito de cada pack desde o dia 1 (Valve cobra).

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

- **Fase 0 — Prototipar layering:** 1 base + 2 camadas (armor + weapon), montar `drawImage` em
  z-order no canvas. Provar que alinha numa animação de ataque.
- **Fase 1 — Mapear equip→camada:** ler o equip do jogador (slots `ItemType`) e montar a lista de
  camadas. Acessórios (RING/NECKLACE) ficam fora.
- **Fase 2 — z dinâmico de arma/escudo** por quadro de animação.
- **Fase 3 — Variedade de arma** (slash/thrust/bow) por `WeaponType`/`WeaponCategory`.
- **Fase 4 — Cosmético:** tons de pele, cabelo, e (liga com [POSICIONAMENTO] C1) skins vendáveis.

---

## 7. Dependências / o que já existe

- ✅ `ItemType` (10 slots) + `WeaponType`/`WeaponCategory` ([CLASSES_ARMAS]) → fonte de quais camadas.
- ✅ `InventoryItem` com equip por slot → estado do boneco.
- ✅ `battleArena.js` (canvas 2D, sprites + fundo por local) → motor onde as camadas entram.
- ✅ Catálogo cosmético no SoulStone ([POSICIONAMENTO] C1) → skins de camada vendáveis (futuro).
