# Plano — Sistema de Cozinha (Cooking)

> Documento de design. Decisões tomadas com o dono em 2026-06-03.
> Status: **✅ IMPLEMENTADO** (441 testes verdes). FEATURES/GDD/USE_CASES/TEST_PLAN sincronizados.

---

## Visão

Dar uso de end-game aos peixes (Pesca / Mar Abençoado): transformá-los em **Refeições** que concedem
**buffs de combate temporários**, mais fortes que os do Templo, num **slot próprio** que empilha com eles.
Cozinhar é **instantâneo** (igual craftar na Forja) — sem skill nem timer.

## Decisões fechadas (2026-06-03)

1. **Efeito:** buffs de **combate** (ATK/DEF/HP/evasão), **mais fortes/longos** que os do Templo.
2. **Slot:** slot separado **"Bem Alimentado"** — independente dos 2 slots de buff do Templo (empilha).
3. **Skill:** **só receita** (instantâneo, sem nível). Receitas liberadas pelo **tier do peixe**.
4. **Onde:** seção **Cozinha** no **Commerce** (junto da Forja).

---

## ⚠️ Pré-requisito técnico (importante)

**Hoje nenhum buff afeta o combate.** `WarriorStatsService.combatStats` (fonte única de Arena/Torre/Zona/
Caçada) soma base + atributos + itens + joias, mas **não** os bônus de buff. Os buffs do Templo só
aparecem na ficha (`WarriorController`), não nas lutas.

Para "refeição = buff de combate" funcionar, `combatStats` passa a somar os bônus dos buffs ativos:
**slot 1 do Templo + slot 2 (VIP) + slot Bem Alimentado (refeição)**. Efeito colateral (bom): **os buffs
do Templo passam a funcionar de verdade no combate** — corrige uma lacuna existente. Como ninguém usa o
jogo ainda, é só ganho.

---

## 1. Receitas e refeições

Cada refeição: peixe(s) consumido(s) → 1 refeição; comer aplica o buff por X min. **Refeições de peixe de
estamina** focam ATK/ofensivo; **de peixe de vida** focam HP/DEF/defensivo.

### Linha do Desfiladeiro (peixe de estamina) — ofensivas
| Refeição | Ingrediente | Buff | Duração |
|----------|-------------|------|---------|
| 🍤 Espetinho de Peixe | 2× Peixe Pequeno | +8 ATK | 30 min |
| 🐟 Filé de Salmão | 2× Salmão | +10 ATK, +5 DEF | 40 min |
| 🍣 Banquete de Atum | 2× Atum | +12 ATK, +20 HP | 45 min |
| 🦈 Bife de Tubarão | 1× Tubarão | +15 ATK, +10 DEF | 45 min |
| 🌟 Prato Lendário | 1× Peixe Lendário | +18 ATK, +12 DEF, +40 HP | 60 min |

### Linha do Mar Abençoado (peixe de vida) — defensivas
| Refeição | Ingrediente | Buff | Duração |
|----------|-------------|------|---------|
| 🥣 Sopa de Coral | 2× Peixe Coral | +30 HP | 30 min |
| 🐠 Caldo Angelical | 2× Peixe-Anjo | +50 HP, +5% evasão | 40 min |
| ✨ Festim Sagrado | 1× Peixe Sagrado | +80 HP, +12 DEF | 50 min |
| 🔥 Assado da Fênix | 1× Peixe Fênix | +100 HP, +15 DEF, +8% evasão | 60 min |

> Referência (Templo): +5 ATK / +5 DEF / +20 HP / +5% evasão, 30-50 min. As refeições são ~1.5-2× mais
> fortes e usam peixe (que custa estamina + tempo de coleta) em vez de bronze — sink de esforço, não de moeda.

> Números são placeholders fáceis de tunar. Comer **substitui** a refeição ativa anterior (1 slot).

---

## 2. Modelo de dados

