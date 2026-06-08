package com.medieval.game.repository;

import com.medieval.game.model.TavernMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** [TAVERNA] Feed da Taverna (chat + avisos). */
public interface TavernMessageRepository extends JpaRepository<TavernMessage, Long> {

    /** Mensagens novas desde o último id visto (polling). */
    List<TavernMessage> findByIdGreaterThanOrderByIdAsc(Long id);

    /** Últimas N (carga inicial / quando o cliente não tem cursor). */
    List<TavernMessage> findTop50ByOrderByIdDesc();

    /** Prune: apaga tudo abaixo de um id (mantém só o histórico recente). */
    void deleteByIdLessThan(Long id);
}
