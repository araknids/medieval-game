# Plano — Ficha do Personagem (fusão Personagem + Inventário + Habilidades) [FICHA_PERSONAGEM]

## Objetivo
Fundir as 3 telas (`Character` / `Inventory` / `Abilities`) numa **única ficha** estilo MMO
(paper-doll): boneco 3D no centro com **slots de equipamento em volta**, que **atualiza ao vivo**
quando o jogador equipa. Mais compacto, com **hover/tooltip** no lugar de texto inline, **sem scroll
na moldura principal** (só scroll interno no painel da direita). Decidido com o jogador (2026-06-15).

## Decisões (jogador)
- **Layout: lado a lado.** Boneco 3D + slots à ESQUERDA (sempre visível); painel **sub-abado** à
  DIREITA (Mochila / Atributos / Habilidades). Equipar da Mochila → o boneco da esquerda muda na hora.
- **Menu: fundir em 1 "Personagem".** A seção "Personagem" do nav passa a ter só `Character` +
  `Achievements`. `Inventory`/`Abilities` saem do menu (os `.tscn` ficam no projeto, inertes).

## Auditoria (o que motivou)
- **Redundância com a topbar do Shell:** a topbar JÁ mostra busto, nome/título/classe/nível, XP, HP,
  Estamina, ATK/DEF/HP/EVA, os **6 atributos** e moedas. A tela Personagem repetia tudo isso grande →
  maior desperdício de espaço. Na fusão o *display* vai pro paper-doll + hover; a tela foca no que é
  ÚNICO: **equipar, gastar ponto, árvore de skill**.
- **Verbosidade:** efeito de atributo e fontes de cada stat de combate (base/equip/buff/skill/pet/...)
  podem ir pro **tooltip**.
- **Inventário:** lista vertical de cards grandes → equipados viram **slots**; mochila vira **grid compacto**.
- **3D já existe:** `BustView`/`PaperDollLive` já vestem o gear real; o sink `equip_changed` já re-veste.

## Arquitetura
### `ui/DollView.gd` (novo) — paper-doll de CORPO INTEIRO
`SubViewportContainer` (own_world_3d, fundo transparente), reaproveita o pipeline do `BustView`
(mesmas `PIECES`/`BASE_PART`/`apply()`), mas com **câmera de corpo inteiro** (a comprovada do
`PaperDollLive`: pos `(0,1.1,3.2)`, rot `-8°`) e **giro lento** no `_process` (mostra o gear de todos
os lados). `apply(inv_arr)` re-veste sem fetch. Processo congela junto com a tela (Shell desliga
`process_mode` das telas escondidas → 0 CPU fora da Ficha).

### `ui/Character.gd` (reescrita) — a Ficha
- `_ready`: `UiKit.scaffold("👤 Personagem")` → `_build_layout()` (UMA vez; **não** recria o 3D a cada
  render) → `_refresh()`.
- `_refresh`: `batch_get(["/api/warrior","/api/inventory","/api/abilities"])` → guarda → `_apply()`.
- `_apply`: `set_wallet`(topbar) + `set_equipped` + `doll.apply(items)` + identidade + `_update_slots()`
  + `_render_panel()` (sub-aba ativa).
- **Esquerda:** `[col slots L] [DollView] [col slots R]` + nome·classe·nível embaixo.
  - L = `HELMET, ARMOR, GLOVES, PANTS, BOOTS`; R = `WEAPON, SHIELD, SHOULDER, RING, NECKLACE`.
  - Slot = quadrado 60×60 (ícone `slot_<type>`): vazio = apagado + borda bronze + tooltip do nome do
    slot; equipado = ícone aceso + **borda na cor da raridade** + tooltip com nome+stats. **Clique num
    slot equipado = desequipa.**
- **Direita:** barra de sub-abas (`UiKit.filter_row`: 🎒 Mochila / ⚔ Atributos / ✨ Habilidades) +
  `ScrollContainer` (scroll SÓ aqui) com o painel da aba:
  - **Mochila:** filtro de raridade + grid compacto. Card = ícone + nome(raridade) + "Nv·raridade" +
    chip de comparação vs equipado; **stats no tooltip**; botões Equipar / Vender. Clicar Equipar →
    boneco + slots atualizam ao vivo (sem refetch, igual ao Inventory atual).
  - **Atributos:** linhas compactas (ícone+sigla+valor+[+]); **efeito do atributo no tooltip**.
  - **Habilidades:** sem classe → estado vazio; com classe → grid de cards (descrição no tooltip) +
    botão de respec.
- Ações reusam a lógica do `Inventory`/`Abilities` (equip/unequip/sell/learn/respec) com cache local
  em memória; em falha, `_refresh()` re-sincroniza.

### `ui/Shell.gd` (3 ajustes)
1. `SECTIONS` "Personagem" → `[["Character","Personagem"], ["Achievements","Conquistas"]]`.
2. `_wire_screen`: `go_inventory` → `_open("Character")` (era `"Inventory"`).
3. Atalho do dashboard: troca o card "Inventário" por outro (evita link morto) e o tip de `Character`
   passa a descrever a ficha completa.

## Fora do v1 (follow-up)
- Tradução EN das strings novas (sub-abas, tooltips) — PT-only por ora (padrão do projeto).
- Drag-and-drop nos slots (hoje é clique).
- Número do nível sobreposto no slot (hoje só no tooltip).
- Sockets/joias na ficha (continua na Forja).
