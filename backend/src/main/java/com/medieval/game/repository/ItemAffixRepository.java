package com.medieval.game.repository;

import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.ItemAffix;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ItemAffixRepository extends JpaRepository<ItemAffix, Long> {

    List<ItemAffix> findAllByItem(InventoryItem item);

    /** Carga em batch para os itens equipados (evita N+1, igual às joias). */
    List<ItemAffix> findAllByItemIn(Collection<InventoryItem> items);

    /** Usado pelo reforge (re-roll) — limpa os afixos antigos. */
    void deleteByItem(InventoryItem item);
}
