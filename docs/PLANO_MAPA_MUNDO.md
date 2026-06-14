# Plano — Mapa-Múndi Interativo (tela Mundo) [MAPA_MUNDO]

## Objetivo

Trocar a **lista** de reinos da tela Mundo por um **mapa-múndi de pergaminho** com os 5
reinos como **regiões clicáveis**. Clicar numa região abre o detalhe daquele reino
(quests + zonas) — exatamente o que já acontece hoje ao expandir um card.

O backend e a navegação **não mudam**: é uma troca de camada visual em cima de fluxos
que já existem.

## Decisões (definidas com o dono)

| Pergunta | Escolha |
|----------|---------|
| Escopo v1 | **Visão geral dos 5 reinos** (não é mapa por-zona nem explorável) |
| Lista atual | **Substitui** a lista pelo mapa |
| Estilo | **Pergaminho / mapa-tesouro** medieval |

## Por que é barato

A tela [`godot-client/ui/World.gd`](../godot-client/ui/World.gd) já tem:
- `kingdoms` = `GET /api/world` (kingdom, displayName, icon, controllingGuild, isMine, lore, bônus).
- `_open(kingdom)` — carrega quests + zonas do reino e renderiza o detalhe.
- `_build_detail(box, kingdom)` — monta quests/zonas/training (todo o miolo).
- Todas as ações (`_start_quest`, `_enter_zone`, chefe, Luna, training…).

A v1 só **substitui o `_render()`** (que hoje empilha `_kingdom_card`) por um mapa com pins.
O resto é reaproveitado intacto.

## Asset

- **Mapa ilustrado externo** (pergaminho pintado, NÃO pixel-art) — testamos o PixelLab primeiro,
  mas o resultado pixel-art não agradou; o dono gerou um mapa ilustrado em outra IA.
- Arquivo: `godot-client/assets/ui/map/world_map.png`, **1536×1024 (3:2)**. Downscale para a tela
  com filtro **linear** (default — é arte de alta-res, não pixel-art; NÃO usar nearest aqui).
- O mapa é **cenário/fundo**. Os 5 reinos são **pins desenhados por cima** em coordenadas
  normalizadas (0..1) cravadas na arte — desacopla arte de hotspot.
- ⚠️ **Reimport**: o `.png.import` pode estar velho (gerado pro mapa de teste). O Godot reimporta
  sozinho ao focar o editor. Enquanto não reimporta, o `_render_map` cai no **fallback** (botões
  por reino) via `ResourceLoader.exists` — a tela nunca quebra.

## Arquitetura Godot (v1)

### Estados da tela
A tela Mundo passa a ter 2 modos:
- **`MAP`** (default): `TextureRect` do mapa + 5 pins. É o novo overview.
- **`DETAIL`**: o detalhe de um reino (reusa `_build_detail`) + botão **🗺 Voltar ao mapa**.

`open_kingdom == ""` → modo MAP; `open_kingdom != ""` → modo DETAIL. (Já existe esse campo;
só muda o que o `_render()` desenha em cada caso.)

### Pins
Um pin por reino, posição **normalizada (0..1)** sobre o mapa (tunar após ver a arte):

```gdscript
# x,y relativos ao mapa (0..1) — cravados na arte ilustrada final (1536×1024)
const PIN_POS := {
    "MINING":            Vector2(0.155, 0.42),  # entrada da mina, montanhas nevadas (oeste)
    "FISHING":           Vector2(0.46, 0.14),   # navios no litoral (norte)
    "MAR_ABENCOADO":     Vector2(0.75, 0.21),   # lago sagrado turquesa (nordeste)
    "GRUTAS_DE_CRISTAL": Vector2(0.42, 0.52),   # espinhos de cristal azul (centro)
    "COMBAT":            Vector2(0.73, 0.58),   # fortaleza maldita escura (sudeste)
}
```

Cada pin é um `Control` clicável (botão/`TextureButton` ou `Control` + `gui_input`) posicionado
via `anchor`/offset proporcional ao tamanho do `TextureRect`. Conteúdo do pin:
- ícone do reino (`k.icon`) + nome curto (`k.displayName`);
- **selo de controle**: 🛡 cor-da-guilda se `controllingGuild`, neutro se não;
- **cadeado** se o nível do jogador não alcança a zona mínima do reino (gate visual).
- hover: leve glow/scale; cursor pointing-hand (já é padrão no projeto).

Clique no pin → `_toggle(kingdom)` / `_open(kingdom)` (já existem) → modo DETAIL.

### Reposicionamento responsivo
Pins recalculam posição no `resized` do `TextureRect` (mapa mantém aspecto; pins seguem
`map_rect.position + PIN_POS * map_rect.size`). Encapsular num `_layout_pins()` chamado no
`resized` e após `_render()`.

## O que NÃO muda
- Backend (`/api/world`, `/api/zones`, etc.).
- `_open`, `_build_detail`, `_quest_card`, `_zone_card`, todas as ações e o replay 3D de batalha.
- A lógica de "tarefa ativa", gates de nível/KO, elemento, training.

## Fora de escopo (v2+)
- **Mapa por reino**: cada reino com seu próprio mapa e as 3 zonas (Safe/PvP/HighRisk) como
  pontos clicáveis. Precisa de +5 a 6 mapas e mais hotspots.
- **Mapa explorável** (andar com o personagem): tileset + câmera + colisão = modo de jogo novo.
- Animação de "viagem" entre pins, fog-of-war, descobrir reino ao subir de nível.

## Passos
1. [arte] Gerar o mapa no PixelLab → salvar `assets/ui/map/world_map.png` (+ `.import` nearest).
2. [código] `World.gd`: separar `_render()` em `_render_map()` (MAP) e o detalhe (DETAIL) com
   botão "Voltar ao mapa"; manter `_kingdom_card`/lista removidos ou atrás de um fallback.
3. [código] `_build_pins()` + `_layout_pins()` (posição normalizada + responsivo).
4. [código] Selo de controle + cadeado de nível no pin.
5. [tune] Ajustar `PIN_POS` olhando a arte gerada.
6. [verificar] Abrir a tela, clicar cada pin, confirmar que abre o reino certo e volta pro mapa.

## Números/coordenadas
Os `PIN_POS` e o tamanho do mapa são **placeholders** — ajustar no playtest visual.
