package com.medieval.game.repository;

import com.medieval.game.model.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    // Relação entre dois jogadores em QUALQUER direção (p/ checar duplicata / remover).
    @Query("SELECT f FROM Friendship f WHERE (f.requesterId = :a AND f.addresseeId = :b) " +
           "OR (f.requesterId = :b AND f.addresseeId = :a)")
    Optional<Friendship> findBetween(@Param("a") Long a, @Param("b") Long b);

    // Amigos confirmados (eu sou qualquer dos dois lados).
    @Query("SELECT f FROM Friendship f WHERE f.status = 'ACCEPTED' " +
           "AND (f.requesterId = :me OR f.addresseeId = :me)")
    List<Friendship> findAccepted(@Param("me") Long me);

    List<Friendship> findByAddresseeIdAndStatus(Long addresseeId, String status); // pedidos recebidos
    List<Friendship> findByRequesterIdAndStatus(Long requesterId, String status); // pedidos enviados
    int countByAddresseeIdAndStatus(Long addresseeId, String status);             // [LEADERBOARDS] badge social
}
