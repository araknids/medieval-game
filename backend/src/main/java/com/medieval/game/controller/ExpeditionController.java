package com.medieval.game.controller;

import com.medieval.game.enums.Element;
import com.medieval.game.enums.ExpeditionSource;
import com.medieval.game.enums.ExpeditionStatus;
import com.medieval.game.enums.Kingdom;
import com.medieval.game.enums.KingdomQuestType;
import com.medieval.game.enums.SkillType;
import com.medieval.game.enums.Zone;
import com.medieval.game.model.ExpeditionRun;
import com.medieval.game.model.Player;
import com.medieval.game.quest.InteractiveQuests;
import com.medieval.game.service.ExpeditionMapGenerator;
import com.medieval.game.service.ExpeditionService;
import com.medieval.game.service.ExpeditionService.ChooseResult;
import com.medieval.game.service.ExpeditionService.ExtractResult;
import com.medieval.game.service.GatheringService.ResourceDrop;
import com.medieval.game.service.Messages;
import com.medieval.game.service.PlayerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** [INCURSAO] Endpoints da Incursão (Delve). Ver docs/PLANO_INCURSAO.md. */
@RestController
@RequestMapping("/api/expedition")
@RequiredArgsConstructor
public class ExpeditionController {

    private final ExpeditionService expeditionService;
    private final PlayerService     playerService;
    private final Messages          messages; // [I18N] diálogo do nó EVENTO por idioma

