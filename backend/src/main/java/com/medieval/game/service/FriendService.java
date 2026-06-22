package com.medieval.game.service;

import com.medieval.game.model.Friendship;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.FriendshipRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.WarriorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * [LEADERBOARDS] Amizade: pedir / aceitar / recusar / remover + listar (amigos, recebidos, enviados).
 * Nomes resolvidos em batch (findByPlayer_IdIn) p/ evitar N+1. Sem custo/limite v1 (placeholder).
 */
@Service
@RequiredArgsConstructor
public class FriendService {

    private final FriendshipRepository repo;
    private final PlayerRepository playerRepository;
    private final WarriorRepository warriorRepository;

    static final String PENDING = "PENDING", ACCEPTED = "ACCEPTED";

    /** Linha de jogador na lista de amigos (playerId p/ inspecionar; requestId p/ aceitar/recusar). */
    public record FriendRow(long playerId, String name, String title, int level, String classId, long requestId) {}
    public record FriendList(List<FriendRow> friends, List<FriendRow> incoming, List<FriendRow> outgoing) {}

    @Transactional
    public void request(Player me, Long targetId) {
        if (targetId == null || targetId.equals(me.getId()))
            throw new IllegalArgumentException("You cannot add yourself.");
        playerRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Player not found."));
        if (repo.findBetween(me.getId(), targetId).isPresent())
            throw new IllegalStateException("Already friends or a request is pending.");
        repo.save(new Friendship(me.getId(), targetId, PENDING));
    }

    @Transactional
    public void accept(Player me, Long requestId) {
        Friendship f = repo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));
        if (!f.getAddresseeId().equals(me.getId()) || !PENDING.equals(f.getStatus()))
            throw new IllegalStateException("No such pending request.");
        f.setStatus(ACCEPTED);
        repo.save(f);
    }

    /** Destinatário recusa OU remetente cancela o próprio pedido. */
    @Transactional
    public void decline(Player me, Long requestId) {
        repo.findById(requestId).ifPresent(f -> {
            if (f.getAddresseeId().equals(me.getId()) || f.getRequesterId().equals(me.getId())) repo.delete(f);
        });
    }

    @Transactional
    public void remove(Player me, Long friendId) {
        repo.findBetween(me.getId(), friendId).ifPresent(repo::delete);
    }

    /** [LEADERBOARDS] Nº de pedidos de amizade pendentes recebidos — alimenta o badge do ícone de Amigos. */
    @Transactional(readOnly = true)
    public int countIncoming(Player me) {
        return repo.countByAddresseeIdAndStatus(me.getId(), PENDING);
    }

    @Transactional(readOnly = true)
    public FriendList list(Player me) {
        long meId = me.getId();
        List<Friendship> accepted = repo.findAccepted(meId);
        List<Friendship> incoming = repo.findByAddresseeIdAndStatus(meId, PENDING);
        List<Friendship> outgoing = repo.findByRequesterIdAndStatus(meId, PENDING);

        Set<Long> ids = new HashSet<>();
        for (Friendship f : accepted) ids.add(otherId(f, meId));
        for (Friendship f : incoming) ids.add(f.getRequesterId());
        for (Friendship f : outgoing) ids.add(f.getAddresseeId());
        Map<Long, Warrior> wByP = ids.isEmpty() ? Map.of()
                : warriorRepository.findByPlayer_IdIn(ids).stream()
                    .collect(Collectors.toMap(w -> w.getPlayer().getId(), w -> w, (a, b) -> a));

        List<FriendRow> friends = accepted.stream().map(f -> toRow(otherId(f, meId), 0L, wByP)).toList();
        List<FriendRow> in = incoming.stream().map(f -> toRow(f.getRequesterId(), f.getId(), wByP)).toList();
        List<FriendRow> out = outgoing.stream().map(f -> toRow(f.getAddresseeId(), f.getId(), wByP)).toList();
        return new FriendList(friends, in, out);
    }

    private static long otherId(Friendship f, long meId) {
        return f.getRequesterId() == meId ? f.getAddresseeId() : f.getRequesterId();
    }

    private FriendRow toRow(long otherId, long requestId, Map<Long, Warrior> wByP) {
        Warrior w = wByP.get(otherId);
        Player p = w != null ? w.getPlayer() : null;
        return new FriendRow(otherId,
                w != null ? w.getName() : "?",
                p != null ? AchievementService.titleString(p) : "",
                w != null ? w.getLevel() : 1,
                w != null && w.getWarriorClass() != null ? w.getWarriorClass().name().toLowerCase() : "recruit",
                requestId);
    }
}
