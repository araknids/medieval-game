package com.medieval.game.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class BattleSimulator {

    // ── Textos de ataque do guerreiro ──
    private static final String[] WARRIOR_ATTACKS = {
        "avança ferozmente e desfere um golpe certeiro",
        "lança um ataque rápido e preciso",
        "arremete com toda a sua força",
        "executa uma sequência devastadora de golpes",
        "salta e golpeia com precisão",
        "aproveita uma abertura e ataca",
        "desencadeia um ataque poderoso",
        "se move rapidamente e golpeia",
        "investiga um ponto fraco e atinge",
        "recua e contra-ataca furiosamente",
    };

    // ── Textos de ataque do oponente/boss ──
    private static final String[] ENEMY_ATTACKS = {
        "contra-ataca violentamente",
        "lança um ataque selvagem",
        "responde com uma pancada pesada",
        "avança furiosamente e golpeia",
        "tenta esmagar com força bruta",
        "desfere um golpe traiçoeiro",
        "investiga uma brecha e ataca",
        "parte para o ataque sem hesitar",
        "lança um bote inesperado",
        "acerta um golpe brutal",
    };

    // ── Partes do corpo ──
    private static final String[] BODY_PARTS = {
        "no pescoço", "no ombro", "no peito", "na cabeça",
        "no braço", "no flanco", "nas pernas", "no abdômen",
        "nas costas", "no rosto",
    };

    // ── Evasões ──
    private static final String[] EVASIONS = {
        "desvia no último segundo",
        "recua habilidosamente",
        "bloqueia o golpe com sua arma",
        "rola para o lado",
        "esquiva com incrível agilidade",
        "para o impacto com seu escudo",
        "se abaixa rapidamente",
        "dança para fora do alcance",
    };

    // ── Texto de vitória ──
    private static final String[] VICTORY_TEXTS = {
        "vence a batalha!",
        "sai vitorioso!",
        "prevalece na luta!",
        "derrota o adversário!",
    };

    public List<String> simulate(
            String cName, int cAtk, int cDef, int cHp, int cEvasion,
            String oName, int oAtk, int oDef, int oHp, int oEvasion) {

        List<String> log = new ArrayList<>();
        Random rng = new Random();
        int cCurrentHp = cHp;
        int oCurrentHp = oHp;

        log.add("⚔ " + cName + " vs " + oName + " — A batalha começa!");
        log.add("HP: [" + cName + ": ❤ " + cHp + "] | [" + oName + ": ❤ " + oHp + "]");
        log.add("─────────────────────────");

        for (int round = 1; round <= 30 && cCurrentHp > 0 && oCurrentHp > 0; round++) {
            log.add("— Rodada " + round + " —");

            // Atacante ataca oponente
            if (rng.nextInt(100) < oEvasion) {
                String evade = EVASIONS[rng.nextInt(EVASIONS.length)];
                log.add("  " + oName + " " + evade + "!");
            } else {
                String attack   = WARRIOR_ATTACKS[rng.nextInt(WARRIOR_ATTACKS.length)];
                String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                int dmg = Math.max(1, cAtk - rng.nextInt(Math.max(1, oDef / 2 + 1)));
                oCurrentHp -= dmg;
                log.add("  " + cName + " " + attack + " " + bodyPart + " de "
                        + oName + "! [-" + dmg + " HP] ❤ " + Math.max(0, oCurrentHp));
            }
            if (oCurrentHp <= 0) break;

            // Oponente ataca atacante
            if (rng.nextInt(100) < cEvasion) {
                String evade = EVASIONS[rng.nextInt(EVASIONS.length)];
                log.add("  " + cName + " " + evade + "!");
            } else {
                String attack   = ENEMY_ATTACKS[rng.nextInt(ENEMY_ATTACKS.length)];
                String bodyPart = BODY_PARTS[rng.nextInt(BODY_PARTS.length)];
                int dmg = Math.max(1, oAtk - rng.nextInt(Math.max(1, cDef / 2 + 1)));
                cCurrentHp -= dmg;
                log.add("  " + oName + " " + attack + " " + bodyPart + " de "
                        + cName + "! [-" + dmg + " HP] ❤ " + Math.max(0, cCurrentHp));
            }
        }

        log.add("─────────────────────────");
        boolean cWon = cCurrentHp > oCurrentHp;
        String winner = cWon ? cName : oName;
        String loser  = cWon ? oName : cName;
        String victoryText = VICTORY_TEXTS[rng.nextInt(VICTORY_TEXTS.length)];
        log.add("🏆 " + winner + " " + victoryText);
        log.add("WINNER:" + winner + "|LOSER:" + loser); // tag para o frontend parsear
        return log;
    }
}
