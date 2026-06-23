package com.medieval.game.service;

import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [DAILY] Recompensa de login diária — ciclo de 7 dias dando peixe de stamina (battery loop p/ reter
 * o novato). Reset por comparação de data (sem scheduler). Faltar um dia zera o streak (volta ao dia 1).
 * Bag cheia → o que não couber vai por mail de recurso ({@link MailService#sendResourceMail}). Os números
 * (tabela do ciclo) são placeholders pra tuning no playtest. Desenho: docs/PLANO_RETENCAO_NOVATO.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyRewardService {

    private final PlayerRepository playerRepository;
    private final GatheringService gatheringService;
    private final MailService      mailService;

    /** Recompensa de um dia do ciclo: peixe de stamina + quantidade + bronze opcional (bônus do dia 7). */
    public record DayReward(ResourceType fish, int qty, long bronze) {}

    public static final int CYCLE_LEN = 7;

    // Ciclo de 7 dias (placeholders). Peixes de stamina escalando; dia 7 = lendário + bônus de bronze.
    private static final DayReward[] CYCLE = {
        new DayReward(ResourceType.SMALL_FISH,     2,   0),
        new DayReward(ResourceType.SMALL_FISH,     3,   0),
        new DayReward(ResourceType.SALMON,         2,   0),
        new DayReward(ResourceType.SALMON,         3,   0),
        new DayReward(ResourceType.TUNA,           2,   0),
        new DayReward(ResourceType.TUNA,           3,   0),
        new DayReward(ResourceType.LEGENDARY_FISH, 1, 500),
    };

    private int cycleDayFor(int streak) { return ((Math.max(1, streak) - 1) % CYCLE_LEN) + 1; }

    /** O streak que uma coleta HOJE produziria (sem alterar nada). */
    private int pendingStreak(Player p, LocalDate today) {
        LocalDate last = p.getLastDailyClaimDate();
        if (last == null)                    return 1;
        if (last.equals(today))              return p.getDailyStreak();      // já coletou hoje
        if (last.equals(today.minusDays(1))) return p.getDailyStreak() + 1;  // dia consecutivo → continua
        return 1;                                                            // quebrou o streak → volta ao dia 1
    }

    public boolean canClaim(Player p) {
        LocalDate last = p.getLastDailyClaimDate();
        return last == null || last.isBefore(LocalDate.now());
    }

    /** Status p/ a UI: pode coletar?, streak atual, dia-no-ciclo de hoje, e a tabela dos 7 dias. */
    public Map<String, Object> status(Player player) {
        LocalDate today = LocalDate.now();
        int pending  = pendingStreak(player, today);
        int cycleDay = cycleDayFor(pending);

        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < CYCLE_LEN; i++) {
            DayReward dr = CYCLE[i];
            days.add(Map.of(
                "day",      i + 1,
                "fish",     dr.fish().name(),
                "fishName", Messages.tr("resource." + dr.fish().name() + ".name", dr.fish().displayName),
                "qty",      dr.qty(),
                "bronze",   dr.bronze()
            ));
        }
        // [VARREDURA] streak EFETIVO (consistente com claimDay): o streak guardado pode estar obsoleto se o
        // jogador perdeu um dia (ex.: guardado=5 mas pending=1 → exibia "streak 5" destacando o dia 1). Vale
        // só se a última coleta foi hoje ou ontem; senão o streak já está quebrado (0).
        LocalDate last = player.getLastDailyClaimDate();
        int effectiveStreak = (last != null && (last.equals(today) || last.equals(today.minusDays(1))))
                ? player.getDailyStreak() : 0;
        return Map.of(
            "canClaim", canClaim(player),
            "streak",   effectiveStreak,
            "claimDay", cycleDay,   // dia-no-ciclo (1..7) que a coleta de hoje cai → a UI destaca
            "days",     days
        );
    }

    /** Coleta a recompensa de hoje. Entrega o peixe na bag; o que não couber vai por mail de recurso. */
    @Transactional
    public Map<String, Object> claim(Player playerArg) {
        Player player = playerRepository.findById(playerArg.getId()).orElseThrow();
        LocalDate today = LocalDate.now();
        if (!canClaim(player))
            throw new com.medieval.game.config.LocalizedException(
                    "error.daily_claimed", "You already claimed today's reward. Come back tomorrow!");

        int newStreak = pendingStreak(player, today);
        int cycleDay  = cycleDayFor(newStreak);
        DayReward dr  = CYCLE[cycleDay - 1];

        long added  = gatheringService.addResource(player, dr.fish(), dr.qty());
        int  mailed = dr.qty() - (int) added;
        if (mailed > 0)
            mailService.sendResourceMail(player, "Daily login reward (bag was full)", dr.fish(), mailed);
        if (dr.bronze() > 0)
            player.addBronzeAmount(dr.bronze());

        player.setDailyStreak(newStreak);
        player.setLastDailyClaimDate(today);
        playerRepository.save(player);

        log.info("[DailyRewardService] player={} claim day={} streak={} fish={}x{} added={} mailed={} bronze={}",
                player.getId(), cycleDay, newStreak, dr.fish(), dr.qty(), added, mailed, dr.bronze());

        return Map.of(
            "fish",       dr.fish().name(),
            "fishName",   Messages.tr("resource." + dr.fish().name() + ".name", dr.fish().displayName),
            "qty",        dr.qty(),
            "addedToBag", added,
            "mailed",     mailed,
            "bronze",     dr.bronze(),
            "streak",     newStreak,
            "claimDay",   cycleDay
        );
    }
}
