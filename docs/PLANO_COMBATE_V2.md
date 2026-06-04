# Plano — Rebalance de Combate + Economia (Combate V2)

> Documento de planejamento. Decisões/pilares aprovados com o dono em 2026-06-04.
> **Status: TODAS as 4 fases IMPLEMENTADAS (452 testes verdes).**
> - Fase 1 ✅ Combate (mitigação % + timeout PvE/PvP) · Fase 2 ✅ Torre V2 (lvl1 não passa) ·
>   Fase 3 ✅ Pesca (não é mais fonte infinita) · Fase 4 ✅ Quests com nicho (curtas=bronze, longas=XP).
> Regra do projeto: discutir + documentar ANTES de codar.

Origem: análise de balanceamento (2026-06-04) que apontou:
- Torre fácil demais (lvl1 pelado vence o Andar 1).
- Tanque de CON vence "no tempo" (teto de 40 rounds + ganha quem tem mais HP).
- DEF vira imunidade (dano `max(1, ATK−DEF)` → chefe bate "1" num lvl1).
- Pesca repõe mais estamina do que custa (fura o freio de estamina).
- Boss Hunt domina XP e bronze por estamina → quests menores inúteis.

---

## Pilar 1 — Defesa vira % (mitigação por curva)

Trocar `dano = max(1, ATK − DEF)` por **mitigação percentual**:

> `dano = round(ATK × 100 / (100 + DEF))`, mínimo **1**. Crítico = ×2 **após** a mitigação.

| DEF | mitigação |
|----|----|
| 12 | 11% |
| 50 | 33% |
| 100 | 50% |
| 200 | 67% |

- Nunca zera, nunca vira inútil. A constante (100) controla o peso da DEF — ajustável.
- Aplica em **todo** combate (Arena, Zona, Torre, Combate PvE, Guerra de Guild, Quest de Reino).
- **Consequência:** os stats de TODOS os inimigos/NPCs (Torre, Zona, Combate PvE, mults de guerra,
  geração de oponente da Arena) foram tunados para subtração — **re-tunar** para a curva nova.

## Pilar 2 — Regra do teto de 40 rounds

Hoje, se ninguém morre em 40 rounds, ganha quem tem **mais HP absoluto** → tanque vence sem matar.

- **PvE** (Torre, Combate PvE, Quest de Reino): não matou em 40 rounds → **o desafiante (1º = jogador) PERDE.**
  Obriga a ter **dano**, não só HP. Mata o abuso de CON.
- **PvP** (Arena, Guerra de Guild, Emboscada de Zona): desempate por **% de HP restante**
  (`hpAtual / hpInicial`), mais justo que HP absoluto.

Implementação: `BattleSimulator.simulateDetailed(..., boolean firstLosesOnTimeout)`.
- `true` para os 3 callers PvE; `false` (default, %HP) para os 4 PvP.
- Lógica de vitória: morreu alguém → quem está vivo vence; timeout → regra acima.

> Mantém todo o sabor d20 (acerto, crítico, Sorte/Fortune Save, logs). Só muda a fórmula de dano e o desempate.

---

## Fase 2 — Torre V2 (curva por nível-alvo)

Re-derivar os stats dos chefes para que o **nível-alvo** vença e o sub-nível perca:

- Provisório (validar por simulação em teste):
  - `ATK = 12 + 5·floor` · `DEF = 8 + 4·floor` · `HP = 140 + 60·floor`
  - `AC = 11 + min(floor, 25)` · `strBonus = min(floor/8, 4)` · `luk = min(floor, 20)`
- **Nível recomendado por andar** exibido na UI (ex.: `≈ lv (floor×3)`).
- **Teste de intenção** (test-driven balancing): assert que um **lvl1 pelado perde no Andar 1-2** e
  que um personagem "no nível certo" limpa a faixa esperada. Ajustar a fórmula até passar.
- (Opcional) trava dura de entrada por nível — provavelmente desnecessária com a curva certa.

## Fase 3 — Pesca (estamina ≈ neutra)

Hoje: 30min = `max(1, dur/5)` = 6 peixes (~+60 estamina) por 15 de custo → **fonte**.
Alvo: pesca = **converter tempo em estamina, não multiplicar**. Net ≈ 0 (no máx. levemente positivo):
- Reduzir o haul (ex.: `max(1, dur/10)`) e/ou o restauro por peixe, de modo que
  `Σ estamina dos peixes ≲ custo + regen passiva do mesmo tempo`.
- Preserva o loop pescar→comer (peixe ainda é a forma de repor estamina), mas sem furar o freio.
- Validar com teste: net de uma sessão ≤ pequeno limiar.

## Fase 4 — Quests com nicho

Hoje Boss Hunt domina XP **e** bronze por estamina **e** por tempo real. Alvo: cada tipo tem razão de existir.
- Equalizar **recompensa por estamina** entre os tipos; diferenciar por **duração/perfil**:
  - Curtas (Patrulha/Masmorra) = ganho **pequeno e frequente**, viés **bronze** (renda ativa).
  - Longas (Raid/Boss Hunt) = **XP em bloco**, viés progressão.
- (Opcional) gatear quests maiores por nível do guerreiro.
- Validar com teste: XP/estamina e bronze/estamina dentro de uma faixa parecida entre tipos.

---

## Plano de implementação por fases

1. **Fase 1 — Combate V2 (núcleo):** mitigação % no `BattleSimulator` + regra de timeout (param PvE/PvP),
   wiring dos 7 callers, re-tunar NPCs existentes, ajustar testes de combate (D20CombatTest/BattleSimulatorTest).
2. **Fase 2 — Torre V2:** curva nova + nível recomendado + testes de intenção de dificuldade.
3. **Fase 3 — Pesca:** haul/restauro pra net ≈ 0 + teste.
4. **Fase 4 — Quests:** recompensas com nicho + teste.

Cada fase: verde (full suite) + docs sincronizadas (FEATURES/GDD/TEST_PLAN) + commit.

---

## Riscos
- **Muda a sensação do jogo** (defesa %). Aprovado pelo dono.
- **Re-tunar inimigos** é o grosso do trabalho da Fase 1 — fazer com testes de simulação como rede.
- Combate é reusado por 6 sistemas → mudança no `BattleSimulator` tem alcance amplo; tests cobrem.

*Decisões: Pilar 1 (defesa %) e Pilar 2 (timeout PvE=derrota / PvP=%HP) aprovados em 2026-06-04.*
