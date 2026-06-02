package com.medieval.game.controller;

import com.medieval.game.config.JwtUtil;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        try {
            Player  player  = playerService.register(req.username(), req.email(), req.password());
            Warrior warrior = warriorService.create(player, req.warriorName(), WarriorClass.WARRIOR);
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
                            "class", warrior.getWarriorClass().displayName
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        try {
            Player  player  = playerService.findByUsername(req.username());
            if (!playerService.checkPassword(player, req.password())) {
                return ResponseEntity.status(401).body(Map.of("error", "Incorrect password"));
            }
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
                            "class", warrior.getWarriorClass().displayName,
                            "level", warrior.getLevel()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody Map<String, String> body) {
        String email = body.get("email");
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Please provide your email"));
        }

        Optional<Player> playerOpt = playerRepository.findByEmail(email.trim().toLowerCase());
        // Retorna sempre a mesma mensagem para não revelar se o email existe
        if (playerOpt.isPresent()) {
            Player player = playerOpt.get();
            PasswordResetToken reset = new PasswordResetToken();
            reset.setToken(UUID.randomUUID().toString());
            reset.setPlayer(player);
            reset.setExpiresAt(LocalDateTime.now().plusMinutes(30));
            resetTokenRepository.save(reset);
            emailService.sendPasswordResetEmail(player.getEmail(), reset.getToken());
        }

        return ResponseEntity.ok(Map.of("message",
                "If this email is registered, you will receive instructions shortly."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody Map<String, String> body) {
        String token       = body.get("token");
        String newPassword = body.get("password");

        if (token == null || newPassword == null || newPassword.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid token or password"));
        }

        PasswordResetToken reset = resetTokenRepository.findByToken(token)
                .orElse(null);

        if (reset == null || reset.isUsed() || reset.isExpired()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid or expired link"));
        }

        // Carrega o player diretamente do banco para evitar LazyInitializationException
        Player player = playerRepository.findById(reset.getPlayer().getId())
                .orElseThrow(() -> new IllegalArgumentException("Player not found"));
        player.setPasswordHash(passwordEncoder.encode(newPassword));
        playerRepository.save(player);

        reset.setUsed(true);
        resetTokenRepository.save(reset);

        return ResponseEntity.ok(Map.of("message", "Password changed successfully! Please log in."));
    }

    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 20) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 6) String password,
            @NotBlank String warriorName
    ) {}

    record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}
}
