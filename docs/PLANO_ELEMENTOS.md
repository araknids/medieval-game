# PLANO — Elementos (encantamento + áreas de zona) [ELEMENTOS]

> Status: **implementado** (fases 1 e 2). `ElementTest` + `ElementIntegrationTest` cobrem roda/multiplicador/encanto/essência.

## Objetivo

Sistema de elementos estilo D&D básico. Você encanta **arma** (elemento que causa) e/ou
**armadura** (elemento de defesa) no Templo — encantamento **temporário (1h)**. Monstros têm
elemento. No combate (PvE e PvP) o confronto de elementos dá ±25% de dano via uma **roda RPS**.
As zonas ganham **áreas por elemento** (botões), cada uma dropando a **essência** daquele elemento
(material do encantamento) + o recurso normal do bioma. Quem quer mais loot vai pras áreas PvP;
dá pra farmar só no PvE também.

## Decisões (alinhadas com o dono)

| Tema | Decisão |
|------|---------|
| Elementos | **Fogo, Água, Terra, Ar** (4) |
| Confronto | **Roda RPS** — cada elemento vence um e perde pra outro (±25%) |
| Encantamento | **Buff temporário (1h)**, custa **essência** do elemento (+ bronze) |
| Drop das áreas | **1 essência por elemento** (4 no total) + recurso normal do bioma |

## A roda (RPS)

```
        FOGO ──vence──▶ AR
         ▲               │
       vence           vence
         │               ▼
        ÁGUA ◀──vence── TERRA
```

- **Fogo** vence Ar · perde p/ Água · neutro vs Terra
- **Ar** vence Terra · perde p/ Fogo · neutro vs Água
- **Terra** vence Água · perde p/ Ar · neutro vs Fogo
- **Água** vence Fogo · perde p/ Terra · neutro vs Ar

Pares neutros (opostos): Fogo↔Terra, Ar↔Água.

**Multiplicador de dano** (`elementMultiplier(atacante_arma, defensor_armadura)`):
- atacante **vence** defensor → **×1.25** (+25%, "super eficaz")
- defensor **vence** atacante → **×0.75** (−25%, resistido)
- mesmo elemento, neutro, ou um dos lados sem encantamento → **×1.0**

## Combate

O modificador entra **por golpe**, depois da mitigação e antes/depois do crit:
`dano = mitigatedDamage(ATK, DEF) × elementMultiplier(...)` (crit dobra como hoje).

- **Você ataca:** sua **arma** (elemento) vs **armadura** do alvo.
- **Inimigo ataca:** **arma** dele vs sua **armadura**.
- **Monstro:** tem **1 elemento** que serve de arma E armadura. Ex.: monstro de Fogo é fraco a Água
  (arma Água = +25% nele) e seu ataque de Fogo é resistido por armadura de Água (−25% em você).
- **Sem encantamento** = neutro (×1.0). Encantar arma importa no ataque; encantar armadura, na defesa.

O `BattleSimulator.simulateDetailed` ganha uma sobrecarga com os 4 elementos (armaA/armaB/armaduraA/armaduraB);
sem eles = comportamento atual. Integrado em: **Zona** (PvE monstro + PvP raid), **Arena** (PvP).
Torre fica pra depois (chefes sem elemento por ora).

**O "se fuder" no PvP:** você não sabe o encantamento do inimigo até o encounter. Se a arma dele
vence o elemento da sua armadura, leva +25%. É o meta-game/aposta que você pediu.

**PvE é aprendível:** numa área de Fogo (monstros de Fogo), o ideal é arma **Água** (+25% neles) e
armadura **Água** (resiste o Fogo deles −25%).

## Encantamento (Templo)

- **Estado temporário no guerreiro** (NÃO no item) — como as bênçãos. `Warrior` ganha
  `weaponElement`/`weaponElementUntil` e `armorElement`/`armorElementUntil`. Dura **1h**.
  Some na **derrota/KO** (`clearBuff()` limpa também os elementos).
- `TempleService.enchantWeapon(player, element)` / `enchantArmor(player, element)`:
  consome **1 essência** do elemento (+ bronze pequeno) e seta o elemento por 1h (re-encantar renova/troca).
