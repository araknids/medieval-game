package com.medieval.game.service;

import com.medieval.game.model.Guild;
import com.medieval.game.model.Player;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GuildService {

    private static final long CREATE_COST_BRONZE = 100L;

    private final GuildRepository    guildRepository;
    private final PlayerRepository   playerRepository;
    private final WarriorRepository  warriorRepository;
    private final PlayerService      playerService;

    // ── Criar guilda ──────────────────────────────────────────────────────────
    @Transactional
    public Guild create(Player player, String name, String description) {
        if (playerRepository.findGuildByPlayerId(player.getId()).isPresent())
            throw new IllegalStateException("Você já pertence a uma guilda.");
        if (guildRepository.existsByName(name))
            throw new IllegalArgumentException("Nome de guilda já existe.");
        if (name == null || name.isBlank() || name.length() < 3 || name.length() > 30)
            throw new IllegalArgumentException("Nome deve ter entre 3 e 30 caracteres.");

        playerService.spendBronze(player, CREATE_COST_BRONZE);

        Guild guild = new Guild();
        guild.setName(name.trim());
        guild.setDescription(description != null ? description.trim() : "");
        guild.setLeaderId(player.getId());
        guildRepository.save(guild);

        player.setGuild(guild);
        playerRepository.save(player);

        return guild;
    }

    // ── Entrar na guilda ──────────────────────────────────────────────────────
    @Transactional
    public Guild join(Player player, Long guildId) {
        if (playerRepository.findGuildByPlayerId(player.getId()).isPresent())
            throw new IllegalStateException("Você já pertence a uma guilda. Saia primeiro.");

        Guild guild = guildRepository.findById(guildId)
                .orElseThrow(() -> new IllegalArgumentException("Guilda não encontrada."));

        int memberCount = playerRepository.countByGuild(guild);
        if (memberCount >= guild.maxMembers())
            throw new IllegalStateException("Guilda está cheia (" + guild.maxMembers() + " membros máx.).");

        player.setGuild(guild);
        player.setGuildDonatedBronze(0); // reset donations on joining a new guild
        playerRepository.save(player);
        return guild;
    }

    // ── Sair da guilda ────────────────────────────────────────────────────────
    @Transactional
    public void leave(Player player) {
        Guild guild = requireGuild(player);

        if (guild.getLeaderId().equals(player.getId())) {
            int memberCount = playerRepository.countByGuild(guild);
            if (memberCount > 1)
                throw new IllegalStateException(
                        "Você é o líder. Transfira a liderança antes de sair, ou dissolva a guilda.");
            disband(player);
            return;
        }

        player.setGuild(null);
        player.setGuildDonatedBronze(0);
        playerRepository.save(player);
    }

    // ── Expulsar membro ───────────────────────────────────────────────────────
    @Transactional
    public void kick(Player leader, Long targetPlayerId) {
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        if (leader.getId().equals(targetPlayerId))
            throw new IllegalArgumentException("Você não pode expulsar a si mesmo.");

        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Jogador não encontrado."));

        if (target.getGuild() == null || !target.getGuild().getId().equals(guild.getId()))
            throw new IllegalArgumentException("Jogador não pertence à sua guilda.");

        target.setGuild(null);
        target.setGuildDonatedBronze(0);
        playerRepository.save(target);
    }

    // ── Transferir liderança ──────────────────────────────────────────────────
    @Transactional
    public Guild transfer(Player leader, Long targetPlayerId) {
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        if (leader.getId().equals(targetPlayerId))
            throw new IllegalArgumentException("Você já é o líder.");

        Player target = playerRepository.findById(targetPlayerId)
                .orElseThrow(() -> new IllegalArgumentException("Jogador não encontrado."));

        if (target.getGuild() == null || !target.getGuild().getId().equals(guild.getId()))
            throw new IllegalArgumentException("Jogador não pertence à sua guilda.");

        guild.setLeaderId(targetPlayerId);
        return guildRepository.save(guild);
    }

    // ── Doar bronze para a guilda ─────────────────────────────────────────────
    @Transactional
    public Guild donate(Player player, long bronzeAmount) {
        if (bronzeAmount <= 0)
            throw new IllegalArgumentException("Quantidade inválida.");

        Guild guild = requireGuild(player);
        playerService.spendBronze(player, bronzeAmount);

        guild.setGold(guild.getGold() + bronzeAmount);
        guildRepository.save(guild);

        // Track individual donation for the ranking
        Player managed = playerRepository.findById(player.getId()).orElse(player);
        managed.setGuildDonatedBronze(managed.getGuildDonatedBronze() + bronzeAmount);
        playerRepository.save(managed);

        return guild;
    }

    // ── Subir nível da guilda (líder) ─────────────────────────────────────────
    @Transactional
    public Guild levelUp(Player leader) {
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        long cost = guild.levelUpCost();
        if (guild.getGold() < cost)
            throw new IllegalStateException(
                    "Gold insuficiente. Necessário: " + cost + ", disponível: " + guild.getGold());

        guild.setGold(guild.getGold() - cost);
        guild.setLevel(guild.getLevel() + 1);
        return guildRepository.save(guild);
    }

    // ── Dissolver guilda (líder) ──────────────────────────────────────────────
    @Transactional
    public void disband(Player leader) {
        Guild guild = requireGuild(leader);
        requireLeader(leader, guild);

        // Remove all members and reset their donation counters
        List<Player> members = playerRepository.findAllByGuild(guild);
        members.forEach(m -> { m.setGuild(null); m.setGuildDonatedBronze(0); });
        playerRepository.saveAll(members);

        guildRepository.delete(guild);
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
                .orElseThrow(() -> new IllegalStateException("Você não pertence a nenhuma guilda."));
    }

    private void requireLeader(Player player, Guild guild) {
        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Apenas o líder pode executar esta ação.");
    }
}
