# Plano — Virada para Cliente 3D no Godot (personagem rigado + paper-doll + ragdoll)

> **Status:** decisão tomada (2026-06-09) — documento de design, ainda **sem código**.
> **Decisão:** o personagem do jogo deixa de ser **pixel-art 2D** (PixelLab + canvas) e passa a ser um
> **humanoide 3D estilizado** renderizado no **Godot 4**, usando assets **CC0 da Quaternius** como base.
> **Por quê:** o paper-doll/rig/desmembramento detalhado em 2D se provou **caro e manual** (ver §1). O 3D
> resolve isso com ferramentas nativas + um ecossistema de assets grátis.

---

## 1. Como chegamos aqui (contexto da decisão)

Exploramos a fundo o personagem 2D antes de virar:
- **PixelLab (IA)** gera sprites lindos, mas **frame-a-frame fica "travado igual GIF"** e a arte detalhada
  **não se decompõe** em peças de rig (oclusão da vista lateral; cor/segmento inconsistentes).
- Um **protótipo de rig+ragdoll em JS/canvas** (`backend/.../static/ragdoll-proto.html`) **validou o FEELING**
  que queremos: esqueleto de ossos (verlet), **ragdoll** ao arrastar/morrer, **desmembramento** (membro voa +
  sangue), e **equipar no osso** (elmo/espada/capa). O dono **aprovou a mecânica**.
- O gargalo ficou só na **ARTE das peças**: desenho à mão = simples demais; peças isoladas da IA = bonitas mas
  vêm como membro inteiro e **não casam de cor**. É o trade-off do §0 do `PLANO_PAPER_DOLL.md`:
  *detalhado/realista × modular = caro*.

**Conclusão:** o protótipo JS cumpriu o papel de **prova de conceito**. O personagem detalhado, rigado e
desmembrável é, na real, **tarefa de engine 3D** — que é justamente o **cliente Godot** já previsto pra Steam.

---

## 2. O que o Godot 3D resolve (de graça/nativo)

| Necessidade | Em 2D (sofrido) | Em Godot 3D (nativo) |
|---|---|---|
| **Rig/esqueleto** | verlet na mão | `Skeleton3D` + modelo **já rigado** |
| **Animações** | gerar frame a frame (trava) | `AnimationTree`/StateMachine + **120+ anims CC0** retargetáveis |
| **Ragdoll** | verlet caseiro | `PhysicalBone3D` (física real) |
| **Desmembramento** | recorte de sprite (oclusão) | soltar osso/`PhysicalBone3D` + VFX |
| **Paper-doll (equipar)** | desenhar camada por item | **Modular Outfits** (62 peças mix-and-match) + `BoneAttachment3D` |
| **Junta sem "fenda de papel"** | impossível limpo | skinning suave nativo |

---

## 3. Assets-base (CC0 / livres) — a "roda de apoio"

Tudo **Quaternius**, **CC0** (domínio público, uso comercial, pode modificar/substituir/vender):
- **Universal Base Characters** — corpo humanoide **rigado** (o esqueleto padrão do projeto).
- **Modular Character Outfits – Fantasy** — **62 peças** de armadura/roupa mix-and-match → **é o paper-doll**.
- **Universal Animation Library** — **120+ animações** (idle/andar/correr/combate/morte), retargetáveis (Mixamo).
- **RPG Character Pack / LowPoly Knight** — modelos prontos extras.

> **Guardar `assets/CREDITS.md`** com licença+fonte de cada pack desde o dia 1 (Steam/Valve cobra declaração;
> CC0 não exige atribuição, mas registramos por higiene). Formatos: glTF/GLB/FBX (abertos).

---

## 4. Não-lock-in (portabilidade) — [GODOT_PORTAVEL]

O compromisso é com a **arquitetura padrão**, não com o Quaternius:
- **CC0/MIT + Godot (MIT)** = zero amarra legal/royalty.
- **Rig humanoide PADRÃO** (bone naming Mixamo/universal) → trocar **modelo** ou **animações** por outros
  (comissão grimdark, Synty, Mixamo) é **retarget**, sem reescrever o jogo.
- **Código nosso** (paper-doll inventário→osso, ragdoll, desmembramento, replay) é lógica de engine, **não**
  depende do asset. Quaternius é conteúdo inicial **substituível peça por peça** conforme o jogo cresce.
- **Disciplina única:** padronizar **UM esqueleto humanoide** desde o começo (todo modelo futuro retargeta nele).

---

## 5. Arquitetura: backend atual + novo cliente Godot

