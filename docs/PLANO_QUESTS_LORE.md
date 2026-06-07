# Plano — Reskin narrativo das Quests (semear a lore) [QUESTS_LORE]

> Reescreve o **conteúdo** das quests existentes pra contar a história de [LORE.md](LORE.md) **na
> entrelinha** — nada é dito ao player; quem liga os pontos descobre, quem não, leva o choque na Torre.
> Complementa [PLANO_QUESTS.md](PLANO_QUESTS.md) (o **sistema** daily/lock — não muda aqui).
>
> **Abordagem:** só reskin (zero sistema novo, farmável como hoje). **Tom:** sutil.
> Texto exibido ao player = **inglês** (regra de UI); prosa do doc = PT.

## O que muda no código (mínimo)
1. **`KingdomQuestType`** — reescrever os 30 `displayName` + **adicionar 1 campo `flavor`** (a linha onde
   mora a semente) + o param no construtor.
2. **`Kingdom.lore`** — reescrever as 5 descrições de reino.
3. **UI** — mostrar o `flavor` no card da quest (é onde o player lê a pista ao escolher a quest).
4. **Onboarding** — o briefing da guarnição (texto de chegada).
5. (Opcional) reskin dos nomes de monstro no `KingdomQuestNarrator` — os atuais já servem.

## A espinha (camada por camada): Mar → Terra → Fronte → Torre
| Movimento | Reino(s) | Camada semeada |
|---|---|---|
| **Mar** | Bone Gorge, Blessed Sea | *o mar tem vontade; a "bênção" é vazamento; ele já cuspiu um homem de volta* (semente do Xamã) |
| **Terra** | Black Iron Mines | *a riqueza vem de algo VIVO — quente, que sangra, que pulsa* |
| **Terra funda** | Crystal Grottoes | *não é coisa, é uma MENTE — ela sonha, fala, e tem fiéis* |
| **Fronte** | Cursed Fortress | *a corrupção FLUI da Torre; o Rei serve algo* |
| **Topo** | A Torre | a verdade (Xamã no andar 9 = "o homem que o mar cuspiu"; Arka no 10) |

---

## Onboarding — briefing da guarnição (Coroa de Arka)
> *"Coroa de Arka was the jewel of the new world — gold in the hills, fish in the tides, more than the
> Old Crown ever dreamed. Then the beasts came, and the King shut himself in his tower and did not come
> down. The merchants still count their coin behind barred doors. We begged the Old Crown for soldiers.
> They sent us recruits. They sent us you. Earn your place, climb the King's tower, and bring him home."*

*(Sutil: riqueza absurda, mercadores gananciosos/cegos, Rei sumido na Torre, você é o socorro.)*

---

## Ato 1 — O Mar

### 🎣 Bone Gorge (`FISHING`)
**Realm lore:** *"A drowned gorge where the tide gives more than any net should hold — and gives back more
than the living. Fish here long enough and you learn not to look too closely at the catch."*

| # | Novo nome | Flavor (a semente) |
|---|---|---|
| 1 | **Generous Waters** | "The nets come up full before you even cast. The sea wants you fed — and wants you to stay." |
| 2 | **The Reef That Grew** | "This reef wasn't on last year's charts. It's closer to shore now. It'll be closer still next season." |
| 3 | **What the Tide Gathers** | "Every wreck on this coast drifted to the same cove, prows all pointing the same way — inward, and down." |
| 4 | **The Reaching Dead** | "The pirates didn't drown fleeing the beasts. They died reaching for the deep, hands out to the water, like they were called." |
| 5 | **Too Deep** | "The further down you dive, the warmer the water gets. A cold sea shouldn't have a warm heart beating somewhere below." |
| 6 | **The Man the Sea Spat Back** ⭐ | "The old hands tell of a fisherman lost years ago, declared dead — until the tide laid him on the sand, breathing, changed. He walked inland and never stopped. The sea keeps nothing it means to use." |

⭐ **Semente do Xamã** — paga no **andar 9** da Torre (o Xamã *é* esse homem).

### 🐟 Blessed Sea (`MAR_ABENCOADO`)
**Realm lore:** *"Sacred waters where the fish restore the life of those who eat them. No one asks how a
dead-cold sea learned to heal. The drowned here do not rest — they reach for the deep."*

| # | Novo nome | Flavor |
|---|---|---|
| 1 | **The Tide That Won't Stay Clean** | "You clear the rot, and by morning the tide has laid down more — gently, patiently, like it has all the time in the world." |
| 2 | **Already Blessed** | "The priests bless the water. The water was already brighter than any prayer they know." |
| 3 | **The Pilgrims** | "They come from every shore to drink the healing tide. Not one asks why a dead sea would want to keep them alive." |
| 4 | **The Pale Reef** | "The coral here is the wrong color — pale, and warm, and it swells and settles, slow, like something breathing in its sleep." |
| 5 | **The Drowned** | "They don't come to kill. They pass you by, hands out to the surf, wanting back to the deep. You cut them down, and they seem to thank you." |
| 6 | **Below the Sacred Reef** | "Under the holiest water the light comes from below, not above. The fish gather there, still as glass, all of them facing down." |

---

## Ato 2 — A Terra

### ⛏ Black Iron Mines (`MINING`)
**Realm lore:** *"The deeper the shaft, the richer the vein — and the warmer the stone. The old miners
say the mountain has a pulse. The new ones learn not to mention it."*

