package com.medieval.game.controller;

import com.medieval.game.config.JwtUtil;
import com.medieval.game.config.LoginRateLimiter;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.PasswordResetToken;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PasswordResetTokenRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.EmailService;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WarriorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PlayerService               playerService;
    private final WarriorService              warriorService;
    private final InventoryService            inventoryService;
    private final EmailService                emailService;
    private final JwtUtil                     jwtUtil;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PlayerRepository            playerRepository;
    private final PasswordEncoder             passwordEncoder;
    private final LoginRateLimiter            rateLimiter;

    // Limites anti-brute-force / anti-spam. [AUDITORIA A10]
    private static final long RL_WINDOW_MS      = 15 * 60 * 1000L; // 15 min
    private static final int  LOGIN_MAX_FAILS   = 10;              // por IP+usuário / 15 min
    private static final int  FORGOT_MAX_REQS   = 5;               // por IP / 15 min
    private static final int  REGISTER_MAX_REQS = 10;              // por IP / 15 min — trava enumeração de usuário/email [AUDITORIA]

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        // Rate limit por IP: impede varredura de quais usernames/emails existem via /register.
        // Conta só tentativas FALHAS (username/email já existe) — igual o login só conta falha —
        // então registro legítimo não acumula, mas o probe de enumeração trava após N. [AUDITORIA]
        String rlKey = "register:" + clientIp(http);
        if (rateLimiter.isBlocked(rlKey, REGISTER_MAX_REQS, RL_WINDOW_MS)) {
            return ResponseEntity.status(429).body(Map.of("error",
                    com.medieval.game.service.Messages.tr("msg.too_many_requests", "Too many requests. Please try again in a few minutes.")));
        }
        log.info("[AuthController] register attempt username='{}' warriorName='{}' emailLen={}",
                req.username(), req.warriorName(), req.email() != null ? req.email().length() : 0);
        Player player;
        try {
            player = playerService.register(req.username(), req.email(), req.password());
        } catch (com.medieval.game.config.LocalizedException e) {
            rateLimiter.recordAttempt(rlKey, RL_WINDOW_MS); // tentativa de enumeração (user/email já existe)
            throw e; // GlobalExceptionHandler devolve a mensagem localizada original
        }
        player.setGender(com.medieval.game.enums.Gender.from(req.gender())); // cosmético: base/peças Male/Female [OUTFITS_FEMALE]
        playerRepository.save(player);
        Warrior warrior = warriorService.create(player, req.warriorName(), WarriorClass.RECRUIT); // nasce neutro; especializa na Trial do Lv10 [CLASSES]
        inventoryService.giveStarterItems(player);
        emailService.sendWelcomeEmail(player.getEmail(), player.getUsername(), warrior.getName());
        String token = jwtUtil.generateToken(player.getId(), player.getUsername());
        return ResponseEntity.ok(Map.of(
                "token",    token,
                "playerId", player.getId(),
                "username", player.getUsername(),
                "gold",     player.getGold(),
                "warrior",  Map.of(
                        "id",    warrior.getId(),
                        "name",  warrior.getName(),
                        "class", com.medieval.game.service.Messages.tr("class." + warrior.getWarriorClass().name() + ".name", warrior.getWarriorClass().displayName) // [I18N]
                )
        ));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        // Rate limit por IP+usuário; mensagem única para não permitir enumeração. [AUDITORIA A10]
        String rlKey = "login:" + clientIp(http) + ":" + req.username().toLowerCase();
        if (rateLimiter.isBlocked(rlKey, LOGIN_MAX_FAILS, RL_WINDOW_MS)) {
            return ResponseEntity.status(429).body(Map.of("error",
                    com.medieval.game.service.Messages.tr("msg.too_many_logins", "Too many login attempts. Please try again in a few minutes.")));
        }
        try {
            Player  player  = playerService.findByUsername(req.username());
            if (!playerService.checkPassword(player, req.password())) {
                rateLimiter.recordAttempt(rlKey, RL_WINDOW_MS);
                return ResponseEntity.status(401).body(Map.of("error", com.medieval.game.service.Messages.tr("msg.invalid_login", "Invalid username or password")));
            }
            rateLimiter.reset(rlKey); // sucesso limpa o contador
            Warrior warrior = warriorService.getWarrior(player);
            String  token   = jwtUtil.generateToken(player.getId(), player.getUsername());
            return ResponseEntity.ok(Map.of(
                    "token",    token,
                    "playerId", player.getId(),
                    "username", player.getUsername(),
                    "gold",     player.getGold(),
                    "warrior",  Map.of(
                            "id",    warrior.getId(),
                            "name",  warrior.getName(),
                            "class", com.medieval.game.service.Messages.tr("class." + warrior.getWarriorClass().name() + ".name", warrior.getWarriorClass().displayName), // [I18N]
                            "level", warrior.getLevel()
                    )
            ));
        } catch (IllegalArgumentException e) {
            rateLimiter.recordAttempt(rlKey, RL_WINDOW_MS);
            return ResponseEntity.status(401).body(Map.of("error", com.medieval.game.service.Messages.tr("msg.invalid_login", "Invalid username or password")));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body, HttpServletRequest http) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", com.medieval.game.service.Messages.tr("msg.provide_email", "Please provide your email")));
        }

        // Anti-spam de email: limita requisições de reset por IP. [AUDITORIA A10]
        String rlKey = "forgot:" + clientIp(http);
        if (rateLimiter.isBlocked(rlKey, FORGOT_MAX_REQS, RL_WINDOW_MS)) {
            return ResponseEntity.status(429).body(Map.of("error",
                    com.medieval.game.service.Messages.tr("msg.too_many_requests", "Too many requests. Please try again in a few minutes.")));
        }
        rateLimiter.recordAttempt(rlKey, RL_WINDOW_MS);

        Optional<Player> playerOpt = playerRepository.findByEmail(email.trim().toLowerCase());
        // Retorna sempre a mesma mensagem para não revelar se o email existe
        if (playerOpt.isPresent()) {
            Player player = playerOpt.get();
            // Invalida tokens pendentes anteriores (só um link válido por vez). [AUDITORIA B5]
            resetTokenRepository.findByPlayerAndUsedFalse(player).forEach(t -> {
                t.setUsed(true);
                resetTokenRepository.save(t);
            });
            PasswordResetToken reset = new PasswordResetToken();
            reset.setToken(secureToken()); // SecureRandom em vez de UUID v4 [AUDITORIA B5]
            reset.setPlayer(player);
            reset.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            resetTokenRepository.save(reset);
            emailService.sendPasswordResetEmail(player.getEmail(), reset.getToken());
        }

        return ResponseEntity.ok(Map.of("message",
                com.medieval.game.service.Messages.tr("msg.reset_email_sent", "If this email is registered, you will receive instructions shortly.")));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String token       = body.get("token");
        String newPassword = body.get("password");

        if (token == null || newPassword == null || newPassword.length() < 8) {
            return ResponseEntity.badRequest().body(Map.of("error", com.medieval.game.service.Messages.tr("msg.invalid_token_or_password", "Invalid token or password (min. 8 characters)")));
        }

        PasswordResetToken reset = resetTokenRepository.findByToken(token)
                .orElse(null);

        if (reset == null || reset.isUsed() || reset.isExpired()) {
            return ResponseEntity.badRequest().body(Map.of("error", com.medieval.game.service.Messages.tr("msg.invalid_or_expired_link", "Invalid or expired link")));
        }

        // Carrega o player diretamente do banco para evitar LazyInitializationException
        Player player = playerRepository.findById(reset.getPlayer().getId())
                .orElseThrow(() -> new IllegalArgumentException("Player not found"));
        player.setPasswordHash(passwordEncoder.encode(newPassword));
        player.setTokenValidFrom(java.time.LocalDateTime.now()); // M6: invalida tokens emitidos antes do reset
        playerRepository.save(player);

        reset.setUsed(true);
        resetTokenRepository.save(reset);

        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.password_changed", "Password changed successfully! Please log in.")));
    }

    /** Token de reset seguro: 32 bytes de SecureRandom em base64url (~256 bits). [AUDITORIA B5] */
    private static final java.security.SecureRandom SECURE_RNG = new java.security.SecureRandom();
    private String secureToken() {
        byte[] bytes = new byte[32];
        SECURE_RNG.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * IP do cliente p/ o rate-limit (Railway roda atrás de proxy). [AUDITORIA_2 A7]
     * Usa o **último** IP do X-Forwarded-For (o que o proxy CONFIÁVEL do Railway anexou), NÃO o primeiro:
     * o cliente pode mandar um X-Forwarded-For falso e o Railway só anexa o IP real no fim. Pegar o
     * primeiro deixava qualquer um rotacionar a chave de rate-limit e furar o limite de login/reset.
     */
    private String clientIp(HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String[] parts = fwd.split(",");
            String last = parts[parts.length - 1].trim(); // right-most = anexado pelo proxy confiável
            if (!last.isBlank()) return last;
        }
        return http.getRemoteAddr();
    }

    // Charset AMIGÁVEL: aceita acento, espaço, ponto, _ e - (nomes BR/internacionais), mas bloqueia
    // os chars de HTML perigosos (< > & " ' /). A defesa REAL de XSS é o escape na exibição
    // (escapeHtml no front); isto é só uma rede extra. Não usar allowlist ASCII estrita (bloqueava
    // cadastro legítimo, ex.: "joão", "Zé"). [XSS / fix cadastro]
    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 20)
            @Pattern(regexp = "[\\p{L}\\p{N} ._-]+", message = "Username: letters, numbers, space . _ - only")
            String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,  // mín. 8 caracteres [AUDITORIA M7]
            @NotBlank @Size(max = 20)   // [NICK_LIMIT] nick do guerreiro: até 20 chars (curtos tipo "Zé" valem)
            @Pattern(regexp = "[\\p{L}\\p{N} ._'-]+", message = "Warrior name has invalid characters")
            String warriorName,
            String gender   // opcional: "MALE"/"FEMALE" (default MALE). Cosmético. [OUTFITS_FEMALE]
    ) {}

    record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}
}
