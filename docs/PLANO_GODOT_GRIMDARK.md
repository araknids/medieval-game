# Plano — Pós-processo GRIMDARK (Godot 3D, Fase 4) — [GODOT_GRIMDARK]

> Amarra o **clima sombrio/medieval** por cima dos 7 cenários de luta de uma vez, sem
> reescrever os 4 perfis de luz (night/dusk/day/dungeon). Cada perfil mantém seu céu/sol;
> o grimdark é só o **acabamento** somado por cima.

## Decisão de design

Duas camadas, em lugares diferentes do pipeline:

| Camada | Onde | O que faz | Por quê ali |
|--------|------|-----------|-------------|
| **Environment grade** | `Scenery.grimdark_grade(env)` — chamado no fim dos 4 perfis de luz | **glow/bloom** (só emissivos fortes: tochas, sangue, minério), **SSAO** (oclusão de contato → cantos encardidos), **exposição** levemente pra baixo | Bloom e SSAO precisam do pipeline 3D (Forward+); não dá pra fazer num shader de tela |
| **Overlay de tela** | `Scenery._grimdark_overlay(host)` — `CanvasLayer`(layer 0) + `ColorRect` full-screen com shader que lê `hint_screen_texture` | **vinheta** (cantos escuros), **dessaturação**, **contraste**, **tint** sépio/frio, **grão** animado | É um "filtro" uniforme por cima de QUALQUER mapa, independente da luz da cena |

A UI da batalha fica num `CanvasLayer` layer 1 (criado **depois**, em `_make_ui`) → desenha **por cima** do overlay (layer 0), então os números de dano/vitória/câmera continuam limpos e legíveis. Os popups in-world (Label3D) ficam **dentro** do render 3D → recebem o grade junto (correto).

## Knobs (defaults — calibrar no playtest por screenshot)

Environment (`grimdark_grade`): `glow_intensity 0.55`, `glow_bloom 0.12`, `glow_hdr_threshold 0.95` (só o bem brilhante floresce), `ssao_intensity 1.8`, `tonemap_exposure 0.92`.

Shader (`GRIMDARK_SHADER` uniforms): `vignette 0.5`, `vradius 0.55`, `saturation 0.85`, `contrast 1.08`, `tint (1.03, 0.99, 0.92)`, `grain 0.035`.

## Toggle (A/B)

`build(host, scenario, rng, combat_r, grim := true)` — `grim=false` desliga as DUAS camadas (volta ao look "cru" de cada perfil; night/dusk/dungeon mantêm o glow original que já tinham). Exposto como `@export var grimdark` em `World.gd` (viewer por cenário) e `BattleReplay.gd` (batalha). Default **ligado**.

## Escopo

Aplica aos 7 mapas do `Scenery` (os que o sorteio de mapa usa em toda luta). O `coliseum` legado (fallback procedural antigo no `BattleReplay`, fora do sorteio) **não** recebe o grade — pode ser somado depois se voltar a ser usado.

## Renderer

Projeto é **Forward+** (`project.godot` → "Forward Plus"). SSAO/glow plenos. Se um dia exportar Web (Compatibility), SSAO é ignorado de boa (degrada sem erro); glow e o overlay de tela continuam funcionando.
