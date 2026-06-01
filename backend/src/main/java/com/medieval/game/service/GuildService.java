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
        if (player.getGuild() != null)
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
        if (player.getGuild() != null)
            throw new IllegalStateException("Você já pertence a uma guilda. Saia primeiro.");

        Guild guild = guildRepository.findById(guildId)
                .orElseThrow(() -> new IllegalArgumentException("Guilda não encontrada."));

        int memberCount = playerRepository.countByGuild(guild);
        if (memberCount >= guild.maxMembers())
            throw new IllegalStateException("Guilda está cheia (" + guild.maxMembers() + " membros máx.).");

        player.setGuild(guild);
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
            // Único membro e líder → dissolve automaticamente
            disband(player);
            return;
        }

        player.setGuild(null);
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
        return guildRepository.save(guild);
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

        // Remove todos os membros
        List<Player> members = playerRepository.findAllByGuild(guild);
        members.forEach(m -> m.setGuild(null));
        playerRepository.saveAll(members);

        guildRepository.delete(guild);
    }

    // ── Consultas ─────────────────────────────────────────────────────────────
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
    private Guild requireGuild(Player player) {
        if (player.getGuild() == null)
            throw new IllegalStateException("Você não pertence a nenhuma guilda.");
        // recarrega para garantir dados atualizados
        return guildRepository.findById(player.getGuild().getId())
                .orElseThrow(() -> new IllegalStateException("Guilda não encontrada."));
    }

    private void requireLeader(Player player, Guild guild) {
        if (!guild.getLeaderId().equals(player.getId()))
            throw new IllegalStateException("Apenas o líder pode executar esta ação.");
    }
}
