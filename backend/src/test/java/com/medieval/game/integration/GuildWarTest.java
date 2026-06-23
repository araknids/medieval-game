package com.medieval.game.integration;

import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Guild;
import com.medieval.game.model.GuildWar;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.GuildWarRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.GuildWarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Guerra de Guilda: elegibilidade, declarar, atacar (kill simétrica + loot), resolução + de-level. [GUERRA_GUILDA]
@DisplayName("Guild War | elegibilidade, ataque (kill/loot), resolução (de-level)")
class GuildWarTest extends BaseIntegrationTest {

    @Autowired GuildWarService    guildWarService;
    @Autowired GuildWarRepository warRepo;
    @Autowired GuildRepository    guildRepository;
    @Autowired PlayerRepository   playerRepository;
    @Autowired WarriorRepository  warriorRepository;

    private Guild guild(boolean everControlled, long lifetimeGold) {
        Guild g = new Guild();
        g.setName("GW_" + uniqueUser("g"));
        g.setLeaderId(-1L);
        g.setEverControlledTerritory(everControlled);
        g.setLifetimeGold(lifetimeGold);
        g.setTreasuryBronze(lifetimeGold);
        g.recomputeLevel();
        return guildRepository.save(g);
    }

    private Player member(Guild g, int atk, int def, int hp) {
        String u = uniqueUser("gwm");
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x"); p.setGuild(g);
        p = playerRepository.save(p);
        Warrior w = new Warrior();
        w.setName("W_" + u); w.setWarriorClass(WarriorClass.WARRIOR); w.setPlayer(p);
        w.setAttack(atk); w.setDefense(def); w.setHealth(hp); w.setStrength(800); // STR alto → acerta no d20
        w.setCurrentHpSnapshot(100); w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        return p;
    }

    private Player leaderOf(Guild g, int atk, int def, int hp) {
        Player leader = member(g, atk, def, hp);
        g.setLeaderId(leader.getId());
        guildRepository.save(g);
        return leader;
    }

    private Player reload(Player p) { return playerRepository.findById(p.getId()).orElseThrow(); }
    private Guild  reload(Guild g)  { return guildRepository.findById(g.getId()).orElseThrow(); }

    // ── Elegibilidade ──
    @Test
    @DisplayName("Sem ter controlado território → não pode declarar/ser declarada")
    void declare_requiresTerritory() {
        Guild a = guild(false, 0); Player la = leaderOf(a, 100, 100, 100);
        Guild b = guild(true, 0);  leaderOf(b, 100, 100, 100);
        assertThatThrownBy(() -> guildWarService.declare(reload(la), b.getId()))
                .isInstanceOf(IllegalStateException.class); // minha guild não é elegível

        Guild a2 = guild(true, 0); Player la2 = leaderOf(a2, 100, 100, 100);
        Guild b2 = guild(false, 0); leaderOf(b2, 100, 100, 100);
        assertThatThrownBy(() -> guildWarService.declare(reload(la2), b2.getId()))
                .isInstanceOf(IllegalStateException.class); // alvo não é elegível
    }

    @Test
    @DisplayName("Declarar cria a guerra; não dá pra ter 2 ao mesmo tempo")
    void declare_oneWarAtATime() {
        Guild a = guild(true, 0); Player la = leaderOf(a, 100, 100, 100);
        Guild b = guild(true, 0); leaderOf(b, 100, 100, 100);
        Guild c = guild(true, 0); leaderOf(c, 100, 100, 100);

        GuildWar war = guildWarService.declare(reload(la), b.getId());
        assertThat(war.getStatus()).isEqualTo(GuildWar.Status.ACTIVE);
        assertThatThrownBy(() -> guildWarService.declare(reload(la), c.getId()))
                .isInstanceOf(IllegalStateException.class); // já em guerra
    }

