# PLANO — UI Shell do cliente Godot (barra superior + nav lateral + conteúdo)

Status: **proposta / aguardando decisões**. Objetivo: trocar a navegação "tela cheia que troca tudo"
por um **shell persistente** (HUD sempre visível + navegação lateral em árvore), no estilo dos jogos
do gênero (browser/idle RPG, Diablo-like, management). [MIGRACAO_GODOT]

---

## 1. Auditoria de UI/UX (estado atual)

Arquitetura hoje (`App.gd` + `ui/UiKit.gd`):
- `App` é roteador: Login → Hub → cada tela é um `Control` **tela cheia** que troca a anterior.
- Cada uma das **21 telas** monta o próprio fundo + `UiKit.scaffold` (header com ← / título / carteira / ↻)
  + status + scroll/conteúdo, com coluna máx 920px.
- Navegar = Hub → tela → "←" volta pro Hub. Trocar de sistema = **voltar ao Hub e entrar em outro**.

**Problemas (pensando no gênero):**
1. **Navegação modal de tela cheia** — some o contexto: enquanto está na Loja você não vê HP/estamina/dinheiro.
   No gênero, o padrão é **HUD fixo** + nav lateral: os recursos (o *gate* do jogo é estamina) ficam sempre à vista.
2. **Ida-e-volta pelo Hub** — pra ir da Loja pra Forja: ← Hub → Forja = 2–3 cliques. O loop de alta frequência
   (missão → inventário → forja → loja) fica lento.
3. **Carteira como emoji minúsculo** repetido em cada header — pouca proeminência; recurso central merece barra fixa.
4. **Sem identidade persistente** — nome/título/nível/HP/estamina só aparecem no Personagem. Jogo de stats deveria
   mostrar isso sempre.
5. **Cromo repetido por tela** — 21 telas reconstroem ←/título/↻ e **as 21 re-buscam `/api/warrior`** só pra
   carteira (21 fetches redundantes). Com o shell, o "←" some (a nav está sempre lá) e o warrior é buscado 1x.
6. **Sensação de "pilha de páginas soltas"** em vez de **um app coeso** — os ícones novos ajudaram, mas o frame ainda troca inteiro.
7. **Descoberta** — 19 sistemas espalhados; uma árvore categorizada fixa ensina a estrutura e mostra **onde você está** (destaque do ativo).
8. **Controle/Steam** — já há foco navegável; uma nav lateral com memória de foco (e atalhos L/R) é melhor pro gamepad.

---

## 2. Desenho proposto — "App Shell"

Layout em 3 zonas persistentes (o **combate continua como overlay por cima de tudo**):

```
┌───────────────────────────────────────────────────────────────┐
│ [busto] Nome  ⟨Título⟩  Classe·Lv  [XP▱▱▱]   HP▰▰▰  ⚡▰▰▰   🪙🥈🥉 ◆ │  ← TopBar (~68px, fixa)
├──────────────┬────────────────────────────────────────────────┤
│ ⚔ LUTAR      │                                                 │
│ ▸ Aventura   │                                                 │
│   🌍 Mundo    │              CONTEÚDO DA TELA ATIVA              │
│   💼 Trabalho │         (sem fundo/header próprios —            │
│   ⛪ Templo    │          o shell já fornece tudo)              │
│ ▸ Batalha    │                                                 │
│   🏰 Torre …  │                                                 │
│ ▸ Comércio…  │                                                 │
│ [« recolher] │                                                 │
└──────────────┴────────────────────────────────────────────────┘
   NavTree (~210px, recolhível p/ rail de ícones)      ContentHost
```

### TopBar (sempre visível)
- **Esquerda:** busto (quadrado ~56px) + Nome + **Título** ativo + Classe·Nível + barrinha de **XP**.
- **Centro/direita:** barra de **HP** + barra de **Estamina** (com "cheia em Xmin") — o *gate*, proeminente.
- **Direita:** moedas (ouro/prata/bronze + SoulStone) com os ícones; badges (VIP, buff da taverna/novato).
- Fonte de dados: **1 fetch** de `/api/warrior` no shell, com refresh após cada ação; as telas param de buscar warrior só pra carteira.

