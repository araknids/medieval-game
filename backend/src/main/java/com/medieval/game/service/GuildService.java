package com.medieval.game.service;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.model.TerritoryDeclaration.DeclarationStatus;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.TerritoryControlRepository;
import com.medieval.game.repository.TerritoryDeclarationRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuildService {

    private static final long CREATE_COST_BRONZE = 100L;
    private static final int  WAR_ROSTER_MAX     = 15; // espelha TerritoryService.ROSTER_MAX. [GUERRA_ROSTER]

    private final GuildRepository              guildRepository;
    private final PlayerRepository             playerRepository;
    private final WarriorRepository            warriorRepository;
    private final PlayerService                playerService;
    private final AchievementService           achievementService; // [TITULOS]
    private final TerritoryControlRepository   territoryControlRepo;
    private final TerritoryDeclarationRepository territoryDeclarationRepo;

    // ── Criar guilda ──────────────────────────────────────────────────────────
    @Transactional
    public Guild create(Player player, String name, String description) {
        log.info("[GuildService] player={} action=createGuild name={}", player.getId(), name);
        if (playerRepository.findGuildByPlayerId(player.getId()).isPresent()) {
            log.warn("[GuildService] player={} REJECTED: already belongs to a guild", player.getId());
            throw new IllegalStateException("You already belong to a guild.");
        }
        if (guildRepository.existsByName(name)) {
            log.warn("[GuildService] player={} REJECTED: guild name already exists: {}", player.getId(), name);
            throw new IllegalArgumentException("Guild name already exists.");
        }
        if (name == null || name.isBlank() || name.length() < 3 || name.length() > 30) {
            log.warn("[GuildService] player={} REJECTED: invalid guild name length", player.getId());
            throw new IllegalArgumentException("Nome deve ter entre 3 e 30 caracteres.");
        }

        // [TOWER_OPTLOCK] Recarrega o player GERENCIADO: o controller o passa detached e este método o
        // salva 2x (spendBronze + setGuild). Sem isso, o 2º merge vê a @Version defasada →
        // OptimisticLockException ("Row was updated by another transaction"). Mesmo padrão do donate().
        player = playerRepository.findById(player.getId())
                .orElseThrow(() -> new IllegalStateException("Player not found"));

        playerService.spendBronze(player, CREATE_COST_BRONZE);

        Guild guild = new Guild();
        guild.setName(name.trim());
        guild.setDescription(description != null ? description.trim() : "");
        guild.setLeaderId(player.getId());
        guildRepository.save(guild);

        player.setGuild(guild);
        playerRepository.save(player);
        achievementService.checkAndUnlock(player, true); // [TITULOS] Kin + Guildmaster (criador é líder)

        log.info("[GuildService] player={} action=createGuild OK guildId={} name={}", player.getId(), guild.getId(), guild.getName());
        return guild;
    }

    // ── Entrar na guilda ──────────────────────────────────────────────────────
    @Transactional
    public Guild join(Player player, Long guildId) {
        log.info("[GuildService] player={} action=joinGuild guildId={}", player.getId(), guildId);
        if (playerRepository.findGuildByPlayerId(player.getId()).isPresent()) {
            log.warn("[GuildService] player={} REJECTED: already belongs to a guild", player.getId());
            throw new IllegalStateException("You already belong to a guild. Saia primeiro.");
        }

        // [VARREDURA] Lock pessimista: serializa joins concorrentes na MESMA guild (count + entrar é
        // check-then-act fora do alcance do @Version — o INSERT é na linha do player). A 2ª entrada
        // bloqueia, conta DEPOIS da 1ª commitar, e respeita o cap.
        Guild guild = guildRepository.findByIdForUpdate(guildId)
                .orElseThrow(() -> new IllegalArgumentException("Guild not found."));

        int memberCount = playerRepository.countByGuild(guild);
        if (memberCount >= guild.maxMembers()) {
            log.warn("[GuildService] player={} REJECTED: guild {} is full ({}/{})", player.getId(), guild.getName(), memberCount, guild.maxMembers());
            throw new com.medieval.game.config.LocalizedException("error.guild_full", "Guild is full ({0} max members).", guild.maxMembers());
        }

        player.setGuild(guild);
        player.setGuildDonatedBronze(0); // reset donations on joining a new guild
        player.setInWarRoster(false);    // entra fora do roster de guerra. [GUERRA_ROSTER]
        playerRepository.save(player);
        achievementService.checkAndUnlock(player, true); // [TITULOS] Kin
        log.info("[GuildService] player={} action=joinGuild OK guildId={} name={}", player.getId(), guild.getId(), guild.getName());
        return guild;
    }

    // ── Sair da guilda ────────────────────────────────────────────────────────
    @Transactional
    public void leave(Player player) {
        log.info("[GuildService] player={} action=leaveGuild", player.getId());
        Guild guild = requireGuild(player);

        if (guild.getLeaderId().equals(player.getId())) {
            int memberCount = playerRepository.countByGuild(guild);
            if (memberCount > 1) {
                log.warn("[GuildService] player={} REJECTED: leader cannot leave while there are other members", player.getId());
                throw new IllegalStateException(
                        "You are the leader. Transfer leadership before leaving, or disband the guild.");
            }
            disband(player);
            return;
        }

        player.setGuild(null);
        player.setGuildDonatedBronze(0);
        player.setInWarRoster(false); // [GUERRA_ROSTER]
        playerRepository.save(player);
        log.info("[GuildService] player={} action=leaveGuild OK guildId={}", player.getId(), guild.getId());
    }

    // ── Expulsar membro ───────────────────────────────────────────────────────
    @Transactional
    public void kick(Player leader, Long targetPlayerId) {
        log.info("[GuildService] player={} action=kickMember targetPlayerId={}", leader.getId(), targetPlayerId);
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        if (leader.getId().equals(targetPlayerId)) {
            log.warn("[GuildService] player={} REJECTED: cannot kick yourself", leader.getId());
            throw new IllegalArgumentException("You cannot kick yourself.");
        }

        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        if (target.getGuild() == null || !target.getGuild().getId().equals(guild.getId())) {
            log.warn("[GuildService] player={} REJECTED: target {} does not belong to this guild", leader.getId(), targetPlayerId);
            throw new IllegalArgumentException("Player does not belong to your guild.");
        }

        target.setGuild(null);
        target.setGuildDonatedBronze(0);
        target.setInWarRoster(false); // [GUERRA_ROSTER]
        playerRepository.save(target);
        log.info("[GuildService] player={} action=kickMember OK targetPlayerId={} guildId={}", leader.getId(), targetPlayerId, guild.getId());
    }

    // ── Transferir liderança ──────────────────────────────────────────────────
    @Transactional
    public Guild transfer(Player leader, Long targetPlayerId) {
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        if (leader.getId().equals(targetPlayerId))
            throw new IllegalArgumentException("You are already the leader.");

        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));

        if (target.getGuild() == null || !target.getGuild().getId().equals(guild.getId()))
            throw new IllegalArgumentException("Player does not belong to your guild.");

        guild.setLeaderId(targetPlayerId);
        Guild saved = guildRepository.save(guild);
        achievementService.checkAndUnlock(target, true); // [TITULOS] novo líder vira Guildmaster
        return saved;
    }

    // ── Roster de guerra (líder escolhe até 15 p/ a batalha de território) ────── [GUERRA_ROSTER]
    @Transactional
    public void setWarRoster(Player leader, List<Long> memberIds) {
        int requested = memberIds == null ? 0 : memberIds.size();
        log.info("[GuildService] player={} action=setWarRoster requested={}", leader.getId(), requested);
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        java.util.Set<Long> wanted = memberIds == null
                ? java.util.Set.of() : new java.util.HashSet<>(memberIds);
        if (wanted.size() > WAR_ROSTER_MAX) {
            log.warn("[GuildService] player={} REJECTED: roster too large ({}/{})", leader.getId(), wanted.size(), WAR_ROSTER_MAX);
            throw new com.medieval.game.config.LocalizedException("error.roster_max", "Battle roster can have at most {0} members.", WAR_ROSTER_MAX);
        }

        List<Player> members = playerRepository.findAllByGuild(guild);
        java.util.Set<Long> memberIdSet = members.stream().map(Player::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (Long id : wanted) {
            if (!memberIdSet.contains(id)) {
                log.warn("[GuildService] player={} REJECTED: {} is not a member of guild {}", leader.getId(), id, guild.getId());
                throw new IllegalArgumentException("All roster members must belong to your guild.");
            }
        }

        members.forEach(m -> m.setInWarRoster(wanted.contains(m.getId())));
        playerRepository.saveAll(members);
        log.info("[GuildService] player={} action=setWarRoster OK guildId={} selected={}", leader.getId(), guild.getId(), wanted.size());
    }

    /** Slot da formação 3×5: membro numa célula (lane 0–2, depth 0–4). [GUERRA_FORMACAO] */
    public record FormationSlot(Long playerId, int lane, int depth) {}

    /** Líder posiciona os membros no tabuleiro 3×5 da guerra. Células vazias = auto-fill na batalha. [GUERRA_FORMACAO] */
    @Transactional
    public void setWarFormation(Player leader, List<FormationSlot> slots) {
        if (slots == null) slots = List.of();
        log.info("[GuildService] player={} action=setWarFormation slots={}", leader.getId(), slots.size());
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        if (slots.size() > WAR_ROSTER_MAX)
            throw new com.medieval.game.config.LocalizedException("error.formation_max", "Formation has at most {0} members.", WAR_ROSTER_MAX);

        List<Player> members = playerRepository.findAllByGuild(guild);
        java.util.Set<Long> memberIdSet = members.stream().map(Player::getId)
                .collect(java.util.stream.Collectors.toSet());

        java.util.Set<String>  usedCells   = new java.util.HashSet<>();
        java.util.Set<Long>    placedIds   = new java.util.HashSet<>();
        java.util.Map<Long,FormationSlot> byPlayer = new java.util.HashMap<>();
        for (FormationSlot s : slots) {
            if (s.lane() < 0 || s.lane() > 2 || s.depth() < 0 || s.depth() > 4)
                throw new IllegalArgumentException("Invalid cell (lane 0-2, depth 0-4).");
            if (!memberIdSet.contains(s.playerId()))
                throw new IllegalArgumentException("All positioned members must belong to your guild.");
            if (!usedCells.add(s.lane() + ":" + s.depth()))
                throw new IllegalArgumentException("Two members in the same cell.");
            if (!placedIds.add(s.playerId()))
                throw new IllegalArgumentException("A member is placed in more than one cell.");
            byPlayer.put(s.playerId(), s);
        }

        for (Player m : members) {
            FormationSlot s = byPlayer.get(m.getId());
            if (s != null) { m.setWarLane(s.lane()); m.setWarDepth(s.depth()); m.setInWarRoster(true); }
            else           { m.setWarLane(-1);       m.setWarDepth(-1);        m.setInWarRoster(false); }
        }
        playerRepository.saveAll(members);
        log.info("[GuildService] player={} action=setWarFormation OK guildId={} placed={}", leader.getId(), guild.getId(), placedIds.size());
    }

    /** Cansaço de guerra (%) que valerá na próxima batalha — p/ exibir na lista de membros. [GUERRA_ROSTER] */
    public int warriorFatiguePct(Player player, long currentCycleId) {
        return warriorRepository.findByPlayer(player)
                .map(w -> w.currentFatiguePct(currentCycleId)).orElse(0);
    }

    // ── Doar bronze para a guilda (sobe o nível automaticamente) ─────────────── [GUILD_LEVEL_GOLD]
    /** Resultado da doação: guild atualizada + se cruzou um limiar de nível. */
    public record DonateResult(Guild guild, boolean leveledUp, int newLevel) {}

    @Transactional
    public DonateResult donate(Player player, long bronzeAmount) {
        log.info("[GuildService] player={} action=donate amount={}", player.getId(), bronzeAmount);
        if (bronzeAmount <= 0) {
            log.warn("[GuildService] player={} REJECTED: invalid donation amount={}", player.getId(), bronzeAmount);
            throw new IllegalArgumentException("Invalid amount.");
        }

        Guild guild = requireGuild(player);
        int beforeLevel = guild.getLevel();

        playerService.spendBronze(player, bronzeAmount);

        guild.setTreasuryBronze(guild.getTreasuryBronze() + bronzeAmount);   // tesouro gastável
        guild.setLifetimeGold(guild.getLifetimeGold() + bronzeAmount);       // acumulado (só cresce)
        guild.recomputeLevel();                                              // nível derivado do acumulado
        guildRepository.save(guild);

        boolean leveledUp = guild.getLevel() > beforeLevel;
        if (leveledUp) {
            log.info("[GuildService] guild={} action=guildLevelUp {} → {} (lifetimeGold={})",
                    guild.getId(), beforeLevel, guild.getLevel(), guild.getLifetimeGold());
        }

        // Track individual donation for the ranking
        Player managed = playerRepository.findById(player.getId()).orElse(player);
        managed.setGuildDonatedBronze(managed.getGuildDonatedBronze() + bronzeAmount);
        playerRepository.save(managed);

        log.info("[GuildService] player={} action=donate OK guildId={} amount={} lifetimeGold={}",
                player.getId(), guild.getId(), bronzeAmount, guild.getLifetimeGold());
        return new DonateResult(guild, leveledUp, guild.getLevel());
    }

    // ── Dissolver guilda (líder) ──────────────────────────────────────────────
    @Transactional
    public void disband(Player leader) {
        log.info("[GuildService] player={} action=disband", leader.getId());
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        // Remove all members and reset their donation counters
        List<Player> members = playerRepository.findAllByGuild(guild);
        members.forEach(m -> { m.setGuild(null); m.setGuildDonatedBronze(0); m.setInWarRoster(false); });
        playerRepository.saveAll(members);

        // Remove territory control if this guild holds a territory
        territoryControlRepo.findByControllingGuild(guild).ifPresent(tc -> {
            tc.setControllingGuild(null);
            tc.setDefenseStreak(0);
            territoryControlRepo.save(tc);
        });

        // Cancel pending territory declarations for this guild
        territoryDeclarationRepo.findByGuild(guild)
                .stream()
                .filter(d -> d.getStatus() == DeclarationStatus.PENDING)
                .forEach(d -> {
                    d.setStatus(DeclarationStatus.CANCELLED);
                    territoryDeclarationRepo.save(d);
                });

        guildRepository.delete(guild);
        log.info("[GuildService] player={} action=disband OK guildId={} name={}", leader.getId(), guild.getId(), guild.getName());
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    // Carrega guild do banco evitando lazy proxy (open-in-view=false)
    public Guild loadGuild(Player player) {
        return playerRepository.findGuildByPlayerId(player.getId()).orElse(null);
    }

    public List<Guild> listAll() {
        return guildRepository.findAllByOrderByLevelDescTreasuryBronzeDesc();
    }

    public List<Player> members(Guild guild) {
        return playerRepository.findAllByGuild(guild);
    }

    /** [AUDITORIA_2 A5] Conta membros sem carregar as linhas (usado na lista de guildas). */
    public long memberCount(Guild guild) {
        return playerRepository.countByGuild(guild);
    }

    public String warriorName(Player player) {
        return warriorRepository.findByPlayer(player)
                .map(w -> w.getName())
                .orElse("?");
    }

    /** [AUDITORIA_2 A5] Warriors dos membros em 1 query (mapa playerId→Warrior) — evita N+1 no roster. */
    public java.util.Map<Long, com.medieval.game.model.Warrior> warriorsByPlayerId(java.util.Collection<Player> players) {
        if (players.isEmpty()) return java.util.Map.of();
        return warriorRepository.findByPlayerIn(players).stream()
                .collect(java.util.stream.Collectors.toMap(w -> w.getPlayer().getId(), w -> w, (a, b) -> a));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Usa query direta para evitar proxy lazy com open-in-view=false
    private Guild requireGuild(Player player) {
        return playerRepository.findGuildByPlayerId(player.getId())
                .orElseThrow(() -> new IllegalStateException("You do not belong to any guild."));
    }

    private void requireLeader(Player player, Guild guild) {
        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Only the leader can perform this action.");
    }
}
