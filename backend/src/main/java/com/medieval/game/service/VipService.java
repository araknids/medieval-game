package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VipService {

    private static final int  VIP_COST_STONES   = 15;
    private static final int  VIP_DAYS          = 30;
    private static final int  VIP_HEAL_CD_MIN   = 10;

    private final PlayerRepository playerRepository;

    // ── Comprar / Renovar VIP ────────────────────────────────────────────────

    @Transactional
    public Player buyVip(Player player) {
        log.info("[VipService] player={} action=buyVip", player.getId());

        if (player.getSoulStones() < VIP_COST_STONES) {
            log.warn("[VipService] player={} REJECTED: not enough SoulStones ({}<{})",
                    player.getId(), player.getSoulStones(), VIP_COST_STONES);
            throw new com.medieval.game.config.LocalizedException("error.soulstones_required", "Not enough SoulStones. Required: {0}", VIP_COST_STONES);
        }

        player.setSoulStones(player.getSoulStones() - VIP_COST_STONES);

        LocalDateTime base = player.isVip() ? player.getVipExpiresAt() : LocalDateTime.now();
        player.setVipExpiresAt(base.plusDays(VIP_DAYS));
        player.setInventoryExpanded(true); // bag expansion included

        playerRepository.save(player);
        log.info("[VipService] player={} action=buyVip OK expiresAt={} stonesLeft={}",
                player.getId(), player.getVipExpiresAt(), player.getSoulStones());
        return player;
    }

    // ── Status ───────────────────────────────────────────────────────────────

    public Map<String, Object> status(Player player) {
        resetDailyCountersIfNeeded(player);
        boolean vip = player.isVip();
        return Map.ofEntries(
            Map.entry("isVip",                 vip),
            Map.entry("vipExpiresAt",          vip && player.getVipExpiresAt() != null
                                               ? player.getVipExpiresAt().toString() : ""),
            Map.entry("soulStones",            player.getSoulStones()),
            Map.entry("arenaFightsRemaining",   player.getArenaFightLimit() - player.getArenaFightsToday()),
            Map.entry("arenaFightLimit",        player.getArenaFightLimit()),
            Map.entry("vipHealCooldownSecs",    vipHealCooldownSecs(player)),
            Map.entry("vipHealReady",           vipHealCooldownSecs(player) == 0)
        );
    }

    // ── VIP Heal CD ──────────────────────────────────────────────────────────

    public long vipHealCooldownSecs(Player player) {
        if (player.getLastVipHealAt() == null) return 0;
        long elapsed = Duration.between(player.getLastVipHealAt(), LocalDateTime.now()).toSeconds();
        return Math.max(0, VIP_HEAL_CD_MIN * 60L - elapsed);
    }

    // ── Arena fights counter ─────────────────────────────────────────────────

    /** Verifica e incrementa o counter de arena. Lança exceção se limite atingido. */
    @Transactional
    public void consumeArenaFight(Player player) {
        resetDailyCountersIfNeeded(player);
        int limit = player.getArenaFightLimit();
        if (player.getArenaFightsToday() >= limit) {
            log.warn("[VipService] player={} REJECTED: arena daily limit ({}/{})",
                    player.getId(), player.getArenaFightsToday(), limit);
            throw new IllegalStateException(
                "Daily fight limit reached (" + player.getArenaFightsToday() + "/" + limit
                + "). Resets at midnight UTC.");
        }
        player.setArenaFightsToday(player.getArenaFightsToday() + 1);
        player.setLastArenaFightDate(LocalDate.now());
        playerRepository.save(player);
    }

    // ── Reset diário ─────────────────────────────────────────────────────────
    // [QUESTS_INTERATIVAS] o counter de missão instantânea foi removido (instant-start aposentado);
    // sobra só o reset do limite de arena.

    /** Zera counters se a data mudou (meia-noite UTC). Chamado antes de qualquer validação diária. */
    public void resetDailyCountersIfNeeded(Player player) {
        LocalDate today = LocalDate.now();
        if (!today.equals(player.getLastArenaFightDate())) {
            player.setArenaFightsToday(0);
            player.setLastArenaFightDate(today);
            playerRepository.save(player);
        }
    }
}
