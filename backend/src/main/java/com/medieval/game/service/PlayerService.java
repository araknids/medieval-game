package com.medieval.game.service;

import com.medieval.game.model.Player;
import com.medieval.game.repository.MountRepository;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final PasswordEncoder  passwordEncoder;
    private final MountRepository  mountRepository;

    @Transactional
    public Player register(String username, String email, String rawPassword) {
        log.info("[PlayerService] action=register username={}", username);
        // [LAUNCH_HARDENING] normaliza o email (trim + lowercase) — senão "A@x.com" e "a@x.com" viram contas
        // distintas (a unique key não pega) e o forgot-password (que faz lowercase) não acharia a conta.
        String normEmail = email == null ? null : email.trim().toLowerCase();
        // [LAUNCH_HARDENING] anti-enumeração: o cliente recebe SEMPRE a MESMA mensagem genérica, seja a
        // colisão de username ou de email — senão um probe com username novo + email-alvo revelaria que o
        // email existe. O motivo real continua nos logs do servidor (não vai pro cliente).
        if (playerRepository.existsByUsername(username)) {
            log.warn("[PlayerService] action=register REJECTED: username already exists: {}", username);
            throw accountTaken();
        }
        if (playerRepository.existsByEmail(normEmail)) {
            log.warn("[PlayerService] action=register REJECTED: email already registered: {}", normEmail);
            throw accountTaken();
        }
        Player player = new Player();
        player.setUsername(username);
        player.setEmail(normEmail);
        player.setPasswordHash(passwordEncoder.encode(rawPassword));
        Player saved = playerRepository.save(player);
        log.info("[PlayerService] action=register OK playerId={} username={}", saved.getId(), username);
        return saved;
    }

    /** Mensagem genérica de colisão (não revela QUAL campo) — anti-enumeração. [LAUNCH_HARDENING] */
    private static com.medieval.game.config.LocalizedException accountTaken() {
        return new com.medieval.game.config.LocalizedException(
                "error.account_taken", "That username or email is already in use.");
    }

    public Player findByUsername(String username) {
        return playerRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + username));
    }

    public Player findById(Long id) {
        return playerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + id));
    }

    // [LAUNCH_METRICS] Marca "ativo hoje" (no máx 1×/dia, via UPDATE direto throttle). Chamado no
    // GET /api/warrior — toda abertura do app valida o token por ele → mede retenção real (D1/D2).
    public void touchLastSeen(Long playerId) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        playerRepository.touchLastSeen(playerId, now, now.toLocalDate().atStartOfDay());
    }

    public boolean checkPassword(Player player, String rawPassword) {
        return passwordEncoder.matches(rawPassword, player.getPasswordHash());
    }

    /** [ONBOARDING] Marca a tela de boas-vindas como vista (idempotente). */
    @Transactional
    public void markOnboardingSeen(Long playerId) {
        Player p = findById(playerId);
        if (!p.isOnboardingSeen()) { p.setOnboardingSeen(true); playerRepository.save(p); }
    }

    /** [OUTFITS_FEMALE] Troca o gênero do personagem (cosmético). Devolve o player atualizado. */
    @Transactional
    public Player setGender(Long playerId, com.medieval.game.enums.Gender gender) {
        Player p = findById(playerId);
        p.setGender(gender == null ? com.medieval.game.enums.Gender.MALE : gender);
        return playerRepository.save(p);
    }

    /** [I18N] Salva o idioma preferido (en/pt). Valida contra os suportados. Devolve o valor aplicado. */
    @Transactional
    public String setLanguage(Player playerArg, String language) {
        String lang = language == null ? "" : language.trim().toLowerCase();
        if (!com.medieval.game.config.I18nConfig.SUPPORTED_TAGS.contains(lang))
            throw new com.medieval.game.config.LocalizedException("error.unsupported_language", "Unsupported language: {0}", language);
        Player p = findById(playerArg.getId());
        p.setLanguage(lang);
        playerRepository.save(p);
        return lang;
    }

    // ── Sistema de 3 moedas (100 bronze = 1 prata, 100 prata = 1 ouro) ──

    /** Adiciona bronze e auto-converte para prata/ouro se necessário */
    @Transactional
    public void addBronze(Player player, long amount) {
        long totalBronze = player.getBronze() + amount;
        long silverGained = totalBronze / 100;
        player.setBronze(totalBronze % 100);
        if (silverGained > 0) addSilverInternal(player, silverGained);
        playerRepository.save(player);
    }

    /** Adiciona prata e auto-converte para ouro se necessário */
    @Transactional
    public void addSilver(Player player, long amount) {
        addSilverInternal(player, amount);
        playerRepository.save(player);
    }

    private void addSilverInternal(Player player, long amount) {
        long totalSilver = player.getSilver() + amount;
        player.setGold(player.getGold() + totalSilver / 100);
        player.setSilver(totalSilver % 100);
    }

    /** Gasta um valor em bronze (decompõe automaticamente prata/ouro se necessário) */
    @Transactional
    public void spendBronze(Player player, long bronzeAmount) {
        // SEGURANÇA: valor negativo passaria na guarda "saldo < negativo" e CREDITARIA dinheiro. [AUDITORIA C2]
        if (bronzeAmount < 0) throw new IllegalArgumentException("amount must be >= 0");
        if (player.totalBronze() < bronzeAmount) {
            log.warn("[PlayerService] player={} REJECTED: insufficient funds (have={} need={})", player.getId(), player.totalBronze(), bronzeAmount);
            throw new IllegalStateException("Insufficient funds");
        }
        long remaining = player.totalBronze() - bronzeAmount;
        player.setGold(remaining / 10_000L);
        remaining %= 10_000L;
        player.setSilver(remaining / 100L);
        player.setBronze(remaining % 100L);
        playerRepository.save(player);
    }

    // [VARREDURA] removidos os wrappers addGold/spendGold (eram bronze mal-nomeados) — callers usam addBronze/spendBronze.

    @Transactional
    public void consumeStamina(Player player, int cost) {
        cost = discountStamina(player, cost); // [ESTABULO] desconto da montaria equipada (cobre quest/arena)
        int current = player.getCalculatedStamina();
        if (current < cost) {
            long minutesLeft = player.getMinutesToFullStamina();
            throw new IllegalStateException(
                "Insufficient stamina (" + current + "/" + cost + "). " +
                "Regenera totalmente em " + minutesLeft + " min."
            );
        }
        player.setCurrentStamina(current - cost);
        player.setStaminaUpdatedAt(LocalDateTime.now());
        playerRepository.save(player);
    }

    // ── [ESTABULO] Desconto de estamina da montaria equipada ────────────────────

    /** Redução de estamina (%) da montaria equipada; 0 se nenhuma. */
    public int staminaReductionPct(Player player) {
        return mountRepository.findByPlayerAndEquippedTrue(player)
                .map(m -> m.getMountType().staminaReductionPct)
                .orElse(0);
    }

    /** Aplica o desconto da montaria a um custo-base de estamina (piso de 1). */
    public int discountStamina(Player player, int baseCost) {
        int pct = staminaReductionPct(player);
        if (pct <= 0) return baseCost;
        return Math.max(1, (int) Math.round(baseCost * (1 - pct / 100.0)));
    }
}
