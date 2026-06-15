package com.medieval.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medieval.game.enums.*;
import com.medieval.game.model.ExpeditionRun;
import com.medieval.game.model.InventoryItem;
import com.medieval.game.model.Player;
import com.medieval.game.model.Warrior;
import com.medieval.game.quest.InteractiveQuests;
import com.medieval.game.quest.QuestDialog;
import com.medieval.game.quest.QuestDialog.QuestOption;
import com.medieval.game.quest.QuestOutcome;
import com.medieval.game.repository.ExpeditionRunRepository;
import com.medieval.game.repository.PlayerRepository;
import com.medieval.game.repository.ResourceInventoryRepository;
import com.medieval.game.repository.WarriorRepository;
import com.medieval.game.repository.WorkSessionRepository;
import com.medieval.game.service.ExpeditionMapGenerator.Layer;
import com.medieval.game.service.ExpeditionMapGenerator.Node;
import com.medieval.game.service.GatheringService.ResourceDrop;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * [INCURSAO] Motor da Incursão (Delve): run roguelike de mapa ramificado, compartilhado por quest
 * (fonte KINGDOM = gear) e coleta (fonte ZONE = recursos). Push-your-luck: o loot vai p/ uma bolsa
 * carregada; nós CAMP e o extract TRAVAM (banca) o que foi sacado; KO/abandono perde a bolsa
 * não-travada. Gate = estamina (entrar) + HP (drena entre batalhas; KO = teto). Ver docs/PLANO_INCURSAO.md.
 *
 * <p>Reusa: {@link BattleSimulator} (HP persiste entre nós), {@link InteractiveQuests}/{@link QuestOutcome}
 * (nós EVENTO), {@link InventoryService#makeRunPending} (gear carregado), {@link GatheringService} (recursos).
 * Números são placeholders p/ tuning no playtest.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpeditionService {

    private static final List<ExpeditionStatus> ACTIVE =
            List.of(ExpeditionStatus.IN_PROGRESS, ExpeditionStatus.NODE_PENDING);
    private static final int PVP_FLAG_MINUTES   = 60; // [PVP_FLAG] exposto ao entrar/extrair de zona 🟡/🔴
    private static final int PVP_SHIELD_MINUTES = 60; // imune por 1h após ser saqueado
    private static final int PVP_LEVEL_BAND     = 10; // só cruza com flagged dentro de ±10 níveis

    private final ExpeditionRunRepository expeditionRepo;
    private final PlayerRepository        playerRepository;
    private final WarriorRepository       warriorRepo;
    private final PlayerService           playerService;
    private final WarriorService          warriorService;
    private final WarriorStatsService     statsService;
    private final BattleSimulator         battleSimulator;
    private final AbilityService          abilityService;
    private final InventoryService        inventoryService;
    private final GatheringService        gatheringService;
    private final ResourceInventoryRepository resourceRepo; // [PVP_FLAG] saque de recursos no raid
    private final MailService             mailService;
    private final ItemLoreGenerator       loreGenerator;
    private final KingdomQuestNarrator    narrator;
    private final WorkSessionRepository   workSessionRepository;
    private final ObjectMapper            objectMapper;
    private final Messages                messages;       // [I18N] narrativas da run por idioma

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // [INCURSAO] Armadilha de baú liga/desliga (FALSE nos testes p/ TREASURE determinístico).
    @Value("${app.expedition.trap-enabled:true}")
    private boolean trapEnabled;

    // [PVP_FLAG] Gatilho de raid PvP nas zonas 🟡/🔴 liga/desliga (FALSE nos testes → collect
    // determinístico; o saque é exercitado direto via raidForTest, igual ao boss em ZoneBossIntegrationTest).
    @Value("${app.expedition.pvp-raid-enabled:true}")
    private boolean pvpRaidEnabled;

    // ── Resultados (records p/ o controller serializar) ───────────────────────

    public record ChooseResult(
            ExpeditionRun run, ExpeditionNodeType resolvedType, boolean nodePending,
            String eventQuest, List<ResourceDrop> drops, String lootItemName, Long lootItemId,
            long bronzeGained, long xpGained, boolean ko,
            List<String> battleLog, List<BattleSimulator.BattleEvent> battleEvents,
            String narrative, String monsterName, boolean canExtract) {}

    public record ExtractResult(
            ExpeditionRun run, long bronzeBanked, long xpBanked, List<ResourceDrop> bankedResources,
            int keptItems, int mailedItems, String narrative) {}

    // ── Start ─────────────────────────────────────────────────────────────────

    @Transactional
    public ExpeditionRun start(Player playerArg, ExpeditionSource source, Kingdom kingdom,
                               Zone zone, SkillType skillType, Element element, int tierArg) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        Warrior warrior = warriorRepo.findByPlayer(player)
                .orElseThrow(() -> new IllegalStateException("Warrior not found."));

        if (source == null) throw new IllegalArgumentException("source is required.");
        if (warrior.isKnockedOut())
            throw new com.medieval.game.config.LocalizedException("error.knocked_out",
                    "Your warrior is knocked out. Heal before delving.");
        WorkService.assertNotBusy(workSessionRepository, player); // [WORK_IDLE] não incursiona trabalhando
        if (expeditionRepo.existsByPlayerAndStatusIn(player, ACTIVE))
            throw new com.medieval.game.config.LocalizedException("error.expedition_in_progress",
                    "You already have a Delve in progress.");

        int tier  = Math.max(1, Math.min(3, tierArg));
        int depth = depthForTier(tier);
        if (!instantComplete) playerService.consumeStamina(player, staminaCost(player, tier));

        ExpeditionRun run = new ExpeditionRun();
        run.setPlayer(player);
        run.setWarrior(warrior);
        run.setSource(source);
        run.setKingdom(kingdom);
        run.setZone(zone);
        run.setSkillType(skillType);
        run.setElement(element);
        run.setTier(tier);
        run.setDepth(depth);
        run.setCurrentLayer(0);
        run.setStatus(ExpeditionStatus.IN_PROGRESS);
        // [PVP_FLAG] zona 🟡/🔴: exposto + itens travados durante a run (pode ser saqueado por outro player)
        if (source == ExpeditionSource.ZONE && zone != null && zone != Zone.SAFE) {
            inventoryService.lockExposedItems(player);
            player.setPvpFlaggedZone(zone);
            player.setPvpFlaggedUntil(LocalDateTime.now().plusMinutes(PVP_FLAG_MINUTES));
            playerRepository.save(player);
        }
        ExpeditionRun saved = expeditionRepo.save(run); // precisa do id p/ seed determinístico

        saved.setSeed(saved.getId());
        // [VIP] perk: +1 nó de TESOURO garantido na run (rehome do antigo "+1 daily"). + −20% estamina no staminaCost.
        ExpeditionMapGenerator.Map map = ExpeditionMapGenerator.generate(saved.getId(), depth, tier, player.isVip() ? 1 : 0);
        saved.setMapJson(writeMap(map));
        saved = expeditionRepo.save(saved);
        log.info("[ExpeditionService] player={} START source={} tier={} depth={} runId={}",
                player.getId(), source, tier, depth, saved.getId());
        return saved;
    }

    public ExpeditionRun current(Player player) {
        return expeditionRepo.findFirstByPlayerAndStatusInOrderByStartedAtDesc(player, ACTIVE).orElse(null);
    }

    // ── Acessores p/ o controller (serialização da run/mapa/bolsa) ────────────

    /** Mapa procedural desserializado (p/ a UI renderizar as camadas). [INCURSAO] */
    public ExpeditionMapGenerator.Map mapOf(ExpeditionRun run) { return readMap(run); }

    /** Bolsa carregada (recursos ainda não sacados). */
    public List<ResourceDrop> carriedResourceList(ExpeditionRun run) { return toDropList(run.getCarriedResources()); }

    /** Recursos já garantidos (travados por checkpoint/extração) — ledger informativo. */
    public List<ResourceDrop> securedResourceList(ExpeditionRun run) { return toDropList(run.getSecuredResources()); }

    private static List<ResourceDrop> toDropList(String csv) {
        return parseResources(csv).entrySet().stream()
                .map(e -> new ResourceDrop(e.getKey(), e.getValue())).toList();
    }

    public boolean runCanExtract(ExpeditionRun run) { return canExtract(run); }

    // ── Choose a node ───────────────────────────────────────────────────────────

    @Transactional
    public ChooseResult choose(Player playerArg, Long runId, String nodeId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ExpeditionRun run = requireOwnedRun(player, runId);
        if (run.getStatus() == ExpeditionStatus.NODE_PENDING)
            throw new com.medieval.game.config.LocalizedException("error.expedition_node_pending",
                    "Resolve the current event first.");
        if (run.getCurrentLayer() >= run.getDepth())
            throw new IllegalStateException("The Delve is over — extract your loot.");

        ExpeditionMapGenerator.Map map = readMap(run);
        Layer layer = map.layers().get(run.getCurrentLayer());
        Node node = layer.nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst()
                .orElseThrow(() -> new com.medieval.game.config.LocalizedException(
                        "error.expedition_unreachable", "That node isn't reachable yet."));

        Warrior warrior = warriorRepo.findByPlayer(player).orElseThrow();

        // EVENTO: pausa e devolve o diálogo (resolvido por resolveNode).
        if (node.type() == ExpeditionNodeType.EVENT) {
            String questName = pickEventQuest(run);
            if (questName != null) {
                run.setStatus(ExpeditionStatus.NODE_PENDING);
                run.setPendingNodeId(node.id());
                run.setPendingNodeType(ExpeditionNodeType.EVENT);
                run.setPendingEventQuest(questName);
                expeditionRepo.save(run);
                return new ChooseResult(run, ExpeditionNodeType.EVENT, true, questName,
                        List.of(), null, null, 0, 0, false, List.of(), List.of(),
                        messages.getOr("delve.node.event", "You come upon something that demands a choice."), null, false);
            }
            // sem quest interativa p/ o reino → cai como tesouro
        }

        // CAMP: cura + checkpoint (banca a bolsa).
        if (node.type() == ExpeditionNodeType.CAMP) {
            warrior.healFull();
            warriorRepo.save(warrior);
            ExtractResult banked = bankCarried(run, player, warrior, "Delve camp"); // trava o sacado
            advance(run);
            expeditionRepo.save(run);
            return new ChooseResult(run, ExpeditionNodeType.CAMP, false, null, banked.bankedResources(),
                    null, null, 0, 0, false, List.of(), List.of(),
                    messages.getOr("delve.node.camp", "You make camp. Your wounds close and your haul is secured."), null, canExtract(run));
        }

        NodeResolution res = resolveNonEventNode(run, player, warrior, node);
        return finishNode(run, player, node.type(), res);
    }

    // ── Resolve a pending EVENT node ──────────────────────────────────────────

    @Transactional
    public ChooseResult resolveNode(Player playerArg, Long runId, String optionId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ExpeditionRun run = requireOwnedRun(player, runId);
        if (run.getStatus() != ExpeditionStatus.NODE_PENDING || run.getPendingNodeType() != ExpeditionNodeType.EVENT)
            throw new IllegalStateException("No event to resolve.");
        if (optionId == null || optionId.isBlank())
            throw new IllegalArgumentException("This event requires a choice.");

        Warrior warrior = warriorRepo.findByPlayer(player).orElseThrow();
        KingdomQuestType qt = KingdomQuestType.valueOf(run.getPendingEventQuest());
        QuestDialog dialog = InteractiveQuests.dialogFor(qt).orElseThrow();
        QuestOption option = dialog.options().stream().filter(o -> o.id().equals(optionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid choice."));

        NodeResolution res = resolveOutcome(run, player, warrior, option.outcome());

        // limpa o pending
        run.setStatus(ExpeditionStatus.IN_PROGRESS);
        run.setPendingNodeId(null);
        run.setPendingNodeType(null);
        run.setPendingEventQuest(null);
        return finishNode(run, player, ExpeditionNodeType.EVENT, res);
    }

    /** Aplica a resolução de um nó (não-pending): KO → derrota; senão credita a bolsa + avança. */
    private ChooseResult finishNode(ExpeditionRun run, Player player, ExpeditionNodeType type, NodeResolution res) {
        if (res.ko) {
            handleDefeat(run, player);
            return new ChooseResult(run, type, false, null, List.of(), null, null, 0, 0, true,
                    res.log, res.events, res.narrative, res.monsterName, false);
        }
        applyToCarried(run, res);
        advance(run);
        expeditionRepo.save(run);
        return new ChooseResult(run, type, false, null, res.resources, res.lootName, res.lootId,
                res.bronze, res.xp, false, res.log, res.events, res.narrative, res.monsterName, canExtract(run));
    }

    // ── Node resolution ─────────────────────────────────────────────────────────

    private NodeResolution resolveNonEventNode(ExpeditionRun run, Player player, Warrior warrior, Node node) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return switch (node.type()) {
            case COMBAT, ELITE, BOSS -> resolveBattleNode(run, player, warrior, node);
            case TREASURE -> {
                // baú: chance de armadilha (mini-luta) — desligável p/ testes determinísticos
                if (trapEnabled && rng.nextInt(100) < 25) {
                    yield resolveBattleNode(run, player, warrior, node);
                }
                yield resolveLootOnly(run, player, warrior, node, 90, messages.getOr("delve.node.treasure", "You crack open the chest."));
            }
            default -> resolveLootOnly(run, player, warrior, node, 30, messages.getOr("delve.node.advance", "You press on."));
        };
    }

    /** Luta + loot na vitória. KO se perder. */
    private NodeResolution resolveBattleNode(ExpeditionRun run, Player player, Warrior warrior, Node node) {
        boolean boss = node.type() == ExpeditionNodeType.BOSS;
        int monsterLevel = Math.max(1, warrior.getLevel() + node.monsterLevelBump());
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // [INCURSAO] cura automática antes de CADA batalha nas Incursões de ZONA (pedido do dono):
        // cada luta começa com HP cheio → o gate vira "perder a luta", não o desgaste acumulado.
        if (run.getSource() == ExpeditionSource.ZONE) {
            warrior.healFull();
            warriorRepo.save(warrior);
        }

        int[] stats = statsService.combatStats(player, warrior);
        int maxHp = stats[2];
        int curHp = Math.max(1, warrior.getCalculatedHpPercent() * maxHp / 100);
        // entra com o HP ATUAL (carrega entre os nós da run); o % final usa o maxHp original guardado acima.
        // Seta o HP no array de stats (API estável) em vez de withCurrentHp (que é WIP do [HP_SPAWN]).
        int[] mine = stats.clone();
        mine[2] = curHp;
        // [PVP_FLAG] zona 🟡/🔴: chance de cruzar um player flagged e SAQUEAR (substitui o NPC deste nó)
        if (pvpRaidEnabled && run.getSource() == ExpeditionSource.ZONE && isPvpZone(run.getZone())
                && rng.nextInt(100) < run.getZone().pvpEncounterChance) {
            Player victim = findFlaggedOpponent(run.getZone(), player, warrior.getLevel());
            if (victim != null) return resolvePvpRaid(run, player, warrior, mine, maxHp, victim);
        }
        int[] mob = npcStats(monsterLevel, rng);
        if (boss) { mob[0] = (int) (mob[0] * 1.35); mob[1] = (int) (mob[1] * 1.3); mob[2] = (int) (mob[2] * 1.7); }

        String monsterName = monsterName(run, node, rng);
        var me = BattleSimulator.Combatant.of(warrior.getName(), mine,
                warrior.getActiveWeaponElement(), warrior.getActiveArmorElement(),
                abilityService.activeLoadout(warrior), statsService.isRangedWeaponEquipped(player));
        var foe = BattleSimulator.Combatant.of(monsterName, mob, run.getElement(), run.getElement(), List.of(), false);
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(me, foe, true); // PvE: timeout = derrota

        List<String> lg = new ArrayList<>(out.log());
        if (!lg.isEmpty()) lg.remove(lg.size() - 1); // remove a tag WINNER

        // persiste HP/desgaste
        int finalPct = maxHp > 0 ? Math.max(0, out.firstHpFinal() * 100 / maxHp) : 0;
        warrior.setCurrentHpSnapshot(finalPct);
        warrior.setHpUpdatedAt(LocalDateTime.now());
        inventoryService.wearEquippedItems(player);
        warriorRepo.save(warrior);
        run.setBattleLog(String.join("\n", lg));

        NodeResolution res = new NodeResolution();
        res.log = lg; res.events = out.events(); res.monsterName = monsterName;
        if (!out.firstWon() || warrior.isKnockedOut()) {
            res.ko = true;
            res.narrative = boss ? messages.getOr("delve.node.boss_ko", "The boss overwhelms you. The Delve ends here.")
                                 : messages.getOr("delve.node.ko", "You fall in battle.");
            return res;
        }
        // vitória → recompensa (escala com o nível do monstro + tier; boss = bônus + item garantido)
        double mult = tierMult(run.getTier()) * (boss ? 3.0 : node.type() == ExpeditionNodeType.ELITE ? 1.6 : 1.0);
        grantRewards(run, player, warrior, res, monsterLevel, mult, mult,
                boss ? 100 : node.type() == ExpeditionNodeType.ELITE ? 45 : 25, boss);
        res.narrative = boss ? messages.getOr("delve.node.boss_win", "The boss falls. The deepest chamber is yours.")
                             : messages.getOr("delve.node.victory", "Victory! You loot the fallen.");
        return res;
    }

    /** Loot sem combate (baú/evento pacífico). */
    private NodeResolution resolveLootOnly(ExpeditionRun run, Player player, Warrior warrior, Node node,
                                           int dropChance, String narrative) {
        int level = Math.max(1, warrior.getLevel() + node.monsterLevelBump());
        NodeResolution res = new NodeResolution();
        double t = tierMult(run.getTier());
        grantRewards(run, player, warrior, res, level, t, t, dropChance, false);
        res.narrative = narrative;
        return res;
    }

    /** Resolve um QuestOutcome (Peaceful/Fight/Check d20) creditando à bolsa. [QUESTS_INTERATIVAS] */
    private NodeResolution resolveOutcome(ExpeditionRun run, Player player, Warrior warrior, QuestOutcome outcome) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        if (outcome instanceof QuestOutcome.Peaceful p) {
            NodeResolution res = new NodeResolution();
            int level = Math.max(1, warrior.getLevel());
            double t = tierMult(run.getTier());
            grantRewards(run, player, warrior, res, level, t * p.bronzeMult(), t * p.xpMult(), p.dropChance(), false);
            res.narrative = p.narrative();
            return res;
        }
        if (outcome instanceof QuestOutcome.Fight) {
            // luta usando o monstro temático do reino, nível do jogador
            Node pseudo = new Node("event", ExpeditionNodeType.COMBAT, 0);
            NodeResolution res = resolveBattleNode(run, player, warrior, pseudo);
            return res;
        }
        if (outcome instanceof QuestOutcome.Check c) {
            int mod = attrValue(warrior, c.attr()) / 4;
            int d20 = rng.nextInt(20) + 1;
            boolean passed = d20 == 20 || (d20 != 1 && d20 + mod >= c.dc());
            return resolveOutcome(run, player, warrior, passed ? c.onSuccess() : c.onFail());
        }
        throw new IllegalStateException("Unknown outcome.");
    }

    /** Credita bronze/xp + (KINGDOM) gear carregado ou (ZONE) recursos carregados ao {@link NodeResolution}. */
    // bronzeMult/xpMult separados → o evento Peaceful escala bronze e xp por multiplicadores distintos
    // (sem double-rounding). Nós de combate/loot passam o mesmo valor pros dois.
    private void grantRewards(ExpeditionRun run, Player player, Warrior warrior, NodeResolution res,
                              int level, double bronzeMult, double xpMult, int dropChance, boolean boss) {
        res.bronze += Math.round(level * 8 * bronzeMult);
        res.xp     += Math.round(level * 7 * xpMult);
        // [INCURSAO] gear nas DUAS fontes: KINGDOM = foco; ZONE também solta equipamento (baú/elite/chefe).
        InventoryItem gear = rollGear(run, player, warrior, dropChance, level, boss);
        if (gear != null) { res.lootName = gear.getName(); res.lootId = gear.getId(); }
        if (run.getSource() == ExpeditionSource.ZONE) {
            res.resources = rollZoneResources(run, level, boss);
            // coleta na zona sobe a PROFISSÃO (não regride o leveling de skill). XP de skill é aplicado
            // já (não fica "em risco" na bolsa) — progressão de profissão é segura.
            if (run.getSkillType() != null && res.xp > 0) {
                var skill = gatheringService.getOrCreateSkill(player, run.getSkillType());
                gatheringService.addSkillXp(skill, (int) res.xp);
            }
        }
    }

    // ── Gear (KINGDOM) ────────────────────────────────────────────────────────

    private InventoryItem rollGear(ExpeditionRun run, Player player, Warrior warrior,
                                   int dropChance, int dropLevel, boolean guaranteed) {
        var rng = ThreadLocalRandom.current();
        int luck = warrior.getLuck();
        if (!guaranteed && rng.nextInt(100) >= dropChance + luck) return null;

        int rarity = guaranteed
                ? (rng.nextInt(100) < 25 ? 5 : rng.nextInt(100) < 67 ? 4 : 3) // boss: ~25% Lendário/40% Épico/35% Raro
                : dropChance >= 60 ? (rng.nextInt(100) < 5 ? 5 : (rng.nextBoolean() ? 3 : 4))
                : dropChance >= 40 ? (rng.nextBoolean() ? 2 : 3)
                : dropChance >= 25 ? (rng.nextBoolean() ? 1 : 2)
                : 1;

        ItemType type = ItemType.values()[rng.nextInt(ItemType.values().length)];
        int itemLevel = Math.max(1, dropLevel);
        int[] s = inventoryService.rollItemStats(itemLevel, rarity);
        long price = switch (rarity) { case 2 -> 150L; case 3 -> 400L; case 4 -> 1000L; case 5 -> 2500L; default -> 25L; };
        boolean isArcher = warrior.getWarriorClass() == WarriorClass.ARCHER;
        String name   = itemName(type, rarity, isArcher, rng);
        String lore   = loreGenerator.generateLore(rarity, type, rng);
        String origin = loreGenerator.originFromQuest("Delve");
        // [INCURSAO] gear vai p/ a bolsa (runPending) — não ocupa a bag até o extract/checkpoint.
        return inventoryService.makeRunPending(player, name, type, s[0], s[1], s[2], rarity, price, itemLevel, lore, origin);
    }

    private String itemName(ItemType type, int rarity, boolean isArcher, java.util.Random rng) {
        String[] bases = switch (type) {
            case HELMET   -> new String[]{"Helm", "Helmet"};
            case ARMOR    -> new String[]{"Armor", "Breastplate"};
            case WEAPON   -> isArcher ? new String[]{"Short Bow", "Long Bow", "Crossbow"}
                                      : new String[]{"Sword", "Greatsword", "Axe", "Spear"};
            case SHIELD   -> new String[]{"Shield", "Buckler"};
            case BOOTS    -> new String[]{"Boots", "Greaves"};
            case GLOVES   -> new String[]{"Gloves", "Gauntlets"};
            case PANTS    -> new String[]{"Pants", "Leggings"};
            case SHOULDER -> new String[]{"Shoulder", "Pauldron"};
            case NECKLACE -> new String[]{"Necklace", "Amulet"};
            case RING     -> new String[]{"Ring", "Signet"};
        };
        String[] suffixes = switch (rarity) {
            case 2 -> new String[]{"of Steel", "of Chainmail", "of Silver"};
            case 3 -> new String[]{"of the Elves", "of the Warrior", "Enchanted"};
            case 4 -> new String[]{"of the Dragon", "Cursed", "of Valor"};
            case 5 -> new String[]{"of the Ancients", "Mythic", "of Eternity"};
            default -> new String[]{"of Iron", "of Leather", "of Wood"};
        };
        // [I18N_ITENS] traduz base + sufixo pro locale do request (itemword.* em messages_pt). EN/teste → inglês.
        return Messages.word(bases[rng.nextInt(bases.length)]) + " " + Messages.word(suffixes[rng.nextInt(suffixes.length)]);
    }

    // ── Recursos (ZONE) ───────────────────────────────────────────────────────

    private List<ResourceDrop> rollZoneResources(ExpeditionRun run, int level, boolean boss) {
        var rng = ThreadLocalRandom.current();
        List<ResourceDrop> drops = new ArrayList<>();
        drops.add(new ResourceDrop(ResourceType.MONSTER_CORE, 1 + rng.nextInt(2) + (boss ? 2 : 0)));
        if (rng.nextInt(100) < 30) drops.add(new ResourceDrop(ResourceType.BEAST_HIDE, 1 + (boss ? 1 : 0)));
        if (run.getElement() != null && rng.nextInt(100) < 40)
            drops.add(new ResourceDrop(essenceFor(run.getElement()), 1));
        if (run.getSkillType() != null)
            drops.add(new ResourceDrop(resourceForSkill(run.getSkillType(), run.getTier()), 1 + rng.nextInt(2) + (boss ? 2 : 0)));
        return drops;
    }

    private static ResourceType essenceFor(Element e) {
        return switch (e) {
            case FIRE -> ResourceType.FIRE_ESSENCE;
            case WATER -> ResourceType.WATER_ESSENCE;
            case EARTH -> ResourceType.EARTH_ESSENCE;
            case AIR -> ResourceType.AIR_ESSENCE;
        };
    }

    private static ResourceType resourceForSkill(SkillType skill, int tier) {
        return switch (skill) {
            case FISHING  -> tier >= 3 ? ResourceType.TUNA : tier == 2 ? ResourceType.SALMON : ResourceType.SMALL_FISH;
            case MINING   -> tier >= 3 ? ResourceType.SILVER_ORE : tier == 2 ? ResourceType.IRON_ORE : ResourceType.COPPER_ORE;
            case GARIMPO  -> tier >= 3 ? ResourceType.EMERALD_FRAGMENT : tier == 2 ? ResourceType.SAPPHIRE_FRAGMENT : ResourceType.AMETHYST_FRAGMENT;
            case SMITHING -> ResourceType.IRON_BAR;
        };
    }

    // ── Carried bag / banking ─────────────────────────────────────────────────

    private void applyToCarried(ExpeditionRun run, NodeResolution res) {
        run.setCarriedBronze(run.getCarriedBronze() + res.bronze);
        run.setCarriedXp(run.getCarriedXp() + res.xp);
        if (res.resources != null && !res.resources.isEmpty()) {
            Map<ResourceType, Long> bag = parseResources(run.getCarriedResources());
            for (ResourceDrop d : res.resources) bag.merge(d.type(), d.quantity(), Long::sum);
            run.setCarriedResources(writeResources(bag));
        }
    }

    /** Banca tudo que está carregado: gear (limpa runPending / mail se cheio), recursos, bronze, xp. */
    private ExtractResult bankCarried(ExpeditionRun run, Player player, Warrior warrior, String reason) {
        int kept = 0, mailed = 0;
        for (InventoryItem it : inventoryService.runPendingItems(player)) {
            if (inventoryService.bagSpaceLeft(player) >= 1) {
                inventoryService.clearRunPending(it);
                kept++;
            } else {
                mailService.sendItemMail(player, reason + " (bag full)", it.getName(), it.getType(),
                        it.getAttackBonus(), it.getDefenseBonus(), it.getHealthBonus(),
                        it.getRarity(), it.getItemLevel(), it.getSockets(), it.getDescription(), it.getOrigin());
                inventoryService.discardRunItem(it);
                mailed++;
            }
        }

        List<ResourceDrop> bankedRes = new ArrayList<>();
        Map<ResourceType, Long> bag = parseResources(run.getCarriedResources());
        for (Map.Entry<ResourceType, Long> e : bag.entrySet()) {
            long qty = e.getValue();
            long added = gatheringService.addResource(player, e.getKey(), qty);
            if (added < qty) mailService.sendResourceMail(player, reason + " (bag full)", e.getKey(), (int) (qty - added));
            bankedRes.add(new ResourceDrop(e.getKey(), qty));
        }

        long bronze = run.getCarriedBronze();
        long xp     = run.getCarriedXp();
        if (bronze > 0) { playerService.addGold(player, bronze); playerRepository.save(player); }
        if (xp > 0)     warriorService.addExperience(warrior, xp);

        // move carregado → garantido (ledger informativo p/ a UI)
        run.setSecuredBronze(run.getSecuredBronze() + bronze);
        run.setSecuredXp(run.getSecuredXp() + xp);
        Map<ResourceType, Long> secured = parseResources(run.getSecuredResources());
        for (Map.Entry<ResourceType, Long> e : bag.entrySet()) secured.merge(e.getKey(), e.getValue(), Long::sum);
        run.setSecuredResources(writeResources(secured));
        run.setCarriedBronze(0);
        run.setCarriedXp(0);
        run.setCarriedResources(null);
        return new ExtractResult(run, bronze, xp, bankedRes, kept, mailed, "Loot secured.");
    }

    // ── Extract / Abandon ─────────────────────────────────────────────────────

    @Transactional
    public ExtractResult extract(Player playerArg, Long runId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ExpeditionRun run = requireOwnedRun(player, runId);
        if (run.getStatus() == ExpeditionStatus.NODE_PENDING)
            throw new com.medieval.game.config.LocalizedException("error.expedition_node_pending",
                    "Resolve the current event first.");
        Warrior warrior = warriorRepo.findByPlayer(player).orElseThrow();
        ExtractResult banked = bankCarried(run, player, warrior, "Delve loot");
        run.setStatus(ExpeditionStatus.COMPLETED);
        run.setResolvedAt(LocalDateTime.now());
        // [PVP_FLAG] extrair de uma zona 🟡/🔴 flagga o jogador
        if (run.getSource() == ExpeditionSource.ZONE && run.getZone() != null && run.getZone() != Zone.SAFE) {
            player.setPvpFlaggedZone(run.getZone());
            player.setPvpFlaggedUntil(LocalDateTime.now().plusMinutes(PVP_FLAG_MINUTES));
            playerRepository.save(player);
        }
        expeditionRepo.save(run);
        log.info("[ExpeditionService] player={} EXTRACT runId={} bronze={} xp={} kept={} mailed={}",
                player.getId(), runId, banked.bronzeBanked(), banked.xpBanked(), banked.keptItems(), banked.mailedItems());
        return new ExtractResult(run, banked.bronzeBanked(), banked.xpBanked(), banked.bankedResources(),
                banked.keptItems(), banked.mailedItems(), messages.getOr("delve.extract", "You extract from the Delve, loot in hand."));
    }

    @Transactional
    public ExtractResult abandon(Player playerArg, Long runId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ExpeditionRun run = requireOwnedRun(player, runId);
        // [STUCK_FIX] abandono é a SAÍDA DE EMERGÊNCIA: funciona em QUALQUER estado ativo,
        // inclusive NODE_PENDING. Antes rejeitava NODE_PENDING ("can't abandon mid-event") —
        // mas extract também rejeita NODE_PENDING, e o modal de evento só abre se o /current
        // devolve um diálogo válido (pendingEventQuest != null E mapeia p/ KingdomQuestType com
        // InteractiveQuests). Se esse elo quebrava, a run ficava PRESA pra sempre (sem resolver,
        // sem extrair, sem abandonar). Abandonar daqui sempre encerra a run; forfeita a bolsa
        // não-travada, sem KO.
        for (InventoryItem it : inventoryService.runPendingItems(player)) inventoryService.discardRunItem(it);
        run.setCarriedBronze(0); run.setCarriedXp(0); run.setCarriedResources(null);
        run.setStatus(ExpeditionStatus.ABANDONED);
        run.setResolvedAt(LocalDateTime.now());
        expeditionRepo.save(run);
        log.info("[ExpeditionService] player={} ABANDON runId={}", player.getId(), runId);
        return new ExtractResult(run, 0, 0, List.of(), 0, 0, messages.getOr("delve.abandon", "You leave the Delve empty-handed."));
    }

    /** KO no meio da run: perde a bolsa não-travada + KO (HP já zerado na batalha). */
    private void handleDefeat(ExpeditionRun run, Player player) {
        for (InventoryItem it : inventoryService.runPendingItems(player)) inventoryService.discardRunItem(it);
        run.setCarriedBronze(0); run.setCarriedXp(0); run.setCarriedResources(null);
        run.setStatus(ExpeditionStatus.DEFEATED);
        run.setResolvedAt(LocalDateTime.now());
        expeditionRepo.save(run);
        log.info("[ExpeditionService] player={} DEFEATED runId={}", player.getId(), run.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private ExpeditionRun requireOwnedRun(Player player, Long runId) {
        ExpeditionRun run = expeditionRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Delve not found."));
        if (!run.getPlayer().getId().equals(player.getId()))
            throw new IllegalStateException("This Delve does not belong to you.");
        if (!run.isActive())
            throw new IllegalStateException("This Delve is over.");
        return run;
    }

    private void advance(ExpeditionRun run) {
        run.setCurrentLayer(run.getCurrentLayer() + 1);
    }

    /** Pode extrair sempre que está numa parada segura (run ativa, sem decisão pendente). */
    private boolean canExtract(ExpeditionRun run) {
        return run.getStatus() == ExpeditionStatus.IN_PROGRESS;
    }

    private int depthForTier(int tier) {
        return switch (tier) { case 3 -> 5; case 2 -> 4; default -> 3; };
    }

    private int staminaCost(Player player, int tier) {
        int base = 8 + (tier - 1) * 6; // 8/14/20
        int cost = playerService.discountStamina(player, base);
        if (player.isVip()) cost = (int) Math.round(cost * 0.8); // [VIP] −20%
        return Math.max(1, cost);
    }

    private double tierMult(int tier) { return 1.0 + (tier - 1) * 0.6; } // 1.0/1.6/2.2

    /** Stats do NPC por nível (replica ZoneService.npcStatsByLevel; private lá). [REBALANCE] */
    private int[] npcStats(int level, java.util.Random rng) {
        // [TUNING] nerf da dificuldade (mobs estavam duros): menos ATK/HP e bem menos ACERTO (dex → erra mais).
        int atk = 2 + (level * 3) / 2 + rng.nextInt(2);
        int def = 1 + level + rng.nextInt(2);
        int hp  = 40 + level * 9 + rng.nextInt(15);
        int dex = Math.min(7 + level / 3, 22);
        int agi = Math.min(level / 6, 8);
        int luk = Math.min(level / 4, 8);
        return new int[]{atk, def, hp, dex, agi, luk};
    }

    // ── [PVP_FLAG] Raid PvP nas zonas 🟡/🔴 (saque por outro player) ──────────────────────────────
    // Self-contained (não toca o ZoneService, que é WIP do dono): só APIs commitadas; combate via
    // Combatant.of com o HP no array de stats (sem withCurrentHp). Espelha ZoneService.resolveEncounters.

    private static boolean isPvpZone(Zone z) { return z == Zone.PVP || z == Zone.HIGH_RISK; }

    /** Sorteia um player FLAGGED (exposto, sem escudo) na zona, dentro de ±PVP_LEVEL_BAND níveis. */
    private Player findFlaggedOpponent(Zone zone, Player exclude, int attackerLevel) {
        List<Player> pool = playerRepository.findFlaggedInZone(zone, LocalDateTime.now(), exclude.getId())
                .stream()
                .filter(p -> !p.isPvpShielded())
                .filter(p -> Math.abs(attackerLevel
                        - warriorRepo.findByPlayer(p).map(Warrior::getLevel).orElse(1)) <= PVP_LEVEL_BAND)
                .toList();
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    /** Combate PvP contra um flagged: vitória → saqueia (loot imediato); derrota → KO (run encerra). */
    private NodeResolution resolvePvpRaid(ExpeditionRun run, Player player, Warrior warrior,
                                          int[] mine, int maxHp, Player victim) {
        NodeResolution res = new NodeResolution();
        Warrior victimW = warriorRepo.findByPlayer(victim).orElse(null);
        if (victimW == null) { res.narrative = "Your quarry slipped away."; return res; }

        int[] vStats = statsService.combatStats(victim, victimW);
        int vMaxHp = vStats[2];
        int[] vMine = vStats.clone();
        vMine[2] = Math.max(1, victimW.getCalculatedHpPercent() * vMaxHp / 100);

        var me = BattleSimulator.Combatant.of(warrior.getName(), mine,
                warrior.getActiveWeaponElement(), warrior.getActiveArmorElement(),
                abilityService.activeLoadout(warrior), statsService.isRangedWeaponEquipped(player));
        var foe = BattleSimulator.Combatant.of(victimW.getName() + " (player)", vMine,
                victimW.getActiveWeaponElement(), victimW.getActiveArmorElement(),
                abilityService.activeLoadout(victimW), statsService.isRangedWeaponEquipped(victim));
        BattleSimulator.BattleOutcome out = battleSimulator.simulate(me, foe, false); // PvP: desempate por %HP

        List<String> lg = new ArrayList<>(out.log());
        if (!lg.isEmpty()) lg.remove(lg.size() - 1);
        res.log = lg; res.events = out.events(); res.monsterName = victimW.getName() + " (player)";
        run.setBattleLog(String.join("\n", lg));

        int vPct = vMaxHp > 0 ? Math.max(0, out.secondHpFinal() * 100 / vMaxHp) : 0;
        victimW.setCurrentHpSnapshot(vPct);
        victimW.setHpUpdatedAt(LocalDateTime.now());

        if (out.firstWon() && !warrior.isKnockedOut()) {
            res.narrative = raidVictim(player, warrior, victim, victimW, run.getZone(), lg);
        } else {
            res.ko = true;
            res.narrative = "You were beaten by " + victimW.getName() + " (player).";
        }
        warriorRepo.save(victimW);

        int aPct = maxHp > 0 ? Math.max(0, out.firstHpFinal() * 100 / maxHp) : 0;
        warrior.setCurrentHpSnapshot(aPct);
        warrior.setHpUpdatedAt(LocalDateTime.now());
        inventoryService.wearEquippedItems(player);
        warriorRepo.save(warrior);
        return res;
    }

    /** Saqueia a vítima por TIER (🟡 bronze+XP; 🔴 +recursos+1 item travado). Loot imediato p/ o atacante. */
    private String raidVictim(Player attacker, Warrior attackerW, Player victim, Warrior victimW, Zone zone, List<String> log) {
        boolean red = zone == Zone.HIGH_RISK;
        long bronze = applyDefeatPenaltyTo(victim, attacker, red ? 0.15 : 0.10);
        long stolenRes = red ? stealResources(attacker, victim) : 0;
        String stolenItem = red ? inventoryService.stealOnePvpLockedItem(victim, attacker) : null;
        long xpLost = stealXp(victimW, attackerW);
        victimW.clearBuff();
        victim.setPvpShieldUntil(LocalDateTime.now().plusMinutes(PVP_SHIELD_MINUTES)); // saqueado 1x por ciclo
        victim.clearPvpFlag();
        inventoryService.unlockAllItems(victim);
        playerRepository.save(victim);
        String loot = bronze + " bronze"
                + (stolenItem != null ? ", " + stolenItem : "")
                + (stolenRes > 0 ? ", " + stolenRes + " resources" : "")
                + (xpLost > 0 ? ", " + xpLost + " XP" : "");
        log.add("💰 You raided " + victimW.getName() + "! Stole " + loot + ".");
        mailService.sendSystemMail(victim, "💀 You were RAIDED by " + attackerW.getName()
                + " in the " + zone.displayName + "! Lost " + loot + ". Shield " + PVP_SHIELD_MINUTES + " min.");
        return "You raided " + victimW.getName() + "! Stole " + loot + ".";
    }

    private long applyDefeatPenaltyTo(Player loser, Player winner, double pct) {
        long lost = Math.round(loser.totalBronze() * pct);
        if (lost > 0) {
            loser.addBronzeAmount(-lost);
            playerRepository.save(loser);
            if (winner != null) { winner.addBronzeAmount(lost / 2); playerRepository.save(winner); }
        }
        return lost;
    }

    private long stealResources(Player attacker, Player victim) {
        long total = 0;
        for (com.medieval.game.model.ResourceInventory r : resourceRepo.findAllByPlayerAndStashed(victim, false)) {
            if (r.getQuantity() <= 0) continue;
            if (inventoryService.resourceSpaceLeft(attacker) <= 0) break;
            long take = Math.max(1, r.getQuantity() / 2);
            long added = gatheringService.addResource(attacker, r.getResourceType(), take);
            if (added > 0) { r.setQuantity(r.getQuantity() - added); resourceRepo.save(r); total += added; }
        }
        return total;
    }

    private long stealXp(Warrior victimW, Warrior attackerW) {
        long xpLost = Math.max(1, victimW.expNeededForNextLevel() / 20);
        warriorService.loseXp(victimW, xpLost);
        long gain = Math.min(xpLost / 2, Math.max(1, attackerW.expNeededForNextLevel() / 10));
        if (gain > 0) warriorService.addExperience(attackerW, gain);
        return xpLost;
    }

    /** [TESTE] Aplica um raid direto (sem o roll de encontro RNG) — exercita o saque determinístico. */
    @Transactional
    public String raidForTest(Long attackerId, Long victimId, Zone zone) {
        Player attacker = playerRepository.findById(attackerId).orElseThrow();
        Player victim   = playerRepository.findById(victimId).orElseThrow();
        Warrior aw = warriorRepo.findByPlayer(attacker).orElseThrow();
        Warrior vw = warriorRepo.findByPlayer(victim).orElseThrow();
        String loot = raidVictim(attacker, aw, victim, vw, zone, new ArrayList<>());
        warriorRepo.save(vw); warriorRepo.save(aw);
        return loot;
    }

    private String monsterName(ExpeditionRun run, Node node, java.util.Random rng) {
        if (run.getKingdom() != null) return narrator.pickMonster(run.getKingdom(), rng);
        return node.type() == ExpeditionNodeType.BOSS ? "Delve Horror" : "Wandering Beast";
    }

    /** Escolhe um KingdomQuestType interativo do reino da run p/ alimentar um nó EVENTO. */
    private String pickEventQuest(ExpeditionRun run) {
        Kingdom k = run.getKingdom();
        if (k == null) return null;
        List<KingdomQuestType> pool = new ArrayList<>();
        for (KingdomQuestType qt : KingdomQuestType.values())
            if (qt.kingdom == k && InteractiveQuests.isInteractive(qt) && qt != KingdomQuestType.RESCUE_STRAY_DOG)
                pool.add(qt);
        if (pool.isEmpty()) return null;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size())).name();
    }

    private static int attrValue(Warrior w, Attribute a) {
        return switch (a) {
            case STRENGTH     -> w.getStrength();
            case DEXTERITY    -> w.getDexterity();
            case CONSTITUTION -> w.getConstitution();
            case AGILITY      -> w.getAgility();
            case LUCK         -> w.getLuck();
            case INTELLECT    -> w.getIntellect();
        };
    }

    // ── Map (de)serialização ──────────────────────────────────────────────────

    private String writeMap(ExpeditionMapGenerator.Map map) {
        try { return objectMapper.writeValueAsString(map); }
        catch (Exception e) { throw new IllegalStateException("Failed to serialize Delve map", e); }
    }

    private ExpeditionMapGenerator.Map readMap(ExpeditionRun run) {
        try { return objectMapper.readValue(run.getMapJson(), ExpeditionMapGenerator.Map.class); }
        catch (Exception e) { throw new IllegalStateException("Failed to read Delve map", e); }
    }

    // ── CSV de recursos ("TYPE:qty,TYPE:qty") ─────────────────────────────────

    static Map<ResourceType, Long> parseResources(String csv) {
        Map<ResourceType, Long> m = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) return m;
        for (String part : csv.split(",")) {
            String[] kv = part.split(":");
            if (kv.length == 2) {
                try { m.merge(ResourceType.valueOf(kv[0].trim()), Long.parseLong(kv[1].trim()), Long::sum); }
                catch (IllegalArgumentException ignored) { /* tipo removido do enum → ignora */ }
            }
        }
        return m;
    }

    static String writeResources(Map<ResourceType, Long> m) {
        if (m == null || m.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ResourceType, Long> e : m.entrySet()) {
            if (e.getValue() <= 0) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey().name()).append(':').append(e.getValue());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Acumulador mutável da resolução de um nó. */
    private static final class NodeResolution {
        long bronze = 0, xp = 0;
        List<ResourceDrop> resources = List.of();
        String lootName = null; Long lootId = null;
        boolean ko = false;
        List<String> log = List.of();
        List<BattleSimulator.BattleEvent> events = List.of();
        String narrative = "";
        String monsterName = null;
    }
}