### NavTree (lateral, recolhível)
- **LUTAR** como ação destacada no topo.
- Árvore com as 5 seções (Aventura/Batalha/Comércio/Personagem/Social), cada uma **expansível**, itens com
  **ícone** (os `slot_*`/seção que já geramos) + **destaque do ativo**.
- Recolhe pra **rail só-ícones** (botão «) — e em largura < ~900px recolhe sozinho (hambúrguer).
- Implementação: **VBox custom estilizado** (StoneStyle), não o nó `Tree` (theming do `Tree` é limitado p/ esse visual).

### ContentHost
- Hospeda a tela ativa. As telas **largam fundo + header (←/título/↻/carteira)**; renderizam só o conteúdo
  (e, se quiserem, um subtítulo). O shell é dono do fundo, da nav, da carteira e do status/toast.

### Busto (portrait)
- **v1:** placeholder = ícone da classe/`character.png` (rápido).
- **v2:** **busto 3D ao vivo** num `SubViewport` reaproveitando **`PaperDollLive.gd`** (mostra o gear equipado),
  câmera no tronco/cabeça. (own_world_3d isolado, igual corrigimos no menu.)

---

## 3. Migração (incremental, baixo risco)

1. Criar `ui/Shell.tscn` + `Shell.gd` = **TopBar + NavTree + ContentHost** + busca/█broadcast do warrior.
2. `App`: logado → mostra `Shell`; o Shell hospeda as telas no ContentHost (no lugar do swap tela-cheia do App).
   O **Hub** vira a **home/dashboard** (ou é aposentado; a nav o substitui).
3. `UiKit.scaffold` ganha um **modo "embutido"** (sem fundo/←/carteira) — shim: as telas continuam chamando
   `scaffold`, mas dentro do shell ele só devolve `content`/`status`/título. Assim **nenhuma tela quebra de uma vez**.
4. Carteira/HP/estamina migram pro TopBar; `UiKit.set_wallet` vira no-op nas telas (o shell cuida).
5. Combate (overlay `_play_battle`) inalterado — cobre o shell.
6. Ajustar foco de gamepad (nav lateral com memória de foco; L/R troca seção).

Risco: médio (mexe no roteamento + scaffold). Mitigação: shim no scaffold + migrar e revisar tela a tela.

---

## 4. Decisões (travadas 2026-06-13)
- **Busto:** ✅ **busto 3D ao vivo** já — `SubViewport` reaproveitando `PaperDollLive.gd` (own_world_3d isolado).
- **Rollout:** ✅ **shell + migrar todas as 21 telas** de uma vez (shim no `scaffold`), revisão depois.
- **Hub:** ✅ vira **dashboard/home** (painel-resumo) — a nav lateral substitui a navegação do Hub.

Números/medidas (px) são placeholders p/ ajuste no playtest.

## 5. Implementação (ordem)
1. `ui/Shell.gd` (class_name Shell): TopBar (busto+identidade+HP/estamina+moedas) + NavTree + ContentHost; busca `/api/warrior` 1x e dá refresh após ação (`refresh_warrior()`), sinal `request_battle`/`logout`.
2. `UiKit.scaffold`: modo **embedded** (quando `screen.has_meta("embedded")`) — sem fundo/←/carteira; só título slim + ↻ + status + content. Mesma forma de retorno (wallet=null → `set_wallet` no-op).
3. `App.gd`: logado → `Shell`; encaminha `request_battle` → `_play_battle(data, shell)` (esconde o shell inteiro, restaura no fim); `go_battle` (LUTAR) segue como overlay/scene.
4. Dashboard (home): card de status + atalhos + daily/avisos.
5. Busto 3D ao vivo no TopBar (PaperDollLive em SubViewport).
6. Foco de gamepad na nav (memória de foco; recolher/expandir).
