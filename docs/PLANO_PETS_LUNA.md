# Plano — Pets (companheiro equipável) + Luna via quest rara

> Status: **implementado** (2026-06-05). Fonte da verdade da feature.
> Idioma: doc em PT; código e strings de UI em EN (traduz pro PT depois via i18n).

## Objetivo

Pets = um novo "equipável" (slot próprio, igual Montaria) que dá bônus de combate. O primeiro é a
**Luna** (cachorra 🐶, **+10% HP**), obtida por uma **quest rara** que às vezes aparece nas dailies:
você acha a cachorra passando mal, ajuda (sem ganhar loot) e tem uma chance pequena+escalante de
ficar com ela. Outros pets virão depois (sistema já extensível).

## Decisões travadas (alinhadas com o dono)

| Tema | Decisão |
|---|---|
| Modelo do pet | Espelha a **Montaria**: entidade `Pet` + enum `PetType` + slot equipável + bônus no combatStats |
| Bônus da Luna | **+10% HP** (multiplicador final no HP de combate) |
| Raridade (pity) | **base 0.01%**, **+0.005% por tentativa**, **teto 1%** (ultra-raro, ~300 tentativas médias) |
| Aparição da quest | **~1 a cada 4 janelas de 12h** (determinístico por player+janela; não pisca) |
| Recompensa da quest | **nada de loot** — só a chance de pet; ajudar não custa estamina |

## Modelo do Pet (igual Montaria/Estábulo)

- **`enums/PetType`**: `LUNA("Luna", "🐶", 10 /* hpBonusPercent */)`. Extensível p/ novos pets.
- **`model/Pet`**: `player` (ManyToOne LAZY), `petType` (enum), `equipped` (boolean). Tabela `pets`
  (auto-criada pelo ddl-auto=update; sem migração manual).
- **`repository/PetRepository`**: `findByPlayer`, `findByPlayerAndEquippedTrue`, `existsByPlayerAndPetType`.
- **`service/PetService`**: `list`, `equip(type)`, `unequip`, `grant(type)` (cria + equipa se nenhum equipado).
- **`controller/PetController`**: `GET /api/pets`, `POST /api/pets/equip/{type}`, `POST /api/pets/unequip`.
- **`WarriorStatsService.combatStats`**: HP final × `(1 + pet.hpBonusPercent/100)` (injeta `PetRepository`).
- **`WarriorController`**: `equippedPet` (PetInfo: name/displayName/icon/hpBonusPercent) na resposta.

## Quest rara da Luna

- **`KingdomQuestType.RESCUE_STRAY_DOG`** (kingdom nominal FISHING, recompensas 0, estamina 0). Interativa.
- **`InteractiveQuests`**: registra o diálogo — intro ("a sick stray dog…") + opções `help` / `leave`
  (outcomes placeholder; o collect é special-cased).
- **Aparição** (`KingdomService.isLunaWindow(player)`): `floorMod(hash(playerId, windowId), 4) == 0` (~25%
  das janelas). Quando ativa **e** o player não tem a Luna, a quest é **injetada na vitrine de TODOS os
  reinos** (achável em qualquer lugar). Trava por janela como as outras (`completedWindowId`).
- **`startQuest`**: bypassa o check "quest pertence ao reino" só p/ a Luna (aparece em todos os reinos).
- **`collectQuest` (special-case Luna)**:
  - `leave` → narrativa, nada acontece (sem pity, sem chance).
  - `help` → sem loot; rola a chance de pet:
    ```
    attempts   = player.petPityAttempts
    chancePpm  = min(10_000, 100 + 50*attempts)   // 0.01% base, +0.005%/try, teto 1% (em ppm)
    if rng.nextInt(1_000_000) < chancePpm: petService.grant(LUNA); acquiredPet="Luna"
    else: player.petPityAttempts++
    ```
  - Marca COLLECTED + `completedWindowId` (lock por janela).
- **`Player.petPityAttempts`** (int, default 0) — contador da pity. Posse da Luna = `PetRepository.existsByPlayerAndPetType(LUNA)` (sem flag redundante).
- **`CollectResult`** ganha `String acquiredPet` (null normalmente; "Luna" quando consegue) → o
  controller devolve no collect; o front celebra + recarrega o guerreiro (HP novo).

## Mudanças por arquivo (resumo)
- Novos: `PetType`, `Pet`, `PetRepository`, `PetService`, `PetController`.
- `Player`: + `petPityAttempts`.
- `KingdomQuestType`: + `RESCUE_STRAY_DOG`.
- `InteractiveQuests`: + diálogo da Luna.
- `KingdomService`: `isLunaWindow`, injeção na vitrine, bypass no start, special-case no collect,
  `CollectResult.acquiredPet`.
- `KingdomController`: vitrine inclui o card da Luna; collect devolve `acquiredPet`.
- `WarriorStatsService`/`WarriorController`: bônus + PetInfo.
- `SchemaMigrator`: coluna `pet_pity_attempts` (tabela `pets` é auto-criada).
- Frontend: pet no card do guerreiro; painel de pets (equip/unequip); resultado da quest da Luna.

## Testes
- Pity: `chancePpm` escala (0→100, 1→150, 198→10000 cap); fail incrementa `petPityAttempts`.
- Grant: roll de sucesso cria a Pet + equipa; HP de combate sobe +10% com a Luna equipada.
- Equip/unequip via endpoint; `existsByPlayerAndPetType` reflete posse.
- Luna window determinística (mesmo player+janela → mesmo resultado); quest some quando já tem a Luna.
- Collect `leave` não mexe na pity; `help` mexe/concede.

## Adendo — Gato (Shadow) no mercado VIP (2026-06-05)

Segundo pet, **comprável** (não vem de quest): **Shadow** 🐱, **+6 AGI (DEX)**, custa **10 SoulStones**
no mercado VIP. Generalizou o modelo:
- `PetType` ganhou `dexBonus` (AGI plana) e `soulStoneCost` (0 = não comprável, ex.: Luna).
- `combatStats` aplica o `dexBonus` do pet equipado no índice de DEX (AC = 10 + dex; entra no d20).
- `PetService.buy(type)` (debita SoulStone + grant) + `PetController POST /api/pets/buy/{type}`.
- Frontend: card no mercado VIP (loadVipShop) com Adopt/Equip; o card do guerreiro mostra o bônus real
  (HP% e/ou AGI).
- `SchemaMigrator`: dropa o check de `pets.pet_type` (novo valor SHADOW aceito em prod).

## Consequências / notas
- Ultra-raro de propósito (decisão do dono) — números são constantes fáceis de tunar depois.
- Pet não ocupa slot de bag (slot próprio, igual montaria). HP% é multiplicador final (empilha com gear/buff/montaria).
- Só 1 pet equipado por vez (equipar troca). Gato vs Luna = escolha entre +AGI e +10% HP.