- UI do Templo: nova seção "Enchanting" — escolhe elemento p/ arma e p/ armadura, mostra essências
  que você tem e o tempo restante.

## Áreas de elemento nas zonas

- Cada **bioma de coleta** (Fishing/Mining/Grutas/Mar) ganha **4 botões de área** (Fogo/Água/Terra/Ar)
  — os mesmos 4 em todo bioma ("todas as zonas têm os mesmos locais").
- A área define: a **essência** que dropa (ex.: Essência de Fogo, flavor "peixe de fogo" na pesca) +
  o **elemento dos monstros** dos encontros daquela área.
- Cada área é farmável nos **tiers** existentes (🟢SAFE / 🟡PVP / 🔴HIGH_RISK) — PvP dropa mais/melhor,
  PvE sempre dá pra farmar. (Tier = risco/recompensa, como hoje; área = elemento.)
- `ZoneActivity` ganha o campo `element`. `enter()` recebe o elemento. `resolveGathering` adiciona a
  essência do elemento; `resolveEncounters` dá o elemento aos monstros e passa pro BattleSimulator.
- **Zonas de combate** (kingdom COMBAT): podem ganhar elemento por área depois (fase 2.5) — v1 foca
  nos biomas de coleta (onde o loop de essência vive). PvP de raid já usa os encantamentos dos players
  em qualquer zona.

## O que muda no código

### Novo
- **`Element`** enum (FIRE/WATER/EARTH/AIR): `beats()`, `multiplier(atk, def)`, displayName/icon.
- **`ResourceType`**: + `FIRE_ESSENCE`, `WATER_ESSENCE`, `EARTH_ESSENCE`, `AIR_ESSENCE` (categoria nova
  ou existente). Drop nas áreas; consumidos no encantamento.
- **`Warrior`**: + `weaponElement`/`weaponElementUntil` + `armorElement`/`armorElementUntil` (+ getters
  "ativos" que respeitam a expiração). Migração: 4 colunas.
- **`ZoneActivity`**: + `element`. Migração: 1 coluna.

### Alterado
- **`BattleSimulator`**: sobrecarga com elementos → aplica o multiplicador por golpe + linha no log
  ("🔥 super effective! +25%"). As chamadas atuais sem elemento seguem iguais.
- **`ZoneService`**: `enter(..., element)`; monstros recebem o elemento da área; PvP passa os
  encantamentos dos dois players; gathering dropa a essência.
- **`ArenaService`**: passa os encantamentos dos dois players ao simulador.
- **`TempleService`** + **`TempleController`**: endpoints de encantar arma/armadura.
- **`WarriorController.buildResponse`**: expõe encantamentos ativos (elemento + tempo) pra ficha.
- **Frontend**: seção de Enchanting no Templo; selos de elemento + timer na ficha; botões de área por
  elemento nas zonas; linha de "super eficaz" no log de batalha.

### Migração / DB
- `warriors`: 4 colunas (weapon/armor element + until). `zone_activities`: 1 coluna (element).
  Check constraints de enum (resource_type já é tratado pelo `dropStaleEnumCheckConstraints`).

## Ordem de implementação sugerida (1 feature, 2 fases)

1. **Fase 1 — Núcleo de elementos:** `Element` enum + roda, integração no `BattleSimulator`,
   campos de encantamento no `Warrior`, essências (`ResourceType`), Templo (encantar) + Arena/Zona
   passando elementos, ficha do guerreiro mostrando encantamentos. (Já dá pra encantar e o PvP/encounters
   passam a importar.)
2. **Fase 2 — Áreas de elemento:** `ZoneActivity.element`, botões de área por bioma, monstros com
   elemento por área, drop de essência por área. (Fecha o loop farm→essência→encantar.)

## Fora de escopo (futuro)
- Elementos na Torre (chefes elementais).
- Recurso temático por bioma×elemento (hoje: 1 essência por elemento, flavor por bioma).
- Encantamento permanente no item / sockets elementais.
- 5º/6º elemento (Raio/Gelo).
- Resistência/afinidade de classe a elementos.
