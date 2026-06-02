package com.medieval.game.service;

import com.medieval.game.enums.Territory;
import com.medieval.game.service.TerritoryService.BrawlResult;
import com.medieval.game.service.TerritoryService.Fighter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the GuildBrawl (King-of-the-Hill) mechanic and territory war edge cases.
 *
 * Key scenario: when 3 guilds all beat the defenders in sequence,
 * the last winner is determined by remaining HP from previous fights.
 */
@DisplayName("Territory War — GuildBrawl and Multi-Attacker Scenarios")
class TerritoryWarTest {

    // TerritoryService only needs BattleSimulator for guildBrawl — pass nulls for repos
    private TerritoryService service;

    @BeforeEach
    void setup() {
        service = new TerritoryService(null, null, null, null, null, new BattleSimulator());
    }

    // Create a fighter with controlled stats (no warrior entity, NPC-style)
    private Fighter fighter(String name, int atk, int def, int hp) {
        return new Fighter(null, name, atk, def, hp, 0, null);
    }

    // ── 1v1: stronger fighter wins ─────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("1v1: vastly superior attacker wins")
    void brawl_strongAttackerWins() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("Strong", 100, 50, 300)),
            List.of(fighter("Weak",     5,  2,  20)),
            Territory.FORTALEZA_MALDITA
        );
        assertThat(result.attackersWon()).isTrue();
    }

    @RepeatedTest(10)
    @DisplayName("1v1: vastly superior defender wins")
    void brawl_strongDefenderWins() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("Weak",     5,  2,  20)),
            List.of(fighter("Strong", 100, 50, 300)),
            Territory.FORTALEZA_MALDITA
        );
        assertThat(result.attackersWon()).isFalse();
    }

    // ── 2v1: attacker advantage ────────────────────────────────────────────────

    @Test
    @DisplayName("2v1: two equivalent attackers vs one defender — attackers win majority")
    void brawl_twoAttackersVsOneDefender_attackersWinMajority() {
        int wins = 0;
        for (int i = 0; i < 10; i++) {
            BrawlResult r = service.guildBrawl(
                List.of(fighter("A1", 25, 15, 80), fighter("A2", 25, 15, 80)),
                List.of(fighter("D1", 25, 15, 80)),
                Territory.FORTALEZA_MALDITA
            );
            if (r.attackersWon()) wins++;
        }
        // 2 attackers vs 1 defender → attackers should win most of the time
        assertThat(wins).isGreaterThan(5);
    }

    // ── Tiebreaker: winner carries reduced HP ──────────────────────────────────

    @Test
    @DisplayName("Tiebreaker: fresh fighter beats tired fighter with 1 HP")
    void tiebreaker_freshBeats1Hp() {
        int wins = 0;
        for (int i = 0; i < 10; i++) {
            BrawlResult r = service.guildBrawl(
                List.of(fighter("FreshGuild", 50, 30, 200)),
                List.of(fighter("TiredGuild", 50, 30,   1)), // 1 HP remaining from prev fight
                Territory.MINAS_DE_FERRO_NEGRO
            );
            if (r.attackersWon()) wins++;
        }
        // Fresh guild with 200HP vs tired guild with 1HP → fresh should win all 10
        assertThat(wins).isGreaterThan(7);
    }

    // ── Phase 2 tiebreaker: everyone uses Phase 1 HP ─────────────────────────

    @Test
    @DisplayName("Tiebreaker: both guilds use Phase 1 HP — neither guaranteed to win")
    void tiebreaker_phase1Hp_neitherGuaranteedWinner() {
        // Design doc: in Phase 2 tiebreaker, all guilds use Phase 1 HP.
        // With identical fighters, BOTH can win (not deterministic).
        // Test: verify that each can win at least once in 30 runs.

        int phase1Hp = 150;
        int guild1Wins = 0, guild2Wins = 0;
        for (int i = 0; i < 30; i++) {
            BrawlResult r = service.guildBrawl(
                List.of(fighter("Guild1", 40, 25, phase1Hp)),
                List.of(fighter("Guild2", 40, 25, phase1Hp)),
                Territory.DESFILADEIRO_DO_OSSO
            );
            if (r.attackersWon()) guild1Wins++; else guild2Wins++;
        }
        // With identical fighters neither should win ALL 30 — both win at least once
        assertThat(guild1Wins).isGreaterThan(0);
        assertThat(guild2Wins).isGreaterThan(0);
    }

    @Test
    @DisplayName("Phase 1 HP advantage wins tiebreaker: better Phase 1 HP → wins more")
    void tiebreaker_betterPhase1Hp_winsMajority() {
        // Guild A kept 200 HP from Phase 1 (beat defenders quickly)
        // Guild B kept 80 HP from Phase 1 (struggled with defenders)
        // In tiebreaker, A should win majority since it starts with more HP

        int winsWith200 = 0;
        for (int i = 0; i < 10; i++) {
            BrawlResult r = service.guildBrawl(
                List.of(fighter("GuildA_better", 40, 25, 200)), // better Phase 1 HP
                List.of(fighter("GuildB_worse",  40, 25,  80)), // worse Phase 1 HP
                Territory.MINAS_DE_FERRO_NEGRO
            );
            if (r.attackersWon()) winsWith200++;
        }
        // Guild A (200 HP Phase 1) vs Guild B (80 HP Phase 1) → A wins majority
        assertThat(winsWith200).isGreaterThan(5);
    }

    // ── Log structure ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Log always contains battle start and outcome")
    void brawl_logHasBattleStartAndOutcome() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("A", 30, 20, 100)),
            List.of(fighter("D", 30, 20, 100)),
            Territory.FORTALEZA_MALDITA
        );

        String fullLog = String.join("\n", result.log());
        assertThat(fullLog).contains("The battle begins!");
        String lastLine = result.log().get(result.log().size() - 1);
        assertThat(lastLine).containsAnyOf("Attackers have conquered", "Defenders held");
    }

    // ── Empty list edge cases ──────────────────────────────────────────────────

    @Test
    @DisplayName("No attackers → defenders hold territory")
    void noAttackers_defendersWin() {
        BrawlResult result = service.guildBrawl(
            List.of(),
            List.of(fighter("D1", 30, 20, 100)),
            Territory.FORTALEZA_MALDITA
        );
        assertThat(result.attackersWon()).isFalse();
    }

    @Test
    @DisplayName("No defenders → attackers conquer immediately")
    void noDefenders_attackersWin() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("A1", 30, 20, 100)),
            List.of(),
            Territory.FORTALEZA_MALDITA
        );
        assertThat(result.attackersWon()).isTrue();
    }

    // ── Debuff cap ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Debuff caps at 50% regardless of streak")
    void debuffCapsAt50Percent() {
        // TerritoryControl.debuffPercent() = min(50, streak * 5)
        // Already tested in TerritoryServiceTest — just verify via direct model
        var ctrl = new com.medieval.game.model.TerritoryControl();
        ctrl.setDefenseStreak(100); // extreme streak
        assertThat(ctrl.debuffPercent()).isEqualTo(50);
    }
}
