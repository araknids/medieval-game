package com.medieval.game.repository;

import com.medieval.game.model.Consignment;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** [MERCADO_STEAM] Consignações do Mercador Azul. */
public interface ConsignmentRepository extends JpaRepository<Consignment, Long> {

    List<Consignment> findByPlayerOrderByCreatedAtDesc(Player player);

    List<Consignment> findByPlayerAndStatusIn(Player player, List<Consignment.Status> statuses);

    // [CONSIGN_FK_FIX] limpa consignações (ex.: RETURNED após cancelar) que ainda referenciam o item
    // antes de deletá-lo (vender) — senão a FK consignments.item_id (nullable=false) barra a venda.
    void deleteByItem(InventoryItem item);
}
