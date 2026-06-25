package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VipService {

    private static final int  VIP_COST_STONES   = 15;
    private static final int  VIP_DAYS          = 30;
    private static final int  VIP_HEAL_CD_MIN   = 10;
    private static final int  GENDER_CHANGE_COST_STONES = 10; // [GENDER] troca de sexo paga (premium)

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

    // ── Trocar de sexo (premium, pago em SoulStone) ──────────────────────────
    // [GENDER] O sexo é escolhido na criação do personagem e NÃO muda no jogo normal;
    // a única forma de trocar é aqui, gastando SoulStone (vendido na tela do VIP).

    @Transactional
    public Player changeGender(Player player, com.medieval.game.enums.Gender gender) {
        log.info("[VipService] player={} action=changeGender target={}", player.getId(), gender);
        if (gender == null) throw new IllegalArgumentException("gender required");
        if (gender == player.getGender())
            throw new com.medieval.game.config.LocalizedException("error.gender_same", "You are already this gender.");
        if (player.getSoulStones() < GENDER_CHANGE_COST_STONES) {
            log.warn("[VipService] player={} REJECTED changeGender: not enough SoulStones ({}<{})",
                    player.getId(), player.getSoulStones(), GENDER_CHANGE_COST_STONES);
            throw new com.medieval.game.config.LocalizedException("error.soulstones_required", "Not enough SoulStones. Required: {0}", GENDER_CHANGE_COST_STONES);
        }
        player.setSoulStones(player.getSoulStones() - GENDER_CHANGE_COST_STONES);
        player.setGender(gender);
        playerRepository.save(player);
        log.info("[VipService] player={} action=changeGender OK gender={} stonesLeft={}",
                player.getId(), gender, player.getSoulStones());
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
            Map.entry("vipHealReady",           vipHealCooldownSecs(player) == 0),
            // [GENDER] sexo atual + custo da troca (vitrine na tela do VIP)
            Map.entry("gender",                 player.getGender() != null ? player.getGender().name() : "MALE"),
            Map.entry("genderChangeCost",       GENDER_CHANGE_COST_STONES)
        );
    }

    // ── VIP Heal CD ──────────────────────────────────────────────────────────

    public long vipHealCooldownSecs(Player player) {
        if (player.getLastVipHealAt() == null) return 0;
        long elapsed = Duration.between(player.getLastVipHealAt(), LocalDateTime.now()).toSeconds();
        return Math.max(0, VIP_HEAL_CD_MIN * 60L - elapsed);
    }

    // ── Arena fights counter ─────────────────────────────────────────────────

    // [ARENA_JANELA] Arena: 10 lutas por janela de 6h (VIP 20). Sem estamina (o gate é a contagem).
    private static final long ARENA_WINDOW_SECONDS = 6L * 3600L;

    /** Verifica e incrementa o counter de arena. Lança exceção se limite atingido. */
    @Transactional
    public void consumeArenaFight(Player player) {
        resetDailyCountersIfNeeded(player);
        int limit = player.getArenaFightLimit();
        if (player.getArenaFightsToday() >= limit) {
            long mins = secondsUntilArenaReset() / 60;
            log.warn("[VipService] player={} REJECTED: arena window limit ({}/{})",
                    player.getId(), player.getArenaFightsToday(), limit);
            throw new com.medieval.game.config.LocalizedException("error.arena_limit",
                "Fight limit reached ({0}/{1}). Resets in {2}m.",
                player.getArenaFightsToday(), limit, mins);
        }
        player.setArenaFightsToday(player.getArenaFightsToday() + 1);
        player.setLastArenaWindowId(currentArenaWindowId());
        playerRepository.save(player);
    }

    // ── Reset por janela de 6h [ARENA_JANELA] ──────────────────────────────────
    /** Zera o counter de arena quando a janela de 6h vira. Chamado antes de validar/consumir. */
    public void resetDailyCountersIfNeeded(Player player) {
        long win = currentArenaWindowId();
        if (player.getLastArenaWindowId() != win) {
            player.setArenaFightsToday(0);
            player.setLastArenaWindowId(win);
            playerRepository.save(player);
        }
    }

    /** Id da janela de 6h atual (blocos fixos do epoch). */
    public static long currentArenaWindowId() {
        return java.time.Instant.now().getEpochSecond() / ARENA_WINDOW_SECONDS;
    }

    /** Segundos até a próxima janela de 6h (reset do limite de arena). */
    public static long secondsUntilArenaReset() {
        return ARENA_WINDOW_SECONDS - (java.time.Instant.now().getEpochSecond() % ARENA_WINDOW_SECONDS);
    }
}
