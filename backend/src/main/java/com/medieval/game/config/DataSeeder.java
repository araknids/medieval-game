package com.medieval.game.config;

import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.InventoryService;
import com.medieval.game.service.PlayerService;
import com.medieval.game.service.WarriorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DataSeeder {

    private final PlayerRepository playerRepository;
    private final PlayerService    playerService;
    private final WarriorService   warriorService;
    private final InventoryService inventoryService;

    @Value("${app.dev.admin.username}")
    private String adminUsername;

    @Value("${app.dev.admin.password}")
    private String adminPassword;

    @Value("${app.dev.admin.warrior-name}")
    private String adminWarriorName;

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (playerRepository.existsByUsername(adminUsername)) return;

        Player  player  = playerService.register(adminUsername, adminUsername + "@dev.local", adminPassword);
        warriorService.create(player, adminWarriorName, WarriorClass.WARRIOR);
        inventoryService.giveStarterItems(player);

        log.info("==============================================");
        log.info("  Admin created — login: {}  password: {}", adminUsername, adminPassword);
        log.info("==============================================");
    }
}
