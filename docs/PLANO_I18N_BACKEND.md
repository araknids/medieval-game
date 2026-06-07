# PLANO — i18n do Backend (Camada 2, locale-aware) — [I18N]

> **Status:** desenho aprovado, implementando por fases. Fonte da verdade desta feature.
> Decisão (2026-06-07): lançar **PT+EN**, tradução **funda (incl. narrativa)**. Ver [[project-launch-i18n]].

## Contexto e escopo

O jogo vai ter cliente **Godot** (Steam) "logo menos". A i18n tem 2 camadas:

- **Camada 1 — UI do cliente web** (`app.js`/`lang/*.json`/`index.html`): **descartável** quando o Godot
  entrar (Godot tem UI + localização próprios). **FORA DE ESCOPO.**
- **Camada 2 — Conteúdo gerado no backend** (quests, lore, torre, itens, achievements, narrativa, erros):
  **dado do jogo**, servido pela API; **sobrevive ao Godot** (mesma API). **ESTE PLANO.**

O Godot não traduz prosa dinâmica do backend sozinho → pra o lançamento bilíngue, o **backend precisa
virar locale-aware de qualquer jeito**. Fazer agora é 100% reaproveitado e de-risca a migração: quando o
Godot existir, o conteúdo já chega traduzido. A tradução da prosa (PT) é feita pelo agente (bilíngue).

## Arquitetura

**Spring `MessageSource` + `LocaleContextHolder` + preferência persistida + resolução por header.**

1. **Locale por request** = header `Accept-Language` (`en`/`pt`), resolvido por `AcceptHeaderLocaleResolver`
   restrito aos suportados (default `en`). **Zero leitura de DB por request** — o cliente manda o header.
   O web/Godot lê a preferência uma vez e passa a mandar o header em toda chamada.
2. **Preferência persistida**: `Player.language` (`varchar(5)`, default `'en'`). Settável por endpoint,
   devolvida nas respostas pra o cliente saber o que mandar. (Belt-and-suspenders: se não vier header,
   cai no default.)
3. **`MessageSource` bean**: basename `messages` → `messages.properties` (EN, base/fallback) +
   `messages_pt.properties` (PT). `defaultEncoding=UTF-8`, `fallbackToSystemLocale=false`.
4. **Helper `Messages`** (`@Component`): `get(key, args...)` →
   `ms.getMessage(key, args, key, LocaleContextHolder.getLocale())`. Default = a própria key (key
   faltando aparece como texto, não quebra). Resolve sempre no locale do request atual.
5. **Catálogos viram KEYED**: enums/catálogos que hoje guardam prosa EN (KingdomQuestType, Achievement,
   ClassTrialLore, InteractiveQuests, TowerFloors) param de guardar texto — guardam **keys** (ou derivam a
   key do `name()`), e o texto vai pros `.properties`. A resolução acontece na **fronteira** (service/DTO)
   via `Messages`, no locale do request.

### Convenção de keys
```
quest.<QUESTTYPE>.name / .flavor
questdlg.<QUESTTYPE>.intro / .opt.<id>.label / .opt.<id>.hint / .outcome.<id>.win / .lose / .text
tower.floor.<n>.atmosphere / tower.mvp.<n> / tower.monster.<slug>
achievement.<NAME>.title / .display / .desc
class.trial.<PATH>.intro / .victory / .defeat        +  class.<PATH>.desc
kingdom.<NAME>.lore
error.<slug>                                          (mensagens de Exception)
combat.<slug>                                         (templates de battle log)
```

## Fases

- **P0 — Infra (sem conteúdo ainda):** `I18nConfig` (MessageSource + LocaleResolver + suportados),
  `Messages` helper, `messages.properties`/`messages_pt.properties` (vazios/base), `Player.language` +
  migração (SchemaMigrator `ADD COLUMN IF NOT EXISTS language varchar(5) DEFAULT 'en'`), endpoint
  `GET/POST /api/settings/language` (ou em `/api/warrior`), expõe `language` + `supportedLanguages` nas
  respostas. Web `api()` passa `Accept-Language` (plumbing mínimo, NÃO é traduzir a UI).
- **P1 — Piloto end-to-end:** `ClassTrialLore` (19 strings) → keys + EN/PT. Prova o padrão (trocar idioma
  → fazer a Trial → lore em PT).
- **P2 — Quests:** `KingdomQuestType` (nomes/flavor, 57) + `InteractiveQuests` (diálogos/opções/desfechos,
  ~235). O grosso da narrativa.
- **P3 — Torre:** `TowerFloors` (atmosfera/MVP/monstros, ~99) + textos da escolha do Arka.
- **P4 — Achievements:** `Achievement` (title/display/desc, 30). ⚠ `titleString` é estático/puro e o título
  aparece pra OUTROS players — resolver no DTO no locale do VISITANTE (quem lê o ranking), não do dono.
- **P5 — Resto do conteúdo:** lore de reino (`Kingdom`), descrições de classe/habilidade
  (`ClassChangeService`/`AbilityService`/`ClassAbility`), loja/forja, `KingdomQuestNarrator`,
  nomes de item/recurso.
- **P6 — Erros (271):** `LocalizedException(key, args)` + `GlobalExceptionHandler` resolve via `Messages`.
  Migrar os `throw` aos poucos (os que sobrarem em EN caem no fallback).
- **P7 — Battle logs:** templates do `BattleSimulator` viram keys `combat.*` com params. O pedaço mais
  tedioso; pode ficar por último.

Cada fase: extrai as strings pra `.properties` (EN), escreve o PT, troca o catálogo/serviço pra resolver
via `Messages`, roda os testes. Números/escopo podem ajustar no caminho.

## Fora de escopo
- UI do cliente web (Camada 1).
- Outros idiomas além de EN/PT (a arquitetura já permite — é só adicionar `messages_xx.properties`).
