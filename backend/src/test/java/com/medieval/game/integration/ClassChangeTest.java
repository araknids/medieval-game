package com.medieval.game.integration;

import com.medieval.game.enums.Attribute;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.service.ClassChangeService;
import com.medieval.game.service.ClassChangeService.TrialResult;
import com.medieval.game.service.WarriorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Path Trial: Recruit no Lv10 escolhe Warrior/Archer vencendo um Guardião; classe permanente + respec. [CLASSES]
@DisplayName("Classes | Path Trial (Recruit → Warrior/Archer no Lv10)")
class ClassChangeTest extends BaseIntegrationTest {

    @Autowired ClassChangeService classService;
    @Autowired WarriorService     warriorService;
    @Autowired PlayerRepository   playerRepository;
    @Autowired WarriorRepository  warriorRepository;

    private Player newPlayer(String prefix) {
        String u = uniqueUser(prefix);
        Player p = new Player();
        p.setUsername(u); p.setEmail(u + "@t.com"); p.setPasswordHash("x");
        return playerRepository.save(p);
    }

    private Warrior makeWarrior(Player p, WarriorClass clazz, int level) {
        Warrior w = new Warrior();
        w.setName("W_" + p.getUsername());
        w.setWarriorClass(clazz);
        w.setPlayer(p);
        w.setLevel(level);
        w.setAttack(clazz.baseAttack);
        w.setDefense(clazz.baseDefense);
        w.setHealth(clazz.baseHealth);
        w.setCurrentHpSnapshot(100);
        w.setHpUpdatedAt(LocalDateTime.now());
        return warriorRepository.save(w);
    }

    private Warrior reload(Warrior w) { return warriorRepository.findById(w.getId()).orElseThrow(); }

    // ── Disponibilidade da Trial ──
    @Test
    @DisplayName("Recruit abaixo do Lv10: Trial indisponível e rejeitada")
    void trialUnavailableBelowLevel10() {
        Player p = newPlayer("cls");
        makeWarrior(p, WarriorClass.RECRUIT, 5);
        assertThat(classService.info(p).available()).isFalse();
        assertThatThrownBy(() -> classService.attemptTrial(p, WarriorClass.WARRIOR))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Recruit no Lv10: Trial disponível, com os dois caminhos")
    void trialAvailableAtLevel10() {
        Player p = newPlayer("cls");
        makeWarrior(p, WarriorClass.RECRUIT, 10);
        var info = classService.info(p);
        assertThat(info.available()).isTrue();
        assertThat(info.paths()).hasSize(2);
        assertThat(info.paths()).extracting(ClassChangeService.ClassPath::id)
                .containsExactlyInAnyOrder("WARRIOR", "ARCHER");
    }

    // ── Vitória: vira a classe + base stats + respec ──
    @Test
    @DisplayName("Vencer a Trial especializa, troca os base stats e devolve os pontos")
    void winTrial_specializesAndRefunds() {
        Player p = newPlayer("cls");
        Warrior w = makeWarrior(p, WarriorClass.RECRUIT, 10);
        // Recruit absurdamente forte → vence o Guardião com folga (STR=acerto garantido, HP enorme).
        w.setAttack(300);
        w.setStrength(800);     // strBonus = 40 → d20+40 sempre passa a AC do Guardião
        w.setConstitution(200); // HP gigante → sobrevive a qualquer crit
        w.setAvailablePoints(3);
        warriorRepository.save(w);

        TrialResult r = classService.attemptTrial(p, WarriorClass.WARRIOR);

        assertThat(r.won()).isTrue();
        assertThat(r.classId()).isEqualTo("WARRIOR");

        Warrior after = reload(w);
        assertThat(after.getWarriorClass()).isEqualTo(WarriorClass.WARRIOR);
        assertThat(after.getAttack()).isEqualTo(WarriorClass.WARRIOR.baseAttack);   // base trocado
        assertThat(after.getDefense()).isEqualTo(WarriorClass.WARRIOR.baseDefense);
        assertThat(after.getHealth()).isEqualTo(WarriorClass.WARRIOR.baseHealth);
        // respec grátis: atributos zerados, pontos gastos (800+200) devolvidos sobre os 3 livres
        assertThat(after.getStrength()).isZero();
        assertThat(after.getConstitution()).isZero();
        assertThat(after.getAvailablePoints()).isEqualTo(3 + 800 + 200);
    }

    // ── Derrota: nada muda ──
    @Test
    @DisplayName("Perder a Trial não muda a classe")
    void loseTrial_noChange() {
        Player p = newPlayer("cls");
        Warrior w = makeWarrior(p, WarriorClass.RECRUIT, 10);
        // Recruit fraquíssimo: ATK 1 → impossível causar os 160 de HP do Guardião em 40 rounds (perde no timeout).
        w.setAttack(1);
        w.setStrength(0);
        warriorRepository.save(w);

        TrialResult r = classService.attemptTrial(p, WarriorClass.WARRIOR);

        assertThat(r.won()).isFalse();
        assertThat(reload(w).getWarriorClass()).isEqualTo(WarriorClass.RECRUIT); // inalterado
    }

    // ── Guards ──
    @Test
    @DisplayName("Quem já escolheu não pode refazer a Trial")
    void alreadySpecialized_rejected() {
        Player p = newPlayer("cls");
        makeWarrior(p, WarriorClass.WARRIOR, 20);
        assertThat(classService.info(p).available()).isFalse();
        assertThatThrownBy(() -> classService.attemptTrial(p, WarriorClass.ARCHER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Não dá pra escolher RECRUIT como caminho")
    void invalidPath_rejected() {
        Player p = newPlayer("cls");
        makeWarrior(p, WarriorClass.RECRUIT, 10);
        assertThatThrownBy(() -> classService.attemptTrial(p, WarriorClass.RECRUIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Recruit inconsciente não pode fazer a Trial")
    void koRecruit_rejected() {
        Player p = newPlayer("cls");
        Warrior w = makeWarrior(p, WarriorClass.RECRUIT, 10);
        w.setCurrentHpSnapshot(0);
        w.setHpUpdatedAt(LocalDateTime.now());
        warriorRepository.save(w);
        assertThatThrownBy(() -> classService.attemptTrial(p, WarriorClass.WARRIOR))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Caps de atributo por classe ──
    @Test
    @DisplayName("Caps são por classe: Warrior puxa STR, Archer puxa DEX")
    void caps_perClass() {
        assertThat(WarriorClass.WARRIOR.capFor(Attribute.STRENGTH))
                .isGreaterThan(WarriorClass.ARCHER.capFor(Attribute.STRENGTH));
        assertThat(WarriorClass.ARCHER.capFor(Attribute.DEXTERITY))
                .isGreaterThan(WarriorClass.WARRIOR.capFor(Attribute.DEXTERITY));

        // Integração: spendPoint respeita o cap da classe (Warrior trava STR em 80).
        Player p = newPlayer("cls");
        Warrior w = makeWarrior(p, WarriorClass.WARRIOR, 30);
        w.setStrength(WarriorClass.WARRIOR.strCap - 1);
        w.setAvailablePoints(5);
        warriorRepository.save(w);

        warriorService.spendPoint(p, Attribute.STRENGTH); // chega no cap (80) — ok
        assertThat(reload(w).getStrength()).isEqualTo(WarriorClass.WARRIOR.strCap);
        assertThatThrownBy(() -> warriorService.spendPoint(p, Attribute.STRENGTH)) // passar do cap → erro
                .isInstanceOf(IllegalStateException.class);
    }
}