    // ── Start / current ───────────────────────────────────────────────────────

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody StartRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        ExpeditionRun run = expeditionService.start(player, req.source(), req.kingdom(),
                req.zone(), req.skillType(), req.element(), req.tier());
        return ResponseEntity.ok(runState(run, player));
    }

    @GetMapping("/current")
    public ResponseEntity<?> current(Authentication auth) {
        Player player = getPlayer(auth);
        ExpeditionRun run = expeditionService.current(player);
        return run == null ? ResponseEntity.ok(Map.of("active", false))
                           : ResponseEntity.ok(runState(run, player));
    }

    // ── Choose / resolve / extract / abandon ──────────────────────────────────

    @PostMapping("/{id}/choose")
    public ResponseEntity<?> choose(@PathVariable Long id, @RequestBody ChooseRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(chooseResponse(expeditionService.choose(player, id, req.nodeId()), player));
    }

    @PostMapping("/{id}/node")
    public ResponseEntity<?> node(@PathVariable Long id, @RequestBody NodeRequest req, Authentication auth) {
        Player player = getPlayer(auth);
        return ResponseEntity.ok(chooseResponse(expeditionService.resolveNode(player, id, req.optionId()), player));
    }

    @PostMapping("/{id}/extract")
    public ResponseEntity<?> extract(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(extractResponse(expeditionService.extract(getPlayer(auth), id)));
    }

    @PostMapping("/{id}/abandon")
    public ResponseEntity<?> abandon(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(extractResponse(expeditionService.abandon(getPlayer(auth), id)));
    }

    // ── Serialização ──────────────────────────────────────────────────────────

    private Map<String, Object> runState(ExpeditionRun run, Player player) {
        var m = new java.util.HashMap<String, Object>();
        m.put("active", run.isActive());
        m.put("id", run.getId());
        m.put("source", run.getSource().name());
        m.put("status", run.getStatus().name());
        m.put("depth", run.getDepth());
        m.put("currentLayer", run.getCurrentLayer());
        m.put("tier", run.getTier());
        if (run.getKingdom() != null)   m.put("kingdom", run.getKingdom().name());
        if (run.getZone() != null)      m.put("zone", run.getZone().name());
        if (run.getSkillType() != null) m.put("skillType", run.getSkillType().name());
        if (run.getElement() != null)   m.put("element", run.getElement().name());
        m.put("map", mapJson(run));
        m.put("carried", Map.of(
                "bronze", run.getCarriedBronze(),
                "xp", run.getCarriedXp(),
                "resources", resourceList(expeditionService.carriedResourceList(run)),
                "items", carriedItems(player)));   // [INCURSAO] itens ganhos (em risco) — antes não eram serializados
        m.put("secured", Map.of(
                "bronze", run.getSecuredBronze(),
                "xp", run.getSecuredXp(),
                "resources", resourceList(expeditionService.securedResourceList(run))));
        m.put("canExtract", expeditionService.runCanExtract(run));
        m.put("scene", sceneFor(run));
        // EVENTO pendente → devolve o diálogo (evento nativo da Incursão OU quest de reino)
        if (run.getStatus() == ExpeditionStatus.NODE_PENDING) {
            m.put("pendingNodeId", run.getPendingNodeId());
            putEventDialog(m, run);
        }
        return m;
    }

    // [INCURSAO] Itens (equipamento) ganhos durante a run, ainda "em risco" (run-pending). Carrega no carried.
    private List<Map<String, Object>> carriedItems(Player player) {
        return expeditionService.runPendingItems(player).stream().map(it -> {
            Map<String, Object> mi = new java.util.HashMap<>();
            mi.put("id", it.getId());
            mi.put("name", it.getName());
            mi.put("rarity", it.getRarity());
            mi.put("type", it.getType() != null ? it.getType().name() : "");
            mi.put("itemLevel", it.getItemLevel());
            return mi;
        }).toList();
    }

    private List<Map<String, Object>> mapJson(ExpeditionRun run) {
        ExpeditionMapGenerator.Map map = expeditionService.mapOf(run);
        boolean choosable = run.getStatus() == ExpeditionStatus.IN_PROGRESS;
        int prevIdx = run.getLastNodeIndex();   // [INCURSAO] coluna escolhida na camada anterior (-1 = sem restrição)
        List<Map<String, Object>> layers = new ArrayList<>();
        for (ExpeditionMapGenerator.Layer layer : map.layers()) {
            List<Map<String, Object>> nodes = new ArrayList<>();
            boolean isCurrent = choosable && layer.index() == run.getCurrentLayer();
            List<ExpeditionMapGenerator.Node> ns = layer.nodes();
            for (int i = 0; i < ns.size(); i++) {
                // caminho ramificado: só as vizinhas (i-1/i/i+1) da coluna anterior ficam alcançáveis.
                boolean neighbor = prevIdx < 0 || Math.abs(i - prevIdx) <= 1;
                nodes.add(Map.of(
                        "id", ns.get(i).id(),
                        "type", ns.get(i).type().name(),
                        "reachable", isCurrent && neighbor));
            }
            layers.add(Map.of("index", layer.index(), "nodes", nodes));
        }
        return layers;
    }

    private Map<String, Object> chooseResponse(ChooseResult cr, Player player) {
        var m = new java.util.HashMap<String, Object>();
        m.put("resolvedType", cr.resolvedType() != null ? cr.resolvedType().name() : null);
        m.put("nodePending", cr.nodePending());
        m.put("ko", cr.ko());
        m.put("bronzeGained", cr.bronzeGained());
        m.put("xpGained", cr.xpGained());
        m.put("drops", resourceList(cr.drops()));
        if (cr.lootItemName() != null) m.put("lootItemName", cr.lootItemName());
        if (cr.lootItemId() != null)   m.put("lootItemId", cr.lootItemId());
        if (cr.monsterName() != null)  m.put("monsterName", cr.monsterName());
        m.put("narrative", cr.narrative());
        m.put("battleLog", cr.battleLog());
        m.put("battleEvents", cr.battleEvents());
        m.put("scene", sceneFor(cr.run()));
        m.put("canExtract", cr.canExtract());
        if (cr.nodePending()) putEventDialog(m, cr.run());
        m.put("state", runState(cr.run(), player)); // estado atualizado p/ re-render do mapa
        return m;
    }

    private Map<String, Object> extractResponse(ExtractResult er) {
        var m = new java.util.HashMap<String, Object>();
        m.put("status", er.run().getStatus().name());
        m.put("bronzeBanked", er.bronzeBanked());
        m.put("xpBanked", er.xpBanked());
        m.put("bankedResources", resourceList(er.bankedResources()));
        m.put("keptItems", er.keptItems());
        m.put("mailedItems", er.mailedItems());
        m.put("narrative", er.narrative());
        return m;
    }

    private List<Map<String, Object>> resourceList(List<ResourceDrop> drops) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (drops != null) for (ResourceDrop d : drops) out.add(Map.of(
                "type", d.type().name(),
                "displayName", d.type().displayName,
                "quantity", d.quantity()));
        return out;
    }

    /** Põe o "dialog" do evento pendente: nativo da Incursão (pacto/loja/altar/santuário) OU quest de reino. */
    private void putEventDialog(Map<String, Object> m, ExpeditionRun run) {
        if (run.getPendingDelveEvent() != null) {
            Map<String, Object> d = expeditionService.delveEventDialog(run);
            if (d != null) m.put("dialog", d);
        } else if (run.getPendingEventQuest() != null) {
            dialogJson(run.getPendingEventQuest()).ifPresent(d -> m.put("dialog", d));
        }
    }

    /** Constrói o diálogo do nó EVENTO a partir do KingdomQuestType (reusa as chaves i18n de quest). */
    private java.util.Optional<Map<String, Object>> dialogJson(String questName) {
        KingdomQuestType qt;
        try { qt = KingdomQuestType.valueOf(questName); } catch (IllegalArgumentException e) { return java.util.Optional.empty(); }
        return InteractiveQuests.dialogFor(qt).map(d -> {
            String base = "questdlg." + qt.name();
            return Map.of(
                "intro", messages.getOr(base + ".intro", d.intro()),
                "options", d.options().stream().map(o -> Map.of(
                    "id", o.id(),
                    "label", messages.getOr(base + ".opt." + o.id() + ".label", o.label()),
                    "hint",  messages.getOr(base + ".opt." + o.id() + ".hint",  o.hint()))).toList());
        });
    }

    /** Fundo do replay (mesma regra do KingdomController/ZoneController). [BATALHA_ANIMADA] */
    private static String sceneFor(ExpeditionRun run) {
        if (run.getSkillType() != null) {
            return switch (run.getSkillType()) {
                case FISHING -> "coast";
                case MINING, GARIMPO -> "cave";
                default -> "fortress";
            };
        }
        Kingdom k = run.getKingdom();
        if (k == null) return "fortress";
        return switch (k) {
            case FISHING -> "coast";
            case MAR_ABENCOADO -> "sea";
            case MINING, GRUTAS_DE_CRISTAL -> "cave";
            default -> "fortress";
        };
    }

    private Player getPlayer(Authentication auth) {
        return playerService.findById((Long) auth.getPrincipal());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    record StartRequest(ExpeditionSource source, Kingdom kingdom, Zone zone,
                        SkillType skillType, Element element, int tier) {}
    record ChooseRequest(String nodeId) {}
    record NodeRequest(String optionId) {}
}