O **backend Java/Spring Boot NÃO muda de papel** — continua sendo o **servidor de regras** (estamina, combate,
inventário, economia, PvP, etc.) expondo a **API REST + JWT** que já existe.

```
┌─────────────────────┐         REST + JWT (igual à web)        ┌──────────────────────────┐
│  Spring Boot (server)│  ◀──────────────────────────────────▶  │   Cliente Godot 4 (3D)   │
│  regras + DB + API   │   /api/warrior, /api/zones, /api/...     │  render + input + replay │
└─────────────────────┘                                         └──────────────────────────┘
        ▲   também serve a web app atual (coexiste durante a transição)
        └── frontend web (app.js) continua no ar p/ jogar enquanto o Godot amadurece
```

- Godot fala com a API via `HTTPRequest` (mesmos endpoints, mesmo `Authorization: Bearer <JWT>`).
- O **combate** já emite `BattleEvent` (spawn/attack/crit/miss/dodge/death…) em `BattleOutcome.events` —
  o Godot **consome o MESMO stream** que o `battleArena.js` consome hoje, só que renderiza em 3D. Reaproveita
  toda a lógica de servidor de [BATALHA_ANIMADA].

### Decisão em aberto **[D1]** — escopo do cliente Godot
- **(a) Só personagem/batalha 3D**: Godot renderiza o boneco/replay; o **resto da UI** (inventário, loja, menus)
  segue na web. Integração possível: **Godot export HTML5** embutido na página atual (a batalha vira uma cena
  Godot dentro do site). Menor esforço, transição suave. ⭐ **recomendado p/ começar**.
- **(b) Cliente Godot completo**: toda a UI migra pro Godot (o "cliente Steam" final). Muito maior; objetivo
  de longo prazo.
> Recomendação: começar por **(a)** (seed do cliente Steam), web segue no ar, e migrar telas pro Godot aos poucos.

---

## 6. O personagem (rig + paper-doll)

### 6.1 Rig padrão
- Adotar o esqueleto **Universal Base Character** (Quaternius) como **canônico**. Documentar o **bone naming**.
- Todo modelo/animação futuro deve bater nesse rig (retarget no import).

### 6.2 Paper-doll = equipar do inventário — [GODOT_PAPERDOLL]
Mapear os **slots do backend** (`ItemType`) → **peça modular / attachment**:

| `ItemType` (backend) | Como aparece no Godot |
|---|---|
| ARMOR / PANTS / BOOTS / GLOVES / SHOULDER | troca da **malha modular** daquela parte do corpo (Modular Outfits) |
| HELMET | malha de cabeça/elmo (troca/oculta cabelo) |
| WEAPON | `BoneAttachment3D` na mão (mesh da arma por `WeaponType`) |
| SHIELD | `BoneAttachment3D` na mão de defesa |
| (capa) | malha/aba presa às costas |
| RING / NECKLACE | sem visual (ou attachment opcional) |

- "Equipar item" no jogo → o Godot **troca a malha/attachment** do slot. Granularidade item-a-item que o 2D
  não dava de graça, aqui é nativa (mix-and-match das 62 peças).
- Cosméticos/skins (SoulStone [POSICIONAMENTO]) = mais malhas/texturas no mesmo rig.

### 6.3 Animações
- `AnimationTree` + StateMachine: idle / walk / attack / hurt / death (da Universal Animation Library).
- Blend suave (sem "travado"). Ataque por `WeaponType` (slash/thrust/bow) refinado depois.

### 6.4 Ragdoll + desmembramento — [GODOT_RAGDOLL]
- `PhysicalBone3D` no `Skeleton3D` → ragdoll na morte/golpe forte.
- Desmembrar = **detach** do `PhysicalBone3D` do membro + spawn de **VFX de sangue** (partículas) + (opcional)
  tampar o coto. Gera ao tomar dano / morrer (espelha [DESMEMBRAMENTO] / o que o protótipo JS provou).
- ⚠️ Tom: o estilo Quaternius é **limpo**; o gore fica menos "realista-sangrento". Mood grimdark vem de
  **textura escura + iluminação + pós** (ver §8), não do modelo base.

---

## 7. Fases de implementação (quando começar a codar)

- **Fase 0 — Prova de pipeline (Godot puro, sem backend):**
  importar 1 Base Character + tocar 1 animação (idle/attack) + **ragdoll** ao "matar" + **1 arma presa no osso**.
  Objetivo: provar rig+anim+ragdoll+attachment rodando. (equivale à Fase 0 que o JS já validou em conceito).
