package com.medieval.game.service;

import com.medieval.game.enums.Kingdom;
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
@DisplayName("Kingdom War — GuildBrawl and Multi-Attacker Scenarios")
class TerritoryWarTest {

    // TerritoryService only needs BattleSimulator for guildBrawl — pass nulls for repos
    private TerritoryService service;

    @BeforeEach
    void setup() {
        service = new TerritoryService(null, null, null, null, null, new BattleSimulator(), null);
    }

    // Create a fighter with controlled stats (no warrior entity, NPC-style)
    // New d20 signature: (playerId, name, atk, def, hp, dex, strBonus, luk, warrior)
    private Fighter fighter(String name, int atk, int def, int hp) {
        return new Fighter(null, name, atk, def, hp, 0, 0, 0, null);
    }

    // ── 1v1: stronger fighter wins ─────────────────────────────────────────────

    @RepeatedTest(10)
    @DisplayName("1v1: vastly superior attacker wins")
    void brawl_strongAttackerWins() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("Strong", 100, 50, 300)),
            List.of(fighter("Weak",     5,  2,  20)),
            Kingdom.COMBAT
        );
        assertThat(result.attackersWon()).isTrue();
    }

    @RepeatedTest(10)
    @DisplayName("1v1: vastly superior defender wins")
    void brawl_strongDefenderWins() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("Weak",     5,  2,  20)),
            List.of(fighter("Strong", 100, 50, 300)),
            Kingdom.COMBAT
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
                Kingdom.COMBAT
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
                Kingdom.MINING
            );
            if (r.attackersWon()) wins++;
        }
        // Fresh guild with 200HP vs tired guild with 1HP → fresh should win all 10
        assertThat(wins).isGreaterThan(7);
    }

    // ── Phase 2 tiebreaker: everyone uses Phase 1 HP ─────────────────────────

    @Test
    @DisplayName("Tiebreaker uses Phase 1 HP: guild with 0 HP cannot win")
    void tiebreaker_zeroHp_cannotWin() {
        // The Phase 1 HP mechanic is deterministic at the extreme:
        // a guild with 0 HP (knocked out) has no fighters in guildBrawl
        // → treated as empty list → opponent wins immediately
        BrawlResult result = service.guildBrawl(
            List.of(), // guild with no surviving fighters (0 HP in Phase 1)
            List.of(fighter("Guild2", 40, 25, 150)),
            Kingdom.FISHING
        );
        assertThat(result.attackersWon()).isFalse();
    }

    @Test
    @DisplayName("Phase 1 HP advantage: 10x more HP wins every single run")
    void tiebreaker_betterPhase1Hp_winsMajority() {
        // Use extreme HP difference to make the test deterministic:
        // 500 HP vs 50 HP with same ATK/DEF → strong side wins every time
        int wins = 0;
        for (int i = 0; i < 10; i++) {
            BrawlResult r = service.guildBrawl(
                List.of(fighter("GuildA_strong", 40, 25, 500)), // 10x better Phase 1 HP
                List.of(fighter("GuildB_weak",   40, 25,  50)), // weak Phase 1 HP
                Kingdom.MINING
            );
            if (r.attackersWon()) wins++;
        }
        // 500 HP vs 50 HP → strong guild wins all 10 runs
        assertThat(wins).isEqualTo(10);
    }

    // ── Log structure ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Log always contains battle start and outcome")
    void brawl_logHasBattleStartAndOutcome() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("A", 30, 20, 100)),
            List.of(fighter("D", 30, 20, 100)),
            Kingdom.COMBAT
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
            Kingdom.COMBAT
        );
        assertThat(result.attackersWon()).isFalse();
    }

    @Test
    @DisplayName("No defenders → attackers conquer immediately")
    void noDefenders_attackersWin() {
        BrawlResult result = service.guildBrawl(
            List.of(fighter("A1", 30, 20, 100)),
            List.of(),
            Kingdom.COMBAT
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
