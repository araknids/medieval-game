package com.medieval.game.service;

import com.medieval.game.model.Guild;
import com.medieval.game.model.GuildInvite;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.GuildInviteRepository;
import com.medieval.game.repository.GuildRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * [LEADERBOARDS] Convite de guilda: o LÍDER convida; o convidado aceita (entra via GuildService.join,
 * que cuida de capacidade + lock + guard de já-tem-guilda) ou recusa. Caminho paralelo ao join aberto.
 */
@Service
@RequiredArgsConstructor
public class GuildInviteService {

    private final GuildInviteRepository repo;
    private final GuildService guildService;
    private final GuildRepository guildRepository;
    private final PlayerRepository playerRepository;
    private final WarriorRepository warriorRepository;

    static final String PENDING = "PENDING", ACCEPTED = "ACCEPTED", DECLINED = "DECLINED";

    @Transactional
    public void invite(Player me, Long targetId) {
        Guild myGuild = playerRepository.findGuildByPlayerId(me.getId())
                .orElseThrow(() -> new IllegalStateException("You are not in a guild."));
        if (!me.getId().equals(myGuild.getLeaderId()))
            throw new IllegalStateException("Only the guild leader can invite.");
        if (targetId == null || targetId.equals(me.getId()))
            throw new IllegalArgumentException("Invalid target.");
        playerRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));
        if (playerRepository.findGuildByPlayerId(targetId).isPresent())
            throw new IllegalStateException("That player is already in a guild.");
        if (repo.findByGuildIdAndInviteeIdAndStatus(myGuild.getId(), targetId, PENDING).isPresent())
            throw new IllegalStateException("Invite already pending.");
        repo.save(new GuildInvite(myGuild.getId(), me.getId(), targetId, PENDING));
    }

    @Transactional
    public void accept(Player me, Long inviteId) {
        GuildInvite inv = repo.findById(inviteId)
                .orElseThrow(() -> new IllegalArgumentException("Invite not found."));
        if (!inv.getInviteeId().equals(me.getId()) || !PENDING.equals(inv.getStatus()))
            throw new IllegalStateException("No such pending invite.");
        guildService.join(me, inv.getGuildId()); // capacidade + lock + guard de já-tem-guilda
        inv.setStatus(ACCEPTED);
        repo.save(inv);
    }

    @Transactional
    public void decline(Player me, Long inviteId) {
        repo.findById(inviteId).ifPresent(inv -> {
            if (inv.getInviteeId().equals(me.getId())) { inv.setStatus(DECLINED); repo.save(inv); }
        });
    }

    /** [LEADERBOARDS] Nº de convites de guilda pendentes — alimenta o badge do ícone de Amigos. */
    @Transactional(readOnly = true)
    public int countIncoming(Player me) {
        return repo.countByInviteeIdAndStatus(me.getId(), PENDING);
    }

    /** Convites PENDING recebidos por mim: {inviteId, guildId, guildName, inviterName}. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> incoming(Player me) {
        List<GuildInvite> invites = repo.findByInviteeIdAndStatus(me.getId(), PENDING);
        if (invites.isEmpty()) return List.of();

        Map<Long, String> guildNames = guildRepository.findAllById(
                        invites.stream().map(GuildInvite::getGuildId).toList()).stream()
                .collect(Collectors.toMap(Guild::getId, Guild::getName, (a, b) -> a));
        Map<Long, String> inviterNames = warriorRepository.findByPlayer_IdIn(
                        invites.stream().map(GuildInvite::getInviterId).toList()).stream()
                .collect(Collectors.toMap(w -> w.getPlayer().getId(), Warrior::getName, (a, b) -> a));

        List<Map<String, Object>> out = new ArrayList<>(invites.size());
        for (GuildInvite inv : invites) {
            out.add(Map.of(
                    "inviteId",    inv.getId(),
                    "guildId",     inv.getGuildId(),
                    "guildName",   guildNames.getOrDefault(inv.getGuildId(), "?"),
                    "inviterName", inviterNames.getOrDefault(inv.getInviterId(), "?")));
        }
        return out;
    }
}
