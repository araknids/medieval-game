# Plano — Expansão para 6 Reinos (Reinos V2)

> Documento de planejamento (fase de design). Decisões tomadas com o dono em 2026-06-03.
> Depois de aprovado, vira implementação por fases + sincroniza com FEATURES/GDD/USE_CASES/TEST_PLAN.

---

## Visão

Expandir de **3 reinos** para **6**, cada um temático, com sua atividade própria.
Unificar os conceitos `Kingdom` e `Territory` (hoje duplicados 1:1) num **único conceito `Kingdom`**.
Nem todos os reinos são território de guild-war no início — uma **flag** limita a 3 (depois liga todos).

### Como funciona hoje (baseline)
- 3 reinos = 3 territórios (1:1): Pesca, Mineração, Combate/Fortaleza.
- Coleta (pesca/mineração) é feita em **expedições de Zona** (PvE = SAFE, PvP = PVP/HIGH_RISK).
- Reino dá um **bônus passivo** à guild que domina + tem quests; território = guerra de guild (declarar, ciclo 6h, manutenção).
- Peixe restaura estamina **e** HP (teto 50%). Gemas: **fragmento cai minerando** → vira joia na Forja.
- Tower = PvE que escala (escada de chefes, sem drop de item).

---

## Os 6 Reinos

| # | Reino | Ícone | Atividade | Loot / Função | Guild-war no início? |
|---|-------|-------|-----------|---------------|----------------------|
| 1 | Pesca — Águas Calmas | 🎣 | Pesca | Peixe que restaura **ESTAMINA** | ✅ (existente) |
| 2 | Mar Abençoado | 🐟 | Pesca | Peixe que restaura **VIDA/HP** | ❌ aberto |
| 3 | Minas de Ferro Negro | ⛏ | Mineração | **Só minério** (gemas saem daqui) | ✅ (existente) |
| 4 | Grutas de Cristal | 🔎 | **Garimpo (NOVO)** | **Fragmentos de joia** | ❌ aberto |
| 5 | Fortaleza Maldita | ⚔ | Combate (guerra/treino) | PvP de guild + treino + quests | ✅ (existente) |
| 6 | Covil das Feras | 👹 | Combate PvE | **Farm de mobs** → gold + materiais escalando com level | ❌ aberto |

> **War inicial (flag) ✅ decidido:** os **3 reinos atuais** (Águas Calmas, Minas, Fortaleza) ficam
> guild-war; os 3 novos (Mar Abençoado, Grutas de Cristal, Covil das Feras) começam como **zonas abertas**.
> Depois é só mudar a config `app.kingdoms.war-territories` pra ligar guerra em todos.
>
> *Nomes provisórios (Mar Abençoado / Grutas de Cristal / Covil das Feras) — mudáveis depois.*

---

## 1. Unificar `Kingdom` + `Territory` (refactor — base de tudo)

Fundir num único enum **`Kingdom`** que carrega tudo o que hoje está espalhado entre `Kingdom` e `Territory`:

```
Kingdom {
  displayName, icon, lore,
  activity            // FISHING | MINING | GARIMPO | COMBAT_PVE | COMBAT_WAR
  primarySkill        // SkillType (FISHING/MINING/GARIMPO) ou null
  lootPool            // qual conjunto de drops (ex.: STAMINA_FISH, HP_FISH, ORE, GEM_FRAGMENT) — null p/ combate
  npcName, npcAtkMult, npcDefMult, npcHpMult   // p/ as batalhas (war e PvE)
  bonusType, bonusValue                        // bônus da guild dominante
}
```

- `Territory` (enum) é **removido**. `TerritoryControl`, `TerritoryDeclaration`, `TerritoryBattleLog`,
  `TerritoryService`, `TerritoryScheduler` passam a referenciar `Kingdom`.
- **Flag de guild-war:** config `app.kingdoms.war-territories` = lista de reinos contestáveis
  (default = os 3 atuais). `TerritoryService`/`ensureInitialized` só cria/resolve controle para esses.
  Ligar guerra em todos = trocar a config (1 linha), sem deploy de código.
- Migração: como o banco é **descartável**, fazemos **soft-wipe**; os nomes de enum em colunas
  (`territory_controls.territory` → `kingdom`) entram limpos. Sem dor de check-constraint.

---

## 2. Garimpo — nova profissão/skill

- `SkillType` ganha **`GARIMPO ("Garimpo", "🔎")`** — nível/XP próprios, igual Pesca/Mineração.
- Funciona **igual pesca/mineração**: expedição com área **PvE (SAFE)** e **PvP (PVP/HIGH_RISK)**.
- **Loot = fragmentos de joia** (RUBY_FRAGMENT … DIAMOND_FRAGMENT/AMETHYST), por nível de Garimpo
  (mesma escada de nível que hoje os fragmentos seguiam por minério).
