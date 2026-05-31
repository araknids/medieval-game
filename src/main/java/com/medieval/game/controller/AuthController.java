package com.medieval.game.controller;

import com.medieval.game.config.JwtUtil;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WarriorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final PlayerService playerService;
    private final WarriorService warriorService;
    private final InventoryService inventoryService;
    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest req) {
        try {
            Player player = playerService.register(req.username(), req.email(), req.password());
            Warrior warrior = warriorService.create(player, req.warriorName(), WarriorClass.WARRIOR);
            inventoryService.giveStarterItems(player);
            String token = jwtUtil.generateToken(player.getId(), player.getUsername());
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "playerId", player.getId(),
                    "username", player.getUsername(),
                    "gold", player.getGold(),
                    "warrior", Map.of(
                            "id", warrior.getId(),
                            "name", warrior.getName(),
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
            Player player = playerService.findByUsername(req.username());
            if (!playerService.checkPassword(player, req.password())) {
                return ResponseEntity.status(401).body(Map.of("error", "Senha incorreta"));
            }
            Warrior warrior = warriorService.getWarrior(player);
            String token = jwtUtil.generateToken(player.getId(), player.getUsername());
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "playerId", player.getId(),
                    "username", player.getUsername(),
                    "gold", player.getGold(),
                    "warrior", Map.of(
                            "id", warrior.getId(),
                            "name", warrior.getName(),
                            "class", warrior.getWarriorClass().displayName,
                            "level", warrior.getLevel()
                    )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Usuário não encontrado"));
        }
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
