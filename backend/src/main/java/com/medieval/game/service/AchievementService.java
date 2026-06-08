package com.medieval.game.service;

import com.medieval.game.enums.Achievement;
import com.medieval.game.enums.AchievementMetric;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.model.PlayerAchievement;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerAchievementRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Achievements + títulos. [TITULOS] Desbloqueia por marco (valor da métrica ≥ threshold),
 * persiste em player_achievements, e o jogador escolhe 1 título ativo entre os desbloqueados.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AchievementService {

    private final PlayerAchievementRepository achievementRepository;
    private final WarriorRepository           warriorRepository;
    private final PlayerRepository            playerRepository;
    private final MailService                 mailService;

    // ── Título exibido (puro, sem DB) — usado pelos DTOs de ranking/guilda/arena. ──
    /**
     * Título ativo do player como string (ou "" se nenhum). NÃO toca no banco nem em lazy fields
     * (só lê {@code player.activeTitle}) → seguro fora de @Transactional e barato por linha de ranking.
     * O desbloqueio é validado no selectTitle e nunca é revertido, então o valor guardado é confiável.
     */
    public static String titleString(Player player) {
        String id = player.getActiveTitle();
        if (id == null || id.isBlank()) return "";
        try { // [I18N] título no idioma de QUEM lê (ranking/guilda/header) — Messages.tr usa o locale do request
            Achievement a = Achievement.valueOf(id);
            return Messages.tr("achievement." + a.name() + ".title", a.title);
        } catch (IllegalArgumentException e) { return ""; }
    }

    // ── Desbloqueio ────────────────────────────────────────────────────────────
    /** Silencioso (sem mail) — usado pela checagem lazy da página. */
    @Transactional
    public List<Achievement> checkAndUnlock(Player playerArg) {
        return checkAndUnlock(playerArg, false);
    }

    /**
     * Avalia todos os achievements e persiste os recém-cumpridos. Retorna os NOVOS. Idempotente.
     * {@code notify=true} manda 1 mail por desbloqueio (gatilhos de gameplay); a checagem lazy da
     * página usa {@code false} pra não spammar todos os marcos já batidos na 1ª visita.
     */
    @Transactional
    public List<Achievement> checkAndUnlock(Player playerArg, boolean notify) {
        Player  p = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        Warrior w = warriorRepository.findByPlayer(p).orElse(null);
        Set<Achievement> already = unlockedSet(p);
        List<Achievement> newly = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            if (already.contains(a)) continue;
            if (metricValue(a.metric, p, w) >= a.threshold) {
                achievementRepository.save(new PlayerAchievement(p, a));
                newly.add(a);
            }
        }
        if (!newly.isEmpty()) {
            log.info("[AchievementService] player={} unlocked {}", p.getId(), newly);
            if (notify) for (Achievement a : newly)
                mailService.sendSystemMail(p, "🏆 Achievement unlocked: " + a.displayName
                        + " — new title \"" + a.title + "\" available! Pick it in Achievements.");
        }
        return newly;
    }

    /** [TITULOS] true se o player já desbloqueou este achievement. */
    public boolean has(Player player, Achievement a) {
        return achievementRepository.existsByPlayerAndAchievement(player, a);
    }

    private Set<Achievement> unlockedSet(Player p) {
        Set<Achievement> set = EnumSet.noneOf(Achievement.class);
        achievementRepository.findByPlayer(p).forEach(pa -> set.add(pa.getAchievement()));
        return set;
    }

    private long metricValue(AchievementMetric m, Player p, Warrior w) {
        return switch (m) {
            case LEVEL          -> w != null ? w.getLevel() : 0;
            case ARENA_WINS     -> p.getArenaWins();
            case RANK_POINTS    -> p.getRankPoints();
            case TOWER_FLOOR    -> p.getTowerBestFloor();
            case WEALTH         -> p.totalBronze();
            case CLASS_WARRIOR  -> w != null && w.getWarriorClass() == WarriorClass.WARRIOR  ? 1 : 0;
            case CLASS_ARCHER   -> w != null && w.getWarriorClass() == WarriorClass.ARCHER   ? 1 : 0;
            case CLASS_MERCHANT -> w != null && w.getWarriorClass() == WarriorClass.MERCHANT ? 1 : 0;
            case GUILD_MEMBER   -> p.getGuild() != null ? 1 : 0;
            case GUILD_LEADER   -> isLeader(p) ? 1 : 0;
            case MANUAL         -> 0; // [TITULOS] dirigido por evento — nunca auto-desbloqueia (só via grant())
        };
    }

    /**
     * [TITULOS] Desbloqueio DIRIGIDO POR EVENTO (ex.: a escolha no topo da Torre — matar/poupar o Rei).
     * Idempotente; manda o mail de desbloqueio. Para achievements `MANUAL` que não saem de métrica.
     * @return true se foi desbloqueado agora (false se já tinha).
     */
    @Transactional
    public boolean grant(Player playerArg, Achievement a) {
        Player p = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        if (achievementRepository.existsByPlayerAndAchievement(p, a)) return false;
        achievementRepository.save(new PlayerAchievement(p, a));
        log.info("[AchievementService] player={} granted (event) {}", p.getId(), a);
        mailService.sendSystemMail(p, "🏆 Achievement unlocked: " + a.displayName
                + " — new title \"" + a.title + "\" available! Pick it in Achievements.");
        return true;
    }

    private boolean isLeader(Player p) {
        return p.getGuild() != null && p.getGuild().getLeaderId() != null
                && p.getGuild().getLeaderId().equals(p.getId());
    }

    // ── Página / seleção ───────────────────────────────────────────────────────
    public record AchievementView(String id, String category, String displayName, String description,
                                  String title, boolean unlocked, long current, long threshold) {}
    public record ListResult(String activeTitle, List<AchievementView> achievements) {}

    /** Lista o catálogo com status do player (roda checkAndUnlock antes como rede de segurança). */
    @Transactional
    public ListResult list(Player playerArg) {
        Player p = playerRepository.findById(playerArg.getId()).orElseThrow();
        checkAndUnlock(p);
        Warrior w = warriorRepository.findByPlayer(p).orElse(null);
        Set<Achievement> unlocked = unlockedSet(p);
        List<AchievementView> views = new ArrayList<>();
        for (Achievement a : Achievement.values()) {
            if (a.hidden && !unlocked.contains(a)) continue; // [TITULOS] oculto até desbloquear (anti-spoiler)
            long cur = metricValue(a.metric, p, w);
            String base = "achievement." + a.name(); // [I18N] EN = o catálogo (default do tr)
            views.add(new AchievementView(a.name(), a.category.displayName,
                    Messages.tr(base + ".display", a.displayName),
                    Messages.tr(base + ".desc",    a.description),
                    Messages.tr(base + ".title",   a.title),
                    unlocked.contains(a), Math.min(cur, a.threshold), a.threshold));
        }
        return new ListResult(titleString(p), views);
    }

    /** Seleciona o título ativo (valida desbloqueio). id null/blank/"none" limpa. */
    @Transactional
    public String selectTitle(Player playerArg, String achievementId) {
        Player p = playerRepository.findById(playerArg.getId()).orElseThrow();
        if (achievementId == null || achievementId.isBlank() || "none".equalsIgnoreCase(achievementId)) {
            p.setActiveTitle(null);
            playerRepository.save(p);
            return "";
        }
        Achievement a;
        try { a = Achievement.valueOf(achievementId); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown title."); }
        if (!achievementRepository.existsByPlayerAndAchievement(p, a))
            throw new IllegalStateException("Title not unlocked yet.");
        p.setActiveTitle(a.name());
        playerRepository.save(p);
        return a.title;
    }
}
