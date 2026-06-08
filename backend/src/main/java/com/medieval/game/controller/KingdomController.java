package com.medieval.game.controller;

import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.model.*;
import com.medieval.game.quest.InteractiveQuests;
import com.medieval.game.service.KingdomService;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/world")
@RequiredArgsConstructor
public class KingdomController {

    private final KingdomService                   kingdomService;
    private final PlayerService                    playerService;
    private final com.medieval.game.service.Messages messages; // [I18N] nome/flavor/diálogo da quest por idioma

    // ── Kingdom overview ──────────────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> listKingdoms(Authentication auth) {
        Player player = getPlayer(auth);
        List<?> kingdoms = kingdomService.getAllKingdomStatus(player).stream()
                .map(ks -> Map.ofEntries(
                    Map.entry("kingdom",          ks.kingdom().name()),
                    Map.entry("displayName",      messages.getOr("kingdom." + ks.kingdom().name() + ".name", ks.kingdom().displayName)), // [I18N]
                    Map.entry("icon",             ks.kingdom().icon),
                    Map.entry("lore",             messages.getOr("kingdom." + ks.kingdom().name() + ".lore", ks.kingdom().lore)),         // [I18N]
                    Map.entry("controllingGuild", ks.controllingGuild() != null ? ks.controllingGuild() : ""),
                    Map.entry("isMine",           ks.isMine()),
                    Map.entry("xpBonus",          ks.xpBonus()),
                    Map.entry("bronzeBonus",      ks.bronzeBonus()),
                    Map.entry("exclusiveBonus",   ks.exclusiveBonus()),
                    Map.entry("secsUntilBattle",  ks.secsUntilBattle()),
                    Map.entry("defenseStreak",    ks.defenseStreak()),
                    Map.entry("primarySkill",     ks.kingdom().primarySkill != null
                            ? ks.kingdom().primarySkill.name() : "")
                )).toList();
        return ResponseEntity.ok(kingdoms);
    }

    // [FORTALEZA_ZONAS] O antigo "Hunt Beasts" (POST /{kingdom}/raid + CombatPveService) foi removido:
    // a caçada PvE virou as 3 zonas (🟢/🟡/🔴) da Fortaleza pelo sistema de zonas (/api/zones).

    // ── Quest types for a kingdom ─────────────────────────────────────────────
    @GetMapping("/{kingdom}/quests")
    public ResponseEntity<?> getQuestTypes(@PathVariable Kingdom kingdom, Authentication auth) {
        Player player = getPlayer(auth);
        int stamina = player.getCalculatedStamina();
        long secondsUntilReset = kingdomService.secondsUntilQuestRotation(); // [DAILY_QUESTS] boundary de 12h

        List<Map<String, Object>> quests = new java.util.ArrayList<>(kingdomService.getQuestsForKingdom(kingdom).stream()
                .map(qt -> {
                    boolean done = kingdomService.isQuestDoneThisPeriod(player, qt); // [DAILY_QUESTS]
                    return Map.<String, Object>ofEntries(
                        Map.entry("id",                qt.name()),
                        Map.entry("displayName",       messages.getOr("quest." + qt.name() + ".name", qt.displayName)),   // [I18N]
                        Map.entry("flavor",            messages.getOr("quest." + qt.name() + ".flavor", qt.flavor)),      // [I18N][QUESTS_LORE]
                        Map.entry("durationMinutes",   qt.durationMinutes),
                        Map.entry("bronzeReward",      qt.bronzeReward),
                        Map.entry("expReward",         qt.expReward),
                        Map.entry("staminaCost",       qt.staminaCost),
                        Map.entry("dropChance",        qt.dropChance),
                        Map.entry("interactive",       InteractiveQuests.isInteractive(qt)), // [QUESTS_INTERATIVAS]
                        Map.entry("doneToday",         done),
                        Map.entry("secondsUntilReset", secondsUntilReset),
                        Map.entry("canStart",          !done && stamina >= qt.staminaCost)
                    );
                }).toList());

        // [LUNA_INTERRUPT] A Luna não é mais uma quest da vitrine — ela interrompe missões normais
        // aleatoriamente (ver KingdomService.shouldLunaInterrupt). Substituiu a antiga quest avulsa.
        return ResponseEntity.ok(quests);
    }

    // ── Active quests in a kingdom ────────────────────────────────────────────
    @GetMapping("/{kingdom}/quests/active")
    public ResponseEntity<?> getActiveQuests(@PathVariable Kingdom kingdom, Authentication auth) {
        Player player = getPlayer(auth);
        List<?> active = kingdomService.getActiveQuests(player, kingdom).stream()
                .map(this::questToMap).toList();
        return ResponseEntity.ok(active);
    }

    // ── Start a kingdom quest ─────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/start")
    public ResponseEntity<?> startQuest(
            @PathVariable Kingdom kingdom,
            @RequestBody StartQuestRequest req,
            Authentication auth) {
        Player player = getPlayer(auth);
        KingdomActiveQuest quest = kingdomService.startQuest(player, kingdom, req.questType());
        var resp = new java.util.HashMap<String, Object>(questToMap(quest));
        // [QUESTS_INTERATIVAS] se a quest tem diálogo, devolve história + opções (sem vazar os outcomes)
        KingdomQuestType qt = quest.getQuestType();
        InteractiveQuests.dialogFor(qt).ifPresent(d -> {
            resp.put("interactive", true);
            String base = "questdlg." + qt.name(); // [I18N] intro/opções por idioma; EN = a prosa do catálogo
            resp.put("dialog", Map.of(
                "intro", messages.getOr(base + ".intro", d.intro()),
                "options", d.options().stream().map(o -> Map.of(
                    "id", o.id(),
                    "label", messages.getOr(base + ".opt." + o.id() + ".label", o.label()),
                    "hint",  messages.getOr(base + ".opt." + o.id() + ".hint",  o.hint()))).toList()
            ));
        });
        return ResponseEntity.ok(resp);
    }

    // ── Collect quest reward ──────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/{id}/collect")
    public ResponseEntity<?> collectQuest(
            @PathVariable Kingdom kingdom,
            @PathVariable Long id,
            @RequestBody(required = false) CollectQuestRequest req,
            Authentication auth) {
        Player player = getPlayer(auth);
        String optionId = req != null ? req.optionId() : null; // [QUESTS_INTERATIVAS] escolha do diálogo
        return ResponseEntity.ok(collectResultToMap(kingdomService.collectQuest(player, id, optionId)));
    }

    // ── [LUNA_INTERRUPT] Decidir sobre o cãozinho que interrompeu a missão (help = ajudar / ignore = terminar) ──
    @PostMapping("/{kingdom}/quests/{id}/luna/{action}")
    public ResponseEntity<?> lunaDecision(
            @PathVariable Kingdom kingdom,
            @PathVariable Long id,
            @PathVariable String action,
            Authentication auth) {
        Player player = getPlayer(auth);
        KingdomService.CollectResult result = "help".equalsIgnoreCase(action)
                ? kingdomService.resolveLunaHelp(player, id)
                : kingdomService.resolveLunaIgnore(player, id);
        return ResponseEntity.ok(collectResultToMap(result));
    }

    /** Serializa o CollectResult de uma quest (reusado por collect + decisão da Luna). [LUNA_INTERRUPT] */
    private Map<String, Object> collectResultToMap(KingdomService.CollectResult result) {
        var resp = new java.util.HashMap<String, Object>();
        resp.put("bronzeEarned", result.bronzeEarned());
        resp.put("xpEarned",     result.xpEarned());
        resp.put("questId",      result.quest().getId());
        resp.put("narrative",          result.narrative());
        resp.put("monsterEncountered", result.monsterEncountered());
        resp.put("monsterDefeated",    result.monsterDefeated());
        if (result.monsterName() != null) resp.put("monsterName", result.monsterName());
        resp.put("battleLog",          result.battleLog());
        if (result.acquiredPet() != null) resp.put("acquiredPet", result.acquiredPet()); // [PETS]
        if (result.lunaPending()) resp.put("lunaPending", true);                          // [LUNA_INTERRUPT]
        if (result.roll() != null) { // [QUESTS_INTERATIVAS] resultado do teste de atributo (d20)
            KingdomService.RollInfo r = result.roll();
            resp.put("roll", Map.of(
                "attr", r.attr(), "rolled", r.rolled(), "mod", r.mod(), "dc", r.dc(), "passed", r.passed()));
        }
        if (result.droppedItem() != null) {
            InventoryItem d = result.droppedItem();
            resp.put("droppedItem", Map.of(
                "name",        d.getName(),
                "type",        d.getType().name(),
                "rarity",      d.getRarity(),
                "attackBonus", d.getAttackBonus(),
                "defenseBonus",d.getDefenseBonus(),
                "healthBonus", d.getHealthBonus()
            ));
        }
        return resp;
    }

    // ── Abandon quest ─────────────────────────────────────────────────────────
    @PostMapping("/{kingdom}/quests/{id}/abandon")
    public ResponseEntity<?> abandonQuest(
            @PathVariable Kingdom kingdom,
            @PathVariable Long id,
            Authentication auth) {
        kingdomService.abandonQuest(getPlayer(auth), id);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.quest_abandoned", "Quest abandoned.")));
    }

    // ── Training (Combat Kingdom) ─────────────────────────────────────────────
    @GetMapping("/COMBAT/training")
    public ResponseEntity<?> getCurrentTraining(Authentication auth) {
        Player player = getPlayer(auth);
        return kingdomService.getCurrentTraining(player)
                .map(s -> ResponseEntity.ok(trainingToMap(s)))
                .orElse(ResponseEntity.ok(Map.of("active", false)));
    }

    @PostMapping("/COMBAT/training/start")
    public ResponseEntity<?> startTraining(
            @RequestBody TrainingRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        TrainingSession session = kingdomService.startTraining(player, req.hours());
        return ResponseEntity.ok(trainingToMap(session));
    }

    @PostMapping("/COMBAT/training/{id}/cancel")
    public ResponseEntity<?> cancelTraining(
            @PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        kingdomService.cancelTraining(player, id);
        return ResponseEntity.ok(Map.of("message", com.medieval.game.service.Messages.tr("msg.training_cancelled", "Training cancelled. Warrior freed.")));
    }

    @PostMapping("/COMBAT/training/{id}/collect")
    public ResponseEntity<?> collectTraining(
            @PathVariable Long id, Authentication auth) {
        Player player = getPlayer(auth);
        TrainingSession session = kingdomService.collectTraining(player, id);
        return ResponseEntity.ok(Map.of(
            "message",   com.medieval.game.service.Messages.tr("msg.training_complete", "Training complete! +{0} XP", session.getXpReward()),
            "xpEarned",  session.getXpReward()
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    private Map<String, Object> questToMap(KingdomActiveQuest q) {
        return Map.of(
            "id",              q.getId(),
            "kingdom",         q.getKingdom().name(),
            "questType",       q.getQuestType().name(),
            "displayName",     messages.getOr("quest." + q.getQuestType().name() + ".name", q.getQuestType().displayName), // [I18N]
            "status",          q.getStatus().name(),
            "bronzeReward",    q.getBronzeReward(),
            "expReward",       q.getExpReward(),
            "secondsRemaining",q.secondsRemaining(),
            "readyToCollect",  q.isReadyToCollect()
        );
    }

    private Map<String, Object> trainingToMap(TrainingSession s) {
        long secs = Math.max(0, java.time.temporal.ChronoUnit.SECONDS
                .between(java.time.LocalDateTime.now(), s.getFinishesAt()));
        return Map.of(
            "active",          true,
            "id",              s.getId(),
            "hours",           s.getHours(),
            "bronzeCost",      s.getBronzeCost(),
            "xpReward",        s.getXpReward(),
            "secondsRemaining",secs,
            "readyToCollect",  s.isReadyToCollect()
        );
    }

    record StartQuestRequest(KingdomQuestType questType) {}
    record CollectQuestRequest(String optionId) {} // [QUESTS_INTERATIVAS] escolha do diálogo (null = não-interativa)
    record TrainingRequest(int hours) {}
}
