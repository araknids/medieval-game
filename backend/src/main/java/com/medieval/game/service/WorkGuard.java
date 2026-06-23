package com.medieval.game.service;

import com.medieval.game.enums.TrainingStatus;
import com.medieval.game.enums.WorkStatus;
import com.medieval.game.model.Player;
import com.medieval.game.repository.TrainingSessionRepository;
import com.medieval.game.repository.WorkSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * [WORK_IDLE][VARREDURA] Guarda de "está trabalhando?" extraído num bean próprio.
 *
 * Antes o check vivia em {@code WorkService.assertNotBusy(WorkSessionRepository, Player)} — ESTÁTICO
 * recebendo o repo por parâmetro só p/ evitar dependência circular (os serviços de aventura precisam
 * checar "trabalhando?" mas o {@code WorkService} depende de vários deles). O custo era: 7 serviços de
 * aventura carregavam o {@code WorkSessionRepository} só de CONDUÍTE pro static. Este bean depende só do
 * repositório (zero ciclo), então cada serviço injeta {@code WorkGuard} e chama {@code assertNotBusy(player)}
 * — sem mais static-com-repo e sem o repo de conduíte espalhado.
 */
@Service
@RequiredArgsConstructor
public class WorkGuard {

    private final WorkSessionRepository workSessionRepository;
    private final TrainingSessionRepository trainingSessionRepository; // [TREINO_IDLE]

    /** true se o jogador está trabalhando AGORA (sessão em andamento que ainda não terminou). */
    public boolean isWorking(Player player) {
        return workSessionRepository.findByPlayerAndStatus(player, WorkStatus.IN_PROGRESS)
                .filter(s -> !s.isReadyToCollect())
                .isPresent();
    }

    /** [TREINO_IDLE] true se o jogador está num treino IDLE GRÁTIS com o timer ainda rodando. */
    public boolean isTrainingIdle(Player player) {
        return trainingSessionRepository.findByPlayerAndStatus(player, TrainingStatus.IN_PROGRESS)
                .filter(s -> !s.isReadyToCollect())   // pago = instantâneo (já pronto) → não bloqueia; só o idle (timer real)
                .isPresent();
    }

    /**
     * Trava cruzada: enquanto o timer do trabalho OU do treino idle roda, o jogador não pode aventurar
     * (zona/arena/torre/missão/guerra/trial). [WORK_IDLE][TREINO_IDLE]
     */
    public void assertNotBusy(Player player) {
        if (isWorking(player)) {
            throw new com.medieval.game.config.LocalizedException(
                    "error.busy_working", "You are working — finish or cancel your job first.");
        }
        if (isTrainingIdle(player)) {
            throw new com.medieval.game.config.LocalizedException(
                    "error.busy_training", "You are training — finish or cancel it first.");
        }
    }
}
