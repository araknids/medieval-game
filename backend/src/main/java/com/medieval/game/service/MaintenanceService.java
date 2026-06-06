package com.medieval.game.service;

import com.medieval.game.enums.Location;
import com.medieval.game.enums.WarriorClass;
import com.medieval.game.model.Player;
import com.medieval.game.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Operações de manutenção (uso pontual, fora do gameplay normal).
 *
 * Roda via JPA/entidades — não depende de nomes de coluna em SQL cru, então é
 * imune a divergências de schema (ao contrário do antigo docs/soft-wipe.sql, que
 * dava rollback se uma coluna não existisse). Disparado pelo SoftWipeRunner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final SocketedGemRepository        socketedGemRepository;
    private final ItemAffixRepository          itemAffixRepository;
    private final InventoryItemRepository      inventoryItemRepository;
    private final WorkSessionRepository        workSessionRepository;
    private final MountRepository              mountRepository;
    private final ArenaMatchRepository         arenaMatchRepository;
    private final ZoneActivityRepository       zoneActivityRepository;
    private final TowerRunRepository           towerRunRepository;
    private final TrainingSessionRepository    trainingSessionRepository;
    private final KingdomActiveQuestRepository kingdomActiveQuestRepository;
    private final SkillLevelRepository         skillLevelRepository;
    private final WorkProfessionRepository     workProfessionRepository;
    private final ResourceInventoryRepository  resourceInventoryRepository;
    private final ShopPurchaseRepository       shopPurchaseRepository;
    private final MailRepository               mailRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TerritoryDeclarationRepository territoryDeclarationRepository;
    private final TerritoryBattleLogRepository  territoryBattleLogRepository;
    private final TerritoryControlRepository    territoryControlRepository;
    private final PlayerRepository             playerRepository;
    private final GuildRepository              guildRepository;
    private final WarriorRepository            warriorRepository;
    private final InventoryService             inventoryService;

    /**
     * Reset "fresh start" mantendo as contas (login). Reseta moedas, guerreiro,
     * inventário (itens iniciais), skills/profissões/recursos, sessões, mails,
     * SoulStones/VIP; dissolve guildas e neutraliza territórios.
     * @return número de jogadores resetados.
     */
    @Transactional
    public int softWipe() {
        // 1) Apaga progressão (filhos antes dos pais por causa das FKs)
        socketedGemRepository.deleteAllInBatch();
        itemAffixRepository.deleteAllInBatch(); // Itens V2: afixos antes dos itens (FK)
        inventoryItemRepository.deleteAllInBatch();
        workSessionRepository.deleteAllInBatch();
        mountRepository.deleteAllInBatch();
        arenaMatchRepository.deleteAllInBatch();
        zoneActivityRepository.deleteAllInBatch();
        towerRunRepository.deleteAllInBatch();
        trainingSessionRepository.deleteAllInBatch();
        kingdomActiveQuestRepository.deleteAllInBatch();
        skillLevelRepository.deleteAllInBatch();
        workProfessionRepository.deleteAllInBatch();
        resourceInventoryRepository.deleteAllInBatch();
        shopPurchaseRepository.deleteAllInBatch();
        mailRepository.deleteAllInBatch();
        passwordResetTokenRepository.deleteAllInBatch();
        territoryDeclarationRepository.deleteAllInBatch();
        territoryBattleLogRepository.deleteAllInBatch();

        // 2) Territórios: apaga TODAS as linhas de controle em lote. Não usar findAll()/save()
        //    aqui — linhas órfãs com nomes de enum antigos de Territory (ex.: DESFILADEIRO_DO_OSSO)
        //    não mapeiam pra Kingdom e fariam o ORM estourar ao carregar. O bulk delete não
        //    desserializa a coluna. TerritoryService.ensureInitialized() recria as linhas neutras
        //    dos reinos de guerra sob demanda. [REINOS_V2]
        territoryControlRepository.deleteAllInBatch();

        // 3) Tira todos das guildas + reseta os jogadores
        List<Player> players = playerRepository.findAll();
        for (Player p : players) {
            p.setGuild(null);
            resetPlayer(p);
        }
        playerRepository.saveAll(players);

        // 4) Dissolve guildas — FLUSH primeiro para que os guild_id=NULL (players e
        //    territory_controls) cheguem ao banco antes do DELETE, senão a FK estoura.
        playerRepository.flush();
        guildRepository.deleteAllInBatch();

        // 5) Reseta guerreiros + devolve itens iniciais
        for (Player p : players) {
            warriorRepository.findByPlayer(p).ifPresent(w -> {
                // Volta pra RECRUIT: o wipe re-testa o onboarding (escolher classe na Trial do Lv10). [CLASSES]
                WarriorClass wc = WarriorClass.RECRUIT;
                w.setWarriorClass(wc);
                w.setLevel(1);
                w.setExperience(0);
                w.setAttack(wc.baseAttack);
                w.setDefense(wc.baseDefense);
                w.setHealth(wc.baseHealth);
                w.setStrength(0);
                w.setDexterity(0);
                w.setConstitution(0);
                w.setLuck(0);
                w.setIntellect(0);
                w.setAvailablePoints(0);
                w.setCurrentHpSnapshot(100);
                w.setHpUpdatedAt(LocalDateTime.now());
                w.clearBuff();
                warriorRepository.save(w);
            });
            inventoryService.giveStarterItems(p);
        }

        log.warn("[MaintenanceService] SOFT WIPE aplicado em {} jogadores", players.size());
        return players.size();
    }

    private void resetPlayer(Player p) {
        p.setBronze(0);
        p.setSilver(50);   // novos jogadores começam com 50 prata
        p.setGold(0);
        p.setRankPoints(1000);
        p.setArenaWins(0);
        p.setArenaLosses(0);
        p.setTowerBestFloor(0);
        p.setCurrentStamina(100);
        p.setStaminaUpdatedAt(LocalDateTime.now());
        p.setLocation(Location.TAVERN);
        p.setGuildDonatedBronze(0);
        p.setSoulStones(0);
        p.setInventoryExpanded(false);
        p.setLastSoulstoneHealAt(null);
        p.setVipExpiresAt(null);
        p.setLastVipHealAt(null);
        p.setArenaFightsToday(0);
        p.setLastArenaFightDate(null);
        p.clearPvpFlag();           // [PVP_FLAG] não pode acordar "exposto" depois do wipe
        p.setPvpShieldUntil(null);
    }
}
