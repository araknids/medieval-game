package com.medieval.game.controller;

import com.medieval.game.enums.Meal;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.service.CookingService;
import com.medieval.game.service.PlayerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@RestController
@RequestMapping("/api/cooking")
@RequiredArgsConstructor
public class CookingController {

    private final CookingService cookingService;
    private final PlayerService  playerService;

    // ── Receitas (com info de ingrediente, efeito e se dá pra cozinhar) ──
    @GetMapping("/recipes")
    public ResponseEntity<?> recipes(Authentication auth) {
        Player player = getPlayer(auth);
        var list = cookingService.getRecipes().stream().map(m -> {
            long owned = cookingService.fishOwned(player, m.fishIngredient);
            return Map.ofEntries(
                Map.entry("id",              m.name()),
                Map.entry("displayName",     com.medieval.game.service.Messages.tr("meal." + m.name() + ".name", m.displayName)),
                Map.entry("icon",            m.icon),
                Map.entry("ingredient",      com.medieval.game.service.Messages.tr("resource." + m.fishIngredient.name() + ".name", m.fishIngredient.displayName)),
                Map.entry("ingredientQty",   m.fishQty),
                Map.entry("fishOwned",       owned),
                Map.entry("effect",          m.effectText()),
                Map.entry("durationMinutes", m.durationMinutes),
                Map.entry("canCook",         owned >= m.fishQty)
            );
        }).toList();
        return ResponseEntity.ok(list);
    }

    // ── Refeições cozidas em estoque ──
    @GetMapping("/meals")
    public ResponseEntity<?> meals(Authentication auth) {
        Player player = getPlayer(auth);
        var list = cookingService.getMeals(player).stream().map(mi -> Map.of(
            "id",              mi.getMeal().name(),
            "displayName",     com.medieval.game.service.Messages.tr("meal." + mi.getMeal().name() + ".name", mi.getMeal().displayName),
            "icon",            mi.getMeal().icon,
            "effect",          mi.getMeal().effectText(),
            "durationMinutes", mi.getMeal().durationMinutes,
            "quantity",        mi.getQuantity()
        )).toList();
        return ResponseEntity.ok(list);
    }

    // ── Cozinhar (consome peixe → +1 refeição) ──
    @PostMapping("/cook")
    public ResponseEntity<?> cook(@Valid @RequestBody MealRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        var inv = cookingService.cook(player, req.meal());
        return ResponseEntity.ok(Map.of(
            "message",  com.medieval.game.service.Messages.tr("toast.meal_cooked", "{0} cooked!", com.medieval.game.service.Messages.tr("meal." + req.meal().name() + ".name", req.meal().displayName)),
            "meal",     req.meal().name(),
            "quantity", inv.getQuantity()
        ));
    }

    // ── Comer (consome 1 refeição → aplica buff Bem Alimentado) ──
    @PostMapping("/eat")
    public ResponseEntity<?> eat(@Valid @RequestBody MealRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        Warrior w = cookingService.eat(player, req.meal());
        long minutesLeft = w.getMealBuffExpiresAt() != null
                ? Math.max(0, ChronoUnit.MINUTES.between(LocalDateTime.now(), w.getMealBuffExpiresAt()))
                : 0;
        return ResponseEntity.ok(Map.of(
            "message",       com.medieval.game.service.Messages.tr("toast.meal_eaten", "You ate {0}! {1}", com.medieval.game.service.Messages.tr("meal." + req.meal().name() + ".name", req.meal().displayName), req.meal().effectText()),
            "mealBuff",      req.meal().name(),
            "effect",        req.meal().effectText(),
            "minutesLeft",   minutesLeft
        ));
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    record MealRequest(@NotNull Meal meal) {}
}
