package com.medieval.game.repository;

import com.medieval.game.model.Consignment;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** [MERCADO_STEAM] Consignações do Mercador Azul. */
public interface ConsignmentRepository extends JpaRepository<Consignment, Long> {

    List<Consignment> findByPlayerOrderByCreatedAtDesc(Player player);

    List<Consignment> findByPlayerAndStatusIn(Player player, List<Consignment.Status> statuses);
}
