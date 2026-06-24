package com.medieval.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.Player;
import com.medieval.game.model.ResourceInventory;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.ResourceInventoryRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [VARREDURA][PVP_FLAG] Lógica COMPARTILHADA de raid PvP (saque por vitória), antes duplicada (near-twins)
 * entre {@code ZoneService} e {@code ExpeditionService} (+ os helpers de loot reusados na Guerra de Guilda).
 *
 * Comportamento UNIFICADO por decisão do dono (2026-06-23):
 *  - mail RICO ao saqueado: log + replay 3D da luta (antes a Zona mandava só texto);
 *  - vitória de raid conta como player-kill (Slayer) nos DOIS (antes só a Zona contava).
 * Loot por tier (idêntico nos dois antes): 🟡 10% bronze + XP; 🔴 +50% recursos + 1 item travado (35%) + 15% bronze.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PvpRaidService {

    public static final int PVP_SHIELD_MINUTES = 60; // imune por 1h após ser saqueado
    public static final int PVP_LEVEL_BAND     = 10; // só cruza com flagged dentro de ±10 níveis

    private final PlayerRepository           playerRepository;
    private final WarriorRepository          warriorRepository;
    private final InventoryService           inventoryService;
    private final GatheringService           gatheringService;
    private final WarriorService             warriorService;
    private final MailService                mailService;
    private final ResourceInventoryRepository resourceRepo;
    private final ObjectMapper               objectMapper;

    /** Sorteia um player FLAGGED (exposto, sem escudo) na zona, dentro de ±PVP_LEVEL_BAND níveis. null se ninguém. */
    public Player findFlaggedOpponent(Zone zone, Player exclude, int attackerLevel) {
        List<Player> pool = playerRepository.findFlaggedInZone(zone, LocalDateTime.now(), exclude.getId(),
                        org.springframework.data.domain.PageRequest.of(0, 50)) // [LAUNCH_HARDENING] capa o pool + N+1
                .stream()
                .filter(p -> !p.isPvpShielded())
                .filter(p -> Math.abs(attackerLevel
                        - warriorRepository.findByPlayer(p).map(Warrior::getLevel).orElse(1)) <= PVP_LEVEL_BAND)
                .toList();
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /**
     * Atacante VENCEU o raid → saqueia a vítima por TIER, manda o mail rico (log + replay) e conta o
     * player-kill do atacante. Adiciona o resumo do loot ao {@code log} (in-battle) e o devolve (narrativa).
     */
    public String raidVictim(Player attacker, Warrior attackerW, Player victim, Warrior victimW,
                             Zone zone, List<String> log, List<BattleSimulator.BattleEvent> events, String scene) {
        boolean red       = (zone == Zone.HIGH_RISK);
        long   bronze     = applyDefeatPenalty(victim, attacker, red ? 0.15 : 0.10);
        long   stolenRes  = red ? stealResources(attacker, victim) : 0;          // recursos só na vermelha
        String stolenItem = red ? inventoryService.stealOnePvpLockedItem(victim, attacker) : null; // item só na vermelha (35%)
        long   xpLost     = stealXp(victimW, attackerW);                          // XP em ambas (killer +50%)

        victimW.clearBuff();
        warriorRepository.save(victimW);
        victim.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES)); // saqueado 1x por ciclo
        victim.clearPvpFlag();
        inventoryService.unlockAllItems(victim);                                 // fim do ciclo: destrava o resto
        playerRepository.save(victim);

        attacker.setPlayerKills(attacker.getPlayerKills() + 1); // [decisão dono] conta o abate de player (Slayer) nos dois
        playerRepository.save(attacker);

        String loot = bronze + " bronze"
                + (stolenItem != null ? ", " + stolenItem : "")
                + (stolenRes  > 0 ? ", " + stolenRes + " resources" : "")
                + (xpLost     > 0 ? ", " + xpLost + " XP" : "");
        log.add("💰 You raided " + victimW.getName() + "! Stole " + loot + ".");

        // [decisão dono] mail RICO com replay 3D — nos dois (Zona + Incursão)
        String eventsJson;
        try { eventsJson = objectMapper.writeValueAsString(events); } catch (Exception e) { eventsJson = null; }
        String msg = "💀 You were RAIDED by " + attackerW.getName() + " in the " + zone.displayName
                + "! Lost " + loot + ". Protection shield for " + PVP_SHIELD_MINUTES + " min.";
        mailService.sendRaidMail(victim, attackerW.getName(), msg, String.join("\n", log), eventsJson, scene);
        return "You raided " + victimW.getName() + "! Stole " + loot + ".";
    }

    /** Penalidade de derrota: perde `pct` do bronze; vencedor (se player) ganha 50% do perdido. Reuso: PvE, raid, guerra. */
    public long applyDefeatPenalty(Player loser, Player winner, double pct) {
        long bronzeLost = Math.round(loser.totalBronze() * pct);
        if (bronzeLost > 0) {
            loser.addBronzeAmount(-bronzeLost);
            playerRepository.save(loser);
            if (winner != null) { winner.addBronzeAmount(bronzeLost / 2); playerRepository.save(winner); }
        }
        return bronzeLost;
    }

    /** Rouba ~50% de cada recurso da bag da vítima e dá ao atacante (clamp na bag dele). */
    public long stealResources(Player attacker, Player victim) {
        long total = 0;
        for (ResourceInventory r : resourceRepo.findAllByPlayerAndStashed(victim, false)) {
            if (r.getQuantity() <= 0) continue;
            if (inventoryService.resourceSpaceLeft(attacker) <= 0) break; // [BAG_WEIGHT] recurso = 0.2 slot
            long take  = Math.max(1, r.getQuantity() / 2);
            long added = gatheringService.addResource(attacker, r.getResourceType(), take);
            if (added > 0) { r.setQuantity(r.getQuantity() - added); resourceRepo.save(r); total += added; }
        }
        return total;
    }

    /** Vítima perde XP; o killer ganha 50% (teto: 10% do XP do nível do killer). */
    public long stealXp(Warrior victimW, Warrior attackerW) {
        long xpLost = Math.max(1, victimW.expNeededForNextLevel() / 20);
        warriorService.loseXp(victimW, xpLost);
        long gain = Math.min(xpLost / 2, Math.max(1, attackerW.expNeededForNextLevel() / 10));
        if (gain > 0) { warriorService.addExperience(attackerW, gain); warriorRepository.save(attackerW); }
        return xpLost;
    }
}
