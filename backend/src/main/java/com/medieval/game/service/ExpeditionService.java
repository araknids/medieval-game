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
    private static final int PVP_FLAG_MINUTES = 60; // [PVP_FLAG] flagga ao extrair de zona 🟡/🔴

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
    private final MailService             mailService;
    private final ItemLoreGenerator       loreGenerator;
    private final KingdomQuestNarrator    narrator;
    private final WorkSessionRepository   workSessionRepository;
    private final ObjectMapper            objectMapper;

    @Value("${app.dev.instant-complete:false}")
    private boolean instantComplete;

    // [INCURSAO] Armadilha de baú liga/desliga (FALSE nos testes p/ TREASURE determinístico).
    @Value("${app.expedition.trap-enabled:true}")
    private boolean trapEnabled;

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
        ExpeditionRun saved = expeditionRepo.save(run); // precisa do id p/ seed determinístico

        saved.setSeed(saved.getId());
        ExpeditionMapGenerator.Map map = ExpeditionMapGenerator.generate(saved.getId(), depth, tier);
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
                        "You come upon something that demands a choice.", null, false);
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
                    "You make camp. Your wounds close and your haul is secured.", null, canExtract(run));
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
                yield resolveLootOnly(run, player, warrior, node, 90, "You crack open the chest.");
            }
            default -> resolveLootOnly(run, player, warrior, node, 30, "You press on.");
        };
    }

    /** Luta + loot na vitória. KO se perder. */
    private NodeResolution resolveBattleNode(ExpeditionRun run, Player player, Warrior warrior, Node node) {
        boolean boss = node.type() == ExpeditionNodeType.BOSS;
        int monsterLevel = Math.max(1, warrior.getLevel() + node.monsterLevelBump());
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        int[] stats = statsService.combatStats(player, warrior);
        int maxHp = stats[2];
        int curHp = Math.max(1, warrior.getCalculatedHpPercent() * maxHp / 100);
        int[] mob = npcStats(monsterLevel, rng);
        if (boss) { mob[0] = (int) (mob[0] * 1.5); mob[1] = (int) (mob[1] * 1.5); mob[2] = mob[2] * 2; }

        String monsterName = monsterName(run, node, rng);
        var me = BattleSimulator.Combatant.of(warrior.getName(), stats,
                warrior.getActiveWeaponElement(), warrior.getActiveArmorElement(),
                abilityService.activeLoadout(warrior), statsService.isRangedWeaponEquipped(player)).withCurrentHp(curHp);
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
            res.narrative = boss ? "The boss overwhelms you. The Delve ends here." : "You fall in battle.";
            return res;
        }
        // vitória → recompensa (escala com o nível do monstro + tier; boss = bônus + item garantido)
        double mult = tierMult(run.getTier()) * (boss ? 3.0 : node.type() == ExpeditionNodeType.ELITE ? 1.6 : 1.0);
        grantRewards(run, player, warrior, res, monsterLevel, mult, mult,
                boss ? 100 : node.type() == ExpeditionNodeType.ELITE ? 45 : 25, boss);
        res.narrative = boss ? "The boss falls. The deepest chamber is yours." : "Victory! You loot the fallen.";
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
        if (run.getSource() == ExpeditionSource.ZONE) {
            res.resources = rollZoneResources(run, level, boss);
        } else {
            InventoryItem gear = rollGear(run, player, warrior, dropChance, level, boss);
            if (gear != null) { res.lootName = gear.getName(); res.lootId = gear.getId(); }
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
        return bases[rng.nextInt(bases.length)] + " " + suffixes[rng.nextInt(suffixes.length)];
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
                banked.keptItems(), banked.mailedItems(), "You extract from the Delve, loot in hand.");
    }

    @Transactional
    public ExtractResult abandon(Player playerArg, Long runId) {
        final Player player = playerRepository.findById(playerArg.getId()).orElse(playerArg);
        ExpeditionRun run = requireOwnedRun(player, runId);
        if (run.getStatus() == ExpeditionStatus.NODE_PENDING)
            throw new com.medieval.game.config.LocalizedException("error.expedition_node_pending",
                    "You can't abandon mid-event.");
        // perde a bolsa NÃO-travada (gear carregado + escalares/CSV), sem KO
        for (InventoryItem it : inventoryService.runPendingItems(player)) inventoryService.discardRunItem(it);
        run.setCarriedBronze(0); run.setCarriedXp(0); run.setCarriedResources(null);
        run.setStatus(ExpeditionStatus.ABANDONED);
        run.setResolvedAt(LocalDateTime.now());
        expeditionRepo.save(run);
        log.info("[ExpeditionService] player={} ABANDON runId={}", player.getId(), runId);
        return new ExtractResult(run, 0, 0, List.of(), 0, 0, "You leave the Delve empty-handed.");
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
        int atk = 3 + level * 2 + rng.nextInt(3);
        int def = 2 + (level * 3) / 2 + rng.nextInt(2);
        int hp  = 50 + level * 13 + rng.nextInt(20);
        int dex = Math.min(10 + level / 2, 35);
        int agi = Math.min(level / 5, 12);
        int luk = Math.min(level / 3, 10);
        return new int[]{atk, def, hp, dex, agi, luk};
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
