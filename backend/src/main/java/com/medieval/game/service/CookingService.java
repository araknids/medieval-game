package com.medieval.game.service;

import com.medieval.game.enums.Meal;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.MealInventory;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.MealInventoryRepository;
import com.medieval.game.repository.ResourceInventoryRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Sistema de Cozinha: peixe → refeição → buff de combate (slot "Bem Alimentado"). Cozinhar é instantâneo. [COZINHA]
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CookingService {

    private final MealInventoryRepository     mealRepository;
    private final ResourceInventoryRepository resourceRepository;
    private final WarriorRepository           warriorRepository;
    private final GatheringService            gatheringService;

    /** Quantos do peixe X o jogador tem (para o "canCook" das receitas). */
    public long fishOwned(Player player, ResourceType fish) {
        return resourceRepository.findByPlayerAndResourceType(player, fish)
                .map(r -> r.getQuantity()).orElse(0L);
    }

    public List<Meal> getRecipes() {
        return List.of(Meal.values());
    }

    /** Refeições cozidas que o jogador tem em estoque (quantidade > 0). */
    public List<MealInventory> getMeals(Player player) {
        return mealRepository.findAllByPlayer(player).stream()
                .filter(m -> m.getQuantity() > 0).toList();
    }

    @Transactional
    public MealInventory cook(Player player, Meal meal) {
        log.info("[CookingService] player={} action=cook meal={}", player.getId(), meal);
        // consome o peixe (lança IllegalState se faltar)
        gatheringService.removeResource(player, meal.fishIngredient, meal.fishQty);

        MealInventory inv = mealRepository.findByPlayerAndMeal(player, meal)
                .orElseGet(() -> {
                    MealInventory m = new MealInventory();
                    m.setPlayer(player);
                    m.setMeal(meal);
                    return m;
                });
        inv.setQuantity(inv.getQuantity() + 1);
        MealInventory saved = mealRepository.save(inv);
        log.info("[CookingService] player={} action=cook OK meal={} total={}", player.getId(), meal, saved.getQuantity());
        return saved;
    }

    @Transactional
    public Warrior eat(Player player, Meal meal) {
        log.info("[CookingService] player={} action=eat meal={}", player.getId(), meal);
        MealInventory inv = mealRepository.findByPlayerAndMeal(player, meal)
                .filter(m -> m.getQuantity() > 0)
                .orElseThrow(() -> new IllegalStateException("You don't have that meal."));
        inv.setQuantity(inv.getQuantity() - 1);
        mealRepository.save(inv);

        Warrior w = warriorRepository.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));
        w.setMealBuff(meal); // substitui a refeição ativa anterior (1 slot Bem Alimentado)
        w.setMealBuffExpiresAt(LocalDateTime.now().plusMinutes(meal.durationMinutes));
        Warrior saved = warriorRepository.save(w);
        log.info("[CookingService] player={} action=eat OK meal={} expiresAt={}", player.getId(), meal, saved.getMealBuffExpiresAt());
        return saved;
    }
}
