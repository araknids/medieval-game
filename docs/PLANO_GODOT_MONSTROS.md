# Plano — Monstros 3D no cliente Godot — [GODOT_MONSTROS]

> Bundle Quaternius (30 `.glb`, gitignored em `godot-client/assets/monsters/`). O inimigo
> da batalha vira um monstro do bundle quando o NOME do backend é de uma besta; humanoides
> (cavaleiro/bandido/orc) e PvP continuam no rig humano do player.

## Helper compartilhado — `Monsters.gd`

RefCounted (sem `class_name`, igual `Scenery.gd`). Usado pelo `MonsterViewer` (calibração) e pelo `BattleReplay` (inimigo).

- **Auto-fit** `fit(node, target_h, hover)` — mede o **AABB** de todas as malhas (em espaço local), escala pra `target_h` e encosta o pé no chão (`ground_y`) ou faz **flutuar** `hover` m (voador). Retorna `{scale, height, ground_y}`. Fim do `monster_scale` manual.
- **Voadores** `FLYERS` (lista EXPLÍCITA — Alpaking/Alpaking Evolved/Armabee/Armabee Evolved/Demon/Dragon) flutuam `HOVER_H` (0.9m). Sem auto-detecção por anim (vários têm clip "fly" mas andam). `is_flyer(name, node)`.
- **Roster de tamanho** `SIZE` (small 1.3 / normal 1.8 / big 2.6 / boss 3.4) → `size_for(name)`. Só os fora do normal estão no mapa.
- **Idle** `find_idle`/`play_idle` — acha a anim idle por palavra-chave e toca em loop.

## Mapa nome-do-inimigo → encenação — `Monsters.pick_for(name)`

Retorna `{kind:"human"}` ou `{kind:"monster", file, target_h, hover}`.

1. Tira o **ícone de elemento** (emoji prefixado) — `_strip_icon`.
2. Casa por **palavra inteira** (hífen→espaço; `" dragon "` em `" young dragon "`). Evita falso-positivo de substring (ex.: `"ape"` ⊄ "esc**ape**d"). `NAME_MAP` = pares `[palavra, arquivo]`, **primeira que casar vence** (específico antes de genérico).
3. Sem palavra de besta → **humano** (cavaleiro/bandido/orc/guarda/PvP/desconhecido).
4. Com besta → monstro; se o nome tem **palavra de boss** (`BOSS_WORDS`: tyrant/behemoth/king/evolved/champion…) → altura ×1.25.

**Vocabulário do backend** (fonte: sweep dos services): zona NPC (Wild Wolf, Orc Warrior, Young Dragon, Lesser Demon…), chefe errante (Tower Tyrant…), torre 1-50 (humanoides/eldritch), quests por reino (Sea Serpent, Stone Golem, Crystal Aberration…), arena = **username** (humano). ~70 nomes, todos strings fixas no `BattleSimulator` (`spawns[1].actor`).

## Integração no `BattleReplay`

- `const Monsters := preload(...)` + `var mons := Monsters.new()`.
- `_build_fighters`: inimigo = `enemy_monster` (override manual) **ou** `pick_for(force_enemy_name || rname)`. Player sempre humano.
- `_make_fighter(..., monster_meta := {})`: vazio = humano; senão `mons.instance` + `mons.fit` (escala/hover do roster). Guarda `base_y` (movimento preserva o hover do voador — `_step_toward`/`_move_kite`/`_dodge_roll`/`_stand_over`/reset de kiting usam `base_y` em vez de `0`) e `bar_off` (barra de vida acima da cabeça, independe do hover).
- Monstro é sempre **melee** (sem arco/kite/dress/arma). Usa as **próprias anims** (`_monster_anim_map` mapeia os papéis do replay por palavra-chave).

### Testar sem PvE real
`@export force_enemy_name` finge o nome do inimigo: "Young Dragon"→Dragon, "Stone Golem"→Goleling Evolved, "Orc Warrior"→humano. (Arena real = humano, porque o oponente é username.)

## Calibração — `MonsterViewer.tscn`
Cicla os 30 (←/→), auto-escalados, idle tocando; ↑/↓ ajusta hover ao vivo; ESPAÇO = turntable. Palco neutro.

## Passo 3 (pendente)
`BattleReplay` hoje só puxa **arena** (PvP). Falta puxar luta **PvE** (zona/torre/quest) do backend pra o monstro mapeado aparecer de verdade em jogo. Números (tamanhos/hover/×1.25 boss) são placeholders p/ tuning.
