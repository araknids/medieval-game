package com.medieval.game.repository;

import com.medieval.game.model.Mail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface MailRepository extends JpaRepository<Mail, Long> {

    // FULL (sem cap) — usado SÓ pelas mutações em lote que precisam varrer tudo (claimAll / deleteAll).
    List<Mail> findByRecipientPlayerIdOrderBySentAtDesc(Long recipientPlayerId);

    List<Mail> findBySenderPlayerIdOrderBySentAtDesc(Long senderPlayerId);

    // [LAUNCH_HARDENING] Leitura LIMITADA no banco p/ os endpoints de listagem — evita carregar uma inbox
    // ilimitada (mail-bomb / cartas de raid c/ JSON de batalha) inteira na heap. claimAll/deleteAll ainda
    // varrem tudo; a poda de retenção mantém o total baixo.
    List<Mail> findTop200ByRecipientPlayerIdOrderBySentAtDesc(Long recipientPlayerId);

    List<Mail> findTop100BySenderPlayerIdOrderBySentAtDesc(Long senderPlayerId);

    @Query("SELECT COUNT(m) FROM Mail m WHERE m.recipientPlayerId = :pid AND m.readAt IS NULL")
    long countUnreadByRecipientPlayerId(@Param("pid") Long pid);

    // [LAUNCH_HARDENING] Poda de retenção: apaga cartas EXPIRADAS antigas. Uma carta expirada já não pode
    // ser reivindicada (isExpired() bloqueia o claim), então o item/recurso já foi perdido por design —
    // apagar a linha só limpa o banco. Não toca em carta viva (expiresAt nulo = sem expiração).
    @Modifying
    @Transactional
    @Query("delete from Mail m where m.expiresAt is not null and m.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
