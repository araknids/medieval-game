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
 * [ONBOARDING] Deveres do Recruta — 3 quests de ENTREGA únicas (Camada B). Um NPC pede um recurso; o
 * recruta ENTREGA e recebe XP + gold. Uma vez cada (flag no Player; soft-wipe reseta). Sem estamina, sem
 * rotação, sem combate — fluxo leve (NÃO reusa o KingdomService). Doc: docs/PLANO_ONBOARDING.md.
 * NPC/flavor/recurso/recompensa são PLACEHOLDERS p/ tuning no playtest. Texto em EN (i18n PT do conteúdo
 * dinâmico = follow-up; as labels estáticas da tela são traduzidas no cliente via Lang).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StarterQuestService {

    private final PlayerRepository playerRepository;
    private final GatheringService gatheringService;
    private final WarriorService   warriorService;

    /** Catálogo fixo dos 3 deveres. Cada um pede um recurso early-game (levelRequired 1). */
    private enum Duty {
        GUARD ("guard",  "Training Hall Guard", "Prove your steel, recruit — bring me a Monster Core and I'll enter your name in the watch roll.", ResourceType.MONSTER_CORE, 1, 120, 300),
        PRIEST("priest", "Father Anselm",       "Lay a fresh catch upon the shrine, and the Light will steady your hand in the dark to come.",   ResourceType.SMALL_FISH,   1, 100, 250),
        SHOP  ("shop",   "Shopkeeper",          "New blood? Fetch me a little ore for the forge and I'll see your purse isn't empty.",            ResourceType.COPPER_ORE,   2, 100, 250);

        final String id, npc, flavor;
        final ResourceType need;
        final int needQty, xp, bronze;
        Duty(String id, String npc, String flavor, ResourceType need, int needQty, int xp, int bronze) {
            this.id = id; this.npc = npc; this.flavor = flavor; this.need = need;
            this.needQty = needQty; this.xp = xp; this.bronze = bronze;
        }
        static Duty of(String which) {
            for (Duty d : values()) if (d.id.equalsIgnoreCase(which)) return d;
            throw new LocalizedException("error.starter_unknown", "Unknown recruit duty: {0}", which);
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

    /** Estado dos 3 deveres: o que cada NPC pede, quanto o jogador já tem, e se foi cumprido. */
    public Map<String, Object> status(Player player) {
        List<Map<String, Object>> quests = new ArrayList<>();
        for (Duty d : Duty.values()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", d.id);
            q.put("npc", d.npc);
            q.put("flavor", d.flavor);
            q.put("needType", d.need.name());
            q.put("needName", d.need.displayName);
            q.put("needQty", d.needQty);
            q.put("have", gatheringService.resourceQuantityTotal(player, d.need));
            q.put("rewardXp", d.xp);
            q.put("rewardBronze", d.bronze);
            q.put("done", isDone(player, d));
            quests.add(q);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("quests", quests);
        return out;
    }

    /** Entrega o recurso pedido e concede XP + gold. Uma vez por dever; valida posse e flag. */
    @Transactional
    public Map<String, Object> turnIn(Player player, String which) {
        Duty d = Duty.of(which);
        if (isDone(player, d))
            throw new LocalizedException("error.starter_already_done", "You have already completed this duty.");
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