| Item | Mudança |
|------|---------|
| `enums/Meal` (novo) | Receita + buff num enum só: `displayName, icon, ResourceType fishIngredient, int fishQty, atkBonus, defBonus, hpBonus, evasionBonus, durationMinutes` |
| `model/MealInventory` (novo) | Estoque de refeições cozidas: `(player, Meal meal, int quantity)`, unique `(player, meal)` — espelha `ResourceInventory` |
| `model/Warrior` | + `Meal mealBuff` (`@Enumerated STRING`) + `LocalDateTime mealBuffExpiresAt` + `hasMealBuff()`/`clearMealBuff()` |
| `service/WarriorStatsService.combatStats` | passa a somar bônus de **slot1 + slot2 + mealBuff** (cada um só se ativo/não-expirado) — **liga buffs no combate** |
| `service/CookingService` (novo) | `getRecipes`, `getMeals`, `cook(player, meal)` (consome peixe → +1 refeição), `eat(player, meal)` (consome 1 refeição → aplica buff no slot Bem Alimentado) |
| `controller/CookingController` (novo) | `GET /api/cooking/recipes`, `GET /api/cooking/meals`, `POST /api/cooking/cook`, `POST /api/cooking/eat` |
| Frontend `app.js` | Seção **Cozinha** no Commerce: lista de receitas (ingredientes/efeito/se dá pra cozinhar) + minhas refeições + botões Cozinhar/Comer; exibir o buff "Bem Alimentado" na ficha/Templo |
| `WarriorController` ficha | incluir o buff de refeição no resumo (já mostra os do Templo) |

### Migração / DB
- Novas colunas `warriors.meal_buff` (varchar) + `meal_buff_expires_at` (timestamp) e tabela `meal_inventory`
  via `SchemaMigrator` (`ADD COLUMN IF NOT EXISTS`; tabela criada pelo Hibernate `ddl-auto=update`).
- `meal_buff` é coluna nova → sem check-constraint defasado (Hibernate cria com os valores atuais). Mesmo
  assim, incluo `warriors`/`meal_inventory` na lista do `dropStaleEnumCheckConstraints` p/ futuras adições.
- Sem soft-wipe necessário (só adições).

---

## 3. Regras

- **Cozinhar:** exige ter os peixes; consome-os e adiciona 1 refeição ao estoque. Instantâneo.
- **Comer:** consome 1 refeição do estoque; seta `mealBuff` + `mealBuffExpiresAt = now + duração`.
  Substitui qualquer refeição ativa (1 slot Bem Alimentado).
- **Combate:** o bônus do `mealBuff` entra via `combatStats` enquanto não expira.
- **Derrota/KO:** o buff de refeição é perdido junto com os do Templo (consistente com `clearBuff` no
  defeat de Arena/Torre). *(Decisão menor — mudável.)*
- **Balanceamento:** guerreiro 100% preparado = base+itens+joias + buff(s) do Templo + refeição. Spike de
  poder grande, mas custa coleta de peixe (estamina+tempo). Números tunáveis.

---

## 4. Plano de testes
| Área | Verifica |
|------|----------|
| Cozinhar | consome o peixe certo, +1 refeição; sem peixe → 400 |
| Comer | consome 1 refeição, seta o buff + expiração; sem refeição → 400 |
| Buff no combate | `combatStats` soma o bônus do mealBuff (e dos slots do Templo) quando ativo; ignora expirado |
| Substituição | comer 2ª refeição troca o buff ativo |
| Receitas | enum `Meal` consistente (ingrediente válido, bônus/duração positivos) |
| Regressão | combate (Arena/Torre/Zona) segue verde com buffs agora somados |

---

## 5. Fora de escopo (agora)
- Skill de Cozinha com nível (decidido: só receita).
- Buffs de utilidade/economia (yield/XP/bronze) — pode virar uma 2ª leva de receitas depois.
- Refeições de guild / feast coletivo (futuro do GDD).
- Timer de cozimento.
