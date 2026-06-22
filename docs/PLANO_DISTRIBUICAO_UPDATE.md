# Plano — Distribuição (arquivo portável) + Auto-update [DISTRIB_UPDATE]

> Status: **proposta / discussão**. Pergunta do dono: (1) como deixar um **arquivo portável**, e
> (2) ao lançar **nova versão**, como **atualizar automático** sem o jogador baixar de novo — é possível?
> **Resposta curta: sim, é possível** — e pela arquitetura aqui, boa parte já é "de graça". Ver abaixo.

## 0. O ponto-chave: cliente FINO + servidor

O jogo é **cliente Godot (UI/render) + backend Java (regras/dados)**. Isso muda tudo no update:

| Tipo de mudança | Precisa o jogador baixar algo? |
|---|---|
| **Balanceamento, drops, preços, novas quests, números, regras** (backend) | ❌ **NÃO** — deploy no servidor e **todo mundo** já joga a versão nova na hora |
| **UI/telas novas, ícones, cenas, modelos 3D, lógica do cliente** (Godot) | ✅ Sim — aí o cliente precisa atualizar |

Ou seja: **a maioria das "novas versões" é backend → já chega automático em todos** sem download.
Só mudança no CLIENTE Godot exige distribuir um build novo. As seções abaixo cobrem esse caso.

## 1. Arquivo portável (sem instalador)

O Godot exporta um **único .exe portável** (Windows): no preset de export, ligar **"Embed PCK"** →
o `.pck` (código+assets) entra dentro do `.exe`. Resultado: **1 arquivo, roda sem instalar**.

- Falta criar o `godot-client/export_presets.cfg` (hoje não existe) com um preset **Windows Desktop**,
  `embed_pck=true`, `binary_format/embed_pck` ligado.
- Roda em: Project → Export → Windows Desktop → Export Project (ou `godot --headless --export-release`).
- (Linux/Mac depois, mesmo esquema.) Para o jogo achar o servidor, o cliente usa a URL do backend /
  seletor de servidor [SERVIDORES] — o portável aponta pro servidor de produção.

## 2. Auto-update — as opções

### Camada A — backend (já é automático) ✅
Já descrito no §0. Deploy do servidor = todo cliente conectado pega na hora. **Custo zero, já funciona.**

### Camada B — "trava de versão" do cliente (barato e importante) ⭐ fazer cedo
Pra um cliente **velho** não quebrar contra uma API que mudou:
- O cliente carrega uma constante `CLIENT_VERSION`.
- O `/api/server-info` passa a expor `minClientVersion` (e `latestClientVersion`).
- No boot, o cliente compara: se `CLIENT_VERSION < minClientVersion` → mostra **"atualização necessária"**
  (e, com launcher, dispara o update); se só tem versão nova disponível → aviso suave "tem update".
- Barato de fazer agora, protege contra mismatch cliente×servidor. **Não baixa nada sozinho ainda** —
  só avisa/gateia. O download automático é a Camada C.

### Camada C — atualizar o BUILD do cliente automático
Aqui sim "atualiza sem o jogador baixar manualmente". Depende do **canal de distribuição**:

- **Steam (o plano de lançamento):** o **Steam atualiza sozinho**, de graça, **sem código nenhum**.
  Você publica o build novo no Steamworks → o cliente do jogador atualiza no próximo boot. Para a versão
  Steam, **auto-update = resolvido pela Steam**. (É o motivo nº1 de lançar na Steam.)

- **Fora da Steam (portável / itch / servidor BR):**
  - **Padrão "launcher"** (mais comum no Godot): um **app Godot pequeno separado** cujo único trabalho é
    (1) ler um **manifesto JSON** com a versão atual (hospedado em GitHub Releases ou no seu bucket),
    (2) comparar com a versão local, (3) **baixar o build novo só se mudou** e (4) abrir o jogo. Base
    open-source pronta: **zip-launcher**. Resolve o "não baixar manualmente". Limite: baixa o zip
    inteiro (sem patch diferencial) — ok pra um jogo pequeno.
  - **itch.io app:** se distribuir pelo itch, o **app do itch atualiza sozinho** (com patch diferencial
    via `butler`). Zero launcher próprio.
  - **No-code pago** (Game Launcher Creator etc.): launcher com patch diferencial + auto-update do próprio
    launcher, sem codar — custa assinatura. Só se quiser UX caprichada sem trabalho.

## 3. Recomendação

1. **Portável:** criar o preset de export com **Embed PCK** → 1 `.exe`. (Posso montar o `export_presets.cfg`.)
2. **Trava de versão (Camada B):** implementar **agora** — `CLIENT_VERSION` + `minClientVersion` no
   `/api/server-info` + checagem no boot. Barato e evita dor de cabeça com cliente velho. (Posso fazer.)
3. **Auto-update do build (Camada C):**
   - **Pro lançamento → Steam faz sozinho.** Não construir launcher próprio pra Steam.
   - **Pré-Steam / teste / canal BR/itch:** quando você for de fato mandar mudança de CLIENTE, usar o
     **launcher** (zip-launcher como base) ou o **app do itch**. Montar isso só quando precisar — não
     antes, porque enquanto a mudança for backend, nem precisa.
4. Lembrar sempre: **mudou só o backend → já é automático**. O launcher/Steam é só pro que muda no Godot.

## 4. Decisão (resolvida) + o que JÁ foi implementado

Dono escolheu: **montar tudo agora** (preset + trava de versão + launcher), com a **Steam como foco**
(o launcher serve pro canal direto/teste fora da Steam).

**Implementado:**
- **Trava de versão (Camada B):**
  - Backend: `/api/server-info` agora expõe `minClientVersion` / `latestClientVersion` / `clientDownloadUrl`
    (props `app.client.*` em `application.properties`, env `CLIENT_MIN_VERSION`/`CLIENT_LATEST_VERSION`/
    `CLIENT_DOWNLOAD_URL`; default `0.0.0` = sem trava).
  - Cliente: `config/version` no `project.godot` (fonte da verdade) + `BackendClient.server_info()` +
    `App._check_version()` no boot — `< min` BLOQUEIA (overlay), `< latest` só avisa. Rede off/`0.0.0` = nada.
- **Portável:** `godot-client/export_presets.cfg` (Windows + **Embed PCK** → 1 `.exe`); versão versionada
  como `export_presets.cfg.example` (o `.cfg` real é gitignored p/ não encher de diff).
- **Launcher (Camada C, fora da Steam):** projeto Godot separado em `launcher/` (`Launcher.gd` +
  `.tscn` + `manifest.json` + `README.md`): manifesto → compara versão → baixa o zip → extrai → abre o
  jogo; offline com jogo instalado → abre o que tem.

**Processo de release** (passo a passo): ver `launcher/README.md`. Resumo: exporta o jogo (Embed PCK) →
zipa → sobe num GitHub Release → atualiza `launcher/manifest.json` (version+url) + `config/version` do
jogo + `CLIENT_LATEST_VERSION` no backend → push. Launcher do jogador atualiza sozinho.

**Pendências do dono (quando for distribuir fora da Steam):**
- Editar `MANIFEST_URL` em `launcher/Launcher.gd` se o manifesto não ficar no raw do repo.
- Fazer o 1º release (preencher `manifest.json`) e exportar o launcher.
- (Steam) auto-update fica por conta da Steam — não usar o launcher lá.

## 5. Canal

**Steam é o foco** (auto-update nativo da Steam). O launcher cobre teste/early/canal direto BR. Se um dia
for itch.io, o app do itch também auto-atualiza (launcher vira opcional).
