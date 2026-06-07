# Auditoria de Balance de Combate — "pior vs melhor caso upando" + x1 desequilibrado

> Gerado rodando o **`BattleSimulator` real** (sem gear/skills/elementos pra isolar atributos)
> via `CombatBalanceProbeTest` — 4000 duelos/matchup, iniciativa alternada, desempate por %HP.
> Reproduza: `mvn -q test -Dtest=CombatBalanceProbeTest` e leia o stdout do surefire.
>
> **Status:** diagnóstico (não foram feitas mudanças de balance ainda). Números base = placeholders.

## Como o combate funciona hoje (resumo)

- **Acerto:** `d20 + STR/20` ≥ **AC = 10 + DEX**. Roll 1 = erro automático. Crit (`d20 ≥ critThreshold`) **ignora a AC**.
- **Crit threshold:** `max(17, 20 − LUK/15)` → 0 LUK = só no 20 (5%); 45+ LUK = 17–20 (20%). Crit = **×2 dano**.
- **Dano:** `max(1, round(ATK × 100/(100+DEF)))`, ×1.25/0.75 por elemento. **ATK = base + STR**. **HP = base + CON×8**.
- **Bônus de acerto = STR/20** → cap +4 (STR 80). **fortuneSave = LUK/10** (% de anular crit recebido).
- Pontos por nível = **(nível−1)×2** (L10=18, L30=58, L50=98). Caps por classe limitam por atributo (CON = ∞).

---

## Achado nº1 — A "PAREDE DE AC": DEX é dominante e tem um *cliff* em ~15

`AC = 10 + DEX` cresce **linear e sem teto**, mas o acerto normal está **travado em `d20 + STR/20 ≤ 24`**.
Logo, **a partir de DEX 15 (AC 25) o alvo só pode ser atingido por CRIT** — golpe normal vira impossível.

| DEX do alvo | AC | Atacante L50 MaxSTR vence |
|---|---|---|
| 0 | 10 | **100%** |
| 10 | 20 | 49% |
| 14 | 24 | 22% |
| **15** | **25** | **22% — [SÓ CRIT]** |
| 20 | 30 | 23% |
| 30 | 40 | 22% |

**Consequências:**
- Investir **~15 DEX** vira de "perde 100%" para "ganha ~78%" contra o maior atacante físico do jogo.
- **DEX acima de 15 é desperdício** (você já é só-crittável). Logo o cap de DEX 55 do Archer é uma **armadilha**.
- O bônus de acerto da **STR (str/20, máx +4) é quase irrelevante** — não vence AC alta de jeito nenhum.
- Contra builds de DEX, **só crit fura** → o dano vira refém de RNG.

## Achado nº2 — Crit dá *one-shot* no L50 (alta variância)

| | ATK | golpe normal | **CRIT (×2)** | com elemento |
|---|---|---|---|---|
| L50 MaxSTR (STR80) vs DEF base 14 | 95 | 83 | **166** | ~208 |

Build ofensiva típica L50 tem **130 HP** (0 CON). **Um único crit (166, ou 208 com elemento) mata de uma vez.**
Crit é 5–20% por golpe, mas **binário-letal** → o x1 frequentemente é decidido por *quem critta primeiro*, não por build.

## Achado nº3 — HP puro (CON) não salva; AC e ATK são tudo

Build **"Noob" L50 = 98 pontos em CON → 914 HP**, mas ATK 15 e **AC 10**:
- Perde **100%** pro Bruiser e **98%** pro DodgeTank.
- Motivo: **AC 10 = sempre é acertado** e **ATK 15 = não consegue matar**. 914 HP é moído com 1 de dano por vez.

CON sem AC/ATK é quase inútil; um pouco de DEX vale muito mais que muito CON.

## Achado nº4 — Pior vs melhor build, MESMO nível: x1 vira atropelo

Mesma classe (Warrior), mesmo nível, só muda a distribuição:

| Nível | Bruiser (STR+DEX) vs Pior (dump INT) | vencedor sai com |
|---|---|---|
| 10 | **96%** | 53% HP |
| 30 | **93%** | 45% HP |
| 50 | **100%** | **87% HP** |

E o "Noob" (só CON, 914 HP — build *plausível* de iniciante) **perde 100%** pro Bruiser no L50.
→ Um jogador que distribui mal os pontos fica **injogável em PvP no mesmo nível**. O *skill ceiling* de build é enorme.

## Achado nº5 — O "triângulo de classes" está invertido/ausente (sem gear)

Builds otimizados, Lv50, só atributos (sem arma/elemento que é o que deveria criar o triângulo):

| vence ↓ \ contra → | Warrior | Archer | Merchant |
|---|---|---|---|
| **Warrior** | — | **75%** | **97%** |
| **Archer** | 25% | — | 20% |
| **Merchant** | 3% | **81%** | — |

- **Warrior domina tudo.** O esperado `Archer › Warrior` (CLAUDE.md) está **invertido** (Archer perde 75%).
- **Archer é a pior classe** no x1 puro: base ATK 18 + armadilha do cap de DEX → fica **sem dano**.
- O triângulo **não é mecânica de verdade** — depende de arma/elemento (fora desta sonda). Vale validar com gear.

---

## Causa-raiz comum

Quase tudo acima sai de **três interações**:
1. **AC linear sem teto** vs **acerto travado em d20+4** → cliff de DEX em 15, STR-acerto inútil, só-crit.
2. **Crit ignora AC e dá ×2** → único furo contra DEX vira também *one-shot* letal.
3. **ATK linear (base+STR) e HP linear (base+CON×8)** sem mitigação cruzada → o build ótimo universal é
   **"DEX ~15 + resto em STR"**; CON e DEX-excedente viram lixo.

**Build ótimo single universal hoje:** DEX 15 (bate na parede) + maximizar STR/ATK. Tudo o mais é sub-ótimo.

## Alavancas possíveis (a discutir — nada implementado)

- **A. Achatar a AC:** AC = 10 + DEX/2 (ou raiz/log) → tira o cliff, DEX vira *soft* defensivo.
- **B. Acerto escalar melhor:** trocar `STR/20` por algo que acompanhe a AC (ex.: `STR/10` ou `+nível/2`),
   pra acerto normal continuar relevante em níveis altos.
- **C. Crit ×1.5 em vez de ×2** (ou cap de dano por golpe = X% do HP do alvo) → mata o one-shot.
- **D. Diminishing returns de atributo** (cada ponto além de K rende menos) → reduz o atropelo pior-vs-melhor.
- **E. Piso de hit-chance** (ex.: sempre 5–10% de errar e 5% de acertar, estilo Pokémon) → tira o "imune a normal".
- **F. Validar o triângulo COM arma+elemento** antes de mexer em classe (a sonda exclui o que deveria criá-lo).

> Recomendação: priorizar **A+B juntos** (resolve a parede de AC, o motor de acerto volta a fazer sentido em
> todo nível) e **C** (mata o one-shot). Depois re-rodar a sonda e checar pior-vs-melhor + triângulo com gear.
