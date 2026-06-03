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

    private final GuildRepository              guildRepository;
    private final PlayerRepository             playerRepository;
    private final WarriorRepository            warriorRepository;
    private final PlayerService                playerService;
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

        playerService.spendBronze(player, CREATE_COST_BRONZE);

        Guild guild = new Guild();
        guild.setName(name.trim());
        guild.setDescription(description != null ? description.trim() : "");
        guild.setLeaderId(player.getId());
        guildRepository.save(guild);

        player.setGuild(guild);
        playerRepository.save(player);

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

        Guild guild = guildRepository.findById(guildId)
                .orElseThrow(() -> new IllegalArgumentException("Guild not found."));

        int memberCount = playerRepository.countByGuild(guild);
        if (memberCount >= guild.maxMembers()) {
            log.warn("[GuildService] player={} REJECTED: guild {} is full ({}/{})", player.getId(), guild.getName(), memberCount, guild.maxMembers());
            throw new IllegalStateException("Guild is full (" + guild.maxMembers() + " max members).");
        }

        player.setGuild(guild);
        player.setGuildDonatedBronze(0); // reset donations on joining a new guild
        playerRepository.save(player);
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
                        "Você é o líder. Transfira a liderança antes de sair, ou dissolva a guilda.");
            }
            disband(player);
            return;
        }

        player.setGuild(null);
        player.setGuildDonatedBronze(0);
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
        return guildRepository.save(guild);
    }

    // ── Doar bronze para a guilda ─────────────────────────────────────────────
    @Transactional
    public Guild donate(Player player, long bronzeAmount) {
        log.info("[GuildService] player={} action=donate amount={}", player.getId(), bronzeAmount);
        if (bronzeAmount <= 0) {
            log.warn("[GuildService] player={} REJECTED: invalid donation amount={}", player.getId(), bronzeAmount);
            throw new IllegalArgumentException("Invalid amount.");
        }

        Guild guild = requireGuild(player);
        playerService.spendBronze(player, bronzeAmount);

        guild.setGold(guild.getGold() + bronzeAmount);
        guildRepository.save(guild);

        // Track individual donation for the ranking
        Player managed = playerRepository.findById(player.getId()).orElse(player);
        managed.setGuildDonatedBronze(managed.getGuildDonatedBronze() + bronzeAmount);
        playerRepository.save(managed);

        log.info("[GuildService] player={} action=donate OK guildId={} amount={}", player.getId(), guild.getId(), bronzeAmount);
        return guild;
    }

    // ── Subir nível da guilda (líder) ─────────────────────────────────────────
    @Transactional
    public Guild levelUp(Player leader) {
        log.info("[GuildService] player={} action=levelUp", leader.getId());
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        long cost = guild.levelUpCost();
        if (guild.getGold() < cost) {
            log.warn("[GuildService] player={} REJECTED: insufficient guild gold (have={} need={})", leader.getId(), guild.getGold(), cost);
            throw new IllegalStateException(
                    "Insufficient guild gold. Required: " + cost + ", available: " + guild.getGold());
        }

        guild.setGold(guild.getGold() - cost);
        guild.setLevel(guild.getLevel() + 1);
        Guild saved = guildRepository.save(guild);
        log.info("[GuildService] player={} action=levelUp OK guildId={} newLevel={}", leader.getId(), guild.getId(), guild.getLevel());
        return saved;
    }

    // ── Dissolver guilda (líder) ──────────────────────────────────────────────
    @Transactional
    public void disband(Player leader) {
        log.info("[GuildService] player={} action=disband", leader.getId());
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        // Remove all members and reset their donation counters
        List<Player> members = playerRepository.findAllByGuild(guild);
        members.forEach(m -> { m.setGuild(null); m.setGuildDonatedBronze(0); });
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
        return guildRepository.findAllByOrderByLevelDescGoldDesc();
    }

    public List<Player> members(Guild guild) {
        return playerRepository.findAllByGuild(guild);
    }

    public String warriorName(Player player) {
        return warriorRepository.findByPlayer(player)
                .map(w -> w.getName())
                .orElse("?");
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
