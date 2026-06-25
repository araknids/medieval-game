package com.medieval.game.service;

import com.medieval.game.config.LocalizedException;
import com.medieval.game.enums.ItemType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.InventoryItemRepository;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [ONBOARDING v3] Deveres do Recruta — 3 quests-TUTORIAL EM ORDEM, completadas por AÇÃO (não fetch):
 *   1) EQUIP (tela Personagem)      — equipe arma + armadura.                    (sem pré-requisito)
 *   2) HEAL  (Templo/Padre Anselmo) — você chega a 80% → cure-se.                (pré: equip)
 *   3) QUEST (Training Hall/Guarda) — complete 1 missão em qualquer reino.       (pré: heal)
 * Estado por dever: locked (pré não cumprido) → available → accepted → done.
 * EQUIP/HEAL completam por botão (turn-in); QUEST completa por EVENTO (KingdomService.collectQuest →
 * onQuestCompleted). Reusa os 3 pares de flag do Player (sem migração): EQUIP=shop, HEAL=priest, QUEST=guard.
 * Números/textos = placeholders. Doc: docs/PLANO_ONBOARDING.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarterQuestService {

    private final PlayerRepository        playerRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final WarriorService          warriorService;

    private enum Comp { EQUIP, HEAL, QUEST }   // como o dever completa (QUEST = por evento, não botão)

    private enum Duty {
        EQUIP("equip", "Character", "Capitão Garrick", "Um recruta desarmado é um cadáver de pé. Vista sua arma e sua armadura antes de pôr o pé lá fora.",                 Comp.EQUIP, 100, 200, null),
        HEAL ("heal",  "Temple",    "Padre Anselmo",   "Você chegou ferido da travessia. Ajoelhe no altar e deixe a Luz fechar suas feridas — vai precisar delas inteiras.", Comp.HEAL,  100, 200, "equip"),
        QUEST("quest", "Work",      "Capitão Garrick", "Recruta não vira soldado batendo em boneco. Vá ao mundo, aceite uma missão num reino e complete-a. Volte soldado.",  Comp.QUEST, 150, 300, "heal");

        final String id, npcScreen, npc, flavor;
        final Comp comp;
        final int xp, bronze;
        final String prereq;       // id do dever anterior (null = sem)
        Duty(String id, String npcScreen, String npc, String flavor, Comp comp, int xp, int bronze, String prereq) {
            this.id = id; this.npcScreen = npcScreen; this.npc = npc; this.flavor = flavor; this.comp = comp;
            this.xp = xp; this.bronze = bronze; this.prereq = prereq;
        }
        static Duty of(String which) {
            for (Duty d : values()) if (d.id.equalsIgnoreCase(which)) return d;
            throw new LocalizedException("error.starter_unknown", "Unknown recruit duty: {0}", which);
        }
        static Duty byId(String id) { for (Duty d : values()) if (d.id.equals(id)) return d; return null; }
    }

    // ── mapeamento dever → flags do Player (reuso, sem migração) ──
    private boolean isAccepted(Player p, Duty d) {
        return switch (d) { case EQUIP -> p.isStarterShopAccepted(); case HEAL -> p.isStarterPriestAccepted(); case QUEST -> p.isStarterGuardAccepted(); };
    }
    private void setAccepted(Player p, Duty d) {
        switch (d) { case EQUIP -> p.setStarterShopAccepted(true); case HEAL -> p.setStarterPriestAccepted(true); case QUEST -> p.setStarterGuardAccepted(true); }
    }
    private boolean isDone(Player p, Duty d) {
        return switch (d) { case EQUIP -> p.isStarterShopDone(); case HEAL -> p.isStarterPriestDone(); case QUEST -> p.isStarterGuardDone(); };
    }
    private void setDone(Player p, Duty d) {
        switch (d) { case EQUIP -> p.setStarterShopDone(true); case HEAL -> p.setStarterPriestDone(true); case QUEST -> p.setStarterGuardDone(true); }
    }

    private boolean prereqDone(Player p, Duty d) {
        if (d.prereq == null) return true;
        Duty pre = Duty.byId(d.prereq);
        return pre == null || isDone(p, pre);
    }
    private String state(Player p, Duty d) {
        if (isDone(p, d))      return "done";
        if (!prereqDone(p, d)) return "locked";
        if (isAccepted(p, d))  return "accepted";
        return "available";
    }

    /** Estado dos 3 deveres (state + onde mora + como completa + recompensa). */
    public Map<String, Object> status(Player player) {
        List<Map<String, Object>> quests = new ArrayList<>();
        for (Duty d : Duty.values()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", d.id);
            q.put("npc", d.npc);
            q.put("npcScreen", d.npcScreen);
            q.put("flavor", d.flavor);
            q.put("comp", d.comp.name());                 // EQUIP/HEAL/QUEST → o front decide o rótulo do botão
            q.put("rewardXp", d.xp);
            q.put("rewardBronze", d.bronze);
            q.put("state", state(player, d));
            quests.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quests", quests);
        return out;
    }

    /** Aceitar (NPC → diário). Exige pré-requisito cumprido; idempotente. */
    @Transactional
    public Map<String, Object> accept(Player player, String which) {
        Duty d = Duty.of(which);
        if (isDone(player, d))
            throw new LocalizedException("error.starter_already_done", "You have already completed this duty.");
        if (!prereqDone(player, d))
            throw new LocalizedException("error.starter_locked", "Finish the previous duty first.");
        if (!isAccepted(player, d)) {
            setAccepted(player, d);
            playerRepository.save(player);
            log.info("[StarterQuestService] player={} duty={} accepted", player.getId(), d.id);
        }
        return status(player);
    }

    /** Conclui pela AÇÃO certa (equipar / curar). QUEST conclui por EVENTO (onQuestCompleted), não aqui. */
    @Transactional
    public Map<String, Object> turnIn(Player player, String which) {
        Duty d = Duty.of(which);
        if (isDone(player, d))
            throw new LocalizedException("error.starter_already_done", "You have already completed this duty.");
        if (!prereqDone(player, d))
            throw new LocalizedException("error.starter_locked", "Finish the previous duty first.");
        if (!isAccepted(player, d))
            throw new LocalizedException("error.starter_not_accepted", "Accept this duty first.");

        Warrior warrior = warriorService.getWarrior(player);
        switch (d.comp) {
            case EQUIP -> {
                boolean weapon = inventoryItemRepository.findByPlayerAndTypeAndEquippedTrue(player, ItemType.WEAPON).isPresent();
                boolean armor  = inventoryItemRepository.findByPlayerAndTypeAndEquippedTrue(player, ItemType.ARMOR).isPresent();
                if (!(weapon && armor))
                    throw new LocalizedException("error.starter_equip_first", "Equip your weapon and armor first (Character screen).");
            }
            case HEAL -> {                                  // cura grátis do onboarding (a "ação" do dever)
                warrior.setCurrentHpSnapshot(100);
                warrior.setHpUpdatedAt(LocalDateTime.now());
            }
            case QUEST -> throw new LocalizedException("error.starter_quest_via_world", "Complete a quest in the World to finish this duty.");
        }
        grant(player, warrior, d);
        log.info("[StarterQuestService] player={} duty={} done ({}) reward xp={} bronze={}",
                player.getId(), d.id, d.comp, d.xp, d.bronze);
        return status(player);
    }

    /**
     * [ONBOARDING v3] Gancho de EVENTO: chamado pelo KingdomService quando o jogador completa uma missão.
     * Conclui o dever QUEST (Training Hall) se estiver aceito + pré-requisito cumprido. Idempotente/silencioso.
     */
    @Transactional
    public void onQuestCompleted(Player player) {
        for (Duty d : Duty.values()) {
            if (d.comp == Comp.QUEST && isAccepted(player, d) && !isDone(player, d) && prereqDone(player, d)) {
                grant(player, warriorService.getWarrior(player), d);
                log.info("[StarterQuestService] player={} duty={} done (QUEST via collectQuest)", player.getId(), d.id);
            }
        }
    }

    /**
     * [DIARIO_QUEST] Gancho de EVENTO: chamado pelo TempleController quando o jogador se cura no Templo
     * (ou já chega são — HP cheio). Conclui o dever HEAL se aceito + pré cumprido. Recarrega o Player na tx
     * p/ evitar o OptimisticLock do grant→addExperience→checkAndUnlock vs player destacado (igual ao collectQuest).
     */
    @Transactional
    public void onHealed(Player playerArg) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        for (Duty d : Duty.values()) {
            if (d.comp == Comp.HEAL && isAccepted(player, d) && !isDone(player, d) && prereqDone(player, d)) {
                grant(player, warriorService.getWarrior(player), d);
                log.info("[StarterQuestService] player={} duty={} done (HEAL via Templo)", player.getId(), d.id);
            }
        }
    }

    /** [DIARIO_QUEST] O dever HEAL está aceito e ainda não concluído (pré cumprido)? Usado pelo Templo p/
     *  liberar o clique de cura mesmo com HP cheio (a bênção do Padre completa o dever). */
    public boolean isHealDutyPending(Player player) {
        for (Duty d : Duty.values())
            if (d.comp == Comp.HEAL && isAccepted(player, d) && !isDone(player, d) && prereqDone(player, d))
                return true;
        return false;
    }

    /** Concede XP + gold e marca o dever como cumprido. (addExperience salva o warrior, incl. a cura do HEAL.) */
    private void grant(Player player, Warrior warrior, Duty d) {
        warriorService.addExperience(warrior, d.xp);
        player.addBronzeAmount(d.bronze);
        setDone(player, d);
        playerRepository.save(player);
    }
}
