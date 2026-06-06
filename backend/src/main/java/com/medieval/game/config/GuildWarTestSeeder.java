package com.medieval.game.config;

import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * ⚠️⚠️⚠️ TEMPORÁRIO — SEED DE TESTE DA GUERRA DE GUILDA. APAGAR ESTE ARQUIVO depois de testar. [TEST_SEED_REMOVE]
 *
 * Roda no boot SÓ em produção (perfil prod → não entra nos testes). Idempotente: não duplica.
 * Faz duas coisas:
 *  1) Libera a guild do player id=1 (seu char 1) a declarar guerra (ever_controlled_territory = true).
 *  2) Cria uma guild + player rival (elegível, com gold p/ saquear) p/ você declarar guerra.
 *
 * Pra remover: delete este arquivo e dá deploy. (Opcional: apague a "Rival Guild" e o player "rival_leader".)
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class GuildWarTestSeeder {

    private static final String RIVAL_GUILD = "Rival Guild";
    private static final String RIVAL_USER  = "rival_leader";

    private final GuildRepository   guildRepository;
    private final PlayerRepository  playerRepository;
    private final WarriorRepository warriorRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        try {
            // 1) libera a guild do char 1 (player id=1) a declarar guerra
            playerRepository.findById(1L).ifPresent(p -> {
                Guild g = p.getGuild();
                if (g != null && !g.isEverControlledTerritory()) {
                    g.setEverControlledTerritory(true);
                    guildRepository.save(g);
                    log.info("[TEST_SEED_REMOVE] guild '{}' (player 1) liberada p/ declarar guerra", g.getName());
                }
            });

            // 2) cria a guild + player rival uma única vez
            if (guildRepository.findByName(RIVAL_GUILD).isPresent()) {
                return; // já criado em deploy anterior
            }

            Guild rival = new Guild();
            rival.setName(RIVAL_GUILD);
            rival.setDescription("War test guild");
            rival.setGold(100_000);
            rival.setLifetimeGold(100_000);
            rival.setEverControlledTerritory(true);
            rival.setLeaderId(-1L); // ajustado abaixo
            rival.recomputeLevel();
            rival = guildRepository.save(rival);

            Player rp = new Player();
            rp.setUsername(RIVAL_USER);
            rp.setEmail(RIVAL_USER + "@test.local");
            rp.setPasswordHash("x"); // nunca loga — só existe pra ser atacado
            rp.setGuild(rival);
            rp.addBronzeAmount(10_000); // bronze p/ você saquear
            rp = playerRepository.save(rp);

            Warrior rw = new Warrior();
            rw.setName("Rival Warrior");
            rw.setWarriorClass(WarriorClass.WARRIOR);
            rw.setPlayer(rp);
            rw.setLevel(5);
            rw.setAttack(30); rw.setDefense(20); rw.setHealth(200);
            rw.setStrength(5); rw.setDexterity(5); rw.setConstitution(5); rw.setLuck(5);
            rw.setCurrentHpSnapshot(100);
            rw.setHpUpdatedAt(LocalDateTime.now());
            warriorRepository.save(rw);

            rival.setLeaderId(rp.getId());
            guildRepository.save(rival);
            log.info("[TEST_SEED_REMOVE] rival criado: guild id={} player id={} — pronto p/ declarar guerra", rival.getId(), rp.getId());
        } catch (Exception e) {
            log.warn("[TEST_SEED_REMOVE] seed da guerra de guilda falhou: {}", e.getMessage(), e);
        }
    }
}
