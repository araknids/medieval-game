# Launcher / auto-updater [DISTRIB_UPDATE]

Projeto Godot **separado** do jogo. É o `.exe` que o jogador baixa **fora da Steam** (canal direto/itch/
teste). Ele lê um **manifesto**, baixa/atualiza o build do jogo sozinho e abre. Na Steam isso é
desnecessário (a Steam atualiza). Desenho: `docs/PLANO_DISTRIBUICAO_UPDATE.md`.

## Como funciona
1. Lê `MANIFEST_URL` (em `Launcher.gd`) → JSON com a versão atual + URL do build.
2. Compara com `user://installed_version.txt`. Se mudou (ou não tem nada instalado): baixa o zip,
   extrai em `user://game/` e grava a versão.
3. Abre `user://game/<exe>` (`OS.create_process`) e fecha o launcher.
4. Offline mas com jogo instalado → abre o que tem. Sem nada instalado → erro + "Tentar de novo".

## Manifesto (`manifest.json`)
Hospedado num lugar **estável** (o default aponta pro raw deste arquivo no GitHub):
```json
{
  "version": "1.0.0",
  "windows": { "url": "https://.../CrownOfAravok-windows-1.0.0.zip", "exe": "CrownOfAravok.exe" }
}
```
- `version`: bump a cada release (o launcher compara string exata).
- `windows.url`: link DIRETO pro zip do build (GitHub Releases é grátis e ótimo).
- `windows.exe`: caminho do .exe DENTRO do zip.

## Publicar uma versão nova (release)
1. **Exportar o jogo:** em `godot-client`, Project → Export → Windows Desktop (Embed PCK) → gera
   `dist/CrownOfAravok.exe` (1 arquivo portável).
2. **Zipar:** `CrownOfAravok-windows-<versão>.zip` com o `.exe` (+ qualquer arquivo solto, se houver).
3. **Subir o zip** num **GitHub Release** (tag `v<versão>`) como asset → copiar o link direto.
4. **Atualizar `launcher/manifest.json`:** `version` + `windows.url` (o link do passo 3) → commit/push.
5. **(Importante) bater a versão do JOGO:** subir `config/version` no `godot-client/project.godot` p/ a
   mesma `version`, e no backend setar `CLIENT_LATEST_VERSION` (e `CLIENT_MIN_VERSION` se quiser FORÇAR).
6. Pronto: o launcher do jogador vê a versão nova → baixa → abre. Ele não fez nada.

> O launcher em si raramente muda; quando mudar, é só redistribuir o `.exe` do launcher (ou usar a
> Steam pro lançamento, que atualiza tudo sozinho).

## Exportar o launcher
Mesmo esquema do jogo: Project → Export → Windows Desktop (Embed PCK) → 1 `.exe` portável. **Esse** é o
arquivo que vai pro jogador no canal fora-da-Steam.
