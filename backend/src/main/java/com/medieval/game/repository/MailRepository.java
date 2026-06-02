package com.medieval.game.repository;

import com.medieval.game.model.Mail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MailRepository extends JpaRepository<Mail, Long> {

    List<Mail> findByRecipientPlayerIdOrderBySentAtDesc(Long recipientPlayerId);

    List<Mail> findBySenderPlayerIdOrderBySentAtDesc(Long senderPlayerId);

    @Query("SELECT COUNT(m) FROM Mail m WHERE m.recipientPlayerId = :pid AND m.readAt IS NULL")
    long countUnreadByRecipientPlayerId(@Param("pid") Long pid);
}
