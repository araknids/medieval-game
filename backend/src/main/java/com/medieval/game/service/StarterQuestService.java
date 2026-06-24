package com.medieval.game.service;

import com.medieval.game.config.LocalizedException;
import com.medieval.game.enums.ResourceType;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [ONBOARDING v2] Deveres do Recruta — quests de ENTREGA descobertas no NPC. Estado por quest:
 * available (o NPC oferece) -> accepted (entra no diário do topbar) -> done. Aceitar é só "Aceitar"
 * (sem recusar). A entrega consome o recurso pedido e dá XP + gold; uma vez cada (flags no Player;
 * soft-wipe reseta). Fluxo leve (NÃO reusa o KingdomService) e fundação do sistema de quest geral.
 * NPC/flavor/recurso/recompensa = PLACEHOLDERS. Doc: docs/PLANO_ONBOARDING.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarterQuestService {

    private final PlayerRepository playerRepository;
    private final GatheringService gatheringService;
    private final WarriorService   warriorService;

    /** Catálogo fixo. npcScreen = tela onde o NPC mora (badge no nav + oferta lá). */
    private enum Duty {
        GUARD ("guard",  "Work",   "Training Hall Guard", "Prove your steel, recruit — bring me a Monster Core and I'll enter your name in the watch roll.", ResourceType.MONSTER_CORE, 1, 120, 300),
        PRIEST("priest", "Temple", "Father Anselm",       "Lay a fresh catch upon the shrine, and the Light will steady your hand in the dark to come.",   ResourceType.SMALL_FISH,   1, 100, 250),
        SHOP  ("shop",   "Shop",   "Shopkeeper",          "New blood? Fetch me a little ore for the forge and I'll see your purse isn't empty.",            ResourceType.COPPER_ORE,   2, 100, 250);

        final String id, npcScreen, npc, flavor;
        final ResourceType need;
        final int needQty, xp, bronze;
        Duty(String id, String npcScreen, String npc, String flavor, ResourceType need, int needQty, int xp, int bronze) {
            this.id = id; this.npcScreen = npcScreen; this.npc = npc; this.flavor = flavor;
            this.need = need; this.needQty = needQty; this.xp = xp; this.bronze = bronze;
        }
        static Duty of(String which) {
            for (Duty d : values()) if (d.id.equalsIgnoreCase(which)) return d;
            throw new LocalizedException("error.starter_unknown", "Unknown recruit duty: {0}", which);
        }
    }

    private boolean isAccepted(Player p, Duty d) {
        return switch (d) {
            case GUARD  -> p.isStarterGuardAccepted();
            case PRIEST -> p.isStarterPriestAccepted();
            case SHOP   -> p.isStarterShopAccepted();
        };
    }
    private void setAccepted(Player p, Duty d) {
        switch (d) {
            case GUARD  -> p.setStarterGuardAccepted(true);
            case PRIEST -> p.setStarterPriestAccepted(true);
            case SHOP   -> p.setStarterShopAccepted(true);
        }
    }
    private boolean isDone(Player p, Duty d) {
        return switch (d) {
            case GUARD  -> p.isStarterGuardDone();
            case PRIEST -> p.isStarterPriestDone();
            case SHOP   -> p.isStarterShopDone();
        };
    }
    private void setDone(Player p, Duty d) {
        switch (d) {
            case GUARD  -> p.setStarterGuardDone(true);
            case PRIEST -> p.setStarterPriestDone(true);
            case SHOP   -> p.setStarterShopDone(true);
        }
    }
    private String state(Player p, Duty d) {
        if (isDone(p, d))     return "done";
        if (isAccepted(p, d)) return "accepted";
        return "available";
    }

    /** Estado dos 3 deveres (state + onde mora o NPC + o que pede + recompensa). */
    public Map<String, Object> status(Player player) {
        List<Map<String, Object>> quests = new ArrayList<>();
        for (Duty d : Duty.values()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", d.id);
            q.put("npc", d.npc);
            q.put("npcScreen", d.npcScreen);
            q.put("flavor", d.flavor);
            q.put("needType", d.need.name());
            q.put("needName", d.need.displayName);
            q.put("needQty", d.needQty);
            q.put("have", gatheringService.resourceQuantityTotal(player, d.need));
            q.put("rewardXp", d.xp);
            q.put("rewardBronze", d.bronze);
            q.put("state", state(player, d));
            quests.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quests", quests);
        return out;
    }

    /** Aceitar (NPC -> diário). Idempotente. Não pode aceitar uma já entregue. */
    @Transactional
    public Map<String, Object> accept(Player player, String which) {
        Duty d = Duty.of(which);
        if (isDone(player, d))
            throw new LocalizedException("error.starter_already_done", "You have already completed this duty.");
        if (!isAccepted(player, d)) {
            setAccepted(player, d);
            playerRepository.save(player);
            log.info("[StarterQuestService] player={} duty={} accepted", player.getId(), d.id);
        }
        return status(player);
    }

    /** Entrega o recurso pedido e concede XP + gold. Exige aceita; uma vez por dever. */
    @Transactional
    public Map<String, Object> turnIn(Player player, String which) {
        Duty d = Duty.of(which);
        if (isDone(player, d))
            throw new LocalizedException("error.starter_already_done", "You have already completed this duty.");
        if (!isAccepted(player, d))
            throw new LocalizedException("error.starter_not_accepted", "Accept this duty first.");
        // consome o recurso pedido (bag + stash) — lança error.gather_insufficient se faltar
        gatheringService.removeResourceTotal(player, d.need, d.needQty);
        Warrior warrior = warriorService.getWarrior(player);
        warriorService.addExperience(warrior, d.xp);
        player.addBronzeAmount(d.bronze);
        setDone(player, d);
        playerRepository.save(player);
        log.info("[StarterQuestService] player={} duty={} turnedIn need={}x{} reward xp={} bronze={}",
                player.getId(), d.id, d.needQty, d.need.name(), d.xp, d.bronze);
        return status(player);
    }
}