    // ── Ataque: vitória → kill + loot + escudo ──
    @Test
    @DisplayName("Atacante forte vence: +1 kill, loot no inimigo, vítima escudada, estamina gasta")
    void attack_win_givesKillLootShield() {
        Guild a = guild(true, 0); Player la = leaderOf(a, 9999, 9999, 99999);
        Guild b = guild(true, 0); Player tb = leaderOf(b, 1, 0, 10);
        // dá bronze pra vítima (loot)
        tb.addBronzeAmount(10_000); playerRepository.save(tb);

        guildWarService.declare(reload(la), b.getId());
        long attackerBronzeBefore = reload(la).totalBronze();
        long victimBronzeBefore   = reload(tb).totalBronze();
        GuildWarService.AttackResult r = guildWarService.attack(reload(la), tb.getId());

        assertThat(r.won()).isTrue();
        assertThat(r.myKills()).isEqualTo(1);
        assertThat(r.enemyKills()).isZero();
        Player victim = reload(tb);
        assertThat(victim.isPvpShielded()).isTrue();                              // escudo pós-derrota
        assertThat(victim.totalBronze()).isLessThan(victimBronzeBefore);          // perdeu bronze
        assertThat(reload(la).totalBronze()).isGreaterThan(attackerBronzeBefore); // ganhou metade
        assertThat(reload(la).getCalculatedStamina()).isLessThan(100); // estamina gasta

        // não pode reatacar o escudado
        assertThatThrownBy(() -> guildWarService.attack(reload(la), tb.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Ataque: derrota → kill simétrica pro defensor ──
    @Test
    @DisplayName("Atacante fraco perde: kill vai pra guild inimiga (simétrico)")
    void attack_loss_symmetricKill() {
        Guild a = guild(true, 0); Player la = leaderOf(a, 1, 0, 10);            // atacante fraco
        Guild b = guild(true, 0); Player tb = leaderOf(b, 9999, 9999, 99999);  // defensor forte

        guildWarService.declare(reload(la), b.getId());
        GuildWarService.AttackResult r = guildWarService.attack(reload(la), tb.getId());

        assertThat(r.won()).isFalse();
        assertThat(r.myKills()).isZero();
        assertThat(r.enemyKills()).isEqualTo(1); // a guild inimiga pontuou
    }

    @Test
    @DisplayName("Não dá pra atacar quem não é da guild inimiga")
    void attack_nonEnemy_rejected() {
        Guild a = guild(true, 0); Player la = leaderOf(a, 100, 100, 100);
        Player ally = member(a, 100, 100, 100);
        Guild b = guild(true, 0); leaderOf(b, 100, 100, 100);
        guildWarService.declare(reload(la), b.getId());
        assertThatThrownBy(() -> guildWarService.attack(reload(la), ally.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Resolução: mais kills leva 25%, perdedor regride, vencedor sobe ──
    @Test
    @DisplayName("Resolução: vencedor leva 25% do acumulado; perdedor cai de nível, vencedor sobe")
    void resolve_winnerGainsLoserDelevels() {
        Guild a = guild(true, 145_000); Player la = leaderOf(a, 100, 100, 100); // Lv5 (perto do 6)
        Guild b = guild(true, 100_000); leaderOf(b, 100, 100, 100);             // Lv5 (no limiar)
        assertThat(reload(a).getLevel()).isEqualTo(5);
        assertThat(reload(b).getLevel()).isEqualTo(5);

        GuildWar war = guildWarService.declare(reload(la), b.getId());
        war.setKillsA(3); war.setKillsB(0); warRepo.save(war);
        guildWarService.resolve(warRepo.findById(war.getId()).orElseThrow());

        Guild winner = reload(a), loser = reload(b);
        assertThat(loser.getLifetimeGold()).isEqualTo(75_000);  // 100k − 25%
        assertThat(loser.getLevel()).isEqualTo(4);              // REGREDIU 5→4
        assertThat(winner.getLifetimeGold()).isEqualTo(170_000);// 145k + 25k
        assertThat(winner.getLevel()).isEqualTo(6);             // SUBIU 5→6
        assertThat(warRepo.findById(war.getId()).orElseThrow().getStatus()).isEqualTo(GuildWar.Status.RESOLVED);
    }

    @Test
    @DisplayName("Empate → sem transferência de gold")
    void resolve_draw_noTransfer() {
        Guild a = guild(true, 100_000); Player la = leaderOf(a, 100, 100, 100);
        Guild b = guild(true, 100_000); leaderOf(b, 100, 100, 100);
        GuildWar war = guildWarService.declare(reload(la), b.getId());
        war.setKillsA(2); war.setKillsB(2); warRepo.save(war);
        guildWarService.resolve(warRepo.findById(war.getId()).orElseThrow());

        assertThat(reload(a).getLifetimeGold()).isEqualTo(100_000);
        assertThat(reload(b).getLifetimeGold()).isEqualTo(100_000);
        assertThat(warRepo.findById(war.getId()).orElseThrow().getWinnerGuildId()).isNull();
    }
}