| # | Novo nome | Flavor |
|---|---|---|
| 1 | **Deeper** | "Every season they sink the shaft lower, and every season the ore is richer for it. No one wonders where it ends." |
| 2 | **The Hum** | "The lowest galleries hum — low and steady, almost a heartbeat. The veterans swear they stopped hearing it years ago." |
| 3 | **The Warm Walls** | "The walls down here are warm to the touch, and they give a little when you brace them — as if they'd rather not be propped." |
| 4 | **The Bleeding Vein** | "Where the pick bites, the ore runs red and wet before it dries — as if the mountain bleeds where you wound it." |
| 5 | **What Grows Below** | "The things in the bottom shaft didn't wander in. They grew there, out of the walls, wearing the shape of the rock." |
| 6 | **What Stirs in the Stone** | "At the end of the vein the wall is smooth — not rock. It yields when you press it. The ore here doesn't break free; it weeps." |

### 🔎 Crystal Grottoes (`GRUTAS_DE_CRISTAL`)
**Realm lore:** *"Caverns where gems grow like frost, beautiful past reason. Prospectors who linger too
long stop digging — and start listening."*

| # | Novo nome | Flavor |
|---|---|---|
| 1 | **What Dreams the Gems** | "The crystals grow back overnight, in the very same shapes, as if something below keeps dreaming them up." |
| 2 | **Mapping What Moves** | "The grotto is never the same twice. The tunnels rearrange when no one is watching — always leading further down." |
| 3 | **Crystallized Blood** | "Crack a fresh geode and the inside is red and slick before it sets. The gems are blood, caught and hardened in the dark." |
| 4 | **The Breathing Fissure** | "No cold draft comes out of the fissure. Warm air goes IN, breath after slow breath, as though the cavern is drawing it down." |
| 5 | **The Ones Who Listened** | "The prospectors here put down their tools and knelt, repeating a word no one taught them. The crystal gave them a voice, and a master to obey." |
| 6 | **The Voice in the Stone** | "It doesn't roar. It speaks — your name, the things you want — and for one breath you want to lower the blade and simply listen." |

---

## Ato 3 — A Fronte

### ⚔ Cursed Fortress (`COMBAT`)
**Realm lore:** *"The old fortress in the Tower's shadow, where the corruption runs thickest. The soldiers
sent to hold the line came back — but they came back wrong."*

| # | Novo nome | Flavor |
|---|---|---|
| 1 | **The Wrong Side** | "The monsters don't lay siege to the fortress. They pour out of it — from the gate that faces the King's tower." |
| 2 | **Those Who Stayed** | "The prisoners in the dungeon are the old garrison. They didn't desert — they were changed where they stood, every face turned toward the Tower." |
| 3 | **In the Tower's Shadow** | "From the ramparts you can see it: no light in the King's tower for a long, long time now — only a faint glow from the lowest floor, the wrong shade of pale." |
| 4 | **The Faithful** | "It isn't an enemy army camped at the gates. It's pilgrims and soldiers who walked to the Tower and knelt. They will not let you pass to it." |
| 5 | **The Last Door** | "The keep's lord sealed it from the inside, they say, 'to keep something in.' Whatever it was, it seeps under the door all the same." |
| 6 | **The Cursed Knight** | "The strongest of the fallen was the King's own champion, set to guard the Tower. He doesn't fight to beat you — he fights to keep you OUT." |

---

## Pagamentos (onde as sementes fecham)
- **Andar 1 da Torre** → a isca já escrita (o ar que sobe da fenda, a marca redonda que o Rei cobriu).
- **Andar 9 (Xamã)** → "The Man the Sea Spat Back": o pescador que o mar devolveu é ele.
- **Andar 10 (Arka)** → tudo aponta pra Torre/o Rei nos atos 2–3; a verdade (ganância, a nova mão) fecha aqui.
- **S2** → o que o Mar/Terra/Cristal sussurravam (vivo, fundo, com mente) = o Deus que Dorme, lá embaixo.

## Notas de implementação
- `displayName` (30) reescritos + novo `String flavor` em `KingdomQuestType` (+ construtor).
- Reescrever os 5 `Kingdom.lore`.
- Expor `flavor` no DTO da lista de quests + mostrar no card (UI).
- Onboarding: achar/criar o texto de chegada (primeiro login) → o briefing acima.
- Monstros (`KingdomQuestNarrator.MONSTERS`): manter (já temáticos) ou leve reskin futuro.
- i18n: textos em EN agora; tradução PT depois.

## Status: IMPLEMENTADO ✅
- `KingdomQuestType` — 30 `displayName` reskinnados + campo `flavor` (1 linha/quest) + Luna.
- `Kingdom.lore` — 5 reinos reescritos.
- `KingdomController` — `flavor` exposto no DTO da quest; UI mostra no card (itálico, abaixo do título).
- 598 testes verdes. Nomes/flavors em EN (i18n PT depois).

## Em aberto
- **Briefing de onboarding** (o texto de chegada da guarnição) — **adiado**: o jogo não tem fluxo de
  intro/primeiro login hoje. Quando existir uma tela de chegada, plugar o briefing (já escrito acima).
- Taste pass nos nomes/flavors (ajuste fino opcional).
