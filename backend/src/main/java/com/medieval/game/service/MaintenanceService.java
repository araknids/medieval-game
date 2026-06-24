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
    private final AuctionListingRepository     auctionListingRepository; // [LEILAO] FK → inventory_items
    private final com.medieval.game.repository.ConsignmentRepository consignmentRepository; // [MERCADO_STEAM] FK → inventory_items
    private final InventoryItemRepository      inventoryItemRepository;
    private final WorkSessionRepository        workSessionRepository;
    private final MountRepository              mountRepository;
    private final ArenaMatchRepository         arenaMatchRepository;
    private final ZoneActivityRepository       zoneActivityRepository;
    private final TowerRunRepository           towerRunRepository;
    private final ExpeditionRunRepository      expeditionRunRepository;  // [INCURSAO] runs (gear carregado sai no batch de itens)
    private final TrainingSessionRepository    trainingSessionRepository;
    private final KingdomActiveQuestRepository kingdomActiveQuestRepository;
    private final SkillLevelRepository         skillLevelRepository;
    private final WorkProfessionRepository     workProfessionRepository;
    private final ResourceInventoryRepository  resourceInventoryRepository;
    private final ShopPurchaseRepository       shopPurchaseRepository;
    private final MailRepository               mailRepository;
    private final com.medieval.game.repository.TavernMessageRepository tavernMessageRepository; // [TAVERNA]
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final TerritoryDeclarationRepository territoryDeclarationRepository;
    private final TerritoryBattleLogRepository  territoryBattleLogRepository;
    private final TerritoryControlRepository    territoryControlRepository;
    private final PlayerRepository             playerRepository;
    private final GuildRepository              guildRepository;
    private final GuildWarRepository           guildWarRepository;       // [GUERRA_GUILDA] FK → guilds
    private final MealInventoryRepository      mealInventoryRepository;  // progressão por player
    private final PetRepository                petRepository;            // progressão por player
    private final WarriorRepository            warriorRepository;
    private final WarriorAbilityRepository     warriorAbilityRepository; // [HABILIDADES]
    private final PlayerAchievementRepository  playerAchievementRepository; // [TITULOS]
    private final InventoryService             inventoryService;

    /**
     * Reset "fresh start" mantendo as contas (login). Reseta moedas, guerreiro,
     * inventário (itens iniciais), skills/profissões/recursos, sessões, mails,
     * leilões, pets, refeições, SoulStones/VIP; dissolve guildas (e guerras de
     * guilda) e neutraliza territórios.
     * @return número de jogadores resetados.
     */
    @Transactional
    public int softWipe() {
        // 1) Apaga progressão (filhos antes dos pais por causa das FKs)
        socketedGemRepository.deleteAllInBatch();
        itemAffixRepository.deleteAllInBatch();    // Itens V2: afixos antes dos itens (FK)
        auctionListingRepository.deleteAllInBatch(); // [LEILAO] leilões referenciam item_id → apaga antes dos itens
        consignmentRepository.deleteAllInBatch();    // [MERCADO_STEAM] consignações referenciam item_id → antes dos itens
        inventoryItemRepository.deleteAllInBatch();
        workSessionRepository.deleteAllInBatch();
        mountRepository.deleteAllInBatch();
        arenaMatchRepository.deleteAllInBatch();
        zoneActivityRepository.deleteAllInBatch();
        towerRunRepository.deleteAllInBatch();
        expeditionRunRepository.deleteAllInBatch(); // [INCURSAO] itens runPending já saem no inventory batch acima
        trainingSessionRepository.deleteAllInBatch();
        kingdomActiveQuestRepository.deleteAllInBatch();
        skillLevelRepository.deleteAllInBatch();
        workProfessionRepository.deleteAllInBatch();
        resourceInventoryRepository.deleteAllInBatch();
        shopPurchaseRepository.deleteAllInBatch();
        mailRepository.deleteAllInBatch();
        tavernMessageRepository.deleteAllInBatch();   // [TAVERNA] limpa o chat/avisos no fresh start
        passwordResetTokenRepository.deleteAllInBatch();
        mealInventoryRepository.deleteAllInBatch();  // progressão por player → fresh start
        petRepository.deleteAllInBatch();            // progressão por player → fresh start
        playerAchievementRepository.deleteAllInBatch(); // [TITULOS] conquistas zeram no wipe
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
        //    As guerras de guilda também referenciam guilds (guild_a/guild_b) → apaga antes. [GUERRA_GUILDA]
        playerRepository.flush();
        guildWarRepository.deleteAllInBatch();
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
                w.setAgility(0);
                w.setLuck(0);
                w.setIntellect(0);
                w.setAvailablePoints(0);
                w.setAbilityPoints(0);                      // [HABILIDADES]
                warriorAbilityRepository.deleteByWarrior(w); // zera as habilidades aprendidas
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
        p.setCreatedAt(LocalDateTime.now()); // [BUFF_NOVATO] "renasce" → re-concede o buff de novato (3 dias)
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
        p.setActiveTitle(null);     // [TITULOS] título zera (conquistas foram apagadas)
        p.setLastDailyClaimDate(null); // [DAILY] zera o streak de login diário
        p.setDailyStreak(0);
    }
}
