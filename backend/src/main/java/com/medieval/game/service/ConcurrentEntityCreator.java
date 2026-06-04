package com.medieval.game.service;

import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.WorkType;
import com.medieval.game.model.Player;
import com.medieval.game.model.SkillLevel;
import com.medieval.game.model.WorkProfession;
import com.medieval.game.repository.SkillLevelRepository;
import com.medieval.game.repository.WorkProfessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cria a 1ª SkillLevel/WorkProfession do player em transação PRÓPRIA (REQUIRES_NEW). [AUDITORIA M15]
 *
 * O unique `(player, skill)` / `(player, work)` já impede duplicatas; o problema era o 500 raro quando
 * duas requisições concorrentes tentavam criar a MESMA linha pela primeira vez. Isolando o INSERT numa
 * transação nova, o conflito faz rollback só DESTA transação (e não contamina a transação externa do
 * chamador) — o chamador captura `DataIntegrityViolationException`, relê a linha que a outra gravou e segue.
 */
@Service
@RequiredArgsConstructor
public class ConcurrentEntityCreator {

    private final SkillLevelRepository     skillRepository;
    private final WorkProfessionRepository professionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SkillLevel createSkill(Player player, SkillType skillType) {
        SkillLevel s = new SkillLevel();
        s.setPlayer(player);
        s.setSkillType(skillType);
        return skillRepository.save(s);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WorkProfession createProfession(Player player, WorkType workType) {
        WorkProfession p = new WorkProfession();
        p.setPlayer(player);
        p.setWorkType(workType);
        return professionRepository.save(p);
    }
}
