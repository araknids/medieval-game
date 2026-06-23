package com.medieval.game.service;

import com.medieval.game.enums.TowerStatus;
import com.medieval.game.repository.TowerRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [TORRE_TRAVA] Limpeza de run presa da Torre numa transação PRÓPRIA (REQUIRES_NEW).
 *
 * Por quê separado: o {@code TowerService.enter} é @Transactional e, ao detectar uma run condenada (andar
 * acima do alcance de nível), precisa abandoná-la E em seguida rejeitar a entrada ("suba de nível"). Se o
 * abandono rodasse na MESMA transação, o throw da rejeição faria rollback e desfaria o abandono — o jogador
 * ficaria preso pra sempre. Aqui o abandono COMMITA independentemente, então a run some mesmo quando o enter
 * rejeita. Bean separado (não self-invocation) p/ o proxy do Spring realmente abrir a transação nova.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TowerRunCleanup {

    private final TowerRunRepository towerRunRepository;

    /** Marca a run como EXITED em transação própria; commita mesmo que a chamadora faça rollback depois. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(Long runId) {
        towerRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus(TowerStatus.EXITED);
            towerRunRepository.save(run);
            log.info("[TowerRunCleanup] abandoned stuck run runId={} floor={}", runId, run.getCurrentFloor());
        });
    }
}