- **Fase 1 — Paper-doll:** trocar peças modulares por slot; mapear `ItemType` → malha/attachment.
- **Fase 2 — Integração backend:** `HTTPRequest` + JWT; ler equip real do `/api/warrior`; montar o boneco do
  jogador a partir do inventário.
- **Fase 3 — Battle replay 3D:** consumir `BattleOutcome.events` (mesmo stream do `battleArena.js`) e encenar
  em 3D (spawn/attack/hit/death/ragdoll). Embed via HTML5 export na web atual **[D1.a]**.
- **Fase 4 — Mood grimdark:** texturas escuras, iluminação/sombra, pós-processo, sangue/desmembramento.
- **Fase 5 — Variedade:** armas por tipo, classes (Warrior/Archer/Merchant) como variações de rig/anim, fundos.
- **Fase 6+ (longo prazo):** migrar mais telas pro cliente Godot rumo ao **cliente Steam** completo **[D1.b]**.

---

## 8. Clima grimdark num asset "limpo"

O Quaternius é estilizado/colorido. Pra puxar pro sombrio sem trocar o modelo:
- **Texturas/paleta** mais escuras e dessaturadas (re-tint dos materiais).
- **Iluminação** baixa + tochas/`OmniLight3D` quentes, névoa (`WorldEnvironment` fog).
- **Pós-processo**: vinheta, contraste, leve bloom nas tochas, color grading frio/escuro.
- **Sangue/desmembramento** dá o "rating mature".
> Se mesmo assim não der o tom, trocar o **modelo** por um 3D grimdark (comissão/pago) **no mesmo rig** — sem
> mexer no resto (graças ao §4).

---

## 9. O que fazer com o trabalho 2D já existente

- **Web app atual (app.js + canvas)**: **continua no ar** — é onde se joga durante a transição. Não jogar fora.
- **`battleArena.js`** (replay 2D) + sprites PixelLab atuais: viram **fallback/legado**; o replay 3D do Godot
  os substitui quando a Fase 3 entrar (possivelmente via embed HTML5).
- **`ragdoll-proto.html`** + peças desenhadas: **scratch de validação** — podem ser removidos quando o Godot
  assumir (a lógica/feel já está documentada aqui).
- **Lógica de servidor** ([BATALHA_ANIMADA], `BattleEvent`, equip, combate): **100% reaproveitada** — o Godot é
  só um novo cliente do mesmo backend.

---

## 10. Setup (pré-requisitos pra Fase 0)

- **Instalar Godot 4** (versão estável recente) — editor GUI; o agente **não roda** (precisa do dono abrir).
- **Estrutura de pastas** (decisão **[D2]**): projeto Godot em `godot-client/` (novo) ou em `prototypes/`.
  Recomendado: `godot-client/` na raiz, isolado do `backend/`.
- **Baixar os packs Quaternius** (Universal Base + Animation Library + Modular Outfits Fantasy) e importar.
- **`assets/CREDITS.md`** com licenças.

---

## 11. Decisões — FECHADAS (2026-06-09)

- **[D1] ✅ (a) Só personagem/batalha 3D.** O Godot faz **só a batalha** (replay 3D do combate); o resto da UI
  (inventário, loja, menus) **segue na web**. Embed via HTML5 export fica pra ver na Fase 3 (ver [D4]).
- **[D2] ✅ Pasta `godot-client/`** na raiz do repo, isolada do `backend/`.
- **[D3] ✅ Começar LIMPO** (Quaternius cru, sem mood grimdark) na Fase 0; escurecer só na Fase 4.
- **[D4] 🕓 Web export — decidir na hora** (Fase 3). Provável sim (embutir a batalha no site), mas confirma lá.
- **[D5] ✅ Padronizar o rig humanoide JÁ na Fase 0** (todo modelo/anim futuro retargeta nele).

> **Foco do escopo:** o cliente Godot, por ora, é **um renderizador de batalha 3D** que consome o stream de
> `BattleEvent` do backend ([BATALHA_ANIMADA]) — nada de inventário/loja/menus no Godot ainda.

---

## 12. Resumo

Viramos pro **cliente Godot 3D** com base **CC0 Quaternius** (rig + paper-doll modular + 120 anims), mantendo o
**backend Java intacto** como servidor. Isso resolve rig/animação/ragdoll/paper-doll/desmembramento que o 2D não
entregava barato — trocando a estética por **3D estilizado** (escurecível). **Sem lock-in** (CC0 + rig padrão +
formatos abertos): a arte é substituível peça por peça conforme o jogo cresce. Próximo passo após este plano:
resolver **[D1]/[D2]** e fazer a **Fase 0** (prova de pipeline no Godot).
