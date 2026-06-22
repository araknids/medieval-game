package com.medieval.game.repository;

import com.medieval.game.model.Guild;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GuildRepository extends JpaRepository<Guild, Long> {
    Optional<Guild> findByName(String name);
    boolean existsByName(String name);
    List<Guild> findAllByOrderByLevelDescGoldDesc();

    // [VARREDURA] Lock pessimista da guild p/ serializar a seção crítica do join (contar membros + entrar).
    // O cap de membros é check-then-act que o @Version não cobre (o INSERT é na linha do PLAYER, não da
    // guild). Duas entradas concorrentes na MESMA guild bloqueiam aqui → a 2ª conta DEPOIS da 1ª e respeita
    // o cap. join é infrequente, então o custo do lock é irrelevante.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Guild g WHERE g.id = :id")
    Optional<Guild> findByIdForUpdate(@Param("id") Long id);
}
