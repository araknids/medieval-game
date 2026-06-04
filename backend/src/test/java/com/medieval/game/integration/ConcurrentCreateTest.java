package com.medieval.game.integration;

import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.SkillLevel;
import com.medieval.game.model.WorkProfession;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.service.ConcurrentEntityCreator;
import com.medieval.game.service.GatheringService;
import com.medieval.game.service.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// M15 — getOrCreateSkill/getProfession resiliente a criação concorrente (sem 500).
@DisplayName("Auditoria M15 | getOrCreate de skill/profissão resiliente a concorrência")
class ConcurrentCreateTest extends BaseIntegrationTest {

    @Autowired GatheringService        gatheringService;
    @Autowired WorkService             workService;
    @Autowired ConcurrentEntityCreator entityCreator;
    @Autowired PlayerRepository        playerRepository;

    Player player;

    @BeforeEach
    void setup() throws Exception {
        String user = uniqueUser("m15");
        registerAndGetToken(user);
        player = playerRepository.findByUsername(user).orElseThrow();
    }

    @Test
    @DisplayName("getOrCreateSkill é idempotente (mesma linha, sem erro)")
    void getOrCreateSkill_idempotent() {
        SkillLevel a = gatheringService.getOrCreateSkill(player, SkillType.FISHING);
        SkillLevel b = gatheringService.getOrCreateSkill(player, SkillType.FISHING);
        assertThat(a.getId()).isEqualTo(b.getId());
    }

    @Test
    @DisplayName("getProfession é idempotente (mesma linha, sem erro)")
    void getProfession_idempotent() {
        WorkProfession a = workService.getProfession(player, WorkType.TAVERN_HELPER);
        WorkProfession b = workService.getProfession(player, WorkType.TAVERN_HELPER);
        assertThat(a.getId()).isEqualTo(b.getId());
    }

    @Test
    @DisplayName("INSERT duplicado lança DataIntegrityViolationException (a exceção que o getOrCreate captura)")
    void duplicateInsert_throwsCaughtException() {
        entityCreator.createSkill(player, SkillType.MINING); // 1ª vez ok
        assertThatThrownBy(() -> entityCreator.createSkill(player, SkillType.MINING))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Linha já criada por 'outra transação' → getOrCreate retorna a existente, sem estourar")
    void recoversWhenRowAlreadyExists() {
        SkillLevel existing = entityCreator.createSkill(player, SkillType.GARIMPO);
        SkillLevel got = gatheringService.getOrCreateSkill(player, SkillType.GARIMPO);
        assertThat(got.getId()).isEqualTo(existing.getId());
    }
}
