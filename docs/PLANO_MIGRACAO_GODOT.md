# Plano — Migração da UI para o cliente Godot (rumo à Steam) — [MIGRACAO_GODOT]

> **Decisão (2026-06-11):** o cliente **Steam será 100% Godot** (UI em Control nodes + batalha 3D nativa).
> A web continua como versão de **dev/referência**. Migração **incremental, combate-primeiro**. O
> **backend REST é o mesmo** pros dois clientes → migrar tela por tela **não desperdiça nada**.
> Contexto da decisão: ver a conversa "compensa mudar tudo pra Godot ou só o combate?" — resposta:
> all-Godot é o destino certo p/ Steam (um executável; webview seria frágil), mas só agora começa.

## Arquitetura do cliente Godot

| Peça | Papel |
|------|-------|
| **`Api` (autoload)** `net/BackendClient.gd` | Singleton HTTP+JWT. Login uma vez → token persiste entre telas. Todas as telas chamam `Api.get_warrior()`, `Api.arena_fight()`, etc. |
| **`App.tscn` / `App.gd`** (main scene) | **Roteador**: troca a tela atual (Login ↔ Personagem ↔ …). Sem token → Login; com token → Personagem. |
| **Telas** `ui/*.tscn` (Control) | Cada tela é um Control que lê/escreve via `Api`. UI montada por **código** (mesmo padrão do resto do projeto). |
| **`BattleReplay.tscn`** (já existe) | A batalha 3D. Reusa o token do `Api` (não re-loga quando vem do app). Standalone (F6) ainda loga via `login.cfg`. |

Telas trocam por `change_scene_to_file` (batalha) ou swap interno do `App` (menus). Cada tela tem **voltar**.

## Ordem de migração (combate-primeiro)

1. ✅ **Batalha 3D** (BattleReplay) — feito (cenários, monstros, PvE, gore, armas/raridade, juice).
2. 🔜 **Login** + **Personagem (home)** — `GET /api/warrior` (stats, HP, estamina, atributos, moeda, buffs) + gastar ponto de atributo + botão **Lutar**. ← **começando agora**
3. **Inventário / Equipar** — `GET /api/inventory`, equipar/vender. É a mais adjacente ao combate (a batalha já lê equip/arma/raridade).
4. **Loja**, **Forja**, **Templo** — economia/gear.
5. **Reinos/Quests**, **Arena**, **Torre** — lançam batalhas (entram no BattleReplay com a luta real).
6. **Guilda**, **Leilão**, **Taverna**, **Conquistas**, **VIP**, **Daily** — social/meta.
7. Polimento, navegação, telas de erro, onboarding.

## Contrato-chave (referência rápida)

- **Login:** `POST /api/auth/login {username,password}` → `{token, playerId, username, …}`. `Api` guarda o `token`.
- **Personagem:** `GET /api/warrior` → 60+ campos: `name, warriorClass(+Id), level, experience, expNeeded, totalAttack/Defense/Health, strength/dexterity/constitution/agility/luck/intellect, availablePoints, hpPercent, isKnockedOut, stamina, minutesToFullStamina, bronze/silver/gold (normalizados), soulStones, rankPoints, title, activeBuff, combatAttack/Defense/Health, newbieBuffActive, tavernBuffPct, …`.
- **Gastar atributo:** `POST /api/warrior/attributes/{STRENGTH|DEXTERITY|CONSTITUTION|AGILITY|LUCK|INTELLECT}` → devolve o WarriorResponse atualizado.
- **Postura:** `POST /api/warrior/posture/{BALANCED|OFFENSIVE|DEFENSIVE}`.

## Princípios

- **Backend intocado** — só consumir a API existente. Sem mudar Java.
- **UI por código** (Control) — consistente com o projeto; fácil de versionar.
- **`Api` compartilhado** — token único; nada de logar em cada tela.
- **Web continua viva** — fonte de verdade de feature até a tela ser migrada; nada quebra.
- **Cada tela testável isolada** (F6) e integrada pelo `App`.

## Status
- [x] Fundação HTTP (`BackendClient`) + batalha 3D.
- [ ] `Api` autoload + `App` roteador + **Login** + **Personagem**. ← agora
- [ ] Inventário/Equipar · Loja · Reinos→batalha · resto.