- **Mineração para de dropar fragmento** (`rollFragment` sai do MINING) → vira **só minério**.
- Loop preservado: **fragmento → Forja → joia → socket**.
- Bônus do reino de Gemas (quando for war): **+% de fragmento** pra guild dominante.

---

## 3. Split de peixe — Estamina vs Vida

Hoje todo peixe dá estamina + HP (teto 50%). Passa a ter **dois conjuntos**, por reino:

- **Águas Calmas** → peixes de **estamina** (mantém SMALL_FISH, SALMON, TUNA, SHARK, LEGENDARY_FISH;
  restauram **só estamina** agora).
- **Mar Abençoado** → **novos peixes de HP** (ex.: Peixe-Coral, Peixe-Anjo, Peixe-Espírito, Peixe-Fênix…)
  que restauram **só vida**, por nível de pesca.
- A expedição de pesca passa a escolher **em qual reino** pesca (define o pool de peixe).

⚠️ **Balance (não furar o sink do Templo, A5):** peixe de HP cura até o **teto de 90%** ✅ (decidido).
Fechar os últimos 10% / reviver de KO continua favorecendo Templo (pago) e regen.

**Nomes provisórios (mudáveis):** peixes de HP por nível de pesca →
Peixe-Coral (lv1), Peixe-Anjo (lv20), Peixe-Espírito (lv40), Peixe-Sagrado (lv60), Peixe-Fênix (lv80).

---

## 4. Combate PvE late-game — farm de mobs

- Reino de Combate PvE = **incursão repetível** que escala com o **level do guerreiro**.
- **Mobs comuns** (não chefes) — chefes ficam reservados pra **Tower**.
- Drop: **gold + materiais** (ex.: `LEATHER` e novos materiais de craft) escalando com level.
- Diferença pra Tower: Tower é escada de **chefe** único sem drop de item; aqui é **farm de mob**
  repetível com drop de gold/material. Diferença pra Zona COMBAT (PvP/emboscada): aqui é **PvE puro**,
  sem roubo entre jogadores.
- Reusa `BattleSimulator` + a infra de expedição.

---

## 5. Mais monstros + especiais da Tower (conteúdo)

- **Aumentar a variedade de mobs comuns** (catálogo de monstros) usados na Zona/Combate PvE — hoje os
  inimigos são gerados por level com poucos nomes. Criar um catálogo maior (nome + stats + tema por reino).
- **Criar chefes especiais pra Tower** (boss-tier) — a Tower passa a ter chefes próprios mais marcantes,
  separados dos mobs comuns do farm PvE.
- (Escopo: pode virar um enum/catálogo `Monster`/`Boss` com stats e raridade.)

---

## Plano de implementação por fases

Cada fase entra com testes e fica verde antes da próxima.

1. **Fase 1 — Unificar Kingdom/Territory + flag de war.** Refactor sem conteúdo novo: funde os enums,
   migra `TerritoryService`/controle/declaração/scheduler para `Kingdom`, adiciona a flag (3 war no início).
   *Paridade com hoje.* (Soft-wipe na subida.)
2. **Fase 2 — Garimpo + realocar gemas.** Nova skill `GARIMPO`, fragmentos vêm do reino de Gemas (PvE/PvP),
   mineração só minério. UI de Garimpo igual pesca/mineração.
3. **Fase 3 — Split de peixe + Mar Abençoado.** Peixes de estamina vs HP, novo reino, seleção de reino na
   expedição de pesca, teto de cura definido.
4. **Fase 4 — Combate PvE (farm de mob).** Reino de incursão, drop de gold/material escalando.
5. **Fase 5 — Catálogo de monstros + chefes da Tower.** Mais mobs comuns; chefes especiais na Tower.

---

## Migração / DB

- Banco **descartável** → cada fase que muda enum/coluna sobe junto de um **soft-wipe**
  (`APP_MAINTENANCE_SOFT_WIPE`), evitando dor com check-constraint de enum.
- Novos enums em colunas: `SkillType.GARIMPO`, novos `ResourceType` (peixes de HP, materiais),
  `Kingdom` no lugar de `Territory`. Hibernate `ddl-auto=update` + `SchemaMigrator` cobrem colunas novas.

---

## Decisões fechadas (2026-06-03)

1. **Teto de cura do peixe de HP:** ✅ **90%**.
2. **Guild-war no início:** ✅ os **3 reinos atuais** (Águas Calmas, Minas, Fortaleza).
3. **Nomes/lore:** ✅ provisórios definidos (Mar Abençoado, Grutas de Cristal, Covil das Feras; peixes
   de HP: Coral/Anjo/Espírito/Sagrado/Fênix) — mudáveis a qualquer momento.

**→ Design 100% travado. Pronto pra implementar a Fase 1.**

---

*Plano vivo — atualizar conforme as fases forem implementadas. Origem: pedido de expansão de mundo
(2026-06-03), engloba o BL-2/M10 (unificar Kingdom×Territory).*
